import http from "node:http";
import { timingSafeEqual } from "node:crypto";

const apiKey=process.env.OPENROUTER_API_KEY;
const accessCode=process.env.ERICPDF_ACCESS_TOKEN;
const model=process.env.OPENROUTER_STT_MODEL || "openai/whisper-large-v3-turbo";
const maxDailySeconds=Number(process.env.ERICPDF_MAX_DAILY_AUDIO_SECONDS || 7200);
if(!apiKey || !accessCode || accessCode.length<24) {
  console.error("OPENROUTER_API_KEY and ERICPDF_ACCESS_TOKEN (24+ chars) required");
  process.exit(1);
}
const requestCounts=new Map();
let daily={day:new Date().toISOString().slice(0,10),seconds:0};
function respond(res,status,payload) {
  res.writeHead(status,{"Content-Type":"application/json","Cache-Control":"no-store",
    "X-Content-Type-Options":"nosniff"});
  res.end(JSON.stringify(payload));
}
function authorized(header) {
  const supplied=Buffer.from((header||"").replace(/^Bearer /i,""));
  const expected=Buffer.from(accessCode);
  return supplied.length===expected.length && timingSafeEqual(supplied,expected);
}
http.createServer(async(req,res)=>{
  if(req.url==="/health" && req.method==="GET")
    return respond(res,200,{ok:true,provider:"OpenRouter"});
  if(req.url!=="/transcribe" || req.method!=="POST")
    return respond(res,404,{error:"Not found"});
  if(!authorized(req.headers.authorization))
    return respond(res,401,{error:"Authorization required"});

  const ip=req.socket.remoteAddress||"unknown",now=Date.now();
  const previous=requestCounts.get(ip)||{time:now,count:0};
  const state=now-previous.time>60000?{time:now,count:0}:previous;
  state.count++;requestCounts.set(ip,state);
  if(state.count>18)return respond(res,429,{error:"Request rate limited"});
  if(Number(req.headers["content-length"]||0)>850000)
    return respond(res,413,{error:"Audio too large"});

  try {
    let raw="";
    for await(const block of req){
      raw+=block.toString("utf8");
      if(raw.length>850000)return respond(res,413,{error:"Audio too large"});
    }
    const body=JSON.parse(raw||"{}");
    if(!["en","de"].includes(body.language) || typeof body.audio!=="string" ||
      body.audio.length>800000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(body.audio))
      return respond(res,400,{error:"Invalid audio"});

    const wav=Buffer.from(body.audio,"base64");
    if(wav.length<32044 || wav.length>420044 ||
      wav.toString("ascii",0,4)!=="RIFF" ||
      wav.toString("ascii",8,12)!=="WAVE" ||
      wav.toString("ascii",36,40)!=="data" ||
      wav.readUInt16LE(20)!==1 || wav.readUInt16LE(22)!==1 ||
      wav.readUInt32LE(24)!==16000 || wav.readUInt16LE(34)!==16 ||
      wav.readUInt32LE(40)!==wav.length-44)
      return respond(res,400,{error:"Expected 16kHz mono PCM WAV"});

    const seconds=(wav.length-44)/32000;
    const today=new Date().toISOString().slice(0,10);
    if(today!==daily.day)daily={day:today,seconds:0};
    if(daily.seconds+seconds>maxDailySeconds)
      return respond(res,429,{error:"Daily usage limit reached"});
    daily.seconds+=seconds;

    const upstream=await fetch("https://openrouter.ai/api/v1/audio/transcriptions",{
      method:"POST",
      headers:{"Authorization":"Bearer "+apiKey,"Content-Type":"application/json"},
      body:JSON.stringify({
        model,input_audio:{data:body.audio,format:"wav"},
        language:body.language,response_format:"json"
      }),
      signal:AbortSignal.timeout(40000)
    });
    if(!upstream.ok) {
      console.error("OpenRouter STT status:",upstream.status);
      return respond(res,502,{error:"OpenRouter transcription failed"});
    }
    const transcript=await upstream.json();
    if(typeof transcript.text!=="string")
      return respond(res,502,{error:"No transcript returned"});
    return respond(res,200,{text:transcript.text.slice(0,20000),sequence:body.sequence||0});
  } catch(error) {
    console.error("STT proxy error:",error?.name||"unknown");
    return respond(res,502,{error:"Cloud transcription unavailable"});
  }
}).listen(Number(process.env.PORT||10000),"0.0.0.0",()=>{
  console.log("EricPDF OpenRouter transcription proxy listening");
});
