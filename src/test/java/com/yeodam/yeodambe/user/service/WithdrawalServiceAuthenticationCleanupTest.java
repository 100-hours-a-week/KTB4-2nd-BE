package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.TripEditLock;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WithdrawalServiceAuthenticationCleanupTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripEditLock editLock = mock(TripEditLock.class);
    private final WithdrawalTransactionService transactions = mock(WithdrawalTransactionService.class);
    private final LoginSessionStore sessions = mock(LoginSessionStore.class);
    private final CsrfTokenStore csrf = mock(CsrfTokenStore.class);
    private final WithdrawalService service = new WithdrawalService(trips, editLock, transactions, sessions, csrf);

    @BeforeEach
    void executeLockedAction() {
        when(trips.findActiveOwnedIds(42L)).thenReturn(List.of(7L));
        when(editLock.executeAll(any(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
    }

    @Test
    void 여행_목록_조회가_예상치_못하게_실패하면_탈퇴_실패로_전달한다() {
        InvalidDataAccessResourceUsageException failure = new InvalidDataAccessResourceUsageException(
                UserInternalErrorMessage.USER_STATS_MISSING.message());
        when(trips.findActiveOwnedIds(42L)).thenThrow(failure);

        assertThatThrownBy(() -> service.withdraw(42L, "browser"))
                .isInstanceOf(WithdrawalFailedException.class)
                .hasCause(failure);

        verifyNoInteractions(editLock, transactions, csrf, sessions);
    }

    @Test
    void 여행_목록의_DB_연결_실패는_기존_예외로_전달한다() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                UserInternalErrorMessage.AUTH_STORE_UNAVAILABLE.message());
        when(trips.findActiveOwnedIds(42L)).thenThrow(failure);

        assertThatThrownBy(() -> service.withdraw(42L, "browser")).isSameAs(failure);

        verifyNoInteractions(editLock, transactions, csrf, sessions);
    }

    @Test
    void 여행_락_거부는_탈퇴_실패로_바꾸지_않고_인증을_보존한다() {
        TripEditLockRejectedException failure = new TripEditLockRejectedException(
                TripEditLockRejectedException.Reason.CONTENDED);
        doThrow(failure).when(editLock).executeAll(any(), any());

        assertThatThrownBy(() -> service.withdraw(42L, "browser")).isSameAs(failure);

        verifyNoInteractions(transactions, csrf, sessions);
    }

    @Test
    void 탈퇴_트랜잭션_성공_후_CSRF와_전체_로그인_세션을_정리한다() {
        service.withdraw(42L, "browser");

        InOrder ordered = inOrder(transactions, csrf, sessions);
        ordered.verify(transactions).withdraw(42L, List.of(7L));
        ordered.verify(csrf).delete("browser");
        ordered.verify(sessions).deleteByUserId(42L);
    }

    @Test
    void 탈퇴_트랜잭션이_실패하면_인증_데이터를_정리하지_않는다() {
        doThrow(new IllegalStateException(UserInternalErrorMessage.AUTH_STORE_UNAVAILABLE.message()))
                .when(transactions).withdraw(42L, List.of(7L));

        assertThatThrownBy(() -> service.withdraw(42L, "browser")).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(csrf, sessions);
    }

    @Test
    void CSRF_정리가_실패해도_로그인_세션_정리를_시도한다() {
        doThrow(new IllegalStateException(UserInternalErrorMessage.AUTH_STORE_UNAVAILABLE.message()))
                .when(csrf).delete("browser");

        service.withdraw(42L, "browser");

        verify(sessions).deleteByUserId(42L);
    }

    @Test
    void 여행_목록_변경이_세_번_반복되면_실패를_전달하고_인증을_보존한다() {
        doThrow(new OptimisticLockingFailureException(UserInternalErrorMessage.WITHDRAWAL_TRIP_SET_CHANGED.message()))
                .when(transactions).withdraw(42L, List.of(7L));

        assertThatThrownBy(() -> service.withdraw(42L, "browser"))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(trips, times(3)).findActiveOwnedIds(42L);
        verify(transactions, times(3)).withdraw(42L, List.of(7L));
        verifyNoInteractions(csrf, sessions);
    }
}
