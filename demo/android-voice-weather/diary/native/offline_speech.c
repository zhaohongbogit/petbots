// Local ARMv7 speech processing. Never opens a microphone or a network socket.
#include "vendor/sherpa-c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <math.h>
#include <sys/stat.h>
static double seconds(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
static void emit(const SherpaOnnxOnlineRecognizer *r,const SherpaOnnxOnlineStream *s,FILE *out,int fed) {
 const char *json=SherpaOnnxGetOnlineStreamResultAsJson(r,s);
 if(json){fprintf(out,"{\"through_sample\":%d,\"result\":%s}\n",fed,json);fflush(out);SherpaOnnxDestroyOnlineStreamResultJson(json);}
}
static double correlation(const float *original,const float *enhanced,int n,int lag) {
 double xy=0,xx=0,yy=0;
 for(int i=513;i<n-513;i+=16){double x=original[i]-original[i-1],y=enhanced[i+lag]-enhanced[i+lag-1];xy+=x*y;xx+=x*x;yy+=y*y;}
 return xy/sqrt(xx*yy+1e-30);
}
int main(int argc,char **argv) {
 setbuf(stdout,NULL);setbuf(stderr,NULL);
 if(!((argc==5 && (!strcmp(argv[1],"enhance") || !strcmp(argv[1],"transcribe"))) || (argc==6 && !strcmp(argv[1],"blend")))) {
  fprintf(stderr,"Usage: %s enhance model.onnx input.wav output.wav | transcribe model-dir input.wav output.jsonl | blend original.wav enhanced.wav output.wav wet_ratio\n",argv[0]);return 2;
 }
 struct stat exists;if(stat(argv[4],&exists)==0){fprintf(stderr,"Refuse to overwrite existing output\n");return 11;}umask(0077);
 printf("ENGINE=%s ORT=%s\n",SherpaOnnxGetVersionStr(),SherpaOnnxGetOnnxruntimeVersionStr());
 const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[3]);
 if(!w || w->sample_rate!=16000 || w->num_samples<=0){fprintf(stderr,"Require 16kHz mono WAV\n");if(w)SherpaOnnxFreeWave(w);return 3;}
 int rc=0;double start=seconds();
 if(!strcmp(argv[1],"blend")) {
  char *end=NULL;float wet=strtof(argv[5],&end);
  const SherpaOnnxWave *original=SherpaOnnxReadWave(argv[2]);
  if(!end || end==argv[5] || *end || !isfinite(wet) || wet<0 || wet>1 || !original || original->sample_rate!=w->sample_rate || original->num_samples!=w->num_samples || w->num_samples<2048) {
   fprintf(stderr,"Blend requires matching WAVs and wet ratio 0..1\n");if(original)SherpaOnnxFreeWave(original);SherpaOnnxFreeWave(w);return 12;
  }
  int lag=0;double best=correlation(original->samples,w->samples,w->num_samples,0);
  for(int k=-512;k<=512;k+=4){double score=correlation(original->samples,w->samples,w->num_samples,k);if(score>best){best=score;lag=k;}}
  int coarse=lag;
  for(int k=coarse-3;k<=coarse+3;k++){if(k < -512 || k > 512)continue;double score=correlation(original->samples,w->samples,w->num_samples,k);if(score>best){best=score;lag=k;}}
  if(best<0.15){fprintf(stderr,"Insufficient alignment confidence; refusing blend\n");SherpaOnnxFreeWave(original);SherpaOnnxFreeWave(w);return 13;}
  float *mixed=malloc((size_t)w->num_samples*sizeof(float));
  if(!mixed){SherpaOnnxFreeWave(original);SherpaOnnxFreeWave(w);return 7;}
  float peak=0;
  for(int i=0;i<w->num_samples;i++){int j=i+lag;float x=original->samples[i];mixed[i]=(j>=0 && j<w->num_samples)?(1-wet)*x+wet*w->samples[j]:x;if(!isfinite(mixed[i])){rc=6;break;}if(fabsf(mixed[i])>peak)peak=fabsf(mixed[i]);}
  float gain=peak>0.95f?0.95f/peak:1;
  if(!rc){for(int i=0;i<w->num_samples;i++)mixed[i]*=gain;if(!SherpaOnnxWriteWave(mixed,w->num_samples,w->sample_rate,argv[4]))rc=8;}
  printf("BLEND_DONE samples=%d wet=%g aligned_lag_samples=%d correlation=%.4f headroom_gain=%g wall_s=%.3f\n",w->num_samples,wet,lag,best,gain,seconds()-start);
  free(mixed);SherpaOnnxFreeWave(original);
 }else if(!strcmp(argv[1],"enhance")) {
  SherpaOnnxOfflineSpeechDenoiserConfig config={0};config.model.gtcrn.model=argv[2];config.model.num_threads=1;config.model.provider="cpu";
  const SherpaOnnxOfflineSpeechDenoiser *d=SherpaOnnxCreateOfflineSpeechDenoiser(&config);
  if(!d){fprintf(stderr,"Denoiser initialization failed\n");SherpaOnnxFreeWave(w);return 4;}
  printf("DENOISE_STARTED samples=%d\n",w->num_samples);
  const SherpaOnnxDenoisedAudio *a=SherpaOnnxOfflineSpeechDenoiserRun(d,w->samples,w->num_samples,w->sample_rate);
  if(!a || a->sample_rate!=w->sample_rate || a->n!=w->num_samples){fprintf(stderr,"Denoiser did not preserve sample count\n");rc=5;}
  else {
   float peak=0;for(int i=0;i<a->n;i++){if(!isfinite(a->samples[i])){rc=6;break;}float x=fabsf(a->samples[i]);if(x>peak)peak=x;}
   float *safe=malloc((size_t)a->n*sizeof(float));
   if(!safe)rc=7;
   else {float gain=peak>0.95f?0.95f/peak:1.0f;for(int i=0;i<a->n;i++)safe[i]=a->samples[i]*gain;
    if(!rc && !SherpaOnnxWriteWave(safe,a->n,a->sample_rate,argv[4]))rc=8;free(safe);
    printf("DENOISE_DONE samples=%d wall_s=%.3f peak_before_headroom=%g headroom_gain=%g\n",a->n,seconds()-start,peak,gain);
   }
  }
  if(a)SherpaOnnxDestroyDenoisedAudio(a);SherpaOnnxDestroyOfflineSpeechDenoiser(d);
 }else {
  char encoder[1024],decoder[1024],joiner[1024],tokens[1024];
  snprintf(encoder,sizeof(encoder),"%s/encoder-epoch-99-avg-1.int8.onnx",argv[2]);snprintf(decoder,sizeof(decoder),"%s/decoder-epoch-99-avg-1.onnx",argv[2]);snprintf(joiner,sizeof(joiner),"%s/joiner-epoch-99-avg-1.int8.onnx",argv[2]);snprintf(tokens,sizeof(tokens),"%s/tokens.txt",argv[2]);
  SherpaOnnxOnlineRecognizerConfig config={0};config.feat_config.sample_rate=16000;config.feat_config.feature_dim=80;
  config.model_config.transducer.encoder=encoder;config.model_config.transducer.decoder=decoder;config.model_config.transducer.joiner=joiner;config.model_config.tokens=tokens;config.model_config.num_threads=1;config.model_config.provider="cpu";
  config.decoding_method="greedy_search";config.max_active_paths=4;config.enable_endpoint=1;config.rule1_min_trailing_silence=2.4f;config.rule2_min_trailing_silence=1.2f;config.rule3_min_utterance_length=30;
  const SherpaOnnxOnlineRecognizer *r=SherpaOnnxCreateOnlineRecognizer(&config);
  if(!r){fprintf(stderr,"ASR initialization failed\n");SherpaOnnxFreeWave(w);return 9;}
  const SherpaOnnxOnlineStream *s=SherpaOnnxCreateOnlineStream(r);FILE *out=fopen(argv[4],"wx");
  if(!s || !out){fprintf(stderr,"Cannot create stream or output (must be new file)\n");if(s)SherpaOnnxDestroyOnlineStream(s);SherpaOnnxDestroyOnlineRecognizer(r);SherpaOnnxFreeWave(w);return 10;}
  fprintf(out,"{\"input_samples\":%d,\"sample_rate\":%d,\"engine\":\"zipformer14m-int8\"}\n",w->num_samples,w->sample_rate);
  int fed=0;printf("ASR_STARTED samples=%d\n",w->num_samples);
  while(fed<w->num_samples){int n=w->num_samples-fed;if(n>1600)n=1600;SherpaOnnxOnlineStreamAcceptWaveform(s,16000,w->samples+fed,n);fed+=n;
   while(SherpaOnnxIsOnlineStreamReady(r,s))SherpaOnnxDecodeOnlineStream(r,s);
   if(SherpaOnnxOnlineStreamIsEndpoint(r,s)){emit(r,s,out,fed);SherpaOnnxOnlineStreamReset(r,s);}
   if(fed%160000==0)printf("ASR_PROGRESS samples=%d wall_s=%.3f\n",fed,seconds()-start);
  }
  float padding[12800]={0};SherpaOnnxOnlineStreamAcceptWaveform(s,16000,padding,12800);SherpaOnnxOnlineStreamInputFinished(s);
  while(SherpaOnnxIsOnlineStreamReady(r,s))SherpaOnnxDecodeOnlineStream(r,s);emit(r,s,out,fed);
  fprintf(out,"{\"completed\":true,\"fed_samples\":%d,\"flush_padding_samples\":12800,\"wall_s\":%.3f}\n",fed,seconds()-start);fclose(out);
  printf("ASR_DONE samples=%d wall_s=%.3f\n",fed,seconds()-start);
  SherpaOnnxDestroyOnlineStream(s);SherpaOnnxDestroyOnlineRecognizer(r);
 }
 SherpaOnnxFreeWave(w);return rc;
}
