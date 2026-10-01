package com.yeodam.yeodambe.trip.performance;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class PostprocessLoadRunner implements AutoCloseable {
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity;
    private record Pending(Future<?> future,java.util.concurrent.atomic.AtomicBoolean recorded) {}
    private final List<Pending> futures=new CopyOnWriteArrayList<>();
    private final Consumer<LoadReport.JourneyResult> sink;
    private final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
    private volatile long maxLateness;
    PostprocessLoadRunner(int maxInFlight,Consumer<LoadReport.JourneyResult> sink) {
        if(maxInFlight<1)throw new IllegalArgumentException("maxInFlight must be positive");
        capacity=new Semaphore(maxInFlight);this.sink=sink;
    }
    boolean submit(Callable<LoadReport.JourneyResult> action) {
        if(!capacity.tryAcquire()) {sink.accept(new LoadReport.JourneyResult(0,"",0,0,"","drop"));return false;}
        var recorded=new java.util.concurrent.atomic.AtomicBoolean();
        var future=workers.submit(()->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try {var result=action.call();if(result!=null && recorded.compareAndSet(false,true))sink.accept(result);}
            catch(Exception e) {if(recorded.compareAndSet(false,true))sink.accept(new LoadReport.JourneyResult(0,"",0,0,e.getClass().getSimpleName(),"failure"));}
            finally {active.decrementAndGet();capacity.release();}
        });futures.add(new Pending(future,recorded));return true;
    }
    int active() {return active.get();} int peak() {return peak.get();} long maxLatenessNanos() {return maxLateness;}
    void runArrivals(List<Callable<LoadReport.JourneyResult>> actions,int rate,Duration duration,java.util.function.BooleanSupplier stop) throws InterruptedException {
        if(rate<1||duration.isNegative()||duration.isZero())throw new IllegalArgumentException("Invalid arrival schedule");
        long interval=TimeUnit.MINUTES.toNanos(1)/rate;long start=System.nanoTime();
        try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
            var done=new CountDownLatch(1);var index=new AtomicInteger();
            scheduler.scheduleAtFixedRate(()->{
                int i=index.getAndIncrement();long planned=(long)i*interval;
                if(i>=actions.size() || planned>=duration.toNanos() || stop.getAsBoolean()) {done.countDown();return;}
                maxLateness=Math.max(maxLateness,Math.max(0,System.nanoTime()-start-planned));submit(actions.get(i));
            },0,interval,TimeUnit.NANOSECONDS);
            if(!done.await(duration.plusSeconds(2).toMillis(),TimeUnit.MILLISECONDS))throw new IllegalStateException("Arrival scheduler did not finish");
            scheduler.shutdownNow();
        }
    }
    void drain(Duration timeout) {
        long deadline=System.nanoTime()+timeout.toNanos();
        try {for(var f:futures)f.future().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(Exception e) {futures.forEach(f->{if(f.recorded().compareAndSet(false,true))sink.accept(new LoadReport.JourneyResult(0,"",0,0,"drain_deadline","timeout"));f.future().cancel(true);});workers.shutdownNow();throw new IllegalStateException("Unfinished requests at drain deadline",e);}
    }
    @Override public void close() {
        futures.forEach(f->{if(!f.future().isDone()) {if(f.recorded().compareAndSet(false,true))sink.accept(new LoadReport.JourneyResult(0,"",0,0,"cancelled","timeout"));f.future().cancel(true);}});workers.shutdownNow();
        try {if(!workers.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Workers did not terminate; preserve fixtures");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
}
