package org.horizon.atomic;

import org.horizon.Cache;
import org.horizon.config.Configuration;
import org.horizon.lock.TimeoutException;
import org.horizon.manager.CacheManager;
import org.horizon.test.TestingUtil;
import org.horizon.test.fwk.TestCacheManagerFactory;
import org.horizon.transaction.DummyTransactionManagerLookup;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.transaction.TransactionManager;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Tester class for AtomicMapCache.
 *
 * @author  Mircea.Markus@jboss.com
 */
@Test(groups = "functional", testName = "atomic.AtomicHashMapConcurrencyTest")
public class AtomicHashMapConcurrencyTest {

   public static final String KEY = "key";
   AtomicMapCache<String, String> cache;
   TransactionManager tm;

   enum Operation {
      PUT,
      COMMIT,
      READ
   }

   @BeforeMethod
   @SuppressWarnings("unchecked")
   public void setUp() {
      Configuration c = new Configuration();
      c.setLockAcquisitionTimeout(500);
      // these 2 need to be set to use the AtomicMapCache
      c.setTransactionManagerLookupClass(DummyTransactionManagerLookup.class.getName());
      c.setInvocationBatchingEnabled(true);
      CacheManager cm = TestCacheManagerFactory.createCacheManager(c);
      Cache basicCache = cm.getCache();
      cache = (AtomicMapCache<String, String>) basicCache;
      tm = TestingUtil.getTransactionManager(cache);
   }

   @AfterMethod
   public void tearDown() {
      try {
         tm.rollback();
      } catch (Exception e) {
      }
   }

   public void testConcurrentCreate() throws Exception {
      tm.begin();
      cache.getAtomicMap(KEY);
      OtherThread ot = new OtherThread();
      ot.start();
      Object response = ot.response.take();
      assert response instanceof TimeoutException;
   }

   public void testConcurrentModifications() throws Exception {
      AtomicMap<Integer, String> atomicMap = cache.getAtomicMap(KEY);
      tm.begin();
      atomicMap.put(1,"");
      OtherThread ot = new OtherThread();
      ot.start();
      ot.toExecute.put(Operation.PUT);
      Object response = ot.response.take();
      assert response instanceof TimeoutException;
   }

   public void testReadAfterTxStarted() throws Exception {
      AtomicMap<Integer, String> atomicMap = cache.getAtomicMap(KEY);
      atomicMap.put(1, "existing");
      tm.begin();
      atomicMap.put(1,"newVal");
      OtherThread ot = new OtherThread();
      ot.start();
      ot.toExecute.put(Operation.READ);
      Object response = ot.response.take();
      assert response.equals("existing");
      tm.commit();
      assert atomicMap.get(1).equals("newVal");
      ot.toExecute.put(Operation.READ);
      response = ot.response.take();
      assert response.equals("newVal");
   }

   public class OtherThread extends Thread {

      public OtherThread() {
         super("OtherThread");
      }

      BlockingQueue response = new ArrayBlockingQueue(1);

      BlockingQueue<Operation> toExecute = new ArrayBlockingQueue<Operation>(1);

      @Override
      public void run() {
         try {
            tm.begin();
            AtomicMap<Integer, String> atomicMap = cache.getAtomicMap(KEY);
            boolean notCommited = true;
            while (notCommited) {
               Operation op = toExecute.take();
               switch (op) {
                  case PUT: {
                     atomicMap.put(1, "val");
                     response.put(new Object());
                     break;
                  }
                  case READ: {
                     String val = atomicMap.get(1);
                     response.put(String.valueOf(val));
                     break;
                  }
                  case COMMIT: {
                     tm.commit();
                     response.put(new Object());
                     notCommited = false;
                     break;
                  }
               }
            }
         } catch (Exception e) {
            try {
               response.put(e);
            } catch (InterruptedException e1) {
               e1.printStackTrace();
            }
            e.printStackTrace();
         }
      }
   }
}
