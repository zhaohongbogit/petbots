package cn.aihongbo.voiceweather.board;

import org.json.JSONArray;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Board-only bounded vocabulary. This is ASR, never authorization to query weather. */
final class DecoderProfile {
    static String weatherGrammar() {
        JSONArray phrases=new JSONArray();
        for(String city:new String[]{"郑州","北京","上海","广州","深圳","杭州"}) {
            for(String day:new String[]{"今天","明天","后天"}) {
                phrases.put(city+" "+day+" 的 天气 怎么样");
                phrases.put(city+" "+day+" 会 下雨 吗");
                phrases.put(city+" "+day+" 的 温度 是 多少");
            }
        }
        phrases.put("查 一下 天气").put("阿 洛").put("收到").put("取消").put("[unk]");
        return phrases.toString();
    }
    static Recognizer create(Model model,boolean weather)throws java.io.IOException {
        Recognizer recognizer=weather?new Recognizer(model,16000f,weatherGrammar()):new Recognizer(model,16000f);
        recognizer.setWords(false);
        return recognizer;
    }
}
