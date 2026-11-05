package cn.aihongbo.voiceweather;
import android.app.*;
import android.os.*;
import java.io.*;
import java.net.*;
import org.json.*;
/** Configured test-service probe. Creates only an unauthenticated challenge, never sends phone/code/approval. */
public final class HbcConnectivityTest extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();HbcAgentClient client=null;try{
        File file=new File(getTargetContext().getFilesDir(),"hbc-agent.json");byte[] bytes=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(bytes);}
        String base=HbcAgentClient.validateBase(new JSONObject(new String(bytes,"UTF-8")).getString("baseUrl"));
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/health").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);
        try{if(c.getResponseCode()!=200)throw new IOException("health status");ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(data.size()+n>16384)throw new IOException("health size");data.write(b,0,n);}}
            JSONObject health=new JSONObject(new String(data.toByteArray(),"UTF-8"));if(!health.optString("gateway").equals("hbc")||!health.optString("status").equals("ok")||!health.getJSONObject("configuration").optBoolean("hbcCapp"))throw new IOException("health configuration");
        }finally{c.disconnect();}
        client=HbcAgentClient.from(getTargetContext());JSONObject r=client.post("/api/chat",new JSONObject().put("message","我想了解东京三日包车，先不要发送短信、创建订单或支付"),null);
        if(!r.optString("status").equals("authentication_required"))throw new IOException("auth challenge");
        out.putString("stream","PASS: board-to-Mac health and customer POST JSON authentication challenge. No phone/captcha/SMS/order/payment/approval sent.");finish(Activity.RESULT_OK,out);
    }catch(Exception e){out.putString("stream","FAIL: "+e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,out);}finally{if(client!=null)client.close();}}
}
