import React, {useEffect} from 'react';
import {createRoot} from 'react-dom/client';
import {QueryClientProvider} from '@tanstack/react-query';
import {createQueryClient} from '@/shared/api/queryClient';
import {apiClient} from '@/shared/api/browser/apiClient';
import {useStoryGeneration} from '@/features/createStory/model/useStoryGeneration';
import {updateStoryCreateDraft} from '@/features/createStory/model/storyCreateDraft';
import {StoryGenerationView} from '@/_pages/storyCreate/ui/storyGenerationView';
import '@/_app/styles/globals.css';

const id=new URLSearchParams(location.search).get('case')||'normal-1';
const joined=await fetch('/__join?case='+id).then(r=>r.json());
document.querySelector('#case-label')!.textContent='실제 FE 컴포넌트 격리 시험 · '+id;
if(joined.loads===1)updateStoryCreateDraft(10001,{generationId:1,startedAt:Date.now()});
const report=(data:any)=>fetch('/__observe',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,...data})});
apiClient.interceptors.response.use(r=>{void report({type:'received',status:r.data?.data?.status,epochMs:performance.timeOrigin+performance.now()});return r;});
document.addEventListener('visibilitychange',()=>{void report({type:'visibility',state:document.visibilityState,epochMs:Date.now()});});
void report({type:'visibility',state:document.visibilityState,epochMs:Date.now()});
let last='';
const observer=new MutationObserver(()=>{
 const node=document.querySelector('[role="status"]');if(!node)return;
 const label=node.getAttribute('aria-label');
 const status=label==='스토리 생성 완료'?'COMPLETED':label==='스토리 생성 실패'?'FAILED':document.querySelector('#root')!.textContent!.includes('회원님의 사진을 살펴보고 있어요')?'PROCESSING':'QUEUED';
 if(status===last)return;last=status;
 const epochMs=performance.timeOrigin+performance.now();
 requestAnimationFrame(()=>requestAnimationFrame(()=>{void report({type:'dom',status,label,epochMs,nextFrameEpochMs:performance.timeOrigin+performance.now()});}));
});
observer.observe(document.querySelector('#root')!,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['aria-label']});
function Pilot(){
 const generation=useStoryGeneration(10001);
 useEffect(()=>{void report({type:'hook',status:generation.status,epochMs:performance.timeOrigin+performance.now(),finiteProgress:Number.isFinite(generation.progressPercent)});},[generation.status,generation.progressPercent]);
 if(generation.status==='idle')return <p>저장된 생성 작업 없음</p>;
 return <main style={{maxWidth:430,minHeight:700,margin:'auto'}}><StoryGenerationView nickname="로컬 시험" status={generation.status} elapsed={generation.elapsed} progressPercent={generation.progressPercent} message={generation.message} onContinueElsewhere={()=>{}} onBack={()=>{}} onView={()=>{}} onRetry={()=>{}}/></main>;
}
createRoot(document.querySelector('#root')!).render(<QueryClientProvider client={createQueryClient()}><Pilot/></QueryClientProvider>);
if(id==='reload'&&joined.loads===1)setTimeout(()=>location.reload(),6000);
const remaining=joined.loads>1?Math.max(3,joined.duration-6):joined.duration;
setTimeout(async()=>{
 await fetch('/__finish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id})});
 if(joined.next)location.assign('/?case='+joined.next);
 else document.querySelector('#summary')!.textContent='추가 검증 종료. /__results 에 원자료 저장됨';
},remaining*1000);
