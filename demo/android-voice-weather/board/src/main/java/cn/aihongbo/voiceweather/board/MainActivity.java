package cn.aihongbo.voiceweather.board;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;

/** Foreground-only, offline ARMv7 microphone diagnostic. Phone app remains separate. */
public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,partial,finals;
    private Spinner source,device,profile;
    private CheckBox saveAudio;
    private final List<AudioDeviceInfo> inputs=new ArrayList<>();
    private int generation;
    volatile MicSession session;
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(session!=null)status.setText(session.status());
        handler.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,8,16,8);scroll.addView(root);setContentView(scroll);
        label(root,"阿洛 ARMv7 · 单识别器真实麦克风",22);
        label(root,"先预热再录音；仅前台监听。不联网、不播报、不执行天气。天气词表限郑州、北京、上海、广州、深圳、杭州及今明后天天气、下雨、温度；保留自由听写作对照。",15);
        profile=new Spinner(this);profile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"天气词表（低负载）","自由听写（对照）"}));root.addView(profile);
        saveAudio=new CheckBox(this);saveAudio.setText("保存完整诊断录音（最多120秒，仅本地）");root.addView(saveAudio);
        source=new Spinner(this);source.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"VOICE_RECOGNITION","MIC"}));root.addView(source);
        List<String> names=new ArrayList<>();names.add("系统默认输入");inputs.add(null);
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        for(AudioDeviceInfo input:audio.getDevices(AudioManager.GET_DEVICES_INPUTS)){
            inputs.add(input);names.add("输入 "+input.getId()+" / type "+input.getType()+" / "+input.getProductName());
        }
        device=new Spinner(this);device.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));root.addView(device);
        Button start=new Button(this);start.setText("预热并监听 120 秒");root.addView(start);
        start.setOnClickListener(view->{
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);
            else begin(120);
        });
        Button stop=new Button(this);stop.setText("停止并保存诊断报告");root.addView(stop);stop.setOnClickListener(view->{if(session!=null)session.stop();});
        status=label(root,"待开始；不会自动打开麦克风",16);
        partial=label(root,"临时转写：",20);finals=label(root,"完整句：",18);
    }
    private TextView label(LinearLayout root,String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);root.addView(text);return text;}
    void begin(int seconds) {
        begin(seconds,profile.getSelectedItemPosition()==0,saveAudio.isChecked());
    }
    void begin(int seconds,boolean weatherVocabulary,boolean diagnosticAudio) {
        if(session!=null&&!session.finished)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){status.setText("未获得麦克风权限");return;}
        final int ticket=++generation;partial.setText("临时转写：");finals.setText("完整句：");
        int audioSource=source.getSelectedItemPosition()==0?MediaRecorder.AudioSource.VOICE_RECOGNITION:MediaRecorder.AudioSource.MIC;
        session=new MicSession(this,seconds,audioSource,inputs.get(device.getSelectedItemPosition()),(text,complete)->handler.post(()->{
            if(ticket!=generation)return;
            if(complete){String previous=finals.getText().toString();if(previous.length()>4000)previous=previous.substring(previous.length()-4000);finals.setText(previous+"\n"+text);partial.setText("临时转写：");}
            else partial.setText("临时转写："+text);
        }),weatherVocabulary,diagnosticAudio);
        session.start();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==7&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)begin(120);}
    @Override protected void onResume(){super.onResume();handler.post(ticker);}
    @Override protected void onPause(){if(session!=null)session.stop();handler.removeCallbacks(ticker);super.onPause();}
    @Override protected void onDestroy(){generation++;if(session!=null)session.stop();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
