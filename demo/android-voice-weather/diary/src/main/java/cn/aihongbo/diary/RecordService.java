package cn.aihongbo.diary;

import android.app.*;
import android.content.*;
import android.media.MediaRecorder;
import android.os.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** One foreground recorder; MPEG4 rollover uses the API 26 next-file handoff. */
public final class RecordService extends Service {
    public static volatile boolean recording, transcribing;
    public static volatile String status = "尚未开始录音";
    private MediaRecorder recorder;
    private PowerManager.WakeLock wake;
    private File current, next;
    private long sessionStart;
    private void markStart(File file) throws IOException {
        try (FileWriter w = new FileWriter(new File(file + ".start"))) { w.write(Long.toString(System.currentTimeMillis())); }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    static File directory(Context c) { File d = new File(c.getFilesDir(), "diary"); d.mkdirs(); return d; }
    static String stamp(long time) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(time)); }
    private File allocate() {
        long time = System.currentTimeMillis(); File file;
        do { file = new File(directory(this), time++ + ".m4a"); } while (file.exists());
        return file;
    }
    private void note(String text) {
        status = text;
        getSharedPreferences("diary", MODE_PRIVATE).edit().putString("last_event", text).apply();
        try (FileWriter w = new FileWriter(new File(directory(this), "events.txt"), true)) {
            w.write(stamp(System.currentTimeMillis()) + " " + text + "\n");
        } catch (IOException ignored) { status = text + "（事件日志写入失败）"; }
    }
    private final Runnable monitor = new Runnable() {
        public void run() {
            if (!recording) return;
            if (directory(RecordService.this).getUsableSpace() < 100L * 1024 * 1024) {
                finish("空间不足100MB，已停止并保留录音"); return;
            }
            if (SystemClock.elapsedRealtime() - sessionStart >= 24L * 3600 * 1000) {
                finish("已完成24小时录音"); return;
            }
            handler.postDelayed(this, 2000);
        }
    };
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "STOP".equals(intent.getAction())) { finish("录音已停止"); return START_NOT_STICKY; }
        if (recording) return START_NOT_STICKY;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("diary", "随身录音", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "diary").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("随身语音日记 · 正在录音").setContentText("声音仅保存在本机，点击应用查看")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "停止", stop).build()).build());
        if (transcribing) { finish("正在转写，请完成后再录音"); return START_NOT_STICKY; }
        if (directory(this).getUsableSpace() < 150L * 1024 * 1024) {
            finish("可用空间不足150MB，无法开始录音"); return START_NOT_STICKY;
        }
        try {
            wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diary:record");
            wake.acquire(24L * 3600 * 1000 + 60000);
            current = allocate();
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000); recorder.setAudioChannels(1); recorder.setAudioEncodingBitRate(32000);
            recorder.setOutputFile(current.getAbsolutePath());
            long segmentBytes = intent == null ? 1200000 : intent.getLongExtra("segment_bytes", 1200000);
            recorder.setMaxFileSize(Math.max(16000, Math.min(1200000, segmentBytes))); // approximately five minutes at 32 kbit/s
            recorder.setOnErrorListener((r, what, extra) -> finish("录音异常 " + what + "/" + extra));
            recorder.setOnInfoListener((r, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING) {
                    try { next = allocate(); r.setNextOutputFile(next); }
                    catch (Exception e) { finish("分段切换失败：" + e.getClass().getSimpleName()); }
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED) {
                    current = next; next = null;
                    try { markStart(current); } catch (IOException e) { finish("分段时间保存失败"); return; }
                    note("录音中，已切换新分段");
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    finish("分段达到限制，录音已停止，请检查设备编码器");
                }
            });
            recorder.prepare(); recorder.start(); markStart(current); sessionStart = SystemClock.elapsedRealtime(); recording = true;
            note("录音中 · AAC 16kHz 单声道，最长24小时"); handler.post(monitor);
        } catch (Exception e) { finish("启动失败：" + e.getClass().getSimpleName() + " " + e.getMessage()); }
        return START_NOT_STICKY;
    }
    private void finish(String reason) {
        recording = false; handler.removeCallbacks(monitor);
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException e) { reason += "；最后一段可能不完整"; }
            recorder.release(); recorder = null;
        }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null; note(reason); stopForeground(true); stopSelf();
    }
    @Override public void onDestroy() { if (recorder != null) finish("服务结束，录音已停止"); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
