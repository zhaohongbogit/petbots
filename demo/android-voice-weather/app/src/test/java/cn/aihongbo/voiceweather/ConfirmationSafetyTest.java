package cn.aihongbo.voiceweather;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ConfirmationSafetyTest {
    @Test public void completeAffirmativeNeedsModelAgreement() {
        assertEquals(SemanticDecision.Intent.YES,ConfirmationSafety.check("是 的",SemanticDecision.Intent.YES));
        assertEquals(SemanticDecision.Intent.OTHER,ConfirmationSafety.check("是 的",SemanticDecision.Intent.NO));
    }
    @Test public void fragmentCannotDeclineOrApproveWeather() {
        assertEquals(SemanticDecision.Intent.OTHER,ConfirmationSafety.check("需要",SemanticDecision.Intent.NO));
        assertEquals(SemanticDecision.Intent.OTHER,ConfirmationSafety.check("需要",SemanticDecision.Intent.YES));
    }
    @Test public void explicitNegativeNeedsModelAgreement() {
        assertEquals(SemanticDecision.Intent.NO,ConfirmationSafety.check("不 用",SemanticDecision.Intent.NO));
        assertEquals(SemanticDecision.Intent.OTHER,ConfirmationSafety.check("不 用",SemanticDecision.Intent.YES));
    }
    @Test public void unrelatedSpeechRemainsUnclear() {
        assertEquals(SemanticDecision.Intent.OTHER,ConfirmationSafety.check("北京的天气",SemanticDecision.Intent.YES));
        assertEquals(SemanticDecision.Intent.OTHER,ConfirmationSafety.check("北京的天气",SemanticDecision.Intent.OTHER));
    }
}
