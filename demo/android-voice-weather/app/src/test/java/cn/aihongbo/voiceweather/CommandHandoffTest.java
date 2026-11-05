package cn.aihongbo.voiceweather;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CommandHandoffTest {
    @Test public void acknowledgementFinishesBeforeModelStartsAndDuplicateCallbackIsIgnored() {
        CommandHandoff handoff=new CommandHandoff();List<String> events=new ArrayList<>();List<Runnable> speech=new ArrayList<>();
        handoff.start(true,done -> {events.add("收到");speech.add(done);},() -> events.add("Qwen"));
        assertTrue(handoff.waiting());assertEquals(Arrays.asList("收到"),events);
        speech.get(0).run();speech.get(0).run();
        assertEquals(Arrays.asList("收到","Qwen"),events);assertFalse(handoff.waiting());
    }
    @Test public void resetDuringAcknowledgementPreventsLateModelDispatch() {
        CommandHandoff handoff=new CommandHandoff();List<Runnable> speech=new ArrayList<>();List<String> jobs=new ArrayList<>();
        handoff.start(true,speech::add,() -> jobs.add("old"));assertTrue(handoff.cancel());
        speech.get(0).run();assertTrue(jobs.isEmpty());assertFalse(handoff.waiting());
    }
    @Test public void oldSpeechCannotDispatchOrCancelNewCommand() {
        CommandHandoff handoff=new CommandHandoff();List<Runnable> speech=new ArrayList<>();List<String> jobs=new ArrayList<>();
        handoff.start(true,speech::add,() -> jobs.add("old"));handoff.cancel();
        handoff.start(true,speech::add,() -> jobs.add("new"));speech.get(0).run();
        assertTrue(handoff.waiting());assertTrue(jobs.isEmpty());speech.get(1).run();
        assertEquals(Arrays.asList("new"),jobs);
    }
    @Test public void confirmationReplyDoesNotAcknowledgeAgain() {
        CommandHandoff handoff=new CommandHandoff();List<String> events=new ArrayList<>();
        handoff.start(false,done -> fail("Confirmation must not say 收到 again"),() -> events.add("Qwen confirmation"));
        assertEquals(Arrays.asList("Qwen confirmation"),events);assertFalse(handoff.waiting());assertFalse(handoff.cancel());
    }
    @Test public void textOnlyFallbackStillDispatchesExactlyOnce() {
        CommandHandoff handoff=new CommandHandoff();int[] jobs={0};
        handoff.start(true,Runnable::run,() -> jobs[0]++);
        assertEquals(1,jobs[0]);assertFalse(handoff.waiting());
    }
}
