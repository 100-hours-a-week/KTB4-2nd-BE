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
    @Test void postprocessEndsAtSaveReturn() {
        var ai=new StageMeasurements.StageSample("r","req",1L,"ai",10,20,"success","",1);
        var save=new StageMeasurements.StageSample("r","req",1L,"save",30,50,"success","",1);
        assertEquals(30,StageMeasurements.postprocessNanos(List.of(ai,save)).get("req"));
    }
}
