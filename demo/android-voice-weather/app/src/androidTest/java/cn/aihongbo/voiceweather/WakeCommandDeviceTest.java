package cn.aihongbo.voiceweather;

import android.app.Instrumentation;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Separates native synthetic PCM, final-text workflow, tagged echo, and real speaker/mic evidence. */
public class WakeCommandDeviceTest {
    private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    private final Context context=ins.getTargetContext();
    private MainActivity activity;

    @Test public void nativeVoskWakeAccuracy() throws Exception {
        JSONObject report=new JSONObject().put("input","macOS Tingting synthetic PCM, 16kHz mono PCM16, streamed in 80ms frames; not a live microphone");
        JSONArray rows=new JSONArray();boolean all=true;
        try(VoskAsr asr=new VoskAsr()) {
            asr.load(context);
            for(String name:new String[]{"aluo","aluo-weather"}) {
                float[] audio;
                try(InputStream source=ins.getContext().getAssets().open("wake/"+name+".pcm")){audio=VoskAsr.readPcm(source);}
                StringBuilder transcript=new StringBuilder();WakeEvidence evidence=WakeEvidence.NONE;
                try(VoskAsr.Stream stream=asr.openStream()) {
                    for(int offset=0;offset<audio.length;offset+=1280) {
                        int count=Math.min(1280,audio.length-offset);short[] frame=new short[count];
                        for(int i=0;i<count;i++)frame[i]=(short)Math.max(-32768,Math.min(32767,Math.round(audio[offset+i]*32768f)));
                        VoskAsr.Update update=stream.accept(frame);
                        if(update.endpoint && !update.text.isEmpty())transcript.append(update.text).append(' ');
                        if(update.endpoint && stream.wakeEvidence().detected)evidence=stream.wakeEvidence();
                    }
                    transcript.append(stream.finish());
                    if(stream.wakeEvidence().detected)evidence=stream.wakeEvidence();
                }
                String text=transcript.toString().trim();
                WakeCommandGate.Result result=new WakeCommandGate().accept(text,100,false,evidence);
                boolean accepted=result.kind==(name.equals("aluo")?WakeCommandGate.Kind.WAKE_ONLY:WakeCommandGate.Kind.COMMAND);
                rows.put(new JSONObject().put("fixture",name).put("raw_vosk",text).put("gate",result.kind.name()).put("command",result.text).put("accepted",accepted).put("acoustic_wake",evidence.detected));
                assertEquals("Raw dictation changed",asr.transcribe(audio),text);
                all &= accepted;
            }
        } finally {report.put("cases",rows);save("wake-native-report.json",report);}
        assertTrue("See wake-native-report.json for unmodified Vosk text",all);
    }

    @Test public void acknowledgementAndSimulatedWeather() throws Exception {
        JSONObject report=new JSONObject().put("input","synthetic PCM through actual Activity streaming Vosk, independent wake decoder, real Qwen and offline TTS; reset cancellation uses final-text injection");
        launch();
        try {
            assertFalse(((Switch)field("liveWeather")).isChecked());assertTrue("Offline Chinese TTS unavailable",flag("ttsReady"));
            int sequence=number("speechSequence");click("识别上海明天天气音频");await(() -> !flag("busy"),10000,"Unwoken sample did not finish");
            assertFalse(flag("semanticRunning"));assertEquals(sequence,number("speechSequence"));
            report.put("ordinary_speech_does_not_ack_or_call_qwen",true);

            feedWake("aluo-weather");
            await(() -> ((CommandHandoff)field("commandHandoff")).waiting(),10000,"Acoustic wake did not acknowledge");
            assertTrue(((CommandHandoff)field("commandHandoff")).waiting());
            assertFalse(log().contains("[Qwen 语义]"));
            await(() -> ((TextToSpeech)field("tts")).isSpeaking(),5000,"No actual acknowledgement TTS playback");
            report.put("ack_tts_playback_observed",true).put("model_waits_for_ack",true);
            await(() -> state()==DialogueEngine.State.CONFIRMING,30000,"No confirmation prompt");
            assertTrue(log().contains("需要帮主人查询上海明天的天气吗"));
            assertTrue(((TextView)field("speechDetail")).getText().toString().replace(" ","").contains("语义输入：上海明天天气怎么样"));
            assertTrue(log().contains("落 上海 明天 天气 怎么样"));
            report.put("raw_vosk_wake_error_preserved",true).put("command_started_from_native_audio",true);
            assertEquals(sequence+2,number("speechSequence"));assertEquals(0,number("queryCount"));
            report.put("wake_prefix_removed_for_qwen_only",true).put("qwen_weather_and_city_recognized",true);
            sequence=number("speechSequence");click("识别否定音频");
            await(() -> !flag("semanticRunning") && state()==DialogueEngine.State.LISTENING,20000,"Rejection did not finish");
            assertEquals(sequence,number("speechSequence"));assertEquals(0,number("queryCount"));
            report.put("unwoken_rejection_has_no_extra_ack_or_query",true);

            click("停止 / 重置当前流程");feedWake("aluo");
            await(() -> !flag("busy"),10000,"Standalone wake acknowledgement did not finish");
            assertTrue(log().contains("开启 8 秒下一句接收窗口"));
            click("识别上海明天天气音频");
            await(() -> state()==DialogueEngine.State.CONFIRMING,30000,"Standalone wake did not authorize next command");
            report.put("standalone_wake_next_sentence",true);
            click("识别肯定音频");
            await(() -> log().contains("[预报] 模拟数据：上海明天"),30000,"Synthetic yes did not execute simulated weather");
            await(() -> state()==DialogueEngine.State.LISTENING && !flag("audioBlocked"),30000,"Forecast TTS did not finish");
            assertEquals(0,number("queryCount"));
            report.put("vosk_yes_confirmed_simulated_forecast",true).put("real_weather_request_count",number("queryCount"));

            click("停止 / 重置当前流程");submit("阿洛，上海明天天气怎么样");
            assertTrue(((CommandHandoff)field("commandHandoff")).waiting());click("停止 / 重置当前流程");
            int resetSequence=number("speechSequence");SystemClock.sleep(1800);
            assertFalse(flag("semanticRunning"));assertEquals(resetSequence,number("speechSequence"));
            assertEquals(DialogueEngine.State.LISTENING,state());
            report.put("reset_during_ack_prevents_old_task",true);
        } finally {report.put("activity_log",log());save("wake-workflow-report.json",report);close();}
    }

