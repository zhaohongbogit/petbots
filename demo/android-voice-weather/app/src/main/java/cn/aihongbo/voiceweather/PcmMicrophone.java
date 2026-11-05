package cn.aihongbo.voiceweather;

import android.media.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Continuous 80ms PCM -> Vosk; sentence WAV files are saved independently of Qwen. */
final class PcmMicrophone implements AutoCloseable {
    interface Listener extends StreamingTranscriber.Listener {void error(String reason);void stopped(String tail);default void recordingError(String reason){android.util.Log.w("SentenceWavRecorder",reason);}}
    private final java.io.File recordingsDirectory;
    private final AudioRecord recorder;
    private final BooleanSupplier accepting,playback;
    private final VoskAsr asr;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    PcmMicrophone(android.content.Context context,VoskAsr asr,BooleanSupplier accepting,BooleanSupplier playback,Listener listener) {
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("未获得麦克风权限");
        this.asr=asr;this.accepting=accepting;this.playback=playback;this.listener=listener;
        recordingsDirectory=new java.io.File(context.getFilesDir(),"recordings");
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IllegalStateException("设备不支持 16kHz 单声道录音");
        // Two seconds of device buffering absorbs short native decoder stalls; no whole-utterance wait.
        recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,64000));
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();throw new IllegalStateException("麦克风初始化失败");}
    }
    void start(){running=true;thread=new Thread(this::capture,"vosk-streaming-microphone");thread.start();}
    private void capture(){
        String tail="";
        try(SentenceWavRecorder recording=new SentenceWavRecorder(recordingsDirectory,SentenceWavRecorder.NO_UPLOAD,listener::recordingError);
            StreamingTranscriber transcriber=new StreamingTranscriber(asr.openStream(),listener,recording)) {
            if(!running)return;
            recorder.startRecording();short[] frame=new short[1280];
            while(running && accepting.getAsBoolean()) {
                int count=recorder.read(frame,0,frame.length);
                if(!running)break;
                if(count<=0)throw new IllegalStateException("麦克风采样失败："+count);
                transcriber.accept(count==frame.length?frame:Arrays.copyOf(frame,count),android.os.SystemClock.elapsedRealtime(),playback.getAsBoolean());
            }
            tail=transcriber.discard();
        }catch(Exception error){if(running)listener.error("流式识别失败："+error.getMessage());}
        finally {
            running=false;try{recorder.stop();}catch(IllegalStateException ignored){}recorder.release();listener.stopped(tail);
        }
    }
    @Override public void close(){running=false;try{recorder.stop();}catch(IllegalStateException ignored){}if(thread==null){recorder.release();listener.stopped("");}}
}
