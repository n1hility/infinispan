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
package org.infinispan.tx;

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.manager.CacheContainer;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.testng.annotations.Test;

import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.util.Arrays;

@Test(groups = "functional", sequential = true, testName = "tx.TransactionsSpanningReplicatedCaches")
public class TransactionsSpanningReplicatedCaches extends MultipleCacheManagersTest {

   CacheContainer cm1, cm2;

   public TransactionsSpanningReplicatedCaches() {
      cleanup = CleanupPhase.AFTER_METHOD;
   }

   protected void createCacheManagers() throws Exception {
      cm1 = addClusterEnabledCacheManager();
      cm2 = addClusterEnabledCacheManager();

      Configuration c = getDefaultClusteredConfig(Configuration.CacheMode.REPL_SYNC, true);

      defineConfigurationOnAllManagers("c1", c);
      defineConfigurationOnAllManagers("c2", c);
   }

   public void testCommitSpanningCaches() throws Exception {
      Cache c1 = cm1.getCache("c1");
      Cache c1Replica = cm2.getCache("c1");
      Cache c2 = cm1.getCache("c2");
      Cache c2Replica = cm2.getCache("c2");

      assert c1.isEmpty();
      assert c2.isEmpty();
      assert c1Replica.isEmpty();
      assert c2Replica.isEmpty();

      c1.put("c1key", "c1value");
      c2.put("c2key", "c2value");

      for (Cache c : Arrays.asList(c1, c1Replica)) {
         assert !c.isEmpty();
         assert c.size() == 1;
         assert c.get("c1key").equals("c1value");
      }

      for (Cache c : Arrays.asList(c2, c2Replica)) {
         assert !c.isEmpty();
         assert c.size() == 1;
         assert c.get("c2key").equals("c2value");
      }

      TransactionManager tm = TestingUtil.getTransactionManager(c1);

      tm.begin();
      c1.put("c1key", "c1value_new");
      c2.put("c2key", "c2value_new");

      assert c1.get("c1key").equals("c1value_new");
      assert c1Replica.get("c1key").equals("c1value");
      assert c2.get("c2key").equals("c2value_new");
      assert c2Replica.get("c2key").equals("c2value");

      Transaction tx = tm.suspend();

      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");
      assert c2.get("c2key").equals("c2value");
      assert c2Replica.get("c2key").equals("c2value");

      tm.resume(tx);
      tm.commit();

      assert c1.get("c1key").equals("c1value_new");
      assert c1Replica.get("c1key").equals("c1value_new");
      assert c2.get("c2key").equals("c2value_new");
      assert c2Replica.get("c2key").equals("c2value_new");
   }

   public void testRollbackSpanningCaches() throws Exception {
      Cache c1 = cm1.getCache("c1");
      Cache c1Replica = cm2.getCache("c1");
      Cache c2 = cm1.getCache("c2");
      Cache c2Replica = cm2.getCache("c2");

      assert c1.isEmpty();
      assert c2.isEmpty();
      assert c1Replica.isEmpty();
      assert c2Replica.isEmpty();

      c1.put("c1key", "c1value");
      c2.put("c2key", "c2value");

      for (Cache c : Arrays.asList(c1, c1Replica)) {
         assert !c.isEmpty();
         assert c.size() == 1;
         assert c.get("c1key").equals("c1value");
      }

      for (Cache c : Arrays.asList(c2, c2Replica)) {
         assert !c.isEmpty();
         assert c.size() == 1;
         assert c.get("c2key").equals("c2value");
      }

      TransactionManager tm = TestingUtil.getTransactionManager(c1);

      tm.begin();
      c1.put("c1key", "c1value_new");
      c2.put("c2key", "c2value_new");

      assert c1.get("c1key").equals("c1value_new");
      assert c1Replica.get("c1key").equals("c1value");
      assert c2.get("c2key").equals("c2value_new");
      assert c2Replica.get("c2key").equals("c2value");

      Transaction tx = tm.suspend();

      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");
      assert c2.get("c2key").equals("c2value");
      assert c2Replica.get("c2key").equals("c2value");

      tm.resume(tx);
      tm.rollback();

      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");
      assert c2.get("c2key").equals("c2value");
      assert c2Replica.get("c2key").equals("c2value");
   }

   public void testRollbackSpanningCaches2() throws Exception {
      Cache c1 = cm1.getCache("c1");

      assert c1.getConfiguration().getCacheMode().isClustered();
      Cache c1Replica = cm2.getCache("c1");

      assert c1.isEmpty();
      assert c1Replica.isEmpty();

      c1.put("c1key", "c1value");
      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");
   }

   public void testSimpleCommit() throws Exception {
      Cache c1 = cm1.getCache("c1");
      Cache c1Replica = cm2.getCache("c1");


      assert c1.isEmpty();
      assert c1Replica.isEmpty();

      TransactionManager tm = TestingUtil.getTransactionManager(c1);
      tm.begin();
      c1.put("c1key", "c1value");
      tm.commit();

      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");
   }

   public void testPutIfAbsent() throws Exception {
      Cache c1 = cm1.getCache("c1");
      Cache c1Replica = cm2.getCache("c1");


      assert c1.isEmpty();
      assert c1Replica.isEmpty();

      TransactionManager tm = TestingUtil.getTransactionManager(c1);
      tm.begin();
      c1.put("c1key", "c1value");
      tm.commit();

      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");

      tm.begin();
      c1.putIfAbsent("c1key", "SHOULD_NOT_GET_INSERTED");
      tm.commit();

      assert c1.get("c1key").equals("c1value");
      assert c1Replica.get("c1key").equals("c1value");
   }
}