package cn.aihongbo.voiceweather;
import org.junit.Test;
import static org.junit.Assert.*;
public class IntentBacklogTest {
 @Test public void confirmationPrecedesOrdinaryWithoutReorderingConfirmations(){
  IntentBacklog<String> queue=new IntentBacklog<>(4);queue.offer("chat",false);queue.offer("no",true);queue.offer("yes",true);
  assertEquals("no",queue.poll());assertEquals("yes",queue.poll());assertEquals("chat",queue.poll());assertNull(queue.poll());
 }
 @Test public void backlogDropsOldOrdinaryTaskAtCapacity(){
  IntentBacklog<String> queue=new IntentBacklog<>(2);queue.offer("old",false);queue.offer("new",false);assertEquals("old",queue.offer("reply",true));assertEquals(2,queue.size());assertEquals("reply",queue.poll());assertEquals("new",queue.poll());
 }
 @Test public void incomingOrdinaryNeverEvictsConfirmation(){
  IntentBacklog<String> queue=new IntentBacklog<>(1);queue.offer("no",true);assertEquals("chat",queue.offer("chat",false));assertEquals("no",queue.poll());
 }
 @Test public void priorityDoesNotReverseReceivedAnswerOrderWhenFull(){
  IntentBacklog<String> queue=new IntentBacklog<>(1);queue.offer("first-no",true);assertEquals("later-yes",queue.offer("later-yes",true));assertEquals("first-no",queue.poll());
 }
 @Test public void clearingTurnRemovesPendingConfirmation(){
  IntentBacklog<String> queue=new IntentBacklog<>(3);queue.offer("reply",true);queue.offer("chat",false);assertTrue(queue.any("reply"::equals));assertEquals(2,queue.clear());assertFalse(queue.any(t -> true));assertNull(queue.poll());
 }
}
