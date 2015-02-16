/*
 * PHOTON
 * Copyright (C) 2015, Parallel Universe Software Co. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.paralleluniverse.photon;

import org.HdrHistogram.HistogramData;
import co.paralleluniverse.common.benchmark.StripedHistogram;
import co.paralleluniverse.common.benchmark.StripedTimeSeries;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.httpclient.FiberHttpClient;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.RateLimiter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.ExceptionEvent;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Photon {

    private static final String rateDefault = "10";
    private static final String testNameDefault = "test";
    private static final String durationDefault = "10";
    private static final String maxConnectionsDefault = "150000";
    private static final String timeoutDefault = "10000";
    private static final String printCycleDefault = "1000";
    private static final String checkCycleDefault = "10000";

    public static void main(final String[] args) throws InterruptedException, IOException {

        final Options options = new Options();
        options.addOption("rate", true, "Requests per second (default " + rateDefault + ")");
        options.addOption("duration", true, "Minimum test duration in seconds: will wait for <duration> * <rate> requests to terminate or, if progress check enabled, no progress after <duration> (default " + durationDefault + ")");
        options.addOption("maxconnections", true, "Maximum number of open connections (default " + maxConnectionsDefault + ")");
        options.addOption("timeout", true, "Connection and read timeout in millis (default " + timeoutDefault + ")");
        options.addOption("print", true, "Print cycle in millis, 0 to disable intermediate statistics (default " + printCycleDefault + ")");
        options.addOption("check", true, "Progress check cycle in millis, 0 to disable progress check (default " + checkCycleDefault + ")");
        options.addOption("stats", false, "Print full statistics when finish (default false)");
        options.addOption("minmax", false, "Print min/mean/stddev/max stats when finish (default false)");
        options.addOption("name", true, "Test name to print in the statistics (default '" + testNameDefault +  "')");
        options.addOption("help", false, "Print help");

        try {
            final CommandLine cmd = new BasicParser().parse(options, args);
            final String[] ar = cmd.getArgs();
            if (cmd.hasOption("help") || ar.length != 1)
                printUsageAndExit(options);

            final String url = ar[0];

            final int timeout = Integer.parseInt(cmd.getOptionValue("timeout", timeoutDefault));
            final int maxConnections = Integer.parseInt(cmd.getOptionValue("maxconnections", maxConnectionsDefault));
            final int duration = Integer.parseInt(cmd.getOptionValue("duration", durationDefault));
            final int printCycle = Integer.parseInt(cmd.getOptionValue("print", printCycleDefault));
            final int checkCycle = Integer.parseInt(cmd.getOptionValue("check", checkCycleDefault));
            final String testName = cmd.getOptionValue("name", testNameDefault);
            final int rate = Integer.parseInt(cmd.getOptionValue("rate", rateDefault));

            final MetricRegistry metrics = new MetricRegistry();
            final Meter requestMeter = metrics.meter("request");
            final Meter responseMeter = metrics.meter("response");
            final Meter errorsMeter = metrics.meter("errors");
            final Logger log = LoggerFactory.getLogger(Photon.class);
            final ConcurrentHashMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();
            final HttpGet request = new HttpGet(url);
            final StripedTimeSeries<Long> sts = new StripedTimeSeries<>(30000, false);
            final StripedHistogram sh = new StripedHistogram(60000, 5);

            log.info("name: "+testName+" url:" + url + " rate:" + rate + " duration:" + duration + " maxconnections:" + maxConnections + ", " + "timeout:" + timeout);
            final DefaultConnectingIOReactor ioreactor = new DefaultConnectingIOReactor(IOReactorConfig.custom().
                    setConnectTimeout(timeout).
                    setIoThreadCount(10).
                    setSoTimeout(timeout).
                    build());

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                final List<ExceptionEvent> events = ioreactor.getAuditLog();
                if (events != null)
                    events.stream().filter(event -> event != null).forEach(event -> {
                        System.err.println("Apache Async HTTP Client I/O Reactor Error Time: " + event.getTimestamp());
                        //noinspection ThrowableResultOfMethodCallIgnored
                        if (event.getCause() != null)
                            //noinspection ThrowableResultOfMethodCallIgnored
                            event.getCause().printStackTrace();
                    });
                if (cmd.hasOption("stats"))
                    printFinishStatistics(errorsMeter, sts, sh, testName);
                if (!errors.keySet().isEmpty())
                    errors.entrySet().stream().forEach(p -> log.info(testName+" "+p.getKey() + " " + p.getValue()+"ms"));
                System.out.println(testName + " responseTime(90%): " + sh.getHistogramData().getValueAtPercentile(90) + "ms");
                if (cmd.hasOption("minmax")) {
                    final HistogramData hd = sh.getHistogramData();
                    System.out.format("%s %8s%8s%8s%8s\n", testName, "min", "mean", "sd", "max");
                    System.out.format("%s %8d%8.2f%8.2f%8d\n", testName, hd.getMinValue(), hd.getMean(), hd.getStdDeviation(), hd.getMaxValue());
                }
            }));

            final PoolingNHttpClientConnectionManager mngr = new PoolingNHttpClientConnectionManager(ioreactor);
            mngr.setDefaultMaxPerRoute(maxConnections);
            mngr.setMaxTotal(maxConnections);
            final CloseableHttpAsyncClient ahc = HttpAsyncClientBuilder.create().
                    setConnectionManager(mngr).
                    setDefaultRequestConfig(RequestConfig.custom().setLocalAddress(null).build()).build();
            try (final CloseableHttpClient client = new FiberHttpClient(ahc)) {
                final int num = duration * rate;

                final CountDownLatch cdl = new CountDownLatch(num);
                final Semaphore sem = new Semaphore(maxConnections);
                final RateLimiter rl = RateLimiter.create(rate);

                spawnStatisticsThread(printCycle, cdl, log, requestMeter, responseMeter, errorsMeter, testName);

                for (int i = 0; i < num; i++) {
                    rl.acquire();
                    if (sem.availablePermits() == 0)
                        System.out.println(new Date() + " waiting...");
                    sem.acquireUninterruptibly();

                    new Fiber<Void>(() -> {
                        requestMeter.mark();
                        final long start = System.nanoTime();
                        try {
                            try (final CloseableHttpResponse ignored = client.execute(request)) {
                                responseMeter.mark();
                            } catch (final Throwable t) {
                                markError(errorsMeter, errors, t);
                            }
                        } catch (final Throwable t) {
                            markError(errorsMeter, errors, t);
                        } finally {
                            final long now = System.nanoTime();
                            final long millis = TimeUnit.NANOSECONDS.toMillis(now - start);
                            sts.record(start, millis);
                            sh.recordValue(millis);
                            sem.release();
                            cdl.countDown();
                        }
                    }).start();
                }
                spawnProgressCheckThread(log, duration, checkCycle, cdl);
                cdl.await();
            }
        } catch (final ParseException ex) {
            System.err.println("Parsing failed.  Reason: " + ex.getMessage());
        }
    }

    private static void spawnProgressCheckThread(final Logger log, final int duration, final int checkCycle, final CountDownLatch cdl) {
        if (checkCycle > 0)
            new Thread(() -> {
                try {
                    Thread.sleep(duration * 1000);

                    long prevCount = cdl.getCount();
                    while (!cdl.await(checkCycle, TimeUnit.MILLISECONDS)) {
                        log.info("Checking progress");
                        final long currCount = cdl.getCount();
                        if (currCount == prevCount) {
                            log.warn("No progress, exiting");
                            System.exit(-1);
                        }
                        prevCount = currCount;
                    }
                } catch (final InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }).start();
    }

    private static void markError(final Meter errorsMeter, final Map<String, AtomicInteger> errors, final Throwable t) {
        errorsMeter.mark();
        if (t != null) {
            errors.putIfAbsent(t.getClass().getName(), new AtomicInteger());
            errors.get(t.getClass().getName()).incrementAndGet();
        }
    }

    private static void printFinishStatistics(final Meter errors, final StripedTimeSeries<Long> sts, final StripedHistogram sh, final String testName) {
        final File file = new File(testName + ".txt");
        try (PrintWriter out = new PrintWriter(file)) {
            out.println("ErrorsCounter: "+errors.getCount());
            final long millisTime = new Date().getTime();
            final long nanoTime = System.nanoTime();
            sts.getRecords().forEach(rec -> out.println(df.format(new Date(millisTime - TimeUnit.NANOSECONDS.toMillis(nanoTime - rec.timestamp)))
                    + " " + testName + " responseTime " + rec.value + "ms"));
            out.println("\nHistogram:");
            for (int i = 0; i <= 100; i++)
                out.println(testName + " responseTimeHistogram " + i + "% : " + sh.getHistogramData().getValueAtPercentile(i));
            System.out.println("Statistics file: " + file.getAbsolutePath());
        } catch (final FileNotFoundException ex) {
            System.err.println(ex.getMessage());
        }
    }

    private static void spawnStatisticsThread(final int printCycle, CountDownLatch cdl, final Logger log, final Meter requestMeter, final Meter responseMeter, final Meter errorsMeter, final String testName) {
        if (printCycle > 0)
            new Thread(() -> {
                try {
                        while (!cdl.await(printCycle, TimeUnit.MILLISECONDS)) {
                            printStatisticsLine(log, requestMeter, responseMeter, errorsMeter, testName, cdl);
                        }
                } catch (final InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }).start();
    }

    private static void printStatisticsLine(final Logger log, final Meter requestMeter, final Meter responseMeter, final Meter errorsMeter, final String testName, final CountDownLatch cdl) {
        log.info(testName + " STATS: "
                + "req: " + requestMeter.getCount() + " " + nf.format(requestMeter.getMeanRate()) + "Hz "
                + "resp: " + responseMeter.getCount() + " " + nf.format(responseMeter.getMeanRate()) + "Hz "
                + "err: " + errorsMeter.getCount() + " "
                + "open: " + (requestMeter.getCount() - errorsMeter.getCount() - responseMeter.getCount()) + " "
                + "countdown: " + cdl.getCount());
    }

    private static void printUsageAndExit(final Options options) {
        new HelpFormatter().printHelp("java -jar photon.jar [options] url", options);
        System.exit(0);
    }

    private static SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS");
    private static DecimalFormat nf = new DecimalFormat("#.##");
}
