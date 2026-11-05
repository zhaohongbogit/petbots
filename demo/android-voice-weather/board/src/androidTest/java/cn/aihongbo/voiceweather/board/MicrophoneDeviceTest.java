package cn.aihongbo.voiceweather.board;

import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual foreground microphone test. No synthetic PCM enters the recognizer after warmup. */
public final class MicrophoneDeviceTest {
    @Test public void backgroundStopsMicrophone()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Intent intent=new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(intent);
        try {
            instrumentation.runOnMainSync(()->activity.begin(120));
            long deadline=SystemClock.elapsedRealtime()+90000;
            while(activity.session.captureStart==0&&!activity.session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(200);
            assertTrue("Microphone never started",activity.session.captureStart>0);
            SystemClock.sleep(3000);
            instrumentation.runOnMainSync(()->assertTrue(activity.moveTaskToBack(true)));
            deadline=SystemClock.elapsedRealtime()+15000;
            while(!activity.session.finished&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(200);
            assertTrue("Background did not stop session",activity.session.finished);
            assertEquals("",activity.session.error);
            long samples=activity.session.captured;SystemClock.sleep(1000);
            assertEquals("Capture continued in background",samples,activity.session.captured);
            assertTrue("Did not stop before timer",activity.session.captureEnd-activity.session.captureStart<20000);
        } finally {instrumentation.runOnMainSync(()->{if(activity.session!=null)activity.session.stop();activity.finish();});}
    }
    @Test public void warmedMicrophoneSession()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        int seconds=Integer.parseInt(InstrumentationRegistry.getArguments().getString("seconds","120"));
        assertTrue(seconds>=10&&seconds<=600);
        Intent intent=new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(intent);
        try {
            boolean weather=!InstrumentationRegistry.getArguments().getString("profile","weather").equals("dictation");
            boolean save=InstrumentationRegistry.getArguments().getString("save_audio","false").equals("true");
            instrumentation.runOnMainSync(()->activity.begin(seconds,weather,save));
            long deadline=SystemClock.elapsedRealtime()+(seconds+120)*1000L;
            while((activity.session==null||!activity.session.finished)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(500);
            assertNotNull(activity.session);assertTrue("Session timeout",activity.session.finished);
            MicSession session=activity.session;assertEquals(session.error,"",session.error);
            assertTrue("No microphone PCM",session.captured>=16000L*(seconds-2));
            SessionChecks.drainedAndAccounted(session);
            if(save){
                java.io.File wav=new java.io.File(instrumentation.getTargetContext().getFilesDir(),"mic-diagnostic.wav");
                assertEquals("Full PCM sample target",seconds*16000L,session.captured);
                assertEquals("Diagnostic WAV size",44+Math.min(session.captured,120*16000L)*2,wav.length());
                try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(wav))){assertEquals("RIFF header",0x52494646,input.readInt());}
            }
            SessionChecks.lossless(session);
        } finally {instrumentation.runOnMainSync(()->{if(activity.session!=null)activity.session.stop();activity.finish();});}
    }
}
