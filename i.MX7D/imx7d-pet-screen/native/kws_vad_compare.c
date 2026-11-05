/* Exact local-WAV keyword benchmark. No microphone, playback, or network. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
int main(int argc,char **argv){
 if(argc!=4)return 2;setbuf(stdout,NULL);
 const char *epoch="13";
 char encoder[1024],decoder[1024],joiner[1024],tokens[1024];
 snprintf(encoder,sizeof(encoder),"%s/encoder-epoch-%s-avg-2-chunk-16-left-64.int8.onnx",argv[1],epoch);
 snprintf(decoder,sizeof(decoder),"%s/decoder-epoch-%s-avg-2-chunk-16-left-64.onnx",argv[1],epoch);
 snprintf(joiner,sizeof(joiner),"%s/joiner-epoch-%s-avg-2-chunk-16-left-64.int8.onnx",argv[1],epoch);
 snprintf(tokens,sizeof(tokens),"%s/tokens.txt",argv[1]);
 const char *keywords="ā l uò @阿洛\n";
 SherpaOnnxKeywordSpotterConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;
 c.model_config.transducer.encoder=encoder;c.model_config.transducer.decoder=decoder;c.model_config.transducer.joiner=joiner;c.model_config.tokens=tokens;c.model_config.num_threads=1;c.model_config.provider="cpu";
 c.max_active_paths=4;c.num_trailing_blanks=1;c.keywords_score=1;c.keywords_threshold=.25;c.keywords_buf=keywords;c.keywords_buf_size=strlen(keywords);
 printf("{\"keywords_score\":%.1f,\"keywords_threshold\":%.2f}\n",c.keywords_score,c.keywords_threshold);
 double start=now();const SherpaOnnxKeywordSpotter *k=SherpaOnnxCreateKeywordSpotter(&c);if(!k)return 3;
 double loaded=now();printf("{\"load_s\":%.3f}\n",loaded-start);
 const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[2]);if(!w || w->sample_rate!=16000)return 4;
 SherpaOnnxVadModelConfig vc={0};vc.silero_vad.model=argv[3];vc.silero_vad.threshold=.25f;vc.silero_vad.min_silence_duration=.5f;vc.silero_vad.min_speech_duration=.1f;vc.silero_vad.max_speech_duration=12;vc.silero_vad.window_size=512;vc.sample_rate=16000;vc.num_threads=1;vc.provider="cpu";
 const SherpaOnnxVoiceActivityDetector *v=SherpaOnnxCreateVoiceActivityDetector(&vc,30);if(!v)return 6;
 int hits=0,segments=0;double worst=0;
 for(int fed=0;fed<w->num_samples;){int n=w->num_samples-fed;if(n>512)n=512;SherpaOnnxVoiceActivityDetectorAcceptWaveform(v,w->samples+fed,n);fed+=n;
  while(!SherpaOnnxVoiceActivityDetectorEmpty(v)){
   const SherpaOnnxSpeechSegment *segment=SherpaOnnxVoiceActivityDetectorFront(v);if(!segment)return 7;segments++;
   const SherpaOnnxOnlineStream *stream=SherpaOnnxCreateKeywordStream(k);if(!stream)return 5;
   int first=segment->start-4800;if(first<0)first=0;int end=segment->start+segment->n;if(end>w->num_samples)end=w->num_samples;
   double tick=now();int found=0;
   for(int from=first;from<end && !found;){int count=end-from;if(count>1280)count=1280;SherpaOnnxOnlineStreamAcceptWaveform(stream,16000,w->samples+from,count);from+=count;
    while(SherpaOnnxIsKeywordStreamReady(k,stream)){
     SherpaOnnxDecodeKeywordStream(k,stream);const SherpaOnnxKeywordResult *r=SherpaOnnxGetKeywordResult(k,stream);
     if(r && r->keyword && r->keyword[0]){found=1;printf("{\"speech_start\":%d,\"through_sample\":%d,\"result\":%s}\n",first,fed,r->json);}if(r)SherpaOnnxDestroyKeywordResult(r);
    }
   }
   float padding[8000]={0};SherpaOnnxOnlineStreamAcceptWaveform(stream,16000,padding,8000);
   while(!found && SherpaOnnxIsKeywordStreamReady(k,stream)){
    SherpaOnnxDecodeKeywordStream(k,stream);const SherpaOnnxKeywordResult *r=SherpaOnnxGetKeywordResult(k,stream);
    if(r && r->keyword && r->keyword[0]){found=1;printf("{\"speech_start\":%d,\"through_sample\":%d,\"result\":%s}\n",first,fed,r->json);}if(r)SherpaOnnxDestroyKeywordResult(r);
   }
   if(found)hits++;double elapsed=now()-tick;if(elapsed>worst)worst=elapsed;
   printf("{\"segment_start\":%d,\"segment_samples\":%d,\"detected\":%d}\n",first,end-first,found);
   SherpaOnnxDestroyOnlineStream(stream);SherpaOnnxDestroySpeechSegment(segment);SherpaOnnxVoiceActivityDetectorPop(v);
  }
 }
 printf("{\"speech_segments\":%d}\n",segments);SherpaOnnxDestroyVoiceActivityDetector(v);
 struct rusage usage;getrusage(RUSAGE_SELF,&usage);
 printf("{\"samples\":%d,\"hits\":%d,\"decode_s\":%.3f,\"worst_frame_s\":%.3f,\"peak_rss_kib\":%ld}\n",w->num_samples,hits,now()-loaded,worst,usage.ru_maxrss);
 SherpaOnnxDestroyKeywordSpotter(k);SherpaOnnxFreeWave(w);return 0;
}
