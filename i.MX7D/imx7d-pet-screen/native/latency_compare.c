/* Read-only same-PCM thread-count comparison, no microphone/TTS/network. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
int main(int argc,char **argv){
 if(argc<4)return 2;setbuf(stdout,NULL);int threads=atoi(argv[2]);if(threads<1 || threads>2)return 2;
 char model[1024],tokens[1024];snprintf(model,sizeof(model),"%s/model.int8.onnx",argv[1]);snprintf(tokens,sizeof(tokens),"%s/tokens.txt",argv[1]);
 SherpaOnnxOfflineRecognizerConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;c.model_config.paraformer.model=model;c.model_config.tokens=tokens;c.model_config.num_threads=threads;c.model_config.provider="cpu";c.decoding_method="greedy_search";
 double start=now();const SherpaOnnxOfflineRecognizer *r=SherpaOnnxCreateOfflineRecognizer(&c);if(!r)return 3;printf("{\"threads\":%d,\"load_s\":%.3f}\n",threads,now()-start);
 for(int round=0;round<3;round++)for(int file=3;file<argc;file++){
  const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[file]);if(!w || w->sample_rate!=16000)return 4;
  const SherpaOnnxOfflineStream *s=SherpaOnnxCreateOfflineStream(r);if(!s)return 5;double tick=now();
  SherpaOnnxAcceptWaveformOffline(s,16000,w->samples,w->num_samples);SherpaOnnxDecodeOfflineStream(r,s);
  const char *json=SherpaOnnxGetOfflineStreamResultAsJson(s);if(!json)return 6;
  printf("{\"threads\":%d,\"round\":%d,\"case\":%d,\"samples\":%d,\"asr_ms\":%.1f,\"result\":%s}\n",threads,round,file-3,w->num_samples,(now()-tick)*1000,json);
  SherpaOnnxDestroyOfflineStreamResultJson(json);SherpaOnnxDestroyOfflineStream(s);SherpaOnnxFreeWave(w);
 }
 SherpaOnnxDestroyOfflineRecognizer(r);return 0;
}
