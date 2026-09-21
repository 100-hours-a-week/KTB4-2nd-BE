package com.yeodam.yeodambe.file.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
}
