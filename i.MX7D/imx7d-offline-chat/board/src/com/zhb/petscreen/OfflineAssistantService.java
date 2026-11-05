package com.zhb.petscreen;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Independent LAN-only service. No cloud configuration, speech input, TTS, or model-driven tools. */
public final class OfflineAssistantService extends Service {
 static volatile OfflineAssistantService current;
 final LinkedHashMap<String,JSONObject> jobs=new LinkedHashMap<>();
 final ArrayList<JSONObject> history=new ArrayList<>();
 final ExecutorService worker=Executors.newSingleThreadExecutor();
 final Handler main=new Handler(Looper.getMainLooper());
 volatile boolean open=true;volatile ServerSocket listener;volatile Socket client;volatile java.lang.Process process;
 String key="",ip="";volatile String running="";
 static String read(File file,int limit)throws IOException{try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>limit)throw new IOException("file_limit");out.write(b,0,n);}return out.toString("UTF-8");}}
 static void write(File file,String value)throws IOException{try(OutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
 @Override public void onCreate(){super.onCreate();current=this;
  NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("offline","离线文字助手",NotificationManager.IMPORTANCE_LOW));
  PendingIntent intent=PendingIntent.getActivity(this,0,new Intent(this,OfflineSetupActivity.class),PendingIntent.FLAG_IMMUTABLE);
  startForeground(7,new Notification.Builder(this,"offline").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("小七离线文字服务").setContentText("仅通过局域网接收配对手机文字，不录音、不播报").setContentIntent(intent).build());
  try{JSONObject config=new JSONObject(read(new File(getFilesDir(),"lan.json"),4096));key=config.getString("key");if(key.length()<32)throw new IOException("pairing_required");
   String saved=getSharedPreferences("offline",MODE_PRIVATE).getString("jobs","[]");JSONArray a=new JSONArray(saved);for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);if(!terminal(j.optString("state")))j.put("state","failed").put("error","设备服务重启，上一任务已中断；未自动重发。");jobs.put(j.getString("id"),j);}JSONArray h=new JSONArray(getSharedPreferences("offline",MODE_PRIVATE).getString("history","[]"));for(int i=0;i<h.length();i++)history.add(h.getJSONObject(i));
   new Thread(this::serve,"offline-lan-server").start();
  }catch(Exception e){android.util.Log.i("OfflineChat","not_ready="+e.getClass().getSimpleName());stopSelf();}
 }
 @Override public int onStartCommand(Intent i,int flags,int id){return START_NOT_STICKY;}
 static boolean terminal(String state){return Arrays.asList("completed","failed","cancelled").contains(state);}
 synchronized void persist(){JSONArray a=new JSONArray();for(JSONObject j:jobs.values())a.put(j);JSONArray h=new JSONArray();for(JSONObject m:history)h.put(m);getSharedPreferences("offline",MODE_PRIVATE).edit().putString("jobs",a.toString()).putString("history",h.toString()).apply();}
 synchronized JSONObject snapshot(String id)throws JSONException{JSONObject j=jobs.get(id);return j==null?null:new JSONObject(j.toString());}
 synchronized boolean canApply(String id){JSONObject j=jobs.get(id);return open&&id.equals(running)&&j!=null&&!terminal(j.optString("state"));}
 synchronized void progress(String id,String value){JSONObject j=jobs.get(id);if(j!=null&&!terminal(j.optString("state")))try{j.put("progress",value);}catch(JSONException ignored){}}
 synchronized void finish(String id,String state,String reply,String error){JSONObject j=jobs.get(id);if(j==null||terminal(j.optString("state")))return;
  try{j.put("state",state).put("reply",reply).put("error",error).put("progress","");if(state.equals("completed")){history.add(new JSONObject().put("role","user").put("content",j.getString("text")));history.add(new JSONObject().put("role","assistant").put("content",reply));while(history.size()>24)history.remove(0);}}catch(JSONException ignored){}
  if(id.equals(running))running="";persist();
 }
 void finishAction(String id,boolean ok,String reply){finish(id,ok?"completed":"failed",ok?reply:"",ok?"":reply);}
 synchronized JSONObject accept(String id,String text)throws Exception{
  if(jobs.containsKey(id)){if(!text.equals(jobs.get(id).optString("text")))throw new IllegalArgumentException("id_conflict");return snapshot(id);}
  if(!running.isEmpty())return null;
  JSONObject j=new JSONObject().put("id",id).put("text",text).put("state","accepted").put("reply","").put("error","").put("progress","等待设备处理").put("mode","offline");
  while(jobs.size()>=64){String first=jobs.keySet().iterator().next();jobs.remove(first);}jobs.put(id,j);running=id;persist();worker.execute(()->execute(id,text));return snapshot(id);
 }
 void execute(String id,String text){
  try{
   progress(id,"设备正在本地处理");
   if(Arrays.asList("帮助","可用功能","你能做什么").contains(text)){finish(id,"completed","我可以在本机短聊、生成时间计划、展示表情、查询设备状态、按明确指令调整媒体音量。输入示例：计划：写报告30分钟，复盘20分钟；开始19:00；可用120分钟。所有模型与任务都在i.MX7运行，不调用云端。","");return;}
   if(Arrays.asList("设备状态","查询设备状态","查看设备状态").contains(text)){AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE);finish(id,"completed","设备：i.MX7 / Android "+Build.VERSION.RELEASE+"\n工作模式：局域网、完全离线\n可用存储："+getFilesDir().getUsableSpace()/1024/1024+" MiB\n媒体音量："+a.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)+"\n本地模型："+(modelReady()?"已准备":"未准备")+"\n未启动麦克风或语音播报。","");return;}
   Integer volume=OfflineCommands.volume(text);if(volume!=null){AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE);int max=a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);a.setStreamVolume(AudioManager.STREAM_MUSIC,Math.round(max*volume/100f),0);finish(id,"completed","设备媒体音量已设为 "+a.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+max+"，没有开始播放。","");return;}
   if(OfflineCommands.isPlan(text)){Calendar c=Calendar.getInstance();finish(id,"completed",OfflineCommands.plan(text,c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE)),"");return;}
   if(ExpressionAction.resolve(text,"neutral",.5)!=null){main.post(()->{if(!canApply(id))return;try{startActivity(new Intent(this,OfflineFaceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("local_job",id).putExtra("local_text",text));main.postDelayed(()->{if(canApply(id))finish(id,"failed","","设备页面没有确认执行，请重试。");},10000);}catch(Exception e){finish(id,"failed","","设备页面未能启动。");}});return;}
   if(text.matches(".*(删除|付款|支付|发邮件|发消息给|提交订单|刷机).*")){finish(id,"completed","离线助手没有接入这项操作，未执行任何外部操作。","");return;}
   String shortReply=OfflineCommands.shortReply(text);if(shortReply!=null){finish(id,"completed",shortReply,"");return;}
   infer(id,text);
  }catch(IllegalArgumentException e){finish(id,"failed","","参数未通过检查。计划格式：计划：写报告30分钟，复盘20分钟；开始19:00；可用120分钟。音量需为0–100。");}
  catch(Exception e){finish(id,"failed","","本地处理未完成（"+e.getClass().getSimpleName()+"）。没有切换到云端，请保留原文重试。");}
 }
 boolean modelReady(){return model().isFile()&&new File(getApplicationInfo().nativeLibraryDir,"libimx7_local_chat.so").isFile();}
 File model(){return new File(getFilesDir(),"models/Qwen3-0.6B-Q2_K.gguf");}
 String prompt(String text,boolean context)throws JSONException{
  StringBuilder b=new StringBuilder("<|im_start|>system\n你是小七。用简短中文回答，不编造已经执行的操作。/no_think<|im_end|>\n");
  synchronized(this){if(context&&history.size()>=2){JSONObject u=history.get(history.size()-2),a=history.get(history.size()-1);if(u.optString("content").length()+a.optString("content").length()<60)b.append("<|im_start|>user\n").append(u.getString("content")).append("<|im_end|>\n<|im_start|>assistant\n").append(a.getString("content")).append("<|im_end|>\n");}}
  return b.append("<|im_start|>user\n").append(text).append("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n").toString();
 }
 void infer(String id,String text)throws Exception{
  File engine=new File(getApplicationInfo().nativeLibraryDir,"libimx7_local_chat.so");if(!engine.isFile()||!model().isFile()){finish(id,"failed","","本地对话模型尚未准备；仍可使用帮助、计划和设备命令。");return;}
  if(text.length()>120){finish(id,"failed","","当前本地模型仅支持简短消息，请缩短到120字以内。没有向云端发送。");return;}
  File in=new File(getCacheDir(),id+".prompt"),out=new File(getCacheDir(),id+".reply");
  try{
   for(int attempt=0;attempt<2;attempt++){
    if(!canApply(id))return;write(in,prompt(text,attempt==0));progress(id,"设备本地模型处理中，短回复可能需要1–3分钟");
    java.lang.Process p=new ProcessBuilder(engine.getAbsolutePath(),model().getAbsolutePath(),in.getAbsolutePath(),out.getAbsolutePath()).redirectErrorStream(true).start();process=p;
    Thread drain=new Thread(()->{try(InputStream stream=p.getInputStream()){byte[] buffer=new byte[4096];while(stream.read(buffer)!=-1){}}catch(IOException ignored){}},"local-model-output");drain.start();
    boolean done=p.waitFor(190,TimeUnit.SECONDS);if(!done){p.destroy();if(!p.waitFor(2,TimeUnit.SECONDS))p.destroyForcibly();}drain.join(1500);process=null;
    if(!canApply(id))return;
    if(!done){finish(id,"failed","","本地模型超时，未获得完整回复；没有切换云端。");return;}
    if(p.exitValue()==7&&attempt==0)continue;
    if(p.exitValue()!=0||!out.isFile()){finish(id,"failed","",p.exitValue()==7?"本条消息超出本地上下文容量，请缩短后重试。":"本地模型未完成回复；设备计划和明确命令仍可使用。");return;}
    String reply=read(out,8192).trim();if(reply.isEmpty())throw new IOException("empty_reply");finish(id,"completed",reply,"");return;
   }
  }finally{in.delete();out.delete();new File(out+".partial").delete();}
 }
 synchronized boolean cancel(String id){JSONObject j=jobs.get(id);if(j==null||terminal(j.optString("state")))return false;if(id.equals(running)&&process!=null)process.destroy();finish(id,"cancelled","","用户已取消，未自动重发。");return true;}
 void serve(){try(ServerSocket ss=new ServerSocket()){
  NetworkInterface wlan=NetworkInterface.getByName("wlan0");if(wlan!=null)for(InetAddress a:Collections.list(wlan.getInetAddresses()))if(a instanceof Inet4Address)ip=a.getHostAddress();
  if(ip.isEmpty())throw new IOException("wifi_required");ss.setReuseAddress(true);ss.bind(new InetSocketAddress(ip,8777));listener=ss;
  while(open)try(Socket s=ss.accept()){client=s;s.setSoTimeout(5000);try{handle(s);}catch(Exception e){try{respond(s,400,new JSONObject().put("error","请求未通过检查"));}catch(Exception ignored){}}finally{client=null;}}catch(IOException ignored){}
 }catch(Exception e){if(open)android.util.Log.i("OfflineChat","listener_failed="+e.getClass().getSimpleName());if(open)stopSelf();}}
 String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int c;(c=in.read())!=-1;){if(c==10)return b.toString("US-ASCII").replace("\r","");if(b.size()>=2048)throw new IOException("header_limit");b.write(c);}throw new EOFException();}
 void handle(Socket s)throws Exception{
  InputStream in=new BufferedInputStream(s.getInputStream());String[] first=line(in).split(" ");if(first.length!=3)throw new IOException("request");Map<String,String> headers=new HashMap<>();int size=0;
  for(String l;!(l=line(in)).isEmpty();){size+=l.length();if(size>8192)throw new IOException("headers");int colon=l.indexOf(':');if(colon<=0)throw new IOException("header");String h=l.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.put(h,l.substring(colon+1).trim())!=null)throw new IOException("duplicate_header");}
  String supplied=headers.getOrDefault("x-pet-key","");if(key.length()<32||!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){respond(s,403,new JSONObject());return;}
  if(first[0].equals("GET")&&first[1].equals("/health")){respond(s,200,new JSONObject().put("offline",true).put("model_ready",modelReady()).put("busy",!running.isEmpty()).put("name","小七离线助手"));return;}
  if(first[0].equals("GET")&&first[1].startsWith("/status?id=")){String id=first[1].substring(11);JSONObject j=snapshot(id);respond(s,j==null?404:200,j==null?new JSONObject():j);return;}
  if(!first[0].equals("POST")||!(first[1].equals("/input")||first[1].equals("/cancel"))){respond(s,404,new JSONObject());return;}
  if(!headers.getOrDefault("content-type","").startsWith("application/json")||headers.containsKey("transfer-encoding")){respond(s,415,new JSONObject());return;}
  int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length<1||length>4096){respond(s,413,new JSONObject());return;}
  byte[] b=new byte[length];int at=0;while(at<length){int n=in.read(b,at,length-at);if(n<0)throw new EOFException();at+=n;}JSONObject data=new JSONObject(new String(b,StandardCharsets.UTF_8));String id=data.optString("id","");if(!id.matches("[a-zA-Z0-9-]{16,64}"))throw new IOException("id");
  if(first[1].equals("/cancel")){respond(s,cancel(id)?200:409,new JSONObject().put("id",id));return;}
  String text=data.optString("text","").trim();if(text.isEmpty()||text.length()>500)throw new IOException("text");JSONObject j=accept(id,text);respond(s,j==null?409:202,j==null?new JSONObject().put("error","设备正在处理另一任务"):j);
 }
 void respond(Socket s,int code,JSONObject body)throws IOException{byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){open=false;if(process!=null)process.destroy();worker.shutdownNow();try{if(listener!=null)listener.close();if(client!=null)client.close();}catch(IOException ignored){}synchronized(this){if(!running.isEmpty())finish(running,"failed","","设备服务已停止，任务中断。");}current=null;stopForeground(true);super.onDestroy();}
}
