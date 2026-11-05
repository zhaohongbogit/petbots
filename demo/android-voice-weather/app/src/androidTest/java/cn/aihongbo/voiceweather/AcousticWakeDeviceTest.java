package cn.aihongbo.voiceweather;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reproducible positive/negative corpus and cross-sentence state checks on the native decoders. */
public class AcousticWakeDeviceTest {
 @Test public void positiveNegativeAndContinuousStreams()throws Exception {
  Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONArray rows=new JSONArray();List<String> failures=new ArrayList<>();
  try(VoskAsr asr=new VoskAsr()) {
   asr.load(target);
   for(String name:new String[]{"aluo","aluo-weather","slow","fast","hallo","annuo","abo","mention","city-weather","yes","no","center","zhengzhong-warning","silence"}) {
    float[] audio=audio(name,target);StringBuilder raw=new StringBuilder();List<WakeEvidence> evidence=new ArrayList<>();
    try(VoskAsr.Stream stream=asr.openStream()){feed(stream,audio,raw,evidence);String tail=stream.finish();if(!tail.isEmpty())raw.append(tail);if(stream.wakeEvidence().detected)evidence.add(stream.wakeEvidence());}
    boolean positive=Arrays.asList("aluo","aluo-weather","slow","fast").contains(name),detected=!evidence.isEmpty();
    String original=asr.transcribe(audio);boolean unchanged=original.equals(raw.toString().trim());
    rows.put(new JSONObject().put("sample",name).put("expected_wake",positive).put("detected",detected).put("raw_vosk",raw.toString().trim()).put("unconstrained_baseline",original).put("raw_unchanged",unchanged).put("command",detected?evidence.get(0).command:""));
    if(positive!=detected || !unchanged)failures.add(name+": expected="+positive+", detected="+detected+", unchanged="+unchanged);
   }
   StringBuilder raw=new StringBuilder();List<WakeEvidence> evidence=new ArrayList<>();
   try(VoskAsr.Stream stream=asr.openStream()) {
    // Includes leading idle, an unwoken sentence, and two distinct wake phrases on one source.
    feed(stream,new float[32000],raw,evidence);
    feed(stream,audio("city-weather",target),raw,evidence);feed(stream,new float[32000],raw,evidence);
    feed(stream,audio("aluo",target),raw,evidence);feed(stream,new float[32000],raw,evidence);
    feed(stream,audio("aluo-weather",target),raw,evidence);feed(stream,new float[32000],raw,evidence);
    stream.finish();if(stream.wakeEvidence().detected)evidence.add(stream.wakeEvidence());
   }
   rows.put(new JSONObject().put("sample","continuous").put("wake_count",evidence.size()).put("raw_vosk",raw.toString()).put("first_command",evidence.isEmpty()?"":evidence.get(0).command).put("last_command",evidence.isEmpty()?"":evidence.get(evidence.size()-1).command));
   if(evidence.size()!=2 || !evidence.get(0).command.isEmpty() || !evidence.get(1).command.replace(" ","").equals("上海明天天气怎么样"))failures.add("continuous stream timing/state mismatch");
  } finally {try(FileOutputStream out=target.openFileOutput("acoustic-wake-regression.json",0)){out.write(new JSONObject().put("rows",rows).put("failures",new JSONArray(failures)).toString(2).getBytes(StandardCharsets.UTF_8));}}
  assertTrue(failures.toString(),failures.isEmpty());
 }
 private static float[] audio(String name,Context target)throws Exception {
  if(name.equals("silence"))return new float[32000];
  boolean fixture=Arrays.asList("aluo","aluo-weather","slow","fast","hallo","annuo","abo","mention").contains(name);
  try(InputStream input=(fixture?InstrumentationRegistry.getInstrumentation().getContext():target).getAssets().open((fixture?"wake/":"samples/")+name+".pcm")){return VoskAsr.readPcm(input);}
 }
 private static void feed(VoskAsr.Stream stream,float[] audio,StringBuilder raw,List<WakeEvidence> evidence)throws Exception {
  for(int offset=0;offset<audio.length;offset+=1280){short[] pcm=new short[Math.min(1280,audio.length-offset)];for(int i=0;i<pcm.length;i++)pcm[i]=(short)Math.max(-32768,Math.min(32767,Math.round(audio[offset+i]*32768f)));VoskAsr.Update update=stream.accept(pcm);if(update.endpoint){if(!update.text.isEmpty())raw.append(update.text).append(' ');if(stream.wakeEvidence().detected)evidence.add(stream.wakeEvidence());}}
 }
}
