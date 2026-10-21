package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.function.Supplier;

@Component
public class MigrationTripMode {
    private final String modeFile;
    private boolean fencing;
    private int activeDirect;
    public MigrationTripMode(@Value("${migration.trip.mode-file:}") String modeFile) { this.modeFile=modeFile; }
    private String marker() {
        if(modeFile.isBlank()) return "direct";
        try {
            String value=Files.readString(Path.of(modeFile)).trim();
            if(!value.equals("direct") && !value.equals("queue") && !value.equals("source-queue")) throw unavailable();
            return value;
        } catch(IOException|SecurityException e) { throw unavailable(); }
    }
    public synchronized boolean queueEnabled() { return fencing || !marker().equals("direct"); }
    public synchronized boolean authQueueEnabled() { return fencing || marker().equals("queue"); }
    public synchronized int beginFence() { fencing=true;return activeDirect; }
    public synchronized int activeDirect() { return activeDirect; }
    public synchronized boolean fencing() { return fencing; }
    public <T> T trip(Supplier<T> direct,Supplier<T> queued) { return execute(true,direct,queued); }
    public <T> T auth(Supplier<T> direct,Supplier<T> queued) { return execute(false,direct,queued); }
    private <T> T execute(boolean trip,Supplier<T> direct,Supplier<T> queued) {
        boolean queue;
        synchronized(this) {
            String value=marker();
            queue=fencing || value.equals("queue") || (trip && value.equals("source-queue"));
            if(!queue) activeDirect++;
        }
        if(queue) return queued.get();
        try { return direct.get(); }
        finally {
            // The normal service proxy has committed on return. If a caller owns an
            // outer transaction, keep its permit until that transaction completes.
            if(TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { completed(); }
                });
            } else completed();
        }
    }
    private synchronized void completed() { activeDirect--;notifyAll(); }
    private MigrationTripAdmissionException unavailable() { return new MigrationTripAdmissionException(HttpStatus.SERVICE_UNAVAILABLE,ErrorMessage.MIGRATION_TRIP_MODE_UNAVAILABLE); }
}
