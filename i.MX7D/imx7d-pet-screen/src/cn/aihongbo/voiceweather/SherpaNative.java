package cn.aihongbo.voiceweather;

/** Confined to the decoder executor; PCM and results never leave the device. */
final class SherpaNative {
    static {System.loadLibrary("onnxruntime");System.loadLibrary("sherpa-onnx-c-api");System.loadLibrary("aluo-sherpa");}
    static native long create(String directory);
    static native void reset(long handle,boolean wake);
    static native String accept(long handle,short[] samples,int length);
    static native String acceptStereo(long handle,short[] samples,int length);
    static native String transcribe(long handle);
    static native void destroy(long handle);
}
