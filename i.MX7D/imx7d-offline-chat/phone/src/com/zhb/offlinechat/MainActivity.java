package com.zhb.offlinechat;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.inputmethod.*;
import android.widget.*;
import android.text.InputFilter;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Text-only companion. It contains neither microphone nor playback code. */
public final class MainActivity extends Activity {
 final Handler main=new Handler(Looper.getMainLooper());final ExecutorService io=Executors.newSingleThreadExecutor();
 TextView status,progress;EditText input;LinearLayout messages;ScrollView scroll;Button send,cancel;String host="",key="",pending="",pendingText="";JSONArray history=new JSONArray();
 volatile HttpURLConnection connection;boolean active,sending;int generation;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  android.content.SharedPreferences p=getSharedPreferences("chat",MODE_PRIVATE);pending=p.getString("pending","");pendingText=p.getString("pending_text","");try{history=new JSONArray(p.getString("history","[]"));}catch(Exception ignored){}
  try{JSONObject c=new JSONObject(read(new File(getFilesDir(),"lan.json"),4096));host=c.getString("host");key=c.getString("key");}catch(Exception ignored){}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,24,20,12);root.setBackgroundColor(Color.rgb(246,248,251));setContentView(root);
  TextView title=new TextView(this);title.setText("小七文字助手");title.setTextSize(25);title.setTextColor(Color.rgb(15,23,42));root.addView(title);
  TextView tip=new TextView(this);tip.setText("纯文字 · 不开麦 · 不播报\n任务和对话模型均在i.MX7执行，手机只通过局域网连接。");root.addView(tip);
  status=new TextView(this);status.setTextSize(15);root.addView(status);Button settings=new Button(this);settings.setText("连接设置 / 检查连接");settings.setOnClickListener(v->settings());root.addView(settings);
  scroll=new ScrollView(this);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  progress=new TextView(this);progress.setTextSize(15);root.addView(progress);
  LinearLayout quick=new LinearLayout(this);root.addView(quick);quick(quick,"设备状态","查询设备状态");quick(quick,"制定计划","计划：写报告30分钟，复盘20分钟；开始19:00；可用120分钟");quick(quick,"表情","请展示开心表情");
  input=new EditText(this);input.setTextSize(18);input.setHint("输入要交给小七的文字");input.setSingleLine(true);input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});input.setText(p.getString("draft",""));root.addView(input);
  input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEND){send();return true;}return false;});
  LinearLayout row=new LinearLayout(this);root.addView(row);cancel=new Button(this);cancel.setText("取消 / 结束查询");cancel.setOnClickListener(v->confirmCancel());row.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));send=new Button(this);send.setText("发送");send.setOnClickListener(v->send());row.addView(send,new LinearLayout.LayoutParams(0,-2,1));render();
 }
 void quick(LinearLayout row,String label,String text){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setOnClickListener(v->{input.setText(text);input.setSelection(input.length());input.requestFocus();});row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
 static final class HttpFailure extends IOException { final int code;HttpFailure(int code,String message){super(message);this.code=code;} }
 static String read(File file,int max)throws IOException{try(InputStream in=new FileInputStream(file)){return readStream(in,max);}}
 static String readStream(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>max)throw new IOException("response_limit");out.write(b,0,n);}return out.toString("UTF-8");}
 void save(){getSharedPreferences("chat",MODE_PRIVATE).edit().putString("history",history.toString()).putString("pending",pending).putString("pending_text",pendingText).putString("draft",input.getText().toString()).apply();}
 void add(String role,String text){try{history.put(new JSONObject().put("role",role).put("text",text));if(history.length()>100){JSONArray trimmed=new JSONArray();for(int i=history.length()-100;i<history.length();i++)trimmed.put(history.getJSONObject(i));history=trimmed;}}catch(JSONException ignored){}}
 void render(){messages.removeAllViews();if(history.length()==0)addView("小七","可以先查询设备状态。计划示例可从下方按钮填入；通用短聊由设备本地模型生成，可能较慢。");for(int i=0;i<history.length();i++){JSONObject m=history.optJSONObject(i);if(m!=null)addView(m.optString("role").equals("user")?"你":"小七",m.optString("text"));}send.setEnabled(pending.isEmpty()&&!sending);cancel.setEnabled(!pending.isEmpty());scroll.post(()->scroll.fullScroll(ScrollView.FOCUS_DOWN));}
 void addView(String who,String text){TextView v=new TextView(this);v.setText(who+"\n"+text);v.setTextSize(18);v.setTextIsSelectable(true);v.setPadding(16,14,16,14);v.setBackgroundColor(who.equals("你")?0xffdbeafe:Color.WHITE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,6,0,6);messages.addView(v,lp);}
 boolean configured(){return key.length()>=32&&privateIp(host);}
 static boolean privateIp(String value){try{String[] parts=value.split("\\.");if(parts.length!=4)return false;int[] n=new int[4];for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))return false;n[i]=Integer.parseInt(parts[i]);if(n[i]>255)return false;}return n[0]==10||(n[0]==192&&n[1]==168)||(n[0]==172&&n[1]>=16&&n[1]<=31);}catch(Exception e){return false;}}
 JSONObject request(String path,JSONObject body)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL("http://"+host+":8777"+path).openConnection();connection=c;c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Pet-Key",key);
  try{if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
   int code=c.getResponseCode();if(code<200||code>=300)throw new HttpFailure(code,code==403?"配对不匹配":code==409?"设备忙或任务状态已改变":code==404?"任务不存在或设备服务已重启":"HTTP "+code);try(InputStream in=c.getInputStream()){return new JSONObject(readStream(in,65536));}
  }finally{c.disconnect();if(connection==c)connection=null;}
 }
 void send(){if(sending||!pending.isEmpty())return;String text=input.getText().toString().trim();if(text.isEmpty())return;if(!configured()){progress.setText("请先完成两端配对，再检查局域网地址。");return;}
  sending=true;pending=UUID.randomUUID().toString();pendingText=text;add("user",text);progress.setText("正在提交给设备…");save();render();String id=pending;int ticket=generation;
  ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
  io.execute(()->{try{request("/input",new JSONObject().put("id",id).put("text",text));main.post(()->{if(ticket!=generation)return;input.setText("");progress.setText("设备已接收，正在本地处理");save();poll();});}catch(Exception e){main.post(()->{if(ticket!=generation)return;if(e instanceof HttpFailure){add("assistant","设备未接受本次请求："+e.getMessage());input.setText(pendingText);pending="";pendingText="";save();progress.setText("原文已保留，请检查连接或等待设备空闲后重新提交。");}else progress.setText("提交结果未确认："+e.getMessage()+"。保留原文与任务编号，将只查询结果，不自动重发。");});}finally{main.post(()->{sending=false;render();if(active&&!pending.isEmpty())main.postDelayed(poller,1500);});}});
 }
 final Runnable poller=this::poll;
 void poll(){if(!active||pending.isEmpty()||sending)return;String id=pending;int ticket=generation;
  io.execute(()->{try{JSONObject r=request("/status?id="+id,null);main.post(()->{if(ticket!=generation||!id.equals(pending))return;String state=r.optString("state");if(Arrays.asList("completed","failed","cancelled").contains(state)){
    add("assistant",state.equals("completed")?r.optString("reply"):"本次未完成："+r.optString("error"));if(!state.equals("completed"))input.setText(pendingText);pending="";pendingText="";progress.setText(state.equals("completed")?"设备处理完成 · 完全离线":"原文已保留，可编辑后重新提交");save();render();
   }else progress.setText(r.optString("progress","设备处理中"));});}catch(Exception e){main.post(()->{if(ticket==generation)progress.setText("暂时无法确认任务结果："+e.getMessage()+"。连接恢复后继续查询，不自动重发。");});}finally{main.post(()->{if(active&&ticket==generation&&!pending.isEmpty()){main.removeCallbacks(poller);main.postDelayed(poller,1500);}});}});
 }
 void health(){if(!configured()){status.setText("尚未配对。需要通过本机配置工具完成首次配对。");return;}int ticket=generation;io.execute(()->{try{JSONObject r=request("/health",null);main.post(()->{if(ticket==generation)status.setText((r.optBoolean("offline")?"已连接 · 完全离线":"连接模式异常")+" · "+host+"\n本地模型"+(r.optBoolean("model_ready")?"已准备":"未准备")+" · "+(r.optBoolean("busy")?"设备忙":"设备空闲"));});}catch(Exception e){main.post(()->{if(ticket==generation)status.setText("未连接："+host+"\n请确认同一局域网且i.MX7文字服务已启动。");});}});}
 void settings(){EditText address=new EditText(this);address.setSingleLine(true);address.setText(host);new AlertDialog.Builder(this).setTitle("i.MX7 局域网地址").setMessage("首次配对由本机配置工具完成；这里仅更新同一设备的内网IP，不显示配对凭据。").setView(address).setNegativeButton("取消",null).setPositiveButton("保存并检查",(d,w)->{String value=address.getText().toString().trim();if(!privateIp(value)){progress.setText("请输入有效的内网IPv4地址。");return;}host=value;try{JSONObject c=new JSONObject().put("host",host).put("key",key);try(OutputStream out=new FileOutputStream(new File(getFilesDir(),"lan.json"))){out.write(c.toString().getBytes(StandardCharsets.UTF_8));}health();}catch(Exception e){progress.setText("地址保存失败");}}).show();}
 void confirmCancel(){if(pending.isEmpty())return;String id=pending;new AlertDialog.Builder(this).setTitle("取消当前设备任务？").setMessage("将停止这次尚未完成的处理；已经执行的设备动作不会撤销。").setNegativeButton("继续等待",null).setNeutralButton("仅结束手机查询",(d,w)->{input.setText(pendingText);pending="";pendingText="";save();render();progress.setText("已结束手机查询；设备上的任务可能仍在运行，没有自动重发。");}).setPositiveButton("取消任务",(d,w)->io.execute(()->{try{request("/cancel",new JSONObject().put("id",id));}catch(Exception ignored){}main.post(this::poll);})).show();}
 @Override protected void onResume(){super.onResume();active=true;generation++;health();if(!pending.isEmpty())main.postDelayed(poller,300);}
 @Override protected void onPause(){active=false;generation++;main.removeCallbacks(poller);save();if(connection!=null)connection.disconnect();super.onPause();}
 @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
