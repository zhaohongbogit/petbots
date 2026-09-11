package com.zhb.petscreen;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.content.pm.PackageManager;
import android.os.Handler;
import java.io.File;

public class MainActivity extends Activity {
    final String[] names = {"待机", "倾听", "思考", "说话"};
    final int[] colors = {0xff65e6cb, 0xff7cbcff, 0xffc4a0ff, 0xffffc877};
    Face face;
    TextView status;
    Button[] buttons = new Button[4];
    int mode = 0, touches = 0;
    PcmRecorder recorder;
    MediaPlayer player;
    Button recordButton, playButton;
    TextView audioStatus;
    File clip;
    Handler handler = new Handler();
    long recordingStarted;
    boolean animate=false, resumed=false;
    long previousCpu, previousWall;
    final Runnable sample = new Runnable() {
        public void run() {
            long wall=android.os.SystemClock.elapsedRealtime(), cpu=android.os.Process.getElapsedCpuTime();
            String phase=recorder!=null&&recorder.isRunning()?"record":player!=null?"play":animate?"animation":"idle";
            if(previousWall>0) android.util.Log.i("PetPerf", "phase="+phase+" cpu_ms="+(cpu-previousCpu)+" wall_ms="+(wall-previousWall));
            previousCpu=cpu;previousWall=wall;
            if(resumed) handler.postDelayed(this,1000);
        }
    };
    final Runnable frame = new Runnable() {
        public void run() {if(resumed && animate){face.invalidate();handler.postDelayed(this,66);}}
    };
    @Override protected void onResume(){super.onResume();resumed=true;previousWall=0;handler.post(sample);handler.post(frame);}

