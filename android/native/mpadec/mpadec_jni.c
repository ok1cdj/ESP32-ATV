/* MPEG audio (layer I / II / III) decoder for the RX tab: minimp3 (lieff/minimp3, CC0) behind JNI. Android has no MP2
 * decoder, and MP2 is what many DATV transmitters send (also the converter of ESP32-DATV-SA's video from the flash). */
#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"
#include <jni.h>
#include <stdlib.h>

#define FN(name) Java_com_ok1cdj_atvrx_MpegAudioDecoder_##name

JNIEXPORT jlong JNICALL FN(nativeNew)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    mp3dec_t *d = malloc(sizeof(*d));
    if (d) mp3dec_init(d);
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL FN(nativeFree)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    free((void *)(intptr_t)h);
}

/* Decodes the first frame in in[off, off + len) into pcm (interleaved, at least MINIMP3_MAX_SAMPLES_PER_FRAME shorts).
 * Returns the samples per channel (0: nothing decoded); info = {bytes used (0: more data needed), channels, Hz, layer}. */
JNIEXPORT jint JNICALL FN(nativeDecode)(JNIEnv *env, jclass cls, jlong h, jbyteArray in, jint off, jint len,
                                        jshortArray pcm, jintArray info) {
    (void)cls;
    if (len <= 0 || (*env)->GetArrayLength(env, pcm) < MINIMP3_MAX_SAMPLES_PER_FRAME) return 0;
    jbyte *src = (*env)->GetByteArrayElements(env, in, NULL);
    jshort *dst = (*env)->GetShortArrayElements(env, pcm, NULL);
    mp3dec_frame_info_t fi = {0};
    int n = mp3dec_decode_frame((mp3dec_t *)(intptr_t)h, (const uint8_t *)src + off, len, dst, &fi);
    (*env)->ReleaseShortArrayElements(env, pcm, dst, 0);
    (*env)->ReleaseByteArrayElements(env, in, src, JNI_ABORT);
    jint out[4] = {fi.frame_bytes, fi.channels, fi.hz, fi.layer};
    (*env)->SetIntArrayRegion(env, info, 0, 4, out);
    return n;
}
