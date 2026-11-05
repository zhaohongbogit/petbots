package cn.aihongbo.voiceweather;

import android.app.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Actual JNI + checksum-pinned models + unchanged recorded PCM, no mic/TTS/business request. */
public final class SherpaReplayTest extends Instrumentation {
    boolean stereo,hello;
    @Override public void onCreate(Bundle args){super.onCreate(args);hello="yes".equals(args.getString("hello"));stereo=hello||"yes".equals(args.getString("stereo"));start();}
    @Override public void onStart(){long handle=0;Bundle result=new Bundle();try{
        handle=SherpaNative.create(SherpaSpeech.prepare(getTargetContext()).getAbsolutePath());if(handle==0)throw new AssertionError("native initialization");
        File source=new File(getTargetContext().getExternalFilesDir("diagnostics"),hello?"h340-hello-20261030-d.wav":stereo?"h340-controlled-20261030-a.wav":"controlled-mono.wav");
        JSONArray events=new JSONArray();int wakeHits=0;List<String> texts=new ArrayList<>();
        for(boolean wake:new boolean[]{true,false}){
            SherpaNative.reset(handle,wake);
            try(DataInputStream in=new DataInputStream(new FileInputStream(source))){
                byte[] header=new byte[44];in.readFully(header);byte[] b=new byte[stereo?17640:2560];long samples=0;int n;
                while((n=in.read(b))!=-1){short[] pcm=new short[n/2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((b[2*i]&255)|(b[2*i+1]<<8));samples+=pcm.length;
                    String raw=stereo?SherpaNative.acceptStereo(handle,pcm,pcm.length):SherpaNative.accept(handle,pcm,pcm.length);if(raw==null)continue;JSONObject event=new JSONObject(raw);
                    if(wake&&event.optBoolean("segment")){
                        if(event.optInt("samples")<=16000*2.5){
                            String text=new JSONObject(SherpaNative.transcribe(handle)).optString("text");boolean matched=WakeUtterance.matches(text);if(matched)wakeHits++;
                            events.put(new JSONObject().put("wake_text",text).put("matched",matched).put("sample_end",samples));android.util.Log.i("SherpaReplay","wake_text="+text+" matched="+matched);
                        }
                        SherpaNative.reset(handle,true);
                    }
                    if(!wake&&event.optBoolean("segment")){
                        String text=new JSONObject(SherpaNative.transcribe(handle)).optString("text");texts.add(text);events.put(new JSONObject().put("text",text).put("sample_end",samples));
                        android.util.Log.i("SherpaReplay",text);
                    }
                }
            }
        }
        if(hello?wakeHits!=3:wakeHits!=0)throw new AssertionError("Wake regression count="+wakeHits);
        if(!texts.contains("上海明天天气怎么样")||!texts.contains(hello?"是的":"今天是星期五我正在测试麦克风"))throw new AssertionError("Wrong human ASR: "+texts);
        for(String name:new String[]{"abo","annuo","hallo","hello-near"}){
            SherpaNative.reset(handle,true);
            try(InputStream in=getContext().getAssets().open(name.equals("hello-near")?"hello-near.pcm":"wake/"+name+".pcm")){
                byte[] b=new byte[2560];int n;while((n=in.read(b))!=-1){short[] pcm=new short[n/2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((b[2*i]&255)|(b[2*i+1]<<8));rejectNegative(handle,SherpaNative.accept(handle,pcm,pcm.length),name);}
            }
            for(int i=0;i<20;i++)rejectNegative(handle,SherpaNative.accept(handle,new short[1280],1280),name);
        }
        JSONObject report=new JSONObject().put("hello_phrase",hello).put("stereo_capture_path",stereo).put("wake_hits",wakeHits).put("events",events).put("note","File replay only. Live microphone, audible reply and full dialogue need separate validation.");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir("diagnostics"),hello?"sherpa-jni-hello-replay.json":stereo?"sherpa-jni-stereo-replay.json":"sherpa-jni-replay.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
        result.putString("stream","PASS JNI human replay: wake_hits="+wakeHits+" weather and expected utterance exact; fixed recording regression only; no microphone, TTS or network");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){android.util.Log.e("SherpaReplay","failed",failure);result.putString("stream","FAIL "+failure);finish(Activity.RESULT_CANCELED,result);}
    finally{if(handle!=0)SherpaNative.destroy(handle);}}
    void rejectNegative(long handle,String value,String name)throws Exception{
        if(value==null)return;JSONObject event=new JSONObject(value);if(!event.optBoolean("segment"))return;
        if(event.optInt("samples")<=16000*2.5){String text=new JSONObject(SherpaNative.transcribe(handle)).optString("text");android.util.Log.i("SherpaReplay","negative="+name+" text="+text);if(WakeUtterance.matches(text))throw new AssertionError("False wake "+name+": "+text);}
        SherpaNative.reset(handle,true);
    }

}
