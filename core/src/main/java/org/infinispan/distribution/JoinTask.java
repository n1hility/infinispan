/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.distribution;

import org.infinispan.CacheException;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.control.RehashControlCommand;
import org.infinispan.config.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.NodeTopologyInfo;
import org.infinispan.remoting.InboundInvocationHandler;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.NotifyingFutureImpl;
import org.infinispan.util.concurrent.TimeoutException;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Future;

import static org.infinispan.commands.control.RehashControlCommand.Type.*;
import static org.infinispan.distribution.ch.ConsistentHashHelper.createConsistentHash;
import static org.infinispan.remoting.rpc.ResponseMode.SYNCHRONOUS;

/**
 * JoinTask: This is a PULL based rehash.  JoinTask is kicked off on the JOINER.  Please see detailed designs on
 * http://community.jboss.org/wiki/DesignOfDynamicRehashing
 *
 * @author Manik Surtani
 * @author Mircea.Markus@jboss.com
 * @since 4.0
 */
public class JoinTask extends RehashTask {

   private final InboundInvocationHandler inboundInvocationHandler;
   protected ConsistentHash chOld, chNew;

   public JoinTask(RpcManager rpcManager, CommandsFactory commandsFactory, Configuration conf,
                   DataContainer dataContainer, DistributionManagerImpl dmi, InboundInvocationHandler inboundInvocationHandler) {
      super(dmi, rpcManager, conf, commandsFactory, dataContainer);
      this.inboundInvocationHandler = inboundInvocationHandler;
   }

   @SuppressWarnings("unchecked")
   private Set<Address> parseResponses(Collection<Response> resp) {
      for (Response r : resp) {
         if (r instanceof SuccessfulResponse) {
            return (Set<Address>) ((SuccessfulResponse) r).getResponseValue();
         }
      }
      return null;
   }

   protected void getPermissionToJoin() throws Exception {
      if (distributionManager.isJoinComplete()) {
         throw new IllegalStateException("Join on " + getMyAddress() + " cannot be complete without rehash to finishing");
      }
      // 1.  Get chOld from coord.
      chOld = retrieveOldConsistentHash();

      // 2.  new CH instance
      if (chOld.getCaches().contains(self))
         chNew = chOld;
      else
         chNew = createConsistentHash(configuration, chOld.getCaches(), distributionManager.getTopologyInfo(), self);
   }

   protected void signalJoinRehashEnd() {
      rpcManager.broadcastRpcCommandInFuture(cf.buildRehashControlCommand(JOIN_REHASH_END, self), true, new NotifyingFutureImpl(null));
   }

   protected void performRehash() throws Exception {
      long start = System.currentTimeMillis();
      if (log.isDebugEnabled())
         log.debugf("Commencing rehash on node: %s. Before start, distributionManager.joinComplete = %s", getMyAddress(), distributionManager.isJoinComplete());
      boolean cleanup = false;
      boolean aborted = false;
      try {
         getPermissionToJoin();
         // After this point, if we fail, we need to inform the cluster of any failures we may see.
         cleanup = true;

         distributionManager.setConsistentHash(chNew);
         if (configuration.isRehashEnabled()) {
            // Broadcast new temp CH
            broadcastNewConsistentHash();

            // pull state from everyone.
            Address myAddress = rpcManager.getTransport().getAddress();

            RehashControlCommand cmd = cf.buildRehashControlCommand(PULL_STATE_JOIN, myAddress, null, chOld, chNew, null);

            List<Address> addressesWhoMaySendStuff = getAddressesWhoMaySendStuff(chNew, configuration.getNumOwners());
            Set<Future<Void>> stateRetrievalProcesses = new HashSet<Future<Void>>(addressesWhoMaySendStuff.size());

            // This process happens in parallel
            for (Address stateProvider : addressesWhoMaySendStuff) {
               stateRetrievalProcesses.add(
                     statePullExecutor.submit(new JoinStateGrabber(stateProvider, cmd, chNew))
               );
            }

            // Wait for all the state retrieval tasks to complete
            for (Future<Void> f : stateRetrievalProcesses) f.get();

         } else {
            broadcastNewConsistentHash();
            if (trace) log.trace("Rehash not enabled, so not pulling state.");
         }
      } catch (Exception e) {
         log.abortingJoin(e);
         broadcastAbort(cleanup);
         aborted = true;
         throw new CacheException("Unexpected exception", e);
      } finally {
         // wait for any enqueued remote commands to finish...
         distributionManager.setJoinComplete(true);
         distributionManager.setRehashInProgress(false);
         inboundInvocationHandler.blockTillNoLongerRetrying(cf.getCacheName());
         if (!aborted) {
            signalJoinRehashEnd();
            if (configuration.isRehashEnabled()) invalidateInvalidHolders(chOld, chNew);
            log.joinRehashCompleted(self, Util.prettyPrintTime(System.currentTimeMillis() - start));
         } else {
            log.joinRehashAborted(self, Util.prettyPrintTime(System.currentTimeMillis() - start));
         }
      }
   }

