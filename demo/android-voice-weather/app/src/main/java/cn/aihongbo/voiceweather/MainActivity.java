package cn.aihongbo.voiceweather;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.speech.tts.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Vosk -> explicit wake gate -> acknowledgement -> Qwen semantic JSON -> confirmation. */
public class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task -> new Thread(() -> {android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT+1);task.run();},"qwen-intent")),ioWorker=Executors.newSingleThreadExecutor(),networkWorker=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor asrWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),task -> new Thread(task,"vosk-transcription"));
    private final IntentBacklog<SemanticJob> backlog=new IntentBacklog<>(8);
    private final WakeCommandGate wakeGate=new WakeCommandGate();
    private final CommandHandoff commandHandoff=new CommandHandoff();
    private final Map<Long,Integer> pendingConfirmAsr=new HashMap<>();
    private boolean qwenReady,qwenFailed,semanticRunning,testing;
    private int asrPending,transcriptCount,skippedSemantics,transcriptsDuringQwen,streamPartialCount,streamFinalCount,partialsDuringQwen;
    private long dialogueChangedAt,dialogueTurn,asrLoadMs,qwenLoadMs,asrLoadPss,qwenLoadPss;
    private SemanticJob activeJob;
    private final StringBuilder transcripts=new StringBuilder();
    private TextView transcriptView,lastFinal;
    private static final class SemanticJob {
        final String text,origin,target,defaultCity;final int session;final long turn,received,queuedAt,asrMs;final boolean confirming,eligible,enabled,live;
        final WakeEvidence wake;
        SemanticJob(String text,String origin,int session,long turn,long received,boolean confirming,boolean eligible,boolean enabled,String target,String defaultCity,boolean live,long asrMs){this(text,origin,session,turn,received,confirming,eligible,enabled,target,defaultCity,live,asrMs,WakeEvidence.NONE);}
        SemanticJob(String text,String origin,int session,long turn,long received,boolean confirming,boolean eligible,boolean enabled,String target,String defaultCity,boolean live,long asrMs,WakeEvidence wake){this.text=text;this.origin=origin;this.session=session;this.turn=turn;this.received=received;this.confirming=confirming;this.eligible=eligible;this.enabled=enabled;this.target=target;this.defaultCity=defaultCity;this.live=live;this.asrMs=asrMs;this.queuedAt=now();this.wake=wake;}
        SemanticJob withFinal(String value,long millis,long received,boolean playback){return new SemanticJob(value,origin,session,turn,received,confirming,eligible && !playback,enabled,target,defaultCity,live,millis);}
        SemanticJob withFinal(String value,long millis,long received,boolean playback,WakeEvidence wake){return new SemanticJob(value,origin,session,turn,received,confirming,eligible && !playback,enabled,target,defaultCity,live,millis,wake);}
        SemanticJob withText(String value,long millis){return new SemanticJob(value,origin,session,turn,received,confirming,eligible,enabled,target,defaultCity,live,millis);}
    }
    private final DialogueEngine engine=new DialogueEngine();
    private VoskAsr asr;
    private volatile OfflineQwen qwen;
    private QwenSemanticInterpreter semantics;
    private PcmMicrophone microphone;
    private TextToSpeech tts;
    private boolean ttsReady,ready,selectedLive;
    private volatile boolean destroyed,foreground,runningMic,audioBlocked,busy;
    private volatile int generation;
    private int speechSequence,queryCount;
    private Runnable afterSpeech;
    private TextView status,partial,history,metrics,modelStatus,decision,speechDetail,comparison;
    private Button micButton;
    private EditText input;
    private Spinner cities;
    private Switch liveWeather,assistantEnabled;
    private CityCatalog catalog;
    private CityCatalog.City selectedCity;
    private final StringBuilder visibleLog=new StringBuilder();
    private static long now(){return SystemClock.elapsedRealtime();}
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(destroyed || !foreground)return;
        // A confirmation received inside the window may finish model processing afterwards.
        if(!confirmationInFlight() && engine.expire(now())==DialogueEngine.Action.TIMED_OUT){advanceDialogue();reportDecision();}
        refresh();handler.postDelayed(this,250);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(Color.rgb(18,36,57));getWindow().setNavigationBarColor(Color.rgb(18,36,57));
        try {catalog=new CityCatalog(new InputStreamReader(getAssets().open("cities.tsv"),StandardCharsets.UTF_8));}
        catch(IOException error){TextView failure=new TextView(this);failure.setText("城市表加载失败");setContentView(failure);return;}
        buildUi();
        tts = new TextToSpeech(this, result -> {
            if (destroyed) return;
            if (result == TextToSpeech.SUCCESS) {
                int available = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                ttsReady = available >= TextToSpeech.LANG_AVAILABLE;
                if (ttsReady) {
                    // Use an installed offline voice only. Never silently upload surrounding speech.
                    java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
                    android.speech.tts.Voice offline = null;
                    if (voices != null) for (android.speech.tts.Voice v : voices) {
                        if (v.getLocale().getLanguage().equals("zh") && !v.isNetworkConnectionRequired()) { offline = v; break; }
                    }
                    if (offline == null) ttsReady = false; else tts.setVoice(offline);
                }
            }
            log("播报", ttsReady ? "中文离线语音就绪" : "缺少中文离线 TTS，仅显示文字；可在系统设置安装中文语音包");
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { handler.post(() -> completeSpeech(id, false)); }
            @Override public void onError(String id) { handler.post(() -> completeSpeech(id, true)); }
        });
        asrWorker.execute(() -> {
            long start=now();
            try {asr=new VoskAsr();asr.load(this);long millis=now()-start,pss=Debug.getPss();handler.post(() -> {if(destroyed)return;ready=true;asrLoadMs=millis;asrLoadPss=pss;updateModelStatus();refresh();});}
            catch(Exception | LinkageError error){handler.post(() -> {if(!destroyed){modelStatus.setText("Vosk 加载失败："+error.getMessage());log("错误","Vosk 未就绪："+error.getMessage());refresh();}});}
        });
        worker.execute(() -> {
            long start=now();
            try {qwen=new OfflineQwen();qwen.load(new File(getExternalFilesDir("models"),OfflineQwen.MODEL_FILE));semantics=new QwenSemanticInterpreter(qwen);long millis=now()-start,pss=Debug.getPss();handler.post(() -> {if(destroyed)return;qwenReady=true;qwenLoadMs=millis;qwenLoadPss=pss;updateModelStatus();drainSemantics();refresh();});}
            catch(Exception | LinkageError error){handler.post(() -> {if(!destroyed){qwenFailed=true;skippedSemantics+=backlog.clear();log("Qwen 加载失败",error.getMessage()+"；Vosk 继续转写");modelStatus.setText("Qwen 加载失败："+error.getMessage()+"；Vosk 继续转写记录");refresh();}});}
        });
    }
    private void updateModelStatus(){
        modelStatus.setText((ready && qwenReady?"Vosk + Qwen3 已就绪":"Vosk "+(ready?"已就绪":"加载中")+" · Qwen "+(qwenReady?"已就绪":qwenFailed?"失败，仅转写":"加载中"))+" · 独立后台任务\nVosk 加载 "+asrLoadMs+" ms / PSS "+asrLoadPss/1024+" MiB · Qwen 加载 "+qwenLoadMs+" ms / PSS "+qwenLoadPss/1024+" MiB（进程快照，非单模型）");
    }
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(32),dp(20),dp(32));root.setBackgroundColor(Color.rgb(241,245,249));scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
        label(root,"阿洛",25);
        label(root,"说“阿洛”唤醒 → 回应“收到” → Qwen3 识别任务 → 确认后查询",15);
        label(root,"仅前台录音，按句保存本地 WAV（files/recordings），不上传。请说“阿洛，上海明天天气怎么样”；也可单独说“阿洛”，回应后 8 秒内说出下一句。普通讲话仅记录，确认回答无需再次唤醒。",14);
        modelStatus=label(root,"正在加载 Vosk 与 Qwen3…",14);
        status=label(root,"状态：正在加载模型",20);
        micButton=button(root,"开始麦克风监听",view -> {
            if(runningMic){stopSession("已手动停止");return;}
            if(!ready || testing)return;
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},11);else startMicrophone();
        });micButton.setEnabled(false);
        button(root,"停止 / 重置当前流程",view -> stopSession("已重置，旧推理结果将丢弃"));
        partial=label(root,"实时转写：等待讲话…",17);lastFinal=label(root,"最近完整句：—",15);
        label(root,"连续转写记录（不等待 Qwen）",17);
        ScrollView transcriptScroll=new ScrollView(this);transcriptView=new TextView(this);transcriptView.setText("等待讲话…");transcriptView.setTextSize(15);transcriptView.setTextColor(Color.rgb(51,65,85));transcriptView.setTextIsSelectable(true);transcriptScroll.addView(transcriptView);root.addView(transcriptScroll,new LinearLayout.LayoutParams(-1,dp(120)));
        decision=label(root,"处理说明：等待模型",15);speechDetail=label(root,"模型语义 JSON：—",14);speechDetail.setTextIsSelectable(true);
        comparison=label(root,"耗时：等待输入；分别统计 ASR 与语义模型",14);metrics=label(root,"真实天气请求：0 次",14);
        label(root,"默认城市（模型判断未提城市时使用）",14);
        List<String> names=new ArrayList<>(Arrays.asList(WeatherClient.PREFERRED_CITIES));for(String name:catalog.names())if(!names.contains(name))names.add(name);
        cities=new Spinner(this);cities.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));root.addView(cities);
        assistantEnabled=new Switch(this);assistantEnabled.setText("启用天气助手（关闭只转写，不调用语义模型）");assistantEnabled.setChecked(true);root.addView(assistantEnabled);
        assistantEnabled.setOnCheckedChangeListener((button,enabled) -> {advanceDialogue();if(qwen!=null)qwen.cancel();speechSequence++;afterSpeech=null;audioBlocked=false;if(tts!=null)tts.stop();engine.reset();log("助手开关",enabled?"开启后台意图处理":"关闭意图处理，Vosk 继续转写记录");refresh();});
        liveWeather=new Switch(this);liveWeather.setText("真实天气 · 联网（关闭时使用模拟数据）");root.addView(liveWeather);
        label(root,"文字模拟（唤醒与任务识别流程）",17);input=new EditText(this);input.setSingleLine(true);input.setHint("例如：阿洛，郑州明天天气怎么样");root.addView(input);
        button(root,"送入一句文字",view -> {String text=input.getText().toString().trim();if(!text.isEmpty()){submitText(text,"文字模拟");input.setText("");}});
        LinearLayout examples=new LinearLayout(this);root.addView(examples);
        smallButton(examples,"阿洛",() -> submitText("阿洛","文字模拟"));smallButton(examples,"上海天气",() -> submitText("阿洛，上海明天天气怎么样","文字模拟"));smallButton(examples,"是的",() -> submitText("是的","文字模拟"));smallButton(examples,"不用",() -> submitText("不用","文字模拟"));
        label(root,"Vosk 合成音频自检",17);
        label(root,"单条天气样本不含唤醒称呼：先点击“阿洛”，等待“收到”回应结束后再播放样本。完整样本自检直接测试模型，不经过唤醒入口。",12);
        button(root,"识别郑州天气音频",view -> recognizeSample("zhengzhou"));
        button(root,"识别快速郑州天气音频",view -> recognizeSample("zhengzhou-fast"));
        button(root,"识别郑州压力音频",view -> recognizeSample("zhengzhou-hard"));
        button(root,"识别上海明天天气音频",view -> recognizeSample("city-weather"));
        LinearLayout answers=new LinearLayout(this);root.addView(answers);smallButton(answers,"识别肯定音频",() -> recognizeSample("yes"));smallButton(answers,"识别否定音频",() -> recognizeSample("no"));
        button(root,"Vosk + Qwen 样本自检（不播报、不查天气）",view -> runSelfTest());
        label(root,"意图与执行记录",17);history=label(root,"",14);history.setTextIsSelectable(true);
        label(root,"Vosk 与 Qwen 独立排队；Qwen 忙或播报时继续转写。播报重叠音频只记录，不触发助手，避免扬声器回声误确认。语义队列最多 8 条、确认优先，过期任务跳过但保留转写。确认窗口 8 秒，同目标冷却 30 秒。",12);
    }
    private TextView label(LinearLayout root, String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(51,65,85)); v.setPadding(0,dp(6),0,dp(6)); root.addView(v); return v;
    }
    private Button button(LinearLayout root, String text, View.OnClickListener action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(action); root.addView(b, new LinearLayout.LayoutParams(-1,-2)); return b;
    }
    private void smallButton(LinearLayout root, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(13); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); root.addView(b,new LinearLayout.LayoutParams(0,-2,1));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==11 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED && foreground)startMicrophone();else {engine.notice("未获得麦克风权限，可使用文字模拟");refresh();}}
    private void startMicrophone(){
        if(!ready || testing || !foreground)return;
        stopSession("开始 Vosk 麦克风实验");int session=generation;
        try {
            microphone=new PcmMicrophone(this,asr,() -> foreground && !destroyed && generation==session,() -> audioBlocked,streamingListener(session,"麦克风",null));
            runningMic=true;microphone.start();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);refresh();
        }catch(Exception error){stopSession("录音失败："+error.getMessage());}
    }
    private boolean canSubmit(){
        if(!foreground || destroyed)return false;
        if(!ready || testing){engine.notice(!ready?"Vosk 未就绪":"样本自检中，请等待");refresh();return false;}
        return true;
    }
    private SemanticJob ticket(String text,String origin,long received,boolean playback,long asrMs){
        DialogueEngine.State state=engine.state();
        return new SemanticJob(text,origin,generation,dialogueTurn,received,state==DialogueEngine.State.CONFIRMING,received>=dialogueChangedAt && !playback && !audioBlocked && (state==DialogueEngine.State.LISTENING || state==DialogueEngine.State.CONFIRMING && engine.acceptsConfirmationAt(received)),assistantEnabled.isChecked(),targetLabel(),(String)cities.getSelectedItem(),liveWeather.isChecked(),asrMs);
    }
    private void recognizeSample(String name){
        if(!canSubmit())return;
        submitAsr(null,name,ticket("","合成音频 "+name,now(),false,0));
    }
    private PcmMicrophone.Listener streamingListener(int session,String origin,SemanticJob initial){
        final Map<Long,SemanticJob> contexts=new HashMap<>(); // Accessed only by posted main-thread callbacks.
        return new PcmMicrophone.Listener(){
            @Override public void started(long id,long received,boolean playback){handler.post(() -> {
                if(destroyed || !foreground || session!=generation)return;
                SemanticJob job=initial==null?ticket("",origin,received,playback,0):initial;
                contexts.put(id,job);if(initial==null && job.confirming && job.eligible)pendingConfirmAsr.merge(job.turn,1,Integer::sum);
            });}
            @Override public void partial(long id,String text,long received,long decodeMs){handler.post(() -> {
                if(destroyed || !foreground || session!=generation)return;
                streamPartialCount++;if(semanticRunning)partialsDuringQwen++;
                partial.setText("实时转写（"+origin+"，临时）："+(text.isEmpty()?"等待讲话…":text));refresh();
            });}
            @Override public void completed(long id,String text,long received,long decodeMs,boolean playback,boolean forced){
                completed(id,text,received,decodeMs,playback,forced,WakeEvidence.NONE);
            }
            @Override public void completed(long id,String text,long received,long decodeMs,boolean playback,boolean forced,WakeEvidence wake){
                long pss=text.isEmpty()?0:Debug.getPss();handler.post(() -> {
                    if(destroyed || !foreground || session!=generation)return;
                    SemanticJob job=contexts.remove(id);
                    if(job==null)return;
                    if(initial==null)finishPendingConfirmation(job);
                    partial.setText("实时转写：等待下一句…");
                    if(!text.isEmpty() || wake.detected){
                        streamFinalCount++;if(forced)log("流式识别","连续音频已达 20 秒，提交当前段最终结果");
                        publishTranscript(job.withFinal(text,decodeMs,received,playback,wake),pss);
                    }else refresh();
                });
            }
            @Override public void error(String reason){handler.post(() -> {if(!destroyed && session==generation){log("流式错误",reason);if(initial==null)stopSession(reason);}});}
            @Override public void recordingError(String reason){android.util.Log.w("SentenceWavRecorder",reason);handler.post(() -> {if(!destroyed)log("录音保存",reason);});}
            @Override public void stopped(String tail){handler.post(() -> {
                if(destroyed)return;
                if(!tail.isEmpty())publishTranscript(new SemanticJob(tail,"麦克风停止收尾（仅记录）",generation,dialogueTurn,now(),false,false,false,targetLabel(),(String)cities.getSelectedItem(),false,0),0);
                if(session==generation && runningMic)stopSession("流式录音线程已停止");
            });}
        };
    }
    private void submitAsr(float[] audio,String sample,SemanticJob context){
        asrPending++;if(context.confirming && context.eligible)pendingConfirmAsr.merge(context.turn,1,Integer::sum);refresh();
        PcmMicrophone.Listener listener=streamingListener(context.session,context.origin,context);
        try {asrWorker.execute(() -> {
            try {
                float[] input=audio;if(input==null)try(InputStream source=getAssets().open("samples/"+sample+".pcm")){input=VoskAsr.readPcm(source);}
                if(context.session!=generation || destroyed)return;
                try(StreamingTranscriber transcriber=new StreamingTranscriber(asr.openStream(),listener)){
                    long start=now();
                    for(int offset=0;offset<input.length;offset+=1280){
                        if(context.session!=generation || destroyed)return;
                        int count=Math.min(1280,input.length-offset);short[] frame=new short[count];
                        for(int i=0;i<count;i++)frame[i]=(short)Math.max(-32768,Math.min(32767,Math.round(input[offset+i]*32768f)));
                        transcriber.accept(frame,start+(offset+count)*1000L/16000,audioBlocked);
                        long wait=start+(offset+count)*1000L/16000-now();if(wait>0)SystemClock.sleep(wait);
                    }
                    transcriber.finish(start+input.length*1000L/16000);
                }
            }catch(Exception error){listener.error("样本流式识别失败："+error.getMessage());}
            finally {handler.post(() -> finishAsr(context));}
        });}catch(RejectedExecutionException full){finishAsr(context);log("Vosk 队列","诊断样本队列满，本次样本未入队；麦克风流式识别继续运行");refresh();}
    }
    private void finishPendingConfirmation(SemanticJob job){if(job.confirming && job.eligible)pendingConfirmAsr.computeIfPresent(job.turn,(key,count) -> count<=1?null:count-1);}
    private void finishAsr(SemanticJob job){
        if(job.session!=generation)return;asrPending=Math.max(0,asrPending-1);
        finishPendingConfirmation(job);
        refresh();
    }
    private void submitText(String text,String origin){if(canSubmit())publishTranscript(ticket(text,origin,now(),false,0),0);}
    private void publishTranscript(SemanticJob job,long pss){
        if(job.text.isEmpty() && !job.wake.detected){log("Vosk","本段未识别出文字");refresh();return;}
        transcriptCount++;if(semanticRunning)transcriptsDuringQwen++;
        lastFinal.setText("最近完整句（Vosk 原文）："+job.text+(job.wake.available?"\n独立声学唤醒："+(job.wake.detected?"阿洛":"未命中"):""));
        String timestamp=new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date());
        transcripts.insert(0,timestamp+" ["+job.origin+"] "+job.text+"\n");if(transcripts.length()>12000)transcripts.setLength(12000);transcriptView.setText(transcripts);
        log("转写 · "+job.origin,job.text);
        if(job.wake.detected)log("独立声学唤醒","检测到阿洛；原始转写未改写；后续命令："+job.wake.command+"；置信度："+job.wake.confidence);
        comparison.setText((pss==0?"文字模拟已记录":"最近转写：Vosk "+job.asrMs+" ms · 进程 PSS "+pss/1024+" MiB（快照，非峰值）")+"\n文字已显示并异步记录，Qwen 处理不阻塞下一段转写");
        if(!job.enabled || !job.eligible || job.turn!=dialogueTurn || qwenFailed){log("仅记录",!job.enabled?"天气助手关闭":qwenFailed?"Qwen 不可用": "播报重叠或对话阶段已改变，不触发助手");refresh();return;}
        if(job.confirming && !engine.acceptsConfirmationAt(job.received)){log("仅记录","确认回答不在当前有效窗口，未执行查询");refresh();return;}
        WakeCommandGate.Result command=wakeGate.accept(job.text,job.received,job.confirming,job.wake);
        if(command.kind==WakeCommandGate.Kind.IGNORE){log("仅记录","未以“阿洛”唤醒，且不在下一句接收窗口；不调用 Qwen");refresh();return;}
        // Keep the raw transcript above; only the semantic input loses the wake prefix.
        job=job.withText(command.text,job.asrMs);
        SemanticJob dropped=backlog.offer(job,job.confirming);
        if(dropped!=null){skippedSemantics++;log("语义队列","积压，跳过旧语义任务；原始转写已保留："+dropped.text);}
        drainSemantics();refresh();
    }
    private boolean current(SemanticJob job){return !destroyed && foreground && job.session==generation && job.turn==dialogueTurn && assistantEnabled.isChecked();}
    private void drainSemantics(){
        if(destroyed || testing || !qwenReady || semanticRunning || audioBlocked)return;
        SemanticJob next;
        while((next=backlog.poll())!=null){
            if(!current(next) || now()-next.received>(next.confirming?30000:15000)){skippedSemantics++;log("语义跳过","任务已过期或对话已变化，转写仍保留："+next.text);continue;}
            final SemanticJob job=next;
            if(!job.confirming && !job.text.isEmpty())wakeGate.reset();
            semanticRunning=true;activeJob=job;refresh();
            commandHandoff.start(!job.confirming,done -> speak("收到",done),() -> {
                if(!current(job) || now()-job.received>(job.confirming?30000:15000)) {
                    semanticRunning=false;activeJob=null;skippedSemantics++;log("语义跳过","回应后任务已失效，未调用 Qwen");drainSemantics();refresh();return;
                }
                if(job.text.isEmpty()) {
                    wakeGate.openWindow(now());semanticRunning=false;activeJob=null;
                    engine.notice("阿洛已唤醒，请在 8 秒内说出下一句命令");log("唤醒","回应完成，开启 8 秒下一句接收窗口；未调用 Qwen");drainSemantics();refresh();return;
                }
                runSemanticJob(job);
            });return;
        }
        refresh();
    }
    private void runSemanticJob(SemanticJob job){
            worker.execute(() -> {
                long start=now(),wait=start-job.queuedAt;
                try {if(job.session!=generation || destroyed)throw new CancellationException("旧语义任务已取消");QwenSemanticInterpreter.Result result=semantics.interpret(job.text,job.confirming,job.target);long millis=now()-start,pss=Debug.getPss();
                    handler.post(() -> {semanticRunning=false;activeJob=null;if(!destroyed){
                        log("Qwen 语义",result.json+" · 推理 "+millis+" ms · 排队 "+wait+" ms · PSS "+pss/1024+" MiB");
                        if(current(job) && result.confirmationGuarded)log("确认保护","模型结果与完整明确回答不一致，本句仅记录，继续等待确认");
                        speechDetail.setText("语义输入："+job.text+"\n模型语义 JSON："+result.json+"\nQwen 推理 "+millis+" ms · 等待 "+wait+" ms · PSS "+pss/1024+" MiB（快照）");
                        if(current(job))applyDecision(result.decision,job.received,job);else log("语义跳过","旧结果只记录，不触发操作");drainSemantics();refresh();
                    }});
                }catch(Exception error){handler.post(() -> {semanticRunning=false;activeJob=null;if(!destroyed){if(current(job))log("Qwen 错误",error.getMessage()+"；Vosk 继续转写");drainSemantics();refresh();}});}
            });
    }
    private boolean confirmationInFlight(){return pendingConfirmAsr.getOrDefault(dialogueTurn,0)>0 || (activeJob!=null && activeJob.turn==dialogueTurn && activeJob.confirming) || backlog.any(job -> job.turn==dialogueTurn && job.confirming);}
    private void advanceDialogue(){dialogueTurn++;dialogueChangedAt=now();wakeGate.reset();if(commandHandoff.cancel()){semanticRunning=false;activeJob=null;}pendingConfirmAsr.clear();int removed=backlog.clear();if(removed>0){skippedSemantics+=removed;log("语义队列","对话阶段改变，跳过 "+removed+" 条旧任务；转写保留");}}
    private String targetLabel(){return selectedCity==null?"当前目标":selectedCity.name+(engine.dayOffset()==1?"明天":"今天");}
    private void failed(Exception error,int session){handler.post(() -> {if(!destroyed && session==generation){testing=false;log("自检错误",error.getMessage());refresh();}});}
    private void applyDecision(SemanticDecision result,long received,SemanticJob job){
        DialogueEngine.Action action=engine.accept(result,received);
        if(action==DialogueEngine.Action.ASK){
            advanceDialogue();
            selectedCity=result.city==null?catalog.find(job.defaultCity):catalog.find(result.city);
            if(selectedCity==null){engine.rejectPrompt("模型未给出有效城市；未执行查询");replyUnsupported();reportDecision();return;}
            String date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(engine.dayOffset()).toString();
            if(!engine.selectTarget(selectedCity.name,date,now()))return;
            selectedLive=job.live;
            speak("需要帮主人查询"+targetLabel()+"的天气吗？",() -> {engine.promptFinished(now());advanceDialogue();log("流程","等待明确回答，8 秒窗口");refresh();});
        }else if(action==DialogueEngine.Action.NOT_SUPPORTED){replyUnsupported();}
        else if(action==DialogueEngine.Action.QUERY){advanceDialogue();queryWeather();}
        else if(action==DialogueEngine.Action.DECLINED){advanceDialogue();log("流程","已拒绝，未查询、不追加播报");}
        reportDecision();
    }
    private void replyUnsupported(){
        String reason=engine.reason(now());
        advanceDialogue();
        speak("我还不会",() -> {engine.finish(now());advanceDialogue();engine.notice(reason+"；已回应“我还不会”");refresh();});
    }
    private void reportDecision(){log("判断",engine.reason(now()));}
    private void runSelfTest(){
        if(!canSubmit() || runningMic || busy || !qwenReady || engine.state()!=DialogueEngine.State.LISTENING)return;
        testing=true;refresh();int session=generation;
        worker.execute(() -> {
            JSONArray rows=new JSONArray();
            try {
                for(String name:new String[]{"zhengzhou","zhengzhou-fast","zhengzhou-hard","city-weather","zhengzhong-warning","center","yes","no"}){
                    if(session!=generation || destroyed)break;
                    long start=now();String raw;try(InputStream source=getAssets().open("samples/"+name+".pcm")){float[] samples=VoskAsr.readPcm(source);raw=asrWorker.submit(() -> asr.transcribe(samples)).get();}long audioMs=now()-start;
                    start=now();QwenSemanticInterpreter.Result result=semantics.interpret(raw,name.equals("yes")||name.equals("no"),"郑州明天");long llmMs=now()-start;
                    JSONObject row=new JSONObject();row.put("sample",name);row.put("raw_asr",raw);row.put("semantic",new JSONObject(result.json));row.put("asr_ms",audioMs);row.put("semantic_ms",llmMs);row.put("pss_kb",Debug.getPss());rows.put(row);
                    handler.post(() -> {if(!destroyed && session==generation){lastFinal.setText("最近完整句（独占自检）："+raw);speechDetail.setText("模型语义 JSON："+result.json);comparison.setText("自检 "+name+"：ASR "+audioMs+" ms · 语义 "+llmMs+" ms\n进程 PSS "+row.optLong("pss_kb")/1024+" MiB（快照）");log("自检",row.toString());}});
                }
                if(session==generation)try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"vosk-qwen-self-test.json"))){out.write(rows.toString(2).getBytes(StandardCharsets.UTF_8));}
                handler.post(() -> {if(!destroyed && session==generation){testing=false;engine.notice("Vosk + Qwen 自检完成："+rows.length()+" 条；未播报、未查询天气");refresh();}});
            }catch(Exception error){failed(error,session);}
        });
    }
    private void queryWeather() {
        final int session = generation, day = engine.dayOffset();
        final CityCatalog.City city = selectedCity;
        if (!selectedLive) {
            log("天气", "模拟模式：未发出网络请求");
            showWeather("模拟数据：" + city.name + (day == 1 ? "明天" : "今天") + "晴，最低18度，最高25度。", session);
            return;
        }
        queryCount++; log("天气", "明确确认后请求 Open-Meteo，城市：" + city.name); refresh();
        networkWorker.execute(() -> {
            try {
                String forecast = WeatherClient.query(city, day);
                handler.post(() -> showWeather(forecast, session));
            } catch (Exception error) {
                handler.post(() -> {
                    if (destroyed || !foreground || session != generation) return;
                    log("错误", "天气查询失败：" + error.getMessage());
                    engine.finish(now()); advanceDialogue(); engine.notice("天气查询失败："+error.getMessage()); reportDecision(); refresh();
                });
            }
        });
    }
    private void showWeather(String forecast, int session) {
        if (destroyed || !foreground || session != generation) return;
        engine.weatherReady(); advanceDialogue(); log("预报", forecast);
        speak(forecast, () -> { engine.finish(now()); advanceDialogue(); log("流程", "播报流程结束，继续安静记录；同一城市和日期冷却 30 秒"); refresh(); });
    }
    private void speak(String text, Runnable done) {
        log("助手", text);
        audioBlocked = true;
        afterSpeech = done;
        String id = Integer.toString(++speechSequence);
        if (!ttsReady) {
            log("播报", "当前仅显示文字，未播放声音");
            completeSpeech(id, false);
            return;
        }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) completeSpeech(id,true);
        // A broken TTS engine must not permanently block the dialogue.
        handler.postDelayed(() -> { if (id.equals(Integer.toString(speechSequence)) && afterSpeech != null) { tts.stop(); completeSpeech(id,true); } }, 25000);
    }
    private void completeSpeech(String id, boolean failed) {
        if (destroyed || !id.equals(Integer.toString(speechSequence)) || afterSpeech == null) return;
        Runnable done = afterSpeech; afterSpeech = null;
        if (failed) log("播报", "语音播放失败，结果已显示为文字");
        int session = generation;
        // Discard trailing speaker sound before accepting a confirmation.
        handler.postDelayed(() -> {
            if (destroyed || !foreground || session != generation || !id.equals(Integer.toString(speechSequence))) return;
            done.run();
            if(afterSpeech==null)audioBlocked = false;
            drainSemantics();
        }, 500);
    }
    private void clearAsrQueue(){Runnable task;while((task=asrWorker.getQueue().poll())!=null)if(task instanceof Future<?>)((Future<?>)task).cancel(false);}
    private void stopSession(String reason){
        generation++;advanceDialogue();clearAsrQueue();asrPending=0;testing=false;speechSequence++;afterSpeech=null;runningMic=false;audioBlocked=false;
        if(qwen!=null)qwen.cancel();if(tts!=null)tts.stop();
        if(microphone!=null){microphone.close();microphone=null;}
        partial.setText("实时转写：已停止");
        engine.reset();engine.notice(reason);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);log("流程",reason);refresh();
    }
    private void refresh(){
        if(status==null || destroyed)return;
        busy=semanticRunning || asrPending>0 || backlog.size()>0 || audioBlocked || testing;
        String state=!ready?"等待模型":engine.state()==DialogueEngine.State.PROMPTING?"正在询问":engine.state()==DialogueEngine.State.CONFIRMING?"等待确认（8 秒窗口）":engine.state()==DialogueEngine.State.FETCHING?"正在查询":engine.state()==DialogueEngine.State.SPEAKING?"正在播报":runningMic?"等待讲话":"实验就绪 · 麦克风关闭";
        status.setText("状态："+state+"\nVosk："+(runningMic?"流式录音":"录音关闭")+" · 待转写 "+asrPending+" 段\nQwen："+(commandHandoff.waiting()?"等待“收到”回应结束":semanticRunning?"后台处理中":qwenReady?"空闲":qwenFailed?"不可用":"加载中")+" · 排队 "+backlog.size()+" 条");decision.setText("处理说明："+engine.reason(now()));
        micButton.setEnabled(ready && !testing);micButton.setText(runningMic?"停止麦克风监听":"开始麦克风监听");
        boolean configurable=engine.state()==DialogueEngine.State.LISTENING;cities.setEnabled(configurable);liveWeather.setEnabled(configurable);assistantEnabled.setEnabled(configurable);
        metrics.setText("完整记录 "+transcriptCount+" 条 · 临时更新 "+streamPartialCount+" 次（Qwen 忙时 "+partialsDuringQwen+" 次）\nQwen 忙时完成句子 "+transcriptsDuringQwen+" 条 · 语义跳过 "+skippedSemantics+" 条\n真实天气请求："+queryCount+" 次 · "+(runningMic?"前台录音开启":"麦克风未开启"));
    }
    private void log(String kind, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        String line = time + " [" + kind + "] " + message + "\n";
        visibleLog.insert(0,line);
        if (visibleLog.length() > 10000) visibleLog.setLength(10000);
        if (history != null) history.setText(visibleLog);
        if (!ioWorker.isShutdown()) ioWorker.execute(() -> {
            try {
                File current = new File(getFilesDir(),"transcripts.jsonl");
                if (current.length() >= 1024*1024) {
                    File old = new File(getFilesDir(),"transcripts.previous.jsonl");
                    if (old.exists() && !old.delete()) return;
                    if (!current.renameTo(old)) return;
                }
                JSONObject record = new JSONObject(); record.put("timestamp",System.currentTimeMillis()); record.put("source",kind); record.put("text",message);
                try (FileOutputStream out = new FileOutputStream(current,true)) { out.write((record.toString()+"\n").getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception ignored) { handler.post(() -> { if (!destroyed) modelStatus.setText("提示：本地日志写入失败，当前仅保留屏幕记录"); }); }
        });
    }
    @Override protected void onResume(){super.onResume();foreground=true;handler.post(ticker);}
    @Override protected void onPause(){foreground=false;handler.removeCallbacks(ticker);stopSession("已离开前台，停止录音及交互");super.onPause();}
    @Override protected void onDestroy(){
        destroyed=true;generation++;commandHandoff.cancel();wakeGate.reset();handler.removeCallbacksAndMessages(null);if(qwen!=null)qwen.cancel();if(tts!=null)tts.shutdown();
        backlog.clear();clearAsrQueue();asrWorker.execute(() -> {if(asr!=null)asr.close();});asrWorker.shutdown();
        worker.execute(() -> {if(qwen!=null)qwen.close();});worker.shutdown();networkWorker.shutdown();ioWorker.shutdown();super.onDestroy();
    }
}
