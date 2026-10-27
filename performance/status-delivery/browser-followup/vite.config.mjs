import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import tailwind from '@tailwindcss/postcss';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import process from 'node:process';

const root=import.meta.dirname;
const fe=process.env.FE_ROOT;
if(!fe)throw Error('Set FE_ROOT to the local frontend repository');
const definitions=[['normal-1',15],['normal-2',15],['normal-3',15],['http-503',22],['reload',17]];
const cases={};
const persist=()=>fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify({definitions,cases},null,2));
const bridge=async(action,body={})=>{
 const r=await fetch('http://127.0.0.1:19099/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
 if(!r.ok)throw Error('Bridge '+r.status);return r.json();
};
const json=(res,obj)=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(obj));};
export default defineConfig({
 root,plugins:[react(),{name:'polling-observation',configureServer(server){server.middlewares.use(async(req,res,next)=>{
  const url=new URL(req.url,'http://127.0.0.1:5179');
  try {
   if(url.pathname==='/__join'){
    const id=url.searchParams.get('case');const i=definitions.findIndex(d=>d[0]===id);if(i<0){res.statusCode=400;res.end();return;}
    if(!cases[id]){
     const before=await bridge('reset');const start=Date.now();
     const c=cases[id]={id,startEpochMs:start,before,requests:[],observations:[],markers:[],visibility:[],loads:0};
     for(const [seconds,phase] of [[4,'PROCESSING'],[10,'COMPLETED']])setTimeout(async()=>{try{c.markers.push(await bridge('phase',{phase}));persist();}catch(e){c.error=String(e);persist();}},seconds*1000);
    }
    cases[id].loads++;persist();json(res,{id,loads:cases[id].loads,duration:definitions[i][1],next:definitions[i+1]?.[0]??null});return;
   }
   if(url.pathname==='/__observe'||url.pathname==='/__finish'){
    let bytes='';for await(const b of req)bytes+=b;const b=JSON.parse(bytes);const c=cases[b.id];
    if(url.pathname==='/__finish'){c.after=await bridge('stats');c.finishedEpochMs=Date.now();}
    else if(b.type==='visibility')c.visibility.push(b);
    else c.observations.push(b);
    persist();json(res,{ok:true});return;
   }
   if(url.pathname==='/__results'){json(res,{definitions,cases});return;}
   if(url.pathname.startsWith('/api/')){
    const c=Object.values(cases).at(-1);const request={startEpochMs:Date.now(),path:url.pathname};c.requests.push(request);
    if(c.id==='http-503'&&Date.now()-c.startEpochMs>=6000&&Date.now()-c.startEpochMs<13000){request.status=503;request.endEpochMs=Date.now();res.statusCode=503;json(res,{message:'TEST_GATEWAY_UNAVAILABLE',data:null});persist();return;}
    const token=JSON.parse(fs.readFileSync(path.join(root,'../auth.local.json')))[0].token;
    const upstream=http.get('http://127.0.0.1:18081'+req.url,{headers:{Cookie:'accessToken='+token}},r=>{
     res.statusCode=r.statusCode;res.setHeader('Content-Type','application/json');r.pipe(res);
     r.on('end',()=>{request.endEpochMs=Date.now();request.status=r.statusCode;persist();});
    });
    upstream.on('error',e=>{request.error=String(e);res.statusCode=502;res.end();persist();});return;
   }
   next();
  }catch(e){res.statusCode=500;json(res,{error:String(e)});}
 });}}],
 resolve:{alias:[{find:'@',replacement:fe+'/src'},{find:'next/link',replacement:root+'/link.tsx'}],dedupe:['react','react-dom']},
 css:{postcss:{plugins:[tailwind()]}},
 server:{host:'127.0.0.1',port:5179,strictPort:true,hmr:false,watch:{ignored:['**/browser-results.json','**/excluded-*.json','**/environment.json','**/performance/**','**/evidence/**']},fs:{allow:[root,fe]}}
});
