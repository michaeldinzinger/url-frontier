/**
 * SPDX-FileCopyrightText: 2020 Crawler-commons SPDX-License-Identifier: Apache-2.0 Licensed to
 * Crawler-Commons under one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership. DigitalPebble licenses
 * this file to You under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package crawlercommons.urlfrontier.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import crawlercommons.urlfrontier.CrawlID;
import crawlercommons.urlfrontier.URLFrontierGrpc;
import crawlercommons.urlfrontier.URLFrontierGrpc.URLFrontierStub;
import crawlercommons.urlfrontier.Urlfrontier.AckMessage;
import crawlercommons.urlfrontier.Urlfrontier.DiscoveredURLItem;
import crawlercommons.urlfrontier.Urlfrontier.URLInfo;
import crawlercommons.urlfrontier.Urlfrontier.URLItem;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "PutURLs", description = "Send URLs from a file into a Frontier")
public class PutURLs implements Runnable {

    @ParentCommand private Client parent;

    @Option(
            names = {"-f", "--file"},
            required = true,
            paramLabel = "STRING",
            description = "path to file containing the URLs to inject into the Frontier")
    private String file;

    @Option(
            names = {"-c", "--crawlID"},
            defaultValue = CrawlID.DEFAULT,
            paramLabel = "STRING",
            description = "crawl to get the stats for")
    private String crawl;

    @Override
    public void run() {

        ManagedChannel channel =
                ManagedChannelBuilder.forAddress(parent.hostname, parent.port)
                        .usePlaintext()
                        .build();

        URLFrontierStub stub = URLFrontierGrpc.newStub(channel);

        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicInteger acked = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicInteger skipped = new AtomicInteger(0);
        final AtomicInteger ok = new AtomicInteger(0);

        int sent = 0;

        StreamObserver<crawlercommons.urlfrontier.Urlfrontier.AckMessage> responseObserver =
                new StreamObserver<>() {

                    @Override
                    public void onNext(crawlercommons.urlfrontier.Urlfrontier.AckMessage value) {
                        // receives confirmation that the value has been received
                        acked.addAndGet(1);
                        if (value.getStatus().equals(AckMessage.Status.SKIPPED)) {
                            skipped.getAndIncrement();
                        } else if (value.getStatus().equals(AckMessage.Status.FAIL)) {
                            failed.getAndIncrement();
                        } else if (value.getStatus().equals(AckMessage.Status.OK)) {
                            ok.getAndIncrement();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        completed.set(true);
                        t.printStackTrace();
                    }

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                };

        StreamObserver<URLItem> streamObserver = stub.putURLs(responseObserver);

        int linenum = 0;

        Instant start = Instant.now();

        List<String> allLines;
        try {
            allLines = Files.readAllLines(Paths.get(file));
            for (String line : allLines) {

                // don't sent too many in one go
                while (sent > acked.get() + 10000) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                URLItem item = parse(line, crawl);
                if (item == null) {
                    System.err.println("Invalid input line " + linenum);
                } else {
                    streamObserver.onNext(parse(line, crawl));
                    sent++;
                }
                linenum++;
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        }

        streamObserver.onCompleted();

        // wait for completion
        while (!completed.get() || sent != acked.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long timetaken = Instant.now().toEpochMilli() - start.toEpochMilli();

        System.out.println("Sent: " + sent);
        System.out.println("Acked: " + acked.get());
        System.out.println("OK: " + ok.get());
        System.out.println("Skipped: " + skipped.get());
        System.out.println("Failed: " + failed.get());
        System.out.println("Total time: " + timetaken + " msec");
        System.out.println("Average OPS: " + acked.get() / Math.max(1, timetaken / 1000));

        channel.shutdownNow();
    }

    /**
     * input format json
     *
     * <p>{url: "http://test.com", key: "test.com"}
     *
     * <p>or plain text where each line is a URL and the other fields are left to their default
     * value i.e. no custom metadata, key determined by the server (i.e. hostname), no explicit
     * refetchable_from_date.
     *
     * <p>The input file can mix json and text lines.
     */
    private static URLItem parse(String input, String crawl) {
        crawlercommons.urlfrontier.Urlfrontier.URLItem.Builder builder = URLItem.newBuilder();
        if (input.trim().startsWith("{")) {
            try {
                JsonFormat.parser().merge(input, builder);
            } catch (InvalidProtocolBufferException e) {
                return null;
            }
        } else {
            String url = input.trim();
            URLInfo info = URLInfo.newBuilder().setUrl(url).setCrawlID(crawl).build();
            DiscoveredURLItem value = DiscoveredURLItem.newBuilder().setInfo(info).build();
            builder.setDiscovered(value);
            builder.setID(crawl + "_" + url);
        }
        return builder.build();
    }
}
