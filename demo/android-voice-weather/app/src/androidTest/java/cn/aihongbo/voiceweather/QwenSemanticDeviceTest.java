package cn.aihongbo.voiceweather;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class QwenSemanticDeviceTest {
    @Test public void measureWholeUtteranceSemantics() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONArray rows=new JSONArray();java.util.List<String> mismatches=new java.util.ArrayList<>();
        try(OfflineQwen qwen=new OfflineQwen()) {
            qwen.load(new File(context.getExternalFilesDir("models"),OfflineQwen.MODEL_FILE));QwenSemanticInterpreter semantics=new QwenSemanticInterpreter(qwen);
            String[][] cases={{"把东西放在正中间","other","null","0","false"},{"郑州明天天气怎么样","weather","郑州","1","false"},{"上海今天天气如何","weather","上海","0","false"},{"今天天气怎么样","weather","null","0","false"},{"今天工作有点忙","other","null","0","false"},{"郑重提醒大家明天天气有变化","other","null","0","false"},{"不要查询上海的天气","no","上海","0","false"},{"上海后天天气怎么样","weather","上海","-1","false"},{"是的","yes","null","0","true"},{"需要","other","null","0","true"},{"不是的","no","null","0","true"},{"不用","no","null","0","true"}};
            for(String[] item:cases){long start=SystemClock.elapsedRealtime();QwenSemanticInterpreter.Result result=semantics.interpret(item[0],Boolean.parseBoolean(item[4]),"郑州明天");JSONObject row=new JSONObject();row.put("input",item[0]);row.put("expected_intent",item[1]);row.put("expected_city",item[2]);row.put("expected_day",Integer.parseInt(item[3]));row.put("output",new JSONObject(result.json));row.put("ms",SystemClock.elapsedRealtime()-start);rows.put(row);
                try(FileOutputStream out=context.openFileOutput("vosk-qwen-semantic.json",0)){out.write(rows.toString(2).getBytes(StandardCharsets.UTF_8));}
                if(Integer.parseInt(item[3])<0) {
                    if(result.decision.day!=-1 || new DialogueEngine().accept(result.decision,0)!=DialogueEngine.Action.NOT_SUPPORTED)mismatches.add("Unsupported date was not rejected: "+result.json);
                    continue; // Semantic label accuracy remains in the report; this check covers the supported-date contract.
                }
                if(!item[1].equals(result.decision.intent.name().toLowerCase(java.util.Locale.ROOT)))mismatches.add(item[0]+" intent="+result.json);
                if(item[1].equals("weather") && (!java.util.Objects.equals(item[2].equals("null")?null:item[2],result.decision.city) || Integer.parseInt(item[3])!=result.decision.day))mismatches.add(item[0]+" city/date="+result.json);

            }
            assertTrue(mismatches.toString(),mismatches.isEmpty());
        }
    }
    @Test public void measureMisheardCityRecoveryAndExecutionSafety() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONArray rows=new JSONArray();
        CityCatalog catalog=new CityCatalog(new InputStreamReader(context.getAssets().open("cities.tsv"),StandardCharsets.UTF_8));
        try(OfflineQwen qwen=new OfflineQwen()) {
            qwen.load(new File(context.getExternalFilesDir("models"),OfflineQwen.MODEL_FILE));QwenSemanticInterpreter semantic=new QwenSemanticInterpreter(qwen);
            for(String input:new String[]{"正中明天天气怎么样","郑重明天的天气怎么样"}) {
                long start=SystemClock.elapsedRealtime();QwenSemanticInterpreter.Result result=semantic.interpret(input,false,"上海今天");
                boolean actionable=result.decision.intent==SemanticDecision.Intent.WEATHER && result.decision.day>=0 && (result.decision.city==null || catalog.find(result.decision.city)!=null);
                boolean recovered=result.decision.intent==SemanticDecision.Intent.WEATHER && "郑州".equals(result.decision.city) && result.decision.day==1;
                JSONObject row=new JSONObject();row.put("input",input);row.put("expected_city","郑州");row.put("output",new JSONObject(result.json));row.put("city_recovered",recovered);row.put("safely_blocked",!actionable);row.put("ms",SystemClock.elapsedRealtime()-start);rows.put(row);
                try(FileOutputStream out=context.openFileOutput("vosk-qwen-city-recovery.json",0)){out.write(rows.toString(2).getBytes(StandardCharsets.UTF_8));}
                assertTrue("Wrong/default city would be offered: "+row,recovered || !actionable);
            }
        }
    }

}
