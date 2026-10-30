package com.yeodam.yeodambe.trip.service;

import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ExifToolProcessTestSupport {
    private ExifToolProcessTestSupport() {
    }

    static Process 응답_설정(ExifToolProcess runner, String output, ExecutorService executor) {
        Process child = mock(Process.class);
        AtomicBoolean alive = new AtomicBoolean(true);
        when(child.isAlive()).thenAnswer(invocation -> alive.get());
        when(child.destroyForcibly()).thenAnswer(invocation -> {
            alive.set(false);
            return child;
        });
        ReflectionTestUtils.setField(runner, "process", child);
        ReflectionTestUtils.setField(runner, "commandWriter", new BufferedWriter(new StringWriter()));
        ReflectionTestUtils.setField(runner, "responseReader", new BufferedReader(new StringReader(output)));
        ReflectionTestUtils.setField(runner, "responseExecutor", executor);
        return child;
    }
}
