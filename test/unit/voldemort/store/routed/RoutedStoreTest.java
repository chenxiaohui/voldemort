/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.routed;

import static voldemort.TestUtils.getClock;
import static voldemort.VoldemortTestConstants.getNineNodeCluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import voldemort.NodeAvailabilityDetectorTestCase;
import voldemort.ServerTestUtils;
import voldemort.TestUtils;
import voldemort.VoldemortException;
import voldemort.VoldemortTestConstants;
import voldemort.client.RoutingTier;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.cluster.nodeavailabilitydetector.NodeAvailabilityDetector;
import voldemort.routing.RoutingStrategyType;
import voldemort.serialization.SerializerDefinition;
import voldemort.store.AbstractByteArrayStoreTest;
import voldemort.store.FailingReadsStore;
import voldemort.store.FailingStore;
import voldemort.store.InsufficientOperationalNodesException;
import voldemort.store.SleepyStore;
import voldemort.store.Store;
import voldemort.store.StoreDefinition;
import voldemort.store.UnreachableStoreException;
import voldemort.store.memory.InMemoryStorageEngine;
import voldemort.store.versioned.InconsistencyResolvingStore;
import voldemort.utils.ByteArray;
import voldemort.utils.Utils;
import voldemort.versioning.Occured;
import voldemort.versioning.VectorClock;
import voldemort.versioning.VectorClockInconsistencyResolver;
import voldemort.versioning.Versioned;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

/**
 * Basic tests for RoutedStore
 * 
 * @author jay
 * 
 */
