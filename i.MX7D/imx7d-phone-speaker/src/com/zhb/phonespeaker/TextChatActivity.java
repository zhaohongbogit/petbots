package com.zhb.phonespeaker;
import android.app.Activity;
import android.os.*;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.text.InputFilter;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.UUID;

public class TextChatActivity extends Activity {
    static final int RECORD_REQUEST=31;
    EditText input;TextView output;Button send,voice;String host,key;volatile boolean active,busy,recording;volatile HttpURLConnection connection;PcmRecorder recorder;File recordingFile;
    public void onCreate(Bundle b){super.onCreate(b);android.content.SharedPreferences prefs=getSharedPreferences("lan",MODE_PRIVATE);host=prefs.getString("board_ip","");key=prefs.getString("pair_key","");
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,24,20,20);
        TextView title=new TextView(this);title.setText("和小七聊聊");title.setTextSize(26);root.addView(title);
        TextView tip=new TextView(this);tip.setText("手机输入中文，iMX7 同步显示对话与表情。\n请保持 iMX7 在宠物对话页面。");root.addView(tip);
        Button homework=new Button(this);homework.setText("发送作业截图 / 规划时间");homework.setOnClickListener(v->startActivity(new android.content.Intent(this,HomeworkActivity.class)));root.addView(homework);
        ScrollView scroll=new ScrollView(this);output=new TextView(this);output.setTextSize(20);output.setTextIsSelectable(true);output.setPadding(0,20,0,20);output.setText("小七准备好听你说了。");scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        input=new EditText(this);input.setTextSize(18);input.setHint("想对小七说什么？");input.setSingleLine(true);input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        input.setOnEditorActionListener((view,action,event)->{
            boolean sendAction=action==EditorInfo.IME_ACTION_SEND;
            boolean enterKey=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN;
            if(sendAction||enterKey){send();return true;}
            return false;
        });root.addView(input);
        LinearLayout actions=new LinearLayout(this);
        voice=new Button(this);voice.setText("点击开始说话");voice.setOnClickListener(v->toggleRecording());actions.addView(voice,new LinearLayout.LayoutParams(0,-2,1));
        send=new Button(this);send.setText("发送给小七");send.setOnClickListener(v->send());actions.addView(send,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
        // ADB-supplied harmless test text fills the editor only; it never sends automatically.
        if(getIntent().hasExtra("draft")){input.setText(getIntent().getStringExtra("draft"));getIntent().removeExtra("draft");}
    }
    protected void onResume(){super.onResume();active=true;}
    protected void onPause(){active=false;if(recording&&recorder!=null)recorder.stop();if(connection!=null)connection.disconnect();super.onPause();}
    void show(String text){runOnUiThread(()->{if(active)output.setText(text);});}
    void send(){String text=input.getText().toString().trim();if(busy||text.isEmpty())return;
        if(key.isEmpty()||host.isEmpty()){output.setText("请先完成两台设备的连接配置");return;}
        busy=true;send.setEnabled(false);input.setEnabled(false);show("你："+text+"\n\n小七正在思考…");
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        new Thread(()->{try{String id=UUID.randomUUID().toString();request("/input",new JSONObject().put("id",id).put("text",text));long deadline=SystemClock.elapsedRealtime()+75000;
            while(active&&SystemClock.elapsedRealtime()<deadline){JSONObject data=request("/status?id="+id,null);String state=data.optString("state");
                if(state.equals("completed")){show("你："+text+"\n\n小七："+data.optString("reply"));runOnUiThread(()->input.setText(""));android.util.Log.i("PetText","completed");return;}
                if(state.equals("failed")||state.equals("cancelled"))throw new IOException("chat_failed");Thread.sleep(700);}
            if(active)throw new IOException("timeout");
        }catch(Exception e){show("本次对话未完成，文字已保留。请查看 iMX7 是否在对话页面及网络状态。\n\n你："+text);android.util.Log.i("PetText","failed="+e.getClass().getSimpleName());}
        finally{busy=false;runOnUiThread(()->{send.setEnabled(true);input.setEnabled(true);});}},"pet-remote-text").start();
    }
    void toggleRecording(){
        if(recording){recorder.stop();voice.setEnabled(false);voice.setText("正在处理录音…");return;}
        if(busy)return;if(key.isEmpty()||host.isEmpty()){output.setText("请先完成两台设备的连接配置");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},RECORD_REQUEST);return;}
        startRecording();
    }
    void startRecording(){
        recordingFile=new File(getCacheDir(),"pet-voice.wav");recorder=new PcmRecorder(recordingFile,7000);
        try{recording=true;voice.setText("点击结束说话（最长7秒）");send.setEnabled(false);output.setText("正在听，请对手机说话…");recorder.start((ok,duration,peak,error)->runOnUiThread(()->recordingFinished(ok,duration,peak,error)));android.util.Log.i("PetMic","record_started max_ms=7000");}
        catch(Exception e){recording=false;voice.setText("点击开始说话");send.setEnabled(true);output.setText("无法启动手机录音");android.util.Log.i("PetMic","record_failed="+e.getClass().getSimpleName());}
    }
    void recordingFinished(boolean ok,long duration,int peak,String error){
        recording=false;recorder=null;voice.setEnabled(true);voice.setText("点击开始说话");send.setEnabled(true);android.util.Log.i("PetMic","record_finished duration_ms="+duration+" peak="+peak+" success="+ok);
        if(!active){recordingFile.delete();return;}if(!ok){output.setText("录音时间太短，请重试");recordingFile.delete();return;}uploadVoice(recordingFile);
    }
    void uploadVoice(File file){
        busy=true;voice.setEnabled(false);send.setEnabled(false);input.setEnabled(false);show("录音完成，小七正在识别和思考…");
        new Thread(()->{String id=UUID.randomUUID().toString();try{upload("/voice?id="+id,file);long deadline=SystemClock.elapsedRealtime()+120000;
            while(active&&SystemClock.elapsedRealtime()<deadline){JSONObject data=request("/status?id="+id,null);String state=data.optString("state");if(state.equals("completed")){show("小七："+data.optString("reply"));android.util.Log.i("PetMic","conversation_completed");return;}if(state.equals("failed")||state.equals("cancelled"))throw new IOException(data.optString("error","voice_failed"));Thread.sleep(700);}if(active)throw new IOException("timeout");
        }catch(Exception e){String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();show("语音对话未完成，请检查 iMX7 页面、网络和语音识别服务。");android.util.Log.i("PetMic","conversation_failed="+e.getClass().getSimpleName()+" detail="+detail);}
        finally{file.delete();busy=false;runOnUiThread(()->{voice.setEnabled(true);send.setEnabled(true);input.setEnabled(true);});}},"phone-pet-voice-upload").start();
    }
    void upload(String path,File file)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://"+host+":8766"+path).openConnection();connection=c;c.setConnectTimeout(5000);c.setReadTimeout(12000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("X-Pet-Key",key);c.setRequestProperty("Content-Type","audio/wav");c.setFixedLengthStreamingMode(file.length());
        try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}try{int code=c.getResponseCode();if(code!=202&&code!=200)throw new IOException("http_"+code);}finally{c.disconnect();connection=null;}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==RECORD_REQUEST&&results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)startRecording();else if(request==RECORD_REQUEST)output.setText("需要麦克风权限才能用手机和小七说话");}
    JSONObject request(String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://"+host+":8766"+path).openConnection();connection=c;c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Pet-Key",key);
        try{if(body!=null){byte[] b=body.toString().getBytes("UTF-8");c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.length);try(OutputStream o=c.getOutputStream()){o.write(b);}}
            int code=c.getResponseCode();if(code!=200&&code!=202)throw new IOException("http_"+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>32768)throw new IOException("response_limit");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}
        }finally{c.disconnect();connection=null;}
    }
}
