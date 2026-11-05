package cn.aihongbo.voiceweather;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.vosk.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Signed instrumentation substitutes only audio I/O and weather; production UI/controller are unchanged. */
public final class AluoIntegrationTest extends Instrumentation {
    int checks,queries;Activity activity;VoiceController controller;
    final FakeInput input=new FakeInput();final FakeOutput output=new FakeOutput();
    void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    interface Condition{boolean met()throws Exception;}
    void await(Condition condition,String reason)throws Exception{long deadline=SystemClock.elapsedRealtime()+6000;while(SystemClock.elapsedRealtime()<deadline){waitForIdleSync();if(condition.met())return;Thread.sleep(50);}throw new AssertionError(reason);}
    void onMain(Runnable run){runOnMainSync(run);waitForIdleSync();}
    void expectMode(BoardSpeech.Mode mode)throws Exception{await(()->input.active&&input.mode==mode,"expected "+mode);}
    void result(String text){onMain(()->input.listener.result(text,SystemClock.elapsedRealtime()));}
    void wake(){onMain(()->input.listener.wake("那 咯","",SystemClock.elapsedRealtime()));}
    void finishSpeech(){onMain(()->{Runnable done=output.pending;output.pending=null;if(done==null)throw new AssertionError("no speech");done.run();});}
    void command(String city)throws Exception{
        expectMode(BoardSpeech.Mode.WAKE);wake();await(()->"收到".equals(output.last),"ack");finishSpeech();expectMode(BoardSpeech.Mode.COMMAND);
        result(city+"明天天气怎么样");await(()->output.pending!=null&&output.last.contains("需要帮主人查询"),"weather prompt");finishSpeech();expectMode(BoardSpeech.Mode.CONFIRM);
    }
    void inspect(View view){
        check(!(view instanceof EditText),"no keyboard input");check(!(view instanceof Switch),"no config switches");check(!(view instanceof Button),"no action buttons");
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)inspect(((ViewGroup)view).getChildAt(i));
    }
    TextView caption(View view){if(view instanceof TextView&&"实时语音转写".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=caption(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        VoiceController.factory=(host,listener)->{controller=new VoiceController(host,listener,input,output,(city,day)->{queries++;return "测试预报："+city.name+"明天晴";});return controller;};
        Intent launch=new Intent();launch.setClassName("com.zhb.petscreen","com.zhb.petscreen.ChatActivity");launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=startActivitySync(launch);expectMode(BoardSpeech.Mode.WAKE);
        onMain(()->inspect(activity.getWindow().getDecorView()));
        TextView caption=caption(activity.getWindow().getDecorView());check(caption!=null,"caption present");
        onMain(()->input.listener.partial("我在说话"));check(caption.getText().toString().equals("我在说话"),"live partial caption");
        onMain(()->input.listener.transcript("普通讲话"));check(caption.getText().toString().equals("普通讲话"),"final caption");check(output.count==0&&queries==0,"ordinary speech no action");
        command("上海");check(input.active,"confirmation auto listening");
        result("需要");expectMode(BoardSpeech.Mode.CONFIRM);check(queries==0,"fragment no query");
        result("不用");expectMode(BoardSpeech.Mode.WAKE);check(queries==0,"decline no query");
        command("北京");result("是的");await(()->output.last.contains("测试预报：北京")&&output.pending!=null,"weather reply");check(queries==1,"confirmed query exactly once");check(!input.active,"playback pauses capture");finishSpeech();expectMode(BoardSpeech.Mode.WAKE);
        command("广州");Thread.sleep(8500);expectMode(BoardSpeech.Mode.WAKE);check(queries==1,"timeout no query");
        wake();await(()->output.pending!=null,"pending acknowledgement");Runnable late=output.pending;
        onMain(()->activity.moveTaskToBack(true));await(()->!input.active,"background stops listening");int starts=input.starts;onMain(late);Thread.sleep(700);check(input.starts==starts,"late TTS cannot restart capture");
        // Real ARMv7 native decode, no AudioRecord and no TTS playback.
        BoardSpeech speech=new BoardSpeech(getTargetContext());Method prepare=BoardSpeech.class.getDeclaredMethod("prepareModel",File.class);prepare.setAccessible(true);
        File directory=new File(getTargetContext().getFilesDir(),"aluo-vosk-small-cn-0.22");prepare.invoke(speech,directory);speech.close();
        try(Model model=new Model(directory.getAbsolutePath());Recognizer recognizer=new Recognizer(model,16000);BoardSpeech.WakeDecoder keyword=new BoardSpeech.WakeDecoder(model)){
            recognizer.setWords(true);long samples=0;
            for(String name:new String[]{"aluo","aluo-weather","hallo","mention","leading"}){
                ByteArrayOutputStream pcm=new ByteArrayOutputStream();if(name.equals("leading"))pcm.write(new byte[16000*3*2]);try(InputStream asset=getContext().getAssets().open("wake/"+(name.equals("leading")?"aluo-weather":name)+".pcm")){byte[] buffer=new byte[4096];int n;while((n=asset.read(buffer))!=-1)pcm.write(buffer,0,n);}
                byte[] audio=pcm.toByteArray();if(name.equals("aluo")){keyword.detect(new JSONObject(),audio,0);keyword.detect(new JSONObject(),audio,0);}
                JSONObject raw=decode(recognizer,audio);BoardSpeech.Wake match=keyword.detect(raw,audio,samples/16000.0);
                check(match.detected==(name.equals("aluo")||name.equals("aluo-weather")||name.equals("leading")),"native wake "+name+": "+raw.optString("text"));
                if(name.equals("aluo-weather")||name.equals("leading"))check(match.command.replace(" ","").equals("上海明天天气怎么样"),"native suffix alignment: "+match.command);
                samples+=audio.length/2;recognizer.reset();
            }
        }
        result.putString("stream","PASS "+checks+" checks: voice-only face, injected speech transitions, real ARMv7 wake PCM; no real mic/TTS/network");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL "+failure.toString());android.util.Log.e("AluoBoardTest","failure",failure);finish(Activity.RESULT_CANCELED,result);}
    finally{if(controller!=null)onMain(()->controller.close());if(activity!=null)onMain(()->activity.finish());VoiceController.factory=VoiceController::createDefault;}}
    static JSONObject decode(Recognizer recognizer,byte[] audio)throws Exception{
        JSONArray words=new JSONArray();StringBuilder text=new StringBuilder();
        for(int offset=0;offset<audio.length;offset+=2560){byte[] frame=Arrays.copyOfRange(audio,offset,Math.min(audio.length,offset+2560));if(recognizer.acceptWaveForm(frame,frame.length))append(new JSONObject(recognizer.getResult()),words,text);}
        append(new JSONObject(recognizer.getFinalResult()),words,text);return new JSONObject().put("result",words).put("text",text.toString());
    }
    static void append(JSONObject json,JSONArray words,StringBuilder text)throws Exception{if(text.length()>0)text.append(' ');text.append(json.optString("text"));JSONArray source=json.optJSONArray("result");if(source!=null)for(int i=0;i<source.length();i++)words.put(source.getJSONObject(i));}
    static final class FakeInput implements VoiceController.Input{
        volatile boolean active;volatile BoardSpeech.Mode mode;volatile BoardSpeech.Listener listener;int starts;
        public void start(BoardSpeech.Mode mode,BoardSpeech.Listener listener){this.mode=mode;this.listener=listener;active=true;starts++;listener.ready();}
        public void stop(){active=false;}public void close(){stop();}
    }
    static final class FakeOutput implements VoiceController.Output{
        volatile String last="";volatile Runnable pending;int count;
        public void initialize(Consumer<Boolean> ready){ready.accept(true);}public boolean speak(String text,Runnable done){last=text;pending=done;count++;return true;}public void stop(){pending=null;}public void close(){stop();}
    }
}
