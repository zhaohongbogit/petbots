package cn.aihongbo.voiceweather;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Offline unconstrained dictation. Returned Vosk text is never rewritten or alias-matched. */
public final class VoskAsr implements AutoCloseable {
    private Model model;
    private Recognizer recognizer;
    private boolean closing;
    // [unk] admits other speech. Similar-name grammar entries can steal noisy true wake audio.
    // Confidence, both syllables, timing, and independent negative tests guard acceptance.
    private static final String WAKE_GRAMMAR="[\"阿 洛\",\"[unk]\"]";
    private final java.util.Set<Stream> streams=new java.util.HashSet<>();
    public void load(Context context) throws IOException {
        if(model!=null)return;
        File directory=new File(context.getFilesDir(),"vosk-small-cn-0.22");
        String version;
        try(InputStream input=context.getAssets().open("model-cn/uuid")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);version=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
        File marker=new File(directory,"ready-version");
        if(!marker.isFile() || !new String(java.nio.file.Files.readAllBytes(marker.toPath()),StandardCharsets.UTF_8).equals(version)) {
            if(marker.exists() && !marker.delete())throw new IOException("无法更新 Vosk 模型标记");
            copy(context,"model-cn",directory);
            java.nio.file.Files.write(marker.toPath(),version.getBytes(StandardCharsets.UTF_8));
        }
        model=new Model(directory.getAbsolutePath());
        recognizer=new Recognizer(model,16000f);
    }
    private static void copy(Context context,String asset,File output)throws IOException {
        String[] children=context.getAssets().list(asset);
        if(children!=null && children.length>0) {
            if(!output.isDirectory() && !output.mkdirs())throw new IOException("无法创建 Vosk 模型目录");
            for(String child:children)copy(context,asset+"/"+child,new File(output,child));
        } else try(InputStream input=context.getAssets().open(asset);OutputStream dest=new FileOutputStream(output)) {
            byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)dest.write(buffer,0,n);
        }
    }
    public String transcribe(float[] samples)throws Exception {
        if(model==null)throw new IllegalStateException("Vosk 尚未加载");
        if(samples.length==0 || samples.length>16000*12)throw new IllegalArgumentException("本次音频须在 12 秒以内");
        StringBuilder text=new StringBuilder();
        recognizer.reset();
        try {
            // No grammar restriction, hotword list, N-best reranking or city substitutions.
            byte[] frame=new byte[6400];
            for(int offset=0;offset<samples.length;offset+=frame.length/2) {
                int count=Math.min(frame.length/2,samples.length-offset);
                for(int i=0;i<count;i++){int value=Math.max(-32768,Math.min(32767,Math.round(samples[offset+i]*32768f)));frame[2*i]=(byte)value;frame[2*i+1]=(byte)(value>>8);}
                if(recognizer.acceptWaveForm(frame,count*2))append(text,recognizer.getResult());
            }
            append(text,recognizer.getFinalResult());
        } finally {recognizer.reset();}
        return text.toString();
    }
    private static void append(StringBuilder text,String result)throws Exception {
        String part=new JSONObject(result).optString("text","");
        if(!part.isEmpty()){if(text.length()>0)text.append(' ');text.append(part);}
    }
    public static float[] readPcm(InputStream source)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
        while((count=source.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>16000*12*2)throw new IOException("样本超过 12 秒");}
        byte[] data=bytes.toByteArray();if(data.length%2!=0)throw new IOException("PCM 格式不完整");
        float[] audio=new float[data.length/2];for(int i=0;i<audio.length;i++)audio[i]=(short)((data[2*i]&255)|(data[2*i+1]<<8))/32768f;
        return audio;
    }
    /** One recognizer per continuous audio source. Call its methods on that source thread only. */
    public synchronized Stream openStream()throws IOException {
        if(model==null || closing)throw new IllegalStateException("Vosk 未就绪或已关闭");
        Recognizer dictation=new Recognizer(model,16000f);
        try {Stream stream=new Stream(dictation,new Recognizer(model,16000f,WAKE_GRAMMAR));streams.add(stream);return stream;}
        catch(IOException | RuntimeException error){dictation.close();throw error;}
    }
    public final class Stream implements StreamingTranscriber.Decoder {
        private final Recognizer nativeRecognizer;
        private final Recognizer wakeRecognizer;
        private final java.util.List<WakeEvidence.Word> wakeWords=new java.util.ArrayList<>();
        private WakeEvidence evidence=WakeEvidence.NONE;
        private boolean closed;
        private Stream(Recognizer recognizer,Recognizer wake){nativeRecognizer=recognizer;wakeRecognizer=wake;nativeRecognizer.setWords(true);wakeRecognizer.setWords(true);}
        public Update accept(short[] pcm)throws Exception {
            if(closed)throw new IllegalStateException("音频流已关闭");
            if(wakeRecognizer.acceptWaveForm(pcm,pcm.length))wakeWords.addAll(words(new JSONObject(wakeRecognizer.getResult())));
            boolean endpoint=nativeRecognizer.acceptWaveForm(pcm,pcm.length);
            JSONObject json=new JSONObject(endpoint?nativeRecognizer.getResult():nativeRecognizer.getPartialResult());
            if(endpoint)finishWake(json);
            return new Update(endpoint,json.optString(endpoint?"text":"partial",""));
        }
        public String finish()throws Exception {
            if(closed)return "";
            JSONObject result=new JSONObject(nativeRecognizer.getFinalResult());finishWake(result);
            String text=result.optString("text","");nativeRecognizer.reset();return text;
        }
        private void finishWake(JSONObject dictation)throws Exception {
            wakeWords.addAll(words(new JSONObject(wakeRecognizer.getFinalResult())));
            evidence=WakeEvidence.evaluate(words(dictation),wakeWords);
            wakeWords.clear();wakeRecognizer.reset();
        }
        @Override public WakeEvidence wakeEvidence(){return evidence;}
        @Override public void close(){if(closed)return;closed=true;nativeRecognizer.close();wakeRecognizer.close();synchronized(VoskAsr.this){streams.remove(this);releaseModelIfUnused();}}
    }
    private static java.util.List<WakeEvidence.Word> words(JSONObject result)throws Exception {
        java.util.List<WakeEvidence.Word> words=new java.util.ArrayList<>();org.json.JSONArray array=result.optJSONArray("result");
        if(array!=null)for(int i=0;i<array.length();i++){JSONObject word=array.getJSONObject(i);words.add(new WakeEvidence.Word(word.optString("word"),word.optDouble("start"),word.optDouble("end"),word.optDouble("conf",0)));}
        return words;
    }
    public static final class Update {
        public final boolean endpoint;public final String text;
        Update(boolean endpoint,String text){this.endpoint=endpoint;this.text=text;}
    }
    private void releaseModelIfUnused(){if(closing && streams.isEmpty() && model!=null){model.close();model=null;}}
    @Override public synchronized void close(){closing=true;if(recognizer!=null){recognizer.close();recognizer=null;}releaseModelIfUnused();}
}
