/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2000 - 2011, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.config.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.ConsistentHashHelper;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.NotifyingFutureImpl;
import org.infinispan.util.concurrent.NotifyingNotifiableFuture;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.infinispan.commands.control.RehashControlCommand.Type.LEAVE_REHASH_END;
import static org.infinispan.commands.control.RehashControlCommand.Type.PULL_STATE_LEAVE;
import static org.infinispan.remoting.rpc.ResponseMode.ASYNCHRONOUS;
import static org.infinispan.remoting.rpc.ResponseMode.SYNCHRONOUS;

/**
 *A task to handle rehashing for when a node leaves the cluster
 * 
 * @author Vladimir Blagojevic
 * @author Manik Surtani
 * @since 4.0
 */
public class InvertedLeaveTask extends RehashTask {

   private static final Log log = LogFactory.getLog(InvertedLeaveTask.class);
   private static final boolean trace = log.isTraceEnabled();
   private final List<Address> leavers;
   private final Address self;
   private final List<Address> leaversHandled;
   private final List<Address> providers;
   private final List<Address> receivers;
   private final boolean isReceiver;
   private final boolean isSender;

   public InvertedLeaveTask(DistributionManagerImpl dmi, RpcManager rpcManager, Configuration conf,
            CommandsFactory commandsFactory, DataContainer dataContainer, List<Address> leavers,
            List<Address> stateProviders, List<Address> stateReceivers, boolean isReceiver) {
      super(dmi, rpcManager, conf, commandsFactory, dataContainer);
      this.leavers = leavers;
      this.leaversHandled = new LinkedList<Address>(leavers);
      this.providers = stateProviders;
      this.receivers = stateReceivers;
      this.isReceiver = isReceiver;      
      this.self = rpcManager.getTransport().getAddress();
      this.isSender = stateProviders.contains(self);
   }

   @SuppressWarnings("unchecked")
   private Map<Object, InternalCacheValue> getStateFromResponse(SuccessfulResponse r) {
      return (Map<Object, InternalCacheValue>) r.getResponseValue();
   }

   protected void performRehash() throws Exception {
      long start = trace ? System.currentTimeMillis() : 0;

      int replCount = configuration.getNumOwners();
      ConsistentHash newCH = dmi.getConsistentHash();
      ConsistentHash oldCH = ConsistentHashHelper.createConsistentHash(configuration, newCH.getCaches(), leaversHandled, dmi.topologyInfo);

      try {
         log.debug("Starting leave rehash[enabled=%s,isReceiver=%s,isSender=%s] on node %s",
                  configuration.isRehashEnabled(), isReceiver, isSender, self);

         if (configuration.isRehashEnabled()) {
            if (isReceiver) {
               try {
                  RehashControlCommand cmd = cf.buildRehashControlCommand(PULL_STATE_LEAVE, self,
                           null, oldCH, newCH, leaversHandled);

                  log.debug("I %s am pulling state from %s", self, providers);
                  List<Response> resps = rpcManager.invokeRemotely(providers, cmd, SYNCHRONOUS,
                           configuration.getRehashRpcTimeout(), true);

                  log.debug("I %s received response %s ", self, resps);
                  for (Response r : resps) {
                     if (r instanceof SuccessfulResponse) {
                        Map<Object, InternalCacheValue> state = getStateFromResponse((SuccessfulResponse) r);
                        log.debug("I %s am applying state %s ", self, state);
                        dmi.applyState(newCH, state);
                     }
                  }
               } finally {
                  RehashControlCommand c = cf.buildRehashControlCommand(LEAVE_REHASH_END, self);
                  rpcManager.invokeRemotely(providers, c, ASYNCHRONOUS, configuration.getRehashRpcTimeout(), false);
               }
            }
            if (isSender) {
               Set<Address> recCopy = new HashSet<Address>(receivers);
               if(isReceiver) {
                  recCopy.remove(self);
               }
               dmi.awaitLeaveRehashAcks(recCopy, configuration.getStateRetrievalTimeout());
               processAndDrainTxLog(oldCH, newCH, replCount);
               invalidateInvalidHolders(leaversHandled, oldCH, newCH);
            }
         }
      } catch (Exception e) {
         throw new CacheException("Unexpected exception", e);
      } finally {
         leavers.removeAll(leaversHandled);
         if (trace)
            log.info("Completed leave rehash on node %s in %s", self,
                     Util.prettyPrintTime(System.currentTimeMillis() - start));
         else
            log.info("Completed leave rehash on node %s", self);

         for (Address addr : leaversHandled)
            dmi.topologyInfo.removeNodeInfo(addr);
         dmi.rehashInProgress = false;
      }
   }

