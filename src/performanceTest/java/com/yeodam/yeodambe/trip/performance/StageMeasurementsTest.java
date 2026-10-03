package com.yeodam.yeodambe.trip.performance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class StageMeasurementsTest {
    static class Probe { public String echo(String value) { return value; } public void fail() { throw new IllegalStateException("probe"); } }
    @Test void keepsReturnValueAndException() {
        var m=new StageMeasurements();m.beginRun("run");
        Probe probe=(Probe)m.wrap(new Probe(),"probe");
        assertEquals("ok",probe.echo("ok"));
        assertThrows(IllegalStateException.class,probe::fail);
        var samples=m.drain();assertEquals(2,samples.size());
        assertEquals("failure",samples.get(1).outcome());assertTrue(samples.get(0).endNanos()>=samples.get(0).startNanos());
    }
    @Test void doesNotRecordWhenRunInactive() {
        var m=new StageMeasurements();((Probe)m.wrap(new Probe(),"probe")).echo("x");assertTrue(m.drain().isEmpty());
    }
    static class AttachmentProbe { public void saveFilesAndAttachments() {} }
    @Test void attachmentSaveIncludesCommitAndPreservesTransactionAdvisor() {
        var committed = new java.util.concurrent.atomic.AtomicLong();
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) {}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { committed.set(System.nanoTime()); }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {}
        };
        var proxy = new org.springframework.aop.framework.ProxyFactory(new AttachmentProbe());
        proxy.setProxyTargetClass(true);
        var transaction = new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource());
        proxy.addAdvice(transaction);
        Object bean = proxy.getProxy();
        var m = new StageMeasurements(); m.beginRun("commit"); m.instrumentAttachmentSave(bean);
        ((AttachmentProbe)bean).saveFilesAndAttachments();
        var sample = m.drain().getFirst();
        assertEquals("attachment_save", sample.stage());
        assertTrue(committed.get() > sample.startNanos());
        assertTrue(sample.endNanos() >= committed.get());
        assertTrue(Arrays.stream(((org.springframework.aop.framework.Advised)bean).getAdvisors())
            .anyMatch(a -> a.getAdvice() == transaction));
    }
    @Test void refusesAttachmentInstrumentationWithoutTransactionAdvisor() {
        assertThrows(IllegalStateException.class, () -> new StageMeasurements().instrumentAttachmentSave(new AttachmentProbe()));
    }
    @Test void postprocessEndsAtSaveReturn() {
        var ai=new StageMeasurements.StageSample("r","req",1L,"ai",10,20,"success","",1);
        var save=new StageMeasurements.StageSample("r","req",1L,"save",30,50,"success","",1);
        assertEquals(30,StageMeasurements.postprocessNanos(List.of(ai,save)).get("req"));
    }
}
