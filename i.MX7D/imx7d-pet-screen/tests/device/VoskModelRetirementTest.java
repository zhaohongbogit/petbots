package cn.aihongbo.voiceweather;
import android.app.*;import android.os.*;import org.json.*;import java.io.*;import java.security.*;import java.util.*;
/** Inventory by default. Model-only retirement is explicitly invoked after user approval. */
public final class VoskModelRetirementTest extends Instrumentation {
    boolean remove;
    @Override public void onCreate(Bundle args){super.onCreate(args);remove="yes".equals(args.getString("approved-remove"));start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        File root=new File(getTargetContext().getFilesDir(),"aluo-vosk-small-cn-0.22");JSONObject inventory=new JSONObject();long bytes=walk(root,root,inventory);
        File report=new File(getTargetContext().getExternalFilesDir("diagnostics"),"vosk-model-inventory.json");try(FileOutputStream out=new FileOutputStream(report)){out.write(new JSONObject().put("bytes",bytes).put("files",inventory).toString(2).getBytes("UTF-8"));}
        if(remove)delete(root);
        result.putString("stream","PASS "+(remove?"retired approved Vosk model only":"read-only Vosk inventory")+" bytes="+bytes);finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL "+failure);finish(Activity.RESULT_CANCELED,result);}}
    long walk(File root,File file,JSONObject inventory)throws Exception{
        if(java.nio.file.Files.isSymbolicLink(file.toPath()))throw new IOException("Unexpected model symlink");
        if(file.isDirectory()){long bytes=0;File[] children=file.listFiles();if(children==null)throw new IOException("Cannot list model");for(File child:children)bytes+=walk(root,child,inventory);return bytes;}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
        inventory.put(root.toPath().relativize(file.toPath()).toString(),hash.toString());return file.length();
    }
    void delete(File file)throws IOException{if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot list model");for(File child:children)delete(child);}if(!file.delete())throw new IOException("Cannot retire model file");}
}
