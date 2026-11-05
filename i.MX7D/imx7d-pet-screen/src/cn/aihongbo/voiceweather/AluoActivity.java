package cn.aihongbo.voiceweather;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Preserve old internal links; the pet face now owns the voice experience. */
public final class AluoActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);Intent intent=new Intent(this,com.zhb.petscreen.ChatActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);finish();}
}
