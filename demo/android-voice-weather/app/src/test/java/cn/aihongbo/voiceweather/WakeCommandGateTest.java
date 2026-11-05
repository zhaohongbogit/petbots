package cn.aihongbo.voiceweather;

import org.junit.Test;
import static org.junit.Assert.*;

public class WakeCommandGateTest {
    @Test public void ordinarySpeechAndBareConsentDoNotReachQwenWhileIdle() {
        WakeCommandGate gate=new WakeCommandGate();
        for(String text:new String[]{"上海明天天气怎么样","是的","不用","我们提到了阿洛","阿落，查天气"})
            assertEquals(text,WakeCommandGate.Kind.IGNORE,gate.accept(text,100,false).kind);
    }
    @Test public void wakePrefixIsRemovedWithoutChangingTheSourceTranscript() {
        WakeCommandGate gate=new WakeCommandGate();String raw="阿 洛 ， 上海 明天 天气 怎么样？";
        WakeCommandGate.Result result=gate.accept(raw,100,false);
        assertEquals(WakeCommandGate.Kind.COMMAND,result.kind);assertEquals("上海明天天气怎么样",result.text);
        assertEquals("阿 洛 ， 上海 明天 天气 怎么样？",raw);
    }
    @Test public void wakeCanBeAdjacentToCommandAndHasNoWeatherKeywordFilter() {
        WakeCommandGate.Result result=new WakeCommandGate().accept("阿洛打开车灯",100,false);
        assertEquals(WakeCommandGate.Kind.COMMAND,result.kind);assertEquals("打开车灯",result.text);
    }
    @Test public void standaloneWakeDoesNotOpenWindowUntilAcknowledgementFinishes() {
        WakeCommandGate gate=new WakeCommandGate();
        assertEquals(WakeCommandGate.Kind.WAKE_ONLY,gate.accept("阿洛！",100,false).kind);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("查上海天气",200,false).kind);
        gate.openWindow(1000);
        assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("查上海天气",1500,false).kind);
    }
    @Test public void windowAuthorizesOnlyOneSentence() {
        WakeCommandGate gate=new WakeCommandGate();gate.openWindow(1000);
        assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("上海天气",1001,false).kind);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("北京天气",1002,false).kind);
    }
    @Test public void windowUsesAudioReceiptTimeWithExclusiveEightSecondDeadline() {
        WakeCommandGate gate=new WakeCommandGate();gate.openWindow(1000);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("天气",999,false).kind);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("天气",9000,false).kind);
        assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("天气",8999,false).kind);
    }
    @Test public void resetAndFreshPrefixedCommandClearStandaloneWakePermission() {
        WakeCommandGate gate=new WakeCommandGate();gate.openWindow(1000);gate.reset();
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("天气",1001,false).kind);
        gate.openWindow(2000);assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("阿洛天气",2001,false).kind);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("再查天气",2002,false).kind);
    }
    @Test public void confirmationRepliesBypassWakeButPreserveNegation() {
        WakeCommandGate gate=new WakeCommandGate();
        assertEquals("不是的",gate.accept("不 是 的",100,true).text);
        assertEquals("不用",gate.accept("阿洛，不用",101,true).text);
        assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("是的",102,true).kind);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept("阿洛",103,true).kind);
    }
    @Test public void whitespaceAndPunctuationCannotConsumeAnOpenWindow() {
        WakeCommandGate gate=new WakeCommandGate();gate.openWindow(1000);
        assertEquals(WakeCommandGate.Kind.IGNORE,gate.accept(" \t，！　",1001,false).kind);
        assertEquals(WakeCommandGate.Kind.COMMAND,gate.accept("上海天气",1002,false).kind);
    }
}
