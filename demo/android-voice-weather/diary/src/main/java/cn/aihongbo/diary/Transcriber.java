package cn.aihongbo.diary;

import android.content.Context;
import android.media.*;
import org.vosk.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Transcriber {
    interface Progress { void update(String text); }
    static File[] recordings(Context c) {
        File[] files = RecordService.directory(c).listFiles((d, n) -> n.endsWith(".m4a"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName)); return files;
    }
    private static void copy(Context c, String asset, File target) throws IOException {
        String[] children = c.getAssets().list(asset);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法创建模型目录");
            for (String child : children) copy(c, asset + "/" + child, new File(target, child));
        } else if (!target.exists()) {
            File part = new File(target + ".part");
            try (InputStream in = c.getAssets().open(asset); OutputStream out = new FileOutputStream(part)) {
                byte[] bytes = new byte[32768]; int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            if (!part.renameTo(target)) throw new IOException("模型文件保存失败");
        }
    }
    static String resultLine(String json, long base) throws JSONException {
        JSONObject result = new JSONObject(json);
        String text = result.optString("text", "").trim();
        if (text.isEmpty()) return "";
        JSONArray words = result.optJSONArray("result");
        double start = 0, end = 0;
        if (words != null && words.length() > 0) {
            start = words.getJSONObject(0).optDouble("start", 0);
            end = words.getJSONObject(words.length() - 1).optDouble("end", start);
        }
        return "[" + RecordService.stamp(base + (long)(start * 1000)) + " → "
            + RecordService.stamp(base + (long)(end * 1000)) + "] " + text + "\n";
    }
    static File prepareModel(Context c) throws IOException {
        File modelPath = new File(c.getFilesDir(), "diary-model-cn");
        copy(c, "model-cn", modelPath);
        return modelPath;
    }
    static void run(Context c, Progress progress) throws Exception {
        progress.update("准备离线中文模型…");
        File modelPath = prepareModel(c);
        try (Model model = new Model(modelPath.getAbsolutePath())) {
            File[] files = recordings(c); int index = 0, failed = 0;
            for (File audio : files) {
                index++;
                File output = new File(audio + ".txt");
                if (output.exists()) continue;
                progress.update("转写 " + index + "/" + files.length + " · " + audio.getName());
                try { decode(model, audio, output); }
                catch (Exception e) {
                    if (e instanceof InterruptedException) throw e;
                    failed++;
                    try (Writer error = new OutputStreamWriter(new FileOutputStream(new File(audio + ".error")), StandardCharsets.UTF_8)) {
                        error.write(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }
            progress.update("转写完成 · 失败分段 " + failed + "；可重试。未识别到文字的分段请回听确认。");
        }
    }
    static void decode(Model model, File audio, File output) throws Exception {
        long base = Long.parseLong(audio.getName().replace(".m4a", ""));
        File time = new File(audio + ".start");
        if (time.exists()) base = Long.parseLong(new String(java.nio.file.Files.readAllBytes(time.toPath()), StandardCharsets.UTF_8).trim());
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        File part = new File(output + ".part");
        try (Recognizer recognizer = new Recognizer(model, 16000);
             Writer writer = new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8)) {
            recognizer.setWords(true);
            extractor.setDataSource(audio.getAbsolutePath());
            int track = -1;
            for (int t = 0; t < extractor.getTrackCount(); t++)
                if (extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME).startsWith("audio/")) { track = t; break; }
            if (track < 0) throw new IOException("文件没有音轨：" + audio.getName());
            extractor.selectTrack(track); MediaFormat format = extractor.getTrackFormat(track);
            if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16000 || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                throw new IOException("只支持16kHz单声道录音");
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0); codec.start();
            boolean inputEnd = false, outputEnd = false, any = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastProgress = android.os.SystemClock.elapsedRealtime();
            while (!outputEnd) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (android.os.SystemClock.elapsedRealtime() - lastProgress > 30000) throw new IOException("解码器30秒无进展");
                if (!inputEnd) {
                    int slot = codec.dequeueInputBuffer(10000);
                    if (slot >= 0) {
                        ByteBuffer in = codec.getInputBuffer(slot); in.clear(); int count = extractor.readSampleData(in, 0);
                        if (count < 0) { codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true; }
                        else { codec.queueInputBuffer(slot, 0, count, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                int slot = codec.dequeueOutputBuffer(info, 10000);
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16000 || f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1
                        || (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT))
                        throw new IOException("解码输出格式不兼容");
                } else if (slot >= 0) {
                    lastProgress = android.os.SystemClock.elapsedRealtime();
                    if (info.size > 0) {
                        ByteBuffer out = codec.getOutputBuffer(slot); out.position(info.offset); out.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size]; out.get(bytes);
                        if (recognizer.acceptWaveForm(bytes, bytes.length)) {
                            String line = resultLine(recognizer.getResult(), base); writer.write(line); any |= !line.isEmpty();
                        }
                    }
                    outputEnd = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0; codec.releaseOutputBuffer(slot, false);
                }
            }
            String line = resultLine(recognizer.getFinalResult(), base); writer.write(line); any |= !line.isEmpty();
            if (!any) writer.write("[" + RecordService.stamp(base) + "] 未识别到文字，请回听原录音确认音质。\n");
        } finally {
            if (codec != null) { try { codec.stop(); } finally { codec.release(); } }
            extractor.release();
        }
        if (!part.renameTo(output)) throw new IOException("逐字稿保存失败");
    }
    static String timeline(Context c) throws IOException {
        StringBuilder text = new StringBuilder("随身语音日记 · 时间线逐字稿\n自动转写可能有错漏，请以原录音为准。\n\n");
        for (File audio : recordings(c)) {
            text.append("分段 ").append(RecordService.stamp(Long.parseLong(audio.getName().replace(".m4a", "")))).append('\n');
            File transcript = new File(audio + ".txt");
            if (transcript.exists()) text.append(new String(java.nio.file.Files.readAllBytes(transcript.toPath()), StandardCharsets.UTF_8));
            else {
                File error = new File(audio + ".error");
                if (error.exists()) text.append("转写失败，可重试：").append(new String(java.nio.file.Files.readAllBytes(error.toPath()), StandardCharsets.UTF_8)).append('\n');
                else text.append("待转写（中断或异常录音可能无法解码）\n");
            }
            text.append('\n');
        }
        return text.toString();
    }
}
