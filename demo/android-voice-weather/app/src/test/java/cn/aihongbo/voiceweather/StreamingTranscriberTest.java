package cn.aihongbo.voiceweather;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class StreamingTranscriberTest {
 static class Decoder implements StreamingTranscriber.Decoder {
  final ArrayDeque<VoskAsr.Update> updates=new ArrayDeque<>();String tail="";int closes,finishes;
  Decoder update(boolean endpoint,String text){updates.add(new VoskAsr.Update(endpoint,text));return this;}
  public VoskAsr.Update accept(short[] pcm){return updates.removeFirst();}
  public String finish(){finishes++;String text=tail;tail="";return text;}
  public void close(){closes++;}
 }
 static class Events implements StreamingTranscriber.Listener {
  final List<String> partials=new ArrayList<>(),finals=new ArrayList<>();final List<Boolean> playback=new ArrayList<>();long received;int starts;boolean forced;
  public void started(long id,long at,boolean echo){starts++;}
  public void partial(long id,String text,long at,long ms){partials.add(text);}
  public void completed(long id,String text,long at,long ms,boolean echo,boolean forced){finals.add(text);playback.add(echo);received=at;this.forced=forced;}
 }
 @Test public void partialsReviseDisplayButOnlyEndpointCompletes()throws Exception {
  Decoder decoder=new Decoder().update(false,"上").update(false,"上海").update(false,"上海").update(true,"上海 明天 天气");Events events=new Events();
  try(StreamingTranscriber t=new StreamingTranscriber(decoder,events)){t.accept(new short[]{1000},100,false);t.accept(new short[]{1000},180,false);t.accept(new short[]{1000},260,false);assertTrue(events.finals.isEmpty());assertEquals(Arrays.asList("上","上海"),events.partials);t.accept(new short[]{0},1000,false);assertEquals(Collections.singletonList("上海 明天 天气"),events.finals);assertEquals(260,events.received);}
 }
 @Test public void quietRecognizedSpeechIsNotFilteredByEnergy()throws Exception {
  Decoder decoder=new Decoder().update(false,"天气").update(true,"天气怎么样");Events events=new Events();
  try(StreamingTranscriber t=new StreamingTranscriber(decoder,events)){t.accept(new short[]{0},100,false);assertEquals(1,events.starts);t.accept(new short[]{0},900,false);assertEquals(Collections.singletonList("天气怎么样"),events.finals);}
 }
 @Test public void playbackOverlapEndsAtBoundaryAndResetsForNext()throws Exception {
  Decoder decoder=new Decoder().update(false,"天气").update(false,"天气").update(true,"天气").update(false,"不用").update(true,"不用");Events events=new Events();
  try(StreamingTranscriber t=new StreamingTranscriber(decoder,events)){t.accept(new short[]{1000},100,false);t.accept(new short[]{1000},180,true);t.accept(new short[]{0},1000,false);t.accept(new short[]{1000},1500,false);t.accept(new short[]{0},2300,false);assertEquals(Arrays.asList(true,false,false),events.playback);}
 }
 @Test public void playbackBoundaryStartsFreshConfirmationSegment()throws Exception {
  Decoder decoder=new Decoder().update(false,"需").update(true,"是 的");decoder.tail="需要";Events events=new Events();
  try(StreamingTranscriber t=new StreamingTranscriber(decoder,events)){
   t.accept(new short[]{1000},100,true);
   t.accept(new short[]{1000},800,false);
   assertEquals(Arrays.asList("需要","是 的"),events.finals);
   assertEquals(Arrays.asList(true,false),events.playback);
   assertEquals(2,events.starts);
   assertEquals(1,decoder.finishes);
  }
 }
 @Test public void repeatedIdenticalSentencesAreBothRecordedAndEofDoesNotDuplicate()throws Exception {
  Decoder decoder=new Decoder().update(true,"是的").update(true,"是的");Events events=new Events();
  try(StreamingTranscriber t=new StreamingTranscriber(decoder,events)){t.accept(new short[]{1000},100,false);t.accept(new short[]{1000},500,false);t.finish(700);assertEquals(Arrays.asList("是的","是的"),events.finals);}
 }
 @Test public void continuousSpeechHasBoundedSentenceLength()throws Exception {
  Decoder decoder=new Decoder().update(false,"天气").update(false,"天气如何");decoder.tail="天气如何";Events events=new Events();
  try(StreamingTranscriber t=new StreamingTranscriber(decoder,events)){t.accept(new short[]{1000},100,false);t.accept(new short[]{1000},20100,false);assertTrue(events.forced);assertEquals(Collections.singletonList("天气如何"),events.finals);}
 }
 @Test public void discardReturnsTailWithoutSemanticFinalCallback()throws Exception {
  Decoder decoder=new Decoder().update(false,"未说完");decoder.tail="未说完";Events events=new Events();
  StreamingTranscriber t=new StreamingTranscriber(decoder,events);t.accept(new short[]{1000},100,false);assertEquals("未说完",t.discard());assertTrue(events.finals.isEmpty());t.close();t.close();assertEquals(1,decoder.closes);
 }
}
