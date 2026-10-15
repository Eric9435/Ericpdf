import http from "node:http";
import {timingSafeEqual} from "node:crypto";
const key=process.env.OPENAI_API_KEY;
const pass=process.env.ERICPDF_ACCESS_TOKEN;
if(!key||!pass||pass.length<24){console.error("OPENAI_API_KEY and 24+ character ERICPDF_ACCESS_TOKEN required");process.exit(1);}
const counts=new Map();
function json(res,status,data){res.writeHead(status,{"Content-Type":"application/json","Cache-Control":"no-store"});res.end(JSON.stringify(data));}
http.createServer(async(req,res)=>{
  if(req.url==="/health"&&req.method==="GET")return json(res,200,{ok:true});
  if(req.url!=="/session"||req.method!=="POST")return json(res,404,{error:"Not found"});
  const ip=req.socket.remoteAddress||"unknown",now=Date.now();
  const prior=counts.get(ip)||{since:now,count:0};
  const state=now-prior.since>60000?{since:now,count:0}:prior;
  state.count++;counts.set(ip,state);
  if(state.count>10)return json(res,429,{error:"Rate limited"});
  const provided=Buffer.from((req.headers.authorization||"").replace(/^Bearer /i,""));
  const expected=Buffer.from(pass);
  if(provided.length!==expected.length||!timingSafeEqual(provided,expected))return json(res,401,{error:"Unauthorized"});
  try {
    let raw="";
    for await(const chunk of req){raw+=chunk.toString();if(raw.length>1024)return json(res,413,{error:"Too large"});}
    const language=JSON.parse(raw||"{}").language;
    if(!["en","de"].includes(language))return json(res,400,{error:"Invalid language"});
    const body={expires_after:{anchor:"created_at",seconds:60},
      session:{type:"transcription",audio:{input:{
        format:{type:"audio/pcm",rate:24000},
        transcription:{model:"gpt-live-transcribe",languages:[language]},
        turn_detection:null
      }}}};
    const upstream=await fetch("https://api.openai.com/v1/realtime/client_secrets",{
      method:"POST",
      headers:{"Authorization":"Bearer "+key,"Content-Type":"application/json"},
      body:JSON.stringify(body),signal:AbortSignal.timeout(10000)
    });
    if(!upstream.ok){console.error("Upstream status",upstream.status);return json(res,502,{error:"Token service unavailable"});}
    const result=await upstream.json();
    if(typeof result.value!=="string"||!result.value.startsWith("ek_"))return json(res,502,{error:"Unexpected upstream response"});
    return json(res,200,{value:result.value,expires_at:result.expires_at});
  }catch(error){console.error("Cloud token error",error?.name);return json(res,502,{error:"Cloud token error"});}
}).listen(Number(process.env.PORT||10000),"0.0.0.0");
