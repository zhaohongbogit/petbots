package cn.aihongbo.voiceweather;

/** Keep a model label from turning an incomplete or contradictory reply into consent or refusal. */
final class ConfirmationSafety {
    private ConfirmationSafety() {}

    static SemanticDecision.Intent check(String transcript, SemanticDecision.Intent modelIntent) {
        if(modelIntent!=SemanticDecision.Intent.YES && modelIntent!=SemanticDecision.Intent.NO)return modelIntent;
        String text=transcript.replaceAll("\\s+", "").replaceAll("[，。！？、,.!?]", "");
        boolean negative=text.contains("不用") || text.contains("不需要") || text.contains("不要")
                || text.contains("不是") || text.contains("不查") || text.contains("拒绝")
                || text.contains("取消") || text.contains("算了") || text.equals("否");
        boolean positive=text.equals("是的") || text.equals("好的") || text.equals("可以")
                || text.equals("同意") || text.equals("对的") || text.equals("当然")
                || text.equals("好") || text.equals("要") || text.equals("查吧")
                || text.contains("帮我查") || text.contains("请查询") || text.contains("要查询");
        if(positive == negative)return SemanticDecision.Intent.OTHER;
        return modelIntent==SemanticDecision.Intent.YES && positive
                || modelIntent==SemanticDecision.Intent.NO && negative ? modelIntent : SemanticDecision.Intent.OTHER;
    }
}
