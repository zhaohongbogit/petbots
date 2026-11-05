package cn.aihongbo.voiceweather;
import android.app.*;
import android.os.*;
import java.io.*;
import org.json.*;
/** Signed maintenance instrumentation, not an exported production Activity/Intent endpoint. Stores endpoint only. */
public final class HbcProvisionTest extends Instrumentation {
    String base;
    @Override public void onCreate(Bundle args){super.onCreate(args);base=args.getString("baseUrl","");start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        String value=HbcAgentClient.validateBase(base);if(value.isEmpty())throw new IllegalArgumentException("baseUrl required");
        File file=new File(getTargetContext().getFilesDir(),"hbc-agent.json");
        try(FileOutputStream stream=new FileOutputStream(file)){stream.write(new JSONObject().put("baseUrl",value).toString().getBytes("UTF-8"));}
        out.putString("stream","PASS: private endpoint provisioned. No credentials or business request.");finish(Activity.RESULT_OK,out);
    }catch(Exception e){out.putString("stream","FAIL: "+e.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,out);}}
}
