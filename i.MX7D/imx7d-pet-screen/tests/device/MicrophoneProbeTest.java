package cn.aihongbo.voiceweather;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.function.Consumer;

/** Finite raw capture, no Vosk, no gain, no audio playback and no network. */
public final class MicrophoneProbeTest extends Instrumentation {
    Activity activity;VoiceController controller;int seconds,rate,channels;String prefix;
    @Override public void onCreate(Bundle args){super.onCreate(args);rate=Integer.parseInt(args.getString("rate","44100"));channels=Integer.parseInt(args.getString("channels","2"));if((rate!=16000&&rate!=44100)||(channels!=1&&channels!=2))throw new IllegalArgumentException("Unsupported capture format");prefix=args.getString("prefix","h340-"+System.currentTimeMillis());if(!prefix.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid recording prefix");seconds=Math.min(120,Math.max(10,Integer.parseInt(args.getString("seconds","40"))));start();}
    void caption(String value){runOnMainSync(()->{TextView caption=find(activity.getWindow().getDecorView());if(caption!=null)caption.setText(value);});}
    TextView find(View view){if(view instanceof TextView&&"实时语音转写".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=find(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}return null;}
    @Override public void onStart(){Bundle result=new Bundle();AudioRecord recorder=null;try{
        VoiceController.factory=(host,listener)->{controller=new VoiceController(host,listener,new VoiceController.Input(){public void start(BoardSpeech.Mode mode,BoardSpeech.Listener l){l.ready();}public void stop(){}public void close(){}},new VoiceController.Output(){public void initialize(Consumer<Boolean> cb){cb.accept(true);}public boolean speak(String text,Runnable done){return false;}public void stop(){}public void close(){}},(city,day)->{throw new IOException("probe no network");});return controller;};
        Intent intent=new Intent();intent.setClassName("com.zhb.petscreen","com.zhb.petscreen.ChatActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=startActivitySync(intent);waitForIdleSync();
        int channelMask=channels==1?AudioFormat.CHANNEL_IN_MONO:AudioFormat.CHANNEL_IN_STEREO;int minimum=AudioRecord.getMinBufferSize(rate,channelMask,AudioFormat.ENCODING_PCM_16BIT);
        recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,channelMask,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,rate*channels*2*2));
        if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("recorder init");
        File directory=getTargetContext().getExternalFilesDir("diagnostics");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("mkdir");File stopFile=new File(directory,prefix+".stop");if(stopFile.exists())throw new IOException("Old stop marker");
        recorder.startRecording();AudioDeviceInfo route=recorder.getRoutedDevice();String routing=route==null?"unknown":route.getId()+"/"+route.getType()+"/"+route.getProductName();
        caption("录音诊断已就绪，请对耳麦说测试句");android.util.Log.i("AudioProbe","READY seconds="+seconds+" rate="+rate+" channels="+channels+" route="+routing);
        long target=(long)rate*channels*seconds,captured=0,start=SystemClock.elapsedRealtime(),maxRead=0;
        ByteArrayOutputStream pcm=new ByteArrayOutputStream();short[] frame=new short[rate*channels/10];JSONArray levels=new JSONArray();long lastLevel=start;double energy=0;int countLevel=0,peak=0;
        while(captured<target&&!stopFile.exists()&&SystemClock.elapsedRealtime()-start<(seconds+10)*1000L){long before=SystemClock.elapsedRealtime();int count=recorder.read(frame,0,(int)Math.min(frame.length,target-captured),AudioRecord.READ_BLOCKING);maxRead=Math.max(maxRead,SystemClock.elapsedRealtime()-before);if(count<=0)throw new IOException("read "+count);captured+=count;
            for(int i=0;i<count;i++){short sample=frame[i];pcm.write(sample&255);pcm.write((sample>>>8)&255);energy+=(double)sample*sample;peak=Math.max(peak,Math.abs((int)sample));countLevel++;}
            if(SystemClock.elapsedRealtime()-lastLevel>=1000){levels.put(new JSONObject().put("samples",captured).put("rms",Math.sqrt(energy/countLevel)).put("peak",peak));energy=0;peak=countLevel=0;lastLevel=SystemClock.elapsedRealtime();}
        }
        recorder.stop();recorder.release();recorder=null;
        if(captured!=target&&!stopFile.exists())throw new IOException("sample target "+captured+"/"+target);
        File wav=new File(directory,prefix+".wav");if(wav.exists()||new File(directory,prefix+".json").exists())throw new IOException("Refusing to overwrite recording");
        try(DataOutputStream output=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(wav)))){output.writeBytes("RIFF");le(output,36+pcm.size(),4);output.writeBytes("WAVEfmt ");le(output,16,4);le(output,1,2);le(output,channels,2);le(output,rate,4);le(output,rate*channels*2,4);le(output,channels*2,2);le(output,16,2);output.writeBytes("data");le(output,pcm.size(),4);pcm.writeTo(output);}
        JSONObject report=new JSONObject().put("route",routing).put("rate",rate).put("channels",channels).put("samples_interleaved",captured).put("wall_ms",SystemClock.elapsedRealtime()-start).put("max_read_ms",maxRead).put("stopped_by_controller",stopFile.exists()).put("levels",levels);
        try(FileOutputStream output=new FileOutputStream(new File(directory,prefix+".json"))){output.write(report.toString(2).getBytes("UTF-8"));}
        caption("诊断录音完成，正在分析音频");result.putString("stream","PASS raw capture "+wav.getAbsolutePath());finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL "+failure);finish(Activity.RESULT_CANCELED,result);}
    finally{if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}if(controller!=null)runOnMainSync(()->controller.close());VoiceController.factory=VoiceController::createDefault;}}
    static void le(DataOutputStream output,int value,int bytes)throws IOException{for(int i=0;i<bytes;i++)output.writeByte(value>>>(8*i));}
}
