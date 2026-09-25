package com.yeodam.yeodambe.file.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    boolean existsByObjectKeyAndDeletedAtIsNull(String objectKey);

    @Modifying
    @Query("""
            update StoredFile file set file.deletedAt = :deletedAt
            where file.id in :ids and file.deletedAt is null
            """)
    int softDeleteByIds(Collection<Long> ids, LocalDateTime deletedAt);

    @Modifying
    @Query("""
            update StoredFile file
            set file.deletedAt = :deletedAt
            where file.userId = :userId
              and file.deletedAt is null
            """)
    int softDeleteByUserId(Long userId, LocalDateTime deletedAt);
}
