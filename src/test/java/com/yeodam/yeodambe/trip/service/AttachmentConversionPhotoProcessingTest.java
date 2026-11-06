package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Photo;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.application.name=conversion-photo-processing-test")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentConversionPhotoProcessingTest {
    @Autowired private TripAttachmentDerivativeService service;
    @Autowired private InitialAttachmentUploadTransactionService initial;
    @Autowired private AttachmentConversionTransactionService conversions;
    @Autowired private AttachmentConversionEnqueueService enqueue;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ExifToolProcess exifTool;
    @MockitoBean private TripAttachmentStorageClient storage;
    @TempDir private Path directory;

    @BeforeEach
    void setUp() {
        when(storage.storeDerivedToAsync(anyString(), any(), anyString()))
                .thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        when(storage.storeDerivedTo(anyString(), any(), anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @ParameterizedTest
    @CsvSource({"INITIAL,image/jpeg", "ADDITIONAL,image/jpeg", "INITIAL,image/png", "ADDITIONAL,image/png",
            "INITIAL,image/heic", "ADDITIONAL,image/heic"})
    void 초기와_추가_변환은_메타데이터와_키를_보존한다(Kind kind, String mime) throws Exception {
        byte[] original = original(mime);
        var claim = claim(kind, mime, original.length);
        var photo = claim.photos().getFirst();
        var attempt = attempt(photo);
        when(storage.open(photo.objectKey())).thenReturn(new ByteArrayInputStream(original));
        var outputFiles = new ArrayList<Path>();
        var outputBytes = new ArrayList<byte[]>();
        if (kind == Kind.INITIAL) {
            when(storage.storeDerivedToAsync(anyString(), any(), anyString())).thenAnswer(call -> {
                Path path = call.getArgument(1);
                outputFiles.add(path);
                outputBytes.add(Files.readAllBytes(path));
                return CompletableFuture.completedFuture(call.getArgument(0));
            });
        } else {
            when(storage.storeDerivedTo(anyString(), any(), anyString())).thenAnswer(call -> {
                Path path = call.getArgument(1);
                outputFiles.add(path);
                outputBytes.add(Files.readAllBytes(path));
                return call.getArgument(0);
            });
        }

        var result = service.processConversionPhoto(claim, photo, attempt, () -> true).get(15, TimeUnit.SECONDS);

        assertThat(result.originalKey()).isEqualTo("original/photo");
        assertThat(result.analyzeKey()).isEqualTo("planned/analyze.jpg");
        assertThat(result.previewKey()).isEqualTo("planned/preview.webp");
        assertThat(result.displayKey()).isEqualTo(mime.equals("image/heic") ? "planned/display.jpg" : null);
        assertThat(result.takenAt()).isEqualTo(OffsetDateTime.parse("2026-09-28T12:34:56+09:00"));
        assertThat(result.latitude()).isEqualByComparingTo("37.5665");
        assertThat(result.longitude()).isEqualByComparingTo("126.978");
        assertThat(result.deviceModel()).isEqualTo("Yeodam FixtureCam");
        assertThat(result.originalSizeBytes()).isEqualTo(original.length);
        assertThat(result.analyzeSizeBytes()).isEqualTo(outputBytes.get(0).length);
        assertThat(result.previewSizeBytes()).isEqualTo(outputBytes.get(1).length);
        if (mime.equals("image/heic")) {
            assertThat(result.displaySizeBytes()).isEqualTo(outputBytes.get(2).length);
        }
        assertThat(ImageIO.read(new ByteArrayInputStream(outputBytes.getFirst()))).isNotNull();
        assertThat(outputFiles).allSatisfy(path -> assertThat(path.getParent()).doesNotExist());
        var retained = new ArrayList<>(keys(attempt));
        retained.add(photo.objectKey());
        verify(storage).retain(retained);
        verify(storage, never()).storeDerived(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 임시_파일을_닫기_전에_PUT이_완료돼야_한다(Kind kind) throws Exception {
        var claim = claim(kind, "image/jpeg", 100);
        var photo = claim.photos().getFirst();
        when(storage.open(photo.objectKey())).thenReturn(new ByteArrayInputStream(original("image/jpeg")));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var upload = new CompletableFuture<String>();
        var file = new AtomicReference<Path>();
        if (kind == Kind.INITIAL) {
            when(storage.storeDerivedToAsync(eq("planned/analyze.jpg"), any(), anyString())).thenAnswer(call -> {
                file.set(call.getArgument(1));
                entered.countDown();
                return upload;
            });
        } else {
            when(storage.storeDerivedTo(eq("planned/analyze.jpg"), any(), anyString())).thenAnswer(call -> {
                file.set(call.getArgument(1));
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(file.get()).exists();
                return call.getArgument(0);
            });
        }
        var future = service.processConversionPhoto(claim, photo, attempt(photo), () -> true);
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(future).isNotDone();
            assertThat(file.get()).exists();
        } finally {
            upload.complete("planned/analyze.jpg");
            release.countDown();
        }
        future.get(10, TimeUnit.SECONDS);
        assertThat(file.get().getParent()).doesNotExist();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 취소되거나_제어권을_잃으면_다음_PUT을_시작하지_않는다(Kind kind) throws Exception {
        var claim = claim(kind, "image/jpeg", 100);
        var photo = claim.photos().getFirst();
        when(storage.open(photo.objectKey())).thenReturn(new ByteArrayInputStream(original("image/jpeg")));
        var active = new AtomicBoolean(true);
        var file = new AtomicReference<Path>();
        if (kind == Kind.INITIAL) {
            when(storage.storeDerivedToAsync(eq("planned/analyze.jpg"), any(), anyString())).thenAnswer(call -> {
                file.set(call.getArgument(1));
                active.set(false);
                return CompletableFuture.completedFuture(call.getArgument(0));
            });
        } else {
            when(storage.storeDerivedTo(eq("planned/analyze.jpg"), any(), anyString())).thenAnswer(call -> {
                file.set(call.getArgument(1));
                active.set(false);
                return call.getArgument(0);
            });
        }

        var future = service.processConversionPhoto(claim, photo, attempt(photo), active::get);
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).isInstanceOfAny(ExecutionException.class,
                java.util.concurrent.CancellationException.class);
        verify(storage, never()).storeDerivedTo(eq("planned/preview.webp"), any(), anyString());
        verify(storage, never()).storeDerivedToAsync(eq("planned/preview.webp"), any(), anyString());
        verify(storage, never()).retain(anyList());
        assertThat(file.get()).isNotNull();
        assertThat(file.get().getParent()).doesNotExist();
    }

    @Test
    void 일부_실패_후에도_모든_예정_키를_추적한다() throws Exception {
        var user = users.save(new User(UUID.randomUUID() + "@photo.test", "사진 실행"));
        stats.save(new UserStats(user));
        var trip = trips.save(new Trip(user.getUserId(), "사진 실행", LocalDate.now(), LocalDate.now()));
        byte[] original = original("image/heic");
        var batch = initial.prepareBatch(trip.getId(), user.getUserId(), new InitialAttachmentUploadUrlRequest(
                1, 1, true, List.of(new InitialAttachmentUploadUrlRequest.Attachment("one.heic", "image/heic", (long) original.length))));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            batches.findById(batch.batchId()).orElseThrow().accept(LocalDateTime.now(), AttachmentConversionRoute.KAFKA);
            enqueue.enqueue(new AttachmentConversionSeed(Kind.INITIAL, batch.batchId(), trip.getId(), "journal",
                    List.of(new AttachmentConversionSeed.Photo(batch.photos().getFirst().itemId(), "image/heic", original.length))));
        });
        var task = tasks.findByInitialBatchIdAndLane(batch.batchId(), Lane.HEIC).orElseThrow();
        var claim = conversions.claim(task.getId(), Kind.INITIAL, Lane.HEIC).claim();
        var photo = claim.photos().getFirst();
        var attempt = conversions.beginPhoto(claim, photo.itemId()).attempt();
        when(storage.open(photo.objectKey())).thenReturn(new ByteArrayInputStream(original));
        when(storage.storeDerivedToAsync(eq(attempt.plannedKeys().previewKey()), any(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new AttachmentStorageException(
                        attempt.plannedKeys().previewKey(), new IllegalStateException("PUT"))));

        assertThatThrownBy(() -> service.processConversionPhoto(claim, photo, attempt, () -> true).get(15, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        verify(storage).storeDerivedToAsync(eq(attempt.plannedKeys().analyzeKey()), any(), anyString());
        verify(storage).storeDerivedToAsync(eq(attempt.plannedKeys().previewKey()), any(), anyString());
        verify(storage, never()).storeDerivedToAsync(eq(attempt.plannedKeys().displayKey()), any(), anyString());
        var stored = items.findById(photo.itemId()).orElseThrow();
        assertThat(initial.cleanupObjects(stored)).extracting(InitialAttachmentUploadTransactionService.CleanupObject::key)
                .containsAll(keys(attempt));
        assertThat(stored.getDerivedJson()).isNull();
        assertThat(stored.getConversionAttempts()).isEqualTo(1);
        verify(storage, never()).delete(anyString());
        verify(storage, never()).retain(anyList());
    }

    private AttachmentConversionClaim claim(Kind kind, String mime, long bytes) {
        var photo = new Photo(1L, mime, "original/photo", bytes, 0, null, null);
        return new AttachmentConversionClaim(UUID.randomUUID().toString(), "token", kind,
                mime.equals("image/heic") ? Lane.HEIC : Lane.LIGHT, 1L, "upload", "execution", 1L, 1L, "request", List.of(photo));
    }

    private Attempt attempt(Photo photo) {
        return new Attempt(photo.itemId(), 1, new DerivedPhotoKeys(photo.objectKey(), "planned/analyze.jpg", "planned/preview.webp",
                photo.contentType().equals("image/heic") ? "planned/display.jpg" : null,
                null, null, null, null, photo.sizeBytes(), null, null, null));
    }

    private List<String> keys(Attempt attempt) {
        var planned = attempt.plannedKeys();
        return planned.displayKey() == null ? List.of(planned.analyzeKey(), planned.previewKey())
                : List.of(planned.analyzeKey(), planned.previewKey(), planned.displayKey());
    }

    private byte[] original(String mime) throws Exception {
        if (mime.equals("image/heic")) {
            return Files.readAllBytes(Path.of("src/test/resources/images/heic/oriented-with-exif.heic"));
        }
        String format = mime.equals("image/png") ? "png" : "jpg";
        Path file = directory.resolve(UUID.randomUUID() + "." + format);
        assertThat(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, file.toFile())).isTrue();
        assertThat(exifTool.execute(List.of("-overwrite_original", "-DateTimeOriginal=2026:09:28 12:34:56",
                "-GPSLatitude=37.5665", "-GPSLatitudeRef=N", "-GPSLongitude=126.978", "-GPSLongitudeRef=E",
                "-Make=Yeodam", "-Model=FixtureCam", file.toString())).exitStatus()).isZero();
        return Files.readAllBytes(file);
    }
}
