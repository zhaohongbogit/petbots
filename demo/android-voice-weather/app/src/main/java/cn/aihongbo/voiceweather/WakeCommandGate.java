package cn.aihongbo.voiceweather;

/** Main-thread entry gate only; Qwen still decides the meaning of a command. */
final class WakeCommandGate {
    static final long WINDOW_MS = 8000;
    enum Kind { IGNORE, WAKE_ONLY, COMMAND }
    static final class Result {
        final Kind kind;
        final String text;
        Result(Kind kind, String text) {this.kind=kind;this.text=text;}
    }
    private long openedAt = -1;

    void openWindow(long now) {openedAt=now;}
    void reset() {openedAt=-1;}

    Result accept(String raw, long received, boolean confirming) {
        return accept(raw,received,confirming,WakeEvidence.NONE);
    }
    Result accept(String raw, long received, boolean confirming,WakeEvidence acoustic) {
        StringBuilder compact=new StringBuilder();
        for(int i=0;i<raw.length();i++) {
            char c=raw.charAt(i);
            if(!Character.isWhitespace(c) && !Character.isSpaceChar(c))compact.append(c);
        }
        String text=trimEdges(compact.toString());
        boolean wake=text.startsWith("阿洛");
        String command=wake?trimEdges(text.substring(2)):text;
        if(acoustic.available && !confirming) {
            wake=acoustic.detected;
            command=wake?acoustic.command:text;
        } else if(acoustic.detected)command=acoustic.command;
        if(command.isEmpty() && (confirming || !wake))return new Result(Kind.IGNORE,"");
        if(confirming)return new Result(Kind.COMMAND,command);
        if(wake) {
            reset();
            return new Result(command.isEmpty()?Kind.WAKE_ONLY:Kind.COMMAND,command);
        }
        if(openedAt>=0 && received>=openedAt && received-openedAt<WINDOW_MS) {
            reset(); // A standalone wake authorizes one following sentence only.
            return new Result(Kind.COMMAND,command);
        }
        return new Result(Kind.IGNORE,"");
    }

    private static String trimEdges(String text) {
        int first=0,last=text.length();
        while(first<last && punctuation(text.charAt(first)))first++;
        while(last>first && punctuation(text.charAt(last-1)))last--;
        return text.substring(first,last);
    }
    private static boolean punctuation(char c) {
        switch(Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION: case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION: return true;
            default: return false;
        }
    }
}
