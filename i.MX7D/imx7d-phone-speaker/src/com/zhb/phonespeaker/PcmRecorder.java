package com.zhb.phonespeaker;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import java.io.*;

/** Short 16 kHz mono WAV recorder used as the phone's temporary remote microphone. */
final class PcmRecorder {
    interface Listener { void finished(boolean success,long durationMs,int peak,String error); }
    static final int RATE=16000;
    final File output;final int maxMs;volatile AudioRecord audio;volatile boolean stopping;Thread worker;
    PcmRecorder(File output,int maxMs){this.output=output;this.maxMs=maxMs;}
    void start(Listener listener)throws IOException{
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IOException("unsupported_audio_format");
        AudioRecord record=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(8192,min*2));
        if(record.getState()!=AudioRecord.STATE_INITIALIZED){record.release();throw new IOException("audio_record_init_failed");}
        audio=record;worker=new Thread(()->capture(record,Math.max(4096,min),listener),"phone-pet-recorder");worker.start();
    }
    void stop(){stopping=true;AudioRecord r=audio;if(r!=null)try{r.stop();}catch(Exception ignored){}}
    void capture(AudioRecord record,int size,Listener listener){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);long samples=0;int peak=0;String error="";boolean ok=false;output.delete();
        try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
            out.write(new byte[44]);byte[] b=new byte[size-size%2];long target=RATE*(long)maxMs/1000;record.startRecording();
            while(!stopping&&samples<target){int n=record.read(b,0,(int)Math.min(b.length,(target-samples)*2));if(n<0)throw new IOException("audio_read_"+n);n-=n%2;if(n==0)continue;out.write(b,0,n);for(int i=0;i<n;i+=2)peak=Math.max(peak,Math.abs((short)((b[i]&255)|(b[i+1]<<8))));samples+=n/2;}
            out.flush();ok=samples>=RATE/2;
        }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
        finally{try{record.stop();}catch(Exception ignored){}record.release();audio=null;if(ok)try{header(output,samples);}catch(Exception e){ok=false;error="wav_header";}if(!ok)output.delete();listener.finished(ok,samples*1000/RATE,peak,error);}
    }
    static void header(File f,long samples)throws IOException{try(RandomAccessFile w=new RandomAccessFile(f,"rw")){long bytes=samples*2;w.writeBytes("RIFF");le32(w,36+bytes);w.writeBytes("WAVEfmt ");le32(w,16);le16(w,1);le16(w,1);le32(w,RATE);le32(w,RATE*2);le16(w,2);le16(w,16);w.writeBytes("data");le32(w,bytes);}}
    static void le16(RandomAccessFile w,long v)throws IOException{w.write((int)v&255);w.write((int)(v>>8)&255);}
    static void le32(RandomAccessFile w,long v)throws IOException{le16(w,v);le16(w,v>>16);}
}
