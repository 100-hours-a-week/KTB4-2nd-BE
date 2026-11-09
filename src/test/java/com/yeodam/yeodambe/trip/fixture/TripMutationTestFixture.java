package com.yeodam.yeodambe.trip.fixture;

import com.yeodam.yeodambe.trip.service.TripMutationCoordinator;
import org.mockito.Answers;
import org.mockito.Mockito;

import java.util.function.Supplier;

public final class TripMutationTestFixture {
    private TripMutationTestFixture() {
    }

    public static TripMutationCoordinator direct() {
        return Mockito.mock(TripMutationCoordinator.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute") || invocation.getMethod().getName().equals("executeAll")) {
                Supplier<?> operation = invocation.getArgument(1);
                return operation.get();
            }
            if (invocation.getMethod().getName().equals("run") || invocation.getMethod().getName().equals("runAll")) {
                Runnable operation = invocation.getArgument(1);
                operation.run();
                return null;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
