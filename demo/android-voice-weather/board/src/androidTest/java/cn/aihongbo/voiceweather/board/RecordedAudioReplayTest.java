package cn.aihongbo.voiceweather.board;

import android.content.Context;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import static org.junit.Assert.*;

/** Full recorded PCM, no microphone, queue, pacing, trimming or software audio processing. */
public final class RecordedAudioReplayTest {
    private final JSONArray memory=new JSONArray();
    private volatile boolean sampling;
    private volatile String memorySamplingError="";
    private void sampleMemory()throws Exception {
        Debug.MemoryInfo info=new Debug.MemoryInfo();Debug.getMemoryInfo(info);
        JSONObject point=new JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()).put("pss_kib",info.getTotalPss());
        try(BufferedReader input=new BufferedReader(new FileReader("/proc/self/status"))){String line;while((line=input.readLine())!=null)if(line.startsWith("VmRSS:")||line.startsWith("VmSwap:"))point.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());}
        synchronized(memory){memory.put(point);}
    }
    @Test public void fullRecordedPcmOffline()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] wav=Files.readAllBytes(new File(context.getFilesDir(),"mic-diagnostic.wav").toPath());
        assertTrue("Truncated WAV",wav.length>=44);
        assertEquals("RIFF",new String(wav,0,4,StandardCharsets.US_ASCII));
        assertEquals("WAVE",new String(wav,8,4,StandardCharsets.US_ASCII));
        assertEquals("fmt ",new String(wav,12,4,StandardCharsets.US_ASCII));
        assertEquals(16,le(wav,16,4));assertEquals(1,le(wav,20,2));assertEquals(1,le(wav,22,2));
        assertEquals(16000,le(wav,24,4));assertEquals(16,le(wav,34,2));
        assertEquals("data",new String(wav,36,4,StandardCharsets.US_ASCII));
        int dataBytes=le(wav,40,4),samples=dataBytes/2;
        assertEquals("WAV payload mismatch",44+dataBytes,wav.length);
        assertEquals("Requires complete 120s recording",120*16000,samples);
        String sourceHash=hash(wav);
        JSONObject live=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"mic-live.json").toPath()),StandardCharsets.UTF_8));
        assertEquals("Recording identity differs from live report",live.getString("diagnostic_wav_sha256"),sourceHash);
        JSONObject report=new JSONObject().put("input_wav_sha256",sourceHash).put("input_samples",samples).put("input_audio_ms",samples/16.0)
            .put("input_kind","full_recorded_pcm_offline").put("sample_rate",16000).put("recognizer_count",1).put("decoder_profile","dictation").put("qwen_enabled",false)
            .put("queue_enabled",false).put("timing_schema","split-final-reset-v2").put("pipeline_reset_interval_s",20).put("memory_samples",memory)
            .put("note","All original samples, no resampling/gain/denoise/drop. Same Vosk profile and 20s guard as live. Timing covers accept/result/final/reset; no live capture or queue. Technical assertions do not establish transcript accuracy.");
        JSONArray frames=new JSONArray(),finals=new JSONArray(),resets=new JSONArray(),warmup=new JSONArray();
        report.put("frames",frames).put("results",finals).put("resets",resets).put("warmup",warmup);
        sampleMemory();sampling=true;
        Thread sampler=new Thread(()->{while(sampling){try{Thread.sleep(3000);sampleMemory();}catch(InterruptedException end){return;}catch(Exception failure){memorySamplingError=failure.toString();return;}}},"offline-memory");sampler.start();
        long total=0,max=0,start=0,load=SystemClock.elapsedRealtime();int fed=0,segment=0;
        try(Model model=new Model(new File(context.getFilesDir(),"model-cn").getAbsolutePath());Recognizer decoder=DecoderProfile.create(model,false)){
            report.put("model_load_ms",SystemClock.elapsedRealtime()-load);
            byte[] warm;try(InputStream input=context.getAssets().open("samples/zhengzhou.pcm");ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)output.write(b,0,n);warm=output.toByteArray();
            }
            for(int round=0;round<2;round++){
                long before=System.nanoTime();
                for(int offset=0;offset<warm.length/2;offset+=1280){short[] pcm=pcm(warm,offset*2,Math.min(1280,warm.length/2-offset));if(decoder.acceptWaveForm(pcm,pcm.length))decoder.getResult();else decoder.getPartialResult();}
                decoder.getFinalResult();decoder.reset();warmup.put(new JSONObject().put("round",round).put("decode_ms",(System.nanoTime()-before)/1e6));
            }
            start=SystemClock.elapsedRealtime();long lastPartial=0;
            for(int offset=0;offset<samples;offset+=1280){
                short[] pcm=pcm(wav,44+offset*2,Math.min(1280,samples-offset));long before=System.nanoTime();
                boolean endpoint=decoder.acceptWaveForm(pcm,pcm.length);long acceptCost=System.nanoTime()-before;String text="";
                if(endpoint)text=new JSONObject(decoder.getResult()).optString("text","");
                else if(SystemClock.elapsedRealtime()-lastPartial>=250){decoder.getPartialResult();lastPartial=SystemClock.elapsedRealtime();}
                long cost=System.nanoTime()-before;total+=cost;max=Math.max(max,cost);fed+=pcm.length;segment+=pcm.length;
                frames.put(new JSONObject().put("first_sample",offset).put("samples",pcm.length).put("decode_ms",cost/1e6).put("accept_ms",acceptCost/1e6).put("result_ms",(cost-acceptCost)/1e6).put("wall_ms",SystemClock.elapsedRealtime()-start));
                if(!text.isEmpty())finals.put(new JSONObject().put("text",text).put("through_sample",fed).put("reason","natural"));
                if(segment>=20*16000){
                    before=System.nanoTime();text=new JSONObject(decoder.getFinalResult()).optString("text","");long finalCost=System.nanoTime()-before;
                    long resetStart=System.nanoTime();decoder.reset();long resetCost=System.nanoTime()-resetStart;cost=System.nanoTime()-before;total+=cost;
                    resets.put(new JSONObject().put("through_sample",fed).put("final_ms",finalCost/1e6).put("reset_ms",resetCost/1e6).put("total_ms",cost/1e6));segment=0;
                    if(!text.isEmpty())finals.put(new JSONObject().put("text",text).put("through_sample",fed).put("reason","segment_limit"));
                }
            }
            long before=System.nanoTime();String text=new JSONObject(decoder.getFinalResult()).optString("text","");long tailCost=System.nanoTime()-before;total+=tailCost;report.put("tail_final_ms",tailCost/1e6);
            if(!text.isEmpty())finals.put(new JSONObject().put("text",text).put("through_sample",fed).put("reason","stop_tail"));
            report.put("completed",true).put("wall_ms",SystemClock.elapsedRealtime()-start).put("decoded_samples",fed).put("decode_ms",total/1e6).put("decode_rtf",total/1e9/(samples/16000.0)).put("max_decode_frame_ms",max/1e6);
        } finally {
            sampling=false;sampler.interrupt();sampler.join();sampleMemory();
            report.put("memory_sampling_error",memorySamplingError);
            synchronized(memory){try(OutputStream output=context.openFileOutput("recorded-replay.json",0)){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
        }
        assertEquals("Offline decoder skipped PCM",samples,fed);
        assertEquals("Recording changed during replay",sourceHash,hash(Files.readAllBytes(new File(context.getFilesDir(),"mic-diagnostic.wav").toPath())));
    }
    private static int le(byte[] b,int offset,int bytes){int v=0;for(int i=0;i<bytes;i++)v|=(b[offset+i]&255)<<(8*i);return v;}
    private static short[] pcm(byte[] b,int offset,int samples){short[] pcm=new short[samples];for(int i=0;i<samples;i++)pcm[i]=(short)((b[offset+i*2]&255)|(b[offset+i*2+1]<<8));return pcm;}
    private static String hash(byte[] b)throws Exception {StringBuilder h=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))h.append(String.format(Locale.ROOT,"%02x",v&255));return h.toString();}
}