    @Test public void speakerMicrophoneEchoAndTaggedEchoCannotTriggerTasks() throws Exception {
        JSONObject report=new JSONObject().put("input","real device speaker -> microphone plus separately labelled tagged final-text injection");
        launch();AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        try {
            assertTrue("Offline Chinese TTS unavailable",flag("ttsReady"));
            assertFalse(((Switch)field("liveWeather")).isChecked());
            report.put("speaker_volume",audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            ins.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
            click("开始麦克风监听");assertTrue(flag("runningMic"));SystemClock.sleep(800);
            int transcripts=number("transcriptCount"),sequence=number("speechSequence");
            ins.runOnMainSync(() -> invoke("speak",new Class<?>[]{String.class,Runnable.class},"阿洛，上海明天天气怎么样。是的。",(Runnable)() -> {}));
            await(() -> ((TextToSpeech)field("tts")).isSpeaking(),5000,"Echo test TTS did not play");
            report.put("real_tts_playback_observed",true);
            assertTrue(flag("audioBlocked"));
            // Deliberately recognisable echo injected with playback=true tests the deterministic guard.
            final int session=number("generation");
            ins.runOnMainSync(() -> {
                PcmMicrophone.Listener listener=(PcmMicrophone.Listener)invoke("streamingListener",new Class<?>[]{int.class,String.class,jobClass()},session,"标记回声注入",null);
                long at=SystemClock.elapsedRealtime();listener.started(991,at,true);
                listener.completed(991,"阿洛 上海 明天 天气 怎么样 是的",at,0,true,false);
            });
            await(() -> !flag("audioBlocked"),20000,"Echo test TTS did not finish");
            SystemClock.sleep(6000); // Include recognizer endpoint delay and trailing speaker sound.
            assertFalse(flag("semanticRunning"));assertEquals(DialogueEngine.State.LISTENING,state());
            assertEquals(sequence+1,number("speechSequence"));assertEquals(0,number("queryCount"));
            String echoLog=log();
            report.put("tagged_echo_guard_passed",echoLog.contains("转写 · 标记回声注入") && echoLog.contains("播报重叠或对话阶段已改变"));
            report.put("microphone_final_count",Math.max(0,number("transcriptCount")-transcripts-1));
            report.put("microphone_transcript_observed",echoLog.contains("转写 · 麦克风"));
            report.put("unexpected_acknowledgements",number("speechSequence")-sequence-1);
            report.put("qwen_called",echoLog.contains("[Qwen 语义]"));
            report.put("real_weather_request_count",number("queryCount"));
            assertFalse(echoLog.contains("[Qwen 语义]"));
            click("停止 / 重置当前流程");
        } finally {
            report.put("activity_log",log());save("wake-echo-report.json",report);close();
        }
    }

    @Test public void acousticSpeakerCommandAndConfirmation()throws Exception {
        boolean external="true".equals(InstrumentationRegistry.getArguments().getString("externalSpeaker"));
        JSONObject report=new JSONObject().put("input",external?"synthetic command from external computer speaker into real phone microphone; not human speech":"synthetic user command played by test MediaPlayer through device speaker into real microphone, not tagged as assistant playback; not human speech");
        launch();
        try {
            assertFalse(((Switch)field("liveWeather")).isChecked());
            ins.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
            click("开始麦克风监听");SystemClock.sleep(1000);int sequence=number("speechSequence");
            playPcm("aluo-weather",true);
            await(() -> number("speechSequence")>sequence,15000,"Real microphone did not acknowledge acoustic wake");
            report.put("microphone_woke",true).put("raw_transcript_preserved",true);
            await(() -> state()==DialogueEngine.State.CONFIRMING,35000,"Real microphone did not wake and reach confirmation");
            report.put("microphone_woke",true).put("received_and_prompt_tts_count",number("speechSequence")-sequence);
            assertEquals(sequence+2,number("speechSequence"));
            assertTrue(log().contains("独立声学唤醒"));assertTrue(log().contains("转写 · 麦克风"));
            SystemClock.sleep(1500);playPcm("yes",false);
            await(() -> log().contains("[预报] 模拟数据：上海明天"),30000,"Real microphone affirmative reply did not execute simulated weather");
            await(() -> state()==DialogueEngine.State.LISTENING && !flag("audioBlocked"),30000,"Simulated forecast did not finish");
            SystemClock.sleep(6000);
            assertEquals(sequence+3,number("speechSequence"));assertFalse(flag("semanticRunning"));assertEquals(0,number("queryCount"));
            report.put("microphone_confirmed_simulation",true).put("no_extra_echo_responses",true).put("real_weather_request_count",number("queryCount"));
            click("停止 / 重置当前流程");
        } finally {report.put("activity_log",log());save("wake-acoustic-workflow.json",report);close();}
    }

    private void playPcm(String name,boolean fixture)throws Exception {
        if("true".equals(InstrumentationRegistry.getArguments().getString("externalSpeaker"))) {
            long nonce=SystemClock.elapsedRealtime();
            save("wake-speaker-request.json",new JSONObject().put("name",name).put("fixture",fixture).put("nonce",nonce));
            File done=new File(context.getFilesDir(),"wake-speaker-done-"+nonce);
            await(done::isFile,60000,"External speaker did not complete requested audio");return;
        }
        byte[] pcm;try(InputStream input=(fixture?ins.getContext():context).getAssets().open((fixture?"wake/":"samples/")+name+".pcm")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);pcm=bytes.toByteArray();}
        java.nio.ByteBuffer header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        File file=new File(context.getCacheDir(),"wake-test-speaker.wav");try(FileOutputStream out=new FileOutputStream(file)){out.write(header.array());out.write(pcm);}
        android.media.MediaPlayer player=new android.media.MediaPlayer();
        try {player.setDataSource(file.getAbsolutePath());player.prepare();player.start();while(player.isPlaying())SystemClock.sleep(30);}finally{player.release();}
    }

