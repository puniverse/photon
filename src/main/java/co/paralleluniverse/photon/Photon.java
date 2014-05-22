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

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.httpclient.FiberHttpClient;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.util.concurrent.RateLimiter;
import java.io.IOException;
import java.text.DecimalFormat;
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
import org.apache.http.nio.reactor.IOReactorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Photon {

    public static void main(String[] args) throws InterruptedException, IOReactorException, IOException {
        
        Options options = new Options();
        options.addOption("rate", true, "requests per second");
        options.addOption("duration", true, "test duration in seconds");
        options.addOption("maxconnections", true, "maximum number of open connections");
        options.addOption("timeout", true, "connection and read timeout in millis");
        options.addOption("help", false, "print help");

        try {
            CommandLine cmd = new BasicParser().parse(options, args);
            String[] ar = cmd.getArgs();
            if (cmd.hasOption("help") || ar.length!=1) 
                printUsageAndExit(options);
            final String url = ar[0];            
            final int timeout = Integer.parseInt(cmd.getOptionValue("timeout","10000"));
            final int maxConnections = Integer.parseInt(cmd.getOptionValue("maxconnections","150000"));
            final int duaration = Integer.parseInt(cmd.getOptionValue("duration","10"));
            final int rate = Integer.parseInt(cmd.getOptionValue("rate","10"));
            final MetricRegistry metrics = new MetricRegistry();
            final Logger log = LoggerFactory.getLogger(Photon.class);
            System.out.println();

            final ConcurrentHashMap<String, AtomicInteger> errors = new ConcurrentHashMap<>();
            final HttpGet request = new HttpGet(url);
            log.info("url:"+url+" rate:"+rate+" duration:"+duaration+" maxconnections:"+maxConnections+", "+"timeout:"+timeout);

            PoolingNHttpClientConnectionManager mngr = new PoolingNHttpClientConnectionManager(new DefaultConnectingIOReactor(IOReactorConfig.custom().
                    setConnectTimeout(timeout).
                    setIoThreadCount(10).
                    setSoTimeout(timeout).
                    build()));
            mngr.setDefaultMaxPerRoute(maxConnections);
            mngr.setMaxTotal(maxConnections);
            CloseableHttpAsyncClient ahc = HttpAsyncClientBuilder.create().
                    setConnectionManager(mngr).
                    setDefaultRequestConfig(RequestConfig.custom().setLocalAddress(null).build()).build();

            try (CloseableHttpClient client = new FiberHttpClient(ahc)) {
                log.info("starting..");
                int num = duaration * rate;
                int tenth = num / 10;
                Timer httpTimer = metrics.timer("httpTimer");
                Meter requestMeter = metrics.meter("req");

                CountDownLatch cdl = new CountDownLatch(num);
                Semaphore sem = new Semaphore(maxConnections);
                final RateLimiter rl = RateLimiter.create(rate);

                for (int i = 0; i < num; i++) {
                    rl.acquire();
                    if (sem.availablePermits() == 0)
                        System.out.println(new Date() + " waiting...");
                    sem.acquireUninterruptibly();

                    final int constCounter = i + 1;
                    new Fiber<Void>(() -> {
                        final Timer.Context ctx = httpTimer.time();
                        requestMeter.mark();
                        if (constCounter % tenth == 0)
                            log.info("Sent(" + constCounter + ") " + constCounter / tenth * 10 + "%..."
                                    + " openConnections: " + (maxConnections - sem.availablePermits())
                                    + " meanRate: " + df.format(requestMeter.getMeanRate()));
                        try {
                            client.execute(request).close();
                        } catch (IOException ex) {
                            errors.putIfAbsent(ex.getClass().getName(), new AtomicInteger());
                            errors.get(ex.getClass().getName()).incrementAndGet();
                        } finally {
                            ctx.stop();
                            sem.release();
                            cdl.countDown();
                            long count = num - cdl.getCount();
                            if (count % tenth == 0)
                                log.info("Responeded (" + count + ") " + ((count) / tenth * 10) + "%..."
                                        + " mean: " + nanos2secs(httpTimer.getSnapshot().getMean())
                                        + " 95th: " + nanos2secs(httpTimer.getSnapshot().get95thPercentile())
                                        + " 99th: " + nanos2secs(httpTimer.getSnapshot().get99thPercentile()));
                        }
                    }).start();
                }
                cdl.await();
                log.info("Errors: "+errors.values().stream().mapToInt(AtomicInteger::get).sum());
                errors.entrySet().stream().forEach(p -> log.info(p.getKey() + " " + p.getValue()));
            }
        } catch (ParseException ex) {
            System.err.println("Parsing failed.  Reason: " + ex.getMessage());
        }
    }

    private static void printUsageAndExit(Options options) {
        new HelpFormatter().printHelp("java -jar photon.jar [options] url", options);
        System.exit(0);
    }

    static String nanos2secs(double nanos) {
        return df.format(nanos / TimeUnit.SECONDS.toNanos(1))+"s";
    }
    static DecimalFormat df = new DecimalFormat("#.##");
}
