/*
 * PHOTON
 * Copyright (C) 2014, Parallel Universe Software Co. All rights reserved.
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
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Photon {

    public static void main(final String[] args) throws InterruptedException, IOException {

        final Options options = new Options();
        options.addOption("rate", true, "requests per second");
        options.addOption("duration", true, "test duration in seconds");
        options.addOption("maxconnections", true, "maximum number of open connections");
        options.addOption("timeout", true, "connection and read timeout in millis");
        options.addOption("print", true, "print cycle in millis. 0 to disable intermediate statistics");
        options.addOption("stats", false, "print full statistics when finish");
        options.addOption("minmax", false, "print min/mean/stddev/max stats when finish");
        options.addOption("name", true, "test name to print in the statistics");
        options.addOption("help", false, "print help");

        try {
            final CommandLine cmd = new BasicParser().parse(options, args);
            final String[] ar = cmd.getArgs();
            if (cmd.hasOption("help") || ar.length != 1)
                printUsageAndExit(options);
            final String url = ar[0];
            final int timeout = Integer.parseInt(cmd.getOptionValue("timeout", "10000"));
            final int maxConnections = Integer.parseInt(cmd.getOptionValue("maxconnections", "150000"));
            final int duration = Integer.parseInt(cmd.getOptionValue("duration", "10"));
            final int printCycle = Integer.parseInt(cmd.getOptionValue("print", "1000"));
            final String testName = cmd.getOptionValue("name", "test");
            final int rate = Integer.parseInt(cmd.getOptionValue("rate", "10"));
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
                if (cmd.hasOption("stats"))
                    printFinishStatistics(errorsMeter, sts, sh, testName);
                if (!errors.keySet().isEmpty())
                    errors.entrySet().stream().forEach(p -> log.info(testName+" "+p.getKey() + " " + p.getValue()+"ms"));
                System.out.println(testName+" responseTime(90%): " + sh.getHistogramData().getValueAtPercentile(90)+"ms");
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
                            client.execute(request).close();
                            responseMeter.mark();
                        } catch (IOException ex) {
                            errorsMeter.mark();
                            errors.putIfAbsent(ex.getClass().getName(), new AtomicInteger());
                            errors.get(ex.getClass().getName()).incrementAndGet();
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
                cdl.await();
            }
        } catch (final ParseException ex) {
            System.err.println("Parsing failed.  Reason: " + ex.getMessage());
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
        new Thread(() -> {
            try {
                if (printCycle > 0)
                    while (!cdl.await(printCycle, TimeUnit.MILLISECONDS)) {
                        printStatisticsLine(log, requestMeter, responseMeter, errorsMeter, testName);
                    }
            } catch (final InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }).start();
    }

    private static void printStatisticsLine(final Logger log, final Meter requestMeter, final Meter responseMeter, final Meter errorsMeter, final String testName) {
        log.info(testName + " STATS: "
                + "req: " + requestMeter.getCount() + " " + nf.format(requestMeter.getMeanRate()) + "Hz "
                + "resp: " + responseMeter.getCount() + " " + nf.format(responseMeter.getMeanRate()) + "Hz "
                + "err: " + errorsMeter.getCount() + " "
                + "open: " + (requestMeter.getCount() - errorsMeter.getCount() - responseMeter.getCount()));
    }

    private static void printUsageAndExit(final Options options) {
        new HelpFormatter().printHelp("java -jar photon.jar [options] url", options);
        System.exit(0);
    }

    private static SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS");
    private static DecimalFormat nf = new DecimalFormat("#.##");
}
