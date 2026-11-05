/* Exact local-WAV keyword benchmark. No microphone, playback, or network. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
int main(int argc,char **argv){
 if(argc<3 || argc>5)return 2;setbuf(stdout,NULL);
 const char *epoch=argc>=4 && !strcmp(argv[3],"zh")?"12":"13";
 char encoder[1024],decoder[1024],joiner[1024],tokens[1024];
 snprintf(encoder,sizeof(encoder),"%s/encoder-epoch-%s-avg-2-chunk-16-left-64.int8.onnx",argv[1],epoch);
 snprintf(decoder,sizeof(decoder),"%s/decoder-epoch-%s-avg-2-chunk-16-left-64.onnx",argv[1],epoch);
 snprintf(joiner,sizeof(joiner),"%s/joiner-epoch-%s-avg-2-chunk-16-left-64.int8.onnx",argv[1],epoch);
 snprintf(tokens,sizeof(tokens),"%s/tokens.txt",argv[1]);
 const char *keywords="ā l uò @阿洛\n";
 SherpaOnnxKeywordSpotterConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;
 c.model_config.transducer.encoder=encoder;c.model_config.transducer.decoder=decoder;c.model_config.transducer.joiner=joiner;c.model_config.tokens=tokens;c.model_config.num_threads=1;c.model_config.provider="cpu";
 c.max_active_paths=4;c.num_trailing_blanks=1;c.keywords_score=argc==5?atof(argv[4]):1;if(c.keywords_score<1 || c.keywords_score>3)return 2;c.keywords_threshold=.25;c.keywords_buf=keywords;c.keywords_buf_size=strlen(keywords);
 printf("{\"keywords_score\":%.1f,\"keywords_threshold\":%.2f}\n",c.keywords_score,c.keywords_threshold);
 double start=now();const SherpaOnnxKeywordSpotter *k=SherpaOnnxCreateKeywordSpotter(&c);if(!k)return 3;
 double loaded=now();printf("{\"load_s\":%.3f}\n",loaded-start);
 const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[2]);if(!w || w->sample_rate!=16000)return 4;
 const SherpaOnnxOnlineStream *s=SherpaOnnxCreateKeywordStream(k);if(!s)return 5;
 int hits=0;double worst=0;
 for(int fed=0;fed<w->num_samples;){int n=w->num_samples-fed;if(n>1280)n=1280;double tick=now();SherpaOnnxOnlineStreamAcceptWaveform(s,16000,w->samples+fed,n);fed+=n;
  while(SherpaOnnxIsKeywordStreamReady(k,s)){
   SherpaOnnxDecodeKeywordStream(k,s);const SherpaOnnxKeywordResult *r=SherpaOnnxGetKeywordResult(k,s);
   if(r && r->keyword && r->keyword[0]){hits++;printf("{\"through_sample\":%d,\"result\":%s}\n",fed,r->json);SherpaOnnxResetKeywordStream(k,s);}if(r)SherpaOnnxDestroyKeywordResult(r);
  }double elapsed=now()-tick;if(elapsed>worst)worst=elapsed;
 }
 struct rusage usage;getrusage(RUSAGE_SELF,&usage);
 printf("{\"samples\":%d,\"hits\":%d,\"decode_s\":%.3f,\"worst_frame_s\":%.3f,\"peak_rss_kib\":%ld}\n",w->num_samples,hits,now()-loaded,worst,usage.ru_maxrss);
 SherpaOnnxDestroyOnlineStream(s);SherpaOnnxDestroyKeywordSpotter(k);SherpaOnnxFreeWave(w);return 0;
}
