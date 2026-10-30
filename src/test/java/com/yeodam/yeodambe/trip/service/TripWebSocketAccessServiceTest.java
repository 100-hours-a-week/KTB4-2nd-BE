package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.service.response.TripPresenceAccessResponse;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.service.ShareUserSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripWebSocketAccessServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripShareUserRepository shares = mock(TripShareUserRepository.class);
    private final ShareUserSearchService users = mock(ShareUserSearchService.class);
    private final Instant now = Instant.parse("2026-10-29T05:00:00Z");
    private final LocalDateTime localNow = LocalDateTime.ofInstant(now, ZoneId.of("Asia/Seoul"));
    private final TripWebSocketAccessService service = new TripWebSocketAccessService(
            trips, shares, users, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void 테스트_준비() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 10L);
        when(trips.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(trip));
        User user = new User("member@test.com", "사용자");
        ReflectionTestUtils.setField(user, "userId", 2L);
        when(users.requireActiveUser(anyLong())).thenReturn(user);
    }

    @Test
    void 소유자는_공유_관계_없이_30초_권한을_받는다() {
        TripPresenceAccessResponse result = service.findPresenceAccess(10L, 1L);
        assertThat(result.permission()).isEqualTo("OWNER");
        assertThat(result.accessValidUntil()).isEqualTo(now.plusSeconds(30));
        assertThat(result.profileImageUrl()).isNull();
        verifyNoInteractions(shares);
    }

    @Test
    void 읽기_사용자의_권한_기한은_공유_만료를_넘지_않는다() {
        when(shares.findByTripIdAndGranteeUserId(10L, 2L)).thenReturn(Optional.of(
                new TripShareUser(10L, 1L, 2L, TripSharePermission.READ,
                        localNow.plusSeconds(12), localNow)));
        TripPresenceAccessResponse result = service.findPresenceAccess(10L, 2L);
        assertThat(result.permission()).isEqualTo("READ");
        assertThat(result.accessValidUntil()).isEqualTo(now.plusSeconds(12));
    }

    @Test
    void 공유_만료가_현재_시각이면_권한을_허용하지_않는다() {
        when(shares.findByTripIdAndGranteeUserId(10L, 2L)).thenReturn(Optional.of(
                new TripShareUser(10L, 1L, 2L, TripSharePermission.WRITE, localNow, localNow)));
        assertThatThrownBy(() -> service.findPresenceAccess(10L, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 잘못된_발급자나_삭제된_공유_관계는_거절한다() {
        TripShareUser share = new TripShareUser(10L, 99L, 2L, TripSharePermission.WRITE,
                localNow.plusDays(1), localNow);
        when(shares.findByTripIdAndGranteeUserId(10L, 2L)).thenReturn(Optional.of(share));
        assertThatThrownBy(() -> service.findPresenceAccess(10L, 2L))
                .isInstanceOf(TripNotFoundException.class);
        ReflectionTestUtils.setField(share, "userId", 1L);
        ReflectionTestUtils.setField(share, "deletedAt", localNow);
        assertThatThrownBy(() -> service.findPresenceAccess(10L, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 여행이나_공유_관계가_없으면_존재_여부를_노출하지_않는다() {
        assertThatThrownBy(() -> service.findPresenceAccess(99L, 2L))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> service.findPresenceAccess(10L, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }
}
