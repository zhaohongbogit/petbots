package cn.aihongbo.voiceweather;
import android.content.Context;
import java.io.*;
import org.json.*;
/** Optional, explicitly provisioned test-only APK asset. Never logged or used against a different endpoint. */
final class HbcFixedLogin {
    final String phone,captcha,agentBase;
    HbcFixedLogin(String phone,String captcha,String agentBase){this.phone=phone;this.captcha=captcha;this.agentBase=agentBase;}
    static HbcFixedLogin load(Context context){
        try(InputStream stream=context.getAssets().open("hbc-test-login.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[512];int n;
            while((n=stream.read(b))!=-1){if(out.size()+n>4096)return null;out.write(b,0,n);}
            JSONObject j=new JSONObject(new String(out.toByteArray(),"UTF-8"));
            if(!j.optString("environment").equals("hbc-test")||!j.optString("countryCode").equals("86"))return null;
            String phone=j.getString("phone"),captcha=j.getString("captcha"),base=HbcAgentClient.validateBase(j.getString("agentBase"));
            if(!phone.matches("1[0-9]{10}")||!captcha.matches("[0-9]{4,8}")||base.isEmpty())return null;
            return new HbcFixedLogin(phone,captcha,base);
        }catch(Exception absent){return null;}
    }
}
