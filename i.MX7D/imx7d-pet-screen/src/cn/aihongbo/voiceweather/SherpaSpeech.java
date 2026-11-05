package cn.aihongbo.voiceweather;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** VAD with one offline recognizer. Exact short wake utterances precede the staged dialogue. */
final class SherpaSpeech implements VoiceController.Input {
    static final String WAKE_PHRASE=WakeUtterance.PHRASE;
    private final Context context;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private volatile Session current;
    private volatile boolean closed;
    private long handle;
    private final int commandTimeoutMs;
    SherpaSpeech(Context context){this(context,20000);}
    // Package-private duration override for signed microphone diagnostics only.
    SherpaSpeech(Context context,int commandTimeoutMs){this.context=context.getApplicationContext();this.commandTimeoutMs=commandTimeoutMs;}
    public void start(BoardSpeech.Mode mode,BoardSpeech.Listener listener){
        stop();Session session=new Session(mode,listener);current=session;decoder.execute(()->run(session));
    }
    public void stop(){Session session=current;current=null;if(session!=null)session.stop();}
    static File prepare(Context context)throws Exception{
        File root=context.getExternalFilesDir("speech-models");
        if(root==null)throw new IOException("Model storage unavailable");
        JSONObject manifest;try(InputStream in=context.getAssets().open("sherpa-models.json")){manifest=new JSONObject(new String(read(in),"UTF-8"));}
        Iterator<String> names=manifest.keys();
        while(names.hasNext()){
            String name=names.next();if(!name.matches("(asr|kws|vad)/[a-zA-Z0-9_.-]+"))throw new IOException("Invalid model path");
            JSONObject expected=manifest.getJSONObject(name);File file=new File(root,name);
            if(!file.isFile()){
                if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("Model directory");
                File part=new File(file+".part");
                try(InputStream in=context.getAssets().open("sherpa-models/"+name);OutputStream out=new FileOutputStream(part)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                if(!part.renameTo(file))throw new IOException("Model rename");
            }
            if(file.length()!=expected.getLong("bytes"))throw new IOException("Model size: "+name);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!hash.toString().equals(expected.getString("sha256")))throw new IOException("Model checksum: "+name);
        }
        return root;
    }
    private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);return bytes.toByteArray();}
    private void run(Session s){
        try{
            if(closed||s.stopped)return;
            if(handle==0){long start=SystemClock.elapsedRealtime();handle=SherpaNative.create(prepare(context).getAbsolutePath());if(handle==0)throw new IOException("Native initialization");android.util.Log.i("AluoSherpa","models_ready_ms="+(SystemClock.elapsedRealtime()-start));}
            if(closed||s.stopped)return;
            SherpaNative.reset(handle,s.mode==BoardSpeech.Mode.WAKE);
            int minimum=AudioRecord.getMinBufferSize(44100,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IOException("44.1kHz stereo input unavailable");
            synchronized(s){
                if(s.stopped)return;
                s.recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,44100,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(352800,minimum));
                if(s.recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("AudioRecord initialization");
                s.recorder.startRecording();
            }
            AudioDeviceInfo route=s.recorder.getRoutedDevice();
            android.util.Log.i("AluoSherpa","ready mode="+s.mode+" capture=44100/stereo model=16000/mono route="+(route==null?"unknown":route.getId()+"/"+route.getType()+"/"+route.getProductName()));
            s.capture=new Thread(()->capture(s),"aluo-sherpa-capture");s.capture.start();s.listener.ready();
            long started=SystemClock.elapsedRealtime(),speechAt=0,lastReport=started;
            while(!closed&&!s.stopped){
                Frame frame=s.queue.poll(100,TimeUnit.MILLISECONDS);
                if(frame==null){if(s.captureDone)break;continue;}
                String value=SherpaNative.acceptStereo(handle,frame.samples,frame.samples.length);s.decoded+=frame.samples.length;
                if(SystemClock.elapsedRealtime()-lastReport>=10000){lastReport=SystemClock.elapsedRealtime();android.util.Log.i("AluoSherpa","mode="+s.mode+" captured="+s.captured+" decoded="+s.decoded+" queue="+s.queue.size());}
                if(s.stopped||closed)break;
                if(value!=null){
                    JSONObject event=new JSONObject(value);
                    if(speechAt==0&&event.optBoolean("speech")){speechAt=frame.received;if(s.mode!=BoardSpeech.Mode.WAKE)s.listener.speechStarted(speechAt);}
                    if(event.optBoolean("segment")){
                        if(s.mode==BoardSpeech.Mode.WAKE&&event.optInt("samples")>16000*2.5){SherpaNative.reset(handle,true);speechAt=0;continue;}
                        if(s.mode!=BoardSpeech.Mode.WAKE){s.haltCapture();join(s);}
                        if(s.mode==BoardSpeech.Mode.CONFIRM&&event.optInt("samples")>16000*8)throw new IOException("Confirmation too long");
                        long tick=SystemClock.elapsedRealtime();String raw=SherpaNative.transcribe(handle);
                        if(s.stopped||closed)break;
                        if(raw==null)throw new IOException("No native result");String text=new JSONObject(raw).optString("text","").trim();
                        android.util.Log.i("AluoSherpa","final mode="+s.mode+" asr_ms="+(SystemClock.elapsedRealtime()-tick)+" chars="+text.length());
                        if(!text.isEmpty())s.listener.transcript(text);
                        if(s.mode==BoardSpeech.Mode.WAKE){
                            if(WakeUtterance.matches(text)){android.util.Log.i("AluoSherpa","wake_utterance_matched");s.stop();s.listener.wake("","",speechAt==0?frame.received:speechAt);break;}
                            SherpaNative.reset(handle,true);speechAt=0;
                        }else{if(!text.isEmpty())s.listener.result(text,speechAt==0?frame.received:speechAt);s.stop();break;}
                    }
                }
                if(s.mode!=BoardSpeech.Mode.WAKE&&speechAt==0&&SystemClock.elapsedRealtime()-started>commandTimeoutMs){s.stop();break;}
            }
        }catch(Exception|LinkageError failure){if(!closed&&!s.stopped){android.util.Log.e("AluoSherpa","input failed",failure);s.listener.error("本地语音暂不可用："+failure.getClass().getSimpleName());}}
        finally{s.stop();join(s);synchronized(s){if(s.recorder!=null){s.recorder.release();s.recorder=null;}}if(!closed)s.listener.ended();}
    }
    private void capture(Session s){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        long lastLevel=SystemClock.elapsedRealtime(),count=0;double energy=0;int peak=0;
        try{while(!closed&&!s.stopped&&!s.captureEnd){short[] samples=new short[8820];int n=s.recorder.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(s.stopped||s.captureEnd)break;if(n<=0||n%2!=0)throw new IOException("Audio read n="+n+" state="+s.recorder.getRecordingState());s.captured+=n;
            for(int i=0;i<n;i++){energy+=(double)samples[i]*samples[i];peak=Math.max(peak,Math.abs((int)samples[i]));}count+=n;
            if(SystemClock.elapsedRealtime()-lastLevel>=5000){android.util.Log.i("AluoLevel","mode="+s.mode+" rms="+Math.round(Math.sqrt(energy/count))+" peak="+peak);lastLevel=SystemClock.elapsedRealtime();energy=0;count=0;peak=0;}
            if(!s.queue.offer(new Frame(n==samples.length?samples:Arrays.copyOf(samples,n),SystemClock.elapsedRealtime())))throw new IOException("Decode queue overflow");}}
        catch(Exception failure){if(!closed&&!s.stopped&&!s.captureEnd){android.util.Log.e("AluoSherpa","capture failed",failure);s.listener.error("收音或识别积压，正在重新监听");s.stop();}}
        finally{s.captureDone=true;}
    }
    private static void join(Session s){if(s.capture!=null)try{s.capture.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    public void close(){closed=true;stop();decoder.execute(()->{if(handle!=0){SherpaNative.destroy(handle);handle=0;}});decoder.shutdown();}
    private static final class Frame{final short[] samples;final long received;Frame(short[] samples,long received){this.samples=samples;this.received=received;}}
    private static final class Session{
        final BoardSpeech.Mode mode;final BoardSpeech.Listener listener;final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(64);
        volatile boolean stopped,captureEnd,captureDone;volatile long captured,decoded;AudioRecord recorder;Thread capture;
        Session(BoardSpeech.Mode mode,BoardSpeech.Listener listener){this.mode=mode;this.listener=listener;}
        synchronized void haltCapture(){captureEnd=true;if(recorder!=null)try{recorder.stop();}catch(IllegalStateException ignored){}}
        synchronized void stop(){stopped=true;haltCapture();}
    }
}
