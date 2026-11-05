package cn.aihongbo.voiceweather;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Voice-only orchestration, independent of the face layout. No user-facing configuration. */
public final class VoiceController implements AutoCloseable {
    public interface Listener { void transcript(String text);void status(String text);void speaking(boolean active); }
    interface Input {void start(BoardSpeech.Mode mode,BoardSpeech.Listener listener);void stop();void close();}
    interface Output {void initialize(Consumer<Boolean> ready);boolean speak(String text,Runnable done);default boolean playbackSucceeded(){return true;}void stop();void close();}
    interface Weather {String query(CityCatalog.City city,int day)throws Exception;}
    interface Factory {VoiceController create(Activity activity,Listener listener);}
    // Package-private seam for signed instrumentation; no Intent/network testing backdoor.
    static Factory factory=VoiceController::new;
    static VoiceController createDefault(Activity activity,Listener listener){return new VoiceController(activity,listener);}
    public static VoiceController create(Activity activity,Listener listener){return factory.create(activity,listener);}
    private final Activity activity;
    private final Listener listener;
    private final Input input;
    private final Output output;
    private final Weather weather;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final DialogueEngine engine=new DialogueEngine();
    private final CityCatalog catalog;
    private final HbcTravelSession travel;
    private boolean travelPromptComplete;
    private final String base,key,model;
    private volatile boolean foreground,closed;
    private boolean outputInitialized,outputReady,processing,speaking,confirmPending;
    private volatile int epoch;
    private volatile HttpURLConnection connection;
    private BoardSpeech.Mode mode;
    private CityCatalog.City target;
    private final Runnable timer=new Runnable(){public void run(){
        if(!foreground||closed)return;
        if(!confirmPending&&engine.expire(now())==DialogueEngine.Action.TIMED_OUT){stopInput();notice("确认超时，未查询");idle();}
        main.postDelayed(this,250);
    }};
    private static long now(){return SystemClock.elapsedRealtime();}
    private VoiceController(Activity activity,Listener listener){this(activity,listener,new SherpaSpeech(activity),new BoardVoiceOutput(activity),WeatherClient::query);}
    VoiceController(Activity activity,Listener listener,Input input,Output output,Weather weather){
        this(activity,listener,input,output,weather,new HbcTravelSession(HbcAgentClient.from(activity),VoiceController::now,HbcFixedLogin.load(activity)));
    }
    VoiceController(Activity activity,Listener listener,Input input,Output output,Weather weather,HbcTravelSession travel){
        this.activity=activity;this.listener=listener;this.input=input;this.output=output;this.weather=weather;this.travel=travel;
        try{catalog=new CityCatalog(new InputStreamReader(activity.getAssets().open("cities.tsv"),"UTF-8"));}catch(IOException failure){throw new IllegalStateException("城市表不可用",failure);}
        SharedPreferences config=activity.getSharedPreferences("pet-chat",Activity.MODE_PRIVATE);
        base=config.getString("llm_base","");key=config.getString("llm_key","");model=config.getString("llm_model","");
        output.initialize(ready->main.post(()->{if(closed)return;outputInitialized=true;outputReady=ready;if(foreground)idle();}));
    }
    public void resume(){
        if(closed||foreground)return;foreground=true;main.post(timer);
        if(!outputInitialized){notice("正在准备语音…");return;}idle();
    }
    public void pause(){
        foreground=false;travel.suspend();travelPromptComplete=false;stopInput();processing=false;speaking=false;confirmPending=false;target=null;engine.reset();output.stop();listener.speaking(false);
        HttpURLConnection c=connection;if(c!=null)c.disconnect();main.removeCallbacks(timer);
    }
    public void permissionResult(boolean granted){if(foreground&&!closed){if(granted)idle();else notice("麦克风权限未允许，无法语音沟通");}}
    private void notice(String value){if(foreground)listener.status(value);android.util.Log.i("AluoBoard",value);}
    private void stopInput(){epoch++;input.stop();mode=null;}
    private void idle(){if(foreground&&!closed&&!processing&&!speaking)listen(BoardSpeech.Mode.WAKE);}
    private void listen(BoardSpeech.Mode next){
        if(!foreground||closed)return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){notice("需要麦克风权限才能语音沟通");activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},12);return;}
        stopInput();int ticket=epoch;mode=next;notice("正在准备监听…");
        input.start(next,new BoardSpeech.Listener(){
            public void ready(){main.post(()->{if(!current(ticket))return;if(next==BoardSpeech.Mode.COMMAND&&travel.active()&&travelPromptComplete){travel.inputReady();travelPromptComplete=false;}if(next==BoardSpeech.Mode.CONFIRM&&engine.state()==DialogueEngine.State.PROMPTING)engine.promptFinished(now());notice((outputReady?"":"中文离线语音不可用；")+(next==BoardSpeech.Mode.WAKE?"正在聆听，说“"+SherpaSpeech.WAKE_PHRASE+"”唤醒":next==BoardSpeech.Mode.CONFIRM?"请回答“是的”或“不用”":"请说话"));});}
            public void speechStarted(long received){main.post(()->{if(current(ticket)&&next==BoardSpeech.Mode.CONFIRM&&engine.acceptsConfirmationAt(received))confirmPending=true;});}
            public void partial(String text){main.post(()->{if(current(ticket)&&!text.isEmpty())listener.transcript(travel.sensitive()?"登录信息已识别":text);});}
            public void transcript(String text){main.post(()->{if(current(ticket)&&!text.isEmpty())listener.transcript(travel.sensitive()?"登录信息已识别":text);});}
            public void wake(String raw,String command,long received){main.post(()->{if(!current(ticket))return;if(!raw.isEmpty())listener.transcript(raw);stopInput();say("收到",()->{if(command.isEmpty())listen(BoardSpeech.Mode.COMMAND);else handle(command,received);});});}
            public void result(String text,long received){main.post(()->{if(!current(ticket))return;listener.transcript(travel.sensitive()?"登录信息已识别":text);confirmPending=false;handle(text,received);});}
            public void error(String reason){main.post(()->{if(current(ticket)){stopInput();travel.suspend();travelPromptComplete=false;confirmPending=false;engine.reset();notice(reason);int retry=epoch;main.postDelayed(()->{if(current(retry))idle();},3000);}});}
            public void ended(){main.post(()->{if(!current(ticket))return;travel.suspend();travelPromptComplete=false;confirmPending=false;if(next==BoardSpeech.Mode.COMMAND&&travel.active())listen(BoardSpeech.Mode.COMMAND);else if(next!=BoardSpeech.Mode.CONFIRM)idle();});}
        });
    }
    private boolean current(int ticket){return foreground&&!closed&&ticket==epoch;}
    private void handle(String raw,long received){
        if(!foreground||processing||speaking||raw.length()>300)return;stopInput();
        if(travel.active()||HbcTravelSession.starts(raw)){
            engine.reset();travelPromptComplete=false;int ticket=epoch;processing=true;notice("正在处理旅行需求…");
            worker.execute(()->{if(!current(ticket))return;HbcTravelSession.Reply reply=travel.respond(raw);main.post(()->{if(current(ticket)){processing=false;sayTravel(reply);}});});return;
        }
        if(engine.state()==DialogueEngine.State.CONFIRMING){
            if(!engine.acceptsConfirmationAt(received)){engine.expire(now());idle();return;}
            DialogueEngine.Action action=engine.accept(BoardIntent.consent(raw),received);
            if(action==DialogueEngine.Action.QUERY){confirmPending=false;fetch();}
            else if(action==DialogueEngine.Action.DECLINED){confirmPending=false;notice("已拒绝，未查询");idle();}
            else{notice("请明确回答“是的”或“不用”");listen(BoardSpeech.Mode.CONFIRM);}return;
        }
        if(engine.state()!=DialogueEngine.State.LISTENING)return;
        int ticket=epoch;processing=true;
        worker.execute(()->{try{
            if(!current(ticket))return;
            // Clear supported local commands need no model round trip. Natural wording uses the existing service automatically.
            SemanticDecision local=BoardIntent.local(raw,catalog);
            SemanticDecision decision=local.intent==SemanticDecision.Intent.WEATHER||base.isEmpty()||model.isEmpty()?local:
                BoardIntent.remote(raw,base,key,model,c->{connection=c;if(c!=null&&!current(ticket))c.disconnect();});
            main.post(()->{if(current(ticket)){processing=false;apply(decision);}});
        }catch(Exception failure){main.post(()->{if(current(ticket)){processing=false;say("这次没有听懂，请再说一次",this::idle);}});}});
    }
    private void apply(SemanticDecision decision){
        DialogueEngine.Action action=engine.accept(decision,now());
        if(action!=DialogueEngine.Action.ASK){say("我还不会",()->{engine.finish(now());idle();});return;}
        target=catalog.find(decision.city==null?"北京":decision.city);
        if(target==null){engine.rejectPrompt("城市无法匹配");say("我还不会",()->{engine.finish(now());idle();});return;}
        String date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(engine.dayOffset()).toString();
        if(!engine.selectTarget(target.name,date,now())){say("刚刚查询过这个天气，请稍后再试",this::idle);return;}
        say("需要帮主人查询"+target.name+(engine.dayOffset()==1?"明天":"今天")+"的天气吗？",()->listen(BoardSpeech.Mode.CONFIRM));
    }
    private void fetch(){
        int ticket=epoch;processing=true;final CityCatalog.City city=target;final int day=engine.dayOffset();notice("正在查询"+city.name+(day==1?"明天":"今天")+"的天气");
        worker.execute(()->{try{
            if(!current(ticket))return;String forecast=weather.query(city,day);
            main.post(()->{if(current(ticket)){processing=false;engine.weatherReady();say(forecast,()->{engine.finish(now());idle();});}});
        }catch(Exception failure){main.post(()->{if(current(ticket)){processing=false;engine.finish(now());say("天气查询失败，请稍后再试",this::idle);}});}});
    }
    private void say(String text,Runnable done){
        stopInput();int ticket=epoch;speaking=true;listener.speaking(true);notice("阿洛："+text);
        Runnable finished=()->main.postDelayed(()->{if(current(ticket)){speaking=false;listener.speaking(false);done.run();}},500);
        if(!outputReady||!output.speak(text,finished)){notice("中文离线语音不可用；"+text);finished.run();}
    }
    private void sayTravel(HbcTravelSession.Reply reply){
        String text=reply.text.replaceAll("\\[([^\\]]+)\\]\\(https?://[^)]+\\)","$1").replaceAll("[`*#]","");
        speakTravelChunk(text,0,reply.listen);
    }
    private void speakTravelChunk(String text,int offset,boolean listen){
        if(!foreground||closed)return;
        if(offset>=text.length()){
            travelPromptComplete=true;
            if(listen)listen(BoardSpeech.Mode.COMMAND);else idle();return;
        }
        int end=Math.min(text.length(),offset+80);
        if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        // Chunk long itineraries, so the existing 25-second audio watchdog cannot silently skip a review.
        String chunk=text.substring(offset,end);final int next=end;
        stopInput();int ticket=epoch;speaking=true;listener.speaking(true);listener.status("旅行服务正在回复…");
        Runnable done=()->main.postDelayed(()->{
            if(!current(ticket))return;speaking=false;listener.speaking(false);
            if(!output.playbackSucceeded()){travel.suspend();travelPromptComplete=false;notice("旅行回复未完整播报，请唤醒后说重听待办或重新说明需求");idle();return;}
            speakTravelChunk(text,next,listen);
        },next>=text.length()?500:150);
        if(!outputReady||!output.speak(chunk,done)){
            speaking=false;listener.speaking(false);travel.suspend();travelPromptComplete=false;
            notice("中文离线语音不可用，旅行操作未确认");idle();
        }
    }
    /** Other existing app replies use the same playback boundary to avoid microphone echo. */
    public void reply(String text){if(foreground){travel.suspend();travelPromptComplete=false;processing=false;engine.reset();say(text,this::idle);}}
    @Override public void close(){if(closed)return;pause();closed=true;input.close();output.close();travel.close();worker.shutdownNow();main.removeCallbacksAndMessages(null);}
}
