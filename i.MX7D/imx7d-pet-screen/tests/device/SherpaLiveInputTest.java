package cn.aihongbo.voiceweather;

import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import java.io.*;import java.util.concurrent.*;import java.util.function.Consumer;

/** Real production capture/ASR and board TTS, explicitly bypassing wake. No weather/network. */
public final class SherpaLiveInputTest extends Instrumentation {
    Activity activity;VoiceController controller;SherpaSpeech input;BoardVoiceOutput output;
    final CountDownLatch finished=new CountDownLatch(1),spoken=new CountDownLatch(1),outputReady=new CountDownLatch(1);
    volatile String transcript,error;volatile boolean voiceReady;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    TextView find(View v){if(v instanceof TextView&&"实时语音转写".contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i));if(t!=null)return t;}return null;}
    void caption(String text){runOnMainSync(()->{TextView t=find(activity.getWindow().getDecorView());if(t!=null)t.setText(text);});}
    @Override public void onStart(){Bundle result=new Bundle();try{
        VoiceController.factory=(host,listener)->{controller=new VoiceController(host,listener,new VoiceController.Input(){public void start(BoardSpeech.Mode m,BoardSpeech.Listener l){l.ready();}public void stop(){}public void close(){}},new VoiceController.Output(){public void initialize(Consumer<Boolean> cb){cb.accept(true);}public boolean speak(String t,Runnable d){return false;}public void stop(){}public void close(){}},(city,day)->{throw new IOException("Diagnostic has no network");});return controller;};
        Intent intent=new Intent();intent.setClassName("com.zhb.petscreen","com.zhb.petscreen.ChatActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=startActivitySync(intent);
        runOnMainSync(()->{output=new BoardVoiceOutput(getTargetContext());output.initialize(ok->{voiceReady=ok;outputReady.countDown();});});
        if(!outputReady.await(20,TimeUnit.SECONDS)||!voiceReady)throw new IOException("Offline TTS unavailable");
        input=new SherpaSpeech(getTargetContext(),45000);
        input.start(BoardSpeech.Mode.COMMAND,new BoardSpeech.Listener(){
            public void ready(){caption("真人识别诊断已就绪，请说上海明天天气怎么样");android.util.Log.i("AluoLiveProbe","READY actual microphone; wake bypassed; no weather/network");}
            public void speechStarted(long received){android.util.Log.i("AluoLiveProbe","VOICE_DETECTED");}
            public void partial(String text){}public void transcript(String text){caption(text);}
            public void result(String text,long received){transcript=text;input.stop();android.util.Log.i("AluoLiveProbe","RESULT="+text);caption(text);runOnMainSync(()->{if(!output.speak("我听到的是，"+text,spoken::countDown))spoken.countDown();});finished.countDown();}
            public void error(String reason){error=reason;finished.countDown();}public void ended(){finished.countDown();}
        });
        if(!finished.await(110,TimeUnit.SECONDS))throw new IOException("Diagnostic timeout");
        if(error!=null)throw new IOException(error);
        if(transcript==null)throw new IOException("No recognized speech");
        if(!spoken.await(25,TimeUnit.SECONDS))throw new IOException("TTS callback timeout");
        if(!"上海明天天气怎么样".equals(transcript))throw new AssertionError("Wrong real ASR: "+transcript);
        result.putString("stream","PASS live microphone ASR exact; TTS terminal callback; human audibility needs confirmation; wake explicitly bypassed; no network");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){android.util.Log.e("AluoLiveProbe","FAIL",failure);result.putString("stream","FAIL "+failure);finish(Activity.RESULT_CANCELED,result);}
    finally{if(input!=null)input.close();runOnMainSync(()->{if(output!=null)output.close();if(controller!=null)controller.close();});VoiceController.factory=VoiceController::createDefault;}}
}
