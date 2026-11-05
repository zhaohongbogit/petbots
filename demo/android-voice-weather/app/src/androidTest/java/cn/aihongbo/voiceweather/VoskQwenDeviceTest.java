package cn.aihongbo.voiceweather;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class VoskQwenDeviceTest {
    @Test public void transcribeRealPcmThenQwen() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject report=new JSONObject();JSONArray rows=new JSONArray();report.put("rows",rows);
        java.util.List<String> failures=new java.util.ArrayList<>();long start=SystemClock.elapsedRealtime();
        try(VoskAsr asr=new VoskAsr();OfflineQwen qwen=new OfflineQwen()) {
            asr.load(context);report.put("asr_load_ms",SystemClock.elapsedRealtime()-start);report.put("pss_after_asr_load_kb",Debug.getPss());
            start=SystemClock.elapsedRealtime();qwen.load(new File(context.getExternalFilesDir("models"),OfflineQwen.MODEL_FILE));report.put("qwen_load_ms",SystemClock.elapsedRealtime()-start);report.put("pss_after_both_load_kb",Debug.getPss());QwenSemanticInterpreter semantic=new QwenSemanticInterpreter(qwen);
            for(String name:new String[]{"zhengzhou","zhengzhou-fast","zhengzhou-hard","city-weather","zhengzhong-warning","center","yes","no"}) {
                float[] samples;try(InputStream source=context.getAssets().open("samples/"+name+".pcm")){samples=VoskAsr.readPcm(source);}
                long beforePss=Debug.getPss();start=SystemClock.elapsedRealtime();String text=asr.transcribe(samples);long asrMs=SystemClock.elapsedRealtime()-start;long asrPss=Debug.getPss();
                start=SystemClock.elapsedRealtime();QwenSemanticInterpreter.Result result=semantic.interpret(text,name.equals("yes")||name.equals("no"),"郑州明天");
                JSONObject row=new JSONObject();row.put("sample",name);if(name.startsWith("zhengzhou")){row.put("expected_city","郑州");row.put("city_recovered",result.decision.intent==SemanticDecision.Intent.WEATHER && "郑州".equals(result.decision.city) && result.decision.day==1);}row.put("raw_asr",text);row.put("asr_ms",asrMs);row.put("semantic_ms",SystemClock.elapsedRealtime()-start);row.put("audio_seconds",samples.length/16000.0);row.put("semantic",new JSONObject(result.json));row.put("pss_before_kb",beforePss);row.put("pss_after_asr_kb",asrPss);row.put("pss_kb",Debug.getPss());rows.put(row);save(context,report);
                try {assertFalse("Empty transcript: "+name,text.isEmpty());
                if(name.equals("zhengzhou-hard")){
                    // Capability measurement: heavily damaged ASR must either recover the correct city or be blocked.
                    CityCatalog catalog=new CityCatalog(new InputStreamReader(context.getAssets().open("cities.tsv"),StandardCharsets.UTF_8));
                    boolean actionable=result.decision.intent==SemanticDecision.Intent.WEATHER && result.decision.day>=0 && (result.decision.city==null || catalog.find(result.decision.city)!=null);
                    if(actionable){assertEquals("郑州",result.decision.city);assertEquals(1,result.decision.day);}
                    row.put("safely_blocked",!actionable);
                }else if(name.startsWith("zhengzhou")){assertEquals("郑州",result.decision.city);assertEquals(SemanticDecision.Intent.WEATHER,result.decision.intent);assertEquals(1,result.decision.day);}
                else if(name.equals("city-weather")){assertEquals("上海",result.decision.city);assertEquals(SemanticDecision.Intent.WEATHER,result.decision.intent);}
                else if(name.equals("yes"))assertEquals(SemanticDecision.Intent.YES,result.decision.intent);
                else if(name.equals("no"))assertEquals(SemanticDecision.Intent.NO,result.decision.intent);
                else assertEquals(SemanticDecision.Intent.OTHER,result.decision.intent);
                }catch(AssertionError error){failures.add(name+": "+text+" -> "+result.json+"; "+error.getMessage());}
            }
            report.put("pss_with_two_models_kb",Debug.getPss());report.put("failures",new JSONArray(failures));save(context,report);assertTrue(failures.toString(),failures.isEmpty());
        }
    }
    private static void save(Context context,JSONObject report)throws Exception {try(FileOutputStream out=context.openFileOutput("vosk-qwen-audio-report.json",0)){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
}
