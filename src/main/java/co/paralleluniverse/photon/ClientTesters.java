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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.reactor.IOReactorException;

public class ClientTesters {
    private static final int TIMEOUT = 60000;
    static final int MAX_CONN = 150000;
    static final int WARMUP = 3;
    static final int DURATION = 20;
    public static MetricRegistry metrics = new MetricRegistry();

    public static void main(String[] args) throws InterruptedException, IOReactorException, IOException {
        if (args.length < 4) {
            System.out.println("args are " + Arrays.toString(args));
            System.out.println("Usage: ClientTesters baseUrl servletPath sleepTime rate");
            System.out.println("Example:\n\tClientTesters http://localhost:8080 /regular 10 500");
            args = new String[]{"http://localhost:8080", "/target", "5000", "1"};
//            System.exit(0);
        }
        final ConcurrentHashMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        final String HOST = args[0];
        final String URL1 = HOST + args[1] + "?sleep=" + args[2];
        final String URL2 = HOST + "/simple";
        final int rate = Integer.parseInt(args[3]);
        System.out.println("configuration: " + HOST + " " + URL1 + " " + rate);
        final ThreadFactory deamonTF = new ThreadFactoryBuilder().setDaemon(true).build();

        final StripedLongTimeSeries opendUrl1 = new StripedLongTimeSeries(100000, false);
        final StripedLongTimeSeries latUrl2 = new StripedLongTimeSeries(100000, false);

//        try (CloseableHttpAsyncClient ahc = HttpAsyncClientBuilder.create().setConnectionManager(mngr).build()) {
//        try (CloseableHttpAsyncClient ahc = HttpAsyncClientBuilder.create().setMaxConnPerRoute(9999).setMaxConnTotal(9999).build()) {
//        InetAddress[] addresses = new InetAddress[6];
//        for (int i = 0; i < addresses.length; i++)
//            addresses[i] = new InetSocketAddress(URL2, i);InetAddress.getByName("192.168.1."+(i+1));
//        CloseableHttpClient[] clients = new CloseableHttpClient[addresses.length];
//        for (int i = 0; i < clients.length; i++)
//            clients[i] = new FiberHttpClient(createDefaultHttpAsyncClient(addresses[i]));
        try (CloseableHttpClient client = new FiberHttpClient(createDefaultHttpAsyncClient(null))) {
            System.out.println("warming up..");
            call(new HttpGet(URL2), rate, 5, null, null, null, MAX_CONN, client, deamonTF).await();
            Thread.sleep(5000);

            System.out.println(new Date() + " starting..");
            final long start = System.nanoTime();
            CountDownLatch latch1 = call(new HttpGet(URL1), rate, DURATION, opendUrl1, latUrl2, errors, MAX_CONN, client, deamonTF);
//            call(new HttpGet(URL2), 5, DURATION, null, latUrl2, errors, MAX_CONN, client, deamonTF).await();
            latch1.await();

//            latUrl2.getRecords().forEach(rec -> System.out.println("url2_lat " + TimeUnit.NANOSECONDS.toMillis(rec.timestamp - start) + " " + rec.value));
            errors.entrySet().stream().forEach(p -> {
                System.out.println(p.getKey() + " " + p.getValue());
            });
//            opendUrl1.getRecords().forEach(rec -> System.out.println("url1_cnt " + TimeUnit.NANOSECONDS.toMillis(rec.timestamp - start) + " " + rec.value));
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

    private static CountDownLatch call(
            final HttpGet httpGet, final int rate, int duration, final StripedLongTimeSeries openedCountSeries, final StripedLongTimeSeries latancySeries,
            final ConcurrentHashMap<String, AtomicInteger> errorsCounters, final int maxOpen, final CloseableHttpClient client, final ThreadFactory deamonTF) {
        int num = duration * rate;
        int tenth = num / 10;
        Timer httpTimer = metrics.timer(httpGet.getURI().toString() + rate);
        Timer fiberTimer = metrics.timer("fiber" + httpGet.getURI().toString() + rate);
        CountDownLatch cdl = new CountDownLatch(num);
        Semaphore sem = new Semaphore(maxOpen);
        Thread url1Thread = deamonTF.newThread(() -> {
            final RateLimiter rl = RateLimiter.create(rate);

            for (int i = 0, j = 0; i < num; i++) {
                rl.acquire();
                Timer.Context fiberCtx = fiberTimer.time();
                if (openedCountSeries != null)
                    openedCountSeries.record(System.nanoTime(), maxOpen - sem.availablePermits());
                if (sem.availablePermits() == 0)
                    System.out.println(new Date() + " waiting...");
                sem.acquireUninterruptibly();
                final int k = i + 1;
                if (openedCountSeries != null && k % tenth == 0)
                    System.out.println(new Date() + " sent(" + k + ") " + k / tenth * 10 + "%...");

//                if (openedCountSeries != null)
//                    System.out.println(">" + (maxOpen - sem.availablePermits()));
                new Fiber<Void>(() -> {
                    fiberCtx.stop();
                    long reqStart = System.nanoTime();
                    final Timer.Context ctx = httpTimer.time();
                    try (CloseableHttpResponse resp = client.execute(httpGet)) {
                        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reqStart);
                        if (resp.getStatusLine().getStatusCode() == 200) {
//                            if (millis > 1000)
//                                System.out.println("millis " + millis);
//                            if (latancySeries != null)
//                                latancySeries.record(reqStart, millis);
                        }
                    } catch (IOException ex) {
                        if (errorsCounters != null) {
                            errorsCounters.putIfAbsent(ex.getClass().getName(), new AtomicInteger());
                            errorsCounters.get(ex.getClass().getName()).incrementAndGet();
                        }
                    } finally {
                        sem.release();
                        cdl.countDown();
                        ctx.stop();
                        long count = num - cdl.getCount();
                        if (openedCountSeries != null && (count) % tenth == 0) {
//                            System.out.println("dd "+ (num-cdl.getCount()) + " : "+ tenth + " : "+(num-cdl.getCount()% tenth));
                            System.out.print(new Date() + " responeded (" + count + ") " + ((count) / tenth * 10) + "%...");
                            System.out.println(" " + httpTimer.getSnapshot().getMean() + " " + fiberTimer.getSnapshot().getMean());
                        }

//                        }
//                        if (openedCountSeries != null)
//                            System.out.println("<" + (maxOpen - sem.availablePermits()));
                    }
                }).start();
            }
        });
        url1Thread.start();
        return cdl;
    }
}
