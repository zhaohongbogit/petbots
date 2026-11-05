package cn.aihongbo.voiceweather;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SentenceWavRecorderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<File> saved = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private SentenceWavRecorder recorder(File directory) {
        return new SentenceWavRecorder(directory, saved::add, errors::add, Runnable::run);
    }
    private byte[] read(File file) throws Exception {return Files.readAllBytes(file.toPath());}
    private short[] samples(File file) throws Exception {
        byte[] bytes=read(file);short[] result=new short[(bytes.length-44)/2];
        ByteBuffer.wrap(bytes,44,bytes.length-44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(result);
        return result;
    }

    @Test public void validWavHeaderAndSignedLittleEndianSamples() throws Exception {
        File directory=new File(temporary.getRoot(),"recordings");
        try(SentenceWavRecorder r=recorder(directory)) {
            r.accept(new short[]{-32768,32767});r.start(7);r.accept(new short[]{-1,0});r.complete();
        }
        assertEquals(1,saved.size());assertTrue(errors.isEmpty());
        byte[] bytes=read(saved.get(0));ByteBuffer header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF",new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WAVEfmt ",new String(bytes,8,8,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(44,header.getInt(4));assertEquals(16,header.getInt(16));
        assertEquals(1,header.getShort(20));assertEquals(1,header.getShort(22));
        assertEquals(16000,header.getInt(24));assertEquals(32000,header.getInt(28));
        assertEquals(2,header.getShort(32));assertEquals(16,header.getShort(34));
        assertEquals("data",new String(bytes,36,4,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(8,header.getInt(40));
        assertArrayEquals(new short[]{-32768,32767,-1,0},samples(saved.get(0)));
        assertArrayEquals(new String[]{saved.get(0).getName()},directory.list());
    }

    @Test public void preRollKeepsOnlyLastHalfSecondInOrder() throws Exception {
        try(SentenceWavRecorder r=recorder(temporary.getRoot())) {
            short[] input=new short[9000];for(int i=0;i<input.length;i++)input[i]=(short)i;
            r.accept(input);r.start(1);r.complete();
            assertArrayEquals(Arrays.copyOfRange(input,1000,9000),samples(saved.get(0)));
        }
    }

    @Test public void sourceBufferReuseCannotAlterQueuedAudio() throws Exception {
        List<Runnable> tasks=new ArrayList<>();
        SentenceWavRecorder r=new SentenceWavRecorder(temporary.getRoot(),saved::add,errors::add,tasks::add);
        short[] frame={1000};r.accept(frame);r.start(1);frame[0]=2000;r.accept(frame);r.complete();frame[0]=0;
        assertTrue(saved.isEmpty());tasks.get(0).run();
        assertArrayEquals(new short[]{1000,2000},samples(saved.get(0)));r.close();
    }

    @Test public void silenceDoesNotCreateFilesAndCloseSavesTailExactlyOnce() throws Exception {
        SentenceWavRecorder r=recorder(temporary.getRoot());r.accept(new short[]{0});r.close();assertTrue(saved.isEmpty());
        r=recorder(temporary.getRoot());r.accept(new short[]{12});r.start(1);r.close();r.close();
        assertEquals(1,saved.size());assertArrayEquals(new short[]{12},samples(saved.get(0)));
    }

    @Test public void endpointAndForcedBoundaryDoNotDuplicateFramesOrFiles() throws Exception {
        StreamingTranscriberTest.Decoder decoder=new StreamingTranscriberTest.Decoder()
                .update(false,"一").update(true,"一句").update(false,"二").update(false,"二句");
        decoder.tail="二句";
        try(SentenceWavRecorder r=recorder(temporary.getRoot());
            StreamingTranscriber t=new StreamingTranscriber(decoder,new StreamingTranscriberTest.Events(),r)) {
            t.accept(new short[]{1000},100,false);t.accept(new short[]{2000},200,false);
            t.accept(new short[]{3000},300,false);t.accept(new short[]{4000},20300,false);
        }
        assertEquals(2,saved.size());assertNotEquals(saved.get(0),saved.get(1));
        assertArrayEquals(new short[]{1000,2000},samples(saved.get(0)));
        assertArrayEquals(new short[]{3000,4000},samples(saved.get(1)));
    }

    @Test public void discardSavesTailWithoutCompletingSemanticTask() throws Exception {
        StreamingTranscriberTest.Decoder decoder=new StreamingTranscriberTest.Decoder().update(false,"尾句");
        decoder.tail="尾句";StreamingTranscriberTest.Events events=new StreamingTranscriberTest.Events();
        try(SentenceWavRecorder r=recorder(temporary.getRoot());StreamingTranscriber t=new StreamingTranscriber(decoder,events,r)) {
            t.accept(new short[]{1234},100,true);assertEquals("尾句",t.discard());
        }
        assertTrue(events.finals.isEmpty());assertEquals(1,saved.size());
        assertArrayEquals(new short[]{1234},samples(saved.get(0)));
    }

    @Test public void queueRejectionReportsErrorWithoutRunningFileHook() {
        try(SentenceWavRecorder r=new SentenceWavRecorder(temporary.getRoot(),saved::add,errors::add,
                task -> {throw new RejectedExecutionException();})) {
            r.accept(new short[]{1000});r.start(1);r.complete();
        }
        assertEquals(1,errors.size());assertTrue(saved.isEmpty());
    }

    @Test public void diskFailureDoesNotCallReadyHook() throws Exception {
        File blocked=temporary.newFile("blocked");
        try(SentenceWavRecorder r=recorder(blocked)) {r.accept(new short[]{1});r.start(1);r.complete();}
        assertEquals(1,errors.size());assertTrue(saved.isEmpty());
    }

    @Test public void completedFileHookRunsOnBackgroundWorker() throws Exception {
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> callbackThread=new java.util.concurrent.atomic.AtomicReference<>();
        Thread source=Thread.currentThread();
        try {
            try(SentenceWavRecorder r=new SentenceWavRecorder(temporary.getRoot(),file -> {
                assertTrue(file.isFile());assertArrayEquals(new short[]{321},samples(file));
                callbackThread.set(Thread.currentThread());ready.countDown();
            },errors::add,worker)) {r.accept(new short[]{321});r.start(1);}
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertNotSame(source,callbackThread.get());assertTrue(errors.isEmpty());
        } finally {worker.shutdownNow();}
    }
}
