package com.zhb.petscreen;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import android.text.InputFilter;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class ChatActivity extends Activity {
    static final int INK=0xffe5f1f7, BG=0xff101c27, ACCENT=0xff72e4c2;
    static final String SYSTEM="你是一只AI陪伴宠物，名字叫小七。用简体中文自然、温暖地陪用户聊天，默认一到三句话、尽量不超过160字，不使用Markdown格式。先回应用户表达的内容，再按需要提出一个小问题，避免每句都追问。不假装是真人，不声称能看到、听到或执行其他现实操作；你可以控制屏幕表情，目前输入方式为文字。尊重用户自主选择，不制造情感依赖。提供计划时先了解必要信息，不编造用户经历。必须只输出JSON对象：{ \"reply\":\"简短回复\", \"actions\":[] }。屏幕动作格式为{ \"type\":\"set_expression\", \"name\":\"angry\", \"intensity\":0.9, \"duration_ms\":12000 }。name限neutral、happy、angry、sad、joyful、worried、surprised。intensity在0到1，duration_ms为1000到30000整数，最多5个动作按顺序执行。用户要求你表现情绪、做表情、表演时必须返回相应动作，不要只用文字描述。很生气用angry和0.95；再生气一点结合给定的当前表情强度提高；先哭再笑返回sad然后happy。喜=happy，怒=angry，哀=sad，乐=joyful，愁=worried。普通聊天、用户描述自己的心情、引用和假设不执行动作，actions为空，保持表情。不要生气笑一个只执行happy。只在明确要求恢复平静时返回neutral。动作不明确时询问澄清，禁止声称已执行未提供的动作。回复不展示技术字段。";
    final Handler main=new Handler();
    final ArrayList<JSONObject> history=new ArrayList<>();
    volatile boolean foreground,busy,speaking,voiceRecording,voiceTranscribing;
    volatile String remoteId="",remoteState="idle",remoteReply="",remoteError="";
    volatile JSONObject remoteHomework=new JSONObject();
    volatile HttpURLConnection connection;
    String base,key,model,speechBase,speechKey,speechModel,pending="";
    volatile long generation;
    EditText input;
    TextView mood;
    Button send,newChat;
    LinearLayout messages;
    ScrollView scroll;
    Pet pet;
    LinearLayout debugPanel;
    boolean debugVisible,blink;
    long smileUntil;
    String expression="neutral";
    double expressionIntensity=.5;
    ExpressionController expressions;
    SpeechOutput speech;
    PcmRecorder voiceRecorder;
    TextView mic;
    boolean autoSpeak = true;
    static final String[] EXPRESSIONS={"neutral","happy","angry","sad","joyful","worried","surprised"};
    static final String[] EXPRESSION_NAMES={"平静","喜 · 微笑","怒 · 生气","哀 · 悲伤","乐 · 大笑","愁 · 担心","惊 · 惊讶"};
    TextInputServer inputServer;
    SharedPreferences settings;
    final Runnable tick=new Runnable(){public void run(){if(!foreground)return;blink=!(busy||voiceTranscribing)&&!blink;pet.invalidate();main.postDelayed(this,speaking?180:((busy||voiceTranscribing)?500:(blink?150:4500)));}};
    @Override public void onCreate(Bundle b){super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        settings=getSharedPreferences("pet-chat",MODE_PRIVATE);
        applyConfig(getIntent());
        base=settings.getString("llm_base","");model=settings.getString("llm_model","");key=settings.getString("llm_key","");
        speechBase=settings.getString("speech_base","");speechKey=settings.getString("speech_key","");speechModel=settings.getString("speech_model","FunAudioLLM/SenseVoiceSmall");
        autoSpeak=settings.getBoolean("auto_speak",true);
        try{JSONArray a=new JSONArray(settings.getString("history","[]"));for(int i=Math.max(0,a.length()-24);i<a.length();i++)history.add(a.getJSONObject(i));}catch(Exception ignored){history.clear();}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);
        pet=new Pet();root.addView(pet,new FrameLayout.LayoutParams(-1,-1));
        pet.setContentDescription("小七的面孔，轻触逗逗它，长按额头三秒打开设备设置");
        mic=text("●",30,0xff244c48);mic.setGravity(Gravity.CENTER);mic.setContentDescription("开始语音对话");
        FrameLayout.LayoutParams micParams=new FrameLayout.LayoutParams(64,56,Gravity.LEFT|Gravity.BOTTOM);micParams.setMargins(12,0,0,6);root.addView(mic,micParams);mic.setOnClickListener(v->toggleVoiceInput());
        TextView debugToggle=text("•••",18,0xff688b87);debugToggle.setGravity(Gravity.CENTER);debugToggle.setContentDescription("打开文字调试");
        FrameLayout.LayoutParams toggleParams=new FrameLayout.LayoutParams(56,48,Gravity.RIGHT|Gravity.BOTTOM);toggleParams.setMargins(0,0,12,8);root.addView(debugToggle,toggleParams);debugToggle.setOnClickListener(v->showDebug(true));
        debugPanel=new LinearLayout(this);debugPanel.setOrientation(1);debugPanel.setPadding(18,10,18,12);debugPanel.setBackgroundColor(0xff101c27);
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(-1,-1);panelParams.setMargins(24,12,24,12);root.addView(debugPanel,panelParams);debugPanel.setVisibility(View.GONE);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);debugPanel.addView(bar);
        bar.addView(text("文字调试",20,INK),new LinearLayout.LayoutParams(0,-2,1));
        newChat=new Button(this);newChat.setText("新对话");newChat.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("清空当前对话，重新开始？").setNegativeButton("保留",null).setPositiveButton("开始新对话",(d,w)->{history.clear();expressions.stop();expressionIntensity=.5;setExpression("neutral");save();render();mood.setText("新的开始，你想聊什么？");}).show());bar.addView(newChat);
        Button tools=new Button(this);tools.setText("设备测试");tools.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));bar.addView(tools);
        Button faces=new Button(this);faces.setText("表情");faces.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("预览表情（同时填入测试文字）").setItems(EXPRESSION_NAMES,(d,which)->{if(busy)return;expressions.execute(Collections.singletonList(new ExpressionAction(EXPRESSIONS[which],.65,12000)),"preview");input.setText("请展示"+EXPRESSION_NAMES[which]+"的表情");showDebug(false);}).show());bar.addView(faces);
        Button voice=new Button(this);voice.setText(autoSpeak?"语音播报开":"语音播报关");voice.setOnClickListener(v->{autoSpeak=!autoSpeak;settings.edit().putBoolean("auto_speak",autoSpeak).apply();voice.setText(autoSpeak?"语音播报开":"语音播报关");if(!autoSpeak&&speech!=null)speech.stop();});bar.addView(voice);
        Button close=new Button(this);close.setText("返回面孔");close.setOnClickListener(v->showDebug(false));bar.addView(close);
        mood=text("文字仅用于调试，语音尚未接入",14,ACCENT);debugPanel.addView(mood);
        LinearLayout right=debugPanel;
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=new LinearLayout(this);messages.setOrientation(1);messages.setPadding(0,10,0,8);scroll.addView(messages);right.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout quick=new LinearLayout(this);
        for(String prompt:new String[]{"你好，小七","讲个短故事","帮我做计划"}){Button bq=new Button(this);bq.setText(prompt);bq.setTextSize(13);bq.setOnClickListener(v->{if(!busy){input.setText(prompt);input.setSelection(input.length());}});quick.addView(bq,new LinearLayout.LayoutParams(0,42,1));}right.addView(quick);
        LinearLayout entry=new LinearLayout(this);input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(0xff95a7b4);input.setHint("输入文字，也可用手机中文输入");input.setTextSize(16);input.setSingleLine(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEND){submitInput();return true;}return false;});entry.addView(input,new LinearLayout.LayoutParams(0,52,1));
        send=new Button(this);send.setText("发送");send.setOnClickListener(v->{if(busy)cancel();else submitInput();});entry.addView(send,new LinearLayout.LayoutParams(78,52));right.addView(entry);
        expressions=new ExpressionController(main,(action,id,index,state)->{
            expressionIntensity=action.intensity;setExpression(action.name);
            android.util.Log.i("PetChat","action request="+id+" index="+index+" name="+action.name+" intensity="+action.intensity+" state="+state);
        });
        speech=new SpeechOutput(this,(stateName,detail)->main.post(()->{
            android.util.Log.i("PetVoice","tts_state="+stateName+" detail="+detail);
            if("speaking".equals(stateName)){speaking=true;mood.setText("小七正在说话");}
            else if("completed".equals(stateName)){speaking=false;if(!busy)mood.setText("我在听你说");}
            else if("failed".equals(stateName)||"unavailable".equals(stateName)){speaking=false;if("unavailable".equals(stateName))mood.setText("本机中文语音不可用，文字对话仍可使用");}
            pet.invalidate();
        }));
        setContentView(root);render();applyDraft(getIntent());
    }
    void applyDraft(Intent intent){if(intent.hasExtra("draft")){String draft=intent.getStringExtra("draft");intent.removeExtra("draft");if(draft!=null&&!busy){input.setText(draft.substring(0,Math.min(500,draft.length())));showDebug(true);}}}
    void applyConfig(Intent intent){
        for(String name:new String[]{"llm_base","llm_model","llm_key","speech_base","speech_model","speech_key"})if(intent.hasExtra(name)){
            String value=intent.getStringExtra(name);settings.edit().putString(name,"__clear__".equals(value)?"":value).apply();intent.removeExtra(name);
        }
        SharedPreferences phoneSettings=getSharedPreferences("phone-speaker",MODE_PRIVATE);
        for(String name:new String[]{"debug_pair_key","debug_phone_ip"})if(intent.hasExtra(name)){
            String value=intent.getStringExtra(name);phoneSettings.edit().putString(name,"__clear__".equals(value)?"":value).apply();intent.removeExtra(name);
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);applyConfig(intent);applyDraft(intent);}
    void setExpression(String value){
        expression="neutral";for(String allowed:EXPRESSIONS)if(allowed.equals(value)){expression=value;break;}
        smileUntil=0;blink=false;pet.setContentDescription("小七的面孔："+expression);pet.invalidate();
        android.util.Log.i("PetChat","expression="+expression);
    }
    void showDebug(boolean show){
        debugVisible=show;debugPanel.setVisibility(show?View.VISIBLE:View.GONE);
        if(show)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
        if(!show){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);input.clearFocus();}
        pet.invalidate();
    }
    void showDeviceSettings(){
        android.util.Log.i("PetSettings","hidden_entry_opened");
        new AlertDialog.Builder(this)
                .setTitle("设备设置")
                .setMessage("选择要打开的设备功能")
                .setPositiveButton("返回系统桌面",(dialog,which)->openSystemHome())
                .setNeutralButton("设备测试",(dialog,which)->startActivity(new Intent(this,MainActivity.class)))
                .setNegativeButton("关闭",null)
                .show();
    }
    void openSystemHome(){
        Intent home=new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{
            startActivity(home);
        }catch(ActivityNotFoundException unavailable){
            Intent fallback=new Intent();
            fallback.setClassName("com.android.iotlauncher","com.android.iotlauncher.DefaultIoTLauncher");
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallback);
        }
        android.util.Log.i("PetSettings","system_home_requested");
    }
    @Override public void onBackPressed(){if(debugVisible)showDebug(false);else super.onBackPressed();}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);return t;}
    void bubble(String role,String content){TextView t=text((role.equals("user")?"你":"小七")+"\n"+content,17,INK);t.setPadding(14,10,14,10);t.setTextIsSelectable(true);GradientDrawable bg=new GradientDrawable();bg.setColor(role.equals("user")?0xff244b52:0xff203140);bg.setCornerRadius(14);t.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(role.equals("user")?34:0,0,role.equals("user")?0:26,10);messages.addView(t,lp);}
    void render(){messages.removeAllViews();if(history.isEmpty())bubble("assistant","你好，我是小七。想聊聊今天，还是一起想个小计划？");for(JSONObject m:history)bubble(m.optString("role"),m.optString("content"));if(busy){bubble("user",pending);bubble("assistant","正在想怎么回答你…");}scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
    void save(){while(history.size()>24){history.remove(0);history.remove(0);}settings.edit().putString("history",new JSONArray(history).toString()).apply();}
    void submitInput(){if(busy)return;String t=input.getText().toString().trim();if(t.isEmpty())return;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);begin(t,UUID.randomUUID().toString());}
    void toggleVoiceInput(){
        if(voiceRecording){voiceRecorder.stop();return;}
        if(busy||voiceTranscribing)return;
        if(speechBase.isEmpty()||speechKey.isEmpty()){
            Toast.makeText(this,"语音识别服务尚未配置",Toast.LENGTH_SHORT).show();
            android.util.Log.i("PetVoice","asr_skipped=not_configured");return;
        }
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},9);return;}
        startVoiceInput();
    }
    void startVoiceInput(){
        File file=new File(getFilesDir(),"voice-input.wav");voiceRecorder=new PcmRecorder(file,7000);
        try{
            voiceRecording=true;mic.setText("■");mic.setContentDescription("停止录音");mood.setText("正在听，请说话");pet.invalidate();
            voiceRecorder.start(result->main.post(()->finishVoiceInput(file,result)));
            android.util.Log.i("PetVoice","record_started max_ms=7000");
        }catch(Exception e){voiceRecording=false;mic.setText("●");mood.setText("无法启动录音");android.util.Log.i("PetVoice","record_failed="+e.getClass().getSimpleName());}
    }
    void finishVoiceInput(File file,PcmRecorder.Result result){
        voiceRecording=false;voiceRecorder=null;mic.setText("●");mic.setContentDescription("开始语音对话");
        android.util.Log.i("PetVoice","record_finished duration_ms="+result.durationMs()+" peak="+result.peak+" success="+result.success);
        if(!foreground||!result.success){mood.setText("录音时间太短，请重试");return;}
        voiceTranscribing=true;mood.setText("正在识别你说的话…");pet.invalidate();
        new Thread(()->{
            try{
                String transcript=SpeechApiClient.transcribe(file,speechBase,speechKey,speechModel);
                main.post(()->{voiceTranscribing=false;pet.invalidate();android.util.Log.i("PetVoice","asr_completed chars="+transcript.length());begin(transcript,UUID.randomUUID().toString());});
            }catch(Exception e){main.post(()->{String reason=failureReason(e);voiceTranscribing=false;pet.invalidate();mood.setText("empty_transcript".equals(reason)?"没有听清，请靠近麦克风再试一次":"语音识别失败，可用文字继续");android.util.Log.i("PetVoice","asr_failed="+reason);});}
        },"pet-asr-request").start();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==9&&results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)startVoiceInput();
    }
    void speakReply(String reply){if(autoSpeak&&speech!=null&&!speech.speak(reply))android.util.Log.i("PetVoice","tts_skipped=not_ready");}
    JSONObject msg(String role,String text)throws JSONException{return new JSONObject().put("role",role).put("content",text);}
    boolean begin(String t,String id){return begin(t,id,null);}
    boolean beginPlan(JSONObject data,String id){
        if(busy||!foreground)return false;
        try{String result=HomeworkSupport.plan(data);remoteId=id;remoteHomework=new JSONObject();remoteReply=result;remoteError="";history.add(msg("user","根据已核对的作业生成时间计划"));history.add(msg("assistant",result));save();render();remoteState="completed";speakReply(result);return true;}catch(Exception e){return false;}
    }
    boolean begin(String t,String id,String image){
        if(busy||!foreground)return false;
        if(t.isEmpty()||t.length()>500)return false;
        List<ExpressionAction> local=image==null?ExpressionAction.resolve(t,expression,expressionIntensity):null;
        remoteHomework=new JSONObject();
        if(local!=null){
            remoteId=id;remoteError="";expressions.execute(local,id);
            String reply=local.size()>1?"好，我先哭，再笑给你看。":"好，看我的表情！";
            try{history.add(msg("user",t));history.add(msg("assistant",reply));}catch(JSONException ignored){}
            remoteReply=reply;remoteState="completed";input.setText("");save();render();showDebug(false);
            speakReply(reply);
            android.util.Log.i("PetChat","decision request="+id+" source=local count="+local.size());return true;
        }
        if(key.isEmpty()||!base.startsWith("http")||model.isEmpty()){mood.setText("尚未配置对话服务");remoteError="not_configured";return false;}
        remoteId=id;remoteState="thinking";remoteReply="";remoteError="";busy=true;pending=t;input.setText("");input.setEnabled(false);send.setText("取消");newChat.setEnabled(false);mood.setText("让我想一想…");render();pet.invalidate();long ticket=++generation;
        final boolean holdExpression=ExpressionAction.isSelfReport(t);
        String instruction=image!=null?HomeworkSupport.PROMPT:SYSTEM+" 只针对本轮用户消息生成动作，不重复历史动作。"+(holdExpression?"本轮是用户描述自身心情，只共情交谈，actions必须为空，不宣称展示表情。":"")+" 当前屏幕表情="+expression+"，强度="+expressionIntensity+"。";
        final JSONArray context=new JSONArray();try{context.put(msg("system",instruction));}catch(JSONException e){cancel();return false;}
        if(image==null)for(JSONObject m:history)context.put(m);
        new Thread(()->{
            long started=SystemClock.elapsedRealtime();
            try{if(ticket!=generation||!foreground)return;if(image==null)context.put(msg("user",t));else context.put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",t)).put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/jpeg;base64,"+image)))));
                JSONObject body=new JSONObject().put("model",model).put("messages",context).put("stream",false).put("max_tokens",image==null?384:1800).put("enable_thinking",false).put("response_format",new JSONObject().put("type","json_object"));
                HttpURLConnection c=(HttpURLConnection)new URL(base.replaceAll("/+$","")+"/chat/completions").openConnection();connection=c;
                try{if(ticket!=generation||!foreground)return;c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setDoOutput(true);byte[] data=body.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(data.length);try(OutputStream o=c.getOutputStream()){o.write(data);}
                    int code=c.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);
                    ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>262144)throw new IOException("response_limit");out.write(b,0,n);}}
                    JSONObject answer=new JSONObject(out.toString("UTF-8")).getJSONArray("choices").getJSONObject(0).getJSONObject("message");String reply=answer.isNull("content")?"":answer.optString("content","").trim();if(reply.isEmpty())throw new IOException("empty_reply");if(reply.length()>(image==null?4096:16000))throw new IOException("response_limit");
                    JSONObject structured=new JSONObject(reply);
                    if(image!=null){
                        final String draft=HomeworkSupport.draft(structured);
                        main.post(()->{if(ticket!=generation||!foreground)return;try{history.add(msg("user","[作业截图] "+t));history.add(msg("assistant",draft));}catch(JSONException ignored){}remoteHomework=structured;remoteReply=draft;remoteState="completed";save();finishUi();mood.setText("请在手机核对作业");android.util.Log.i("PetChat","homework_recognized tasks="+structured.optJSONArray("tasks").length());});return;
                    }
                    if(!(structured.opt("reply") instanceof String)||!(structured.opt("actions") instanceof JSONArray))throw new IOException("invalid_reply_format");
                    final String result=structured.getString("reply").trim();
                    if(result.isEmpty())throw new IOException("empty_reply");
                    final ArrayList<ExpressionAction> actions=new ArrayList<>();JSONArray list=structured.getJSONArray("actions");
                    if(list.length()>5)throw new IOException("too_many_actions");
                    for(int i=0;i<list.length();i++){
                        JSONObject action=list.getJSONObject(i);
                        if(!"set_expression".equals(action.optString("type"))||!(action.opt("name") instanceof String)||!(action.opt("intensity") instanceof Number)||!(action.opt("duration_ms") instanceof Number))throw new IOException("invalid_action");
                        double duration=action.getDouble("duration_ms");if(duration!=Math.rint(duration))throw new IOException("invalid_duration");
                        actions.add(new ExpressionAction(action.getString("name"),action.getDouble("intensity"),(int)duration));
                    }
                    if(holdExpression){actions.clear();android.util.Log.i("PetChat","guard request="+id+" reason=self_report");}
                    main.post(()->{if(ticket!=generation||!foreground)return;try{history.add(msg("user",t));history.add(msg("assistant",result));}catch(JSONException ignored){}remoteReply=result;remoteState="completed";expressions.execute(actions,id);android.util.Log.i("PetChat","decision request="+id+" source=model count="+actions.size());save();finishUi();mood.setText("我在听你说");speakReply(result);android.util.Log.i("PetChat","completed elapsed_ms="+(SystemClock.elapsedRealtime()-started)+" turns="+(history.size()/2));});
                }finally{c.disconnect();if(ticket==generation)connection=null;}
            }catch(Exception e){main.post(()->{if(ticket!=generation||!foreground)return;remoteState="failed";remoteError=e instanceof IOException?"connection_or_http_error":"response_error";finishUi();input.setText(t);mood.setText("这次没连上，文字已保留");android.util.Log.i("PetChat","failed="+failureReason(e));});}
        },"pet-chat-request").start();return true;
    }
    static String failureReason(Exception e){
        String reason=e.getMessage();
        if(reason!=null&&(reason.matches("HTTP [0-9]{3}")||Arrays.asList("invalid_reply_format","empty_reply","empty_transcript","too_many_actions","invalid_action","invalid_duration","response_limit").contains(reason)))return reason;
        return e.getClass().getSimpleName();
    }
    void finishUi(){busy=false;pending="";input.setEnabled(true);send.setText("发送");newChat.setEnabled(true);render();pet.invalidate();}
    void cancel(){String retry=pending;generation++;if(connection!=null)connection.disconnect();connection=null;remoteState="cancelled";finishUi();input.setText(retry);mood.setText("已取消，你可以重新编辑");}
    @Override protected void onResume(){super.onResume();foreground=true;expressions.resume();main.post(tick);
        SharedPreferences p=getSharedPreferences("phone-speaker",MODE_PRIVATE);String token=p.getString("pair_key","");String debugToken=p.getString("debug_pair_key","");
        if(token.length()>=24||debugToken.length()>=24){inputServer=new TextInputServer(this,token,debugToken);inputServer.start();}
    }
    @Override protected void onPause(){foreground=false;expressions.pause();main.removeCallbacks(tick);if(voiceRecorder!=null)voiceRecorder.stop();if(busy)cancel();if(inputServer!=null){inputServer.close();inputServer=null;}super.onPause();}
    @Override protected void onDestroy(){expressions.stop();if(speech!=null)speech.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
    class Pet extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Runnable openSettings=new Runnable(){public void run(){
            if(!foreheadPressed)return;
            foreheadPressed=false;longPressHandled=true;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);showDeviceSettings();
        }};
        boolean foreheadPressed,longPressHandled,moved;
        float downX,downY;
        final float touchSlop;
        Pet(){super(ChatActivity.this);touchSlop=ViewConfiguration.get(ChatActivity.this).getScaledTouchSlop();setClickable(true);}
        boolean isForehead(float x,float y){
            float scale=Math.min(getWidth()/800f,getHeight()/480f);
            if(scale<=0)return false;
            float localX=(x-getWidth()/2f)/scale,localY=(y-getHeight()/2f)/scale;
            return Math.abs(localX)<=150&&localY>=-220&&localY<=-105;
        }
        void cancelSettingsHold(){foreheadPressed=false;main.removeCallbacks(openSettings);}
        @Override public boolean onTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=event.getX();downY=event.getY();moved=false;longPressHandled=false;
                    foreheadPressed=isForehead(downX,downY);
                    if(foreheadPressed)main.postDelayed(openSettings,3000);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=event.getX()-downX,dy=event.getY()-downY;
                    if(dx*dx+dy*dy>touchSlop*touchSlop||!isForehead(event.getX(),event.getY())){moved=true;cancelSettingsHold();}
                    return true;
                case MotionEvent.ACTION_UP:
                    cancelSettingsHold();
                    if(!moved&&!longPressHandled)performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelSettingsHold();return true;
                default:return true;
            }
        }
        @Override public boolean performClick(){
            super.performClick();smileUntil=SystemClock.uptimeMillis()+1800;invalidate();main.postDelayed(()->invalidate(),1800);return true;
        }
        @Override protected void onDetachedFromWindow(){cancelSettingsHold();super.onDetachedFromWindow();}
        void fill(Canvas c,int color,float l,float t,float r,float b,float radius){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(l,t,r,b,radius,radius,p);}
        void stroke(Canvas c,int color,float width){p.setStyle(Paint.Style.STROKE);p.setColor(color);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);}
        Path eyePath(float x,float y,float openness){Path path=new Path();path.moveTo(x-61,y);path.cubicTo(x-30,y-31*openness,x+30,y-31*openness,x+61,y);path.cubicTo(x+30,y+34*openness,x-30,y+34*openness,x-61,y);path.close();return path;}
        void drawEye(Canvas c,float x,float y,float openness,float gaze,boolean close){
            if(close||openness<.12f){stroke(c,0xff4c302b,7);c.drawPath(eyePath(x,y,.18f),p);return;}
            Path eye=eyePath(x,y,openness);p.setStyle(Paint.Style.FILL);p.setColor(0xfffffcf7);c.drawPath(eye,p);
            c.save();c.clipPath(eye);float irisX=x+gaze;
            p.setShader(new RadialGradient(irisX-5,y-7,30,new int[]{0xffb9d8c6,0xff568f7d,0xff294f48},null,Shader.TileMode.CLAMP));c.drawCircle(irisX,y,29,p);p.setShader(null);
            p.setColor(0xff152b2a);c.drawCircle(irisX,y,13,p);p.setColor(0xffffffff);c.drawCircle(irisX-8,y-9,6,p);c.restore();
            stroke(c,0xff4c302b,6);c.drawPath(eye,p);
        }
        void drawBrow(Canvas c,float x,float y,float innerOffset,float outerOffset){
            Path brow=new Path();brow.moveTo(x-58,y+outerOffset);brow.cubicTo(x-25,y-12,x+24,y-12,x+58,y+innerOffset);stroke(c,0xff513831,12);c.drawPath(brow,p);
        }
        void drawMouth(Canvas c,String shown,boolean thinking){
            int lip=0xffa85d62;
            if(speaking||shown.equals("surprised")){float open=speaking?(blink?24:42):54;p.setStyle(Paint.Style.FILL);p.setColor(0xff5b2529);c.drawOval(-45,103,45,103+open,p);p.setColor(0xffd98888);c.drawOval(-28,125,28,142,p);return;}
            if(shown.equals("joyful")){p.setStyle(Paint.Style.FILL);p.setColor(0xff64292d);c.drawRoundRect(-78,92,78,161,38,38,p);p.setColor(0xfffff8ee);c.drawRoundRect(-62,94,62,116,8,8,p);p.setColor(0xffdd8587);c.drawOval(-49,132,49,158,p);return;}
            Path upper=new Path(),lower=new Path();
            if(shown.equals("happy")){upper.moveTo(-65,109);upper.cubicTo(-27,128,27,128,65,109);lower.moveTo(-65,109);lower.cubicTo(-28,158,28,158,65,109);}
            else if(shown.equals("sad")){upper.moveTo(-58,136);upper.cubicTo(-25,105,25,105,58,136);lower.moveTo(-55,139);lower.cubicTo(-20,126,20,126,55,139);}
            else if(shown.equals("worried")){upper.moveTo(-59,127);upper.cubicTo(-31,101,-7,143,17,117);upper.cubicTo(33,101,48,118,61,124);lower.moveTo(-57,131);lower.cubicTo(-17,144,23,139,58,127);}
            else if(shown.equals("angry")){upper.moveTo(-58,122);upper.cubicTo(-20,116,20,116,58,122);lower.moveTo(-51,130);lower.cubicTo(-15,139,15,139,51,130);}
            else{upper.moveTo(-57,119);upper.cubicTo(-22,126,22,126,57,119);lower.moveTo(-55,122);lower.cubicTo(-18,139,18,139,55,122);}
            p.setStyle(Paint.Style.FILL);p.setColor(lip);Path lips=new Path(upper);lips.lineTo(58,shown.equals("happy")?112:128);lips.addPath(lower);lips.close();c.drawPath(lips,p);stroke(c,0xff7f4147,3);c.drawPath(upper,p);
        }
        @Override protected void onDraw(Canvas c){
            c.drawColor(0xffe8f1dd);float scale=Math.min(getWidth()/800f,getHeight()/480f);c.save();c.translate(getWidth()/2f,getHeight()/2f);c.scale(scale,scale);
            String shown=(busy||voiceTranscribing)?"thinking":(SystemClock.uptimeMillis()<smileUntil?"happy":expression);
            boolean happy=shown.equals("happy"),joy=shown.equals("joyful"),angry=shown.equals("angry"),sad=shown.equals("sad"),worried=shown.equals("worried"),surprised=shown.equals("surprised"),thinking=busy||voiceTranscribing;
            fill(c,0xffd5e5cc,-345,-224,345,218,150);fill(c,0xffe8f1dd,-337,-224,337,198,145);
            stroke(c,0xff9ebca3,7);c.drawArc(-48,-202,0,-147,20,125,false,p);c.drawArc(-9,-207,39,-150,25,120,false,p);
            int dark=0xff244c48;stroke(c,dark,10);
            if(angry){float tilt=(float)(18+40*expressionIntensity);c.drawLine(-242,-112-tilt,-141,-112+tilt/2,p);c.drawLine(141,-112+tilt/2,242,-112-tilt,p);}
            else if(sad||worried){c.drawLine(-242,-104,-145,-144,p);c.drawLine(145,-144,242,-104,p);}
            else if(surprised){c.drawArc(-241,-170,-142,-121,195,150,false,p);c.drawArc(142,-170,241,-121,195,150,false,p);}
            else{c.drawLine(-233,-119,-159,thinking?-134:-128,p);c.drawLine(159,thinking?-132:-128,233,-119,p);}
            for(int side:new int[]{-1,1}){float x=side*185;
                if((blink&&!angry&&!sad&&!worried&&!surprised)||happy||joy){stroke(c,dark,15);c.drawArc(x-57,-67,x+57,25,(happy||joy)?195:15,150,false,p);}
                else{fill(c,dark,x-65,-86,x+65,57,59);fill(c,0xff79d8b8,x-44,-59,x+43,42,39);float dx=(thinking||worried)?13:0;fill(c,dark,x-23+dx,-51,x+26+dx,37,24);fill(c,0xfff8fff2,x-18+dx,-48,x-1+dx,-24,9);
                    if(angry){p.setStyle(Paint.Style.FILL);p.setColor(0xffe8f1dd);Path lid=new Path();float drop=(float)(45*expressionIntensity);lid.moveTo(x-68,-90);lid.lineTo(x+68,-90);lid.lineTo(x+68,-84+(side<0?drop:0));lid.lineTo(x-68,-84+(side>0?drop:0));lid.close();c.drawPath(lid,p);}
                    if(thinking||worried)fill(c,0xffe8f1dd,x-69,-89,x+69,-46,0);
                }
            }
            if(sad){fill(c,0xff80cbe1,-218,45,-185,117,16);fill(c,0xff80cbe1,185,45,218,117,16);}
            if(angry){stroke(c,0xffcb776b,6);c.drawLine(267,-155,267,-135,p);c.drawLine(279,-124,298,-124,p);c.drawLine(267,-112,267,-93,p);c.drawLine(238,-124,256,-124,p);}
            if(worried){fill(c,0xff80cbe1,274,-92,297,-48,12);stroke(c,0xff9ebca3,4);c.drawLine(-24,-130,0,-142,p);c.drawLine(0,-142,24,-130,p);}
            fill(c,angry?0xffe69283:0xffefb6a4,-284,49,-211,76,14);fill(c,0xffefb6a4,211,49,284,76,14);
            p.setStyle(Paint.Style.FILL);p.setColor(dark);Path nose=new Path();nose.moveTo(-18,30);nose.quadTo(0,21,18,30);nose.quadTo(14,43,0,47);nose.quadTo(-14,43,-18,30);c.drawPath(nose,p);stroke(c,dark,8);c.drawLine(0,47,0,66,p);
            if(speaking){float open=blink?20:43;fill(c,dark,-48,79,48,79+open,24);if(!blink)fill(c,0xffdf9b97,-28,101,28,121,13);}
            else if(thinking)c.drawOval(35,79,64,101,p);
            else if(joy){fill(c,dark,-69,65,69,146,50);fill(c,0xfff6ffef,-45,67,45,83,7);fill(c,0xffdf9b97,-38,111,38,143,19);}
            else if(surprised)c.drawOval(-25,76,25,136,p);
            else if(sad)c.drawArc(-56,97,56,151,205,130,false,p);
            else if(worried){Path mouth=new Path();mouth.moveTo(-48,106);mouth.cubicTo(-18,85,12,125,48,103);c.drawPath(mouth,p);}
            else if(angry)c.drawArc(-52,93,52,(float)(115+40*expressionIntensity),200,140,false,p);
            else{c.drawArc(-75,38,0,100,0,145,false,p);c.drawArc(0,38,75,100,35,145,false,p);if(happy)fill(c,0xffd98985,-23,93,23,115,15);}
            c.restore();
        }
        void drawHumanFace(Canvas c){
            p.setShader(new LinearGradient(0,0,getWidth(),getHeight(),0xff213642,0xff6f8c89,Shader.TileMode.CLAMP));c.drawRect(0,0,getWidth(),getHeight(),p);p.setShader(null);
            float scale=Math.min(getWidth()/800f,getHeight()/480f);c.save();c.translate(getWidth()/2f,getHeight()/2f);c.scale(scale,scale);
            String shown=(busy||voiceTranscribing)?"thinking":(SystemClock.uptimeMillis()<smileUntil?"happy":expression);
            boolean happy=shown.equals("happy"),joy=shown.equals("joyful"),angry=shown.equals("angry"),sad=shown.equals("sad"),worried=shown.equals("worried"),surprised=shown.equals("surprised"),thinking=busy||voiceTranscribing;
            // Ears and a softly shaded human face fill the display like a portrait.
            p.setStyle(Paint.Style.FILL);p.setColor(0xffd99c82);c.drawOval(-330,-62,-266,75,p);c.drawOval(266,-62,330,75,p);
            p.setShader(new RadialGradient(-70,-55,365,new int[]{0xffffdfc7,0xffefb99d,0xffc47e69},null,Shader.TileMode.CLAMP));c.drawOval(-292,-260,292,286,p);p.setShader(null);
            // Hair, temples and fringe.
            Path hair=new Path();hair.moveTo(-286,-70);hair.cubicTo(-310,-240,-188,-292,0,-280);hair.cubicTo(190,-292,312,-226,286,-62);hair.lineTo(250,-174);hair.cubicTo(176,-215,78,-211,22,-181);hair.cubicTo(-70,-226,-183,-213,-248,-158);hair.close();p.setColor(0xff352825);c.drawPath(hair,p);
            p.setColor(0x182f1f1b);c.drawOval(-262,105,-122,220,p);c.drawOval(122,105,262,220,p);
            // Brows carry most of the emotional direction.
            if(angry){drawBrow(c,-145,-89,(float)(35*expressionIntensity),-16);c.save();c.scale(-1,1);drawBrow(c,-145,-89,(float)(35*expressionIntensity),-16);c.restore();}
            else if(sad||worried){drawBrow(c,-145,-90,-26,14);c.save();c.scale(-1,1);drawBrow(c,-145,-90,-26,14);c.restore();}
            else if(surprised){drawBrow(c,-145,-119,-5,-5);c.save();c.scale(-1,1);drawBrow(c,-145,-119,-5,-5);c.restore();}
            else{drawBrow(c,-145,-91,thinking?-7:0,0);c.save();c.scale(-1,1);drawBrow(c,-145,-91,thinking?-7:0,0);c.restore();}
            boolean eyesClosed=(blink&&!angry&&!sad&&!worried&&!surprised)||happy||joy;
            float openness=surprised?1.28f:angry?.64f:sad?.72f:worried?.82f:1f;float gaze=(thinking||worried)?13:0;
            drawEye(c,-145,-28,openness,gaze,eyesClosed);drawEye(c,145,-28,openness,gaze,eyesClosed);
            // Under-eye shading, cheeks and tears/sweat make expressions less symbolic.
            p.setColor(0x167a493f);c.drawOval(-210,18,-82,63,p);c.drawOval(82,18,210,63,p);
            p.setColor(angry?0x55d65d55:happy||joy?0x55ed8d82:0x32df8d83);c.drawOval(-247,55,-158,93,p);c.drawOval(158,55,247,93,p);
            if(sad){p.setColor(0xaa72bdd8);c.drawOval(-177,15,-151,91,p);c.drawOval(151,15,177,91,p);}
            if(worried){p.setColor(0xcc70bdd1);Path drop=new Path();drop.moveTo(246,-64);drop.quadTo(274,-25,246,-4);drop.quadTo(218,-25,246,-64);c.drawPath(drop,p);}
            // Nose bridge, tip and nostrils.
            p.setShader(new LinearGradient(-28,-60,38,75,0x00b56855,0x558b4f43,Shader.TileMode.CLAMP));c.drawOval(-31,-69,39,83,p);p.setShader(null);
            p.setColor(0x3dc77c69);c.drawOval(-34,55,34,91,p);stroke(c,0xff8c5149,4);c.drawArc(-34,61,-3,85,15,145,false,p);c.drawArc(3,61,34,85,20,145,false,p);
            drawMouth(c,shown,thinking);
            // Subtle jaw shadow and chin highlight.
            stroke(c,0x285d3731,4);c.drawArc(-162,139,162,245,17,146,false,p);p.setStyle(Paint.Style.FILL);p.setColor(0x25ffffff);c.drawOval(-64,174,64,211,p);
            c.restore();
        }
    }
}