   private void processAndDrainTxLog(ConsistentHash oldCH, ConsistentHash newCH, int replCount) {

      List<WriteCommand> c;
      int i = 0;
      TransactionLogger transactionLogger = dmi.getTransactionLogger();
      if (trace)
         log.trace("Processing transaction log iteratively: " + transactionLogger);
      while (transactionLogger.shouldDrainWithoutLock()) {
         if (trace)
            log.trace("Processing transaction log, iteration %s", i++);
         c = transactionLogger.drain();
         if (trace)
            log.trace("Found %s modifications", c.size());
         apply(oldCH, newCH, replCount, c);
      }

      if (trace)
         log.trace("Processing transaction log: final drain and lock");
      c = transactionLogger.drainAndLock();
      if (trace)
         log.trace("Found %s modifications", c.size());
      apply(oldCH, newCH, replCount, c);

      if (trace)
         log.trace("Handling pending prepares");
      PendingPreparesMap state = new PendingPreparesMap(leavers, oldCH, newCH, replCount);
      Collection<PrepareCommand> pendingPrepares = transactionLogger.getPendingPrepares();
      if (trace)
         log.trace("Found %s pending prepares", pendingPrepares.size());
      for (PrepareCommand pc : pendingPrepares)
         state.addState(pc);

      if (trace)
         log.trace("State map for pending prepares is %s", state.getState());

      Set<Future<Object>> pushFutures = new HashSet<Future<Object>>();
      for (Map.Entry<Address, List<PrepareCommand>> e : state.getState().entrySet()) {
         if (log.isDebugEnabled())
            log.debug("Pushing %s uncommitted prepares to %s", e.getValue().size(), e.getKey());
         RehashControlCommand push = cf.buildRehashControlCommandTxLogPendingPrepares(self, e.getValue());
         NotifyingNotifiableFuture<Object> f = new NotifyingFutureImpl(null);
         pushFutures.add(f);
         rpcManager.invokeRemotelyInFuture(Collections.singleton(e.getKey()), push, true, f, configuration.getRehashRpcTimeout());
      }

      for (Future f : pushFutures) {
         try {
            f.get();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (ExecutionException e) {
            log.error("Error pushing tx log", e);
         }
      }
      if (trace)
         log.trace("Finished pushing pending prepares; unlocking and disabling transaction logging");

      transactionLogger.unlockAndDisable();
   }

   private void apply(ConsistentHash oldCH, ConsistentHash newCH, int replCount, List<WriteCommand> wc) {
      // need to create another "state map"
      TransactionLogMap state = new TransactionLogMap(leavers, oldCH, newCH, replCount);
      for (WriteCommand c : wc)
         state.addState(c);

      if (trace)
         log.trace("State map for modifications is %s", state.getState());

      Set<Future<Object>> pushFutures = new HashSet<Future<Object>>();
      for (Map.Entry<Address, List<WriteCommand>> entry : state.getState().entrySet()) {
         if (log.isDebugEnabled())
            log.debug("Pushing %s modifications to %s", entry.getValue().size(), entry.getKey());
         RehashControlCommand push = cf.buildRehashControlCommandTxLog(self, entry.getValue());
         NotifyingNotifiableFuture<Object> f = new NotifyingFutureImpl(null);
         pushFutures.add(f);
         rpcManager.invokeRemotelyInFuture(Collections.singleton(entry.getKey()), push, true, f,
                  configuration.getRehashRpcTimeout());
      }

      for (Future f : pushFutures) {
         try {
            f.get();
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (ExecutionException e) {
            log.error("Error pushing tx log", e);
         }
      }
   }

   @Override
   protected Log getLog() {
      return log;
   }
}

abstract class StateMap<S> {
   List<Address> leavers;
   ConsistentHash oldCH, newCH;
   int replCount;
   Map<Address, S> state = new HashMap<Address, S>();
   protected static final Log log = LogFactory.getLog(InvertedLeaveTask.class);

   StateMap(List<Address> leavers, ConsistentHash oldCH, ConsistentHash newCH, int replCount) {
      this.leavers = leavers;
      this.oldCH = oldCH;
      this.newCH = newCH;
      this.replCount = replCount;
   }

   Map<Address, S> getState() {
      return state;
   }
}

/**
 * A state map that aggregates {@link ReplicableCommand}s according to recipient affected.
 *
 * @param <T> type of replicable command to aggregate
 */
abstract class CommandAggregatingStateMap<T extends ReplicableCommand> extends StateMap<List<T>> {
   Set<Object> keysHandled = new HashSet<Object>();

   CommandAggregatingStateMap(List<Address> leavers, ConsistentHash oldCH, ConsistentHash newCH, int replCount) {
      super(leavers, oldCH, newCH, replCount);
   }

   // if only Java had duck-typing!
   abstract Set<Object> getAffectedKeys(T payload);

   /**
    * Only add state to state map if old_owner_list for key contains a leaver, and the position of the leaver in the old
    * owner list
    *
    * @param payload payload to consider when adding to the aggregate state
    */
   void addState(T payload) {
      for (Object key : getAffectedKeys(payload)) {
         for (Address leaver : leavers) {
            List<Address> owners = oldCH.locate(key, replCount);
            int leaverIndex = owners.indexOf(leaver);
            if (leaverIndex > -1) {
               // add to state map!
               List<Address> newOwners = newCH.locate(key, replCount);
               newOwners.removeAll(owners);
               if (!newOwners.isEmpty()) {
                  for (Address no : newOwners) {
                     List<T> s = state.get(no);
                     if (s == null) {
                        s = new LinkedList<T>();
                        state.put(no, s);
                     }
                     s.add(payload);
                  }
               }
            }
         }
      }
   }
}

/**
 * Specific version of the CommandAggregatingStateMap that aggregates PrepareCommands, used to flush pending prepares
 * to nodes during a leave.
 */
class PendingPreparesMap extends CommandAggregatingStateMap<PrepareCommand> {
   PendingPreparesMap(List<Address> leavers, ConsistentHash oldCH, ConsistentHash newCH, int replCount) {
      super(leavers, oldCH, newCH, replCount);
   }

   @Override
   Set<Object> getAffectedKeys(PrepareCommand payload) {
      return payload.getAffectedKeys();
   }
}

/**
 * Specific version of the CommandAggregatingStateMap that aggregates PrepareCommands, used to flush writes
 * made while state was being transferred to nodes during a leave. 
 */
class TransactionLogMap extends CommandAggregatingStateMap<WriteCommand> {
   TransactionLogMap(List<Address> leavers, ConsistentHash oldCH, ConsistentHash newCH, int replCount) {
      super(leavers, oldCH, newCH, replCount);
   }

   @Override
   Set<Object> getAffectedKeys(WriteCommand payload) {
      return payload.getAffectedKeys();
   }
}
