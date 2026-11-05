package cn.aihongbo.voiceweather;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.vosk.*;

/** Measures constrained acoustic decoding separately from untouched dictation. */
public class WakeGrammarProbeTest {
 @Test public void probe() throws Exception {
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONArray rows=new JSONArray();
  try(VoskAsr asr=new VoskAsr()) {
   asr.load(context);Field field=VoskAsr.class.getDeclaredField("model");field.setAccessible(true);Model model=(Model)field.get(asr);
   String[] grammars={"[\"阿 洛\",\"[unk]\"]","[\"阿洛\",\"[unk]\"]","[\"阿 洛\",\"二 楼\",\"[unk]\"]","[\"阿 洛\",\"哈 喽\",\"阿 波\",\"安 诺\",\"阿 龙\",\"阿 姨\",\"[unk]\"]"};
   java.util.List<String> names=new java.util.ArrayList<>(java.util.Arrays.asList("aluo","aluo-weather","hallo","annuo","abo","city-weather","yes","no","zhengzhong-warning","center"));
   String prefix=InstrumentationRegistry.getArguments().getString("recordingPrefix","");
   String selectedIds=InstrumentationRegistry.getArguments().getString("recordingIds","");
   File[] recordings=new File(context.getFilesDir(),"recordings").listFiles();if(!prefix.isEmpty() && recordings!=null){java.util.Arrays.sort(recordings);for(File file:recordings)if(file.getName().startsWith(prefix) && file.length()<384000 && (selectedIds.isEmpty() || java.util.Arrays.asList(selectedIds.split(",")).stream().anyMatch(id -> file.getName().endsWith("-"+id+".wav"))))names.add(file.getAbsolutePath());}
   for(String grammar:grammars)for(String name:names) {
    float[] samples;boolean wake=java.util.Arrays.asList("aluo","aluo-weather","hallo","annuo","abo").contains(name);
    try(InputStream input=name.startsWith("/")?new FileInputStream(name):(wake?InstrumentationRegistry.getInstrumentation().getContext():context).getAssets().open(wake?"wake/"+name+".pcm":"samples/"+name+".pcm")){if(name.startsWith("/"))input.skip(44);samples=VoskAsr.readPcm(input);}
    JSONArray results=new JSONArray();
    try(Recognizer recognizer=new Recognizer(model,16000f,grammar)) {
     recognizer.setWords(true);
     for(int offset=0;offset<samples.length;offset+=1280) {
      short[] pcm=new short[Math.min(1280,samples.length-offset)];for(int i=0;i<pcm.length;i++)pcm[i]=(short)Math.max(-32768,Math.min(32767,Math.round(samples[offset+i]*32768f)));
      if(recognizer.acceptWaveForm(pcm,pcm.length))results.put(new JSONObject(recognizer.getResult()));
     }
     results.put(new JSONObject(recognizer.getFinalResult()));
    }
    rows.put(new JSONObject().put("grammar",grammar).put("sample",name).put("raw",asr.transcribe(samples)).put("restricted",results));
   }
  } finally {try(FileOutputStream output=context.openFileOutput("wake-grammar-probe.json",0)){output.write(rows.toString(2).getBytes(StandardCharsets.UTF_8));}}
 }
}
