package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;

public interface AdditionalAttachmentUploadItemRepository
        extends JpaRepository<AdditionalAttachmentUploadItem, Long> {
    List<AdditionalAttachmentUploadItem> findAllByTripAttachmentIdIn(Collection<Long> attachmentIds);

    List<AdditionalAttachmentUploadItem>
    findAllByBatch_IdOrderByFileOrderAsc(Long batchId);

    @Query("""
            select item
            from AdditionalAttachmentUploadItem item
            join fetch item.batch batch
            where batch.additionId = :additionId
            order by batch.batchNo, item.fileOrder
            """)
    List<AdditionalAttachmentUploadItem> findAdditionItems(
            @Param("additionId") String additionId
    );
}
