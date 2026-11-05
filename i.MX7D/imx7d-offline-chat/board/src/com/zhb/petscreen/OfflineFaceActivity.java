package com.zhb.petscreen;
import android.os.*;
import android.content.*;
import android.view.View;
/** Reuse the current pet face and allowlisted local expressions, with all voice paths disabled. */
public final class OfflineFaceActivity extends ChatActivity {
 @Override public void onCreate(Bundle b){getSharedPreferences("pet-chat",MODE_PRIVATE).edit().putBoolean("auto_speak",false).apply();super.onCreate(b);mic.setVisibility(View.GONE);if(speech!=null)speech.close();}
 @Override void showDeviceSettings(){startActivity(new Intent(this,OfflineSetupActivity.class));}
 @Override void speakReply(String reply){}
 @Override void toggleVoiceInput(){mood.setText("当前为纯文字离线模式");}
 @Override void showDebug(boolean visible){super.showDebug(false);}
 @Override boolean begin(String text,String id,String image){if(image!=null || ExpressionAction.resolve(text,expression,expressionIntensity)==null)return false;return super.begin(text,id,image);}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);applyTask();}
 @Override protected void onResume(){super.onResume();applyTask();}
 private void applyTask(){if(!foreground)return;String id=getIntent().getStringExtra("local_job"),text=getIntent().getStringExtra("local_text");if(id==null||text==null)return;getIntent().removeExtra("local_job");getIntent().removeExtra("local_text");OfflineAssistantService s=OfflineAssistantService.current;if(s==null||!s.canApply(id))return;boolean ok=begin(text,id,null);s.finishAction(id,ok,ok?"已在设备上启动指定表情动作。":"设备没有执行该动作。");}
}
