package com.zhb.phonespeaker;
import android.app.Activity;
import android.os.*;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.text.InputFilter;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.UUID;

public class TextChatActivity extends Activity {
    EditText input;TextView output;Button send;String host,key;volatile boolean active,busy;volatile HttpURLConnection connection;
    public void onCreate(Bundle b){super.onCreate(b);android.content.SharedPreferences prefs=getSharedPreferences("lan",MODE_PRIVATE);host=prefs.getString("board_ip","");key=prefs.getString("pair_key","");
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,24,20,20);
        TextView title=new TextView(this);title.setText("和小七聊聊");title.setTextSize(26);root.addView(title);
        TextView tip=new TextView(this);tip.setText("手机输入中文，iMX7 同步显示对话与表情。\n请保持 iMX7 在宠物对话页面。");root.addView(tip);
        Button homework=new Button(this);homework.setText("发送作业截图 / 规划时间");homework.setOnClickListener(v->startActivity(new android.content.Intent(this,HomeworkActivity.class)));root.addView(homework);
        ScrollView scroll=new ScrollView(this);output=new TextView(this);output.setTextSize(20);output.setTextIsSelectable(true);output.setPadding(0,20,0,20);output.setText("小七准备好听你说了。");scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        input=new EditText(this);input.setTextSize(18);input.setHint("想对小七说什么？");input.setSingleLine(true);input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        input.setOnEditorActionListener((view,action,event)->{
            boolean sendAction=action==EditorInfo.IME_ACTION_SEND;
            boolean enterKey=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN;
            if(sendAction||enterKey){send();return true;}
            return false;
        });root.addView(input);
        send=new Button(this);send.setText("发送给小七");send.setOnClickListener(v->send());root.addView(send);setContentView(root);
        // ADB-supplied harmless test text fills the editor only; it never sends automatically.
        if(getIntent().hasExtra("draft")){input.setText(getIntent().getStringExtra("draft"));getIntent().removeExtra("draft");}
    }
    protected void onResume(){super.onResume();active=true;}
    protected void onPause(){active=false;if(connection!=null)connection.disconnect();super.onPause();}
    void show(String text){runOnUiThread(()->{if(active)output.setText(text);});}
    void send(){String text=input.getText().toString().trim();if(busy||text.isEmpty())return;
        if(key.isEmpty()||host.isEmpty()){output.setText("请先完成两台设备的连接配置");return;}
        busy=true;send.setEnabled(false);input.setEnabled(false);show("你："+text+"\n\n小七正在思考…");
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        new Thread(()->{try{String id=UUID.randomUUID().toString();request("/input",new JSONObject().put("id",id).put("text",text));long deadline=SystemClock.elapsedRealtime()+75000;
            while(active&&SystemClock.elapsedRealtime()<deadline){JSONObject data=request("/status?id="+id,null);String state=data.optString("state");
                if(state.equals("completed")){show("你："+text+"\n\n小七："+data.optString("reply"));runOnUiThread(()->input.setText(""));android.util.Log.i("PetText","completed");return;}
                if(state.equals("failed")||state.equals("cancelled"))throw new IOException("chat_failed");Thread.sleep(700);}
            if(active)throw new IOException("timeout");
        }catch(Exception e){show("本次对话未完成，文字已保留。请查看 iMX7 是否在对话页面及网络状态。\n\n你："+text);android.util.Log.i("PetText","failed="+e.getClass().getSimpleName());}
        finally{busy=false;runOnUiThread(()->{send.setEnabled(true);input.setEnabled(true);});}},"pet-remote-text").start();
    }
    JSONObject request(String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://"+host+":8766"+path).openConnection();connection=c;c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Pet-Key",key);
        try{if(body!=null){byte[] b=body.toString().getBytes("UTF-8");c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(b.length);try(OutputStream o=c.getOutputStream()){o.write(b);}}
            int code=c.getResponseCode();if(code!=200&&code!=202)throw new IOException("http_"+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>32768)throw new IOException("response_limit");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}
        }finally{c.disconnect();connection=null;}
    }
}
