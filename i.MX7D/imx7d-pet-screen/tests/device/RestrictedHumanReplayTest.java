package cn.aihongbo.voiceweather;
import android.app.*;import android.os.*;import org.json.*;import org.vosk.*;import java.io.*;
/** Compare pre-existing six-city weather vocabulary against the exact recording, never execute its hypotheses. */
public final class RestrictedHumanReplayTest extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 static String grammar(){JSONArray phrases=new JSONArray();for(String city:new String[]{"郑州","北京","上海","广州","深圳","杭州"})for(String day:new String[]{"今天","明天","后天"}){phrases.put(city+" "+day+" 的 天气 怎么样");phrases.put(city+" "+day+" 会 下雨 吗");phrases.put(city+" "+day+" 的 温度 是 多少");}return phrases.put("阿 洛").put("是 的").put("好 的").put("可 以").put("不 用").put("不 需要").put("取 消").put("[unk]").toString();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  File directory=getTargetContext().getExternalFilesDir("diagnostics"),source=new File(directory,"h340-mix.wav"),modelDir=new File(getTargetContext().getFilesDir(),"aluo-vosk-small-cn-0.22");long start=SystemClock.elapsedRealtime(),samples=0;JSONArray rows=new JSONArray();
  try(Model model=new Model(modelDir.getAbsolutePath());Recognizer recognizer=new Recognizer(model,16000,grammar());DataInputStream input=new DataInputStream(new FileInputStream(source))){recognizer.setWords(true);byte[] header=new byte[44];input.readFully(header);byte[] frame=new byte[2560];int n;while((n=input.read(frame))!=-1){samples+=n/2;if(recognizer.acceptWaveForm(frame,n)){JSONObject row=new JSONObject(recognizer.getResult());rows.put(row);android.util.Log.i("RestrictedReplay",row.toString());}}rows.put(new JSONObject(recognizer.getFinalResult()));}
  JSONObject report=new JSONObject().put("profile","restricted_weather_not_general_dictation").put("samples",samples).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("segments",rows);try(FileOutputStream out=new FileOutputStream(new File(directory,"restricted-replay.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
  result.putString("stream","PASS restricted replay report saved; accuracy requires comparison to spoken phrases");finish(Activity.RESULT_OK,result);
 }catch(Throwable failure){result.putString("stream","FAIL "+failure);finish(Activity.RESULT_CANCELED,result);}}
}