    final Runnable finishRecording = () -> stopRecording();
    final Runnable meter = new Runnable() {
        public void run() {
            if (recorder == null) return;
            try {
                audioStatus.setText("录音中，请说话 · " + Math.min(5000, recorder.samples*1000/PcmRecorder.SAMPLE_RATE) + "/5000 ms · 峰值 " + recorder.peak);
                handler.postDelayed(this, 250);
            } catch (RuntimeException e) { failAudio("读取录音状态失败"); }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 12, 20, 12);
        root.setBackgroundColor(0xff101923);
        TextView title = new TextView(this);
        title.setText("宠物表情实验室  /  点击此处开关动画");
        title.setOnClickListener(v -> {animate=!animate; title.setText("宠物表情实验室  /  动画"+(animate?"开启（15 fps目标）":"关闭"));handler.removeCallbacks(frame);handler.post(frame);}); title.setTextColor(Color.WHITE); title.setTextSize(20);
        root.addView(title);
        face = new Face(); root.addView(face, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this); status.setTextColor(0xffa8bacb); status.setTextSize(15);
        status.setText("点击下方切换表情；在脸部滑动检查触摸轨迹");
        root.addView(status, new LinearLayout.LayoutParams(-1, 30));
        LinearLayout row = new LinearLayout(this);
        for (int i=0;i<4;i++) {
            final int index=i; Button b=new Button(this); buttons[i]=b;
            b.setText(names[i]); b.setAllCaps(false); b.setTextSize(18);
            b.setOnClickListener(v -> { mode=index; updateButtons(); face.invalidate(); });
            row.addView(b,new LinearLayout.LayoutParams(0,60,1));
        }
        root.addView(row);
        clip = new File(getFilesDir(), "last-recording.wav");
        audioStatus = new TextView(this); audioStatus.setTextColor(0xffa8bacb); audioStatus.setTextSize(15);
        audioStatus.setText("本地录音测试 · 每次录音替换上一段 · 不上传");
        root.addView(audioStatus, new LinearLayout.LayoutParams(-1, 28));
        LinearLayout audioRow = new LinearLayout(this);
        recordButton = new Button(this); recordButton.setText("录音 5 秒");
        recordButton.setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
            else startRecording();
        });
        playButton = new Button(this); playButton.setText("播放录音"); playButton.setEnabled(clip.length()>0);
        playButton.setOnClickListener(v -> playRecording());
        audioRow.addView(recordButton, new LinearLayout.LayoutParams(0,52,1));
        audioRow.addView(playButton, new LinearLayout.LayoutParams(0,52,1));
        Button phone=new Button(this);phone.setText("手机音箱");
        phone.setOnClickListener(v->startActivity(new android.content.Intent(this,PhoneOutputActivity.class)));
        audioRow.addView(phone,new LinearLayout.LayoutParams(0,52,1));
        Button chat=new Button(this);chat.setText("宠物对话");chat.setOnClickListener(v->startActivity(new android.content.Intent(this,ChatActivity.class)));audioRow.addView(chat,new LinearLayout.LayoutParams(0,52,1));
        root.addView(audioRow);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(root); updateButtons();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request==7) audioStatus.setText(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED
            ? "麦克风已允许，请点击录音 5 秒" : "未允许麦克风，无法录音");
    }
    void setAudioMode(int value) { mode=value; updateButtons(); face.invalidate(); }
    void startRecording() {
        if (recorder!=null || player!=null) return;
        try {
            recorder=new PcmRecorder(clip,5000); recordingStarted=System.currentTimeMillis();
            recorder.start(result -> handler.post(() -> recordingFinished(result)));
            android.util.Log.i("PetAudio","record_started format=pcm16 rate=16000 source=voice_recognition");
            recordButton.setEnabled(false); playButton.setEnabled(false); setAudioMode(1);
            handler.post(meter); handler.postDelayed(finishRecording,8000);
        } catch (Exception e) { failAudio("无法开始录音："+e.getClass().getSimpleName()); }
    }
    void stopRecording() {
        handler.removeCallbacks(meter); handler.removeCallbacks(finishRecording);
        if(recorder==null) return;
        recorder.stop();
    }
    void recordingFinished(PcmRecorder.Result result) {
        recorder=null;
        android.util.Log.i("PetAudio","record_stop elapsed_ms="+result.elapsedMs+" samples="+result.samples+" duration_ms="+result.durationMs()+" peak="+result.peak+" bytes="+clip.length()+" success="+result.success+" error="+result.error);
        recordButton.setEnabled(true);playButton.setEnabled(result.success);setAudioMode(0);
        audioStatus.setText(result.success ? "录音已保存 · 实际 "+result.durationMs()+" ms · 峰值 "+result.peak+" · 点击播放检查声音" : "录音未完成："+result.error);
    }
    void playRecording() {
        if(recorder!=null || player!=null) return;
        try {
            player=new MediaPlayer();player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(clip.getAbsolutePath());
            player.setOnCompletionListener(p -> {android.util.Log.i("PetAudio","play_completed");releasePlayer(); audioStatus.setText("播放已结束 · 请确认是否听到刚才的声音");});
            player.setOnErrorListener((p,w,e) -> {failAudio("播放失败，错误 "+w+"/"+e);return true;});
            player.prepare();android.util.Log.i("PetAudio","play_duration_ms="+player.getDuration());player.start(); recordButton.setEnabled(false);playButton.setEnabled(false);setAudioMode(3);
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            audioStatus.setText("本地播放中 · "+player.getDuration()+" ms · 媒体音量 "+am.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch(Exception e) {failAudio("无法播放："+e.getClass().getSimpleName());}
    }
    void releasePlayer() {
        if(player!=null) {player.release();player=null;}
        recordButton.setEnabled(true);playButton.setEnabled(clip.length()>0);setAudioMode(0);
    }
    void failAudio(String message) {
        handler.removeCallbacks(meter);handler.removeCallbacks(finishRecording);
        if(recorder!=null) {recorder.stop();recorder=null;clip.delete();}
        releasePlayer();audioStatus.setText(message);
    }
    @Override protected void onPause() {
        super.onPause();resumed=false;handler.removeCallbacks(frame);handler.removeCallbacks(sample);
        if(recorder!=null) stopRecording();
        if(player!=null) {releasePlayer();audioStatus.setText("离开页面，播放已停止");}
    }
    void updateButtons() {
        for(int i=0;i<4;i++) {buttons[i].setText((mode==i?"● ":"")+names[i]); buttons[i].setTextColor(mode==i?0xff006b58:0xff263648);}
        face.setContentDescription("宠物表情："+names[mode]);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) touches++;
        if(status!=null) status.setText(String.format(java.util.Locale.ROOT,"触摸 %d 次  ·  X %.0f / Y %.0f  ·  %s",touches,e.getRawX(),e.getRawY(),e.getActionMasked()==1?"已松开":"接触中"));
        return super.dispatchTouchEvent(e);
    }
    class Face extends View {
        Paint p=new Paint(3); Path trail=new Path();
        Face(){super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        void line(float x1,float y1,float x2,float y2){getPaint(); canvas.drawLine(x1,y1,x2,y2,p);}
        Canvas canvas;
        void getPaint(){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(colors[mode]);}
        @Override protected void onDraw(Canvas c){
            canvas=c; float s=Math.min(getWidth()/760f,getHeight()/300f);
            c.save(); c.translate(getWidth()/2f,getHeight()/2f); c.scale(s,s);
            if(animate){double phase=android.os.SystemClock.uptimeMillis()/350.0;c.translate(0,(float)Math.sin(phase)*7);c.scale(1,1+(float)Math.sin(phase)*0.025f);}
            p.setStyle(Paint.Style.FILL);p.setColor(0xff1a2a38);c.drawRoundRect(-205,-115,205,115,70,70,p);
            getPaint();
            if(mode==0){c.drawArc(-120,-40,-45,20,190,160,false,p);c.drawArc(45,-40,120,20,190,160,false,p);}
            else if(mode==2){line(-118,-25,-50,-5);line(50,-5,118,-25);}
            else {p.setStyle(Paint.Style.FILL);c.drawOval(-113,-50,-75,15,p);c.drawOval(75,-50,113,15,p);}
            getPaint();
            if(mode==3)c.drawOval(-28,30,28,82,p);
            else if(mode==2){c.drawLine(-15,60,15,60,p);for(int i=0;i<3;i++){p.setStyle(Paint.Style.FILL);c.drawCircle(245+i*30,-65,6,p);}}
            else c.drawArc(-42,15,42,75,15,150,false,p);
            if(mode==1){getPaint();c.drawArc(-260,-65,-195,65,110,140,false,p);c.drawArc(195,-65,260,65,-70,140,false,p);}
            c.restore(); p.setColor(0xfffa7195);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawPath(trail,p);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==0){trail.reset();trail.moveTo(e.getX(),e.getY());}
            else trail.lineTo(e.getX(),e.getY());
            invalidate();return true;
        }
    }
}
