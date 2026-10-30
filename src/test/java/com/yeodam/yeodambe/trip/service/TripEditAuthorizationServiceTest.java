package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.response.TripEditAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripEditAuthorizationServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private final TripShareUserRepository shares = mock(TripShareUserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC);
    private final TripEditAuthorizationService service = new TripEditAuthorizationService(shares, clock);
    private Trip trip;

    @BeforeEach
    void fixture() {
        trip = new Trip(1L, "권한검사", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30));
        ReflectionTestUtils.setField(trip, "id", 73L);
    }

    @Test
    void 소유자의_읽기_권한은_검사_시각부터_30초간_유효하다() {
        TripEditAccess access = service.requireReadPermission(trip, 1L);

        assertThat(access.permission()).isEqualTo("OWNER");
        assertThat(access.checkedAt()).isEqualTo(clock.instant());
        assertThat(access.accessValidUntil()).isEqualTo(clock.instant().plusSeconds(30));
        verifyNoInteractions(shares);
    }

    @Test
    void READ_공유도_읽을_수_있고_유효기간은_공유_만료를_넘지_않는다() {
        stored(share(1L, TripSharePermission.READ, NOW.plusSeconds(5)));

        TripEditAccess access = service.requireReadPermission(trip, 2L);

        assertThat(access.permission()).isEqualTo("READ");
        assertThat(access.accessValidUntil()).isEqualTo(clock.instant().plusSeconds(5));
    }

    @Test
    void WRITE_공유_읽기_검사는_30초를_상한으로_사용한다() {
        stored(share(1L, TripSharePermission.WRITE, NOW.plusHours(1)));

        TripEditAccess access = service.requireReadPermission(trip, 2L);

        assertThat(access.permission()).isEqualTo("WRITE");
        assertThat(access.accessValidUntil()).isEqualTo(clock.instant().plusSeconds(30));
    }

    @Test
    void 소유자는_공유_관계를_조회하지_않고_허용한다() {
        assertThatCode(() -> service.requireWritePermission(trip, 1L)).doesNotThrowAnyException();
        verifyNoInteractions(shares);
    }

    @Test
    void 유효한_WRITE_공유자는_허용한다() {
        stored(share(1L, TripSharePermission.WRITE, NOW.plusSeconds(1)));

        assertThatCode(() -> service.requireWritePermission(trip, 2L)).doesNotThrowAnyException();
    }

    @Test
    void READ_공유자는_쓰기_권한_오류로_거절한다() {
        stored(share(1L, TripSharePermission.READ, NOW.plusHours(1)));

        assertThatThrownBy(() -> service.requireWritePermission(trip, 2L))
                .isInstanceOf(WritePermissionRequiredException.class);
    }

    @Test
    void 공유_관계가_없으면_여행_접근을_거절한다() {
        when(shares.findByTripIdAndGranteeUserId(73L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireWritePermission(trip, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 한국_저장시각_기준으로_만료_시각부터_거절한다(int remainingSeconds) {
        stored(share(1L, TripSharePermission.WRITE, NOW.plusSeconds(remainingSeconds)));

        assertThatThrownBy(() -> service.requireWritePermission(trip, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 철회한_공유는_기한이_남아도_거절한다() {
        TripShareUser share = share(1L, TripSharePermission.WRITE, NOW.plusHours(1));
        ReflectionTestUtils.setField(share, "deletedAt", NOW.minusSeconds(1));
        stored(share);

        assertThatThrownBy(() -> service.requireWritePermission(trip, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 현재_여행_소유자가_발급한_공유만_허용한다() {
        stored(share(99L, TripSharePermission.WRITE, NOW.plusHours(1)));

        assertThatThrownBy(() -> service.requireWritePermission(trip, 2L))
                .isInstanceOf(TripNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L})
    void 삭제된_여행은_소유자와_공유자_모두_거절한다(Long actorId) {
        trip.softDelete(NOW);

        assertThatThrownBy(() -> service.requireWritePermission(trip, actorId))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(shares);
    }

    private TripShareUser share(Long issuerId, TripSharePermission permission, LocalDateTime expiresAt) {
        return new TripShareUser(73L, issuerId, 2L, permission, expiresAt, NOW.minusHours(1));
    }

    private void stored(TripShareUser share) {
        when(shares.findByTripIdAndGranteeUserId(73L, 2L)).thenReturn(Optional.of(share));
    }
}
