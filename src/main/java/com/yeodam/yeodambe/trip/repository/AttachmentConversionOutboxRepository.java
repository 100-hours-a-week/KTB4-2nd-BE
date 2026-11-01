package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentConversionOutboxRepository extends JpaRepository<AttachmentConversionOutbox, String> {
}
