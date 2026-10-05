import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { check } from 'k6';
import { Gauge } from 'k6/metrics';
const fixtures = new SharedArray('capacity-fixtures', () => JSON.parse(open(__ENV.AUTH_FIXTURES)));
const base=__ENV.AUTH_BASE, rate=Number(__ENV.AUTH_RATE), vus=Number(__ENV.AUTH_VUS||1000);
const warmup=Number(__ENV.AUTH_WARMUP||0), duration=Number(__ENV.AUTH_DURATION||180);
const profile=__ENV.AUTH_PROFILE||'normal', forcedPhase=__ENV.AUTH_PHASE;
const burst=profile==='rtr-burst' && !forcedPhase;
const gap=__ENV.AUTH_VERIFY_RTR==='1'?1:20,pulse=__ENV.AUTH_VERIFY_RTR==='1'?1:10;
const spikeVus=Math.min(vus,100),totalOwners=burst?vus+3*spikeVus:vus;
if (!/^http:\/\/127\.0\.0\.1:\d+\/api$/.test(base) || rate<10 || rate%10 || !['normal','db','rtr-burst'].includes(profile)
    || vus<1 || vus>fixtures.length || (burst && totalOwners>Math.floor(fixtures.length/6))) throw new Error('Invalid local mixed load');
const windowStart=new Gauge('auth_window_start_ms'), fixtureIndex=new Gauge('auth_fixture_index');
const scenarios={mixed:{executor:'constant-arrival-rate',rate:rate/10,timeUnit:'1s',duration:`${warmup+duration}s`,preAllocatedVUs:vus,maxVUs:vus,gracefulStop:'10s'}};
if(burst){
  for(let i=0;i<3;i++)scenarios[`refresh-spike-${i+1}`]={executor:'constant-arrival-rate',exec:'refreshSpike',
    startTime:`${warmup+gap+i*(pulse+gap)}s`,rate:rate/5,timeUnit:'1s',duration:`${pulse}s`,
    preAllocatedVUs:spikeVus,maxVUs:spikeVus,gracefulStop:'10s'};
}
export const options={scenarios,noCookiesReset:true,systemTags:['status','method','name','scenario','expected_response']};
const jars=new Map();let cursor=0,readCursor=0;
function currentPhase(){
  if(forcedPhase)return forcedPhase;
  const spike=exec.scenario.name.startsWith('refresh-spike-');
  const pulseIndex=spike?Number(exec.scenario.name.slice(-1))-1:0;
  const start=exec.scenario.startTime+(spike?-gap-pulseIndex*(pulse+gap):warmup)*1000;
  const now=Date.now();
  const phase=now<start?'warmup':now<start+duration*1000?'measurement':'drain';
  if(phase==='measurement')windowStart.add(start);
  return phase;
}
function fixture(index,refresh){
  const f=fixtures[index];
  if(!jars.has(index)||!refresh){
    const jar=new http.CookieJar();
    for(const cookie of f.cookie.split('; ')){const equal=cookie.indexOf('=');jar.set(base,cookie.slice(0,equal),cookie.slice(equal+1),{path:'/'});}
    if(refresh)jar.set(base,'refreshToken',f.refresh_token,{path:'/api/auth'});
    if(refresh)jars.set(index,jar);
    return {f,index,jar};
  }
  return {f,index,jar:jars.get(index)};
}
function nextFixture(read=false){
  if(read && __ENV.AUTH_VERIFY_RTR!=='1')return fixture(readCursor++%fixtures.length,false);
  const spike=exec.scenario.name.startsWith('refresh-spike-');
  const pulseIndex=spike?Number(exec.scenario.name.slice(-1))-1:0;
  const partition=Math.floor(fixtures.length/6);
  const size=spike?(pulseIndex===2?fixtures.length/2-2*partition:partition):(burst?fixtures.length/2:fixtures.length);
  const baseIndex=spike?fixtures.length/2+pulseIndex*partition:0,owners=totalOwners;
  const owner=exec.vu.idInTest-1,count=Math.floor((size-1-owner)/owners)+1;
  return fixture(baseIndex+owner+(__ENV.AUTH_VERIFY_RTR==='1'?0:cursor++%count)*owners,true);
}
function request(method,path,name,expected,owned){
  const item=owned||nextFixture(['place','me','trips'].includes(name)),phase=currentPhase();
  const response=http.request(method,base+path(item.f),null,{jar:item.jar,timeout:'5s',headers:{'X-CSRF-TOKEN':item.f.csrf,'X-Auth-Perf-Phase':phase,'X-Auth-Perf-Scenario':'MIX','X-Auth-Perf-Endpoint':name},tags:{phase,name}});
  fixtureIndex.add(item.index,{phase,name});check(response,{[name]:expected},{phase,name});return item;
}
const data=r=>r.status===200&&r.json('data')!==undefined;
function refresh(){request('POST',()=>'/auth/token/refresh','refresh',r=>r.status===200&&r.cookies.refreshToken?.length>0&&r.cookies.accessToken?.length>0);}
export function refreshSpike(){refresh();}
export default function(){
  readCursor=exec.scenario.iterationInTest*7;
  const actions=[
    ()=>{for(let i=0;i<(profile==='db'?2:5);i++)request('GET',()=>'/places?query=%EC%84%9C%EC%9A%B8&pageNo=1','place',data);},
    ()=>{for(let i=0;i<2;i++)request('GET',()=>'/users/me','me',data);},
    ()=>{const item=request('POST',f=>`/trips/${f.trip_id}/favorite`,'favorite-add',r=>r.status===200&&r.json('data.isFavorite')===true);request('DELETE',f=>`/trips/${f.trip_id}/favorite`,'favorite-remove',r=>r.status===204,item);},refresh];
  if(profile==='db')actions.push(()=>{for(let i=0;i<3;i++)request('GET',()=>'/trips','trips',data);});
  const offset=exec.scenario.iterationInTest%actions.length;
  for(let i=0;i<actions.length;i++)actions[(offset+i)%actions.length]();
}
