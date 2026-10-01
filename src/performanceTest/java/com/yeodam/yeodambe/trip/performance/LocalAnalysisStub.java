package com.yeodam.yeodambe.trip.performance;

import com.sun.net.httpserver.*;
import tools.jackson.databind.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local HTTP contracts only; never calls an external provider. */
final class LocalAnalysisStub implements AutoCloseable {
    enum FaultMode { NONE, FIRST_429, FIRST_500, TIMEOUT_TWICE, EMPTY_DOCUMENTS }
    record RunSpec(int placeCount, int uniqueCoordinates, FaultMode fault, long aiDelayMs,
                   long kakaoDelayMs, long slowDelayMs, boolean holdAnalysis) {
        RunSpec {
            if (uniqueCoordinates < 1 || uniqueCoordinates > placeCount || placeCount > 200
                || aiDelayMs < 0 || kakaoDelayMs < 0 || slowDelayMs < 0) throw new IllegalArgumentException("Invalid run spec");
        }
    }
    record Coordinate(BigDecimal latitude, BigDecimal longitude) {}
    record CallEvent(int coordinateIndex, int attempt, long startNanos, long endNanos, int status) {}
    record StubSnapshot(int attempts, int retries, int peakActive, List<CallEvent> events) {}
    private static final class Run {
        final RunSpec spec;
        final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        final ConcurrentMap<Integer, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<CallEvent> events = new CopyOnWriteArrayList<>();
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        volatile String state = "QUEUED";
        volatile JsonNode response;
        Run(RunSpec spec) { this.spec = spec; if (!spec.holdAnalysis()) release.countDown(); }
    }
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentMap<Long, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, long[]> coordinates = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer ai, kakao;
    LocalAnalysisStub() throws IOException {
        ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kakao = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.setExecutor(executor); kakao.setExecutor(executor);
        ai.createContext("/", this::analysis); kakao.createContext("/", this::lookup);
        ai.start(); kakao.start();
    }
    URI aiBaseUrl() { return URI.create("http://127.0.0.1:"+ai.getAddress().getPort()); }
    URI kakaoBaseUrl() { return URI.create("http://127.0.0.1:"+kakao.getAddress().getPort()); }
    void register(long tripId, RunSpec spec) {
        if (tripId < 1 || tripId > 100000000 || runs.putIfAbsent(tripId, new Run(spec)) != null)
            throw new IllegalArgumentException("Invalid or duplicate trip");
        for (int i=0; i<spec.uniqueCoordinates(); i++) {
            var c = coordinate(tripId, i);
            coordinates.put(key(c.longitude(), c.latitude()), new long[]{tripId,i});
        }
    }
    Coordinate coordinate(long tripId, int i) {
        return new Coordinate(new BigDecimal("33").add(BigDecimal.valueOf(tripId, 8)),
                              new BigDecimal("126").add(BigDecimal.valueOf(i, 6)));
    }
    boolean awaitAnalysisRequests(Set<Long> ids, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime()+timeout.toNanos();
        for (Long id: ids) {
            Run run = runs.get(id);
            if (run == null || !run.arrived.await(Math.max(0, deadline-System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
        }
        return true;
    }
    void releaseAnalysis(Set<Long> ids) { ids.forEach(id -> { Run r=runs.get(id); if(r!=null) r.release.countDown(); }); }
    StubSnapshot snapshot(long id) {
        Run r = Objects.requireNonNull(runs.get(id));
        return new StubSnapshot(r.attempts.values().stream().mapToInt(AtomicInteger::get).sum(),
            r.attempts.values().stream().mapToInt(a -> Math.max(0,a.get()-1)).sum(), r.peak.get(), List.copyOf(r.events));
    }
    void forget(long id) {
        Run r=runs.remove(id); if(r!=null) r.release.countDown();
        coordinates.entrySet().removeIf(e -> e.getValue()[0]==id);
    }
    private void analysis(HttpExchange x) throws IOException {
        try {
            if(x.getRequestURI().getPath().equals("/health")) { send(x,200,Map.of("status","ok","model_loaded",true)); return; }
            String[] path = x.getRequestURI().getPath().split("/");
            if(path.length!=4 || !path[1].equals("trips") || !path[3].equals("process")) {send(x,404,Map.of());return;}
            long id=Long.parseLong(path[2]); Run r=runs.get(id);
            if(r==null) {send(x,404,Map.of());return;}
            if(x.getRequestMethod().equals("DELETE")) {r.state="FAILED";r.release.countDown();send(x,200,Map.of());return;}
            if(x.getRequestMethod().equals("GET")) {
                Map<String,Object> status = new LinkedHashMap<>();
                status.put("trip_id",id); status.put("status",r.state);
                status.put("progress",r.state.equals("QUEUED")?null:Map.of("done",r.state.equals("COMPLETED")?1:0,"total",1));
                status.put("current_step",null); status.put("result",r.state.equals("COMPLETED")?r.response.path("result"):null);
                status.put("error",null); send(x,200,status); return;
            }
            if(!x.getRequestMethod().equals("POST")) {send(x,405,Map.of());return;}
            JsonNode request=json.readTree(x.getRequestBody());
            r.response=buildResponse(id,r.spec,request); r.state="PROCESSING";r.arrived.countDown();
            if(!r.release.await(600,TimeUnit.SECONDS)) {r.state="FAILED";send(x,504,Map.of());return;}
            Thread.sleep(r.spec.aiDelayMs()); r.state="COMPLETED"; send(x,200,r.response);
        } catch(InterruptedException e) {Thread.currentThread().interrupt();}
          catch(RuntimeException e) {send(x,400,Map.of("error",e.getClass().getSimpleName()));}
        finally {x.close();}
    }
    private JsonNode buildResponse(long id, RunSpec spec, JsonNode request) {
        JsonNode photos=request.path("attachments");
        if(!photos.isArray() || photos.size()<spec.placeCount()) throw new IllegalArgumentException("P exceeds N");
        List<Map<String,Object>> places=new ArrayList<>();
        for(int i=0;i<spec.placeCount();i++) {
            var c=coordinate(id,i%spec.uniqueCoordinates()); List<Map<String,Object>> attachments=new ArrayList<>();
            for(int n=i;n<photos.size();n+=spec.placeCount()) attachments.add(Map.of("trip_attachment_id",photos.get(n).path("trip_attachment_id").asLong(),
                "region_origin","INFERRED","evaluation",80,"latitude",c.latitude(),"longitude",c.longitude(),"taken_at","2026-09-01T12:00:00+09:00"));
            places.add(Map.of("place_id","p"+i,"latitude",c.latitude(),"longitude",c.longitude(),"first_taken_at","2026-09-01T12:00:00+09:00",
                "last_taken_at","2026-09-01T12:00:00+09:00","representative_attachment_id",photos.get(i).path("trip_attachment_id").asLong(),"attachments",attachments));
        }
        return json.valueToTree(Map.of("trip_id",id,"execution_id",request.path("execution_id").asString(),"status","COMPLETED",
            "result",Map.of("places",places,"unclassified",List.of(),"failed",List.of())));
    }
    private void lookup(HttpExchange x) throws IOException {
        Run r=null; int index=0, attempt=0, status=200; long started=System.nanoTime();
        try {
            if(!x.getRequestURI().getPath().equals("/v2/local/geo/coord2address.json")) {send(x,404,Map.of());return;}
            Map<String,String> q=new HashMap<>();
            for(String part:x.getRequestURI().getRawQuery().split("&")) {String[] kv=part.split("=",2);q.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
            long[] owner=coordinates.get(key(new BigDecimal(q.get("x")),new BigDecimal(q.get("y"))));
            if(owner==null) {send(x,404,Map.of());return;}
            r=runs.get(owner[0]);index=(int)owner[1];
            attempt=r.attempts.computeIfAbsent(index,k -> new AtomicInteger()).incrementAndGet();
            r.peak.accumulateAndGet(r.active.incrementAndGet(),Math::max);
            boolean fault=index < Math.max(1,r.spec.uniqueCoordinates()/10);
            long delay=index==0 && r.spec.slowDelayMs()>0 ? r.spec.slowDelayMs():r.spec.kakaoDelayMs();
            if(fault && r.spec.fault()==FaultMode.TIMEOUT_TWICE) delay=4000;
            Thread.sleep(delay);
            if(fault && attempt==1 && r.spec.fault()==FaultMode.FIRST_429) status=429;
            if(fault && attempt==1 && r.spec.fault()==FaultMode.FIRST_500) status=500;
            Object documents=fault && r.spec.fault()==FaultMode.EMPTY_DOCUMENTS ? List.of():
                List.of(Map.of("road_address",Map.of("building_name","synthetic-"+owner[0]+"-"+index),"address",Map.of("region_2depth_name","제주시")));
            send(x,status,Map.of("documents",documents));
        } catch(InterruptedException e) {Thread.currentThread().interrupt();}
          catch(IOException e) {status=499;} // A timed-out client can close before the response.
        finally {if(r!=null && attempt>0) {r.active.decrementAndGet();r.events.add(new CallEvent(index,attempt,started,System.nanoTime(),status));}x.close();}
    }
    private String key(BigDecimal x,BigDecimal y) {return x.stripTrailingZeros().toPlainString()+","+y.stripTrailingZeros().toPlainString();}
    private void send(HttpExchange x,int status,Object value) throws IOException {
        byte[] bytes=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json");
        x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
    }
    @Override public void close() {runs.values().forEach(r->r.release.countDown());ai.stop(0);kakao.stop(0);executor.shutdownNow();}
}
