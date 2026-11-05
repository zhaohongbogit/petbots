package com.zhb.petscreen;
import android.app.*;
import android.os.*;
import android.content.*;
import android.widget.*;
public final class OfflineSetupActivity extends Activity {
 TextView info;
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,20);setContentView(root);
  TextView title=new TextView(this);title.setText("小七 · 离线文字服务");title.setTextSize(25);root.addView(title);info=new TextView(this);info.setTextSize(18);root.addView(info);
  Button start=new Button(this);start.setText("启动局域网文字服务");root.addView(start);start.setOnClickListener(v->{startForegroundService(new Intent(this,OfflineAssistantService.class));refresh();});
  Button face=new Button(this);face.setText("显示小七面孔");root.addView(face);face.setOnClickListener(v->startActivity(new Intent(this,OfflineFaceActivity.class)));
  Button stop=new Button(this);stop.setText("停止文字服务");root.addView(stop);stop.setOnClickListener(v->{stopService(new Intent(this,OfflineAssistantService.class));refresh();});
  startForegroundService(new Intent(this,OfflineAssistantService.class));refresh();
 }
 void refresh(){info.setText("手机连接同一局域网后，在“小七文字助手”中聊天。\n通用回复在本机模型生成；计划与设备动作在本机执行。\n不启用麦克风，不播报回复。\n"+(OfflineAssistantService.current==null?"服务正在启动或已停止":"服务已启动，端口8777"));}
}
