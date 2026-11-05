package cn.aihongbo.voiceweather;

/** Whole-utterance wake decision. Keeps ASR text unchanged; never matches a substring. */
final class WakeUtterance {
    static final String PHRASE="你好阿洛";
    static boolean matches(String raw){
        if(raw==null)return false;
        String text=raw.replaceAll("[\\s\\p{Z}\\p{P}]+","");
        return "你好阿洛".equals(text)||"你好啊洛".equals(text)||"你好阿路".equals(text)||"你好啊路".equals(text);
    }
}
