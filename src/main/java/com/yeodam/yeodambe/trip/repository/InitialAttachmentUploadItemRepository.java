package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InitialAttachmentUploadItemRepository
        extends JpaRepository<InitialAttachmentUploadItem, Long> {

    List<InitialAttachmentUploadItem>
    findAllByBatch_IdOrderByFileOrderAsc(Long batchId);
}