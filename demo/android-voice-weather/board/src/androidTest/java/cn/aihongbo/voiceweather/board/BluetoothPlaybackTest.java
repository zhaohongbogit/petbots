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

/** Explicit user-requested audible test; refuses playback without the AirPods A2DP output. */
public final class BluetoothPlaybackTest {
    @Test public void playKnownSpeechThroughAirPods()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo target=null;
        for(AudioDeviceInfo device:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if(device.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP&&device.getProductName().toString().contains("AirPods"))target=device;
        assertNotNull("AirPods A2DP output not available; no playback attempted",target);
        byte[] pcm;
        try(InputStream input=context.getAssets().open("samples/zhengzhou.pcm");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);pcm=output.toByteArray();
        }
        AudioTrack track=new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
        JSONObject report=new JSONObject().put("sample","samples/zhengzhou.pcm").put("repetitions",3).put("volume_changed",false);
        try {
            assertEquals(AudioTrack.STATE_NO_STATIC_DATA,track.getState());
            assertTrue("Preferred AirPods output rejected",track.setPreferredDevice(target));
            assertEquals(pcm.length,track.write(pcm,0,pcm.length));
            assertEquals(AudioTrack.SUCCESS,track.setLoopPoints(0,pcm.length/2,2));
            track.play();AudioDeviceInfo routed=null;long deadline=SystemClock.elapsedRealtime()+15000;
            while(SystemClock.elapsedRealtime()<deadline){
                AudioDeviceInfo actual=track.getRoutedDevice();if(actual!=null)routed=actual;
                if(track.getPlaybackHeadPosition()>=pcm.length/2*3)break;
                SystemClock.sleep(100);
            }
            assertNotNull("Playback route missing",routed);
            report.put("route_name",routed.getProductName()).put("route_type",routed.getType()).put("route_id",routed.getId())
                .put("playback_head_frames",track.getPlaybackHeadPosition()).put("expected_frames",pcm.length/2*3);
            assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,routed.getType());assertEquals(target.getId(),routed.getId());
            assertTrue("Playback incomplete",track.getPlaybackHeadPosition()>=pcm.length/2*3);
            SystemClock.sleep(1500);
        }finally {
            track.stop();track.release();
            try(FileOutputStream output=context.openFileOutput("bluetooth-playback.json",0)){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
    }
}
