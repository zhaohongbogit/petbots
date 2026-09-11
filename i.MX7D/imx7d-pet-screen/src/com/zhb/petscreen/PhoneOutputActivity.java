package com.zhb.petscreen;
import android.app.Activity;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import org.json.JSONObject;

/** One-shot LAN audio sender; vendor credentials are never used here. */
public class PhoneOutputActivity extends Activity {
    TextView status;
    Button toneButton,recordingButton;
    String host,key;
    volatile boolean active,busy;
    volatile HttpURLConnection connection;
    @Override public void onCreate(Bundle b){super.onCreate(b);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.content.SharedPreferences prefs=getSharedPreferences("phone-speaker",MODE_PRIVATE);
        for(String name:new String[]{"pair_key","phone_ip"})if(getIntent().hasExtra(name)){prefs.edit().putString(name,getIntent().getStringExtra(name)).apply();getIntent().removeExtra(name);}
        key=prefs.getString("pair_key","");host=prefs.getString("phone_ip","");
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(28,24,28,24);
        TextView title=new TextView(this);title.setText("小米手机 · 局域网音箱");title.setTextSize(26);root.addView(title);
        status=new TextView(this);status.setTextSize(19);status.setPadding(0,24,0,24);
        status.setText("目标："+host+"\n手机需打开“iMX7 手机音箱”页面\n先播放 3 秒测试音，再测试已有录音。");root.addView(status,new LinearLayout.LayoutParams(-1,140));
        toneButton=new Button(this);toneButton.setText("测试音 → 手机扬声器");toneButton.setOnClickListener(v->send(true));root.addView(toneButton);
        recordingButton=new Button(this);recordingButton.setText("现有录音 → 手机扬声器");recordingButton.setOnClickListener(v->send(false));root.addView(recordingButton);
        TextView note=new TextView(this);note.setText("仅通过当前局域网发送音频，不调用云端。\n测试音不依赖 iMX7 录音硬件。接收成功不等于实际听到声音。");root.addView(note);setContentView(root);
    }
    @Override protected void onResume(){super.onResume();active=true;}
    @Override protected void onPause(){active=false;if(connection!=null)connection.disconnect();super.onPause();}
    void update(String text){runOnUiThread(()->{if(active)status.setText(text);});}
    void send(boolean tone){
        if(busy)return;
        if(key.length()<24 || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")){status.setText("尚未配置手机连接，请重新配置");return;}
        busy=true;toneButton.setEnabled(false);recordingButton.setEnabled(false);
        new Thread(()->{
            long begin=SystemClock.elapsedRealtime();
            try{
                byte[] bytes;
                if(tone)bytes=testTone();
                else {File f=new File(getFilesDir(),"last-recording.wav");if(f.length()<44 || f.length()>2*1024*1024)throw new IOException("没有可用录音");
                    try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);bytes=out.toByteArray();}}
                update("正在发送到手机 · "+bytes.length+" 字节");
                JSONObject accepted=request("/play",bytes);String id=accepted.getString("id");
                android.util.Log.i("PetLan","accepted bytes="+bytes.length+" upload_ms="+(SystemClock.elapsedRealtime()-begin));
                long deadline=SystemClock.elapsedRealtime()+45000;
                while(active && SystemClock.elapsedRealtime()<deadline){
                    JSONObject result=request("/status?id="+id,null);String phase=result.getString("state");String route=result.optString("route");
                    update("手机状态："+stateLabel(phase)+"\n实际输出："+(route.equals("phone_speaker")?"手机内置扬声器":route.equals("unverified")?"等待确认":route)+"\n音频时长："+result.optInt("duration_ms")+" ms\n"+result.optString("error"));
                    if(phase.equals("completed")){android.util.Log.i("PetLan","completed duration_ms="+result.optInt("duration_ms")+" route="+route+" total_ms="+(SystemClock.elapsedRealtime()-begin));return;}
                    if(phase.equals("failed")||phase.equals("cancelled")||phase.equals("stopped"))throw new IOException("手机："+phase+" "+result.optString("error"));
                    Thread.sleep(350);
                }
                if(active)throw new IOException("等待手机播放超时");
            }catch(Exception e){android.util.Log.i("PetLan","failed="+e.getClass().getSimpleName());update("播放未完成\n检查手机音箱页面是否打开、两端是否仍在同一局域网。\n未自动重试；手机页面可查看播放错误。");}
            finally{busy=false;runOnUiThread(()->{toneButton.setEnabled(true);recordingButton.setEnabled(true);});}
        },"pet-lan-send").start();
    }
    String stateLabel(String value){switch(value){case "accepted":return "已接收";case "preparing":return "准备播放";case "playing":return "正在播放";case "completed":return "播放完成";case "failed":return "播放失败";case "cancelled":return "已停止";default:return value;}}
    JSONObject request(String path,byte[] body)throws Exception{
        if(!active)throw new IOException("页面已退出");
        HttpURLConnection c=(HttpURLConnection)new URL("http://"+host+":8765"+path).openConnection();connection=c;
        c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Pet-Key",key);
        try{
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/octet-stream");c.setFixedLengthStreamingMode(body.length);try(OutputStream o=c.getOutputStream()){o.write(body);}}
            int code=c.getResponseCode();if(code!=200 && code!=202)throw new IOException("HTTP "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[1024];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>8192)throw new IOException("响应过大");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}
        }finally{c.disconnect();connection=null;}
    }
    static byte[] testTone(){
        int rate=16000,samples=rate*3;ByteBuffer b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36+samples*2).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(samples*2);
        for(int i=0;i<samples;i++){double t=i/(double)rate,beat=t%1.0;double env=beat<0.7?Math.min(1,Math.min(beat/0.02,(0.7-beat)/0.02)):0;
            b.putShort((short)(Math.sin(2*Math.PI*(523.25+((int)t%2)*136)*t)*4500*env));}
        return b.array();
    }
}
