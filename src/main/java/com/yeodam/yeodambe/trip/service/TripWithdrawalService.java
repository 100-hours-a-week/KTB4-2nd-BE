package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TripWithdrawalService {

    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final StoredFileRepository storedFileRepository;

    @Transactional
    public void withdrawAll(Long userId, LocalDateTime withdrawnAt) {
        tripRegionRepository.softDeleteByUserId(userId, withdrawnAt);
        tripDetailPlaceRepository.softDeleteByUserId(userId, withdrawnAt);
        tripAttachmentRepository.softDeleteByUserId(userId, withdrawnAt);
        storedFileRepository.softDeleteByUserId(userId, withdrawnAt);
        tripRepository.softDeleteByUserId(userId, withdrawnAt);
    }
}