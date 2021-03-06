/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
/**
 * 
 */
package org.apache.geode.internal.cache.partitioned;

import static org.apache.geode.distributed.ConfigurationProperties.*;

import java.util.Properties;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.ExpirationAction;
import org.apache.geode.cache.ExpirationAttributes;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache.util.CacheListenerAdapter;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.internal.AvailablePort;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.ha.HARegionQueueStats;
import org.apache.geode.internal.cache.tier.sockets.CacheClientNotifier;
import org.apache.geode.internal.cache.tier.sockets.CacheClientProxy;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.Wait;
import org.apache.geode.test.dunit.WaitCriterion;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;
import org.apache.geode.test.junit.categories.DistributedTest;

/**
 * The test creates two datastores with a partitioned region, and also running a cache server each.
 * A publisher client is connected to one server while a subscriber client is connected to both the
 * servers. The partitioned region has entry expiry set with ttl of 3 seconds and action as DESTROY.
 * The test ensures that the EXPIRE_DESTROY events are propagated to the subscriber client and the
 * secondary server does process the QRMs for the EXPIRE_DESTROY events.
 */
@Category(DistributedTest.class)
@SuppressWarnings("serial")
public class Bug47388DUnitTest extends JUnit4DistributedTestCase {

  private static VM vm0 = null;
  private static VM vm1 = null;
  private static VM vm2 = null;
  private static VM vm3 = null;

  private static GemFireCacheImpl cache;

  private static volatile boolean lastKeyDestroyed = false;

  public static final String REGION_NAME = Bug47388DUnitTest.class.getSimpleName() + "_region";

  @Override
  public final void postSetUp() throws Exception {
    disconnectFromDS();
    Host host = Host.getHost(0);
    vm0 = host.getVM(0); // datastore and server
    vm1 = host.getVM(1); // datastore and server
    vm2 = host.getVM(2); // durable client with subscription
    vm3 = host.getVM(3); // durable client without subscription

    // int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    int port0 = (Integer) vm0.invoke(() -> Bug47388DUnitTest.createCacheServerWithPRDatastore());
    int port1 = (Integer) vm1.invoke(() -> Bug47388DUnitTest.createCacheServerWithPRDatastore());

    vm2.invoke(Bug47388DUnitTest.class, "createClientCache",
        new Object[] {vm2.getHost(), new Integer[] {port0, port1}, Boolean.TRUE});
    vm3.invoke(Bug47388DUnitTest.class, "createClientCache",
        new Object[] {vm3.getHost(), new Integer[] {port0}, Boolean.FALSE});

  }

  @Override
  public final void preTearDown() throws Exception {
    closeCache();

    vm2.invoke(() -> Bug47388DUnitTest.closeCache());
    vm3.invoke(() -> Bug47388DUnitTest.closeCache());

    vm0.invoke(() -> Bug47388DUnitTest.closeCache());
    vm1.invoke(() -> Bug47388DUnitTest.closeCache());
  }

  public static void closeCache() throws Exception {
    if (cache != null) {
      cache.close();
    }
    lastKeyDestroyed = false;
  }

