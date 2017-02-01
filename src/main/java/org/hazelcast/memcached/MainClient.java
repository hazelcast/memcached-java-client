package org.hazelcast.memcached;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.BinaryConnectionFactory;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.OperationFuture;

import java.io.IOException;
import java.util.Properties;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainClient {

    private MemcachedClient CLIENT;
    private ExecutorService SERVICE;
    private String propertiesName = "/HazelcastMemcachedClient.properties";

    private AtomicInteger TxnCounter;
    private Properties properties;
    private boolean isStopped;
    private int maxKeys;
    private int ttl;

    MainClient() {
        setProperties();
        initGlobal();
        initServicePool();
        startTPSMonitor();
        initConnection();
        String opType = properties.getProperty("operation_type");
        if(opType.equalsIgnoreCase("load"))
            loadData();
        else {
            mutateCluster();
        }
    }

    private void initGlobal() {
        maxKeys = Integer.valueOf(properties.getProperty("max_keys"));
        ttl = Integer.valueOf(properties.getProperty("ttl"));
    }

    private void initConnection() {
        try {
            CLIENT = new MemcachedClient(new BinaryConnectionFactory(), AddrUtil.getAddresses(properties.getProperty("server_url")));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initTestClock() {
        final int duration = Integer.valueOf(properties.getProperty("test_duration"));

        final TimerTask task = new TimerTask() {
            @Override
            public void run() {
                isStopped = true;
            }
        };
        Thread timer = new Thread() {
            public void run() {
                new Timer().schedule(task, duration*1000);
            }
        };
        timer.setDaemon(true);
        timer.start();
    }

    private void mutateCluster() {
        initTestClock();

        final int readOpsPercentile = Integer.valueOf(properties.getProperty("read_operations_percentile"));


        Random rand = new Random();
        while(!isStopped) {
            final String key = buildKey(rand.nextInt(maxKeys));
            SERVICE.execute(new Runnable() {
                public void run() {

                    TxnCounter.incrementAndGet();
                    if(TxnCounter.get() % 10 < readOpsPercentile) {
                        get(key);
                    } else {
                        put(key, new byte[1024]);
                    }
                }
            });
        }
        initiateShutdown();
    }

    private String buildKey(int keyID) {
        return "K"+keyID;
    }

    private OperationFuture<Boolean> put(String key, byte[] value) {
        return CLIENT.set(key, ttl, value);
    }

    private Object get(String key) {
        return CLIENT.get(key);
    }

    private void initiateShutdown() {
        System.out.println("Test complete. Initiating shutdown...");
        SERVICE.shutdown();
        CLIENT.shutdown();
    }

    private void setProperties() {
        try {
            properties = new Properties();
            properties.load(getClass().getResourceAsStream(propertiesName));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadData() {
        int LOADER_THREAD_COUNT = 2;
        ExecutorService LOADERS = Executors.newFixedThreadPool(LOADER_THREAD_COUNT);

        int perThread = maxKeys/LOADER_THREAD_COUNT;

        CountDownLatch latch = new CountDownLatch(LOADER_THREAD_COUNT);

        for(int i=0; i<LOADER_THREAD_COUNT; i++) {
            int start = perThread * i;
            int last = perThread * (i+1);
            LOADERS.execute(new Loader(start, last, latch));
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Load complete.. ");
        try {
            LOADERS.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        CLIENT.shutdown();
    }

    class Loader implements Runnable {
        int start;
        int last;
        CountDownLatch latch;

        Loader(int start, int last, CountDownLatch latch) {
            this.start = start;
            this.last = last;
            this.latch = latch;
        }

        public void run() {
            int counter =0;
            for(int i=start; i< last; i++) {
                put(buildKey(i), new byte[1024]);
                counter++;
            }
            System.out.println("Entries loaded by this thread: "+counter);
            latch.countDown();
        }
    }

    private void initServicePool() {
        SERVICE = Executors.newFixedThreadPool(Integer.valueOf(properties.getProperty("service_pool_size")));
    }

    private void startTPSMonitor() {
        TxnCounter = new AtomicInteger();
        final int tpsInterval = Integer.valueOf(properties.getProperty("tps_interval"));
        Thread monitor = new Thread() {
            public void run() {
                    try {
                        while(!interrupted()) {
                            sleep(tpsInterval * 1000);
                            System.out.println("Transactions processed per second: "+TxnCounter.getAndSet(0)/tpsInterval);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
            }
        };
        monitor.setDaemon(true);
        monitor.start();
    }


    public static void main(String[] args) {
        new MainClient();
    }
}
