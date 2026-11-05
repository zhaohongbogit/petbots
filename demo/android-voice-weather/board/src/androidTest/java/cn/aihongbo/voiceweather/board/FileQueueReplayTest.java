package cn.aihongbo.voiceweather.board;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Replaces only the capture producer. Real ArrayBlockingQueue and Vosk worker are shared. */
public final class FileQueueReplayTest {
    @Test public void stopDrainsAgedTail()throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->{activity.session=MicSession.fileReplay(activity,120,new MicSession.Replay(3000,3000,false),(text,complete)->{});activity.session.start();});
            MicSession session=activity.session;long deadline=SystemClock.elapsedRealtime()+90000;
            while(session.captureStart==0&&!session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            assertTrue("File producer never started",session.captureStart>0);
            // Pause worker at 15s, stop input at 16s, then drain frames older than 1.5s.
            while(!session.finished&&SystemClock.elapsedRealtime()-session.captureStart<16000)SystemClock.sleep(50);
            session.stop();deadline=SystemClock.elapsedRealtime()+30000;
            while(!session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            SessionChecks.drainedAndAccounted(session);SessionChecks.lossless(session);
            assertTrue("Did not stop early",session.captured<18*16000L);
            assertTrue("Stopped before stall",session.captured>=15*16000L);
            assertTrue("File replay opened microphone",captureState(instrumentation).contains("closed"));
            JSONObject report=readReport(activity.getFilesDir());
            assertTrue("No aged tail observed",report.getLong("max_observed_queue_age_ms")>1500);
            long begin=-1,end=-1;JSONArray events=report.getJSONArray("events");
            for(int i=0;i<events.length();i++){
                JSONObject event=events.getJSONObject(i);
                if(event.getString("kind").equals("injected_stall_begin"))begin=event.getLong("elapsed_ms");
                if(event.getString("kind").equals("injected_stall_end"))end=event.getLong("elapsed_ms");
            }
            long inputEnd=report.getLong("capture_wall_ms");
            assertTrue("Stop did not occur inside injected stall",begin>=0&&begin<inputEnd&&end>inputEnd);
        } finally {
            if(activity.session!=null)activity.session.stop();
            long deadline=SystemClock.elapsedRealtime()+30000;
            while(activity.session!=null&&!activity.session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            instrumentation.runOnMainSync(activity::finish);
        }
    }
    @Test public void pacedFileThroughRealQueue()throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Bundle args=InstrumentationRegistry.getArguments();
        int seconds=Integer.parseInt(args.getString("seconds","120"));
        int stallMs=Integer.parseInt(args.getString("stall_ms","0"));
        boolean legacy=Boolean.parseBoolean(args.getString("legacy","false"));
        boolean expectDrops=Boolean.parseBoolean(args.getString("expect_drops","false"));
        assertTrue(seconds>=20&&seconds<=600);
        assertTrue("Microphone already open",captureState(instrumentation).contains("closed"));
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->{
                activity.session=MicSession.fileReplay(activity,seconds,new MicSession.Replay(1000,stallMs,legacy),(text,complete)->{});
                activity.session.start();
            });
            MicSession session=activity.session;long deadline=SystemClock.elapsedRealtime()+90000;
            while(session.captureStart==0&&!session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(200);
            assertTrue("File producer never started",session.captureStart>0);
            assertTrue("File replay opened microphone",captureState(instrumentation).contains("closed"));
            deadline=SystemClock.elapsedRealtime()+(seconds+90)*1000L;
            while(!session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(500);
            SessionChecks.drainedAndAccounted(session);
            assertEquals("Producer omitted samples",seconds*16000L,session.captured);
            assertTrue("Microphone still open",captureState(instrumentation).contains("closed"));
            if(expectDrops)assertTrue("Expected overload/legacy drops not reproduced",session.totalDroppedSamples()>0);
            else SessionChecks.lossless(session);
            JSONObject report=readReport(activity.getFilesDir());
            assertEquals("file_replay",report.getString("input_kind"));
            assertEquals("Wrong queue policy",legacy?"legacy_age_1500ms":"pressure_recovery",report.getString("queue_policy"));
            int expectedStalls=stallMs==0?0:Math.min(4,(seconds*1000-15000)/30000+1);
            assertEquals("Stall schedule incomplete",expectedStalls,report.getInt("injected_stalls"));
            assertTrue("Truncated timeline",report.getLong("omitted_events")==0);
            JSONArray results=report.getJSONArray("results");StringBuilder transcript=new StringBuilder();
            for(int i=0;i<results.length();i++)transcript.append(results.getJSONObject(i).getString("text").replace(" ",""));
            String expected="郑州明天的天气怎么样";int matches=0,position=0;
            while((position=transcript.indexOf(expected,position))>=0){matches++;position+=expected.length();}
            int minimum=expectDrops?1:(int)(seconds/3.09025*.7);
            assertTrue("Too few correct known phrases: "+matches+" < "+minimum,matches>=minimum);
        } finally {
            instrumentation.runOnMainSync(()->{if(activity.session!=null)activity.session.stop();});
            long deadline=SystemClock.elapsedRealtime()+30000;
            while(activity.session!=null&&!activity.session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(200);
            instrumentation.runOnMainSync(activity::finish);
        }
    }
    private static JSONObject readReport(File directory)throws Exception {
        try(InputStream input=new FileInputStream(new File(directory,"queue-replay.json"));ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
    private static String captureState(Instrumentation instrumentation)throws IOException {
        ParcelFileDescriptor descriptor=instrumentation.getUiAutomation().executeShellCommand("cat /proc/asound/card1/pcm0c/sub0/status");
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[512];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
