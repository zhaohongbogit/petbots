package cn.aihongbo.voiceweather.board;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import org.json.*;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One model/recognizer, warmed before recording; capture never waits for decoding. */
final class MicSession {
    interface Listener {void text(String text,boolean complete);}
    private static final int RATE=16000,FRAME=1280;
    private final Context context;
    private final int seconds,source;
    private final AudioDeviceInfo preferred;
    private final Listener listener;
    private final boolean weatherVocabulary,saveAudio;
    /** Test-only producer settings; no microphone is opened in this mode. */
    static final class Replay {
        final int silenceMs,stallMs;
        final boolean legacy;
        Replay(int silenceMs,int stallMs,boolean legacy){
            if(silenceMs<0||silenceMs>3000||stallMs<0||stallMs>10000)throw new IllegalArgumentException("Replay bounds");
            this.silenceMs=silenceMs;this.stallMs=stallMs;this.legacy=legacy;
        }
    }
    private final Replay replay;
    private final int queueCapacity;
    private final ArrayBlockingQueue<Frame> queue;
    private final JSONArray memory=new JSONArray(),warmup=new JSONArray(),results=new JSONArray(),timestamps=new JSONArray();
    private final JSONObject report=new JSONObject();
    private final Object recordLock=new Object();
    private volatile AudioRecord recorder;
    private volatile boolean stopping,captureDone,sampling;
    volatile boolean finished;
    volatile String phase="待开始",error="";
    volatile long captured,decoded,dropped,queuedFrames,maxQueue,readErrors,zeroReads,maxReadGapMs,decodeNanos,maxDecodeNanos,maxQueueAgeMs;
    volatile long captureStart,captureEnd,zeroSamples,clippedSamples,energeticFrames,partialUpdates,naturalFinals;
    private long segmentFlushes,gapResets;
    private volatile long staleDropped;
    private short[] diagnosticPcm;
    private int diagnosticSamples;
    private static final int MAX_DIAGNOSTIC_SECONDS=120;
    private static final class ReadTiming {
        final long firstSample,start,returned,workEnd;final int samples;
        ReadTiming(long firstSample,int samples,long start,long returned,long workEnd){this.firstSample=firstSample;this.samples=samples;this.start=start;this.returned=returned;this.workEnd=workEnd;}
    }
    private final ReadTiming[] readTimings=new ReadTiming[4096];
    private volatile int readTimingCount,omittedReadTimings;
    private volatile long maxReadCallMs,maxCaptureWorkMs;
    private final JSONArray levels=new JSONArray();
    private final JSONArray events=new JSONArray();
    private long omittedEvents,nextStallMs=15000;
    private volatile int injectedStalls;
    private long gapResetNanos,reportNanos,workerStart,workerEnd;
    private long maxObservedQueueAgeMs,pressureRecoveries,softLagWarnings;
    private volatile boolean lagWarning;
    private volatile int peak;
    private volatile double lastRms;
    private volatile int timestampOk,timestampErrors;
    private volatile String route="尚未录音";
    private Thread worker,capture,sampler;
    private long firstRead,lastRead,lastReport;
    private static final class Frame {
        final short[] pcm;final long received,firstSample;
        Frame(short[] pcm,long received,long firstSample){this.pcm=pcm;this.received=received;this.firstSample=firstSample;}
    }
    MicSession(Context context,int seconds,int source,AudioDeviceInfo preferred,Listener listener){
        this(context,seconds,source,preferred,listener,true,false);
    }
    MicSession(Context context,int seconds,int source,AudioDeviceInfo preferred,Listener listener,boolean weatherVocabulary,boolean saveAudio){
        this(context,seconds,source,preferred,listener,weatherVocabulary,saveAudio,null);
    }
    private MicSession(Context context,int seconds,int source,AudioDeviceInfo preferred,Listener listener,boolean weatherVocabulary,boolean saveAudio,Replay replay){
        this.context=context.getApplicationContext();this.seconds=Math.max(1,Math.min(1800,seconds));this.source=source;this.preferred=preferred;this.listener=listener;
        this.weatherVocabulary=weatherVocabulary;this.saveAudio=saveAudio;
        this.replay=replay;queueCapacity=replay!=null&&replay.legacy?32:64;queue=new ArrayBlockingQueue<>(queueCapacity);
    }
    static MicSession fileReplay(Context context,int seconds,Replay replay,Listener listener){
        return new MicSession(context,seconds,MediaRecorder.AudioSource.VOICE_RECOGNITION,null,listener,false,false,replay);
    }
    long totalDroppedSamples(){return dropped+staleDropped;}
    long staleDroppedSamples(){return staleDropped;}
    int pendingFrames(){return queue.size();}
    private String reportName(){return replay==null?"mic-live.json":"queue-replay.json";}
    private boolean legacyPolicy(){return replay!=null&&replay.legacy;}
    void start(){worker=new Thread(this::run,"board-vosk-session");worker.start();}
    void stop(){stopping=true;synchronized(recordLock){if(recorder!=null)try{recorder.stop();}catch(IllegalStateException ignored){}}}
    String status(){
        long wall=captureStart==0?0:((captureEnd==0?SystemClock.elapsedRealtime():captureEnd)-captureStart);
        return "状态："+phase+(!finished&&lagWarning?"（积压恢复中）":"")+(error.isEmpty()?"":"\n错误："+error)+"\n采集 "+captured/16000.0+" 秒 / 墙钟 "+wall/1000.0+" 秒；解码 "+decoded/16000.0+" 秒"
            +"\n队列 "+queue.size()+"/"+queueCapacity+"，丢弃 "+totalDroppedSamples()+" 采样；读错误 "+readErrors+"\nRMS "+Math.round(lastRms)+" / peak "+peak+"；完整句 "+naturalFinals+"\n输入："+route+"\n诊断：files/"+reportName();
    }
    private void run(){
        try {
            report.put("version","0.4.1").put("sample_rate",RATE).put("frame_samples",FRAME).put("queue_capacity",queueCapacity).put("pipeline_reset_interval_s",20)
                .put("capture_stop_mode",replay==null&&saveAudio?"sample_target_with_wall_guard":"wall_clock_or_file_target").put("diagnostic_limit_s",MAX_DIAGNOSTIC_SECONDS)
                .put("input_kind",replay==null?"microphone":"file_replay").put("events",events)
                .put("decoder_profile",weatherVocabulary?"weather_vocabulary":"dictation").put("max_queue_age_limit_ms",legacyPolicy()?1500:JSONObject.NULL)
                .put("queue_policy",legacyPolicy()?"legacy_age_1500ms":"pressure_recovery").put("queue_soft_age_ms",1500).put("queue_pressure_frames",queueCapacity-2).put("recovery_target_age_ms",500)
                .put("audio_diagnostic_enabled",saveAudio).put("audio_levels",levels)
                .put("duration_limit_s",seconds).put("source",source).put("qwen_enabled",false).put("recognizer_count",1)
                .put("memory_samples",memory).put("warmup",warmup).put("results",results).put("timestamps",timestamps)
                .put("note","Queue drops include overflow and recovery trims. Soft age warnings do not drop audio; pressure recovery is lossy. No age-based trim after input ends. RTF includes gap resets, excludes report writes and injected stalls. Event values: drops=samples, lag/decode/reset/stall=ms, queue_sample=frames. Optional WAV is local, before queue drops.");
            if(replay!=null)report.put("replay_asset","samples/zhengzhou.pcm").put("replay_silence_ms",replay.silenceMs).put("injected_stall_ms",replay.stallMs).put("injected_stall_schedule","wall seconds 15,45,75,105; at most four");
            sampleMemory();sampling=true;sampler=new Thread(()->{while(sampling){try{Thread.sleep(3000);sampleMemory();}catch(InterruptedException end){return;}catch(Exception failure){android.util.Log.w("BoardMic","memory sampling failed",failure);}}},"board-memory");sampler.start();
            phase="复制模型";File directory=new File(context.getFilesDir(),"model-cn");copy("model-cn",directory);
            if(stopping)return;
            phase="加载模型";long load=SystemClock.elapsedRealtime();
            try(Model model=new Model(directory.getAbsolutePath());Recognizer recognizer=DecoderProfile.create(model,weatherVocabulary)) {
                report.put("model_and_recognizer_load_ms",SystemClock.elapsedRealtime()-load);
                byte[] pcm;try(InputStream input=context.getAssets().open("samples/zhengzhou.pcm");ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);pcm=output.toByteArray();
                }
                for(int round=0;round<2&&!stopping;round++){
                    phase="预热 "+(round+1)+"/2（尚未录音）";long start=System.nanoTime();
                    for(int offset=0;offset<pcm.length/2&&!stopping;offset+=FRAME){short[] frame=new short[Math.min(FRAME,pcm.length/2-offset)];for(int i=0;i<frame.length;i++)frame[i]=(short)((pcm[(offset+i)*2]&255)|(pcm[(offset+i)*2+1]<<8));if(recognizer.acceptWaveForm(frame,frame.length))recognizer.getResult();else recognizer.getPartialResult();}
                    recognizer.getFinalResult();recognizer.reset();
                    warmup.put(new JSONObject().put("round",round).put("decode_ms",(System.nanoTime()-start)/1e6).put("audio_ms",pcm.length/32.0));
                    writeReport();
                }
                if(stopping)return;
                if(replay==null)initializeRecord();
                if(saveAudio)diagnosticPcm=new short[Math.min(seconds,MAX_DIAGNOSTIC_SECONDS)*RATE];
                phase=replay==null?"监听就绪，请说话":"清晰文件按实时节奏回放（不录麦克风）";
                final byte[] replayPcm=pcm;
                capture=new Thread(replay==null?this::capture:()->captureReplay(replayPcm),"board-pcm-capture");capture.start();
                workerStart=SystemClock.elapsedRealtime();
                String previous="";long lastPartial=0,segmentSamples=0,expectedSample=0;
                while(!captureDone||!queue.isEmpty()){
                    if(replay!=null&&replay.stallMs>0&&!stopping&&!captureDone&&captureStart>0&&injectedStalls<4&&SystemClock.elapsedRealtime()-captureStart>=nextStallMs){
                        event("injected_stall_begin",decoded,replay.stallMs);Thread.sleep(replay.stallMs);
                        injectedStalls++;nextStallMs+=30000;event("injected_stall_end",decoded,replay.stallMs);
                    }
                    Frame frame=queue.poll(200,TimeUnit.MILLISECONDS);
                    if(frame!=null){
                        long age=SystemClock.elapsedRealtime()-frame.received;
                        maxObservedQueueAgeMs=Math.max(maxObservedQueueAgeMs,age);
                        eventIfLate(frame,age);
                        if(age>1500&&!lagWarning){lagWarning=true;softLagWarnings++;event("soft_lag_warning",frame.firstSample,age);}
                        else if(age<500&&lagWarning){lagWarning=false;event("lag_recovered",frame.firstSample,age);}
                        if(legacyPolicy()){
                            // Retained only for test replay comparisons with 0.3.0 behavior.
                            while(SystemClock.elapsedRealtime()-frame.received>1500){
                                Frame newer=queue.poll();if(newer==null)break;
                                staleDropped+=frame.pcm.length;event("stale_drop",frame.firstSample,frame.pcm.length);frame=newer;
                            }
                        }else if(!stopping&&!captureDone&&age>1500&&queue.size()>=queueCapacity-2){
                            // Age alone is an alert, not permission to cut speech. Recover in one batch
                            // only under actual bounded-buffer pressure; preserve the tail after stop.
                            long trimStart=frame.firstSample,trimmed=0;
                            while(!stopping&&!captureDone&&SystemClock.elapsedRealtime()-frame.received>500){
                                Frame newer=queue.poll();if(newer==null)break;
                                trimmed+=frame.pcm.length;frame=newer;
                            }
                            if(trimmed>0){staleDropped+=trimmed;pressureRecoveries++;event("pressure_trim",trimStart,trimmed);}
                        }
                        if(frame.firstSample!=expectedSample){long resetStart=System.nanoTime();recognizer.reset();long resetCost=System.nanoTime()-resetStart;gapResetNanos+=resetCost;decodeNanos+=resetCost;gapResets++;event("gap_reset",frame.firstSample,resetCost/1e6);segmentSamples=0;previous="";listener.text("",false);}
                        expectedSample=frame.firstSample+frame.pcm.length;
                        maxQueueAgeMs=Math.max(maxQueueAgeMs,SystemClock.elapsedRealtime()-frame.received);
                        long before=System.nanoTime();boolean endpoint=recognizer.acceptWaveForm(frame.pcm,frame.pcm.length);
                        long now=SystemClock.elapsedRealtime();
                        JSONObject result=endpoint?new JSONObject(recognizer.getResult()):now-lastPartial>=250?new JSONObject(recognizer.getPartialResult()):null;
                        long cost=System.nanoTime()-before;decodeNanos+=cost;maxDecodeNanos=Math.max(maxDecodeNanos,cost);decoded+=frame.pcm.length;
                        if(cost>=160000000L)event("slow_decode",frame.firstSample,cost/1e6);
                        String text=result==null?"":result.optString(endpoint?"text":"partial","");
                        segmentSamples+=frame.pcm.length;
                        // Empty natural endpoints must not postpone the bounded pipeline reset.
                        if(endpoint){if(!text.isEmpty()){naturalFinals++;addText(text,"natural");}previous="";}
                        else if(result!=null){lastPartial=now;if(!text.equals(previous)){previous=text;partialUpdates++;listener.text(text,false);}}
                        if(segmentSamples>=20L*RATE){
                            long flushStart=System.nanoTime();String tail=new JSONObject(recognizer.getFinalResult()).optString("text","");recognizer.reset();decodeNanos+=System.nanoTime()-flushStart;
                            event("segment_reset",decoded,(System.nanoTime()-flushStart)/1e6);
                            segmentFlushes++;segmentSamples=0;previous="";listener.text("",false);if(!tail.isEmpty())addText(tail,"segment_limit");
                        }
                    }
                    if(SystemClock.elapsedRealtime()-lastReport>=5000){lastReport=SystemClock.elapsedRealtime();writeReport();}
                }
                long flushStart=System.nanoTime();String tail=new JSONObject(recognizer.getFinalResult()).optString("text","");decodeNanos+=System.nanoTime()-flushStart;if(!tail.isEmpty())addText(tail,"stop_tail");
                workerEnd=SystemClock.elapsedRealtime();
            }
        }catch(Exception|LinkageError failure){error=failure.toString();android.util.Log.e("BoardMic","session failed",failure);}
        finally {
            stop();if(capture!=null)try{capture.join();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            if(saveAudio&&diagnosticSamples>0)try{saveDiagnosticAudio();}catch(Exception failure){error="原始录音保存失败："+failure;}
            sampling=false;if(sampler!=null){sampler.interrupt();try{sampler.join();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
            phase=error.isEmpty()?"已停止":"失败";
            try{sampleMemory();writeReport();}catch(Exception failure){error="报告保存失败："+failure;}
            finished=true;
        }
    }
    private void initializeRecord()throws Exception {
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("RECORD_AUDIO not granted");
        int minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IOException("16k mono PCM16 unsupported: "+minimum);
        synchronized(recordLock){
            recorder=new AudioRecord(source,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,RATE*2*2));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();recorder=null;throw new IOException("AudioRecord initialization failed");}
            if(preferred!=null&&!recorder.setPreferredDevice(preferred)){recorder.release();recorder=null;throw new IOException("Preferred input rejected");}
            report.put("record_buffer_frames",recorder.getBufferSizeInFrames()).put("preferred_input_id",preferred==null?-1:preferred.getId());
        }
    }
    private void capture(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        long lastTimestamp=0,levelSamples=0;double levelSum=0,levelEnergy=0;int levelPeak=0;
        try {
            synchronized(recordLock){if(stopping)return;recorder.startRecording();captureStart=SystemClock.elapsedRealtime();}
            long target=seconds*(long)RATE;
            while(!stopping&&(saveAudio?captured<target&&SystemClock.elapsedRealtime()-captureStart<(seconds+15)*1000L:SystemClock.elapsedRealtime()-captureStart<seconds*1000L)){
                short[] pcm=new short[saveAudio?(int)Math.min(FRAME,target-captured):FRAME];
                long readStart=SystemClock.elapsedRealtime();int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);long now=SystemClock.elapsedRealtime();
                if(stopping)break;
                if(n<0){readErrors++;throw new IOException("AudioRecord.read returned "+n);}if(n==0){zeroReads++;continue;}
                if(firstRead==0)firstRead=now;if(lastRead!=0)maxReadGapMs=Math.max(maxReadGapMs,now-lastRead);lastRead=now;
                if(n!=pcm.length)pcm=Arrays.copyOf(pcm,n);
                if(diagnosticPcm!=null){int count=Math.min(n,diagnosticPcm.length-diagnosticSamples);System.arraycopy(pcm,0,diagnosticPcm,diagnosticSamples,count);diagnosticSamples+=count;}
                double energy=0;for(short value:pcm){int absolute=Math.abs((int)value);peak=Math.max(peak,absolute);if(value==0)zeroSamples++;if(absolute>=32760)clippedSamples++;energy+=(double)value*value;}
                for(short value:pcm){levelSum+=value;levelPeak=Math.max(levelPeak,Math.abs((int)value));}levelEnergy+=energy;levelSamples+=n;
                lastRms=Math.sqrt(energy/n);if(lastRms>=350)energeticFrames++;
                long firstSample=captured;enqueue(pcm,now);
                if(now-lastTimestamp>=1000){
                    lastTimestamp=now;AudioTimestamp timestamp=new AudioTimestamp();int result=recorder.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_MONOTONIC);
                    long callMonotonic=System.nanoTime(),callElapsed=SystemClock.elapsedRealtime();
                    synchronized(timestamps){if(result==AudioRecord.SUCCESS){timestampOk++;timestamps.put(new JSONObject().put("frame_position",timestamp.framePosition).put("nano_time",timestamp.nanoTime).put("captured_samples",captured).put("elapsed_ms",now).put("call_monotonic_ns",callMonotonic).put("call_elapsed_ms",callElapsed));}else timestampErrors++;}
                    synchronized(levels){levels.put(new JSONObject().put("captured_samples",captured).put("samples",levelSamples).put("rms",Math.sqrt(levelEnergy/levelSamples)).put("dc_mean",levelSum/levelSamples).put("peak",levelPeak));}levelSum=levelEnergy=0;levelSamples=0;levelPeak=0;
                    AudioDeviceInfo routed=recorder.getRoutedDevice();if(routed!=null)route="id="+routed.getId()+", type="+routed.getType()+", "+routed.getProductName();
                    event("queue_sample",captured,queue.size());
                }
                long workEnd=SystemClock.elapsedRealtime();maxReadCallMs=Math.max(maxReadCallMs,now-readStart);maxCaptureWorkMs=Math.max(maxCaptureWorkMs,workEnd-now);
                int index=readTimingCount;if(index<readTimings.length){readTimings[index]=new ReadTiming(firstSample,n,readStart-captureStart,now-captureStart,workEnd-captureStart);readTimingCount=index+1;}else omittedReadTimings++;
                if(now-readStart>160)event("long_read",firstSample,now-readStart);
                if(workEnd-now>80)event("capture_work_delay",firstSample,workEnd-now);
            }
            if(saveAudio&&!stopping&&captured<target)throw new IOException("PCM sample target not reached before wall guard: "+captured+"/"+target);
        }catch(Exception failure){if(!stopping){error=failure.toString();android.util.Log.e("BoardMic","capture failed",failure);}}
        finally{captureEnd=SystemClock.elapsedRealtime();synchronized(recordLock){if(recorder!=null){try{recorder.stop();}catch(IllegalStateException ignored){}recorder.release();recorder=null;}}captureDone=true;}
    }
    private void enqueue(short[] pcm,long now){
        long firstSample=captured;captured+=pcm.length;
        if(!queue.offer(new Frame(pcm,now,firstSample))){dropped+=pcm.length;event("overflow_drop",firstSample,pcm.length);}
        else{queuedFrames++;maxQueue=Math.max(maxQueue,queue.size());}
    }
    private void captureReplay(byte[] speech){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        int speechSamples=speech.length/2,cycle=speechSamples+16*replay.silenceMs;long lastSnapshot=0;
        route="asset samples/zhengzhou.pcm（无麦克风）";
        try {
            captureStart=SystemClock.elapsedRealtime();
            while(!stopping&&captured<seconds*(long)RATE){
                int count=(int)Math.min(FRAME,seconds*(long)RATE-captured);
                long due=captureStart+(captured+count)*1000/RATE;
                while(!stopping){long delay=due-SystemClock.elapsedRealtime();if(delay<=0)break;Thread.sleep(Math.min(20,delay));}
                if(stopping)break;
                short[] frame=new short[count];
                for(int i=0;i<count;i++){int position=(int)((captured+i)%cycle);if(position<speechSamples)frame[i]=(short)((speech[position*2]&255)|(speech[position*2+1]<<8));}
                long now=SystemClock.elapsedRealtime();if(firstRead==0)firstRead=now;if(lastRead>0)maxReadGapMs=Math.max(maxReadGapMs,now-lastRead);lastRead=now;
                enqueue(frame,now);
                if(now-lastSnapshot>=1000){lastSnapshot=now;event("queue_sample",captured,queue.size());}
            }
        }catch(Exception failure){error=failure.toString();}
        finally{captureEnd=SystemClock.elapsedRealtime();captureDone=true;}
    }
    private void eventIfLate(Frame frame,long age){if(age>750)event("late_frame",frame.firstSample,age);}
    private void event(String kind,long sample,double value){
        synchronized(events){
            if(events.length()>=4096){omittedEvents++;return;}
            try{events.put(new JSONObject().put("kind",kind).put("elapsed_ms",captureStart==0?0:SystemClock.elapsedRealtime()-captureStart).put("first_sample",sample).put("value",value).put("queue_frames",queue.size()).put("captured_samples",captured).put("decoded_samples",decoded));}
            catch(JSONException ignored){omittedEvents++;}
        }
    }
    private void addText(String text,String reason)throws Exception {
        results.put(new JSONObject().put("text",text).put("elapsed_ms",SystemClock.elapsedRealtime()).put("reason",reason).put("forced_at_stop",reason.equals("stop_tail")));listener.text(text,true);
    }
    private void sampleMemory()throws Exception {
        Debug.MemoryInfo info=new Debug.MemoryInfo();Debug.getMemoryInfo(info);
        JSONObject point=new JSONObject().put("phase",phase).put("elapsed_ms",SystemClock.elapsedRealtime()).put("pss_kib",info.getTotalPss()).put("native_allocated_kib",Debug.getNativeHeapAllocatedSize()/1024);
        try(BufferedReader input=new BufferedReader(new FileReader("/proc/self/status"))){String line;while((line=input.readLine())!=null)if(line.startsWith("VmRSS:")||line.startsWith("VmHWM:")||line.startsWith("VmSwap:"))point.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());}
        synchronized(memory){memory.put(point);}
    }
    private void writeReport()throws Exception {
        long reportStart=System.nanoTime();
        long wall=captureStart==0?0:(captureEnd==0?SystemClock.elapsedRealtime():captureEnd)-captureStart;
        report.put("phase",phase).put("error",error).put("capture_start_ms",captureStart).put("capture_end_ms",captureEnd).put("capture_wall_ms",wall)
            .put("captured_samples",captured).put("decoded_samples",decoded).put("queue_dropped_samples",dropped+staleDropped).put("overflow_dropped_samples",dropped).put("stale_dropped_samples",staleDropped).put("queue_frames",queue.size()).put("max_queue_frames",maxQueue)
            .put("read_errors",readErrors).put("zero_reads",zeroReads).put("max_read_gap_ms",maxReadGapMs).put("first_read_delay_ms",firstRead==0?0:firstRead-captureStart)
            .put("decode_ms",decodeNanos/1e6).put("decode_rtf",decoded==0?0:decodeNanos/1e9/(decoded/(double)RATE)).put("max_decode_frame_ms",maxDecodeNanos/1e6)
            .put("max_queue_age_ms",maxQueueAgeMs).put("zero_sample_ratio",captured==0?0:zeroSamples/(double)captured).put("clipped_samples",clippedSamples)
            .put("max_read_call_ms",maxReadCallMs).put("max_capture_work_ms",maxCaptureWorkMs).put("omitted_read_timings",omittedReadTimings)
            .put("peak",peak).put("last_rms",lastRms).put("energetic_frames",energeticFrames).put("partial_updates",partialUpdates).put("natural_finals",naturalFinals)
            .put("segment_flushes",segmentFlushes).put("decoder_gap_resets",gapResets)
            .put("gap_reset_ms",gapResetNanos/1e6).put("report_write_ms",reportNanos/1e6).put("worker_wall_ms",workerStart==0?0:(workerEnd==0?SystemClock.elapsedRealtime():workerEnd)-workerStart)
            .put("injected_stalls",injectedStalls).put("omitted_events",omittedEvents)
            .put("max_observed_queue_age_ms",maxObservedQueueAgeMs).put("soft_lag_warnings",softLagWarnings).put("pressure_recoveries",pressureRecoveries)
            .put("samples_accounted",captured==decoded+dropped+staleDropped).put("capture_done",captureDone)
            .put("timestamp_ok",timestampOk).put("timestamp_errors",timestampErrors).put("routed_input",route).put("sample_wall_delta_ms",captured/16.0-wall);
        // Snapshot immutable entries quickly; never serialize or write while holding capture locks.
        JSONObject snapshot=new JSONObject();Iterator<String> keys=report.keys();while(keys.hasNext()){String key=keys.next();snapshot.put(key,report.get(key));}
        snapshot.put("memory_samples",snapshotArray(memory)).put("timestamps",snapshotArray(timestamps)).put("audio_levels",snapshotArray(levels)).put("events",snapshotArray(events));
        JSONArray reads=new JSONArray();int count=readTimingCount;
        for(int i=0;i<count;i++){ReadTiming r=readTimings[i];reads.put(new JSONObject().put("first_sample",r.firstSample).put("samples",r.samples).put("start_ms",r.start).put("returned_ms",r.returned).put("work_end_ms",r.workEnd));}
        snapshot.put("read_timings",reads);
        File temporary=new File(context.getFilesDir(),reportName()+".part"),destination=new File(context.getFilesDir(),reportName());
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(snapshot.toString(2).getBytes(StandardCharsets.UTF_8));}
        if(!temporary.renameTo(destination))throw new IOException("report rename failed");
        reportNanos+=System.nanoTime()-reportStart;
    }
    private static JSONArray snapshotArray(JSONArray source)throws JSONException {
        JSONArray copy=new JSONArray();synchronized(source){for(int i=0;i<source.length();i++)copy.put(source.get(i));}return copy;
    }
    private void saveDiagnosticAudio()throws Exception {
        File temporary=new File(context.getFilesDir(),"mic-diagnostic.wav.part"),destination=new File(context.getFilesDir(),"mic-diagnostic.wav");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))){
            int bytes=diagnosticSamples*2;out.writeBytes("RIFF");writeLe(out,36+bytes,4);out.writeBytes("WAVEfmt ");writeLe(out,16,4);writeLe(out,1,2);writeLe(out,1,2);writeLe(out,RATE,4);writeLe(out,RATE*2,4);writeLe(out,2,2);writeLe(out,16,2);out.writeBytes("data");writeLe(out,bytes,4);
            for(int i=0;i<diagnosticSamples;i++)writeLe(out,diagnosticPcm[i],2);
        }
        if(!temporary.renameTo(destination))throw new IOException("diagnostic WAV rename failed");
        diagnosticPcm=null;report.put("diagnostic_wav","mic-diagnostic.wav").put("diagnostic_wav_samples",diagnosticSamples);
        java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(destination)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);}
        StringBuilder digest=new StringBuilder();for(byte value:hash.digest())digest.append(String.format(java.util.Locale.ROOT,"%02x",value&255));report.put("diagnostic_wav_sha256",digest.toString());
    }
    private static void writeLe(DataOutputStream out,int value,int count)throws IOException {for(int i=0;i<count;i++)out.writeByte(value>>>(8*i));}
    private void copy(String asset,File target)throws IOException {
        String[] children=context.getAssets().list(asset);
        if(children!=null&&children.length>0){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("mkdir failed");for(String child:children)copy(asset+"/"+child,new File(target,child));}
        else if(!target.isFile()){File temporary=new File(target.getPath()+".part");try(InputStream input=context.getAssets().open(asset);OutputStream output=new FileOutputStream(temporary)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}if(!temporary.renameTo(target))throw new IOException("model rename failed");}
    }
}
