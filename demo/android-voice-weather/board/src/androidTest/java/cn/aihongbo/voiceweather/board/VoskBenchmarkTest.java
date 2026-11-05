package cn.aihongbo.voiceweather.board;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Each mode runs in a fresh instrumentation process. No dormant batch recognizer. */
public final class VoskBenchmarkTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final JSONObject report=new JSONObject();
    private final JSONArray memory=new JSONArray();
    private volatile boolean sampling;
    private volatile String phase="baseline";
    private Thread sampler;
    private int silenceMs;
    private void snapshot() throws Exception {
        Debug.MemoryInfo info=new Debug.MemoryInfo();Debug.getMemoryInfo(info);
        JSONObject point=new JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()).put("phase",phase)
            .put("pss_kib",info.getTotalPss()).put("native_allocated_kib",Debug.getNativeHeapAllocatedSize()/1024)
            .put("java_used_kib",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024);
        try(BufferedReader input=new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;while((line=input.readLine())!=null)if(line.startsWith("VmRSS:")||line.startsWith("VmHWM:")||line.startsWith("VmSwap:"))point.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());
        }
        synchronized(memory){memory.put(point);}
    }
    @Test public void benchmark() throws Exception {
        String mode=InstrumentationRegistry.getArguments().getString("mode","single");
        boolean weather=InstrumentationRegistry.getArguments().getString("profile","dictation").equals("weather");
        assertTrue(mode,mode.equals("single")||mode.equals("dual"));
        silenceMs=Integer.parseInt(InstrumentationRegistry.getArguments().getString("silence_ms","3000"));
        assertTrue("silence_ms must be 0..3000",silenceMs>=0 && silenceMs<=3000);
        report.put("mode",mode).put("profile",weather?"weather":"dictation").put("abi",android.os.Build.SUPPORTED_ABIS[0]).put("sample_rate",16000)
            .put("tail_silence_ms",silenceMs).put("memory_sampling_interval_ms",200)
            .put("frame_samples",1280).put("qwen_enabled",false).put("memory_samples",memory)
            .put("note","Synthetic PCM; sampled PSS is whole process, not exact peak or model-only memory. Dual matches production decoder calls, excluding UI/WAV/wake-evidence parsing.");
        File directory=new File(context.getFilesDir(),"model-cn");
        long copyStart=SystemClock.elapsedRealtime();copy("model-cn",directory);
        report.put("copy_ms",SystemClock.elapsedRealtime()-copyStart);
        snapshot();sampling=true;
        sampler=new Thread(()->{while(sampling){try{snapshot();Thread.sleep(200);}catch(Exception error){try{report.put("sampler_error",error.toString());}catch(Exception ignored){}return;}}},"memory-sampler");sampler.start();
        try {
            phase="model_load";long start=SystemClock.elapsedRealtime();
            try(Model model=new Model(directory.getAbsolutePath())) {
                report.put("model_load_ms",SystemClock.elapsedRealtime()-start);snapshot();
                phase="recognizer_load";start=SystemClock.elapsedRealtime();
                try(Recognizer dictation=weather?DecoderProfile.create(model,true):new Recognizer(model,16000f)) {
                    dictation.setWords(!weather);
                    Recognizer wake=mode.equals("dual")?new Recognizer(model,16000f,"[\"阿 洛\",\"[unk]\"]"):null;
                    try {
                        if(wake!=null)wake.setWords(true);
                        report.put("recognizer_load_ms",SystemClock.elapsedRealtime()-start);snapshot();
                        JSONArray runs=new JSONArray();report.put("runs",runs);
                        for(boolean paced:new boolean[]{false,true}) {
                            phase=paced?"paced":"throughput";
                            for(int round=0;round<3;round++) {
                                dictation.reset();if(wake!=null)wake.reset();
                                runs.put(run(dictation,wake,paced,round));snapshot();
                            }
                        }
                    } finally {if(wake!=null)wake.close();}
                }
            }
            phase="released";snapshot();
        } finally {
            sampling=false;sampler.join();
            try(FileOutputStream output=context.openFileOutput("benchmark-"+mode+".json",0)){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
        JSONArray runs=report.getJSONArray("runs");
        for(int i=0;i<runs.length();i++){
            JSONArray finals=runs.getJSONObject(i).getJSONArray("finals");assertTrue("No recognized text",finals.length()>0);
            if(weather)assertTrue("Wrong weather transcript: "+finals,finals.toString().replace(" ","").contains("郑州明天的天气怎么样"));
        }
    }
    private JSONObject run(Recognizer dictation,Recognizer wake,boolean paced,int round)throws Exception {
        byte[] speech;try(InputStream input=context.getAssets().open("samples/zhengzhou.pcm");ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);speech=output.toByteArray();
        }
        int samples=speech.length/2+16*silenceMs;
        JSONArray finals=new JSONArray();ArrayList<Double> costs=new ArrayList<>();
        long total=0,start=SystemClock.elapsedRealtime(),firstPartial=-1,maxLag=0;int over80=0;
        for(int offset=0;offset<samples;offset+=1280) {
            short[] frame=new short[Math.min(1280,samples-offset)];
            for(int i=0;i<frame.length;i++){int position=(offset+i)*2;if(position+1<speech.length)frame[i]=(short)((speech[position]&255)|(speech[position+1]<<8));}
            long available=start+(offset+frame.length)*1000L/16000;
            if(paced){long delay=available-SystemClock.elapsedRealtime();if(delay>0)SystemClock.sleep(delay);}
            long before=System.nanoTime();
            if(wake!=null && wake.acceptWaveForm(frame,frame.length))wake.getResult();
            boolean endpoint=dictation.acceptWaveForm(frame,frame.length);
            JSONObject result=new JSONObject(endpoint?dictation.getResult():dictation.getPartialResult());
            String text=result.optString(endpoint?"text":"partial","");
            if(endpoint){if(wake!=null){wake.getFinalResult();wake.reset();}if(!text.isEmpty())finals.put(text);}
            else if(!text.isEmpty() && firstPartial<0)firstPartial=SystemClock.elapsedRealtime()-start;
            long cost=System.nanoTime()-before;total+=cost;costs.add(cost/1e6);if(cost>80000000L)over80++;
            if(paced)maxLag=Math.max(maxLag,SystemClock.elapsedRealtime()-available);
        }
        long before=System.nanoTime();String tail=new JSONObject(dictation.getFinalResult()).optString("text","");
        if(wake!=null){wake.getFinalResult();wake.reset();}total+=System.nanoTime()-before;if(!tail.isEmpty())finals.put(tail);
        Collections.sort(costs);double duration=samples/16.0;
        return new JSONObject().put("paced",paced).put("round",round).put("audio_ms",duration)
            .put("decode_ms",total/1e6).put("rtf",total/1e6/duration).put("wall_ms",SystemClock.elapsedRealtime()-start)
            .put("frame_p95_ms",costs.get((int)Math.ceil(costs.size()*.95)-1)).put("frame_max_ms",costs.get(costs.size()-1))
            .put("frames_over_80ms",over80).put("first_partial_wall_ms",firstPartial).put("max_completion_lag_ms",maxLag).put("finals",finals);
    }
    private void copy(String asset,File target)throws IOException {
        String[] children=context.getAssets().list(asset);
        if(children!=null && children.length>0){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("mkdir failed");for(String child:children)copy(asset+"/"+child,new File(target,child));}
        else if(!target.isFile()){
            File temporary=new File(target.getPath()+".part");
            try(InputStream input=context.getAssets().open(asset);OutputStream output=new FileOutputStream(temporary)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);}
            if(!temporary.renameTo(target))throw new IOException("model copy rename failed");
        }
    }
}
