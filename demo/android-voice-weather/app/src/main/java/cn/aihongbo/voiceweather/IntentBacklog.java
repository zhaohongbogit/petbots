package cn.aihongbo.voiceweather;

import java.util.ArrayDeque;
import java.util.function.Predicate;

/** Main-thread-owned bounded text queue. Confirmation jobs precede ordinary utterances. */
final class IntentBacklog<T> {
    private final int capacity;
    private final ArrayDeque<T> ordinary=new ArrayDeque<>(),confirmation=new ArrayDeque<>();
    IntentBacklog(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;}
    /** Returns a dropped task, or null. Raw transcripts are stored independently of this queue. */
    T offer(T task,boolean urgent){
        T dropped=null;
        if(size()>=capacity){if(ordinary.isEmpty())return task;dropped=ordinary.removeFirst();}
        (urgent?confirmation:ordinary).addLast(task);return dropped;
    }
    T poll(){return confirmation.isEmpty()?ordinary.pollFirst():confirmation.pollFirst();}
    int size(){return ordinary.size()+confirmation.size();}
    boolean any(Predicate<T> predicate){for(T t:confirmation)if(predicate.test(t))return true;for(T t:ordinary)if(predicate.test(t))return true;return false;}
    int clear(){int count=size();ordinary.clear();confirmation.clear();return count;}
}
