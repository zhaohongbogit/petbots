package cn.aihongbo.voiceweather;

import java.util.HashMap;
import java.util.Map;

/** Pure state machine. Target cooldowns use a resolved city and an absolute local date. */
public final class DialogueEngine {
    public enum State { LISTENING, PROMPTING, CONFIRMING, FETCHING, SPEAKING }
    public enum Action { NONE, ASK, QUERY, DECLINED, TIMED_OUT, NOT_SUPPORTED }
    public static final long CONFIRM_MS = 8000, COOLDOWN_MS = 30000;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private State state = State.LISTENING;
    private long deadline, blockedUntil;
    private int dayOffset;
    private String targetKey, targetLabel, blockedLabel, reason = "等待讲话；普通聊天只记录";
    public State state() { return state; }
    public boolean acceptsConfirmationAt(long received){return state==State.CONFIRMING && received>=deadline-CONFIRM_MS && received<deadline;}
    public int dayOffset() { return dayOffset; }
    public String reason(long now) {
        if (blockedUntil>0) {
            long remaining=(blockedUntil-now+999)/1000;
            return remaining>0 ? blockedLabel+"刚处理过，还需等待 "+remaining+" 秒；可询问其他城市或日期" : "该目标冷却已结束，请重新说出天气询问";
        }
        return reason;
    }
    public void notice(String message) { reason=message; blockedUntil=0; }
    public Action accept(SemanticDecision decision, long now) {
        blockedUntil=0;
        if (state == State.CONFIRMING) {
            if (now >= deadline) return expire(now);
            if (decision.intent==SemanticDecision.Intent.NO) { finish(now); notice("已拒绝，不查询、不追加播报；只冷却当前城市和日期"); return Action.DECLINED; }
            if (decision.intent==SemanticDecision.Intent.YES) { state = State.FETCHING; notice("已明确同意，正在查询天气"); return Action.QUERY; }
            notice("正在等待确认，请回答“是的”或“不用”；其他内容仅记录");
            return Action.NONE;
        }
        if (state != State.LISTENING) { notice("当前正在询问或处理天气，请等待本轮结束"); return Action.NONE; }
        if((decision.intent==SemanticDecision.Intent.WEATHER || decision.intent==SemanticDecision.Intent.OTHER) && (decision.day<0 || decision.day>1)) return notSupported("目前只支持今天或明天，不执行查询");
        if (decision.intent!=SemanticDecision.Intent.WEATHER) {
            return notSupported(decision.intent==SemanticDecision.Intent.AMBIGUOUS ? "模型判断城市或日期不明确" : decision.intent==SemanticDecision.Intent.UNSUPPORTED ? "模型判断任务不支持" : "模型判断为非天气任务");
        }
        dayOffset=decision.day;
        targetKey=null; targetLabel=null; state = State.PROMPTING;
        notice("已识别天气询问，正在提取城市和日期");
        return Action.ASK;
    }
    /** Must resolve a target before asking. A different city or date is immediately eligible. */
    public boolean selectTarget(String city, String date, long now) {
        if (state!=State.PROMPTING) throw new IllegalStateException("No pending weather prompt");
        cooldowns.entrySet().removeIf(entry -> entry.getValue()<=now);
        String key=city+"|"+date;
        Long until=cooldowns.get(key);
        if (until!=null) {
            state=State.LISTENING; targetKey=null; targetLabel=null;
            blockedLabel=city+" "+date; blockedUntil=until;
            return false;
        }
        targetKey=key; targetLabel=city+" "+date;
        notice("已提取目标："+targetLabel+"，准备询问是否查询");
        return true;
    }
    private Action notSupported(String message) { state=State.SPEAKING; targetKey=null; targetLabel=null; notice(message); return Action.NOT_SUPPORTED; }
    public Action rejectPrompt(String message) {
        if (state!=State.PROMPTING) throw new IllegalStateException("No pending weather prompt");
        return notSupported(message);
    }
    public void promptFinished(long now) {
        if (state == State.PROMPTING) {
            if (targetKey==null) throw new IllegalStateException("Resolve a city and date first");
            state = State.CONFIRMING; deadline = now + CONFIRM_MS;
            notice("等待明确回答，8 秒内说“是的”或“不用”");
        }
    }
    public Action expire(long now) {
        if (state == State.CONFIRMING && now >= deadline) {
            finish(now); notice("确认超时，未查询；可询问其他城市或日期"); return Action.TIMED_OUT;
        }
        return Action.NONE;
    }
    public void weatherReady() { if (state == State.FETCHING) state = State.SPEAKING; }
    public void finish(long now) {
        if (targetKey!=null) cooldowns.put(targetKey,now+COOLDOWN_MS);
        state=State.LISTENING; notice("本轮结束；同一城市和日期冷却 30 秒，其他目标可立即询问");
        targetKey=null; targetLabel=null;
    }
    public void reset() { state=State.LISTENING; cooldowns.clear(); targetKey=null; targetLabel=null; deadline=0; notice("已重置，等待新的天气询问"); }
}
