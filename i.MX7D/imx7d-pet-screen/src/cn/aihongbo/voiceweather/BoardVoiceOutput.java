package cn.aihongbo.voiceweather;

import android.content.Context;
import android.os.*;
import android.speech.tts.*;
import java.util.*;
import java.util.function.Consumer;

/** Offline Chinese TTS, cancellable callbacks and bounded playback. Never changes system volume. */
final class BoardVoiceOutput implements VoiceController.Output {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean closed,ready;
    private boolean playbackSucceeded;
    private int sequence;
    private Runnable after;
    BoardVoiceOutput(Context context){this.context=context.getApplicationContext();}
    public void initialize(Consumer<Boolean> callback){
        tts=new TextToSpeech(context,result->{
            if(closed)return;
            if(result==TextToSpeech.SUCCESS){Set<android.speech.tts.Voice> voices=tts.getVoices();if(voices!=null)for(android.speech.tts.Voice voice:voices)if(voice.getLocale().getLanguage().equals("zh")&&!voice.isNetworkConnectionRequired()){tts.setVoice(voice);ready=true;break;}}
            android.util.Log.i("AluoTts","initialized offline_zh="+ready);callback.accept(ready);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){android.util.Log.i("AluoTts","started id="+id);}public void onDone(String id){android.util.Log.i("AluoTts","completed id="+id);main.post(()->finish(id,true));}public void onError(String id){android.util.Log.w("AluoTts","failed id="+id);main.post(()->finish(id,false));}});
    }
    public boolean speak(String text,Runnable done){
        if(closed||!ready)return false;stop();playbackSucceeded=false;after=done;String id=Integer.toString(++sequence);
        android.util.Log.i("AluoTts","queued id="+id);
        if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,id)==TextToSpeech.ERROR){after=null;return false;}
        main.postDelayed(()->{if(id.equals(Integer.toString(sequence))&&after!=null){tts.stop();finish(id,false);}},25000);return true;
    }
    public boolean playbackSucceeded(){return playbackSucceeded;}
    private void finish(String id,boolean success){if(closed||!id.equals(Integer.toString(sequence))||after==null)return;playbackSucceeded=success;Runnable done=after;after=null;done.run();}
    public void stop(){sequence++;after=null;if(tts!=null)tts.stop();}
    public void close(){closed=true;stop();main.removeCallbacksAndMessages(null);if(tts!=null)tts.shutdown();}
}
