package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.io.EOFException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;



@Slf4j
@Component
final class ExifToolProcess implements AutoCloseable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);

    private final ReentrantLock commandLock = new ReentrantLock();

    private Process process;
    private BufferedWriter commandWriter;
    private BufferedReader responseReader;
    private ExecutorService responseExecutor;

    private long sequence;
    private boolean closed;

    Result execute(List<String> arguments) {
        try {
            commandLock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    TripInternalErrorMessage.COMMAND_INTERRUPTED.message()
                            .formatted("exiftool"),
                    failure
            );
        }

        Future<Result> response = null;
        boolean responseReceived = false;

        try {
            startIfNeeded();

            long requestId = ++sequence;
            BufferedReader reader = responseReader;

            response = responseExecutor.submit(
                    () -> readResult(reader, requestId)
            );

            writeCommand(arguments, requestId);

            Result result = response.get(
                    COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS
            );

            responseReceived = true;
            return result;
        } catch (TimeoutException failure) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.COMMAND_TIMEOUT.message()
                            .formatted("exiftool"),
                    failure
            );
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    TripInternalErrorMessage.COMMAND_INTERRUPTED.message()
                            .formatted("exiftool"),
                    failure
            );
        } catch (ExecutionException failure) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.COMMAND_FAILED.message()
                            .formatted("exiftool"),
                    failure.getCause()
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.COMMAND_UNAVAILABLE.message()
                            .formatted("exiftool"),
                    failure
            );
        } finally {
            try {
                if (!responseReceived) {
                    if (response != null) {
                        response.cancel(true);
                    }
                    resetProcess();
                }
            } finally {
                commandLock.unlock();
            }
        }
    }

    private void startIfNeeded() throws IOException {
        if (closed) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.COMMAND_UNAVAILABLE.message()
                            .formatted("exiftool")
            );
        }

        if (process != null && process.isAlive()) {
            return;
        }

        resetProcess();

        process = new ProcessBuilder(
                "exiftool", "-stay_open", "True", "-@", "-"
        )
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        commandWriter = new BufferedWriter(
                new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8
                )
        );
        responseReader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8
                )
        );
        responseExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("exiftool-response").daemon(true).factory()
        );
    }

    private void writeCommand(List<String> arguments, long requestId)
            throws IOException {
        for (String argument : arguments) {
            if (argument == null
                    || argument.contains("\n")
                    || argument.contains("\r")
                    || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        TripInternalErrorMessage.EXIF_COMMAND_ARGUMENT_INVALID.message()
                );
            }
        }

        for (String argument : arguments) {
            commandWriter.write(argument);
            commandWriter.newLine();
        }

        commandWriter.write("-echo3");
        commandWriter.newLine();
        commandWriter.write("{status" + requestId + "}=${status}");
        commandWriter.newLine();
        commandWriter.write("-execute" + requestId);
        commandWriter.newLine();
        commandWriter.flush();
    }

    private Result readResult(BufferedReader reader, long requestId)
            throws IOException {
        String readyMarker = "{ready" + requestId + "}";
        String statusPrefix = "{status" + requestId + "}=";
        StringBuilder output = new StringBuilder();
        Integer exitStatus = null;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.equals(readyMarker)) {
                if (exitStatus == null) {
                    throw new IOException(
                            TripInternalErrorMessage.EXIF_COMMAND_RESPONSE_INVALID.message()
                    );
                }
                return new Result(output.toString(), exitStatus);
            }

            if (line.startsWith(statusPrefix)) {
                try {
                    exitStatus = Integer.parseInt(
                            line.substring(statusPrefix.length())
                    );
                } catch (NumberFormatException failure) {
                    throw new IOException(
                            TripInternalErrorMessage.EXIF_COMMAND_RESPONSE_INVALID.message(),
                            failure
                    );
                }
            } else {
                output.append(line).append('\n');
            }
        }

        throw new EOFException(
                TripInternalErrorMessage.EXIF_COMMAND_RESPONSE_INVALID.message()
        );
    }

    private void resetProcess() {
        boolean interrupted = false;

        if (process != null) {
            process.destroyForcibly();

            while (process.isAlive()) {
                try {
                    process.waitFor();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        }

        if (responseExecutor != null) {
            responseExecutor.shutdownNow();
        }

        closeQuietly(commandWriter);
        closeQuietly(responseReader);

        process = null;
        commandWriter = null;
        responseReader = null;
        responseExecutor = null;

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(Closeable stream) {
        if (stream == null) {
            return;
        }

        try {
            stream.close();
        } catch (IOException failure) {
            log.warn("ExifTool 스트림 정리 실패", failure);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        commandLock.lock();

        try {
            closed = true;
            resetProcess();
        } finally {
            commandLock.unlock();
        }
    }

    record Result(String output, int exitStatus) {
    }
}