package cn.aihongbo.voiceweather;
public final class WakeUtteranceTest {
    public static void main(String[] args){
        int count=0;
        for(String text:new String[]{"你好阿洛","你好啊洛","你好阿路","你好啊路","你好，阿洛。"," 你 好 阿 洛 ","你好　阿洛"}){if(!WakeUtterance.matches(text))throw new AssertionError(text);count++;}
        for(String text:new String[]{null,"","你好","阿洛","你好阿诺","你好阿波","你好阿洛上海明天天气怎么样","他说你好阿洛","不要说你好阿洛","你好阿洛再见","上海明天天气怎么样","是的","你好阿洛你好阿洛"}){if(WakeUtterance.matches(text))throw new AssertionError(text);count++;}
        System.out.println("PASS "+count+" wake decision assertions");
    }
}
