/* Local streaming ASR comparison; every source sample is consumed, no microphone/network. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
static void result(const SherpaOnnxOnlineRecognizer *r,const SherpaOnnxOnlineStream *s,int fed){
 const char *json=SherpaOnnxGetOnlineStreamResultAsJson(r,s);if(json){printf("{\"through_sample\":%d,\"result\":%s}\n",fed,json);SherpaOnnxDestroyOnlineStreamResultJson(json);}
}
int main(int argc,char **argv){
 if(argc!=5){fprintf(stderr,"Usage: %s ctc|14m MODEL_DIR INPUT.wav THREADS\n",argv[0]);return 2;}setbuf(stdout,NULL);
 int threads=atoi(argv[4]);if(threads<1 || threads>2)return 2;
 char encoder[1024],decoder[1024],joiner[1024],tokens[1024];snprintf(tokens,sizeof(tokens),"%s/tokens.txt",argv[2]);
 SherpaOnnxOnlineRecognizerConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;
 if(!strcmp(argv[1],"ctc")){snprintf(encoder,sizeof(encoder),"%s/model.int8.onnx",argv[2]);c.model_config.zipformer2_ctc.model=encoder;}
 else if(!strcmp(argv[1],"14m")){
  snprintf(encoder,sizeof(encoder),"%s/encoder-epoch-99-avg-1.int8.onnx",argv[2]);snprintf(decoder,sizeof(decoder),"%s/decoder-epoch-99-avg-1.onnx",argv[2]);snprintf(joiner,sizeof(joiner),"%s/joiner-epoch-99-avg-1.int8.onnx",argv[2]);
  c.model_config.transducer.encoder=encoder;c.model_config.transducer.decoder=decoder;c.model_config.transducer.joiner=joiner;
 }else return 2;
 c.model_config.tokens=tokens;c.model_config.num_threads=threads;c.model_config.provider="cpu";c.decoding_method="greedy_search";c.enable_endpoint=1;c.rule1_min_trailing_silence=2.4f;c.rule2_min_trailing_silence=1.2f;c.rule3_min_utterance_length=20;
 double start=now();const SherpaOnnxOnlineRecognizer *r=SherpaOnnxCreateOnlineRecognizer(&c);if(!r)return 3;double loaded=now();printf("{\"engine\":\"%s\",\"threads\":%d,\"load_s\":%.3f}\n",argv[1],threads,loaded-start);
 const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[3]);if(!w || w->sample_rate!=16000)return 4;
 const SherpaOnnxOnlineStream *s=SherpaOnnxCreateOnlineStream(r);if(!s)return 5;int fed=0;double worst=0;
 while(fed<w->num_samples){int n=w->num_samples-fed;if(n>1280)n=1280;double tick=now();SherpaOnnxOnlineStreamAcceptWaveform(s,16000,w->samples+fed,n);fed+=n;
  while(SherpaOnnxIsOnlineStreamReady(r,s))SherpaOnnxDecodeOnlineStream(r,s);
  if(SherpaOnnxOnlineStreamIsEndpoint(r,s)){result(r,s,fed);SherpaOnnxOnlineStreamReset(r,s);}double elapsed=now()-tick;if(elapsed>worst)worst=elapsed;
 }
 float pad[12800]={0};SherpaOnnxOnlineStreamAcceptWaveform(s,16000,pad,12800);SherpaOnnxOnlineStreamInputFinished(s);while(SherpaOnnxIsOnlineStreamReady(r,s))SherpaOnnxDecodeOnlineStream(r,s);result(r,s,fed);
 struct rusage u;getrusage(RUSAGE_SELF,&u);printf("{\"completed\":true,\"samples\":%d,\"flush_padding_samples\":12800,\"decode_s\":%.3f,\"worst_frame_s\":%.3f,\"peak_rss_kib\":%ld}\n",fed,now()-loaded,worst,u.ru_maxrss);
 SherpaOnnxDestroyOnlineStream(s);SherpaOnnxDestroyOnlineRecognizer(r);SherpaOnnxFreeWave(w);return 0;
}
