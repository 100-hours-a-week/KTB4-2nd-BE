package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import com.yeodam.yeodambe.story.service.response.StoryUpdateResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.TripEditAuthorizationService;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryContentUpdateRepository;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import org.mockito.InOrder;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bson.BsonDocument;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.time.ZoneOffset;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@org.springframework.boot.test.context.SpringBootTest
@Import({TestcontainersConfiguration.class, StoryUpdateServiceIntegrationTest.MongoConfig.class,
        StorySqlCounterConfiguration.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoryUpdateServiceIntegrationTest {

    private static final Queue<BsonDocument> MONGO_UPDATES = new ConcurrentLinkedQueue<>();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {

        @org.springframework.context.annotation.Bean
        MongoClientSettingsBuilderCustomizer captureStoryUpdates() {
            return settings -> settings.addCommandListener(new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if ("update".equals(event.getCommandName())
                            && "story_contents".equals(event.getCommand().getString("update").getValue())) {
                        MONGO_UPDATES.add(event.getCommand().clone());
                    }
                }
            });
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails isolatedMongo() {
            return () -> new com.mongodb.ConnectionString(
                    com.yeodam.yeodambe.SharedTestMongo.connectionString("storyupdateserviceintegrationtest"));
        }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StoryUpdateService service;
    @MockitoSpyBean StoryContentRepository contents;
    @MockitoSpyBean StoryContentUpdateRepository contentUpdates;
    @Autowired com.yeodam.yeodambe.story.repository.StoryBlockRepository sqlBlocks;
    @Autowired com.yeodam.yeodambe.story.repository.StorySelectedPlaceRepository selectedPlaces;
    @Autowired com.yeodam.yeodambe.story.repository.StoryRepository stories;
    @Autowired StoryContentDocumentFactory factory;
    @Autowired MongoTemplate mongoTemplate;
    @MockitoBean TripAttachmentStorageClient storage;
    @MockitoBean TripEditAuthorizationService authorizationService;
    private TransactionTemplate tx;
    private Fixture fixture;

    record Fixture(Long userId, Long tripId, Long storyId, Long blockId, Long photoId, Long placeId) {}

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactionManager);
        fixture = tx.execute(status -> {
            User user = new User(UUID.randomUUID() + "@story-update.test", "수정회원");
            em.persist(user);
            Trip trip = new Trip(user.getUserId(), "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));
            ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
            em.persist(trip);
            TripDetailPlace place = place(trip.getId(), 2);
            TripAttachment photo = photo(user.getUserId(), trip.getId(), place);
            Story story = new Story(trip.getId(), Story.Mood.PLAIN, "기존 요약");
            story.complete();
            em.persist(story);
            StoryBlock block = new StoryBlock(story.getId(), photo.getId(), place.getId(),
                    place.getStartedAt().toLocalDate(), 1, "기존 날짜", "기존 설명", "기존 메모");
            em.persist(block);
            trip.changeCurrentStory(story.getId());
            return new Fixture(user.getUserId(), trip.getId(), story.getId(), block.getId(), photo.getId(), place.getId());
        });
        tx.executeWithoutResult(status -> contents.insert(factory.create(
                stories.findById(fixture.storyId()).orElseThrow(),
                selectedPlaces.findAllByStoryIdOrderByIdAsc(fixture.storyId()),
                sqlBlocks.findAllByStoryIdOrderByOrderNumberAscIdAsc(fixture.storyId()),
                ZoneOffset.UTC)));
        when(storage.createReadUrl(anyString())).thenAnswer(invocation -> "https://image.test/" + invocation.getArgument(0));
    }

    @AfterEach
    void cleanMongo() {
        if (fixture != null) contents.deleteById(fixture.storyId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "요\n약", "\u007F", "\u0085", "\uD800", "\uDC00"})
    void 잘못된_문구는_서비스에서_직접_호출해도_거절하고_기존_본문을_보존한다(String text) {
        StoryContentDocument before = contents.findById(fixture.storyId()).orElseThrow();
        StoryUpdateRequest.Block validBlock = block(fixture.blockId(), fixture.photoId(), 1);
        List<StoryUpdateRequest> requests = List.of(
                new StoryUpdateRequest(Story.Mood.CALM, text, List.of(validBlock)),
                new StoryUpdateRequest(Story.Mood.CALM, "요약", List.of(
                        new StoryUpdateRequest.Block(fixture.blockId(), fixture.photoId(), text, "설명", null, 1)
                )),
                new StoryUpdateRequest(Story.Mood.CALM, "요약", List.of(
                        new StoryUpdateRequest.Block(fixture.blockId(), fixture.photoId(), "첫째 날", text, null, 1)
                ))
        );

        for (StoryUpdateRequest request : requests) {
            assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request))
                    .isInstanceOf(InvalidStoryUpdateException.class);
        }

        StoryContentDocument after = contents.findById(fixture.storyId()).orElseThrow();
        assertThat(after.getStorySummary()).isEqualTo(before.getStorySummary());
        assertThat(after.getMood()).isEqualTo(before.getMood());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getBlocks()).containsExactlyElementsOf(before.getBlocks());
    }

    @Test
    void 읽은뒤_문서버전이_변하면_최신본문을_덮어쓰지_않는다() {
        service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1)));
        doAnswer(invocation -> {
            StoryContentDocument newer = contents.findById(fixture.storyId()).orElseThrow();
            newer.updateContent(Story.Mood.PLAIN, "먼저 저장한 요약", newer.getBlocks());
            mongoTemplate.save(newer);
            return invocation.callRealMethod();
        }).when(contentUpdates).saveChanges(any(StoryContentDocument.class), anyList());

        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1))))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(contents.findById(fixture.storyId()).orElseThrow().getStorySummary())
                .isEqualTo("먼저 저장한 요약");
    }

    @Test
    void 응답URL_준비가_실패하면_본문과_버전을_저장하지_않는다() {
        StoryContentDocument before = contents.findById(fixture.storyId()).orElseThrow();
        when(storage.createReadUrl(anyString())).thenThrow(new IllegalStateException(
                StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message()
        ));

        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1))))
                .isInstanceOf(IllegalStateException.class);

        StoryContentDocument after = contents.findById(fixture.storyId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getBlocks()).isEqualTo(before.getBlocks());
        assertThat(after.getStorySummary()).isEqualTo(before.getStorySummary());
        verify(contents, never()).save(any(StoryContentDocument.class));
        verify(contentUpdates, never()).saveChanges(any(StoryContentDocument.class), anyList());
    }

    @Test
    void 저장호출이_실행전_실패하면_기존문서를_유지한다() {
        StoryContentDocument before = contents.findById(fixture.storyId()).orElseThrow();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(
                StoryGenerationInternalErrorMessage.CONTENT_UPDATE_UNACKNOWLEDGED.message()
        )).when(contentUpdates).saveChanges(any(StoryContentDocument.class), anyList());

        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1))))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        StoryContentDocument after = contents.findById(fixture.storyId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getBlocks()).isEqualTo(before.getBlocks());
    }

    @Test
    void 응답준비후에_저장하고_SQL롤백이_저장한_Mongo본문을_되돌리지_않는다() {
        tx.executeWithoutResult(status -> {
            StoryUpdateResponse response = service.update(fixture.userId(), fixture.storyId(),
                    request(block(fixture.blockId(), fixture.photoId(), 1)));
            assertThat(response.storySummary()).isEqualTo("수정 요약");
            status.setRollbackOnly();
        });

        assertThat(contents.findById(fixture.storyId()).orElseThrow().getStorySummary())
                .isEqualTo("수정 요약");
        tx.executeWithoutResult(status -> assertThat(em.find(Story.class, fixture.storyId())
                .getStorySummary()).isEqualTo("기존 요약"));
        InOrder order = inOrder(storage, contentUpdates);
        order.verify(storage).createReadUrl(anyString());
        order.verify(contentUpdates).saveChanges(any(StoryContentDocument.class), anyList());
    }

    @Test
    void 전체_내용을_수정하되_같은사진의_기존_날짜장소를_유지한다() {
        tx.executeWithoutResult(status -> {
            TripDetailPlace changed = place(fixture.tripId(), 3);
            em.find(TripAttachment.class, fixture.photoId()).classify(changed.getId(), RegionOrigin.EXIF,
                    changed.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        });
        var response = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1)));
        assertThat(response.storyId()).isEqualTo(fixture.storyId());
        assertThat(response.tone()).isEqualTo(Story.Mood.CALM);
        assertThat(response.storySummary()).isEqualTo("수정 요약");
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 2));
        var saved = response.days().getFirst().blocks().getFirst();
        assertThat(saved.tripPlaceId()).isEqualTo(fixture.placeId());
        assertThat(saved.detailSummary()).isEqualTo("요청 설명");
        assertThat(saved.memo()).isEqualTo("요청 메모");
    }

    @Test
    void 삭제한_사진을_다시_추가하면_새_블록_ID를_발급한다() {
        Long newPhoto = newPhoto(true);
        var input = request(block(null, newPhoto, 1));
        var first = service.update(fixture.userId(), fixture.storyId(), input);
        Long newBlock = first.days().getFirst().blocks().getFirst().storyBlockId();
        var repeated = service.update(fixture.userId(), fixture.storyId(), input);
        assertThat(repeated.days().getFirst().blocks().getFirst().storyBlockId()).isEqualTo(newBlock);
        tx.executeWithoutResult(status -> {
            var savedDocument = contents.findById(fixture.storyId()).orElseThrow();
            assertThat(savedDocument.getBlocks()).filteredOn(block -> block.id().equals(fixture.blockId()))
                    .allSatisfy(block -> assertThat(block.deletedAt()).isNotNull());
            assertThat(savedDocument.getBlocks()).hasSize(2);
            assertThat(em.find(StoryBlock.class, fixture.blockId()).getDeletedAt()).isNull();
            assertThat(em.find(TripAttachment.class, fixture.photoId()).getDeletedAt()).isNull();
            assertThat(em.createQuery("select count(b) from StoryBlock b where b.storyId = :id", Long.class)
                    .setParameter("id", fixture.storyId()).getSingleResult()).isEqualTo(1L);
        });
        var readded = service.update(fixture.userId(), fixture.storyId(), request(
                block(null, fixture.photoId(), 1), block(newBlock, newPhoto, 2)));
        assertThat(readded.days().stream().flatMap(day -> day.blocks().stream()))
                .filteredOn(item -> item.tripAttachmentId().equals(fixture.photoId()))
                .singleElement().satisfies(item -> assertThat(item.storyBlockId()).isNotIn(fixture.blockId(), newBlock));
        assertThat(contents.findById(fixture.storyId()).orElseThrow().getBlocks())
                .filteredOn(item -> item.id().equals(fixture.blockId()))
                .singleElement().satisfies(item -> assertThat(item.deletedAt()).isNotNull());
    }

    @Test
    void 삭제된_블록_ID는_거절하지만_그_사진으로_활성_블록을_교체할_수_있다() {
        Long newPhoto = newPhoto(true);
        var first = service.update(fixture.userId(), fixture.storyId(), request(block(null, newPhoto, 1)));
        Long activeBlockId = first.days().getFirst().blocks().getFirst().storyBlockId();

        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);

        var replaced = service.update(fixture.userId(), fixture.storyId(), request(
                block(activeBlockId, fixture.photoId(), 1)));
        var block = replaced.days().getFirst().blocks().getFirst();
        assertThat(block.storyBlockId()).isEqualTo(activeBlockId);
        assertThat(block.tripAttachmentId()).isEqualTo(fixture.photoId());
    }

    @Test
    void 사진교체후_새사진과_현재장소날짜로_응답하며_원본사진은_유지한다() {
        Long replacement = newPhoto(true);
        var response = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), replacement, 1)));
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 4));
        var saved = response.days().getFirst().blocks().getFirst();
        assertThat(saved.storyBlockId()).isEqualTo(fixture.blockId());
        assertThat(saved.tripAttachmentId()).isEqualTo(replacement);
        tx.executeWithoutResult(status -> assertThat(em.find(TripAttachment.class, fixture.photoId()).getDeletedAt()).isNull());
    }

    @Test
    void 앞선_신규블록을_저장했어도_뒤의_장소검증_실패시_전체롤백한다() {
        Long good = newPhoto(true);
        Long bad = newPhoto(false);
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(null, good, 1), block(null, bad, 2))))
                .isInstanceOf(InvalidStoryUpdateException.class);
        tx.executeWithoutResult(status -> {
            assertThat(em.find(Story.class, fixture.storyId()).getStorySummary()).isEqualTo("기존 요약");
            assertThat(em.find(StoryBlock.class, fixture.blockId()).getDeletedAt()).isNull();
            assertThat(em.createQuery("select count(b) from StoryBlock b where b.storyId = :id", Long.class)
                    .setParameter("id", fixture.storyId()).getSingleResult()).isEqualTo(1L);
        });
    }

    @Test
    void 타인과_없는블록과_타여행사진과_중복사진을_거절한다() {
        var valid = request(block(fixture.blockId(), fixture.photoId(), 1));
        assertThatThrownBy(() -> service.update(-1L, fixture.storyId(), valid))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(block(-1L, fixture.photoId(), 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        Long otherPhoto = tx.execute(status -> {
            Trip other = new Trip(fixture.userId(), "다른 여행", LocalDate.now(), LocalDate.now());
            em.persist(other);
            return photo(fixture.userId(), other.getId(), place(other.getId(), 3)).getId();
        });
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(block(null, otherPhoto, 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1), block(null, fixture.photoId(), 2))))
                .isInstanceOf(AttachmentCannotBeReusedException.class);
    }

    @Test
    void 삭제사진과_다른블록이_차지한_사진을_거절한다() {
        Long additional = newPhoto(true);
        Long additionalBlock = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1), block(null, additional, 2)))
                .days().getLast().blocks().getFirst().storyBlockId();
        assertThat(additionalBlock).isNotEqualTo(fixture.blockId());
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), additional, 1))))
                .isInstanceOf(AttachmentCannotBeReusedException.class);
        tx.executeWithoutResult(status -> em.find(TripAttachment.class, additional).softDelete(LocalDateTime.now()));
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(block(additionalBlock, additional, 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
    }

    @Test
    void 유지와_교체와_삭제이력에_프리뷰키를_보존한다() {
        service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1)));
        StoryContentDocument retained = contents.findById(fixture.storyId()).orElseThrow();
        String originalKey = tx.execute(status ->
                em.find(TripAttachment.class, fixture.photoId()).getPreviewStorageKey());
        assertThat(retained.getBlocks().getFirst().previewStorageKey()).isEqualTo(originalKey);

        Long addedPhoto = newPhoto(true);
        service.update(fixture.userId(), fixture.storyId(), request(block(null, addedPhoto, 1)));
        StoryContentDocument changed = contents.findById(fixture.storyId()).orElseThrow();
        String addedKey = tx.execute(status ->
                em.find(TripAttachment.class, addedPhoto).getPreviewStorageKey());
        assertThat(changed.getBlocks().getFirst().previewStorageKey()).isEqualTo(addedKey);
        assertThat(changed.getBlocks()).filteredOn(item -> item.id().equals(fixture.blockId()))
                .allSatisfy(item -> {
                    assertThat(item.deletedAt()).isNotNull();
                    assertThat(item.previewStorageKey()).isEqualTo(originalKey);
                });
    }

    @Test
    void 정상_전체수정은_중복_장소조회없이_SQL_다섯번을_사용한다() {
        StorySqlCounterConfiguration.begin();
        try {
            StoryUpdateResponse response = service.update(
                    fixture.userId(), fixture.storyId(),
                    request(block(fixture.blockId(), fixture.photoId(), 1))
            );
            List<String> queries = StorySqlCounterConfiguration.finish();

            assertThat(response.storySummary()).isEqualTo("수정 요약");
            assertThat(contents.findById(fixture.storyId()).orElseThrow().getStorySummary())
                    .isEqualTo("수정 요약");
            assertThat(queries).hasSize(5);
            assertThat(queries).noneMatch(sql -> sql.contains("from trip_detail_places"));
        } finally {
            StorySqlCounterConfiguration.clear();
        }
    }

    @Test
    void 같은_트랜잭션에서_사진의_FK만_바뀌어도_새_장소와_날짜를_사용한다() {
        Long replacementId = newPhoto(true);
        tx.executeWithoutResult(status -> {
            TripAttachment replacement = em.find(TripAttachment.class, replacementId);
            Long previousPlaceId = replacement.getTripPlace().getId();
            TripDetailPlace movedPlace = place(fixture.tripId(), 5);
            replacement.classify(
                    movedPlace.getId(), RegionOrigin.EXIF, movedPlace.getStartedAt(),
                    BigDecimal.ZERO, BigDecimal.ZERO, 1
            );
            em.flush();
            assertThat(replacement.getTripPlace().getId()).isEqualTo(previousPlaceId);

            StoryUpdateResponse response = service.update(
                    fixture.userId(), fixture.storyId(),
                    request(block(fixture.blockId(), replacementId, 1))
            );

            assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(response.days().getFirst().blocks().getFirst().tripPlaceId())
                    .isEqualTo(movedPlace.getId());
        });
    }

    @Test
    void 메모_부분수정은_전체배열_전송없이_다른블록의_수정시각과_이력을_보존한다() {
        Long addedPhotoId = newPhoto(true);
        service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1), block(null, addedPhotoId, 2)
        ));
        Long deletedPhotoId = newPhoto(true);
        StoryContentDocument prepared = contents.findById(fixture.storyId()).orElseThrow();
        Instant previousTime = Instant.parse("2026-10-01T00:00:00Z");
        List<StoryContentBlock> blocks = new ArrayList<>(prepared.getBlocks().stream()
                .map(block -> withUpdatedAt(block, previousTime))
                .toList());
        StoryContentBlock deleted = new StoryContentBlock(
                prepared.issueBlockId(), deletedPhotoId, fixture.placeId(),
                LocalDate.of(2026, 10, 1), "삭제 날짜", "삭제 설명", "삭제 메모",
                previousTime, previousTime, previousTime, "deleted-preview"
        );
        blocks.add(deleted);
        prepared.updateContent(prepared.getMood(), prepared.getStorySummary(), blocks);
        ReflectionTestUtils.setField(prepared, "initialContentHash", "a".repeat(64));
        contents.save(prepared);
        StoryContentDocument before = contents.findById(fixture.storyId()).orElseThrow();
        List<StoryUpdateRequest.Block> requested = List.of(
                new StoryUpdateRequest.Block(
                        blocks.getFirst().id(), fixture.photoId(), "요청 날짜", "요청 설명", "변경 메모", 1
                ),
                block(blocks.get(1).id(), addedPhotoId, 2)
        );
        MONGO_UPDATES.clear();

        service.update(fixture.userId(), fixture.storyId(), new StoryUpdateRequest(
                before.getMood(), before.getStorySummary(), requested
        ));

        List<BsonDocument> commands = List.copyOf(MONGO_UPDATES);
        assertThat(commands).hasSize(1);
        BsonDocument operation = commands.getFirst().getArray("updates").getFirst().asDocument();
        BsonDocument update = operation.getDocument("u");
        assertThat(update).containsKey("$set");
        assertThat(update.getDocument("$set"))
                .containsKeys("blocks.0.memo", "blocks.0.updatedAt", "blocks.1.updatedAt")
                .doesNotContainKeys("blocks", "blocks.1.memo", "blocks.2.updatedAt");
        assertThat(operation.getDocument("q").getNumber("version").longValue())
                .isEqualTo(before.getVersion());
        assertThat(update.getDocument("$inc").getNumber("version").longValue()).isEqualTo(1L);
        StoryContentDocument after = contents.findById(fixture.storyId()).orElseThrow();
        assertThat(after.getBlocks().getFirst().memo()).isEqualTo("변경 메모");
        assertThat(after.getBlocks().getFirst().updatedAt()).isAfter(previousTime);
        assertThat(after.getBlocks().get(1).updatedAt()).isAfter(previousTime);
        assertThat(after.getBlocks().get(2)).isEqualTo(deleted);
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(after.getInitialContentHash()).isEqualTo("a".repeat(64));
        assertThat(after.getSelectedPlaceIds()).isEqualTo(before.getSelectedPlaceIds());
        assertThat(after.getNextBlockId()).isEqualTo(before.getNextBlockId());
    }

    @Test
    void 블록_재정렬은_전체저장으로_순서와_버전을_함께_변경한다() {
        Long addedPhotoId = newPhoto(true);
        StoryUpdateResponse first = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1), block(null, addedPhotoId, 2)
        ));
        Long addedBlockId = first.days().getLast().blocks().getFirst().storyBlockId();
        StoryContentDocument before = contents.findById(fixture.storyId()).orElseThrow();
        MONGO_UPDATES.clear();

        service.update(fixture.userId(), fixture.storyId(), request(
                block(addedBlockId, addedPhotoId, 1), block(fixture.blockId(), fixture.photoId(), 2)
        ));

        List<BsonDocument> commands = List.copyOf(MONGO_UPDATES);
        assertThat(commands).hasSize(1);
        BsonDocument update = commands.getFirst().getArray("updates").getFirst()
                .asDocument().getDocument("u");
        assertThat(update).doesNotContainKey("$set").containsKey("blocks");
        StoryContentDocument after = contents.findById(fixture.storyId()).orElseThrow();
        assertThat(after.getBlocks()).extracting(StoryContentBlock::id)
                .containsExactly(addedBlockId, fixture.blockId());
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 동일요청과_null메모도_부분갱신으로_버전을_한번_증가시킨다(boolean clearMemo) {
        service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1)));
        StoryContentDocument before = contents.findById(fixture.storyId()).orElseThrow();
        StoryContentBlock existing = before.getBlocks().getFirst();
        String memo = clearMemo ? null : existing.memo();
        StoryUpdateRequest input = new StoryUpdateRequest(
                before.getMood(), before.getStorySummary(), List.of(new StoryUpdateRequest.Block(
                        existing.id(), existing.tripAttachmentId(), existing.dayLabel(),
                        existing.detailSummary(), memo, 1
                ))
        );
        MONGO_UPDATES.clear();

        service.update(fixture.userId(), fixture.storyId(), input);

        StoryContentDocument after = contents.findById(fixture.storyId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
        assertThat(after.getBlocks().getFirst().memo()).isEqualTo(memo);
        assertThat(after.getBlocks().getFirst().createdAt()).isEqualTo(existing.createdAt());
        List<BsonDocument> commands = List.copyOf(MONGO_UPDATES);
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().getArray("updates").getFirst().asDocument().getDocument("u"))
                .containsKey("$set").doesNotContainKey("blocks");
    }

    @Test
    void 부분갱신_직전에_문서가_삭제되면_다시_생성하지_않는다() {
        service.update(fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1)));
        doAnswer(invocation -> {
            mongoTemplate.remove(
                    Query.query(
                            Criteria.where("_id").is(fixture.storyId())
                    ), StoryContentDocument.class
            );
            return invocation.callRealMethod();
        }).when(contentUpdates).saveChanges(any(StoryContentDocument.class), anyList());

        assertThatThrownBy(() -> service.update(
                fixture.userId(), fixture.storyId(),
                request(block(fixture.blockId(), fixture.photoId(), 1))
        )).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(contents.findById(fixture.storyId())).isEmpty();
    }

    private StoryContentBlock withUpdatedAt(StoryContentBlock block, Instant updatedAt) {
        return new StoryContentBlock(
                block.id(), block.tripAttachmentId(), block.sourceTripPlaceId(), block.sourceDate(),
                block.dayLabel(), block.detailSummary(), block.memo(), block.createdAt(),
                updatedAt, block.deletedAt(), block.previewStorageKey()
        );
    }

    private StoryUpdateRequest request(StoryUpdateRequest.Block... blocks) {
        return new StoryUpdateRequest(Story.Mood.CALM, "수정 요약", List.of(blocks));
    }

    private StoryUpdateRequest.Block block(Long id, Long photoId, int order) {
        return new StoryUpdateRequest.Block(id, photoId, "요청 날짜", "요청 설명", "요청 메모", order);
    }

    private Long newPhoto(boolean withPlace) {
        return tx.execute(status -> photo(fixture.userId(), fixture.tripId(),
                withPlace ? place(fixture.tripId(), 4) : null).getId());
    }

    private TripDetailPlace place(Long tripId, int day) {
        var place = TripDetailPlace.fromAnalysis(tripId, day, "장소" + day, BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDateTime.of(2026, 10, day, 12, 0),
                LocalDateTime.of(2026, 10, day, 13, 0), null);
        em.persist(place);
        return place;
    }

    private TripAttachment photo(Long userId, Long tripId, TripDetailPlace place) {
        String key = UUID.randomUUID().toString();
        StoredFile file = StoredFile.uploaded(userId, "사진.jpg", key, "image/jpeg");
        em.persist(file);
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), key + "-analyze", key + "-preview");
        if (place != null) {
            photo.classify(place.getId(), RegionOrigin.EXIF, place.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        }
        em.persist(photo);
        return photo;
    }
}
