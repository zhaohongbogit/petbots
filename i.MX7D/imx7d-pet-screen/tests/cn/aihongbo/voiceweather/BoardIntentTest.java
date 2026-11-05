package cn.aihongbo.voiceweather;
import java.io.*;
public final class BoardIntentTest {
    static int count;
    static void check(boolean ok){count++;if(!ok)throw new AssertionError("case "+count);}
    public static void main(String[] args)throws Exception{
        CityCatalog cities=new CityCatalog(new InputStreamReader(new FileInputStream(args[0]),"UTF-8"));
        for(String t:new String[]{"上海明天天气怎么样","阿洛，上海 明天 的 天气 怎么样","帮我查一下上海明天天气"}){SemanticDecision d=BoardIntent.local(t,cities);check(d.intent==SemanticDecision.Intent.WEATHER);check(d.city.equals("上海"));check(d.day==1);}
        for(String t:new String[]{"取消查询上海天气","不要查上海天气","他说上海天气怎么样","上海后天天气怎么样","上海下周天气","巴黎明天天气怎么样","明天呢","讲个故事"})check(BoardIntent.local(t,cities).intent!=SemanticDecision.Intent.WEATHER);
        check(BoardIntent.local("上海北京天气怎么样",cities).intent==SemanticDecision.Intent.AMBIGUOUS);
        check(BoardIntent.local("上海今天明天天气怎么样",cities).intent==SemanticDecision.Intent.AMBIGUOUS);
        for(String t:new String[]{"是的","是 的","好的","可以","阿洛，帮我查"})check(BoardIntent.consent(t).intent==SemanticDecision.Intent.YES);
        for(String t:new String[]{"不用","不是的","不要查","取消"})check(BoardIntent.consent(t).intent==SemanticDecision.Intent.NO);
        for(String t:new String[]{"需要","好","要","不是的但可以","不要帮我查","是的不用","是的查询北京"})check(BoardIntent.consent(t).intent==SemanticDecision.Intent.OTHER);
        DialogueEngine engine=new DialogueEngine();
        check(engine.accept(BoardIntent.local("上海明天天气怎么样",cities),100)==DialogueEngine.Action.ASK);
        check(engine.selectTarget("上海","2026-10-30",100));engine.promptFinished(1000);
        check(!engine.acceptsConfirmationAt(999));check(engine.acceptsConfirmationAt(8999));check(!engine.acceptsConfirmationAt(9000));
        check(engine.accept(BoardIntent.consent("需要"),2000)==DialogueEngine.Action.NONE);
        check(engine.accept(BoardIntent.consent("是的"),3000)==DialogueEngine.Action.QUERY);
        engine.weatherReady();engine.finish(4000);
        check(engine.accept(BoardIntent.local("上海明天天气怎么样",cities),5000)==DialogueEngine.Action.ASK);
        check(!engine.selectTarget("上海","2026-10-30",5000));
        System.out.println("PASS "+count+" assertions");
    }
}
