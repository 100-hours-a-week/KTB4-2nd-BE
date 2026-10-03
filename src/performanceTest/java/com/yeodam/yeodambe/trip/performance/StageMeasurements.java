package com.yeodam.yeodambe.trip.performance;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.MDC;
import org.springframework.aop.framework.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class StageMeasurements implements BeanPostProcessor, Ordered {
    record StageSample(String runId,String requestId,Long tripId,String stage,long startNanos,long endNanos,String outcome,String errorType,long count) {}
    private final Queue<StageSample> samples=new ConcurrentLinkedQueue<>();
    private final Map<String,Long> trips=new ConcurrentHashMap<>();
    private final Map<String,HeadTotals> heads=new ConcurrentHashMap<>();
    private static final class HeadTotals { StageSample first; long sum,max,count; synchronized void add(StageSample s){if(first==null)first=s;sum+=s.endNanos()-s.startNanos();max=Math.max(max,s.endNanos()-s.startNanos());count++;} }
    private final java.util.concurrent.atomic.AtomicInteger activeUploads=new java.util.concurrent.atomic.AtomicInteger();
    int activeUploads() {return activeUploads.get();}
    boolean isActive() {return runId!=null;}
    private volatile String runId;
    private volatile Consumer<StageSample> observer=s -> {};
    @Override public int getOrder() {return Ordered.LOWEST_PRECEDENCE;}
    void beginRun(String id) {samples.clear();trips.clear();heads.clear();runId=id;}
    void endRun() {runId=null;}
    void onSample(Consumer<StageSample> listener) {observer=listener;}
    List<StageSample> drain() {List<StageSample> result=new ArrayList<>();StageSample s;while((s=samples.poll())!=null)result.add(s);
        heads.forEach((key,h)->{synchronized(h){var f=h.first;result.add(new StageSample(f.runId(),f.requestId(),f.tripId(),"head",0,h.sum,f.outcome(),f.errorType(),h.count));result.add(new StageSample(f.runId(),f.requestId(),f.tripId(),"head_max",0,h.max,f.outcome(),f.errorType(),0));}});heads.clear();return result;}
    @Override public Object postProcessAfterInitialization(Object bean,String name) {
        String stage=switch(name) {
            case "tripAttachmentService" -> "upload";
            case "tripPhotoAnalysisService" -> "ai";
            case "tripPlaceNameService" -> "resolve";
            case "userStatsService" -> "stats";
            case "s3TripAttachmentStorageClient" -> "head";
            default -> null;
        };
        if (name.equals("s3TripAttachmentStorageClient")) bean=wrap(bean,"storage_retain");
        return stage==null?bean:wrap(bean,stage);
    }
    // Installed after singleton construction, when the transaction proxy is certain to exist.
    void instrumentSave(Object bean) {
        if(!(bean instanceof Advised a) || Arrays.stream(a.getAdvisors()).noneMatch(ad -> ad.getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor))
            throw new IllegalStateException("Result service must retain its transaction advisor");
        a.addAdvice(0,interceptor("save"));
    }
    void instrumentAttachmentSave(Object bean) {
        if (!(bean instanceof Advised a) || Arrays.stream(a.getAdvisors()).noneMatch(ad -> ad.getAdvice() instanceof org.springframework.transaction.interceptor.TransactionInterceptor))
            throw new IllegalStateException("Attachment service must retain its transaction advisor");
        a.addAdvice(0,interceptor("attachment_save"));
    }
    Object wrap(Object bean,String stage) {
        if(bean instanceof Advised a) {a.addAdvice(0,interceptor(stage));return bean;}
        var factory=new ProxyFactory(bean);factory.setProxyTargetClass(true);factory.addAdvice(interceptor(stage));return factory.getProxy();
    }
    private MethodInterceptor interceptor(String stage) {
        return invocation -> {
            String method=invocation.getMethod().getName();
            if(stage.equals("upload") && method.equals("uploadInitialAttachments")) {activeUploads.incrementAndGet();try{return invocation.proceed();}finally{activeUploads.decrementAndGet();}}
            boolean target=switch(stage) {
                case "ai" -> method.equals("analyze");case "resolve" -> method.equals("resolve");
                case "save" -> method.equals("saveCompleted");case "stats" -> method.equals("refreshFromActiveTrips");
                case "attachment_save" -> method.equals("saveFilesAndAttachments");
                case "storage_retain" -> method.equals("retain");
                case "head" -> method.equals("size");default -> true;
            };
            String run=runId;
            if(!target || run==null)return invocation.proceed();
            String request=Objects.requireNonNullElse(MDC.get("request_id"),"unassociated");
            if(Set.of("ai","resolve","save").contains(stage))trips.put(request,(Long)invocation.getArguments()[0]);
            Long trip=trips.get(request);long start=System.nanoTime();String outcome="success",error="";
            try {return invocation.proceed();}
            catch(Throwable e) {outcome="failure";error=e.getClass().getSimpleName();throw e;}
            finally {
                var sample=new StageSample(run,request,trip,stage,start,System.nanoTime(),outcome,error,1);
                if(stage.equals("head"))heads.computeIfAbsent(request+":"+outcome,k->new HeadTotals()).add(sample);else samples.add(sample);observer.accept(sample);
            }
        };
    }
    static Map<String,Long> postprocessNanos(List<StageSample> values) {
        Map<String,Long> ai=new HashMap<>(),result=new HashMap<>();
        values.stream().filter(s->s.stage().equals("ai")&&s.outcome().equals("success")).forEach(s->ai.put(s.requestId(),s.endNanos()));
        values.stream().filter(s->s.stage().equals("save")&&s.outcome().equals("success")).forEach(s->{Long start=ai.get(s.requestId());if(start!=null)result.put(s.requestId(),s.endNanos()-start);});
        return result;
    }
}