public class RoutedStoreTest extends AbstractByteArrayStoreTest implements
        NodeAvailabilityDetectorTestCase {

    private Cluster cluster;
    private final ByteArray aKey = TestUtils.toByteArray("jay");
    private final byte[] aValue = "kreps".getBytes();
    private Class<NodeAvailabilityDetector> nodeAvailabilityDetectorClass;
    private NodeAvailabilityDetector nodeAvailabilityDetector;

    public static Test suite() {
        return TestUtils.createNodeAvailabilityDetectorTestSuite(RoutedStoreTest.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        cluster = getNineNodeCluster();
    }

    public void setNodeAvailabilityDetectorClass(Class<NodeAvailabilityDetector> nodeAvailabilityDetectorClass) {
        this.nodeAvailabilityDetectorClass = nodeAvailabilityDetectorClass;
    }

    @Override
    public Store<ByteArray, byte[]> getStore() throws Exception {
        return new InconsistencyResolvingStore<ByteArray, byte[]>(getStore(cluster,
                                                                           cluster.getNumberOfNodes(),
                                                                           cluster.getNumberOfNodes(),
                                                                           4,
                                                                           0),
                                                                  new VectorClockInconsistencyResolver<byte[]>());
    }

    private RoutedStore getStore(Cluster cluster, int reads, int writes, int threads, int failing)
            throws Exception {
        return getStore(cluster,
                        reads,
                        writes,
                        threads,
                        failing,
                        0,
                        RoutingStrategyType.TO_ALL_STRATEGY,
                        new VoldemortException());
    }

    private RoutedStore getStore(Cluster cluster,
                                 int reads,
                                 int writes,
                                 int threads,
                                 int failing,
                                 int sleepy,
                                 String strategy,
                                 VoldemortException e) throws Exception {
        Map<Integer, Store<ByteArray, byte[]>> subStores = Maps.newHashMap();
        int count = 0;
        for(Node n: cluster.getNodes()) {
            if(count >= cluster.getNumberOfNodes())
                throw new IllegalArgumentException(failing + " failing nodes, " + sleepy
                                                   + " sleepy nodes, but only "
                                                   + cluster.getNumberOfNodes()
                                                   + " nodes in the cluster.");
            else if(count < failing)
                subStores.put(n.getId(), new FailingStore<ByteArray, byte[]>("test", e));
            else if(count < failing + sleepy)
                subStores.put(n.getId(),
                              new SleepyStore<ByteArray, byte[]>(Long.MAX_VALUE,
                                                                 new InMemoryStorageEngine<ByteArray, byte[]>("test")));
            else
                subStores.put(n.getId(), new InMemoryStorageEngine<ByteArray, byte[]>("test"));

            count += 1;
        }

        setNodeAvailabilityDetector(subStores);

        return new RoutedStore("test",
                               subStores,
                               cluster,
                               ServerTestUtils.getStoreDef("test",
                                                           reads + writes,
                                                           reads,
                                                           reads,
                                                           writes,
                                                           writes,
                                                           strategy),
                               threads,
                               true,
                               1000L,
                               nodeAvailabilityDetector);
    }

    private int countOccurances(RoutedStore routedStore, ByteArray key, Versioned<byte[]> value) {
        int count = 0;
        for(Store<ByteArray, byte[]> store: routedStore.getInnerStores().values())
            try {
                if(store.get(key).size() > 0 && Utils.deepEquals(store.get(key).get(0), value))
                    count += 1;
            } catch(VoldemortException e) {
                // This is normal for the failing store...
            }
        return count;
    }

    private void assertNEqual(RoutedStore routedStore,
                              int expected,
                              ByteArray key,
                              Versioned<byte[]> value) {
        int count = countOccurances(routedStore, key, value);
        assertEquals("Expected " + expected + " occurances of '" + key + "' with value '" + value
                     + "', but found " + count + ".", expected, count);
    }

    private void assertNOrMoreEqual(RoutedStore routedStore,
                                    int expected,
                                    ByteArray key,
                                    Versioned<byte[]> value) {
        int count = countOccurances(routedStore, key, value);
        assertTrue("Expected " + expected + " or more occurances of '" + key + "' with value '"
                   + value + "', but found " + count + ".", expected <= count);
    }

    private void testBasicOperations(int reads, int writes, int failures, int threads)
            throws Exception {
        RoutedStore routedStore = getStore(cluster, reads, writes, threads, failures);
        Store<ByteArray, byte[]> store = new InconsistencyResolvingStore<ByteArray, byte[]>(routedStore,
                                                                                            new VectorClockInconsistencyResolver<byte[]>());
        VectorClock clock = getClock(1);
        Versioned<byte[]> versioned = new Versioned<byte[]>(aValue, clock);
        routedStore.put(aKey, versioned);
        assertNOrMoreEqual(routedStore, cluster.getNumberOfNodes() - failures, aKey, versioned);
        List<Versioned<byte[]>> found = store.get(aKey);
        assertEquals(1, found.size());
        assertEquals(versioned, found.get(0));
        assertNOrMoreEqual(routedStore, cluster.getNumberOfNodes() - failures, aKey, versioned);
        assertTrue(routedStore.delete(aKey, versioned.getVersion()));
        assertNEqual(routedStore, 0, aKey, versioned);
        assertTrue(!routedStore.delete(aKey, versioned.getVersion()));
    }

    public void testBasicOperationsSingleThreaded() throws Exception {
        testBasicOperations(cluster.getNumberOfNodes(), cluster.getNumberOfNodes(), 0, 1);
    }

    public void testBasicOperationsMultiThreaded() throws Exception {
        testBasicOperations(cluster.getNumberOfNodes(), cluster.getNumberOfNodes(), 0, 4);
    }

    public void testBasicOperationsMultiThreadedWithFailures() throws Exception {
        testBasicOperations(cluster.getNumberOfNodes() - 2, cluster.getNumberOfNodes() - 2, 2, 4);
    }

    private void testBasicOperationFailure(int reads, int writes, int failures, int threads)
            throws Exception {
        VectorClock clock = getClock(1);
        Versioned<byte[]> versioned = new Versioned<byte[]>(aValue, clock);
        RoutedStore routedStore = getStore(cluster,
                                           reads,
                                           writes,
                                           threads,
                                           failures,
                                           0,
                                           RoutingStrategyType.TO_ALL_STRATEGY,
                                           new UnreachableStoreException("no go"));
        try {
            routedStore.put(aKey, versioned);
            fail("Put succeeded with too few operational nodes.");
        } catch(InsufficientOperationalNodesException e) {
            // expected
        }
        try {
            routedStore.get(aKey);
            fail("Get succeeded with too few operational nodes.");
        } catch(InsufficientOperationalNodesException e) {
            // expected
        }
        try {
            routedStore.delete(aKey, versioned.getVersion());
            fail("Get succeeded with too few operational nodes.");
        } catch(InsufficientOperationalNodesException e) {
            // expected
        }
    }

    public void testBasicOperationFailureMultiThreaded() throws Exception {
        testBasicOperationFailure(cluster.getNumberOfNodes() - 2,
                                  cluster.getNumberOfNodes() - 2,
                                  4,
                                  4);
    }

    public void testPutIncrementsVersion() throws Exception {
        Store<ByteArray, byte[]> store = getStore();
        VectorClock clock = new VectorClock();
        VectorClock copy = clock.clone();
        store.put(aKey, new Versioned<byte[]>(getValue(), clock));
        List<Versioned<byte[]>> found = store.get(aKey);
        assertEquals("Invalid number of items found.", 1, found.size());
        assertEquals("Version not incremented properly",
                     Occured.BEFORE,
                     copy.compare(found.get(0).getVersion()));
    }

    public void testObsoleteMasterFails() {
    // write me
    }

    public void testOnlyNodeFailuresDisableNode() throws Exception {
        // test put
        cluster = getNineNodeCluster();

        Store<ByteArray, byte[]> s1 = getStore(cluster,
                                               1,
                                               9,
                                               9,
                                               9,
                                               0,
                                               RoutingStrategyType.TO_ALL_STRATEGY,
                                               new VoldemortException());
        try {
            s1.put(aKey, new Versioned<byte[]>(aValue));
            fail("Failure is expected");
        } catch(InsufficientOperationalNodesException e) { /* expected */
        }
        assertOperationalNodes(9);

        cluster = getNineNodeCluster();

        Store<ByteArray, byte[]> s2 = getStore(cluster,
                                               1,
                                               9,
                                               9,
                                               9,
                                               0,
                                               RoutingStrategyType.TO_ALL_STRATEGY,
                                               new UnreachableStoreException("no go"));
        try {
            s2.put(aKey, new Versioned<byte[]>(aValue));
            fail("Failure is expected");
        } catch(InsufficientOperationalNodesException e) { /* expected */
        }
        assertOperationalNodes(0);

        // test get
        cluster = getNineNodeCluster();

        s1 = getStore(cluster,
                      1,
                      9,
                      9,
                      9,
                      0,
                      RoutingStrategyType.TO_ALL_STRATEGY,
                      new VoldemortException());
        try {
            s1.get(aKey);
            fail("Failure is expected");
        } catch(InsufficientOperationalNodesException e) { /* expected */
        }
        assertOperationalNodes(9);

        cluster = getNineNodeCluster();

        s2 = getStore(cluster,
                      1,
                      9,
                      9,
                      9,
                      0,
                      RoutingStrategyType.TO_ALL_STRATEGY,
                      new UnreachableStoreException("no go"));
        try {
            s2.get(aKey);
            fail("Failure is expected");
        } catch(InsufficientOperationalNodesException e) { /* expected */
        }
        assertOperationalNodes(0);

        // test delete
        cluster = getNineNodeCluster();

        s1 = getStore(cluster,
                      1,
                      9,
                      9,
                      9,
                      0,
                      RoutingStrategyType.TO_ALL_STRATEGY,
                      new VoldemortException());
        try {
            s1.delete(aKey, new VectorClock());
            fail("Failure is expected");
        } catch(InsufficientOperationalNodesException e) { /* expected */
        }
        assertOperationalNodes(9);

        cluster = getNineNodeCluster();

        s2 = getStore(cluster,
                      1,
                      9,
                      9,
                      9,
                      0,
                      RoutingStrategyType.TO_ALL_STRATEGY,
                      new UnreachableStoreException("no go"));
        try {
            s2.delete(aKey, new VectorClock());
            fail("Failure is expected");
        } catch(InsufficientOperationalNodesException e) { /* expected */
        }
        assertOperationalNodes(0);
    }

    /**
     * Tests that getAll works correctly with a node down in a two node cluster.
     */
    public void testGetAllWithNodeDown() throws Exception {
        cluster = VoldemortTestConstants.getTwoNodeCluster();

        RoutedStore routedStore = getStore(cluster, 1, 2, 1, 0);
        Store<ByteArray, byte[]> store = new InconsistencyResolvingStore<ByteArray, byte[]>(routedStore,
                                                                                            new VectorClockInconsistencyResolver<byte[]>());

        Map<ByteArray, byte[]> expectedValues = Maps.newHashMap();
        for(byte i = 1; i < 11; ++i) {
            ByteArray key = new ByteArray(new byte[] { i });
            byte[] value = new byte[] { (byte) (i + 50) };
            store.put(key, Versioned.value(value));
            expectedValues.put(key, value);
        }

        nodeAvailabilityDetector.recordException(cluster.getNodes().iterator().next(), null);

        Map<ByteArray, List<Versioned<byte[]>>> all = store.getAll(expectedValues.keySet());
        assertEquals(expectedValues.size(), all.size());
        for(Map.Entry<ByteArray, List<Versioned<byte[]>>> mapEntry: all.entrySet()) {
            byte[] value = expectedValues.get(mapEntry.getKey());
            assertEquals(new ByteArray(value), new ByteArray(mapEntry.getValue().get(0).getValue()));
        }
    }

    public void testGetAllWithFailingStore() throws Exception {
        cluster = VoldemortTestConstants.getTwoNodeCluster();

        StoreDefinition storeDef = ServerTestUtils.getStoreDef("test",
                                                               2,
                                                               1,
                                                               1,
                                                               2,
                                                               2,
                                                               RoutingStrategyType.CONSISTENT_STRATEGY);

        Map<Integer, Store<ByteArray, byte[]>> subStores = Maps.newHashMap();
        subStores.put(Iterables.get(cluster.getNodes(), 0).getId(),
                      new InMemoryStorageEngine<ByteArray, byte[]>("test"));
        subStores.put(Iterables.get(cluster.getNodes(), 1).getId(),
                      new FailingReadsStore<ByteArray, byte[]>("test"));

        setNodeAvailabilityDetector(subStores);

        RoutedStore routedStore = new RoutedStore("test",
                                                  subStores,
                                                  cluster,
                                                  storeDef,
                                                  1,
                                                  true,
                                                  1000L,
                                                  nodeAvailabilityDetector);

        Store<ByteArray, byte[]> store = new InconsistencyResolvingStore<ByteArray, byte[]>(routedStore,
                                                                                            new VectorClockInconsistencyResolver<byte[]>());

        Map<ByteArray, byte[]> expectedValues = Maps.newHashMap();
        for(byte i = 1; i < 11; ++i) {
            ByteArray key = new ByteArray(new byte[] { i });
            byte[] value = new byte[] { (byte) (i + 50) };
            store.put(key, Versioned.value(value));
            expectedValues.put(key, value);
        }

        Map<ByteArray, List<Versioned<byte[]>>> all = store.getAll(expectedValues.keySet());
        assertEquals(expectedValues.size(), all.size());
        for(Map.Entry<ByteArray, List<Versioned<byte[]>>> mapEntry: all.entrySet()) {
            byte[] value = expectedValues.get(mapEntry.getKey());
            assertEquals(new ByteArray(value), new ByteArray(mapEntry.getValue().get(0).getValue()));
        }
    }

    /**
     * One node up, two preferred reads and one required read. See:
     * 
     * http://github.com/voldemort/voldemort/issues#issue/18
     */
    public void testGetAllWithMorePreferredReadsThanNodes() throws Exception {
        cluster = VoldemortTestConstants.getTwoNodeCluster();

        StoreDefinition storeDef = ServerTestUtils.getStoreDef("test",
                                                               2,
                                                               2,
                                                               1,
                                                               2,
                                                               2,
                                                               RoutingStrategyType.CONSISTENT_STRATEGY);

        Map<Integer, Store<ByteArray, byte[]>> subStores = Maps.newHashMap();
        subStores.put(Iterables.get(cluster.getNodes(), 0).getId(),
                      new InMemoryStorageEngine<ByteArray, byte[]>("test"));
        subStores.put(Iterables.get(cluster.getNodes(), 1).getId(),
                      new InMemoryStorageEngine<ByteArray, byte[]>("test"));

        setNodeAvailabilityDetector(subStores);
        RoutedStore routedStore = new RoutedStore("test",
                                                  subStores,
                                                  cluster,
                                                  storeDef,
                                                  1,
                                                  true,
                                                  1000L,
                                                  nodeAvailabilityDetector);

        Store<ByteArray, byte[]> store = new InconsistencyResolvingStore<ByteArray, byte[]>(routedStore,
                                                                                            new VectorClockInconsistencyResolver<byte[]>());
        store.put(aKey, Versioned.value(aValue));
        nodeAvailabilityDetector.recordException(cluster.getNodes().iterator().next(), null);
        Map<ByteArray, List<Versioned<byte[]>>> all = store.getAll(Arrays.asList(aKey));
        assertEquals(1, all.size());
        assertTrue(Arrays.equals(aValue, all.values().iterator().next().get(0).getValue()));
    }

    /**
     * See Issue #89: Sequential retrieval in RoutedStore.get doesn't consider
     * repairReads.
     */
    public void testReadRepairWithFailures() throws Exception {
        cluster = getNineNodeCluster();

        RoutedStore routedStore = getStore(cluster,
                                           cluster.getNumberOfNodes() - 1,
                                           cluster.getNumberOfNodes() - 1,
                                           1,
                                           0);
        // Disable node 1 so that the first put also goes to the last node
        nodeAvailabilityDetector.recordException(Iterables.get(cluster.getNodes(), 1), null);
        Store<ByteArray, byte[]> store = new InconsistencyResolvingStore<ByteArray, byte[]>(routedStore,
                                                                                            new VectorClockInconsistencyResolver<byte[]>());
        store.put(aKey, new Versioned<byte[]>(aValue));

        byte[] anotherValue = "john".getBytes();

        // Disable the last node and enable node 1 to prevent the last node from
        // getting the new version
        nodeAvailabilityDetector.recordException(Iterables.getLast(cluster.getNodes()), null);
        nodeAvailabilityDetector.recordSuccess(Iterables.get(cluster.getNodes(), 1));
        VectorClock clock = getClock(1);
        store.put(aKey, new Versioned<byte[]>(anotherValue, clock));

        // Enable last node and disable node 1, the following get should cause a
        // read repair on the last node in the code path that is only executed
        // if there are failures.
        nodeAvailabilityDetector.recordException(Iterables.get(cluster.getNodes(), 1), null);
        nodeAvailabilityDetector.recordSuccess(Iterables.getLast(cluster.getNodes()));
        List<Versioned<byte[]>> versioneds = store.get(aKey);
        assertEquals(1, versioneds.size());
        assertEquals(new ByteArray(anotherValue), new ByteArray(versioneds.get(0).getValue()));

        // Read repairs are done asynchronously, so we sleep for a short period.
        // It may be a good idea to use a synchronous executor service.
        Thread.sleep(100);
        for(Store<ByteArray, byte[]> innerStore: routedStore.getInnerStores().values()) {
            List<Versioned<byte[]>> innerVersioneds = innerStore.get(aKey);
            assertEquals(1, versioneds.size());
            assertEquals(new ByteArray(anotherValue), new ByteArray(innerVersioneds.get(0)
                                                                                   .getValue()));
        }
    }

    /**
     * See issue #134: RoutedStore put() doesn't wait for enough attempts to
     * succeed
     * 
     * This issue would only happen with one node down and another that was slow
     * to respond.
     */
    public void testPutWithOneNodeDownAndOneNodeSlow() throws Exception {
        cluster = VoldemortTestConstants.getThreeNodeCluster();
        StoreDefinition storeDef = ServerTestUtils.getStoreDef("test",
                                                               3,
                                                               2,
                                                               2,
                                                               2,
                                                               2,
                                                               RoutingStrategyType.CONSISTENT_STRATEGY);

        /* The key used causes the nodes selected for writing to be [2, 0, 1] */
        Map<Integer, Store<ByteArray, byte[]>> subStores = Maps.newHashMap();
        subStores.put(Iterables.get(cluster.getNodes(), 2).getId(),
                      new InMemoryStorageEngine<ByteArray, byte[]>("test"));
        subStores.put(Iterables.get(cluster.getNodes(), 0).getId(),
                      new FailingStore<ByteArray, byte[]>("test"));
        /*
         * The bug would only show itself if the second successful required
         * write was slow (but still within the timeout).
         */
        subStores.put(Iterables.get(cluster.getNodes(), 1).getId(),
                      new SleepyStore<ByteArray, byte[]>(100,
                                                         new InMemoryStorageEngine<ByteArray, byte[]>("test")));
        setNodeAvailabilityDetector(subStores);
        RoutedStore routedStore = new RoutedStore("test",
                                                  subStores,
                                                  cluster,
                                                  storeDef,
                                                  1,
                                                  true,
                                                  1000L,
                                                  nodeAvailabilityDetector);

        Store<ByteArray, byte[]> store = new InconsistencyResolvingStore<ByteArray, byte[]>(routedStore,
                                                                                            new VectorClockInconsistencyResolver<byte[]>());
        store.put(aKey, new Versioned<byte[]>(aValue));
    }

    public void testPutTimeout() throws Exception {
        int timeout = 50;
        StoreDefinition definition = new StoreDefinition("test",
                                                         "foo",
                                                         new SerializerDefinition("test"),
                                                         new SerializerDefinition("test"),
                                                         RoutingTier.CLIENT,
                                                         RoutingStrategyType.CONSISTENT_STRATEGY,
                                                         3,
                                                         3,
                                                         3,
                                                         3,
                                                         3,
                                                         0);
        Map<Integer, Store<ByteArray, byte[]>> stores = new HashMap<Integer, Store<ByteArray, byte[]>>();
        List<Node> nodes = new ArrayList<Node>();
        int totalDelay = 0;
        for(int i = 0; i < 3; i++) {
            int delay = 4 + i * timeout;
            totalDelay += delay;
            stores.put(i,
                       new SleepyStore<ByteArray, byte[]>(delay,
                                                          new InMemoryStorageEngine<ByteArray, byte[]>("test")));
            List<Integer> partitions = Arrays.asList(i);
            nodes.add(new Node(i, "none", 0, 0, partitions));
        }

        setNodeAvailabilityDetector(stores);

        RoutedStore routedStore = new RoutedStore("test",
                                                  stores,
                                                  new Cluster("test", nodes),
                                                  definition,
                                                  3,
                                                  false,
                                                  timeout,
                                                  nodeAvailabilityDetector);

        long start = System.currentTimeMillis();
        try {
            routedStore.put(new ByteArray("test".getBytes()),
                            new Versioned<byte[]>(new byte[] { 1 }));
            fail("Should have thrown");
        } catch(InsufficientOperationalNodesException e) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed + " < " + totalDelay, elapsed < totalDelay);
        }
    }

    public void assertOperationalNodes(int expected) {
        int found = 0;
        for(Node n: cluster.getNodes())
            if(nodeAvailabilityDetector.isAvailable(n))
                found++;
        assertEquals("Number of operational nodes not what was expected.", expected, found);
    }

    private void setNodeAvailabilityDetector(Map<Integer, Store<ByteArray, byte[]>> subStores)
            throws Exception {
        nodeAvailabilityDetector = nodeAvailabilityDetectorClass.newInstance();
        nodeAvailabilityDetector.setNodeBannageMs(10000);
        nodeAvailabilityDetector.setStores(subStores);
    }

}
