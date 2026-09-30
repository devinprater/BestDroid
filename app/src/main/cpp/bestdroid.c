/* JNI bridge for the OpenBST (BeSTspeech) engine.
 *
 * Stateless by design: each synth call opens its own handle, applies the
 * requested voice parameters, synthesizes, and closes. Opening is cheap
 * (tens of microseconds), and statelessness keeps concurrent syntheses
 * from different TTS requests from stepping on each other.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "bst.h"

#define PKG "org/bestdroid/tts/NativeBst"

JNIEXPORT jstring JNICALL Java_org_bestdroid_tts_NativeBst_nativeBuildsCsv(
        JNIEnv *env, jclass cls) {
    (void)cls;
    int count = bst_builds(NULL, 0);
    if (count <= 0) return (*env)->NewStringUTF(env, "");
    const char **names = (const char **)calloc((size_t)count, sizeof *names);
    if (!names) return (*env)->NewStringUTF(env, "");
    bst_builds(names, count);
    size_t total = 0;
    for (int i = 0; i < count; i++) total += strlen(names[i]) + 1;
    char *csv = (char *)malloc(total + 1);
    if (!csv) { free(names); return (*env)->NewStringUTF(env, ""); }
    csv[0] = '\0';
    for (int i = 0; i < count; i++) {
        if (i) strcat(csv, ",");
        strcat(csv, names[i]);
    }
    jstring out = (*env)->NewStringUTF(env, csv);
    free(csv);
    free(names);
    return out;
}

/* Synthesizes one chunk: opens the build, sets pitch/rate, returns PCM.
 * `text` is already encoded for the build (Latin bytes or the build's
 * legacy code page) and NUL-free. Returns null when there is nothing. */
JNIEXPORT jshortArray JNICALL Java_org_bestdroid_tts_NativeBst_nativeSay(
        JNIEnv *env, jclass cls, jstring build, jbyteArray text,
        jint pitch, jint rate) {
    (void)cls;
    if (build == NULL || text == NULL) return NULL;
    const char *name = (*env)->GetStringUTFChars(env, build, NULL);
    if (!name) return NULL;

    jsize len = (*env)->GetArrayLength(env, text);
    if (len <= 0) { (*env)->ReleaseStringUTFChars(env, build, name); return NULL; }

    jbyte *bytes = (*env)->GetByteArrayElements(env, text, NULL);
    if (!bytes) { (*env)->ReleaseStringUTFChars(env, build, name); return NULL; }

    /* Reject embedded NULs: they would truncate silently at the C boundary. */
    for (jsize i = 0; i < len; i++) {
        if (bytes[i] == 0) {
            (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
            (*env)->ReleaseStringUTFChars(env, build, name);
            return NULL;
        }
    }

    char *terminated = (char *)malloc((size_t)len + 1);
    if (!terminated) {
        (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
        (*env)->ReleaseStringUTFChars(env, build, name);
        return NULL;
    }
    memcpy(terminated, bytes, (size_t)len);
    terminated[len] = '\0';
    (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);

    bst *h = bst_open(name);
    (*env)->ReleaseStringUTFChars(env, build, name);
    if (!h) { free(terminated); return NULL; }

    if (pitch != 0) bst_set(h, "pitch", pitch);
    bst_set(h, "rate", rate);

    long want = bst_length(h, terminated);
    jshortArray out = NULL;
    if (want > 0) {
        int16_t *pcm = (int16_t *)malloc((size_t)want * sizeof(int16_t));
        if (pcm) {
            long written = bst_say(h, terminated, pcm, want);
            if (written > 0) {
                out = (*env)->NewShortArray(env, (jsize)written);
                if (out) (*env)->SetShortArrayRegion(env, out, 0, (jsize)written, pcm);
            }
            free(pcm);
        }
    }
    free(terminated);
    bst_close(h);
    return out;
}

JNIEXPORT jint JNICALL Java_org_bestdroid_tts_NativeBst_nativeRate(
        JNIEnv *env, jclass cls, jstring build) {
    (void)cls;
    if (build == NULL) return 0;
    const char *name = (*env)->GetStringUTFChars(env, build, NULL);
    if (!name) return 0;
    bst *h = bst_open(name);
    (*env)->ReleaseStringUTFChars(env, build, name);
    if (!h) return 0;
    int rate = bst_rate(h);
    bst_close(h);
    return (jint)rate;
}

JNIEXPORT jint JNICALL Java_org_bestdroid_tts_NativeBst_nativeDefaultParam(
        JNIEnv *env, jclass cls, jstring build, jstring param) {
    (void)cls;
    if (build == NULL || param == NULL) return 0;
    const char *bname = (*env)->GetStringUTFChars(env, build, NULL);
    const char *pname = (*env)->GetStringUTFChars(env, param, NULL);
    if (!bname || !pname) {
        if (bname) (*env)->ReleaseStringUTFChars(env, build, bname);
        if (pname) (*env)->ReleaseStringUTFChars(env, param, pname);
        return 0;
    }
    bst *h = bst_open(bname);
    int value = 0;
    if (h) { value = bst_get(h, pname); bst_close(h); }
    (*env)->ReleaseStringUTFChars(env, build, bname);
    (*env)->ReleaseStringUTFChars(env, param, pname);
    return (jint)value;
}
