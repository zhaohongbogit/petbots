/* Board-only JNI: one offline recognizer; VAD segments wake, command and consent. */
#include "../../../demo/android-voice-weather/diary/native/vendor/sherpa-c-api.h"
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef struct {
 const SherpaOnnxOfflineRecognizer *asr;
 const SherpaOnnxVoiceActivityDetector *vad;
 const SherpaOnnxVoiceActivityDetector *wake_vad;
 const SherpaOnnxVoiceActivityDetector *dialogue_vad;
 const SherpaOnnxSpeechSegment *pending;
 const SherpaOnnxLinearResampler *resampler;
 int wake;
 float history[320000],window[512];
 int received,window_size;
} Engine;
static void destroy(Engine *e){
 if(!e)return;
 if(e->pending)SherpaOnnxDestroySpeechSegment(e->pending);
 if(e->asr)SherpaOnnxDestroyOfflineRecognizer(e->asr);
 if(e->wake_vad)SherpaOnnxDestroyVoiceActivityDetector(e->wake_vad);
 if(e->dialogue_vad)SherpaOnnxDestroyVoiceActivityDetector(e->dialogue_vad);
 if(e->resampler)SherpaOnnxDestroyLinearResampler(e->resampler);
 free(e);
}
JNIEXPORT jlong JNICALL Java_cn_aihongbo_voiceweather_SherpaNative_create(JNIEnv *env,jclass cls,jstring directory){
 (void)cls;const char *root=(*env)->GetStringUTFChars(env,directory,NULL);if(!root)return 0;
 char asr[1024],asrtokens[1024],vad[1024];
 snprintf(asr,sizeof(asr),"%s/asr/model.int8.onnx",root);snprintf(asrtokens,sizeof(asrtokens),"%s/asr/tokens.txt",root);snprintf(vad,sizeof(vad),"%s/vad/silero_vad.onnx",root);
 (*env)->ReleaseStringUTFChars(env,directory,root);
 Engine *e=calloc(1,sizeof(*e));if(!e)return 0;
 e->resampler=SherpaOnnxCreateLinearResampler(44100,16000,0,0);if(!e->resampler){destroy(e);return 0;}
 SherpaOnnxOfflineRecognizerConfig c={0};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;c.model_config.paraformer.model=asr;c.model_config.tokens=asrtokens;c.model_config.num_threads=2;c.model_config.provider="cpu";c.decoding_method="greedy_search";
 e->asr=SherpaOnnxCreateOfflineRecognizer(&c);if(!e->asr){destroy(e);return 0;}
 SherpaOnnxVadModelConfig v={0};v.silero_vad.model=vad;v.silero_vad.threshold=.5f;v.silero_vad.min_silence_duration=.7f;v.silero_vad.min_speech_duration=.15f;v.silero_vad.max_speech_duration=12;v.silero_vad.window_size=512;v.sample_rate=16000;v.num_threads=1;v.provider="cpu";
 e->dialogue_vad=SherpaOnnxCreateVoiceActivityDetector(&v,20);if(!e->dialogue_vad){destroy(e);return 0;}
 v.silero_vad.min_silence_duration=.4f;
 e->wake_vad=SherpaOnnxCreateVoiceActivityDetector(&v,20);if(!e->wake_vad){destroy(e);return 0;}
 e->vad=e->dialogue_vad;
 return (jlong)(intptr_t)e;
}
JNIEXPORT void JNICALL Java_cn_aihongbo_voiceweather_SherpaNative_reset(JNIEnv *env,jclass cls,jlong handle,jboolean wake){
 (void)env;(void)cls;Engine *e=(Engine *)(intptr_t)handle;if(!e)return;
 if(e->pending){SherpaOnnxDestroySpeechSegment(e->pending);e->pending=NULL;}
 e->wake=wake;e->vad=wake?e->wake_vad:e->dialogue_vad;
 e->received=0;e->window_size=0;
 SherpaOnnxVoiceActivityDetectorReset(e->vad);
 SherpaOnnxLinearResamplerReset(e->resampler);
}
static jstring accept_samples(JNIEnv *env,Engine *e,const float *samples,int length){
 /* The capture frame is 100ms; Silero always receives complete 32ms windows. */
 for(int i=0;i<length;i++){
  e->history[e->received%320000]=samples[i];e->received++;
  e->window[e->window_size++]=samples[i];
  if(e->window_size==512){SherpaOnnxVoiceActivityDetectorAcceptWaveform(e->vad,e->window,512);e->window_size=0;}
 }
 if(!e->pending && !SherpaOnnxVoiceActivityDetectorEmpty(e->vad)){
  e->pending=SherpaOnnxVoiceActivityDetectorFront(e->vad);SherpaOnnxVoiceActivityDetectorPop(e->vad);
  if(e->pending){char json[160];snprintf(json,sizeof(json),"{\"segment\":true,\"speech\":true,\"start\":%d,\"samples\":%d}",e->pending->start,e->pending->n);return (*env)->NewStringUTF(env,json);}
 }
 if(e->wake && e->received>16000*600 && !SherpaOnnxVoiceActivityDetectorDetected(e->vad) && SherpaOnnxVoiceActivityDetectorEmpty(e->vad)){SherpaOnnxVoiceActivityDetectorReset(e->vad);e->received=0;e->window_size=0;}
 return SherpaOnnxVoiceActivityDetectorDetected(e->vad)?(*env)->NewStringUTF(env,"{\"speech\":true}"):NULL;
}
JNIEXPORT jstring JNICALL Java_cn_aihongbo_voiceweather_SherpaNative_accept(JNIEnv *env,jclass cls,jlong handle,jshortArray pcm,jint length){
 (void)cls;Engine *e=(Engine *)(intptr_t)handle;if(!e || length<1 || length>1280 || length>(*env)->GetArrayLength(env,pcm))return NULL;
 jshort input[1280];float samples[1280];(*env)->GetShortArrayRegion(env,pcm,0,length,input);if((*env)->ExceptionCheck(env))return NULL;
 for(int i=0;i<length;i++)samples[i]=input[i]/32768.f;
 return accept_samples(env,e,samples,length);
}
JNIEXPORT jstring JNICALL Java_cn_aihongbo_voiceweather_SherpaNative_acceptStereo(JNIEnv *env,jclass cls,jlong handle,jshortArray pcm,jint length){
 (void)cls;Engine *e=(Engine *)(intptr_t)handle;if(!e || length<2 || length>8820 || length%2 || length>(*env)->GetArrayLength(env,pcm))return NULL;
 jshort input[8820];float mono[4410];(*env)->GetShortArrayRegion(env,pcm,0,length,input);if((*env)->ExceptionCheck(env))return NULL;
 for(int i=0;i<length/2;i++)mono[i]=(input[i*2]+(float)input[i*2+1])/65536.f;
 const SherpaOnnxResampleOut *out=SherpaOnnxLinearResamplerResample(e->resampler,mono,length/2,0);
 jstring result=out?accept_samples(env,e,out->samples,out->n):NULL;
 if(out)SherpaOnnxLinearResamplerResampleFree(out);return result;
}
JNIEXPORT jstring JNICALL Java_cn_aihongbo_voiceweather_SherpaNative_transcribe(JNIEnv *env,jclass cls,jlong handle){
 (void)cls;Engine *e=(Engine *)(intptr_t)handle;if(!e || !e->pending)return NULL;
 const SherpaOnnxOfflineStream *s=SherpaOnnxCreateOfflineStream(e->asr);if(!s)return NULL;
 /* Preserve 300ms before VAD onset. Never repair missing characters in the transcript. */
 int first=e->pending->start-4800;if(first<0)first=0;
 int end=e->pending->start+e->pending->n;if(end>e->received)end=e->received;
 if(first<e->received-320000 || end<=first){SherpaOnnxDestroyOfflineStream(s);return NULL;}
 int n=end-first;float *utterance=malloc((size_t)n*sizeof(float));if(!utterance){SherpaOnnxDestroyOfflineStream(s);return NULL;}
 for(int i=0;i<n;i++)utterance[i]=e->history[(first+i)%320000];
 SherpaOnnxAcceptWaveformOffline(s,16000,utterance,n);free(utterance);SherpaOnnxDecodeOfflineStream(e->asr,s);
 const char *json=SherpaOnnxGetOfflineStreamResultAsJson(s);jstring result=json?(*env)->NewStringUTF(env,json):NULL;
 if(json)SherpaOnnxDestroyOfflineStreamResultJson(json);SherpaOnnxDestroyOfflineStream(s);
 SherpaOnnxDestroySpeechSegment(e->pending);e->pending=NULL;return result;
}
JNIEXPORT void JNICALL Java_cn_aihongbo_voiceweather_SherpaNative_destroy(JNIEnv *env,jclass cls,jlong handle){(void)env;(void)cls;destroy((Engine *)(intptr_t)handle);}
