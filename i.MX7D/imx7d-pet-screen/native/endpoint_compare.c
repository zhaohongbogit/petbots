/* Compare VAD boundaries on the same WAV; never records or executes a dialogue. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <stdio.h>
int main(int argc,char **argv){
 if(argc<3)return 2;setbuf(stdout,NULL);
 for(int profile=0;profile<2;profile++){
  float tail=profile?.4f:.7f;SherpaOnnxVadModelConfig c={0};c.silero_vad.model=argv[1];c.silero_vad.threshold=.5f;c.silero_vad.min_silence_duration=tail;c.silero_vad.min_speech_duration=.15f;c.silero_vad.max_speech_duration=12;c.silero_vad.window_size=512;c.sample_rate=16000;c.num_threads=1;c.provider="cpu";
  const SherpaOnnxVoiceActivityDetector *v=SherpaOnnxCreateVoiceActivityDetector(&c,20);if(!v)return 3;
  for(int file=2;file<argc;file++){
   const SherpaOnnxWave *w=SherpaOnnxReadWave(argv[file]);if(!w || w->sample_rate!=16000)return 4;SherpaOnnxVoiceActivityDetectorReset(v);int fed=0;
   while(fed<w->num_samples){int n=w->num_samples-fed;if(n>512)n=512;SherpaOnnxVoiceActivityDetectorAcceptWaveform(v,w->samples+fed,n);fed+=n;
    while(!SherpaOnnxVoiceActivityDetectorEmpty(v)){const SherpaOnnxSpeechSegment *s=SherpaOnnxVoiceActivityDetectorFront(v);if(!s)return 5;printf("{\"tail_ms\":%d,\"file\":%d,\"start\":%d,\"samples\":%d,\"detected_through\":%d}\n",profile?400:700,file-2,s->start,s->n,fed);SherpaOnnxDestroySpeechSegment(s);SherpaOnnxVoiceActivityDetectorPop(v);}
   }
   SherpaOnnxFreeWave(w);
  }
  SherpaOnnxDestroyVoiceActivityDetector(v);
 }
 return 0;
}
