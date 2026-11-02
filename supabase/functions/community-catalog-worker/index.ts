import { provision, transport, clientHash, projectUrl, SafeFailure } from '../_shared/community_auto.mjs';
const env=Deno.env.toObject();
Deno.serve(async (req: Request) => {
  if(req.method!=='POST'||!env.COMMUNITY_WORKER_SECRET||req.headers.get('Authorization')!=='Bearer '+env.COMMUNITY_WORKER_SECRET)return new Response(null,{status:401});
  if(env.COMMUNITY_AUTO_WRITE_ENABLED!=='true')return Response.json({status:'disabled'});
  try{
    const project=projectUrl(env);
    const rows=await transport(fetch,Date.now,project)(project+'/rest/v1/rpc/community_auto_pending',{}, {apikey:env.SUPABASE_SERVICE_ROLE_KEY,Authorization:'Bearer '+env.SUPABASE_SERVICE_ROLE_KEY});
    if(!Array.isArray(rows)||rows.length>5)return Response.json({status:'blocked'});
    const results=[];const hash=await clientHash('background',env.SUPABASE_SERVICE_ROLE_KEY);
    for(const identity of rows)results.push(await provision(identity,env,transport(fetch,Date.now,project),hash,true));
    const counts=results.reduce((r,v)=>({...r,[v.status]:(r[v.status]||0)+1}),{} as Record<string,number>);
    console.log(JSON.stringify({event:'community_worker',counts}));
    return Response.json({status:'completed',counts});
  }catch(error){console.log(JSON.stringify({event:'community_worker',status:'unavailable',reason:error instanceof SafeFailure?error.code:'worker_failed'}));return Response.json({status:'unavailable'});}
});
