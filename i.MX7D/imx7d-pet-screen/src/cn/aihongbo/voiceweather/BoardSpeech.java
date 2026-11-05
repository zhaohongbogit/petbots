package cn.aihongbo.voiceweather;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.json.*;
import org.vosk.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One native decoder at a time; capture never waits for native decoding or UI. */
final class BoardSpeech implements VoiceController.Input {
    enum Mode { WAKE, COMMAND, CONFIRM }
    interface Listener {
        void ready(); void speechStarted(long received); void partial(String text); void result(String text,long received);
        default void transcript(String text){}
        default void wake(String raw,String command,long received){result(raw,received);}
        void error(String reason); void ended();
    }
    private final Context context;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private volatile Session current;
    private volatile boolean closed;
    private Model model;
    private Recognizer dictation;
    private WakeDecoder wakeDecoder;
    private long processedSamples;
    BoardSpeech(Context context){this.context=context.getApplicationContext();}
    public void start(Mode mode,Listener listener){
        stop();Session session=new Session(mode,listener);current=session;
        decoder.execute(()->run(session));
    }
    public void stop(){Session session=current;current=null;if(session!=null)session.stop();}
    private void run(Session session){
        try {
            if(closed||session.stopped)return;
            if(model==null){File dir=new File(context.getFilesDir(),"aluo-vosk-small-cn-0.22");prepareModel(dir);model=new Model(dir.getAbsolutePath());}
            if(closed||session.stopped)return;
            if(dictation==null){
                dictation=new Recognizer(model,16000);dictation.setWords(true);
                // Warm the one live dictation decoder once; reuse it between dialogue stages.
                for(int round=0;round<2&&!closed;round++){
                    try(DataInputStream sample=new DataInputStream(context.getAssets().open("samples/zhengzhou.pcm"))){
                        byte[] bytes=new byte[2560];int count;
                        while((count=sample.read(bytes))!=-1){
                            short[] pcm=new short[count/2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((bytes[2*i]&255)|(bytes[2*i+1]<<8));
                            dictation.acceptWaveForm(pcm,pcm.length);processedSamples+=pcm.length;
                        }
                    }
                    dictation.getFinalResult();dictation.reset();
                }
            }
            if(wakeDecoder==null){
                wakeDecoder=new WakeDecoder(model);
                byte[] sample;try(InputStream input=context.getAssets().open("samples/aluo.pcm")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);sample=bytes.toByteArray();}
                for(int round=0;round<2&&!closed;round++)wakeDecoder.detect(new JSONObject(),sample,0);
            }
            {
                Recognizer recognizer=dictation;
                if(session.stopped||closed)return;
                int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IOException("设备不支持16kHz录音");
                synchronized(session){
                    if(session.stopped)return;
                    session.recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,64000));
                    if(session.recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("麦克风初始化失败");
                    session.recorder.startRecording();
                }
                Thread capture=new Thread(()->capture(session),"aluo-capture");session.capture=capture;capture.start();session.listener.ready();
                boolean notified=false;String last="";long started=SystemClock.elapsedRealtime(),lastVoice=started;
                ByteArrayOutputStream sentence=new ByteArrayOutputStream();long segmentStart=processedSamples;long lastReport=started,lastPartialAt=0;
                while(!session.stopped&&!closed){
                    Frame frame=session.queue.poll(100,TimeUnit.MILLISECONDS);
                    if(frame==null){if(session.captureDone)break;continue;}
                    double energy=0;for(short sample:frame.pcm)energy+=(double)sample*sample;
                    if(Math.sqrt(energy/frame.pcm.length)>=350){lastVoice=frame.received;if(!notified){notified=true;session.listener.speechStarted(lastVoice);}}
                    for(short sample:frame.pcm){sentence.write(sample&255);sentence.write((sample>>>8)&255);}
                    long decodeStart=SystemClock.elapsedRealtime();boolean endpoint=recognizer.acceptWaveForm(frame.pcm,frame.pcm.length);processedSamples+=frame.pcm.length;session.decodedSamples+=frame.pcm.length;
                    if(SystemClock.elapsedRealtime()-lastReport>=5000){lastReport=SystemClock.elapsedRealtime();android.util.Log.i("AluoAudio","mode="+session.mode+" captured="+session.capturedSamples+" decoded="+session.decodedSamples+" queue="+session.queue.size());}
                    long decodedAt=SystemClock.elapsedRealtime();
                    if(decodedAt-decodeStart>500)android.util.Log.i("AluoTiming","accept_ms="+(decodedAt-decodeStart)+" mode="+session.mode);
                    if(!endpoint&&decodedAt-lastPartialAt<250)continue;
                    lastPartialAt=decodedAt;
                    JSONObject json=new JSONObject(endpoint?recognizer.getResult():recognizer.getPartialResult());
                    String text=json.optString(endpoint?"text":"partial","");
                    if(endpoint){
                        if(!text.isEmpty()){android.util.Log.i("AluoAsr","mode="+session.mode+" final="+text);session.listener.transcript(text);}
                        if(session.mode==Mode.WAKE&&(!text.isEmpty()||notified)){
                            // Reuse a warmed keyword decoder and inspect only the first two seconds of speech.
                            long wakeStart=SystemClock.elapsedRealtime();Wake match=wakeDecoder.detect(json,sentence.toByteArray(),segmentStart/16000.0);
                            android.util.Log.i("AluoTiming","wake_ms="+(SystemClock.elapsedRealtime()-wakeStart)+" detected="+match.detected);
                            if(match.detected){session.stop();session.listener.wake(text,match.command,lastVoice);break;}
                        }else if(!text.isEmpty()){
                            session.stop();session.listener.result(text,lastVoice);break;
                        }
                        sentence.reset();segmentStart=processedSamples;last="";notified=false;
                    }else if(!text.equals(last)){last=text;session.listener.partial(text);}
                    if(processedSamples-segmentStart>=16000L*20){
                        String tail=new JSONObject(recognizer.getFinalResult()).optString("text","");recognizer.reset();
                        if(!tail.isEmpty())session.listener.transcript(tail);
                        // Forced tails cannot authorize a wake or consent.
                        if(session.mode==Mode.COMMAND&&!tail.isEmpty()){session.stop();session.listener.result(tail,lastVoice);break;}
                        sentence.reset();segmentStart=processedSamples;last="";notified=false;
                    }
                    if(session.mode!=Mode.WAKE&&SystemClock.elapsedRealtime()-started>20000){session.stop();break;}

                }
            }
        }catch(Exception|LinkageError failure){if(!session.stopped&&!closed)session.listener.error("本地识别失败："+failure.getClass().getSimpleName());}
        finally{
            session.stop();
            if(session.capture!=null)try{session.capture.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            synchronized(session){if(session.recorder!=null){session.recorder.release();session.recorder=null;}}
            if(dictation!=null){dictation.getFinalResult();dictation.reset();}
            android.util.Log.i("AluoAudio","stopped mode="+session.mode+" captured="+session.capturedSamples+" decoded="+session.decodedSamples);
            if(!closed)session.listener.ended();
        }
    }
    private void capture(Session session){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try{
            while(!session.stopped&&!closed){
                short[] pcm=new short[1280];int count=session.recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(session.stopped)break;if(count<=0)throw new IOException("read");
                session.capturedSamples+=count;
                if(!session.queue.offer(new Frame(count==pcm.length?pcm:Arrays.copyOf(pcm,count),SystemClock.elapsedRealtime())))throw new IOException("queue_overflow");
            }
        }catch(Exception failure){if(!session.stopped&&!closed){session.listener.error("采集错误或识别积压，已停止；请重试");session.stop();}}
        finally{session.captureDone=true;}
    }
    static final class Wake {final boolean detected;final String command;Wake(boolean detected,String command){this.detected=detected;this.command=command;}}
    static Wake detectWake(Model model,JSONObject raw,byte[] pcm,double start)throws Exception{
        try(WakeDecoder decoder=new WakeDecoder(model)){return decoder.detect(raw,pcm,start);}
    }
    static final class WakeDecoder implements AutoCloseable {
        private final Recognizer keyword;
        private long samples;
        WakeDecoder(Model model)throws IOException{keyword=new Recognizer(model,16000,"[\"阿 洛\",\"[unk]\"]");keyword.setWords(true);}
        Wake detect(JSONObject raw,byte[] pcm,double start)throws Exception{
            JSONArray original=raw.optJSONArray("result");
            // Word times locate the beginning of actual speech, excluding arbitrarily long leading silence.
            double speech=original!=null&&original.length()>0?original.getJSONObject(0).getDouble("start"):start;
            int firstSample=Math.max(0,Math.min(pcm.length/2,(int)Math.floor((speech-start-.25)*16000)));
            int from=firstSample*2,to=Math.min(pcm.length,from+16000*2*2);
            double keywordBase=samples/16000.0,sourceStart=start+firstSample/16000.0;
            JSONArray words=new JSONArray();
            try{
                for(int offset=from;offset<to;offset+=2560){byte[] frame=Arrays.copyOfRange(pcm,offset,Math.min(to,offset+2560));samples+=frame.length/2;if(keyword.acceptWaveForm(frame,frame.length))appendWords(words,new JSONObject(keyword.getResult()));}
                appendWords(words,new JSONObject(keyword.getFinalResult()));
            }finally{keyword.reset();}
            JSONObject wake=new JSONObject().put("result",words);if(!validWake(wake))return new Wake(false,"");
            double first=words.getJSONObject(0).getDouble("start")-keywordBase+sourceStart,end=words.getJSONObject(1).getDouble("end")-keywordBase+sourceStart;
            if(original!=null&&original.length()>0&&original.getJSONObject(0).getDouble("start")+.15<first)return new Wake(false,"");
            StringBuilder command=new StringBuilder();
            if(original!=null)for(int i=0;i<original.length();i++){JSONObject word=original.getJSONObject(i);if(word.getDouble("end")>end+.04){if(command.length()>0)command.append(' ');command.append(word.getString("word"));}}
            return new Wake(true,command.toString());
        }
        @Override public void close(){keyword.close();}
    }
    private static void appendWords(JSONArray result,JSONObject json)throws Exception{JSONArray words=json.optJSONArray("result");if(words!=null)for(int i=0;i<words.length();i++)result.put(words.getJSONObject(i));}
    static boolean validWake(JSONObject json){
        JSONArray words=json.optJSONArray("result");if(words==null||words.length()<2)return false;
        JSONObject a=words.optJSONObject(0),b=words.optJSONObject(1);if(a==null||b==null)return false;
        double duration=b.optDouble("end")-a.optDouble("start");
        return "阿".equals(a.optString("word"))&&"洛".equals(b.optString("word"))&&a.optDouble("conf")>=.85&&b.optDouble("conf")>=.85&&duration>=.12&&duration<=1.2&&b.optDouble("start")-a.optDouble("end")<=.2;
    }
    private void prepareModel(File directory)throws IOException{
        boolean reuse=Arrays.asList(context.getAssets().list("")).contains("aluo-model-reuse-only");
        if(reuse){
            File marker=new File(directory,"uuid");
            if(!marker.isFile())throw new IOException("请先安装完整模型包");
            byte[] expected;try(InputStream source=context.getAssets().open("model-cn/uuid")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();int next;while((next=source.read())!=-1)bytes.write(next);expected=bytes.toByteArray();}
            if(!Arrays.equals(expected,java.nio.file.Files.readAllBytes(marker.toPath())))throw new IOException("已安装模型版本不同");
            for(String name:new String[]{"am/final.mdl","graph/Gr.fst","graph/HCLr.fst","conf/model.conf"})if(!new File(directory,name).isFile())throw new IOException("已安装模型缺失");
        }else copy("model-cn",directory);
    }
    private void copy(String asset,File target)throws IOException{
        String[] children=context.getAssets().list(asset);
        if(children!=null&&children.length>0){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("mkdir");for(String child:children)copy(asset+"/"+child,new File(target,child));}
        else if(!target.isFile()){
            File part=new File(target+".part");try(InputStream input=context.getAssets().open(asset);OutputStream output=new FileOutputStream(part)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
            if(!part.renameTo(target))throw new IOException("rename");
        }
    }
    @Override public void close(){closed=true;stop();decoder.execute(()->{if(dictation!=null){dictation.close();dictation=null;}if(wakeDecoder!=null){wakeDecoder.close();wakeDecoder=null;}if(model!=null){model.close();model=null;}});decoder.shutdown();}
    private static final class Frame{final short[] pcm;final long received;Frame(short[] pcm,long received){this.pcm=pcm;this.received=received;}}
    private static final class Session{
        final Mode mode;final Listener listener;final ArrayBlockingQueue<Frame> queue=new ArrayBlockingQueue<>(64);
        volatile boolean stopped,captureDone;volatile long capturedSamples,decodedSamples;AudioRecord recorder;Thread capture;
        Session(Mode mode,Listener listener){this.mode=mode;this.listener=listener;}
        synchronized void stop(){stopped=true;if(recorder!=null)try{recorder.stop();}catch(IllegalStateException ignored){}}
    }
}
