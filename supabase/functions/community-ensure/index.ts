import { provision, transport, clientHash, SafeFailure, readLimited, projectUrl } from '../_shared/community_auto.mjs';
const env=Deno.env.toObject();
Deno.serve(async (req: Request) => {
  if(req.method!=='POST')return new Response(null,{status:405});
  // Publishable key permits guest reads, never confers write authority.
  if(!env.COMMUNITY_PUBLIC_KEY || req.headers.get('apikey')!==env.COMMUNITY_PUBLIC_KEY)return new Response(null,{status:401});
  try{
    if(Number(req.headers.get('content-length')||0)>2048)return new Response(null,{status:413});
    const raw=await readLimited(req.body,2048);
    const hash=await clientHash(req.headers.get('x-forwarded-for')?.split(',')[0]?.trim()||'unknown',env.SUPABASE_SERVICE_ROLE_KEY);
    const result=await provision(JSON.parse(raw),env,transport(fetch,Date.now,projectUrl(env)),hash);
    console.log(JSON.stringify({event:'community_ensure',status:result.status,source:result.source??null,reason:result.reason??null}));
    return Response.json(result,{headers:{'Cache-Control':'no-store'}});
  }catch(error){
    console.log(JSON.stringify({event:'community_ensure',status:'unavailable',reason:error instanceof SafeFailure?error.code:'invalid_request'}));
    return Response.json({status:'unavailable',reason:error instanceof SafeFailure?error.code:'invalid_request'},{status:200,headers:{'Cache-Control':'no-store'}});
  }
});
