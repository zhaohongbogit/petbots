/* Local file-only Paraformer comparison: no microphone/network/playback. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
#include <time.h>
#include <sys/resource.h>
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
int main(int argc,char **argv){
 if(argc!=4)return 2;setbuf(stdout,NULL);char model[1024],tokens[1024];snprintf(model,sizeof(model),"%s/model.int8.onnx",argv[1]);snprintf(tokens,sizeof(tokens),"%s/tokens.txt",argv[1]);
 SherpaOnnxOfflineRecognizerConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;c.model_config.paraformer.model=model;c.model_config.tokens=tokens;c.model_config.num_threads=1;c.model_config.provider="cpu";c.decoding_method="greedy_search";
 double start=now();const SherpaOnnxOfflineRecognizer *r=SherpaOnnxCreateOfflineRecognizer(&c);if(!r)return 3;double loaded=now();printf("{\"load_s\":%.3f}\n",loaded-start);
 const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[2]);if(!w || w->sample_rate!=16000)return 4;
 SherpaOnnxVadModelConfig vc={0};vc.silero_vad.model=argv[3];vc.silero_vad.threshold=.5f;vc.silero_vad.min_silence_duration=.7f;vc.silero_vad.min_speech_duration=.15f;vc.silero_vad.max_speech_duration=12;vc.silero_vad.window_size=512;vc.sample_rate=16000;vc.num_threads=1;vc.provider="cpu";
 const SherpaOnnxVoiceActivityDetector *v=SherpaOnnxCreateVoiceActivityDetector(&vc,30);if(!v)return 6;
 double vadStart=now();
 for(int from=0;from<w->num_samples;from+=512){int n=w->num_samples-from;if(n>512)n=512;SherpaOnnxVoiceActivityDetectorAcceptWaveform(v,w->samples+from,n);}
 SherpaOnnxVoiceActivityDetectorFlush(v);printf("{\"vad_s\":%.3f}\n",now()-vadStart);
 while(!SherpaOnnxVoiceActivityDetectorEmpty(v)){
  const SherpaOnnxSpeechSegment *segment=SherpaOnnxVoiceActivityDetectorFront(v);if(!segment)return 7;
  const SherpaOnnxOfflineStream *s=SherpaOnnxCreateOfflineStream(r);if(!s)return 5;double tick=now();SherpaOnnxAcceptWaveformOffline(s,16000,segment->samples,segment->n);SherpaOnnxDecodeOfflineStream(r,s);
  const char *json=SherpaOnnxGetOfflineStreamResultAsJson(s);if(json){printf("{\"sample_start\":%d,\"samples\":%d,\"asr_s\":%.3f,\"result\":%s}\n",segment->start,segment->n,now()-tick,json);SherpaOnnxDestroyOfflineStreamResultJson(json);}SherpaOnnxDestroyOfflineStream(s);SherpaOnnxDestroySpeechSegment(segment);SherpaOnnxVoiceActivityDetectorPop(v);
 }
 SherpaOnnxDestroyVoiceActivityDetector(v);
 struct rusage usage;getrusage(RUSAGE_SELF,&usage);printf("{\"samples\":%d,\"decode_s\":%.3f,\"peak_rss_kib\":%ld}\n",w->num_samples,now()-loaded,usage.ru_maxrss);
 SherpaOnnxFreeWave(w);SherpaOnnxDestroyOfflineRecognizer(r);return 0;
}