   private void broadcastAbort(boolean cleanup) {
      if (cleanup) {
         RehashControlCommand abortCommand = cf.buildRehashControlCommand(JOIN_ABORT, self);
         rpcManager.broadcastRpcCommand(abortCommand, false, true);
      }
   }

   protected void broadcastNewConsistentHash() {
      RehashControlCommand rehashControlCommand = cf.buildRehashControlCommand(JOIN_REHASH_START, self);
      rehashControlCommand.setNodeTopologyInfo(distributionManager.getTopologyInfo().getNodeTopologyInfo(rpcManager.getAddress()));
      Map<Address, Response> responses = rpcManager.invokeRemotely(null, rehashControlCommand, true, true);
      updateTopologyInfo(responses.values());
   }

   private void updateTopologyInfo(Collection<Response> responses) {
      for (Response r : responses) {
         if (r instanceof SuccessfulResponse) {
            SuccessfulResponse sr = (SuccessfulResponse) r;
            NodeTopologyInfo nti = (NodeTopologyInfo) sr.getResponseValue();
            if (nti != null) {
               distributionManager.getTopologyInfo().addNodeTopologyInfo(nti.getAddress(), nti);
            }
         } else {
            // will ignore unsuccessful response
            if (trace)
               log.tracef("updateTopologyInfo will ignore unsuccessful response (another node may not be ready), got response with success=%s, is a %s", r.isSuccessful(), r.getClass().getSimpleName());
         }
      }
      if (trace) log.tracef("Topology after after getting cluster info: %s", distributionManager.getTopologyInfo());
   }

   private ConsistentHash retrieveOldConsistentHash() throws InterruptedException, IllegalAccessException, InstantiationException, ClassNotFoundException {

      // this happens in a loop to ensure we receive the correct CH and not a "union".
      // TODO make at least *some* of these configurable!
      ConsistentHash result = null;
      long minSleepTime = 500, maxSleepTime = 2000; // sleep time between retries
      int maxWaitTime = (int) configuration.getRehashRpcTimeout() * 10; // after which we give up!
      Random rand = new Random();
      long giveupTime = System.currentTimeMillis() + maxWaitTime;
      do {
         if (trace)
            log.trace("Requesting old consistent hash from coordinator");
         Map<Address, Response> resp;
         Set<Address> addresses;
         try {
            resp = rpcManager.invokeRemotely(coordinator(), cf.buildRehashControlCommand(
                  JOIN_REQ, self), SYNCHRONOUS, configuration.getRehashRpcTimeout(),
                                             true);
            addresses = parseResponses(resp.values());
            if (log.isDebugEnabled())
               log.debugf("Retrieved old consistent hash address list %s", addresses);
         } catch (TimeoutException te) {
            // timed out waiting for responses; retry!
            resp = null;
            addresses = null;
            if (log.isDebugEnabled())
               log.debug("Timed out waiting for responses.");
         }

         if (addresses == null) {
            long time = rand.nextInt((int) (maxSleepTime - minSleepTime) / 10);
            time = (time * 10) + minSleepTime;
            if (trace)
               log.tracef("Sleeping for %s", Util.prettyPrintTime(time));
            Thread.sleep(time); // sleep for a while and retry
         } else {
            result = createConsistentHash(configuration, addresses, distributionManager.getTopologyInfo());
         }
      } while (result == null && System.currentTimeMillis() < giveupTime);

      if (result == null)
         throw new CacheException(
               "Unable to retrieve old consistent hash from coordinator even after several attempts at sleeping and retrying!");
      return result;
   }

   /**
    * Retrieves a List of Address of who should be sending state to the joiner (self), given a repl count (numOwners)
    * for each entry.
    * <p/>
    * The algorithm essentially works like this.  Given a list of all Addresses in the system (ordered by their
    * positions in the new consistent hash wheel), locate where the current address (self, the joiner) is, on this list.
    * Addresses from (replCount - 1) positions behind self, and 1 position ahead of self would be sending state.
    * <p/>
    *
    * @param replCount
    * @return
    */
   List<Address> getAddressesWhoMaySendStuff(ConsistentHash ch, int replCount) {
      return ch.getStateProvidersOnJoin(self, replCount);
   }

   public Address getMyAddress() {
      return rpcManager != null && rpcManager.getTransport() != null ? rpcManager.getTransport().getAddress() : null;
   }

   protected final class JoinStateGrabber extends StateGrabber {

      public JoinStateGrabber(Address stateProvider, ReplicableCommand command, ConsistentHash newConsistentHash) {
         super(stateProvider, command, newConsistentHash);
      }

      @Override
      protected boolean isForLeave() {
         return false;
      }
   }
}
