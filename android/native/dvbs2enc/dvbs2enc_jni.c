/* JNI bindings of dvbs2enc for com.ok1cdj.atvrx.S2Encoder (continuous framing). GPL-3.0-or-later. */
#include <jni.h>
#include <stdint.h>

#include "dvbs2enc.h"

#define FN(name) Java_com_ok1cdj_atvrx_S2Encoder_##name

JNIEXPORT jlong JNICALL FN(nativeNew)(JNIEnv *env, jclass cls, jint constellation, jstring rate, jboolean shortFrame, jboolean pilots)
{
    (void)cls;
    const char *r = (*env)->GetStringUTFChars(env, rate, NULL);
    s2enc_cfg cfg = { constellation, r, shortFrame, pilots, 0 };
    const char *err = NULL;
    s2enc *e = s2enc_new(&cfg, &err);
    (*env)->ReleaseStringUTFChars(env, rate, r);
    if (!e) {
        jclass ex = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        (*env)->ThrowNew(env, ex, err ? err : "dvbs2enc");
        return 0;
    }
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL FN(nativeFree)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; s2enc_free((s2enc *)(intptr_t)h); }
JNIEXPORT jint JNICALL FN(nativeFrameSymbols)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return s2enc_frame_symbols((s2enc *)(intptr_t)h); }
JNIEXPORT jint JNICALL FN(nativeKbch)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return s2enc_kbch((s2enc *)(intptr_t)h); }
JNIEXPORT jint JNICALL FN(nativeModcod)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return s2enc_modcod((s2enc *)(intptr_t)h); }
JNIEXPORT jboolean JNICALL FN(nativeReady)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return (jboolean)s2enc_ready((s2enc *)(intptr_t)h); }

/* push one 188-byte packet from buf[off]: 1 taken, 0 buffer full, -1 no sync byte */
JNIEXPORT jint JNICALL FN(nativePush)(JNIEnv *env, jclass cls, jlong h, jbyteArray buf, jint off)
{
    (void)cls;
    jbyte p[188];
    (*env)->GetByteArrayRegion(env, buf, off, 188, p);
    return s2enc_push((s2enc *)(intptr_t)h, (const uint8_t *)p);
}

/* the next PLFRAME as phase indices into out (frameSymbols bytes); returns its symbols, 0 when not ready */
JNIEXPORT jint JNICALL FN(nativeStreamFrame)(JNIEnv *env, jclass cls, jlong h, jbyteArray out)
{
    (void)cls;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = s2enc_stream_frame((s2enc *)(intptr_t)h, (uint8_t *)o);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}
