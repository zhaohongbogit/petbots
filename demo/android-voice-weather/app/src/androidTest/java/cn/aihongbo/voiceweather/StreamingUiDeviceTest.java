package cn.aihongbo.voiceweather;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class StreamingUiDeviceTest {
 private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();private MainActivity activity;
 @Test public void partialIsVisibleBeforeFinalAndQwenNeverBlocksNativeStream()throws Exception {
  launch();CountDownLatch qwenRelease=new CountDownLatch(1),qwenBlocked=new CountDownLatch(1),audioRelease=new CountDownLatch(1),partialSeen=new CountDownLatch(1);JSONObject report=new JSONObject();
  try {
   ((ExecutorService)field("worker")).execute(() -> {qwenBlocked.countDown();try{qwenRelease.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(qwenBlocked.await(3,TimeUnit.SECONDS));
   PcmMicrophone.Listener output=listener();AtomicBoolean first=new AtomicBoolean(true);
   StreamingTranscriber.Listener observed=new StreamingTranscriber.Listener(){
    public void started(long id,long at,boolean playback){output.started(id,at,playback);}
    public void partial(long id,String text,long at,long ms){output.partial(id,text,at,ms);if(!text.isEmpty() && first.compareAndSet(true,false)){partialSeen.countDown();try{audioRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    public void completed(long id,String text,long at,long ms,boolean playback,boolean forced){output.completed(id,text,at,ms,playback,forced);}
   };
   Future<?> audio=feed("zhengzhou",observed);assertTrue(partialSeen.await(10,TimeUnit.SECONDS));await(() -> number("streamPartialCount")>0,3000,"Partial not rendered");
   assertEquals("Partial was incorrectly committed",0,number("transcriptCount"));assertFalse("Partial was sent to Qwen",flag("semanticRunning"));assertEquals(0,((IntentBacklog<?>)field("backlog")).size());
   assertTrue(text().contains("实时转写（流式注入，临时）："));report.put("partial_visible_before_final",true);report.put("semantic_jobs_from_partial",0);
   SystemClock.sleep(150);ins.waitForIdleSync();
   android.graphics.Bitmap screenshot=ins.getUiAutomation().takeScreenshot();if(screenshot!=null){try(FileOutputStream out=activity.openFileOutput("stream-partial.png",0)){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
   audioRelease.countDown();audio.get(10,TimeUnit.SECONDS);await(() -> number("transcriptCount")==1,3000,"Final not committed");assertTrue(flag("semanticRunning"));
   await(() -> !((CommandHandoff)field("commandHandoff")).waiting(),3000,"Acknowledgement did not finish");
   feed("city-weather",listener()).get(10,TimeUnit.SECONDS);await(() -> number("transcriptCount")==2,3000,"Second final blocked by Qwen");
   assertTrue(number("partialsDuringQwen")>0);assertTrue(number("transcriptsDuringQwen")>0);assertTrue(text().contains("上海"));
   report.put("final_sentences_before_qwen_completed",number("transcriptCount"));report.put("partial_updates_while_qwen_busy",number("partialsDuringQwen"));report.put("final_sentences_while_qwen_busy",number("transcriptsDuringQwen"));
   click("停止 / 重置当前流程");qwenRelease.countDown();await(() -> !flag("semanticRunning"),15000,"Old semantic task did not finish");assertEquals(0,number("queryCount"));
   ((ExecutorService)field("ioWorker")).submit(() -> {}).get(3,TimeUnit.SECONDS);String log=new String(java.nio.file.Files.readAllBytes(new File(activity.getFilesDir(),"transcripts.jsonl").toPath()),java.nio.charset.StandardCharsets.UTF_8);assertTrue(log.contains("转写 · 流式注入"));report.put("final_transcripts_persisted",true);report.put("reset_prevented_old_action",true);
   try(FileOutputStream out=activity.openFileOutput("stream-ui-report.json",0)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  }finally{audioRelease.countDown();qwenRelease.countDown();ins.runOnMainSync(() -> activity.finish());ins.waitForIdleSync();}
 }
 private Future<?> feed(String name,StreamingTranscriber.Listener listener)throws Exception {
  float[] samples;try(InputStream input=activity.getAssets().open("samples/"+name+".pcm")){samples=VoskAsr.readPcm(input);}VoskAsr asr=(VoskAsr)field("asr");
  return ((ExecutorService)field("asrWorker")).submit(() -> {try(StreamingTranscriber stream=new StreamingTranscriber(asr.openStream(),listener)){for(int offset=0;offset<samples.length;offset+=1280)stream.accept(StreamingVoskDeviceTest.frame(samples,offset),SystemClock.elapsedRealtime(),false);stream.finish(SystemClock.elapsedRealtime());}catch(Exception e){throw new RuntimeException(e);}});
 }
 // Pre-arm the one-sentence window for wake-free audio fixtures. This does not test acoustic wake accuracy.
 private PcmMicrophone.Listener listener(){int session=number("generation");AtomicReference<PcmMicrophone.Listener> result=new AtomicReference<>();ins.runOnMainSync(() -> {try{java.lang.reflect.Field gate=MainActivity.class.getDeclaredField("wakeGate");gate.setAccessible(true);((WakeCommandGate)gate.get(activity)).openWindow(SystemClock.elapsedRealtime());for(java.lang.reflect.Method m:MainActivity.class.getDeclaredMethods())if(m.getName().equals("streamingListener")){m.setAccessible(true);result.set((PcmMicrophone.Listener)m.invoke(activity,session,"流式注入",null));}}catch(Exception e){throw new RuntimeException(e);}});return result.get();}
 private void launch()throws Exception {
  try(android.os.ParcelFileDescriptor fd=ins.getUiAutomation().executeShellCommand("am start -W -n cn.aihongbo.voiceweather/.MainActivity");InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
  await(() -> {ins.runOnMainSync(() -> {for(android.app.Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof MainActivity)activity=(MainActivity)a;});return activity!=null;},20000,"No activity");await(() -> text().contains("Vosk + Qwen3 已就绪"),120000,"Models not ready");
  ins.runOnMainSync(() -> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("ttsReady");f.setAccessible(true);f.setBoolean(activity,false);}catch(Exception e){throw new RuntimeException(e);}});
 }
 private Object field(String name){AtomicReference<Object> value=new AtomicReference<>();ins.runOnMainSync(() -> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);value.set(f.get(activity));}catch(Exception e){throw new RuntimeException(e);}});return value.get();}
 private int number(String name){return (Integer)field(name);}private boolean flag(String name){return (Boolean)field(name);}
 private void click(String label){ins.runOnMainSync(() -> {View v=find(activity.getWindow().getDecorView(),label);assertNotNull(v);v.performClick();});}
 private static View find(View v,String label){if(v instanceof Button && label.equals(((Button)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View value=find(((ViewGroup)v).getChildAt(i),label);if(value!=null)return value;}return null;}
 private String text(){AtomicReference<String> value=new AtomicReference<>();ins.runOnMainSync(() -> {StringBuilder out=new StringBuilder();collect(activity.getWindow().getDecorView(),out);value.set(out.toString());});return value.get();}
 private static void collect(View v,StringBuilder out){if(v instanceof TextView)out.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
 private void await(BooleanSupplier condition,long timeout,String message){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(30);}fail(message);}
}
