package com.zhb.petscreen;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.os.Process;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Records an exact number of mono PCM samples and wraps them in a WAV file. */
final class PcmRecorder {
    interface Listener { void finished(Result result); }
    static final int SAMPLE_RATE = 16000;
    static final int BYTES_PER_SAMPLE = 2;

    static final class Result {
        final boolean success;
        final long samples;
        final long elapsedMs;
        final int peak;
        final int routeType;
        final String routeName;
        final String error;
        Result(boolean success, long samples, long elapsedMs, int peak, int routeType, String routeName, String error) {
            this.success = success;
            this.samples = samples;
            this.elapsedMs = elapsedMs;
            this.peak = peak;
            this.routeType = routeType;
            this.routeName = routeName;
            this.error = error;
        }
        long durationMs() { return samples * 1000L / SAMPLE_RATE; }
    }

    final File output;
    final int durationMs;
    final int audioSource;
    volatile boolean stopping;
    volatile AudioRecord recorder;
    volatile long samples;
    volatile int peak;
    Thread worker;

    PcmRecorder(File output, int durationMs) {
        this(output, durationMs, MediaRecorder.AudioSource.VOICE_RECOGNITION);
    }

    PcmRecorder(File output, int durationMs, int audioSource) {
        this.output = output;
        this.durationMs = durationMs;
        this.audioSource = audioSource;
    }

    void start(Listener listener) throws IOException {
        if (worker != null) throw new IllegalStateException("already_started");
        int minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException("unsupported_audio_format");
        int bufferBytes = Math.max(minimum, 4096);
        AudioRecord audio = new AudioRecord(
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes * 2);
        if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
            audio.release();
            throw new IOException("audio_record_init_failed");
        }
        recorder = audio;
        worker = new Thread(() -> record(audio, bufferBytes, listener), "pet-pcm-recorder");
        worker.start();
    }

    void stop() {
        stopping = true;
        AudioRecord audio = recorder;
        if (audio != null) try { audio.stop(); } catch (IllegalStateException ignored) {}
    }

    boolean isRunning() { return worker != null && worker.isAlive(); }

    private void record(AudioRecord audio, int bufferBytes, Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long started = android.os.SystemClock.elapsedRealtime();
        long targetSamples = SAMPLE_RATE * (long) durationMs / 1000L;
        String error = "";
        boolean success = false;
        int routeType = AudioDeviceInfo.TYPE_UNKNOWN;
        String routeName = "unknown";
        output.delete();
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            out.write(new byte[44]);
            byte[] buffer = new byte[bufferBytes - bufferBytes % BYTES_PER_SAMPLE];
            audio.startRecording();
            AudioDeviceInfo route = audio.getRoutedDevice();
            if (route != null) { routeType = route.getType(); routeName = String.valueOf(route.getProductName()); }
            while (!stopping && samples < targetSamples) {
                int wanted = (int) Math.min(buffer.length, (targetSamples - samples) * BYTES_PER_SAMPLE);
                int count = audio.read(buffer, 0, wanted);
                if (count < 0) throw new IOException("audio_read_" + count);
                if (count == 0) continue;
                count -= count % BYTES_PER_SAMPLE;
                out.write(buffer, 0, count);
                for (int i = 0; i < count; i += 2) {
                    int value = (short) ((buffer[i] & 255) | (buffer[i + 1] << 8));
                    peak = Math.max(peak, Math.abs(value));
                }
                samples += count / BYTES_PER_SAMPLE;
            }
            out.flush();
            success = samples >= SAMPLE_RATE / 2;
        } catch (Exception e) {
            error = e instanceof IOException ? e.getMessage() : e.getClass().getSimpleName();
        } finally {
            try { if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop(); }
            catch (IllegalStateException ignored) {}
            audio.release();
            recorder = null;
            if (success) {
                try { writeHeader(output, samples); } catch (IOException e) { success = false; error = "wav_header"; }
            }
            if (!success) output.delete();
            listener.finished(new Result(success, samples,
                android.os.SystemClock.elapsedRealtime() - started, peak, routeType, routeName, error == null ? "" : error));
        }
    }

    static void writeHeader(File file, long samples) throws IOException {
        long dataBytes = samples * BYTES_PER_SAMPLE;
        try (RandomAccessFile wav = new RandomAccessFile(file, "rw")) {
            wav.seek(0);
            wav.writeBytes("RIFF"); writeLe32(wav, 36 + dataBytes); wav.writeBytes("WAVE");
            wav.writeBytes("fmt "); writeLe32(wav, 16); writeLe16(wav, 1); writeLe16(wav, 1);
            writeLe32(wav, SAMPLE_RATE); writeLe32(wav, SAMPLE_RATE * BYTES_PER_SAMPLE);
            writeLe16(wav, BYTES_PER_SAMPLE); writeLe16(wav, 16);
            wav.writeBytes("data"); writeLe32(wav, dataBytes);
        }
    }

    static void writeLe16(RandomAccessFile out, long value) throws IOException {
        out.write((int) value & 255); out.write((int) (value >> 8) & 255);
    }
    static void writeLe32(RandomAccessFile out, long value) throws IOException {
        out.write((int) value & 255); out.write((int) (value >> 8) & 255);
        out.write((int) (value >> 16) & 255); out.write((int) (value >> 24) & 255);
    }
}
