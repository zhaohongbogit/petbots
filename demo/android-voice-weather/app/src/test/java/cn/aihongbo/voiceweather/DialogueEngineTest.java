package cn.aihongbo.voiceweather;
import org.junit.Test;
import static org.junit.Assert.*;
public class DialogueEngineTest {
    private static SemanticDecision decision(SemanticDecision.Intent intent){return new SemanticDecision(intent,"郑州",1);}
    private DialogueEngine waiting(){DialogueEngine engine=new DialogueEngine();assertEquals(DialogueEngine.Action.ASK,engine.accept(decision(SemanticDecision.Intent.WEATHER),0));assertTrue(engine.selectTarget("郑州","2026-10-23",0));engine.promptFinished(1000);return engine;}
    @Test public void onlyModelYesInsideWindowQueries(){DialogueEngine e=waiting();assertEquals(DialogueEngine.Action.QUERY,e.accept(decision(SemanticDecision.Intent.YES),2000));assertEquals(DialogueEngine.State.FETCHING,e.state());}
    @Test public void noDeclines(){DialogueEngine e=waiting();assertEquals(DialogueEngine.Action.DECLINED,e.accept(decision(SemanticDecision.Intent.NO),2000));assertEquals(DialogueEngine.State.LISTENING,e.state());}
    @Test public void unrelatedDoesNotConsent(){DialogueEngine e=waiting();assertEquals(DialogueEngine.Action.NONE,e.accept(decision(SemanticDecision.Intent.WEATHER),2000));assertEquals(DialogueEngine.Action.NONE,e.accept(decision(SemanticDecision.Intent.OTHER),3000));assertEquals(DialogueEngine.State.CONFIRMING,e.state());}
    @Test public void delayedConfirmationTimesOut(){assertEquals(DialogueEngine.Action.TIMED_OUT,waiting().accept(decision(SemanticDecision.Intent.YES),9000));}
    @Test public void promptingNeverAcceptsConsent(){DialogueEngine e=new DialogueEngine();e.accept(decision(SemanticDecision.Intent.WEATHER),0);assertEquals(DialogueEngine.Action.NONE,e.accept(decision(SemanticDecision.Intent.YES),100));}
    @Test public void unsupportedTaskGetsFeedbackWithoutPromptOrCooldown(){for(SemanticDecision.Intent i:new SemanticDecision.Intent[]{SemanticDecision.Intent.NO,SemanticDecision.Intent.YES,SemanticDecision.Intent.OTHER,SemanticDecision.Intent.AMBIGUOUS,SemanticDecision.Intent.UNSUPPORTED}){DialogueEngine e=new DialogueEngine();assertEquals(DialogueEngine.Action.NOT_SUPPORTED,e.accept(decision(i),0));assertEquals(DialogueEngine.State.SPEAKING,e.state());e.finish(100);assertEquals(DialogueEngine.Action.ASK,e.accept(decision(SemanticDecision.Intent.WEATHER),200));assertTrue(e.selectTarget("郑州","2026-10-23",200));}}
    @Test public void unsupportedModelDateGetsFeedbackWithoutQuery(){DialogueEngine e=new DialogueEngine();assertEquals(DialogueEngine.Action.NOT_SUPPORTED,e.accept(new SemanticDecision(SemanticDecision.Intent.WEATHER,"郑州",-1),0));assertEquals(DialogueEngine.State.SPEAKING,e.state());assertTrue(e.reason(0).contains("今天或明天"));}
    @Test public void unknownCityGetsFeedbackBeforeConfirmation(){DialogueEngine e=new DialogueEngine();assertEquals(DialogueEngine.Action.ASK,e.accept(decision(SemanticDecision.Intent.WEATHER),0));assertEquals(DialogueEngine.Action.NOT_SUPPORTED,e.rejectPrompt("无效城市"));assertEquals(DialogueEngine.State.SPEAKING,e.state());assertFalse(e.acceptsConfirmationAt(100));}
    @Test public void cooldownIsScopedToResolvedTarget(){DialogueEngine e=waiting();e.accept(decision(SemanticDecision.Intent.NO),2000);e.accept(decision(SemanticDecision.Intent.WEATHER),3000);assertFalse(e.selectTarget("郑州","2026-10-23",3000));e.accept(decision(SemanticDecision.Intent.WEATHER),3001);assertTrue(e.selectTarget("上海","2026-10-23",3001));}
    @Test public void otherDateIsImmediatelyEligible(){DialogueEngine e=waiting();e.accept(decision(SemanticDecision.Intent.NO),2000);e.accept(decision(SemanticDecision.Intent.WEATHER),3000);assertTrue(e.selectTarget("郑州","2026-10-22",3000));}
    @Test public void resetInvalidatesPendingConfirmation(){DialogueEngine e=waiting();e.reset();assertFalse(e.acceptsConfirmationAt(2000));assertEquals(DialogueEngine.Action.NOT_SUPPORTED,e.accept(decision(SemanticDecision.Intent.YES),2000));}
    @Test public void cooldownExpires(){DialogueEngine e=waiting();e.accept(decision(SemanticDecision.Intent.NO),2000);e.accept(decision(SemanticDecision.Intent.WEATHER),32000);assertTrue(e.selectTarget("郑州","2026-10-23",32000));}
    @org.junit.Test public void confirmationReceiptMustBelongToTheActualWindow(){
        DialogueEngine engine=new DialogueEngine();org.junit.Assert.assertFalse(engine.acceptsConfirmationAt(0));
        engine.accept(new SemanticDecision(SemanticDecision.Intent.WEATHER,"上海",1),100);engine.selectTarget("上海","2026-10-23",100);engine.promptFinished(200);
        org.junit.Assert.assertFalse(engine.acceptsConfirmationAt(199));org.junit.Assert.assertTrue(engine.acceptsConfirmationAt(200));org.junit.Assert.assertTrue(engine.acceptsConfirmationAt(8199));org.junit.Assert.assertFalse(engine.acceptsConfirmationAt(8200));
    }
}
