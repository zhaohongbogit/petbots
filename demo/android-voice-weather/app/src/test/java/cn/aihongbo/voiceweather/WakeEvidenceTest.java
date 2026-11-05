package cn.aihongbo.voiceweather;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WakeEvidenceTest {
 private WakeEvidence.Word w(String text,double start,double end,double conf){return new WakeEvidence.Word(text,start,end,conf);}
 private List<WakeEvidence.Word> prefix(){return Arrays.asList(w("阿",0.03,0.12,1),w("洛",0.12,0.48,1),w("[unk]",0.6,2,1));}
 @Test public void independentKeywordUsesTimingWithoutReplacingDictation() {
  List<WakeEvidence.Word> raw=Arrays.asList(w("落",0.03,0.48,.8),w("上海",0.6,1,1),w("明天",1,1.3,1));
  WakeEvidence result=WakeEvidence.evaluate(raw,prefix());
  assertTrue(result.detected);assertEquals("上海 明天",result.command);assertEquals("落",raw.get(0).text);
  assertEquals(WakeCommandGate.Kind.COMMAND,new WakeCommandGate().accept("落 上海 明天",100,false,result).kind);
 }
 @Test public void keywordOnlyCanWakeDespiteWrongDictation() {
  WakeEvidence evidence=WakeEvidence.evaluate(Arrays.asList(w("那",.03,.12,.8),w("咯",.12,.48,.8)),prefix());
  assertTrue(evidence.detected);assertEquals("",evidence.command);
  assertEquals(WakeCommandGate.Kind.WAKE_ONLY,new WakeCommandGate().accept("那 咯",100,false,evidence).kind);
 }
 @Test public void unknownOrCompetingPhraseBeforeKeywordMustNotWake() {
  assertFalse(WakeEvidence.evaluate(Collections.emptyList(),Arrays.asList(w("[unk]",0,.5,1),w("阿",.5,.6,1),w("洛",.6,1,1))).detected);
  assertFalse(WakeEvidence.evaluate(Collections.emptyList(),Arrays.asList(w("安",0,.2,1),w("诺",.2,.5,1))).detected);
 }
 @Test public void lowConfidenceIncompleteAndImplausibleDurationAreRejected() {
  assertFalse(WakeEvidence.evaluate(Collections.emptyList(),Arrays.asList(w("阿",0,.12,.84),w("洛",.12,.48,1))).detected);
  assertFalse(WakeEvidence.evaluate(Collections.emptyList(),Arrays.asList(w("阿",0,.12,1))).detected);
  assertFalse(WakeEvidence.evaluate(Collections.emptyList(),Arrays.asList(w("阿",0,.12,1),w("洛",1.4,1.5,1))).detected);
 }
 @Test public void nameMentionedAfterEarlierDictationIsRejected() {
  assertFalse(WakeEvidence.evaluate(Arrays.asList(w("我们",0,.5,1)),Arrays.asList(w("阿",.7,.8,1),w("洛",.8,1,1))).detected);
 }
 @Test public void laterSentenceUsesSourceAudioOffsetAndKeepsFirstCommandWord() {
  WakeEvidence evidence=WakeEvidence.evaluate(Arrays.asList(w("落",10.03,10.48,.8),w("上海",10.55,10.9,1)),Arrays.asList(w("阿",10.03,10.12,1),w("洛",10.12,10.48,1)));
  assertTrue(evidence.detected);assertEquals("上海",evidence.command);
 }
 @Test public void acousticRejectionDoesNotFallBackToCorrectedTextButConfirmationStillWorks() {
  WakeCommandGate gate=new WakeCommandGate();
  assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("阿洛查天气",100,false,WakeEvidence.REJECTED).kind);
  assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("不用",101,true,WakeEvidence.REJECTED).kind);
  assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("阿洛查天气",102,false).kind);
 }
}
