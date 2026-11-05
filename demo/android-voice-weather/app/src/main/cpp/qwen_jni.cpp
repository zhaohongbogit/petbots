#include <jni.h>
#include <llama.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::once_flag initialized;
std::atomic<unsigned long> cancel_epoch{0};
struct Deadline {
    unsigned long epoch;
    std::chrono::steady_clock::time_point end;
};
bool aborted(void *data) {
    auto *deadline=static_cast<Deadline *>(data);
    return cancel_epoch.load()!=deadline->epoch || std::chrono::steady_clock::now()>=deadline->end;
}
// JNI input/output uses real UTF-8 bytes, not modified UTF-8 strings.
std::string bytes(JNIEnv *env,jbyteArray input) {
    std::string result(env->GetArrayLength(input),'\0');
    env->GetByteArrayRegion(input,0,result.size(),reinterpret_cast<jbyte *>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_cn_aihongbo_voiceweather_OfflineQwen_nativeLoad(JNIEnv *env,jclass,jstring path) {
    std::call_once(initialized,[]{llama_backend_init();});
    const char *name=env->GetStringUTFChars(path,nullptr);
    auto params=llama_model_default_params(); params.n_gpu_layers=0;
    auto *model=llama_model_load_from_file(name,params);
    env->ReleaseStringUTFChars(path,name);
    return reinterpret_cast<jlong>(model);
}
extern "C" JNIEXPORT void JNICALL
Java_cn_aihongbo_voiceweather_OfflineQwen_nativeCancel(JNIEnv *,jclass) { ++cancel_epoch; }
extern "C" JNIEXPORT void JNICALL
Java_cn_aihongbo_voiceweather_OfflineQwen_nativeFree(JNIEnv *,jclass,jlong handle) {
    llama_model_free(reinterpret_cast<llama_model *>(handle));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_aihongbo_voiceweather_OfflineQwen_nativeInfer(JNIEnv *env,jclass,jlong handle,jbyteArray input,jstring grammar,jint timeout) {
    auto *model=reinterpret_cast<llama_model *>(handle);
    if(!model) return nullptr;
    Deadline deadline{cancel_epoch.load(),std::chrono::steady_clock::now()+std::chrono::milliseconds(timeout)};
    std::string prompt=bytes(env,input);
    const auto *vocab=llama_model_get_vocab(model);
    int count=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
    constexpr int max_output=96;
    if(count<=0 || count+max_output>1024) return nullptr;
    std::vector<llama_token> tokens(count);
    if(llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),count,true,true)!=count) return nullptr;
    auto params=llama_context_default_params();
    params.n_ctx=1024;params.n_batch=512;params.n_ubatch=128;
    params.n_threads=2;params.n_threads_batch=2; // Leave CPU capacity for continuous Vosk decoding.
    params.abort_callback=aborted;params.abort_callback_data=&deadline;
    std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model,params),llama_free);
    if(!ctx) return nullptr;
    const char *grammar_text=env->GetStringUTFChars(grammar,nullptr);
    auto *grammar_sampler=llama_sampler_init_grammar(vocab,grammar_text,"root");
    env->ReleaseStringUTFChars(grammar,grammar_text);
    if(!grammar_sampler) return nullptr;
    std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
    llama_sampler_chain_add(sampler.get(),grammar_sampler);
    llama_sampler_chain_add(sampler.get(),llama_sampler_init_greedy());
    for(int offset=0;offset<count;offset+=512) {
        if(aborted(&deadline)) return nullptr;
        auto batch=llama_batch_get_one(tokens.data()+offset,std::min(512,count-offset));
        if(llama_decode(ctx.get(),batch)!=0) return nullptr;
    }
    std::string output;
    bool complete=false;
    for(int step=0;step<max_output;++step) {
        if(aborted(&deadline)) return nullptr;
        llama_token token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
        if(llama_vocab_is_eog(vocab,token)) {complete=true;break;}
        char piece[256];int size=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,true);
        if(size<0) return nullptr;
        output.append(piece,size);
        // The grammar has exactly one JSON object; stop at its closing brace.
        if(output.find('}')!=std::string::npos) {complete=true;break;}
        auto batch=llama_batch_get_one(&token,1);
        if(llama_decode(ctx.get(),batch)!=0) return nullptr;
    }
    if(!complete || output.empty()) return nullptr;
    auto result=env->NewByteArray(output.size());
    env->SetByteArrayRegion(result,0,output.size(),reinterpret_cast<const jbyte *>(output.data()));
    return result;
}
