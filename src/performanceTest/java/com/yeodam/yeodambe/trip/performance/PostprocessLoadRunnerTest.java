package com.yeodam.yeodambe.trip.performance;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PostprocessLoadRunnerTest {
    @Test void arrivalCapacityRecordsDrops() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var results=new CopyOnWriteArrayList<LoadReport.JourneyResult>();
        try(var runner=new PostprocessLoadRunner(1,results::add)) {
            runner.submit(()->{started.countDown();release.await();return new LoadReport.JourneyResult(1,"r",1,200,"COMPLETED","success");});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            runner.submit(()->new LoadReport.JourneyResult(2,"s",1,200,"COMPLETED","success"));
            assertEquals("drop",results.getFirst().outcome());release.countDown();runner.drain(Duration.ofSeconds(2));
            assertEquals(2,results.size());
        } finally {release.countDown();}
    }
    @Test void timeoutDoesNotLeaveRunningActions() throws Exception {
        var interrupted=new CountDownLatch(1);var started=new CountDownLatch(1);
        var results=new CopyOnWriteArrayList<LoadReport.JourneyResult>();
        try(var runner=new PostprocessLoadRunner(1,results::add)) {
            runner.submit(()->{started.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();throw e;}return null;});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->runner.drain(Duration.ofMillis(20)));
            assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertEquals(1,results.size());assertEquals("timeout",results.getFirst().outcome());
        }
    }
}
