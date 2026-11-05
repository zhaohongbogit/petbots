package cn.aihongbo.voiceweather;
import android.app.*;import android.os.*;import java.util.concurrent.*;
/** Explicitly invoked real offline TTS; no microphone, network or volume changes. */
public final class ReplyProbeTest extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();final BoardVoiceOutput[] output=new BoardVoiceOutput[1];try{
  CountDownLatch ready=new CountDownLatch(1),done=new CountDownLatch(1);boolean[] available={false},queued={false};
  runOnMainSync(()->{output[0]=new BoardVoiceOutput(getTargetContext());output[0].initialize(ok->{available[0]=ok;ready.countDown();});});
  if(!ready.await(20,TimeUnit.SECONDS)||!available[0])throw new IllegalStateException("offline TTS unavailable");
  runOnMainSync(()->queued[0]=output[0].speak("收到，这是阿洛的语音回复测试",done::countDown));
  if(!queued[0]||!done.await(25,TimeUnit.SECONDS))throw new IllegalStateException("playback not completed");
  result.putString("stream","PASS offline TTS queued and terminal callback; human audibility still needs confirmation");finish(Activity.RESULT_OK,result);
 }catch(Throwable failure){result.putString("stream","FAIL "+failure);finish(Activity.RESULT_CANCELED,result);}finally{if(output[0]!=null)runOnMainSync(()->output[0].close());}}
}
