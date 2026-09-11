package com.zhb.petscreen;
import android.os.Handler;
import android.os.SystemClock;
import java.util.*;

/** Sequential, cancellable expressions. Empty plans leave the current face alone. */
final class ExpressionController {
    interface Listener {void apply(ExpressionAction action,String requestId,int index,String state);}
    final Handler handler;final Listener listener;
    final ArrayDeque<ExpressionAction> queue=new ArrayDeque<>();
    String requestId="";int index;long deadline;boolean paused;
    final Runnable next=()->advance();
    ExpressionController(Handler handler,Listener listener){this.handler=handler;this.listener=listener;}
    void execute(List<ExpressionAction> actions,String id){
        if(actions.isEmpty())return;
        handler.removeCallbacks(next);queue.clear();queue.addAll(actions);requestId=id;index=0;advance();
    }
    void advance(){
        handler.removeCallbacks(next);
        if(queue.isEmpty()){deadline=0;listener.apply(new ExpressionAction("neutral",.5,8000),requestId,index,"expired");return;}
        ExpressionAction a=queue.removeFirst();listener.apply(a,requestId,index++,"applied");deadline=SystemClock.uptimeMillis()+a.durationMs;
        if(!paused)handler.postDelayed(next,a.durationMs);
    }
    void pause(){paused=true;handler.removeCallbacks(next);}
    void resume(){paused=false;if(deadline>0)handler.postDelayed(next,Math.max(0,deadline-SystemClock.uptimeMillis()));}
    void stop(){handler.removeCallbacks(next);queue.clear();deadline=0;}
}
