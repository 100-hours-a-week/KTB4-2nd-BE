package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trip_analysis_execution_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripAnalysisExecutionUpload {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false, length = 36)
    private String executionId;

    @Column(name = "addition_id", nullable = false, updatable = false, length = 36)
    private String additionId;

    public TripAnalysisExecutionUpload(String executionId, String additionId) {
        this.executionId = executionId;
        this.additionId = additionId;
    }
}
