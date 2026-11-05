package cn.aihongbo.voiceweather.board;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Explicit local-output test, independent of microphone quality and Bluetooth. */
public final class BoardSpeakerTest {
    @Test public void knownSpeechToBoardOutput()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo target=null;
        for(AudioDeviceInfo device:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){target=device;break;}
        assertNotNull("Local board speaker output unavailable",target);
        byte[] pcm;
        try(InputStream input=context.getAssets().open("samples/zhengzhou.pcm");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);pcm=bytes.toByteArray();
        }
        JSONObject report=new JSONObject().put("sample","samples/zhengzhou.pcm").put("repetitions",2).put("volume_changed",false);
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
        try{
            assertTrue("Local output preference rejected",track.setPreferredDevice(target));
            assertEquals(pcm.length,track.write(pcm,0,pcm.length));assertEquals(AudioTrack.SUCCESS,track.setLoopPoints(0,pcm.length/2,1));
            track.play();AudioDeviceInfo routed=null;long deadline=SystemClock.elapsedRealtime()+12000;
            while(SystemClock.elapsedRealtime()<deadline){AudioDeviceInfo actual=track.getRoutedDevice();if(actual!=null)routed=actual;if(track.getPlaybackHeadPosition()>=pcm.length)break;SystemClock.sleep(100);}
            assertNotNull("Playback route not reported",routed);
            report.put("route_type",routed.getType()).put("route_name",routed.getProductName()).put("route_id",routed.getId()).put("expected_frames",pcm.length).put("played_frames",track.getPlaybackHeadPosition());
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,routed.getType());assertEquals(target.getId(),routed.getId());assertTrue("Playback incomplete",track.getPlaybackHeadPosition()>=pcm.length);
            SystemClock.sleep(500);
        }finally{
            track.stop();track.release();
            try(FileOutputStream output=context.openFileOutput("board-speaker-test.json",0)){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
    }
}
