import android.media.*;
import android.os.*;
import java.io.*;
public final class AluoWavPlayer {
 static int le(DataInputStream in)throws IOException {return Integer.reverseBytes(in.readInt());}
 public static void main(String[] args)throws Exception {
  Looper.prepareMainLooper();Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
  try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
   if(input.readInt()!=0x52494646)throw new IOException("not RIFF");le(input);if(input.readInt()!=0x57415645)throw new IOException("not WAVE");
   int bytes=0;boolean format=false;
   while(true){int tag=input.readInt(),size=le(input);if(size<0)throw new IOException("chunk size");
    if(tag==0x666d7420){byte[] f=new byte[size];input.readFully(f);format=size>=16 && f[0]==1 && f[2]==1 && (f[4]&255)==128 && (f[5]&255)==62 && f[14]==16;}
    else if(tag==0x64617461){bytes=size;break;}else {int left=size;while(left>0){int n=input.skipBytes(left);if(n<=0)throw new EOFException();left-=n;}}
    if((size&1)!=0)input.readByte();
   }
   if(!format || bytes<=0 || bytes>32000*120)throw new IOException("expected PCM16 mono 16kHz, max 120 seconds");
   int buffer=Math.max(16384,AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)*4);
   AudioTrack track=new AudioTrack(AudioManager.STREAM_MUSIC,16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer,AudioTrack.MODE_STREAM);
   try {
    if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("AudioTrack not initialized");
    byte[] block=new byte[8192];int sent=0;long deadline=SystemClock.elapsedRealtime()+bytes/32+15000;
    int first=Math.min(block.length,bytes);input.readFully(block,0,first);int initial=track.write(block,0,first,AudioTrack.WRITE_BLOCKING);if(initial!=first)throw new IOException("initial write="+initial);sent=initial;
    track.play();System.out.println("PLAYBACK_STARTED duration_ms="+bytes/32+" stream=MUSIC volume_unchanged=true");
    while(sent<bytes){if(SystemClock.elapsedRealtime()>deadline)throw new IOException("playback timeout");int n=Math.min(block.length,bytes-sent);input.readFully(block,0,n);int off=0;while(off<n){int wrote=track.write(block,off,n-off,AudioTrack.WRITE_BLOCKING);if(wrote<=0)throw new IOException("write="+wrote);off+=wrote;sent+=wrote;}}
    while((track.getPlaybackHeadPosition()&0xffffffffL)<bytes/2 && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
    long played=track.getPlaybackHeadPosition()&0xffffffffL;
    System.out.println("PLAYBACK_ENDED frames_played="+played+" expected="+bytes/2);
    if(played<bytes/2)throw new IOException("playback incomplete");
   }finally {track.stop();track.release();}
  }
  System.exit(0);
 }
}
