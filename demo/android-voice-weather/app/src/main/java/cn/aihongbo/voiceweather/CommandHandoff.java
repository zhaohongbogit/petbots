package cn.aihongbo.voiceweather;

import java.util.function.Consumer;

/** Main-thread acknowledgement barrier. Late or duplicate speech callbacks cannot dispatch work. */
final class CommandHandoff {
    private long sequence;
    private boolean waiting;

    void start(boolean acknowledge, Consumer<Runnable> respond, Runnable dispatch) {
        long token=++sequence;
        waiting=acknowledge;
        if(!acknowledge) {dispatch.run();return;}
        respond.accept(() -> {
            if(token!=sequence || !waiting)return;
            waiting=false;
            dispatch.run();
        });
    }

    boolean waiting() {return waiting;}
    boolean cancel() {
        boolean pending=waiting;
        sequence++;waiting=false;
        return pending;
    }
}
