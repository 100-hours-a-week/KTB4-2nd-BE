package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.UnclassifiedFolderAttachmentCount;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnclassifiedFolderListServiceTest {
    private final TripAccessService access = mock(TripAccessService.class);
    private final TripAttachmentRepository repository = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final UnclassifiedFolderListService service = new UnclassifiedFolderListService(access, repository, storage);

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
}
