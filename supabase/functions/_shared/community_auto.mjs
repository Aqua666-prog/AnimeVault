// Shared, dependency-free engine: Deno Edge runtime + Node fixture tests.
const PROJECT='https://kgvkxocbkklisqrqmcjx.supabase.co';
const HOSTS=new Set(['graphql.anilist.co','shikimori.io','api.myanimelist.net','kodik-api.com','kodik-add.com','animetka.com','api.yani.tv','aniliberty.top']);
export class SafeFailure extends Error {
  constructor(code,source='verification',stage='identity',status=null,retry=false){super(code);this.code=code;this.source=source;this.stage=stage;this.status=status;this.retry=retry;}
}
const need=(ok,code)=>{if(!ok)throw new SafeFailure(code);};
const id=v=> typeof v!=='boolean' && /^[1-9][0-9]{0,9}$/.test(String(v)) && Number(v)<=2147483647 ? Number(v):null;
function ids(row,...keys){need(row && typeof row==='object' && !Array.isArray(row),'provider_shape');const vs=keys.map(k=>row[k]).filter(v=>v!==null&&v!==undefined&&v!=='');need(vs.every(v=>id(v)),'invalid_provider_id');const set=new Set(vs.map(id));need(set.size<=1,'provider_id_conflict');return [...set][0]??null;}
export function identity(value){
  need(value && typeof value==='object'&&!Array.isArray(value),'invalid_identity');
  need(Object.keys(value).every(k=>['p_provider','p_external','p_anilist','p_mal','p_episode_external'].includes(k)),'invalid_fields');
  const p=value.p_provider,e=value.p_external;
  need(['kodik','animetka','yummy','aniliberty'].includes(p) && typeof e==='string' && e.length<=100,'unsupported_provider');
  need(p==='kodik'? /^shiki:[1-9][0-9]{0,9}$/.test(e): /^[1-9][0-9]{0,9}$/.test(e),'unsupported_external');
  need(value.p_episode_external==null,'episode_mapping_required');
  for(const k of ['p_anilist','p_mal'])need(value[k]==null || typeof value[k]==='number' && id(value[k])===value[k],'invalid_metadata');
  return {p_provider:p,p_external:e,p_anilist:value.p_anilist??null,p_mal:value.p_mal??null,p_episode_external:null};
}
export async function readLimited(stream,limit){
  const reader=stream?.getReader();let length=0;const chunks=[];
  if(reader){while(true){const {done,value}=await reader.read();if(done)break;length+=value.length;if(length>limit){await reader.cancel();throw new SafeFailure('response_too_large');}chunks.push(value);}}
  const bytes=new Uint8Array(length);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}return new TextDecoder().decode(bytes);
}
export function projectUrl(env){const url=env.SUPABASE_URL||PROJECT;need(/^https:\/\/[a-z0-9]{20}\.supabase\.co$/.test(url),'invalid_project_configuration');return url;}
export function transport(fetcher=fetch,clock=Date.now,backend=PROJECT){
  const deadline=clock()+20000;let calls=0;
  /** @param {string} url @param {any} body @param {Record<string,string>} headers */
  async function send(url,body=null,headers={},source='supabase',stage='rpc'){
    const u=new URL(url);need(u.protocol==='https:'&&!u.username&&!u.password&&(u.origin===backend||HOSTS.has(u.hostname)&&!u.port),'host_refused');
    if(source==='kodik_public')need(u.hostname==='kodik-add.com'&&u.pathname==='/add-players.min.js','host_refused');
    if(++calls>16 || clock()>=deadline)throw new SafeFailure('request_budget',source,stage,null,true);
    let response;
    try{response=await fetcher(url,{method:body===null?'GET':'POST',headers:{Accept:'application/json',...(body===null?{}:{'Content-Type':source==='kodik'?'application/x-www-form-urlencoded':'application/json'}),...headers},body:body===null?undefined:source==='kodik'?new URLSearchParams(body).toString():JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(Math.max(1,Math.min(6000,deadline-clock())))});}
    catch{throw new SafeFailure('network_unavailable',source,stage,null,true);}
    if(!response.ok){
      try{await response.body?.cancel();}catch{/* no raw upstream error is exposed */}throw new SafeFailure('http_'+response.status,source,stage,response.status,[429,500,502,503,504].includes(response.status));
    }
    let text;
    try{text=await readLimited(response.body,1024*1024);}
    catch(e){throw new SafeFailure(e instanceof SafeFailure?e.code:'network_unavailable',source,stage,response.status,!(e instanceof SafeFailure));}
    if(source==='kodik_public')return text;
    try{const result=JSON.parse(text);if(result?.errors||result?.error)throw Error();return result;}
    catch{throw new SafeFailure('invalid_response',source,stage,response.status);}

  }
  return send;
}
function unwrap(result){for(let i=0;i<3;i++){if(result && !Array.isArray(result) && typeof result==='object'){const next=['response','data','anime'].map(k=>result[k]).find(v=>v&&typeof v==='object');if(!next)return result;result=next;}else break;}need(result&&!Array.isArray(result)&&typeof result==='object','provider_shape');return result;}
function reference(row){return row.shikimori_id? 'shiki:'+row.shikimori_id:row.kinopoisk_id?'kp:'+row.kinopoisk_id:'kodik:'+row.id;}
async function publishedKodikToken(request){
  const script=await request('https://kodik-add.com/add-players.min.js?v=2',null,{Referer:'https://kodik.info/'},'kodik_public','credential');
  need(typeof script==='string','kodik_public_token_unavailable');
  const token=script.match(/token\s*[=:]\s*["']([0-9a-f]{32,128})["']/i)?.[1];
  if(!token)throw new SafeFailure('kodik_public_token_unavailable','kodik_public','credential',null,true);
  return token;
}
export async function verify(i,env,request,bridge=null){
  need(env.MAL_CLIENT_ID,'mal_verification_not_configured');
  const get=(url,headers={},source=i.p_provider,stage='primary')=>request(url,null,headers,source,stage);
  let mal=null,aid=null,shiki=null,observed={},method='direct_id';
  if(i.p_provider==='kodik'){
    shiki=id(i.p_external.slice(6));need(shiki,'invalid_provider_id');
    // Match the Android adapter: use an explicitly configured token when present;
    // otherwise obtain Kodik's published player token from its fixed first-party script.
    // This is a bounded GET with no redirects, not an arbitrary URL or client-supplied token.
    let kodikToken=env.KODIK_API_TOKEN || await publishedKodikToken(request);
    const search=token=>request('https://kodik-api.com/search?'+new URLSearchParams({
      token,shikimori_id:String(shiki),limit:'50',types:'anime,anime-serial',
    }),null, {},'kodik','primary');
    let result;
    try{result=await search(kodikToken);}
    catch(error){
      // A configured token may be stale. Mirror the Android token fallback once;
      // never retry rate limits, network failures or mismatched identity.
      if(!env.KODIK_API_TOKEN || !(error instanceof SafeFailure) ||
         (error.status!==401 && error.code!=='invalid_response'))throw error;
      kodikToken=await publishedKodikToken(request);
      result=await search(kodikToken);
    }
    const rows=result?.results;need(Array.isArray(rows)&&rows.length>0&&rows.length<=50,'kodik_not_found');
    // Kodik returns one row per translation; tolerate unrelated/incomplete rows, never use them as proof.
    const exact=rows.filter(r=>r&&id(r.shikimori_id)===shiki&&reference(r)===i.p_external&&typeof r.id==='string'&&r.id.length>0);
    need(exact.length>0,'kodik_id_conflict');
    observed={id:exact[0].id,shikimori_id:exact[0].shikimori_id,kinopoisk_id:exact[0].kinopoisk_id??null};method='external_link_chain';
  }else if(i.p_provider==='animetka'){
    const d=unwrap(await get('https://animetka.com/api/anime/'+i.p_external,{Referer:'https://animetka.com/'}));
    need(String(ids(d,'id','material_id','animetka_id'))===i.p_external,'provider_id_conflict');
    mal=ids(d,'mal_id','myanimelist_id','malId');aid=ids(d,'anilist_id','anilistId');shiki=ids(d,'shikimori_id','shikimoriId');observed={id:i.p_external};
  }else if(i.p_provider==='yummy'){
    need(env.YUMMY_APPLICATION_KEY,'yummy_not_configured');
    const result=await get('https://api.yani.tv/anime/'+i.p_external,{'X-Application':env.YUMMY_APPLICATION_KEY,Lang:'ru',Referer:'https://yummyani.me/'});
    const d=result?.response;need(d&&String(d.anime_id)===i.p_external,'provider_id_conflict');
    mal=ids(d.remote_ids??{},'myanimelist_id');shiki=ids(d.remote_ids??{},'shikimori_id');observed={id:i.p_external};
  }else{
    // No invented reverse endpoint or title search. A server-imported bridge is required.
    need(bridge?.method==='animetka_anilibria_mapping'&&id(bridge.animetka_id)&&String(bridge.release_id)===i.p_external,'aniliberty_bridge_required');
    const d=unwrap(await get('https://animetka.com/api/anime/'+bridge.animetka_id,{Referer:'https://animetka.com/'},'animetka','bridge'));
    need(String(ids(d,'id','material_id','animetka_id'))===String(bridge.animetka_id),'bridge_id_conflict');
    mal=ids(d,'mal_id','myanimelist_id','malId');aid=ids(d,'anilist_id','anilistId');shiki=ids(d,'shikimori_id','shikimoriId');
    const mapping=d.AnilibriaMapping,episodes=mapping?.episodes;
    need(String(mapping?.animetka_id)===String(bridge.animetka_id)&&Array.isArray(episodes)&&episodes.length>0&&episodes.every(e=>e&&String(e.release_id)===i.p_external&&typeof e.id==='string'),'bridge_unconfirmed');
    const release=await get('https://aniliberty.top/api/v1/anime/releases/'+i.p_external,{},'aniliberty');
    need(String(release?.id)===i.p_external&&Array.isArray(release.episodes)&&release.episodes.length>0,'bridge_unconfirmed');
    const uuids=new Set(episodes.map(e=>e.id));need(release.episodes.every(e=>e&&typeof e.id==='string'&&uuids.has(e.id)),'bridge_id_conflict');observed={id:i.p_external};method='external_link_chain';
  }
  let chainUrl=null;
  if(shiki){
    // Shikimori's numeric anime IDs are MAL anime IDs. External-links is not a
    // reliable MAL mapping endpoint: it may contain no MAL link at all.
    // The relationship is independently validated below against official MAL
    // and two reciprocal AniList lookups; titles are never used for matching.
    need(!mal||mal===shiki,'mal_id_conflict');
    mal=shiki;
    chainUrl='https://myanimelist.net/anime/'+mal;
    method='external_link_chain';
  }
  need(mal||aid,'provider_ids_missing');
  const query='query($id:Int,$mal:Int){Media(id:$id,idMal:$mal,type:ANIME){id idMal title{romaji english native}}}';
  const media=(await request('https://graphql.anilist.co',{query,variables:aid?{id:aid}:{mal}}, {},'anilist','identity'))?.data?.Media;
  need(media&&id(media.id)&&id(media.idMal)&&(!aid||media.id===aid)&&(!mal||media.idMal===mal),'anilist_id_conflict');aid=media.id;mal=media.idMal;
  // Independent official MAL API, not an inferred equality or title comparison.
  const official=await get('https://api.myanimelist.net/v2/anime/'+mal+'?fields=id',{'X-MAL-CLIENT-ID':env.MAL_CLIENT_ID},'mal','identity');need(official?.id===mal,'mal_id_conflict');
  const reciprocal=(await request('https://graphql.anilist.co',{query,variables:{mal}}, {},'anilist','reciprocal'))?.data?.Media;
  need(reciprocal?.id===aid&&reciprocal?.idMal===mal,'anilist_id_conflict');
  need((i.p_anilist===null||i.p_anilist===aid)&&(i.p_mal===null||i.p_mal===mal),'client_metadata_conflict');
  const title=['romaji','english','native'].map(k=>media.title?.[k]).find(v=>typeof v==='string'&&v.length>0&&v.length<=300);need(title,'canonical_title_missing');
  const client={...i};
  return [{kind:'anime',anilist_id:aid,mal_id:mal,title},{kind:'link',provider:i.p_provider,external_id:i.p_external,anilist_id:aid,status:'verified',evidence:{source:i.p_provider,method,mal_id:mal,anilist_id:aid,shikimori_id:shiki,url:chainUrl,client_contract:'android-release-id-v1',client_identity:client,observed_identity:observed,...(bridge?{provenance:bridge}:{})}}];
}
export async function provision(raw,env,request,clientHash,background=false){
  const i=identity(raw),backend=projectUrl(env);
  const rpc=(name,body,admin=true)=>request(backend+'/rest/v1/rpc/'+name,body,admin?{apikey:env.SUPABASE_SERVICE_ROLE_KEY,Authorization:'Bearer '+env.SUPABASE_SERVICE_ROLE_KEY}:{apikey:env.SUPABASE_ANON_KEY},'supabase',name);
  const found=await rpc('community_resolve',i,false);if(found?.anime_id)return {status:'resolved',context:found,existing:true};
  if(env.COMMUNITY_AUTO_WRITE_ENABLED!=='true')return {status:'disabled',context:null};
  // Do not reserve a failed job for six hours when an operator simply needs
  // to configure a server-only secret. Existing aliases remain readable above.
  if(!env.MAL_CLIENT_ID)return {status:'unavailable',context:null,source:'mal',reason:'mal_verification_not_configured'};
  if(i.p_provider==='yummy'&&!env.YUMMY_APPLICATION_KEY)return {status:'unavailable',context:null,source:'yummy',reason:'yummy_not_configured'};
  const claim=await rpc('community_auto_claim',{p_identity:i,p_client_hash:clientHash,p_background:background});
  if(claim?.status!=='claimed'){
    // The RPC returns bounded diagnostics for an already queued or failed job.
    // Surface only server-owned safe codes, never arbitrary upstream body or tokens.
    const status=['pending','unverified','disabled','rate_limited'].includes(claim?.status)?claim.status:'unavailable';
    const reason=typeof claim?.reason==='string'&&/^[a-z0-9_]{1,80}$/.test(claim.reason)?claim.reason:null;
    const seconds=Number.isInteger(claim?.retry_after_seconds)&&claim.retry_after_seconds>=1&&claim.retry_after_seconds<=21600?claim.retry_after_seconds:null;
    return {status,context:null,...(reason?{reason}:{}),...(seconds?{retry_after_seconds:seconds}:{})};
  }
  try{
    const items=await verify(i,env,request,claim.bridge);
    const completed=await rpc('community_auto_complete',{p_key:claim.key,p_lease:claim.lease,p_items:items});
    const context=await rpc('community_resolve',i,false);need(completed?.anime_id&&context?.anime_id===completed.anime_id&&context.episode_id==null,'post_resolve_failed');
    return {status:'resolved',context,existing:false};
  }catch(error){
    const failure=error instanceof SafeFailure?error:new SafeFailure('verification_failed');
    try{await rpc('community_auto_finish',{p_key:claim.key,p_lease:claim.lease,p_code:failure.code,p_retry:failure.retry});}catch{/* Lease expiry/disabled does not permit an alternate write path. */}
    return {status:failure.retry?'pending':'unverified',context:null,reason:failure.code,source:failure.source,stage:failure.stage,http_status:failure.status};
  }
}
export async function clientHash(value,secret){const e=new TextEncoder();const key=await crypto.subtle.importKey('raw',e.encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign']);return [...new Uint8Array(await crypto.subtle.sign('HMAC',key,e.encode(value)))].map(n=>n.toString(16).padStart(2,'0')).join('');}
