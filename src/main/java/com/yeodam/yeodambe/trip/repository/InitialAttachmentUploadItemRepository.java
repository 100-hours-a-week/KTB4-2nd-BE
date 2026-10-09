package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InitialAttachmentUploadItemRepository
        extends JpaRepository<InitialAttachmentUploadItem, Long> {

    List<InitialAttachmentUploadItem>
    findAllByBatch_IdOrderByFileOrderAsc(Long batchId);

    List<InitialAttachmentUploadItem> findAllByBatch_ExecutionId(
            String executionId
    );

    @Query("""
            select item from InitialAttachmentUploadItem item
            join fetch item.batch batch
            where batch.executionId = :executionId
            order by batch.batchNo, item.fileOrder
            """)
    List<InitialAttachmentUploadItem> findExecutionItems(@Param("executionId") String executionId);
}
