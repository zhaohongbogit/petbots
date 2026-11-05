package cn.aihongbo.voiceweather;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Source-thread PCM buffering; completed sentences are written by a bounded background worker. */
final class SentenceWavRecorder implements AutoCloseable {
    /** Called on the file worker only after a complete WAV is available. No upload is configured. */
    interface UploadHook { void onFileReady(File wav) throws Exception; }
    static final UploadHook NO_UPLOAD = wav -> {};
    private static final Executor FILE_WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "sentence-wav-writer");
                thread.setDaemon(true);
                return thread;
            });
    private static final int SAMPLE_RATE = 16000;
    private final short[] preRoll = new short[SAMPLE_RATE / 2];
    private final File directory;
    private final Executor worker;
    private final UploadHook uploadHook;
    private final Consumer<String> errors;
    private final String session = System.currentTimeMillis() + "-" + UUID.randomUUID();
    private int prePosition, preCount;
    private long sentenceId;
    private ByteArrayOutputStream sentence;
    private boolean closed;

    SentenceWavRecorder(File directory, UploadHook uploadHook, Consumer<String> errors) {
        this(directory, uploadHook, errors, FILE_WORKER);
    }

    SentenceWavRecorder(File directory, UploadHook uploadHook, Consumer<String> errors, Executor worker) {
        this.directory = directory;
        this.uploadHook = uploadHook;
        this.errors = errors;
        this.worker = worker;
    }

    void accept(short[] pcm) {
        if (closed) return;
        if (sentence != null) {
            append(pcm, 0, pcm.length);
        } else {
            for (short sample : pcm) {
                preRoll[prePosition] = sample;
                prePosition = (prePosition + 1) % preRoll.length;
                preCount = Math.min(preCount + 1, preRoll.length);
            }
        }
    }

    void start(long id) {
        if (closed || sentence != null) return;
        sentenceId = id;
        sentence = new ByteArrayOutputStream();
        int first = (prePosition - preCount + preRoll.length) % preRoll.length;
        int count = Math.min(preCount, preRoll.length - first);
        append(preRoll, first, count);
        append(preRoll, 0, preCount - count);
        preCount = prePosition = 0;
    }

    private void append(short[] pcm, int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            sentence.write(pcm[i] & 255);
            sentence.write((pcm[i] >>> 8) & 255);
        }
    }

    void complete() {
        if (sentence == null) return;
        byte[] pcm = sentence.toByteArray();
        sentence = null;
        if (pcm.length == 0) return;
        File wav = new File(directory, session + "-" + sentenceId + ".wav");
        try {
            worker.execute(() -> save(wav, pcm));
        } catch (java.util.concurrent.RejectedExecutionException error) {
            errors.accept("录音保存队列已满，本段未保存");
        }
    }

    private void save(File wav, byte[] pcm) {
        File temporary = new File(wav.getPath() + ".part");
        try {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                throw new IOException("无法创建录音目录");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(header(pcm.length));
                output.write(pcm);
            }
            Files.move(temporary.toPath(), wav.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) {}
            errors.accept("录音保存失败：" + error.getMessage());
            return;
        }
        try {
            uploadHook.onFileReady(wav);
        } catch (Exception error) {
            errors.accept("录音已保存，上传预留回调失败：" + error.getMessage());
        }
    }

    private static byte[] header(int size) {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[]{'R','I','F','F'}).putInt(36 + size);
        bytes.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        bytes.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        bytes.putShort((short) 2).putShort((short) 16);
        bytes.put(new byte[]{'d','a','t','a'}).putInt(size);
        return bytes.array();
    }

    @Override public void close() {
        if (closed) return;
        complete(); // Preserve an unfinished live sentence without dispatching semantic actions.
        closed = true;
        preCount = prePosition = 0;
    }
}
