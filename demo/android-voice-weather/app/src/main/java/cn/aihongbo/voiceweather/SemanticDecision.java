package cn.aihongbo.voiceweather;
/** Structured model output; contains no transcript matching or correction rules. */
public final class SemanticDecision {
    public enum Intent { WEATHER, YES, NO, OTHER, UNSUPPORTED, AMBIGUOUS }
    public final Intent intent;
    public final String city;
    public final int day;
    public SemanticDecision(Intent intent,String city,int day) {this.intent=intent;this.city=city;this.day=day;}
}
