package cn.aihongbo.voiceweather;

import android.app.*;
import android.os.*;
import org.json.*;
import org.vosk.*;
import java.io.*;
import java.lang.reflect.Method;

/** Exact recorded human PCM replay; no microphone, TTS, network or business execution. */
public final class HumanReplayTest extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        File directory=getTargetContext().getExternalFilesDir("diagnostics"),source=new File(directory,"h340-mix.wav");
        BoardSpeech helper=new BoardSpeech(getTargetContext());Method prepare=BoardSpeech.class.getDeclaredMethod("prepareModel",File.class);prepare.setAccessible(true);File modelDir=new File(getTargetContext().getFilesDir(),"aluo-vosk-small-cn-0.22");prepare.invoke(helper,modelDir);helper.close();
        long started=SystemClock.elapsedRealtime(),samples=0,segment=0;JSONArray segments=new JSONArray();
        try(Model model=new Model(modelDir.getAbsolutePath());Recognizer recognizer=new Recognizer(model,16000);BoardSpeech.WakeDecoder keyword=new BoardSpeech.WakeDecoder(model);DataInputStream input=new DataInputStream(new FileInputStream(source))){
            recognizer.setWords(true);byte[] header=new byte[44];input.readFully(header);ByteArrayOutputStream current=new ByteArrayOutputStream();byte[] frame=new byte[2560];int n;
            while((n=input.read(frame))!=-1){current.write(frame,0,n);samples+=n/2;
                if(recognizer.acceptWaveForm(frame,n)){JSONObject raw=new JSONObject(recognizer.getResult());BoardSpeech.Wake wake=keyword.detect(raw,current.toByteArray(),segment/16000.0);raw.put("wake_detected",wake.detected).put("command",wake.command).put("sample_end",samples);segments.put(raw);android.util.Log.i("HumanReplay",raw.toString());current.reset();segment=samples;}
                if(samples-segment>=16000*20){JSONObject raw=new JSONObject(recognizer.getFinalResult());raw.put("forced",true).put("sample_end",samples);segments.put(raw);recognizer.reset();current.reset();segment=samples;}
            }
            JSONObject tail=new JSONObject(recognizer.getFinalResult());tail.put("tail",true).put("sample_end",samples);segments.put(tail);
        }
        JSONObject report=new JSONObject().put("samples",samples).put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("segments",segments);
        File reportFile=new File(directory,"human-replay.json");try(FileOutputStream output=new FileOutputStream(reportFile)){output.write(report.toString(2).getBytes("UTF-8"));}
        result.putString("stream","PASS exact human recording replay "+reportFile);finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL "+failure);android.util.Log.e("HumanReplay","failure",failure);finish(Activity.RESULT_CANCELED,result);}}
}
