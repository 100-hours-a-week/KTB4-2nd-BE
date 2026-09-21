package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.LoginTicketEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoginTicketRepository
        extends JpaRepository<LoginTicketEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ticket
            FROM LoginTicketEntity ticket
            WHERE ticket.ticketHash = :ticketHash
            """)
    Optional<LoginTicketEntity> findByTicketHashForUpdate(
            @Param("ticketHash") String ticketHash
    );

    long deleteByExpiresAtLessThanEqual(LocalDateTime now);
}