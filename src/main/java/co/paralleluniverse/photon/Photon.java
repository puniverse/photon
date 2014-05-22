package co.paralleluniverse.photon;

import co.paralleluniverse.common.benchmark.StripedLongTimeSeries;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.httpclient.FiberHttpClient;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.IOReactorException;

public class Photon {
    private static final int TIMEOUT = 60000;
    static final int MAX_CONN = 150000;
    static final int DURATION = 20;
    public static MetricRegistry metrics = new MetricRegistry();

    public static void main(String[] args) throws InterruptedException, IOReactorException, IOException {
        if (args.length < 2) {
            System.out.println("args are " + Arrays.toString(args));
            System.out.println("Usage: ClientTesters url rate");
            System.out.println("Example:\n\tClientTesters http://localhost:8080/regular?sleep=50 500");
            args = new String[]{"http://www.google.com", "1"};
//            System.exit(0);
        }
        final ConcurrentHashMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        final HttpGet request = new HttpGet(args[0]);
        final int rate = Integer.parseInt(args[1]);
        System.out.println("configuration: " + request + " " + rate);
        final ThreadFactory deamonTF = new ThreadFactoryBuilder().setDaemon(true).build();

        try (CloseableHttpClient client = new FiberHttpClient(createDefaultHttpAsyncClient(null))) {
            System.out.println(new Date() + " starting..");
            int num = DURATION * rate;
            boolean print = true;
            int tenth = num / 10;
            Timer httpTimer = metrics.timer("httpTimer");
            CountDownLatch cdl = new CountDownLatch(num);
            Semaphore sem = new Semaphore(MAX_CONN);
            deamonTF.newThread(() -> {
                final RateLimiter rl = RateLimiter.create(rate);

                for (int i = 0; i < num; i++) {
                    rl.acquire();
                    if (sem.availablePermits() == 0)
                        System.out.println(new Date() + " waiting...");
                    sem.acquireUninterruptibly();

                    final int constCounter = i + 1;
                    new Fiber<Void>(() -> {
                        final Timer.Context ctx = httpTimer.time();
                        if (print && constCounter % tenth == 0)
                            System.out.println(new Date() + " sent(" + constCounter + ") " + constCounter / tenth * 10 + "%..."
                                    + " openConnections: " + (MAX_CONN - sem.availablePermits()));
                        try {
                            client.execute(request).close();
                        } catch (IOException ex) {
                            errors.putIfAbsent(ex.getClass().getName(), new AtomicInteger());
                            errors.get(ex.getClass().getName()).incrementAndGet();
                        } finally {
                            sem.release();
                            cdl.countDown();
                            ctx.stop();
                            long count = num - cdl.getCount();
                            if (print && (count) % tenth == 0)
                                System.out.print(new Date() + " responeded (" + count + ") " + ((count) / tenth * 10) + "%..."
                                        + " mean: " + httpTimer.getSnapshot().getMean()
                                        + " 95th: " + httpTimer.getSnapshot().get95thPercentile()
                                        + " 99th: " + httpTimer.getSnapshot().get99thPercentile());
                        }
                    }).start();
                }
            }).start();
            cdl.await();
            errors.entrySet().stream().forEach(p -> System.out.println(p.getKey() + " " + p.getValue()));
        }
    }

    public static CloseableHttpAsyncClient createDefaultHttpAsyncClient(final InetAddress address) throws IOReactorException, UnknownHostException {
        PoolingNHttpClientConnectionManager mngr = new PoolingNHttpClientConnectionManager(new DefaultConnectingIOReactor(IOReactorConfig.custom().
                setConnectTimeout(TIMEOUT).
                setIoThreadCount(10).
                setSoTimeout(TIMEOUT).
                build()));
        mngr.setDefaultMaxPerRoute(MAX_CONN);
        mngr.setMaxTotal(MAX_CONN);
        return HttpAsyncClientBuilder.create().
                setConnectionManager(mngr).
                setDefaultRequestConfig(RequestConfig.custom().setLocalAddress(address).build()).build();
    }
}
