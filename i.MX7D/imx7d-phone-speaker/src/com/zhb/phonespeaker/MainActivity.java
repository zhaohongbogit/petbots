package com.zhb.phonespeaker;

import android.app.Activity;
import android.os.*;
import android.media.*;
import android.widget.*;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-only LAN prototype. No cloud, microphone or storage permission. */
public class MainActivity extends Activity {
    final Handler main=new Handler();
    final AtomicBoolean busy=new AtomicBoolean();
    volatile boolean active;
    volatile ServerSocket listener;
    volatile Socket client;
    volatile String job="", phase="idle", error="", route="unverified";
    volatile int duration;
    String key, allowedIp, bindIp;
    TextView info;
    MediaPlayer player;
    AudioManager audio;
    AudioFocusRequest focus;
    File audioFile;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.content.SharedPreferences prefs=getSharedPreferences("lan",MODE_PRIVATE);
        for(String name:new String[]{"pair_key","board_ip","bind_ip"})
            if(getIntent().hasExtra(name)){prefs.edit().putString(name,getIntent().getStringExtra(name)).apply();getIntent().removeExtra(name);}
        key=prefs.getString("pair_key","");allowedIp=prefs.getString("board_ip","");bindIp=prefs.getString("bind_ip","");
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,36,24,24);
        TextView title=new TextView(this);title.setText("iMX7 手机音箱");title.setTextSize(28);root.addView(title);
        info=new TextView(this);info.setTextSize(19);info.setPadding(0,30,0,30);root.addView(info);
        Button stop=new Button(this);stop.setText("停止当前播放");stop.setOnClickListener(v -> {if(busy.get())finishJob("cancelled","user_stopped");});root.addView(stop);
        Button chat=new Button(this);chat.setText("给小七发文字");chat.setOnClickListener(v->startActivity(new android.content.Intent(this,TextChatActivity.class)));root.addView(chat);
        TextView tip=new TextView(this);tip.setText("请保持此页面在前台。\n用手机音量键调整音量。\n只接收已配置 iMX7 的局域网音频，不上传云端。\n离开页面会停止接收和播放。");tip.setTextSize(16);root.addView(tip);
        setContentView(root);show();
    }
    String stateLabel(String value){switch(value){case "ready":return "等待 iMX7 音频";case "accepted":return "已接收";case "preparing":return "准备播放";case "playing":return "正在播放";case "completed":return "播放完成";case "cancelled":return "已停止播放";case "stopped":return "接收已关闭";case "failed":return "播放失败";default:return value;}}
    void show(){info.setText("接收地址："+bindIp+":8765\n允许设备："+allowedIp+"\n状态："+stateLabel(phase)+"\n音频时长："+duration+" ms\n实际输出："+(route.equals("phone_speaker")?"手机内置扬声器":route.equals("unverified")?"等待播放确认":route)+"\n媒体音量："+audio.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)+(error.isEmpty()?"":"\n详情："+error));}
    @Override protected void onResume(){super.onResume();
        if(key.length()<24 || allowedIp.isEmpty() || bindIp.isEmpty()){phase="需要配置连接";show();return;}
        active=true;new Thread(this::serve,"pet-lan-server").start();
    }
    void serve(){
        try(ServerSocket ss=new ServerSocket()){
            ss.setReuseAddress(true);ss.bind(new InetSocketAddress(bindIp,8765));listener=ss;
            if(!active)return;
            main.post(()->{phase="ready";show();});
            while(active){try(Socket s=ss.accept()){client=s;s.setSoTimeout(10000);handle(s);}catch(Exception e){if(active)android.util.Log.i("PetSpeaker","request_failed="+e.getClass().getSimpleName());}finally{client=null;}}
        }catch(Exception e){if(active)main.post(()->{phase="listen_failed";error=e.getClass().getSimpleName();show();});}
        finally{listener=null;}
    }
    String line(InputStream in) throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c==10)return b.toString("US-ASCII").replace("\r","");if(b.size()>=2048)throw new IOException("header_too_long");b.write(c);}
        throw new EOFException();
    }
    void handle(Socket s) throws Exception {
        InputStream in=new BufferedInputStream(s.getInputStream());
        String[] first=line(in).split(" ");if(first.length!=3){reply(s,400,"{}");return;}
        Map<String,String> headers=new HashMap<>();int total=0;
        for(String l;(l=line(in)).length()>0;){total+=l.length();if(total>8192)throw new IOException("headers_too_long");int n=l.indexOf(':');if(n>0)headers.put(l.substring(0,n).toLowerCase(Locale.ROOT),l.substring(n+1).trim());}
        if(!s.getInetAddress().getHostAddress().equals(allowedIp) || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),headers.getOrDefault("x-pet-key","").getBytes(StandardCharsets.UTF_8))){reply(s,403,"{\"error\":\"forbidden\"}");return;}
        if(first[0].equals("GET") && first[1].startsWith("/status?id=")){
            if(!first[1].substring(11).equals(job)){reply(s,404,"{}");return;}
            JSONObject j=new JSONObject();j.put("id",job);j.put("state",phase);j.put("route",route);j.put("duration_ms",duration);j.put("error",error);reply(s,200,j.toString());return;
        }
        if(!first[0].equals("POST") || !first[1].equals("/play")){reply(s,404,"{}");return;}
        int size;try{size=Integer.parseInt(headers.getOrDefault("content-length","0"));}catch(Exception e){reply(s,400,"{}");return;}
        if(size<44 || size>2*1024*1024 || headers.containsKey("transfer-encoding")){reply(s,413,"{}");return;}
        if(!busy.compareAndSet(false,true)){reply(s,409,"{\"error\":\"busy\"}");return;}
        File f=null;boolean accepted=false;
        try{
            f=File.createTempFile("received-",".audio",getCacheDir());
            try(OutputStream out=new FileOutputStream(f)){byte[] buf=new byte[8192];int left=size;while(left>0){int n=in.read(buf,0,Math.min(left,buf.length));if(n<0)throw new EOFException();out.write(buf,0,n);left-=n;}}
            job=UUID.randomUUID().toString();phase="accepted";error="";route="unverified";duration=0;
            final File file=f; final String expected=job;
            main.post(()->{if(active && expected.equals(job)){audioFile=file;play();}else{file.delete();busy.set(false);}});
            accepted=true;reply(s,202,new JSONObject().put("id",job).put("state","accepted").toString());
        }finally{if(!accepted){if(f!=null)f.delete();busy.set(false);}}
    }
    void reply(Socket s,int code,String body)throws IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream o=s.getOutputStream();
        o.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));o.write(bytes);o.flush();
    }
    void play(){
        try{
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change->{if(change<0)finishJob("failed","audio_focus_lost");}).build();
            if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){finishJob("failed","audio_focus_denied");return;}
            AudioDeviceInfo speaker=null;for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)speaker=d;
            if(speaker==null){finishJob("failed","no_builtin_speaker");return;}
            player=new MediaPlayer();player.setAudioAttributes(attrs);player.setDataSource(audioFile.getAbsolutePath());
            if(!player.setPreferredDevice(speaker)){finishJob("failed","speaker_preference_rejected");return;}
            player.setVolume(0.6f,0.6f);
            player.setOnErrorListener((p,w,e)->{finishJob("failed","media_error_"+w+"_"+e);return true;});
            player.setOnCompletionListener(p->finishJob("completed",""));
            player.setOnPreparedListener(p->{duration=p.getDuration();if(duration>30000){finishJob("failed","audio_too_long");return;}p.start();phase="playing";show();android.util.Log.i("PetSpeaker","playing duration_ms="+duration);main.postDelayed(this::checkRoute,150);});
            player.prepareAsync();phase="preparing";show();main.postDelayed(playTimeout,35000);
        }catch(Exception e){finishJob("failed",e.getClass().getSimpleName());}
    }
    final Runnable playTimeout=()->finishJob("failed","play_timeout");
    void checkRoute(){if(player==null || !phase.equals("playing"))return;
        AudioDeviceInfo d=player.getRoutedDevice();
        if(d!=null){route=d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER?"phone_speaker":"type_"+d.getType();android.util.Log.i("PetSpeaker","route="+route);show();if(d.getType()!=AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){finishJob("failed","wrong_output_route");return;}}
        main.postDelayed(this::checkRoute,500);
    }
    void finishJob(String result,String detail){
        main.removeCallbacks(playTimeout);
        if(player!=null){player.release();player=null;}
        if(focus!=null){audio.abandonAudioFocusRequest(focus);focus=null;}
        if(audioFile!=null){audioFile.delete();audioFile=null;}
        phase=result;error=detail;busy.set(false);show();android.util.Log.i("PetSpeaker","state="+result+" route="+route+" error="+detail);
    }
    @Override protected void onPause(){active=false;try{if(listener!=null)listener.close();if(client!=null)client.close();}catch(IOException ignored){}finishJob("stopped","");super.onPause();}
}
