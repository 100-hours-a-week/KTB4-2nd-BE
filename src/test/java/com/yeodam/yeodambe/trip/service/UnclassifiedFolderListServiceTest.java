package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentIssueException;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.UnclassifiedFolderAttachmentCount;
import com.yeodam.yeodambe.trip.service.request.AttachmentCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnclassifiedFolderListServiceTest {
    private final TripAccessService access = mock(TripAccessService.class);
    private final TripAttachmentRepository repository = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UnclassifiedFolderListService service = new UnclassifiedFolderListService(
            access, repository, storage, objectMapper
    );

    @Test
    void 빈_여행도_세_폴더를_고정_순서로_반환한다() {
        when(repository.countUnclassifiedByIssue(7L)).thenReturn(List.of());
        var response = service.findFolders(1L, 7L);
        assertThat(response.folders()).extracting(folder -> folder.issue())
                .containsExactly(AttachmentIssue.UNCLEAR_LOCATION, AttachmentIssue.BLURRY, AttachmentIssue.DUPLICATED);
        assertThat(response.folders()).extracting(folder -> folder.name())
                .containsExactly("장소가 명확하지 않은 첨부", "흐릿한 첨부", "비슷한 첨부");
        assertThat(response.folders()).allSatisfy(folder -> {
            assertThat(folder.attachmentCount()).isZero();
            assertThat(folder.representativeAttachment()).isNull();
        });
        verify(repository).countUnclassifiedByIssue(7L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(storage);
    }

    @Test
    void 사진이_있는_폴더만_대표_한장과_조회_URL을_구성한다() {
        when(repository.countUnclassifiedByIssue(7L)).thenReturn(
                List.of(new UnclassifiedFolderAttachmentCount(AttachmentIssue.BLURRY, 8L)));
        TripAttachment photo = mock(TripAttachment.class);
        when(photo.getId()).thenReturn(503L);
        when(photo.getPreviewStorageKey()).thenReturn("preview/503");
        when(repository.findUnclassifiedRepresentatives(7L, AttachmentIssue.BLURRY, PageRequest.of(0, 1)))
                .thenReturn(List.of(photo));
        when(storage.createReadUrl("preview/503")).thenReturn("https://example.test/503");
        var folder = service.findFolders(1L, 7L).folders().get(1);
        assertThat(folder.attachmentCount()).isEqualTo(8);
        assertThat(folder.representativeAttachment().tripAttachmentId()).isEqualTo(503);
        assertThat(folder.representativeAttachment().thumbnailUrl()).isEqualTo("https://example.test/503");
        verify(repository).countUnclassifiedByIssue(7L);
        verify(repository).findUnclassifiedRepresentatives(7L, AttachmentIssue.BLURRY, PageRequest.of(0, 1));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 접근불가_여행은_사진과_URL_조회_전에_거절한다() {
        doThrow(new TripNotFoundException()).when(access).requireReadableTrip(7L, 1L);
        assertThatThrownBy(() -> service.findFolders(1L, 7L)).isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(repository, storage);
    }

    @ParameterizedTest
    @CsvSource({
            "UNCLEAR_LOCATION, 장소가 명확하지 않은 첨부",
            "BLURRY, 흐릿한 첨부",
            "DUPLICATED, 비슷한 첨부"
    })
    void 사유별_이름_URL과_nullable_복구장소를_반환한다(AttachmentIssue issue, String name) {
        TripAttachment restorable = attachment(503L, issue, 31L);
        TripAttachment withoutPlace = attachment(502L, issue, null);
        when(repository.findUnclassifiedByIssueWithCursor(
                7L, issue, null, null, PageRequest.of(0, 19)
        )).thenReturn(List.of(restorable, withoutPlace));
        when(repository.countUnclassifiedByIssue(7L)).thenReturn(List.of(
                new UnclassifiedFolderAttachmentCount(issue, 2L)
        ));

        var response = service.findAttachments(1L, 7L, issue.name(), null);

        assertThat(response.issue()).isEqualTo(issue);
        assertThat(response.name()).isEqualTo(name);
        assertThat(response.attachmentCount()).isEqualTo(2);
        assertThat(response.items()).extracting(item -> item.tripAttachmentId())
                .containsExactly(503L, 502L);
        assertThat(response.items()).extracting(item -> item.issue()).containsOnly(issue);
        assertThat(response.items()).extracting(item -> item.thumbnailUrl())
                .containsExactly("https://example.test/503", "https://example.test/502");
        assertThat(response.items()).extracting(item -> item.restoreTripPlaceId())
                .containsExactly(31L, null);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"NONE", "blurry", " BLURRY", "BLURRY ", " ", "UNKNOWN"})
    void 허용되지_않은_사유는_접근검증_전에_거절한다(String issue) {
        assertThatThrownBy(() -> service.findAttachments(1L, 7L, issue, null))
                .isInstanceOf(InvalidAttachmentIssueException.class);
        verifyNoInteractions(access, repository, storage);
    }

    @Test
    void 내부목록도_접근거절은_커서해석_사진_URL_조회_전에_처리한다() {
        doThrow(new TripNotFoundException()).when(access).requireReadableTrip(7L, 1L);

        assertThatThrownBy(() -> service.findAttachments(1L, 7L, "BLURRY", "broken!"))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(repository, storage);
    }

    @Test
    void 잘못된_커서는_사진_URL_조회_전에_거절한다() {
        assertThatThrownBy(() -> service.findAttachments(1L, 7L, "BLURRY", "broken!"))
                .isInstanceOf(InvalidCursorException.class);
        verify(access).requireReadableTrip(7L, 1L);
        verifyNoInteractions(repository, storage);
    }

    @Test
    void 빈_목록은_빈_items와_기본_개수_0을_반환한다() {
        when(repository.findUnclassifiedByIssueWithCursor(
                7L, AttachmentIssue.BLURRY, null, null, PageRequest.of(0, 19)
        )).thenReturn(List.of());
        when(repository.countUnclassifiedByIssue(7L)).thenReturn(List.of(
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.DUPLICATED, 3L)
        ));

        var response = service.findAttachments(1L, 7L, "BLURRY", null);

        assertThat(response.items()).isEmpty();
        assertThat(response.attachmentCount()).isZero();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verifyNoInteractions(storage);
    }

    @Test
    void 사진18개_조회는_다음커서_없이_전체개수를_반환한다() {
        List<TripAttachment> attachments = page(18);
        stubPage(attachments, 25L);

        var response = service.findAttachments(1L, 7L, "BLURRY", null);

        assertThat(response.items()).hasSize(18);
        assertThat(response.attachmentCount()).isEqualTo(25);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        verify(storage, times(18)).createReadUrl(anyString());
    }

    @Test
    @DisplayName("19개_조회시_18개만_반환하고_마지막_반환사진으로_커서를_만든다")
    void 사진19개_조회시_18개만_반환하고_마지막_반환사진으로_커서를_만든다() {
        List<TripAttachment> attachments = page(19);
        stubPage(attachments, 25L);

        var response = service.findAttachments(1L, 7L, "BLURRY", null);

        assertThat(response.items()).hasSize(18);
        assertThat(response.attachmentCount()).isEqualTo(25);
        assertThat(response.hasNext()).isTrue();
        assertThat(AttachmentCursor.decode(response.nextCursor(), objectMapper))
                .isEqualTo(new AttachmentCursor(
                        attachments.get(17).getCreatedAt(),
                        attachments.get(17).getId()
                ));
        verify(storage, times(18)).createReadUrl(anyString());
        verify(storage, never()).createReadUrl(attachments.get(18).getPreviewStorageKey());
    }

    @Test
    void 전달한_커서의_시각과_ID로_다음페이지를_조회한다() {
        AttachmentCursor cursor = new AttachmentCursor(LocalDateTime.of(2026, 10, 9, 10, 0), 503L);
        when(repository.findUnclassifiedByIssueWithCursor(
                7L, AttachmentIssue.BLURRY, cursor.createdAt(), cursor.tripAttachmentId(), PageRequest.of(0, 19)
        )).thenReturn(List.of());

        service.findAttachments(1L, 7L, "BLURRY", cursor.encode(objectMapper));

        verify(repository).findUnclassifiedByIssueWithCursor(
                7L, AttachmentIssue.BLURRY, cursor.createdAt(), cursor.tripAttachmentId(), PageRequest.of(0, 19)
        );
    }

    @Test
    void URL_발급_실패는_요청_실패로_전파한다() {
        TripAttachment attachment = attachment(503L, AttachmentIssue.BLURRY, null);
        stubPage(List.of(attachment), 1L);
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        when(storage.createReadUrl("preview/503")).thenThrow(failure);

        assertThatThrownBy(() -> service.findAttachments(1L, 7L, "BLURRY", null))
                .isSameAs(failure);
    }

    private List<TripAttachment> page(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> attachment(503L - index, AttachmentIssue.BLURRY, null))
                .toList();
    }

    private void stubPage(List<TripAttachment> attachments, long total) {
        when(repository.findUnclassifiedByIssueWithCursor(
                7L, AttachmentIssue.BLURRY, null, null, PageRequest.of(0, 19)
        )).thenReturn(attachments);
        when(repository.countUnclassifiedByIssue(7L)).thenReturn(List.of(
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.BLURRY, total)
        ));
    }

    private TripAttachment attachment(Long id, AttachmentIssue issue, Long placeId) {
        TripAttachment attachment = mock(TripAttachment.class);
        when(attachment.getId()).thenReturn(id);
        when(attachment.getIssue()).thenReturn(issue);
        when(attachment.getTripPlaceId()).thenReturn(placeId);
        when(attachment.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 10, 9, 10, 0));
        when(attachment.getPreviewStorageKey()).thenReturn("preview/" + id);
        when(storage.createReadUrl("preview/" + id)).thenReturn("https://example.test/" + id);
        return attachment;
    }
}
