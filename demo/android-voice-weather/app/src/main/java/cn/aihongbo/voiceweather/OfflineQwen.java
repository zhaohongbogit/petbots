package cn.aihongbo.voiceweather;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** CPU-only llama.cpp JNI. All calls except cancel run on one dedicated worker. */
public final class OfflineQwen implements AutoCloseable {
    public static final String MODEL_FILE="Qwen3-0.6B-Q4_K_M.gguf";
    static { System.loadLibrary("voice_qwen"); }
    private long handle;
    public void load(File file) {
        if(handle!=0) return;
        if(!file.isFile()) throw new IllegalStateException("缺少模型文件："+MODEL_FILE+"；请按 README 部署模型");
        if(file.length()!=396704416L) throw new IllegalStateException("模型文件不完整，长度不符；请重新部署");
        try(java.io.InputStream input=new java.io.FileInputStream(file)) {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[1024*1024];int count;
            while((count=input.read(buffer))!=-1) digest.update(buffer,0,count);
            StringBuilder hex=new StringBuilder();for(byte value:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            if(!hex.toString().equals("1f06f85468d17be76c1212d612c15f9d7d70f54fc553839458d5a8f5ccd4c8fd")) throw new IllegalStateException("模型 SHA-256 不匹配；请重新部署本项目量化文件");
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException error) {throw new IllegalStateException("模型校验失败："+error.getMessage(),error);}
        handle=nativeLoad(file.getAbsolutePath());
        if(handle==0) throw new IllegalStateException("模型加载失败：文件损坏、内存不足或不兼容");
    }
    public String complete(String prompt,String grammar) {
        if(handle==0) throw new IllegalStateException("模型未加载");
        byte[] output=nativeInfer(handle,prompt.getBytes(StandardCharsets.UTF_8),grammar,15000);
        if(output==null) throw new IllegalStateException("推理取消、超过 15 秒、内存不足或解码失败");
        return new String(output,StandardCharsets.UTF_8);
    }
    public void cancel() { nativeCancel(); }
    @Override public void close() { if(handle!=0) {nativeFree(handle);handle=0;} }
    private static native long nativeLoad(String path);
    private static native byte[] nativeInfer(long handle,byte[] prompt,String grammar,int timeoutMs);
    private static native void nativeCancel();
    private static native void nativeFree(long handle);
}
