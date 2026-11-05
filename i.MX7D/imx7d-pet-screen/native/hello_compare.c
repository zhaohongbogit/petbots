/* Pinned KWS + production stereo resampler. Files only; no microphone/TTS/network. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
static unsigned le(const unsigned char *p){return p[0]|(unsigned)p[1]<<8|(unsigned)p[2]<<16|(unsigned)p[3]<<24;}
static int decode(const SherpaOnnxKeywordSpotter *k,const SherpaOnnxOnlineStream *s,int file,long frames){
 int hits=0;while(SherpaOnnxIsKeywordStreamReady(k,s)){
  SherpaOnnxDecodeKeywordStream(k,s);const SherpaOnnxKeywordResult *r=SherpaOnnxGetKeywordResult(k,s);
  if(r && r->keyword && r->keyword[0]){hits++;printf("{\"file\":%d,\"source_frames\":%ld,\"result\":%s}\n",file,frames,r->json);SherpaOnnxResetKeywordStream(k,s);}if(r)SherpaOnnxDestroyKeywordResult(r);
 }return hits;
}
int main(int argc,char **argv){
 if(argc<3)return 2;setbuf(stdout,NULL);
 char encoder[1024],decoder[1024],joiner[1024],tokens[1024];
 snprintf(encoder,sizeof(encoder),"%s/kws/encoder.int8.onnx",argv[1]);snprintf(decoder,sizeof(decoder),"%s/kws/decoder.onnx",argv[1]);snprintf(joiner,sizeof(joiner),"%s/kws/joiner.int8.onnx",argv[1]);snprintf(tokens,sizeof(tokens),"%s/kws/tokens.txt",argv[1]);
 float threshold=.25f;const char *setting=getenv("ALUO_KWS_THRESHOLD");if(setting)threshold=strtof(setting,NULL);if(!(threshold>=.05f && threshold<=1))return 2;
 int fresh=getenv("ALUO_KWS_FRESH_STREAM") && !strcmp(getenv("ALUO_KWS_FRESH_STREAM"),"1");
 const char *keywords="n ǐ h ǎo ā l uò @你好阿洛\n";
 SherpaOnnxKeywordSpotterConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;c.model_config.transducer.encoder=encoder;c.model_config.transducer.decoder=decoder;c.model_config.transducer.joiner=joiner;c.model_config.tokens=tokens;c.model_config.num_threads=1;c.model_config.provider="cpu";c.max_active_paths=4;c.num_trailing_blanks=1;c.keywords_score=1;c.keywords_threshold=threshold;c.keywords_buf=keywords;c.keywords_buf_size=strlen(keywords);
 double start=now();const SherpaOnnxKeywordSpotter *k=SherpaOnnxCreateKeywordSpotter(&c);if(!k)return 3;
 const SherpaOnnxLinearResampler *resampler=SherpaOnnxCreateLinearResampler(44100,16000,0,0);if(!resampler)return 4;
 printf("{\"load_s\":%.3f,\"keyword\":\"你好阿洛\",\"score\":1,\"threshold\":%.3f,\"fresh_stream\":%d}\n",now()-start,threshold,fresh);
 for(int file=2;file<argc;file++){
  FILE *in=fopen(argv[file],"rb");if(!in)return 5;unsigned char h[44];if(fread(h,1,44,in)!=44 || memcmp(h,"RIFF",4) || memcmp(h+8,"WAVEfmt ",8) || le(h+16)!=16 || h[20]!=1 || h[22]!=2 || le(h+24)!=44100 || h[34]!=16 || memcmp(h+36,"data",4))return 6;
  const SherpaOnnxOnlineStream *s=SherpaOnnxCreateKeywordStream(k);if(!s)return 7;SherpaOnnxLinearResamplerReset(resampler);
  int16_t input[8820];float mono[4410];unsigned remaining=le(h+40);int hits=0;long frames=0;double tick=now();
  while(remaining){unsigned count=remaining/2;if(count>8820)count=8820;if(count%2 || !count || fread(input,2,count,in)!=count)return 8;remaining-=count*2;frames+=count/2;
   for(unsigned i=0;i<count/2;i++)mono[i]=(input[2*i]+(float)input[2*i+1])/65536.f;
   const SherpaOnnxResampleOut *out=SherpaOnnxLinearResamplerResample(resampler,mono,count/2,0);if(!out)return 9;
   SherpaOnnxOnlineStreamAcceptWaveform(s,16000,out->samples,out->n);SherpaOnnxLinearResamplerResampleFree(out);int found=decode(k,s,file-2,frames);hits+=found;if(found && fresh){SherpaOnnxDestroyOnlineStream(s);s=SherpaOnnxCreateKeywordStream(k);if(!s)return 7;}
  }
  float padding[8000]={0};SherpaOnnxOnlineStreamAcceptWaveform(s,16000,padding,8000);hits+=decode(k,s,file-2,frames);
  printf("{\"file\":%d,\"source_frames\":%ld,\"hits\":%d,\"decode_s\":%.3f}\n",file-2,frames,hits,now()-tick);
  SherpaOnnxDestroyOnlineStream(s);fclose(in);
 }
 SherpaOnnxDestroyLinearResampler(resampler);SherpaOnnxDestroyKeywordSpotter(k);return 0;
}
