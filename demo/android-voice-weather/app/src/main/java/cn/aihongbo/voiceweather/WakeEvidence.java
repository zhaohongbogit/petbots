package cn.aihongbo.voiceweather;

import java.util.List;

/** Separate acoustic evidence; never a corrected dictation transcript. */
final class WakeEvidence {
    static final WakeEvidence NONE=new WakeEvidence(false,false,"",0);
    static final WakeEvidence REJECTED=new WakeEvidence(true,false,"",0);
    final boolean available,detected;
    final String command;
    final double confidence;
    private WakeEvidence(boolean available,boolean detected,String command,double confidence) {
        this.available=available;this.detected=detected;this.command=command;this.confidence=confidence;
    }
    static final class Word {
        final String text;final double start,end,confidence;
        Word(String text,double start,double end,double confidence){this.text=text;this.start=start;this.end=end;this.confidence=confidence;}
    }
    static WakeEvidence evaluate(List<Word> dictation,List<Word> keyword) {
        if(keyword.size()<2)return REJECTED;
        Word first=keyword.get(0),second=keyword.get(1);
        double duration=second.end-first.start,confidence=Math.min(first.confidence,second.confidence);
        if(!first.text.equals("阿") || !second.text.equals("洛") || confidence<0.85
                || duration<0.12 || duration>1.2 || second.start-first.end>0.2)return REJECTED;
        // Both Vosk recognizers retain source-absolute timestamps across result/reset boundaries.
        double start=first.start,end=second.end;
        // Reject a name mentioned after earlier speech, even if the keyword decoder skipped it.
        if(!dictation.isEmpty() && dictation.get(0).start+0.15<start)return REJECTED;
        StringBuilder suffix=new StringBuilder();
        for(Word word:dictation)if(word.end>end+0.04) {
            if(suffix.length()>0)suffix.append(' ');
            suffix.append(word.text);
        }
        return new WakeEvidence(true,true,suffix.toString(),confidence);
    }
}
