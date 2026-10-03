package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripAttachmentDerivativeLoadTest {
    private static final String DATASET_ENV = "YEODAM_PERF_DATASET_DIR";

    @Test
    void 외부_사진_세트로_형식_해상도_동시성별_시간을_기록한다() throws Exception {
        String configured = System.getenv(DATASET_ENV);
        assumeTrue(configured != null && !configured.isBlank(), DATASET_ENV + " 미설정");
        Path root = Path.of(configured);
        assertTrue(Files.isDirectory(root), "사진 세트 디렉터리가 없습니다: " + root);

        System.out.println("format,resolution,concurrency,repeat,job,photos,total_bytes,submit_to_first_read_ms,first_read_to_complete_ms,total_ms,outcome");
        record Condition(String format, String resolution, List<Path> photos, String mime, int concurrency) {}
        List<Condition> conditions = new ArrayList<>();
        for (String format : List.of("jpg", "png", "heic")) {
            if (!Files.isDirectory(root.resolve(format + "-low"))
                    || !Files.isDirectory(root.resolve(format + "-high"))) continue;
            List<Path> low = photos(root.resolve(format + "-low"));
            List<Path> high = photos(root.resolve(format + "-high"));
            assertEquals(low.size(), high.size(), format + " 저·고해상도 사진 수가 다릅니다");
            String mime = switch (format) {
                case "jpg" -> "image/jpeg";
                case "png" -> "image/png";
                default -> "image/heic";
            };
            for (Map.Entry<String, List<Path>> set : Map.of("low", low, "high", high).entrySet()) {
                runWave(format, set.getKey(), set.getValue(), mime, 1, 0);
                for (int concurrency : List.of(1, 2, 4, 8)) {
                    conditions.add(new Condition(format, set.getKey(), set.getValue(), mime, concurrency));
                }
            }
        }
        assertTrue(!conditions.isEmpty(), "저·고해상도 사진 쌍이 없습니다: " + root);
        for (int repeat = 1; repeat <= 5; repeat++) {
            Collections.shuffle(conditions, new Random(20260930L + repeat));
            for (Condition condition : conditions) {
                runWave(condition.format(), condition.resolution(), condition.photos(),
                        condition.mime(), condition.concurrency(), repeat);
            }
        }
    }

    private static void runWave(String format, String resolution, List<Path> photos,
                                String mime, int concurrency, int repeat) throws Exception {
        TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
        Map<String, Long> firstReadAt = new ConcurrentHashMap<>();
        AtomicInteger nextKey = new AtomicInteger();
        when(storage.open(any(String.class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            String[] parts = key.split("/");
            firstReadAt.putIfAbsent(parts[0], System.nanoTime());
            return Files.newInputStream(photos.get(Integer.parseInt(parts[1])));
        });
        when(storage.storeDerived(any(String.class), any(Path.class), any(String.class)))
                .thenAnswer(call -> call.getArgument(0) + "/derived/" + nextKey.getAndIncrement());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TripDerivativeScheduler scheduler = new TripDerivativeScheduler(1, 1, 10, 20, registry);
        TripAttachmentDerivativeService service = new TripAttachmentDerivativeService(
                storage, new ObjectMapper(), registry, scheduler);
        record Submitted(String job, long at, AtomicLong completedAt,
                         CompletableFuture<List<DerivedPhotoKeys>> future) {}
        List<Submitted> accepted = new ArrayList<>();
        long totalBytes = bytes(photos);
        try (ResourceSampler resources = new ResourceSampler()) {
            for (int n = 1; n <= concurrency; n++) {
                String job = "job-" + n;
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < photos.size(); i++) keys.add(job + "/" + i);
                long submittedAt = System.nanoTime();
                try {
                    AtomicLong completedAt = new AtomicLong();
                    CompletableFuture<List<DerivedPhotoKeys>> future = service.createAll(
                            job, keys, java.util.Collections.nCopies(photos.size(), mime))
                            .whenComplete((result, failure) -> completedAt.set(System.nanoTime()));
                    accepted.add(new Submitted(job, submittedAt, completedAt, future));
                } catch (RejectedExecutionException rejected) {
                    print(format, resolution, concurrency, repeat, job, photos.size(), totalBytes,
                            -1, -1, System.nanoTime() - submittedAt, "rejected");
                }
            }
            for (Submitted submission : accepted) {
                submission.future().join();
                long doneAt = submission.completedAt().get();
                long readAt = firstReadAt.get(submission.job());
                print(format, resolution, concurrency, repeat, submission.job(), photos.size(), totalBytes,
                        readAt - submission.at(), doneAt - readAt, doneAt - submission.at(), "completed");
            }
            resources.print(format, resolution, concurrency, repeat);
        } finally {
            scheduler.stop();
        }
    }

    // Test-only sampling. Short-lived child processes may exit between samples.
    private static final class ResourceSampler implements AutoCloseable {
        private static final String TEMP_PREFIX = "사진 작업 디렉터리를 만들 수 없습니다.";
        private final Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        private final Set<Path> existing = tempDirectories();
        private final Map<Long, Long> childCpuNanos = new HashMap<>();
        private final long gcCountBefore = gcCount();
        private final long gcMillisBefore = gcMillis();
        private final Thread thread;
        private volatile boolean running = true;
        private volatile long peakHeapBytes;
        private volatile long peakChildRssKb;
        private volatile long peakTempBytes;
        private volatile long sampledChildren;

        ResourceSampler() {
            thread = new Thread(() -> {
                while (running) {
                    sample();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "derivative-resource-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private void sample() {
            peakHeapBytes = Math.max(peakHeapBytes, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            long rssKb = 0;
            for (ProcessHandle child : ProcessHandle.current().children().toList()) {
                String command = child.info().command().orElse("");
                if (!command.endsWith("/convert") && !command.endsWith("/exiftool")) continue;
                sampledChildren++;
                long[] usage = processUsage(child.pid());
                rssKb += usage[0];
                childCpuNanos.merge(child.pid(), usage[1], Math::max);
            }
            peakChildRssKb = Math.max(peakChildRssKb, rssKb);
            long tempBytes = 0;
            for (Path dir : tempDirectories()) {
                if (existing.contains(dir)) continue;
                try (var paths = Files.walk(dir)) {
                    tempBytes += paths.filter(Files::isRegularFile).mapToLong(path -> {
                        try { return Files.size(path); } catch (Exception ignored) { return 0; }
                    }).sum();
                } catch (Exception ignored) {
                    // The worker may delete a directory while it is sampled.
                }
            }
            peakTempBytes = Math.max(peakTempBytes, tempBytes);
        }

        private Set<Path> tempDirectories() {
            try (var paths = Files.list(tempRoot)) {
                return new HashSet<>(paths.filter(path -> path.getFileName().toString().startsWith(TEMP_PREFIX)).toList());
            } catch (Exception ignored) {
                return Set.of();
            }
        }

        private static long[] processUsage(long pid) {
            try {
                Process ps = new ProcessBuilder("ps", "-o", "rss=,time=", "-p", Long.toString(pid)).start();
                String value = new String(ps.getInputStream().readAllBytes()).trim();
                if (ps.waitFor(1, TimeUnit.SECONDS) && !value.isEmpty()) {
                    String[] fields = value.split("\\s+");
                    String[] clock = fields[1].split(":");
                    double seconds = 0;
                    for (String part : clock) seconds = seconds * 60 + Double.parseDouble(part);
                    return new long[] {Long.parseLong(fields[0]), (long) (seconds * 1_000_000_000)};
                }
                ps.destroyForcibly();
            } catch (Exception ignored) {
                // A process can exit between enumeration and ps.
            }
            return new long[] {0, 0};
        }

        private static long gcCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(n -> n >= 0).sum();
        }

        private static long gcMillis() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(n -> n >= 0).sum();
        }

        void print(String format, String resolution, int concurrency, int repeat) {
            close();
            long cpuNanos = childCpuNanos.values().stream().mapToLong(Long::longValue).sum();
            System.out.printf(Locale.ROOT,
                    "RESOURCE,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    format, resolution, concurrency, repeat, peakHeapBytes, gcCount() - gcCountBefore,
                    gcMillis() - gcMillisBefore, cpuNanos, peakChildRssKb * 1024,
                    peakTempBytes, sampledChildren);
        }

        @Override public void close() {
            running = false;
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void print(String format, String resolution, int concurrency, int repeat,
                              String job, int count, long bytes, long wait, long run, long total, String outcome) {
        System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%s,%d,%d,%s,%s,%.3f,%s%n",
                format, resolution, concurrency, repeat, job, count, bytes,
                wait < 0 ? "" : String.format(Locale.ROOT, "%.3f", wait / 1_000_000.0),
                run < 0 ? "" : String.format(Locale.ROOT, "%.3f", run / 1_000_000.0),
                total / 1_000_000.0, outcome);
    }

    private static List<Path> photos(Path directory) throws Exception {
        assertTrue(Files.isDirectory(directory), "사진 세트가 없습니다: " + directory);
        List<Path> paths;
        try (var files = Files.list(directory)) {
            paths = files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
        }
        assertTrue(!paths.isEmpty() && paths.size() <= 10, "사진은 세트당 1~10장이어야 합니다: " + directory);
        for (Path path : paths) {
            assertTrue(Files.size(path) <= 15L * 1024 * 1024, "15MiB 초과: " + path);
        }
        assertTrue(bytes(paths) <= 145L * 1024 * 1024, "145MiB 초과: " + directory);
        return paths;
    }

    private static long bytes(List<Path> paths) throws Exception {
        long total = 0;
        for (Path path : paths) total += Files.size(path);
        return total;
    }

    private static byte[] tinyJpeg() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }
}
