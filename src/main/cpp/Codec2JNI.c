/*
 * Codec 2 decode -> JNI kopru (saf C; libc++ bagimliligi yok).
 * com.codec2.player.Codec2 native metodlarini JNI_OnLoad icinde
 * RegisterNatives ile baglar. Yalniz cozme (decode) yolu.
 */
#include <jni.h>
#include <stdint.h>
#include "codec2.h"

static jlong c2_create(JNIEnv *env, jclass clazz, jint mode) {
    struct CODEC2 *c = codec2_create(mode);
    if (!c) return 0;
    codec2_set_natural_or_gray(c, 1);
    return (jlong)(uintptr_t) c;
}

static jint c2_spf(JNIEnv *env, jclass clazz, jlong n) {
    return codec2_samples_per_frame((struct CODEC2 *)(uintptr_t) n);
}

static jint c2_bytes(JNIEnv *env, jclass clazz, jlong n) {
    return codec2_bytes_per_frame((struct CODEC2 *)(uintptr_t) n);
}

static void c2_destroy(JNIEnv *env, jclass clazz, jlong n) {
    if (n) codec2_destroy((struct CODEC2 *)(uintptr_t) n);
}

static void c2_decode(JNIEnv *env, jclass clazz, jlong n, jbyteArray inBits, jshortArray outPcm) {
    struct CODEC2 *c = (struct CODEC2 *)(uintptr_t) n;
    jbyte  *b = (*env)->GetByteArrayElements(env, inBits, 0);
    jshort *s = (*env)->GetShortArrayElements(env, outPcm, 0);
    codec2_decode(c, (short *) s, (unsigned char *) b);
    (*env)->ReleaseShortArrayElements(env, outPcm, s, 0);
    (*env)->ReleaseByteArrayElements(env, inBits, b, JNI_ABORT);
}

static JNINativeMethod methods[] = {
    { "create",          "(I)J",     (void *) c2_create  },
    { "samplesPerFrame", "(J)I",     (void *) c2_spf     },
    { "bytesPerFrame",   "(J)I",     (void *) c2_bytes   },
    { "destroy",         "(J)V",     (void *) c2_destroy },
    { "decode",          "(J[B[S)V", (void *) c2_decode  },
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "com/codec2/player/Codec2");
    if (!clazz) return JNI_ERR;
    jint r = (*env)->RegisterNatives(env, clazz, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, clazz);
    return (r == 0) ? JNI_VERSION_1_6 : JNI_ERR;
}
