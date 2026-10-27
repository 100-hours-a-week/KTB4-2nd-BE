import http from 'k6/http';
import {check,sleep} from 'k6';
import {SharedArray} from 'k6/data';
import {Trend,Counter,Rate} from 'k6/metrics';
const auth=new SharedArray('auth',()=>JSON.parse(open(__ENV.AUTH_FILE)));
const start=Number(__ENV.START_MS), warm=Number(__ENV.WARM_SECONDS||30), measure=Number(__ENV.MEASURE_SECONDS||120), kind=__ENV.KIND;
const measuredLatency=new Trend('poll_latency_ms',true), measuredCount=new Counter('poll_requests'), measuredErrors=new Rate('poll_errors'), noProgress=new Rate('progress_field_absent'), bodyBytes=new Counter('poll_body_bytes');
const mapLatency=new Trend('map_probe_latency_ms',true),mapErrors=new Rate('map_probe_errors');
let initialized=false,finished=false,last='',phase=0;
export const options={scenarios:__ENV.PROBE_ONLY==='1'?{map_probe:{executor:'constant-arrival-rate',exec:'map_probe',rate:5,timeUnit:'1s',duration:`${warm+measure+5}s`,preAllocatedVUs:2,maxVUs:10}}:{polling:{executor:'constant-vus',vus:Number(__ENV.VUS),duration:`${warm+measure+5}s`}},gracefulStop:'5s',discardResponseBodies:false,summaryTrendStats:['avg','med','p(95)','p(99)','max'],tags:{run_id:__ENV.RUN_ID,kind:kind},thresholds:__ENV.PROBE_ONLY==='1'?{map_probe_errors:['rate==0']}:{'poll_errors':['rate==0'],'progress_field_absent':['rate==1']}};
export default function(){
 if(!initialized){const initial=start-Date.now()+((__VU-1)%Number(__ENV.VUS))/Number(__ENV.VUS)*2000;if(initial>0)sleep(initial/1000);initialized=true;}
 if(finished){sleep(1);return;}
 const u=auth[__VU-1],base=`http://127.0.0.1:${__VU%2===0?18082:18081}`,id=kind==='story'?u.index:(kind==='creation'?20000:30000)+u.index;
 const path=kind==='story'?`/api/story-generation/${id}`:`/api/trips/${id}/processing-status`;
 const r=http.get(base+path,{headers:{Cookie:`accessToken=${u.token}`},tags:{name:kind==='story'?'/api/story-generation/{id}':'/api/trips/{id}/processing-status'},timeout:'10s'});
 let d=null;try{d=r.json('data')}catch(_){}
 const ok=r.status===200&&d&&d.status;
 const elapsed=(Date.now()-start)/1000;
 if(elapsed>=warm&&elapsed<warm+measure){measuredCount.add(1);measuredLatency.add(r.timings.duration);measuredErrors.add(!ok);noProgress.add(Boolean(d)&&!Object.prototype.hasOwnProperty.call(d,'progress'));bodyBytes.add((r.body||'').length);}
 check(r,{'200 and task state':()=>Boolean(ok),'progress omitted':()=>Boolean(d)&&!Object.prototype.hasOwnProperty.call(d,'progress')});
 if(ok){const signature=d.status+'|'+(d.currentStep||'');if(signature!==last){console.log(JSON.stringify({event:'observed',userId:u.userId,status:d.status,step:d.currentStep||null,epochMs:Date.now(),elapsed:elapsed}));last=signature;}
 if(['COMPLETED','FAILED','CANCELED'].includes(d.status))finished=true;}
 sleep(2);
}
export function handleSummary(data){return {[__ENV.SUMMARY_FILE]:JSON.stringify(data,null,2)};}

export function map_probe(){
 const u=auth[0],r=http.get('http://127.0.0.1:'+(__ITER%2===0?18081:18082)+'/api/trips/map',{headers:{Cookie:`accessToken=${u.token}`},tags:{name:'/api/trips/map'},timeout:'10s'});
 const elapsed=(Date.now()-start)/1000;
 if(elapsed>=warm&&elapsed<warm+measure){mapLatency.add(r.timings.duration);mapErrors.add(r.status!==200);}
 check(r,{'map probe 200':()=>r.status===200});
}
