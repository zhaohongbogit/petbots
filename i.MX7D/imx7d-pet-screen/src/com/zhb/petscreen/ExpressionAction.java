package com.zhb.petscreen;
import java.util.*;

/** Bounded device capability, independent of Android and the model. */
final class ExpressionAction {
    static final List<String> NAMES=Arrays.asList("neutral","happy","angry","sad","joyful","worried","surprised");
    final String name; final double intensity; final int durationMs;
    ExpressionAction(String name,double intensity,int durationMs){
        if(!NAMES.contains(name)||!Double.isFinite(intensity)||intensity<0||intensity>1||durationMs<1000||durationMs>30000)throw new IllegalArgumentException("invalid_action");
        this.name=name;this.intensity=intensity;this.durationMs=durationMs;
    }
    static boolean isSelfReport(String text){
        String t=text.replaceAll("\\s", "");
        return t.matches("^(我(今天|现在)?|今天我)(很|非常|有点|特别|真的|好)?(生气|伤心|难过|开心|忧愁|担心).*")
            && !t.matches(".*(表情|表现|表演|演一下|样子|笑一个|哭一个|你也|做个|做一个).*");
    }
    /** Only whole, explicit commands are handled locally. Everything else goes to the model. */
    static List<ExpressionAction> resolve(String input,String previous,double strength){
        String t=input.replaceAll("[\\s，。！？!?,、·]","");
        if(t.matches("(小七)?(请)?(恢复平静|恢复正常|停止表演|停止表情|保持平静)"))return Collections.singletonList(new ExpressionAction("neutral",.5,8000));
        if(t.matches("(请)?再(生气|愤怒)一点"))return Collections.singletonList(new ExpressionAction("angry",Math.min(1,(previous.equals("angry")?strength:.5)+.25),12000));
        if(t.matches("(请)?再(夸张|强烈)一点")&&!previous.equals("neutral"))return Collections.singletonList(new ExpressionAction(previous,Math.min(1,strength+.25),12000));
        if(t.matches("(请)?先哭(再|然后)笑"))return Arrays.asList(new ExpressionAction("sad",.7,4000),new ExpressionAction("happy",.7,8000));
        if(t.matches("(请)?(不要|别)生气笑一个"))return Collections.singletonList(new ExpressionAction("happy",.7,12000));
        String prefix="(小七)?(请)?(你)?(给我)?(请)?";
        String command="(表现(出|得|出来)?|展示|做(一)?个|露出|变得|装作|来(一)?个)?";
        String degree="(很|非常|特别|超级|有点|稍微)?";
        String suffix="(的)?(样子|表情)?(一下|一个)?(给我看|看看)?(吧)?";
        String[][] words={{"angry","生气|愤怒|气鼓鼓|怒"},{"happy","微笑|开心|喜悦|高兴|笑|喜"},{"sad","悲伤|伤心|难过|哭|哭泣|哀"},{"joyful","大笑|开怀大笑|乐"},{"worried","忧愁|担心|发愁|愁"},{"surprised","惊讶|吃惊"},{"neutral","平静"}};
        // Require an imperative cue; a bare emotion could be the user's own feeling.
        boolean cue=t.matches(".*(表现|展示|做个|做一个|露出|变得|装作|来个|来一个|一下|一个|给我看).*");
        if(!cue)return null;
        for(String[] item:words)if(t.matches(prefix+command+degree+"("+item[1]+")"+suffix)){
            double value=t.matches(".*(很|非常|特别|超级).*")?.95:t.matches(".*(有点|稍微).*")?.35:.65;
            return Collections.singletonList(new ExpressionAction(item[0],value,12000));
        }
        return null;
    }
}
