package org.hazelcast.client;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.io.IOException;
import java.util.Properties;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HazelcastMainClient {

    private final static ILogger log = Logger.getLogger(HazelcastMainClient.class);

    private HazelcastInstance CLIENT;
    private ExecutorService SERVICE;
    private String propertiesName = "/HazelcastMemcachedClient.properties";
    private String MAP_NAME = "benchmark_map";
    private IMap MAP;

    private AtomicInteger txnCounter;
    private AtomicLong latencyBucket;
    private AtomicInteger latencyCounter;
    private Properties properties;
    private AtomicBoolean isStopped;
    private int maxKeys;
    private int ttl;
    private int duration;
    private int SERVICE_POOL_SIZE;

    private Thread latencyMonitor;
    private Thread tpsMonitor;

    HazelcastMainClient() {
        setProperties();
        initGlobal();
        initServicePool();
        initConnection();
        String opType = properties.getProperty("operation_type");
        if(opType.equalsIgnoreCase("load"))
            loadData();
        else {
            startTPSMonitor();
            startLatencyMonitor();
            mutateCluster();
        }
    }

    private void initGlobal() {
        maxKeys = Integer.valueOf(properties.getProperty("max_keys"));
        ttl = Integer.valueOf(properties.getProperty("ttl"));
    }

    private void initConnection() {
        ClientConfig config = new ClientConfig();
        config.getNetworkConfig().addAddress(properties.getProperty("hazelcast_server_url"));
        CLIENT = HazelcastClient.newHazelcastClient(config);
        MAP = CLIENT.getMap(MAP_NAME);
    }

    private void initTestClock() {
        isStopped = new AtomicBoolean();
        duration = Integer.valueOf(properties.getProperty("test_duration"));

        final TimerTask shutdownTask = new TimerTask() {
            @Override
            public void run() {
                isStopped.set(true);
                initiateShutdown();
            }
        };
        Thread timer = new Thread() {
            public void run() {
                new Timer().schedule(shutdownTask, duration*1000);
            }
        };
        timer.setDaemon(true);
        timer.start();
    }

    private void startLatencyMonitor() {
        latencyBucket = new AtomicLong();
        latencyCounter = new AtomicInteger();
        latencyMonitor = new Thread() {
            public void run() {
                while(!isInterrupted()) {
                    try {
                        sleep(5000);
                        long latency = latencyBucket.getAndSet(0)/latencyCounter.getAndSet(0);
                        log.info("Average latency in last 5 seconds: "+ (latency/1000) + " us");
                    } catch (InterruptedException e) {
                    }
                }
            }
        };
        latencyMonitor.setDaemon(true);
        latencyMonitor.start();
    }

    private void startTPSMonitor() {
        txnCounter = new AtomicInteger();
        final int tpsInterval = Integer.valueOf(properties.getProperty("tps_interval"));
        tpsMonitor = new Thread() {
            public void run() {
                try {
                    while(!isInterrupted()) {
                        sleep(tpsInterval * 1000);
                        log.info("Transactions processed per second: "+ txnCounter.getAndSet(0)/tpsInterval);
                    }
                } catch (InterruptedException e) {
                }
            }
        };
        tpsMonitor.setDaemon(true);
        tpsMonitor.start();
    }


    private void mutateCluster() {
        initTestClock();

        log.info("Starting benchmark test for "+duration+" seconds");

        final int readOpsPercentile = Integer.valueOf(properties.getProperty("read_operations_percentile"));
        for(int i=0; i<SERVICE_POOL_SIZE; i++) {
            SERVICE.execute(new Runnable() {
                public void run() {
                    Random rand = new Random();
                    while(true) {
                        String key = buildKey(rand.nextInt(maxKeys));
                        long start = System.nanoTime();
                        if (txnCounter.get() % 10 < readOpsPercentile) {
                            get(key);
                        } else {
                            put(key, getValue(ThreadLocalRandom.current().nextInt(4, 13)));
                        }
                        long latency = System.nanoTime() - start;
                        txnCounter.incrementAndGet();
                        latencyCounter.incrementAndGet();
                        latencyBucket.addAndGet(latency);
                    }
                }
            });
        }
    }

    private String buildKey(int keyID) {
        return "K"+keyID;
    }

    private void put(String key, byte[] value) {
        MAP.set(key, value);
    }

    private Object get(String key) {
        return MAP.get(key);
    }

    private void initiateShutdown() {
        log.info("Test complete. Initiating shutdown...");

        latencyMonitor.interrupt();
        tpsMonitor.interrupt();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }
        System.exit(0);
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
        int LOADER_THREAD_COUNT = Integer.valueOf(properties.getProperty("loader_threads"));
        ExecutorService LOADERS = Executors.newFixedThreadPool(LOADER_THREAD_COUNT);

        CountDownLatch latch = new CountDownLatch(LOADER_THREAD_COUNT);

        int perThread = maxKeys/LOADER_THREAD_COUNT;
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
        log.info("Load complete.. ");

       // CLIENT.shutdown();
    }

    class Loader implements Runnable {
        private int start;
        private int last;

        private CountDownLatch latch;

        Loader(int start, int last, CountDownLatch latch) {
            this.start = start;
            this.last = last;
            this.latch = latch;
        }

        public void run() {
            int counter =0;
            for(int i=start; i< last; i++) {
                put(buildKey(i), getValue(ThreadLocalRandom.current().nextInt(4, 13)));
                counter++;
            }
            log.info("Entries loaded by this thread: "+counter);
            latch.countDown();
        }
    }

    private byte[] getValue(int type) {
        byte[] value;
        switch(type) {
            case 4:
                value = new byte[4096];
                break;
            case 5:
                value = new byte[5120];
                break;
            case 6:
                value = new byte[6144];
                break;
            case 7:
                value = new byte[7168];
                break;
            case 8:
                value = new byte[8192];
                break;
            case 9:
                value = new byte[9216];
                break;
            case 10:
                value = new byte[10240];
                break;
            case 11:
                value = new byte[11264];
                break;
            case 12:
                value = new byte[12288];
                break;
            default:
                value = new byte[1024];
                break;
        }
        return value;
    }

    private void initServicePool() {
        SERVICE_POOL_SIZE = Integer.valueOf(properties.getProperty("service_pool_size"));
        SERVICE = Executors.newFixedThreadPool(SERVICE_POOL_SIZE);
    }

    public static void main(String[] args) {
        new HazelcastMainClient();
    }
}
