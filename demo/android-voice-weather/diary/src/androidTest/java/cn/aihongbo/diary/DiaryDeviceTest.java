package cn.aihongbo.diary;

import android.content.*;
import android.media.*;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

/** Authorized 15-second local microphone test. Never uploads or deletes recordings. */
public final class DiaryDeviceTest {
    @Test public void captureRolloverDecodeAndTranscribe() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("已有录音，不应打断", RecordService.recording);
        long start = System.currentTimeMillis();
        try {
            // Smaller segments exercise the platform handoff inside the authorized short recording.
            c.startForegroundService(new Intent(c, RecordService.class).putExtra("segment_bytes", 24000L));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!RecordService.recording && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertTrue(RecordService.status, RecordService.recording);
            SystemClock.sleep(15000);
        } finally {
            c.startService(new Intent(c, RecordService.class).setAction("STOP"));
        }
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (RecordService.recording && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        // stop() runs on the main thread; wait for its finalized status as well.
        SystemClock.sleep(1000);
        assertFalse(RecordService.status, RecordService.recording);
        int segments = 0; long durationUs = 0;
        for (File f : Transcriber.recordings(c)) {
            if (Long.parseLong(f.getName().replace(".m4a", "")) < start) continue;
            MediaExtractor e = new MediaExtractor();
            try {
                e.setDataSource(f.getAbsolutePath()); assertEquals(1, e.getTrackCount());
                MediaFormat format = e.getTrackFormat(0);
                assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME));
                assertEquals(16000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                durationUs += format.getLong(MediaFormat.KEY_DURATION); segments++;
                assertTrue(new File(f + ".start").isFile());
            } finally { e.release(); }
        }
        assertTrue("应切换至少两个分段，实际=" + segments, segments >= 2);
        assertTrue("总音轨时长应约15秒，实际微秒=" + durationUs, durationUs > 14000000 && durationUs < 17000000);
        Transcriber.run(c, value -> android.util.Log.i("DiaryTest", value));
        for (File f : Transcriber.recordings(c)) {
            assertTrue("每段都应保存转写结果（允许无识别文字）", new File(f + ".txt").isFile());
            assertFalse("完成后不应留下临时逐字稿", new File(f + ".txt.part").exists());
        }
        assertTrue(Transcriber.timeline(c).contains("时间线逐字稿"));
        android.util.Log.i("DiaryTest", "segments=" + segments + " duration_us=" + durationUs);
    }
    @Test public void knownAacSpeechProducesText() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(c.getCacheDir(), "known-speech-test"); directory.mkdirs();
        File audio = new File(directory, "1000.m4a");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("known-speech.m4a");
             OutputStream out = new FileOutputStream(audio)) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        }
        File output = new File(directory, "known.txt");
        try (org.vosk.Model model = new org.vosk.Model(Transcriber.prepareModel(c).getAbsolutePath())) {
            Transcriber.decode(model, audio, output);
            File broken = new File(directory, "2000.m4a");
            try (FileOutputStream invalid = new FileOutputStream(broken)) { invalid.write(new byte[]{0,1,2}); }
            File brokenOutput = new File(directory, "broken.txt");
            try {
                Transcriber.decode(model, broken, brokenOutput);
                fail("损坏文件不应标记为成功");
            } catch (IOException expected) { assertFalse(brokenOutput.exists()); }
        }
        String result = new String(java.nio.file.Files.readAllBytes(output.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("清晰文件应识别出文字：" + result, result.contains("未识别到文字"));
        assertTrue("应识别出天气主题：" + result, result.replace(" ", "").contains("天气"));
        android.util.Log.i("DiaryTest", "known speech result=" + result);
    }
    @Test public void preserveWordTimestamps() throws Exception {
        String line = Transcriber.resultLine("{\"text\":\"今天 记录\",\"result\":[{\"start\":1.25,\"end\":1.8},{\"start\":2.0,\"end\":2.5}]}", 0);
        assertTrue(line.contains(RecordService.stamp(1250)));
        assertTrue(line.contains(RecordService.stamp(2500)));
        assertTrue(line.endsWith("今天 记录\n"));
        assertEquals("", Transcriber.resultLine("{\"text\":\"\"}", 0));
    }
}
