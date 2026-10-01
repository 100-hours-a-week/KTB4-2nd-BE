package com.yeodam.yeodambe.trip.performance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
class LoadReportTest {
    @Test void nearestRankAndEmptySamples() {
        assertEquals(3,LoadReport.percentile(List.of(1L,2L,3L,4L,5L),.5));
        assertEquals(5,LoadReport.percentile(List.of(1L,2L,3L,4L,5L),.95));
        assertNull(LoadReport.percentile(List.of(),.95));
    }
    @Test void failuresAndDropsStayInDenominator() {
        var results=List.of(new LoadReport.JourneyResult(1,"a",10,200,"COMPLETED","success"),
            new LoadReport.JourneyResult(2,"b",20,500,"","failure"),new LoadReport.JourneyResult(3,"c",0,0,"","drop"));
        var summary=LoadReport.journeySummary(results);assertEquals(3,summary.get("attempted"));
        assertEquals(1L,summary.get("success"));assertEquals(true,summary.get("insufficient_samples"));
    }
}