  @SuppressWarnings("deprecation")
  public static Integer createCacheServerWithPRDatastore() throws Exception {
    Properties props = new Properties();
    Bug47388DUnitTest test = new Bug47388DUnitTest();
    DistributedSystem ds = test.getSystem(props);
    ds.disconnect();
    cache = (GemFireCacheImpl) CacheFactory.create(test.getSystem());

    RegionFactory<String, String> rf = cache.createRegionFactory(RegionShortcut.PARTITION);

    rf.setEntryTimeToLive(new ExpirationAttributes(3, ExpirationAction.DESTROY))
        .setPartitionAttributes(new PartitionAttributesFactory<String, String>()
            .setRedundantCopies(1).setTotalNumBuckets(4).create())
        .setConcurrencyChecksEnabled(false);

    rf.create(REGION_NAME);

    CacheServer server = cache.addCacheServer();
    server.setPort(AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET));
    server.start();
    return server.getPort();
  }

  @SuppressWarnings("deprecation")
  public static void createClientCache(Host host, Integer[] ports, Boolean doRI) throws Exception {
    Properties props = new Properties();
    props.setProperty(DURABLE_CLIENT_ID, "my-durable-client-" + ports.length);
    props.setProperty(DURABLE_CLIENT_TIMEOUT, "300000");

    DistributedSystem ds = new Bug47388DUnitTest().getSystem(props);
    ds.disconnect();
    ClientCacheFactory ccf = new ClientCacheFactory(props);
    ccf.setPoolSubscriptionEnabled(doRI);
    ccf.setPoolSubscriptionAckInterval(50);
    ccf.setPoolSubscriptionRedundancy(1);
    for (int port : ports) {
      ccf.addPoolServer(host.getHostName(), port);
    }
    cache = (GemFireCacheImpl) ccf.create();

    ClientRegionFactory<String, String> crf =
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY);

    if (doRI) {
      crf.addCacheListener(new CacheListenerAdapter<String, String>() {
        @Override
        public void afterDestroy(EntryEvent<String, String> event) {
          if (event.getKey().equalsIgnoreCase("LAST_KEY")) {
            lastKeyDestroyed = true;
          }
        }
      });
    }

    Region<String, String> region = crf.create(REGION_NAME);

    if (doRI) {
      region.registerInterest("ALL_KEYS", true);
      cache.readyForEvents();
    }
  }

  @SuppressWarnings("unchecked")
  public static void doPuts(Integer numOfSets, Integer numOfPuts) throws Exception {
    Region<String, String> region = cache.getRegion(REGION_NAME);

    for (int i = 0; i < numOfSets; i++) {
      for (int j = 0; j < numOfPuts; j++) {
        region.put("KEY_" + i + "_" + j, "VALUE_" + j);
      }
    }
    region.put("LAST_KEY", "LAST_KEY");
  }

  public static Boolean isPrimaryServer() {
    return ((CacheClientProxy) CacheClientNotifier.getInstance().getClientProxies().toArray()[0])
        .isPrimary();
  }

  public static void verifyClientSubscriptionStats(final Boolean isPrimary, final Integer events)
      throws Exception {

    WaitCriterion wc = new WaitCriterion() {
      private long dispatched;
      private long qrmed;

      @Override
      public boolean done() {
        HARegionQueueStats stats =
            ((CacheClientProxy) CacheClientNotifier.getInstance().getClientProxies().toArray()[0])
                .getHARegionQueue().getStatistics();

        final int numOfEvents;
        if (!isPrimary) {
          numOfEvents = events - 1; // No marker
        } else {
          numOfEvents = events;
        }

        if (isPrimary) {
          this.dispatched = stats.getEventsDispatched();
          return numOfEvents == this.dispatched;
        } else {
          this.qrmed = stats.getEventsRemovedByQrm();
          return this.qrmed == numOfEvents || (this.qrmed + 1) == numOfEvents;
          // Why +1 above? Because sometimes(TODO: explain further) there may
          // not be any QRM sent to the secondary for the last event dispatched
          // at primary.
        }
      }

      @Override
      public String description() {
        return "Expected events: " + events + " but actual eventsDispatched: " + this.dispatched
            + " and actual eventsRemovedByQrm: " + this.qrmed;
      }
    };

    Wait.waitForCriterion(wc, 60 * 1000, 500, true);
  }

  public static void waitForLastKeyDestroyed() throws Exception {
    WaitCriterion wc = new WaitCriterion() {
      @Override
      public boolean done() {
        return lastKeyDestroyed;
      }

      @Override
      public String description() {
        return "Last key's destroy not received";
      }

    };

    Wait.waitForCriterion(wc, 60 * 1000, 500, true);
  }

  @Ignore("TODO: test is disabled due to bug51931")
  @Test
  public void testQRMOfExpiredEventsProcessedSuccessfully() throws Exception {
    int numOfSets = 2, numOfPuts = 5;
    int totalEvents = 23; // = (numOfSets * numOfPuts) * 2 [eviction-destroys] +
                          // 2 [last key's put and eviction-destroy] + 1 [marker
                          // message]
    vm3.invoke(() -> Bug47388DUnitTest.doPuts(numOfSets, numOfPuts));

    boolean isvm0Primary = (Boolean) vm0.invoke(() -> Bug47388DUnitTest.isPrimaryServer());

    vm2.invoke(() -> Bug47388DUnitTest.waitForLastKeyDestroyed());

    vm0.invoke(() -> Bug47388DUnitTest.verifyClientSubscriptionStats(isvm0Primary, totalEvents));
    vm1.invoke(() -> Bug47388DUnitTest.verifyClientSubscriptionStats(!isvm0Primary, totalEvents));
  }
}
