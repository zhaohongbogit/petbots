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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Exercises the actual Activity + Vosk with delayed and real Qwen processing. */
public class AsyncTranscriptionDeviceTest {
 private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
 private MainActivity activity;
 @Test public void voskRecordsWhileQwenWorkerIsBlockedAndOldResultsCannotAct()throws Exception {
  launch();CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(1);JSONObject report=new JSONObject();
  try {
   ExecutorService qwen=(ExecutorService)field("worker");qwen.execute(() -> {started.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   assertTrue(started.await(3,TimeUnit.SECONDS));submit("阿洛，今天工作有点忙");assertTrue(flag("semanticRunning"));
   await(() -> !((CommandHandoff)field("commandHandoff")).waiting(),3000,"Acknowledgement did not finish");
   long start=SystemClock.elapsedRealtime();click("识别上海明天天气音频");click("识别肯定音频");click("识别否定音频");
   await(() -> number("transcriptCount")>=4,15000,"ASR stalled behind Qwen");
   assertTrue("Qwen should still be blocked",flag("semanticRunning"));assertEquals(4,number("transcriptCount"));
   assertTrue(text().contains("上海 明天 天气 怎么样"));assertTrue(text().replace(" ","").contains("是的"));assertTrue(text().replace(" ","").contains("不用"));
   report.put("blocked_qwen_asr_completed_ms",SystemClock.elapsedRealtime()-start);report.put("transcripts_before_qwen_completed",number("transcriptCount"));report.put("transcripts_during_qwen",number("transcriptsDuringQwen"));
   for(int i=0;i<20;i++)submit("阿洛，连续记录第"+i+"句");
   assertEquals(24,number("transcriptCount"));assertEquals(8,((IntentBacklog<?>)field("backlog")).size());assertTrue(number("skippedSemantics")>=12);
   report.put("all_transcripts_after_burst",number("transcriptCount"));report.put("bounded_semantic_backlog",((IntentBacklog<?>)field("backlog")).size());report.put("semantic_overflow_skipped",number("skippedSemantics"));
   ((ExecutorService)field("ioWorker")).submit(() -> {}).get(3,TimeUnit.SECONDS);
   String log=new String(java.nio.file.Files.readAllBytes(new java.io.File(activity.getFilesDir(),"transcripts.jsonl").toPath()),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue("Raw text was not persisted before Qwen finished",log.contains("上海 明天 天气 怎么样"));report.put("persisted_before_semantic_completion",true);
   // Reset removes queued city/yes/no, so delayed results cannot become a fresh confirmation.
   click("停止 / 重置当前流程");release.countDown();await(() -> !flag("semanticRunning"),15000,"Old task failed to finish");
   assertEquals(DialogueEngine.State.LISTENING,((DialogueEngine)field("engine")).state());assertEquals(0,number("queryCount"));
   report.put("reset_prevented_old_action",true);save("async-blocked-report.json",report);
  } finally {release.countDown();close();}
 }
 @Test public void realQwenAndVoskOverlapAndMicrophoneKeepsAcceptingDuringPlayback()throws Exception {
  launch();JSONObject report=new JSONObject();
  try {
   ins.getUiAutomation().grantRuntimePermission("cn.aihongbo.voiceweather",android.Manifest.permission.RECORD_AUDIO);
   click("开始麦克风监听");assertTrue(flag("runningMic"));
   submit("阿洛，今天工作有点忙");assertTrue(flag("semanticRunning"));
   await(() -> !((CommandHandoff)field("commandHandoff")).waiting(),3000,"Acknowledgement did not finish");
   Object microphone=field("microphone");java.lang.reflect.Field f=microphone.getClass().getDeclaredField("accepting");f.setAccessible(true);BooleanSupplier accepting=(BooleanSupplier)f.get(microphone);
   assertTrue("Qwen blocks capture",accepting.getAsBoolean());
   ins.runOnMainSync(() -> {try{java.lang.reflect.Field b=MainActivity.class.getDeclaredField("audioBlocked");b.setAccessible(true);b.setBoolean(activity,true);}catch(Exception e){throw new RuntimeException(e);}});
   assertTrue("Playback blocks capture",accepting.getAsBoolean());
   // Playback-associated ASR must be displayed, while excluded from semantic execution.
   click("识别上海明天天气音频");await(() -> number("transcriptCount")>=2 && text().contains("上海 明天 天气 怎么样"),15000,"Playback-associated final text missing");
   assertTrue(text().contains("上海 明天 天气 怎么样"));assertTrue(text().contains("播报重叠或对话阶段已改变"));
   report.put("capture_accepts_while_qwen_busy",true);report.put("capture_accepts_during_playback",true);report.put("transcripts_during_qwen",number("transcriptsDuringQwen"));report.put("recorded_without_action_during_playback",true);
   assertTrue("No ASR result completed during real Qwen work",number("transcriptsDuringQwen")>0);
   click("停止 / 重置当前流程");await(() -> !flag("semanticRunning"),15000,"Qwen cancel did not finish");assertEquals(0,number("queryCount"));
   save("async-real-report.json",report);
  }finally {close();}
 }
 @Test public void consentBelongsToItsTurnAndTimelyReplySurvivesSlowQwen()throws Exception {
  launch();CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(1);JSONObject report=new JSONObject();
  try {
   submit("阿洛，上海明天天气怎么样");assertTrue(flag("semanticRunning"));submit("是的");
   await(() -> ((DialogueEngine)field("engine")).state()==DialogueEngine.State.CONFIRMING,30000,"No confirmation prompt");
   assertEquals(0,number("queryCount"));assertEquals(0,((IntentBacklog<?>)field("backlog")).size());assertTrue(text().contains("播报重叠或对话阶段已改变"));
   report.put("consent_before_prompt_discarded",true);
   ((ExecutorService)field("worker")).execute(() -> {started.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   assertTrue(started.await(3,TimeUnit.SECONDS));submit("是的");SystemClock.sleep(9000);
   assertEquals("Reply received on time must not expire while Qwen is busy",DialogueEngine.State.CONFIRMING,((DialogueEngine)field("engine")).state());
   report.put("confirmation_waited_beyond_8_seconds_for_qwen",true);release.countDown();
   await(() -> text().contains("[预报] 模拟数据：上海明天"),20000,"Timely consent was lost");report.put("timely_consent_executed_simulated_weather",true);save("async-consent-report.json",report);
  }finally{release.countDown();close();}
 }
 private void launch()throws Exception {
  try(android.os.ParcelFileDescriptor fd=ins.getUiAutomation().executeShellCommand("am start -W -n cn.aihongbo.voiceweather/.MainActivity");java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
  await(() -> {ins.runOnMainSync(() -> {for(android.app.Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof MainActivity)activity=(MainActivity)a;});return activity!=null;},20000,"No activity");
  await(() -> text().contains("Vosk + Qwen3 已就绪"),120000,"Models not ready");
  // Keep these concurrency tests deterministic; actual audible TTS needs a separate device check.
  ins.runOnMainSync(() -> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("ttsReady");f.setAccessible(true);f.setBoolean(activity,false);}catch(Exception e){throw new RuntimeException(e);}});
 }
 private Object field(String name){AtomicReference<Object> value=new AtomicReference<>();ins.runOnMainSync(() -> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);value.set(f.get(activity));}catch(Exception e){throw new RuntimeException(e);}});return value.get();}
 private boolean flag(String name){return (Boolean)field(name);}private int number(String name){return (Integer)field(name);}
 private void submit(String text){ins.runOnMainSync(() -> {EditText view=(EditText)find(activity.getWindow().getDecorView(),null,EditText.class);view.setText(text);});click("送入一句文字");}
 private void click(String label){ins.runOnMainSync(() -> {View v=find(activity.getWindow().getDecorView(),label,Button.class);assertNotNull(label,v);assertTrue(v.isEnabled());assertTrue(v.performClick());});}
 private String text(){AtomicReference<String> out=new AtomicReference<>();ins.runOnMainSync(() -> {StringBuilder b=new StringBuilder();collect(activity.getWindow().getDecorView(),b);out.set(b.toString());});return out.get();}
 private static void collect(View view,StringBuilder out){if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),out);}
 private static View find(View view,String label,Class<?> type){if(type.isInstance(view) && (label==null || view instanceof TextView && label.equals(((TextView)view).getText().toString())))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),label,type);if(result!=null)return result;}return null;}
 private void await(BooleanSupplier condition,long timeout,String error){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(50);}fail(error+"\n"+(activity==null?"":text()));}
 private void save(String file,JSONObject report)throws Exception{try(java.io.FileOutputStream out=activity.openFileOutput(file,0)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
 private void close(){if(activity!=null){ins.runOnMainSync(() -> activity.finish());ins.waitForIdleSync();}}
}