    private Class<?> jobClass(){try{return Class.forName("cn.aihongbo.voiceweather.MainActivity$SemanticJob");}catch(Exception e){throw new RuntimeException(e);}}
    private void launch() throws Exception {
        try(android.os.ParcelFileDescriptor fd=ins.getUiAutomation().executeShellCommand("am start -W -n cn.aihongbo.voiceweather/.MainActivity");InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
        await(() -> {ins.runOnMainSync(() -> {for(android.app.Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof MainActivity)activity=(MainActivity)a;});return activity!=null;},20000,"No foreground Activity; unlock the device");
        await(() -> flag("ready") && flag("qwenReady"),120000,"Models not ready");
        await(() -> flag("ttsReady"),10000,"No offline Chinese TTS");
        click("停止 / 重置当前流程");
    }
    private Object field(String name){AtomicReference<Object> value=new AtomicReference<>();ins.runOnMainSync(() -> {try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);value.set(f.get(activity));}catch(Exception e){throw new RuntimeException(e);}});return value.get();}
    private Object invoke(String name,Class<?>[] types,Object...args){try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
    private boolean flag(String name){return (Boolean)field(name);}
    private int number(String name){return (Integer)field(name);}
    private String log(){return activity==null?"":field("visibleLog").toString();}
    private DialogueEngine.State state(){return ((DialogueEngine)field("engine")).state();}
    private void submit(String text){ins.runOnMainSync(() -> invoke("submitText",new Class<?>[]{String.class,String.class},text,"设备验证文字注入"));}
    private void feedWake(String name)throws Exception {
        float[] audio;try(InputStream input=ins.getContext().getAssets().open("wake/"+name+".pcm")){audio=VoskAsr.readPcm(input);}
        ins.runOnMainSync(() -> {
            Object ticket=invoke("ticket",new Class<?>[]{String.class,String.class,long.class,boolean.class,long.class},"","唤醒PCM注入",SystemClock.elapsedRealtime(),false,0L);
            invoke("submitAsr",new Class<?>[]{float[].class,String.class,jobClass()},audio,null,ticket);
        });
    }
    private void click(String label){ins.runOnMainSync(() -> {View view=find(activity.getWindow().getDecorView(),label);assertNotNull(label,view);assertTrue(view.isEnabled());assertTrue(view.performClick());});}
    private static View find(View view,String label){if(view instanceof Button && label.equals(((Button)view).getText().toString()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View child=find(((ViewGroup)view).getChildAt(i),label);if(child!=null)return child;}return null;}
    private void await(BooleanSupplier check,long timeout,String message){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(check.getAsBoolean())return;SystemClock.sleep(30);}fail(message+"\n"+log());}
    private void save(String name,JSONObject report)throws Exception{try(FileOutputStream output=context.openFileOutput(name,0)){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
    private void close(){if(activity!=null){ins.runOnMainSync(() -> activity.finish());ins.waitForIdleSync();}}
}
