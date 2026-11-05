package cn.aihongbo.diary;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private TextView state, text;
    private LinearLayout list;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private static volatile String progress = "";
    private boolean destroyed;
    private final Runnable refresh = new Runnable() {
        public void run() {
            long free = RecordService.directory(MainActivity.this).getUsableSpace() / 1024 / 1024;
            String status = RecordService.status;
            if (!RecordService.recording && status.equals("尚未开始录音"))
                status = "当前未录音 · 已保存 " + Transcriber.recordings(MainActivity.this).length + " 段";
            state.setText((RecordService.transcribing ? progress : status) + "\n可用 " + free + " MB · 约14.4MB/小时");
            handler.postDelayed(this, 1500);
        }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20,12,20,12); root.setBackgroundColor(Color.rgb(246,248,250));
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("随身语音日记"); title.setTextSize(26); title.setTextColor(Color.rgb(15,23,42)); root.addView(title);
        TextView tip = new TextView(this);
        tip.setText("全天录音 → 停止后离线转写 → 时间线逐字稿\n声音仅保存在本机。请让谈话参与者知悉录音。先短录并回听确认人声。重启后需手动继续录音。"); root.addView(tip);
        state = new TextView(this); state.setTextSize(17); root.addView(state);
        LinearLayout recordRow = row(root);
        button(recordRow,"开始录音", () -> {
            if (RecordService.transcribing) { toast("请等待转写完成"); return; }
            stopPlayback();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);
            else startForegroundService(new Intent(this,RecordService.class));
        });
        button(recordRow,"停止录音", () -> {
            if (RecordService.recording) startService(new Intent(this,RecordService.class).setAction("STOP"));
            handler.postDelayed(this::reload,500);
        });
        LinearLayout processRow = row(root);
        button(processRow,"离线转写待处理录音", this::transcribe);
        button(processRow,"导出逐字稿 TXT", () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,"语音日记-逐字稿.txt");
            try { startActivityForResult(i,2); } catch (ActivityNotFoundException e) { toast("系统无文件选择器；逐字稿仍保存在应用内"); }
        });
        LinearLayout toolsRow = row(root);
        button(toolsRow,"刷新时间线", this::reload);
        button(toolsRow,"停止回听", this::stopPlayback);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list);
        text = new TextView(this); text.setTextIsSelectable(true); text.setTextSize(17); root.addView(text);
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); parent.addView(row); return row;
    }
    private void button(LinearLayout parent,String label,Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run());
        if (label.equals("开始录音")) { b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(15,118,110))); b.setTextColor(Color.WHITE); }
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) parent.addView(b,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
        else parent.addView(b);
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void reload() {
        if (destroyed) return;
        list.removeAllViews();
        String previousDay = "";
        for (File file : Transcriber.recordings(this)) {
            String label = RecordService.stamp(Long.parseLong(file.getName().replace(".m4a","")));
            String day = label.substring(0,10);
            if (!day.equals(previousDay)) {
                TextView heading = new TextView(this); heading.setText(day + " · 录音时间线"); heading.setTextSize(21);
                heading.setTextColor(Color.rgb(15,23,42)); heading.setPadding(0,18,0,6); list.addView(heading); previousDay = day;
            }
            button(list,"回听 " + label + " · " + file.length()/1024 + "KB", () -> {
                if (RecordService.recording) { toast("请先停止录音再回听"); return; }
                stopPlayback();
                try {
                    player = new MediaPlayer(); player.setDataSource(file.getAbsolutePath()); player.prepare(); player.start();
                    player.setOnCompletionListener(p -> stopPlayback());
                } catch (Exception e) { stopPlayback(); toast("无法回听：录音可能未正常结束"); }
            });
            TextView transcript = new TextView(this); transcript.setTextIsSelectable(true); transcript.setTextSize(17);
            transcript.setPadding(10,4,10,12); list.addView(transcript);
            try {
                File output = new File(file + ".txt"), error = new File(file + ".error");
                if (output.exists()) transcript.setText(new String(java.nio.file.Files.readAllBytes(output.toPath()),StandardCharsets.UTF_8));
                else if (error.exists()) transcript.setText("转写失败，可重试：" + new String(java.nio.file.Files.readAllBytes(error.toPath()),StandardCharsets.UTF_8));
                else transcript.setText("待转写 · 停止录音后点击离线转写");
            } catch (IOException e) { transcript.setText("无法读取逐字稿："+e.getMessage()); }
        }
        text.setText(list.getChildCount()==0 ? "还没有录音。先录一小段并回听，确认人声清晰。" : "自动转写可能有错漏，请以原录音为准。");
    }
    private void transcribe() {
        if (RecordService.recording) { toast("请先停止录音"); return; }
        if (RecordService.transcribing) return;
        stopPlayback(); RecordService.transcribing = true;
        Context context = getApplicationContext();
        new Thread(() -> {
            PowerManager.WakeLock wake = ((PowerManager)context.getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"diary:transcribe");
            wake.acquire(24L*3600*1000);
            try { Transcriber.run(context,value -> progress=value); }
            catch (Exception e) { progress="转写失败："+e.getClass().getSimpleName()+" "+e.getMessage(); }
            finally {
                if (wake.isHeld()) wake.release();
                RecordService.status=progress; RecordService.transcribing=false;
                handler.post(() -> { if (!destroyed) reload(); });
            }
        },"diary-transcriber").start();
        toast("转写中，请保持应用在前台；已完成分段会保存，重试可继续");
    }
    private void stopPlayback() { if (player!=null) { player.release(); player=null; } }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); reload(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { destroyed=true; stopPlayback(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if (request==1 && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)
            startForegroundService(new Intent(this,RecordService.class));
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==2 && result==RESULT_OK && data!=null && data.getData()!=null) {
            try (OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null) throw new IOException("无法打开目标文件");
                out.write(Transcriber.timeline(this).getBytes(StandardCharsets.UTF_8)); toast("逐字稿已导出");
            } catch(Exception e) { toast("导出失败："+e.getMessage()); }
        }
    }
}
