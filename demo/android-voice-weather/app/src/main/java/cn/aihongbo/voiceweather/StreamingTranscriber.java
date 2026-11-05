package cn.aihongbo.voiceweather;

/** Source-thread-owned native stream. Partials never become semantic tasks or durable transcripts. */
final class StreamingTranscriber implements AutoCloseable {
    interface Decoder extends AutoCloseable {VoskAsr.Update accept(short[] pcm)throws Exception;String finish()throws Exception;default WakeEvidence wakeEvidence(){return WakeEvidence.NONE;}void close();}
    interface Listener {
        void started(long id,long received,boolean playback);
        void partial(long id,String text,long received,long decodeMs);
        void completed(long id,String text,long received,long decodeMs,boolean playback,boolean forced);
        default void completed(long id,String text,long received,long decodeMs,boolean playback,boolean forced,WakeEvidence wake){completed(id,text,received,decodeMs,playback,forced);}
    }
    private final Decoder stream;
    private final Listener listener;
    private final SentenceWavRecorder recording;
    private long id=1,startedAt,lastVoiceAt,lastPlaybackAt=Long.MIN_VALUE/2,decodeNanos;
    private boolean active,overlapsPlayback,wasPlayback,closed;
    private String lastPartial="";
    private static final long MAX_SENTENCE_MS=20000;
    StreamingTranscriber(Decoder stream,Listener listener){this(stream,listener,null);}
    StreamingTranscriber(Decoder stream,Listener listener,SentenceWavRecorder recording){this.stream=stream;this.listener=listener;this.recording=recording;}
    void accept(short[] pcm,long received,boolean playback)throws Exception {
        if(closed)throw new IllegalStateException("音频流已关闭");
        if(wasPlayback && !playback) {
            // The next answer needs its own Vosk segment and confirmation ticket.
            // Any segment containing speaker audio remains ineligible, even if it has trailing speech.
            long before=System.nanoTime();String tail=stream.finish();decodeNanos+=System.nanoTime()-before;
            if(active || !tail.isEmpty())complete(tail,lastVoiceAt==0?received:lastVoiceAt,false);
        }
        wasPlayback=playback;
        if(recording!=null)recording.accept(pcm);
        if(playback)lastPlaybackAt=received;
        double energy=0;for(short value:pcm)energy+=(double)value*value;
        boolean voiced=pcm.length>0 && Math.sqrt(energy/pcm.length)>=350;
        if(voiced){if(!active)start(received,playback);lastVoiceAt=received;}
        if(active)overlapsPlayback |= received-lastPlaybackAt<=200;
        long before=System.nanoTime();VoskAsr.Update update=stream.accept(pcm);long elapsed=System.nanoTime()-before;
        if(!active && !update.text.isEmpty())start(received,playback);
        if(active)decodeNanos+=elapsed;
        if(update.endpoint){complete(update.text,lastVoiceAt==0?received:lastVoiceAt,false);return;}
        if(!update.text.equals(lastPartial)){
            lastPartial=update.text;listener.partial(id,update.text,received,decodeNanos/1000000);
        }
        if(active && received-startedAt>=MAX_SENTENCE_MS){before=System.nanoTime();String text=stream.finish();decodeNanos+=System.nanoTime()-before;complete(text,lastVoiceAt==0?received:lastVoiceAt,true);}
    }
    private void start(long received,boolean playback){active=true;decodeNanos=0;startedAt=lastVoiceAt=received;overlapsPlayback=playback || received-lastPlaybackAt<=200;if(recording!=null)recording.start(id);listener.started(id,received,overlapsPlayback);}
    private void complete(String text,long received,boolean forced){
        if(recording!=null)recording.complete();
        if(active || !text.isEmpty())listener.completed(id,text,received,decodeNanos/1000000,overlapsPlayback,forced,stream.wakeEvidence());
        id++;active=false;overlapsPlayback=false;startedAt=lastVoiceAt=decodeNanos=0;lastPartial="";
    }
    /** Finite sample EOF is a final result; live stop/reset uses discard() so it cannot execute actions. */
    void finish(long received)throws Exception {long before=System.nanoTime();String text=stream.finish();decodeNanos+=System.nanoTime()-before;complete(text,lastVoiceAt==0?received:lastVoiceAt,false);}
    String discard()throws Exception {return stream.finish();}
    @Override public void close(){if(closed)return;closed=true;stream.close();}
}
