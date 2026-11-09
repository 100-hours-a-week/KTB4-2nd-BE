package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripAnalysisExecutionUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripAnalysisExecutionUploadRepository extends JpaRepository<TripAnalysisExecutionUpload, Long> {
    List<TripAnalysisExecutionUpload> findAllByExecutionIdOrderById(String executionId);
}
