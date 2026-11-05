package cn.aihongbo.voiceweather;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class StreamingVoskDeviceTest {
 @Test public void nativePartialsPrecedeEofAndSilenceProducesOneFinalPerSentence()throws Exception {
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONArray events=new JSONArray();List<String> finals=new ArrayList<>();long[] firstPartial={-1};
  float[] audio;try(InputStream input=context.getAssets().open("samples/zhengzhou.pcm")){audio=VoskAsr.readPcm(input);}
  StreamingTranscriber.Listener listener=new StreamingTranscriber.Listener(){
   public void started(long id,long at,boolean playback){}
   public void partial(long id,String text,long at,long ms){if(!text.isEmpty() && firstPartial[0]<0)firstPartial[0]=at;add("partial",id,text,at,ms);}
   public void completed(long id,String text,long at,long ms,boolean playback,boolean forced){if(!text.isEmpty())finals.add(text);add("final",id,text,at,ms);}
   private void add(String kind,long id,String text,long at,long ms){try{events.put(new JSONObject().put("kind",kind).put("id",id).put("text",text).put("audio_ms",at).put("decode_ms",ms));}catch(Exception e){throw new RuntimeException(e);}}
  };
  JSONObject report=new JSONObject();report.put("events",events);long started=SystemClock.elapsedRealtime();
  try(VoskAsr asr=new VoskAsr()) {
   asr.load(context);
   try(StreamingTranscriber transcriber=new StreamingTranscriber(asr.openStream(),listener)) {
    long at=0;
    for(int round=0;round<2;round++) {
     for(int offset=0;offset<audio.length;offset+=1280){short[] frame=frame(audio,offset);at+=frame.length*1000L/16000;transcriber.accept(frame,at,false);}
     for(int i=0;i<38;i++){at+=80;transcriber.accept(new short[1280],at,false);}
     assertEquals("Native endpoint did not produce one final",round+1,finals.size());
    }
    transcriber.finish(at);assertEquals("EOF duplicated a committed final",2,finals.size());
    assertTrue("No partial before audio EOF",firstPartial[0]>0 && firstPartial[0]<audio.length*1000L/16000);
    assertTrue(finals.toString(),finals.get(0).contains("郑州"));
    asr.close();transcriber.accept(new short[1280],at+80,false);report.put("model_kept_alive_until_stream_closed",true);
   }
  }
  report.put("first_partial_audio_ms",firstPartial[0]);report.put("input_audio_ms",audio.length*1000L/16000);report.put("natural_endpoint_finals",new JSONArray(finals));report.put("wall_ms",SystemClock.elapsedRealtime()-started);
  try(FileOutputStream output=context.openFileOutput("stream-native-report.json",0)){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
 }
 static short[] frame(float[] audio,int offset){int count=Math.min(1280,audio.length-offset);short[] frame=new short[count];for(int i=0;i<count;i++)frame[i]=(short)Math.max(-32768,Math.min(32767,Math.round(audio[offset+i]*32768f)));return frame;}
}
