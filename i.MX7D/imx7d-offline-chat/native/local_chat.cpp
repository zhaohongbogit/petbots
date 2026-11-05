// Offline, stateless inference. No sockets and no device-command execution.
#include <llama.h>
#include <chrono>
#include <fstream>
#include <string>
#include <vector>
#include <memory>
#include <cstdio>
#include <algorithm>
#include <sys/stat.h>
#ifdef __ANDROID__
#include <sys/resource.h>
#endif
struct Deadline {std::chrono::steady_clock::time_point end;};
static bool aborted(void *data){return std::chrono::steady_clock::now()>=static_cast<Deadline*>(data)->end;}
int main(int argc,char **argv){
 if(argc!=4){fprintf(stderr,"Usage: imx7-local-chat model.gguf prompt.txt NEW-output.txt\n");return 2;}
 struct stat existing;if(stat(argv[3],&existing)==0){fprintf(stderr,"Output exists\n");return 3;}
 umask(0077);
 std::ifstream input(argv[2],std::ios::binary);std::string prompt((std::istreambuf_iterator<char>(input)),{});
 if(!input || prompt.empty() || prompt.size()>8192){fprintf(stderr,"Invalid prompt\n");return 4;}
#ifdef __ANDROID__
 struct rlimit limit={512ULL*1024*1024,512ULL*1024*1024};if(setrlimit(RLIMIT_AS,&limit)!=0){perror("setrlimit");return 5;}
#endif
 llama_log_set([](ggml_log_level level,const char *text,void *){if(level<=GGML_LOG_LEVEL_ERROR)fputs(text,stderr);},nullptr);
 llama_backend_init();auto mparams=llama_model_default_params();mparams.n_gpu_layers=0;mparams.load_mode=LLAMA_LOAD_MODE_MMAP;mparams.use_extra_bufts=false;
 auto *model=llama_model_load_from_file(argv[1],mparams);if(!model){fprintf(stderr,"Model load failed\n");return 6;}
 const auto *vocab=llama_model_get_vocab(model);int count=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
 constexpr int context=256,max_output=64;
 if(count<=0 || count+max_output>context){fprintf(stderr,"Prompt too long: tokens=%d limit=%d\n",count,context-max_output);llama_model_free(model);return 7;}
 std::vector<llama_token> tokens(count);llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),count,true,true);
 Deadline deadline{std::chrono::steady_clock::now()+std::chrono::seconds(150)};
 auto params=llama_context_default_params();params.n_ctx=context;params.n_batch=32;params.n_ubatch=32;params.n_threads=2;params.n_threads_batch=2;params.abort_callback=aborted;params.abort_callback_data=&deadline;
 std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model,params),llama_free);
 if(!ctx){llama_model_free(model);return 8;}
 auto sampler=std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)>(llama_sampler_init_greedy(),llama_sampler_free);
 auto started=std::chrono::steady_clock::now();bool complete=false;int generated=0;std::string output;
 fprintf(stderr,"READY input_tokens=%d\n",count);
 for(int offset=0;offset<count;offset+=32){auto batch=llama_batch_get_one(tokens.data()+offset,std::min(32,count-offset));if(aborted(&deadline)||llama_decode(ctx.get(),batch)!=0)goto finish;}
 fprintf(stderr,"PREFILL_DONE\n");
 for(int i=0;i<max_output;i++){
  if(aborted(&deadline))break;llama_token token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
  if(llama_vocab_is_eog(vocab,token)){complete=true;break;}
  char piece[512];int n=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,true);if(n<0)break;output.append(piece,n);generated++;
  if(output.size()>=24 && (output.find("。")!=std::string::npos || output.find("！")!=std::string::npos || output.find("？")!=std::string::npos)){complete=true;break;}
  if(generated%8==0)fprintf(stderr,"GENERATING tokens=%d\n",generated);
  auto batch=llama_batch_get_one(&token,1);if(llama_decode(ctx.get(),batch)!=0)break;
 }
finish:
 double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count();
 if(!complete && !output.empty()){std::ofstream partial(std::string(argv[3])+".partial",std::ios::binary);partial<<output;}
 if(complete && !output.empty()){std::ofstream out(argv[3],std::ios::binary);out<<output;out.close();if(!out){fprintf(stderr,"Write failed\n");complete=false;}}
 fprintf(stderr,"DONE complete=%d generated=%d elapsed_s=%.3f\n",complete,generated,elapsed);
 sampler.reset();ctx.reset();llama_model_free(model);llama_backend_free();return complete?0:9;
}
