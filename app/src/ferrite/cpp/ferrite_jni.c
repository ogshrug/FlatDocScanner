/*
 * JNI bridge between FerriteRunner.kt and the Ferrite runtime.
 *
 * One Model per loaded .onnx file: its graph, a weight arena, and an activation arena
 * sized exactly for the input shape it was last run with. Calls on one model are
 * serialised by its mutex; different models can run at the same time (they share
 * Ferrite's worker pool, which a second caller simply doesn't use while it is busy).
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "types.h"
#include "tensor.h"
#include "allocator.h"
#include "graph.h"
#include "engine.h"
#include "onnx.h"
#include "ops.h"

#define TAG "Ferrite"

typedef struct {
    pthread_mutex_t lock;
    FeGraph *graph;
    unsigned char *weights;
    size_t weights_size, weights_used;
    unsigned char *act;
    size_t act_size;
    FeRuntime rt;
    int ready;                       /* rt initialised for in_shape */
    int in_ndim;
    int in_shape[FERRITE_MAX_DIMS];
} Model;

static void throw_io(JNIEnv *env, const char *what, int status) {
    char msg[160];
    snprintf(msg, sizeof msg, "Ferrite: %s failed (status %d)", what, status);
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT void JNICALL
Java_io_github_ogshrug_flatdocscanner_scan_FerriteNative_setThreads(JNIEnv *env, jclass cls,
                                                                    jint n) {
    (void)env; (void)cls;
    fe_set_num_threads(n);
}

/* model: a direct ByteBuffer holding the .onnx file (an asset mapped from the APK). */
JNIEXPORT jlong JNICALL
Java_io_github_ogshrug_flatdocscanner_scan_FerriteNative_load(JNIEnv *env, jclass cls,
                                                              jobject model) {
    (void)cls;
    const void *data = (*env)->GetDirectBufferAddress(env, model);
    jlong len = (*env)->GetDirectBufferCapacity(env, model);
    if (!data || len <= 0) { throw_io(env, "reading the model buffer", -1); return 0; }

    Model *m = calloc(1, sizeof(Model));
    if (!m) { throw_io(env, "allocating", FE_ERR_NOMEM); return 0; }
    m->graph = calloc(1, sizeof(FeGraph));
    /* Half-precision weights widen to float32 on load, so allow a little over twice
     * the file. Pages that are never touched cost nothing. */
    m->weights_size = (size_t)len * 2 + (8u << 20);
    m->weights = malloc(m->weights_size);
    if (!m->graph || !m->weights) {
        free(m->graph); free(m->weights); free(m);
        throw_io(env, "allocating", FE_ERR_NOMEM);
        return 0;
    }

    FeArena arena;
    fe_arena_init(&arena, m->weights, m->weights_size);
    FeStatus s = fe_onnx_load_buffer(m->graph, &arena, data, (size_t)len);
    if (s != FE_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "model load failed: %d", s);
        free(m->graph); free(m->weights); free(m);
        throw_io(env, "loading the model", s);
        return 0;
    }
    m->weights_used = arena.offset;
    pthread_mutex_init(&m->lock, NULL);
    return (jlong)(intptr_t)m;
}

/* (Re)builds the runtime with an activation buffer sized for this input shape. */
static FeStatus prepare(Model *m, const FeTensor *input) {
    if (m->ready && m->in_ndim == input->ndim &&
        memcmp(m->in_shape, input->shape, sizeof(int) * (size_t)input->ndim) == 0)
        return FE_OK;

    unsigned char probe[64];
    unsigned char *wbuf = m->weights + m->weights_used;
    size_t wsize = m->weights_size - m->weights_used;
    FeStatus s = fe_runtime_init(&m->rt, m->graph, wbuf, wsize, probe, sizeof probe);
    if (s != FE_OK) return s;
    size_t need = 0;
    s = fe_runtime_required_bytes(&m->rt, input, &need);
    if (s != FE_OK) return s;
    need += 4096;                    /* alignment slack */
    if (need > m->act_size) {
        free(m->act);
        m->act = malloc(need);
        m->act_size = m->act ? need : 0;
        if (!m->act) return FE_ERR_NOMEM;
    }
    s = fe_runtime_init(&m->rt, m->graph, wbuf, wsize, m->act, m->act_size);
    if (s != FE_OK) return s;
    m->ready = 1;
    m->in_ndim = input->ndim;
    memcpy(m->in_shape, input->shape, sizeof(int) * (size_t)input->ndim);
    return FE_OK;
}

static int to_dims(JNIEnv *env, jintArray arr, int *dims) {
    jsize n = (*env)->GetArrayLength(env, arr);
    if (n < 1 || n > FERRITE_MAX_DIMS) return -1;
    jint tmp[FERRITE_MAX_DIMS];
    (*env)->GetIntArrayRegion(env, arr, 0, n, tmp);
    for (int i = 0; i < n; i++) dims[i] = tmp[i];
    return n;
}

/*
 * input: a direct FloatBuffer (address taken as is) or null with inputArray set.
 * The result is copied into output, whose length must match outShape.
 */
static void run(JNIEnv *env, Model *m, float *in_data, jintArray inShape,
                jfloatArray output, jintArray outShape) {
    int in_dims[FERRITE_MAX_DIMS], out_dims[FERRITE_MAX_DIMS];
    int in_n = to_dims(env, inShape, in_dims), out_n = to_dims(env, outShape, out_dims);
    if (in_n < 0 || out_n < 0) { throw_io(env, "reading the shapes", FE_ERR_SHAPE); return; }

    FeTensor *in = fe_tensor_from_data(in_data, DTYPE_FLOAT32, in_n, in_dims);
    FeTensor *out = fe_tensor_alloc(DTYPE_FLOAT32, out_n, out_dims);
    if (!in || !out) {
        if (in) fe_tensor_free(in);
        if (out) fe_tensor_free(out);
        throw_io(env, "allocating", FE_ERR_NOMEM);
        return;
    }
    if (fe_tensor_numel(out) != (*env)->GetArrayLength(env, output)) {
        fe_tensor_free(in); fe_tensor_free(out);
        throw_io(env, "matching the output size", FE_ERR_SHAPE);
        return;
    }

    FeStatus s = prepare(m, in);
    if (s == FE_OK) s = fe_runtime_run(&m->rt, in, out);
    if (s == FE_OK)
        (*env)->SetFloatArrayRegion(env, output, 0, fe_tensor_numel(out), (const jfloat *)out->data);
    else
        throw_io(env, "running the model", s);
    fe_tensor_free(in);
    fe_tensor_free(out);
}

JNIEXPORT void JNICALL
Java_io_github_ogshrug_flatdocscanner_scan_FerriteNative_runDirect(
        JNIEnv *env, jclass cls, jlong handle, jobject input, jintArray inShape,
        jfloatArray output, jintArray outShape) {
    (void)cls;
    Model *m = (Model *)(intptr_t)handle;
    float *data = (float *)(*env)->GetDirectBufferAddress(env, input);
    if (!m || !data) { throw_io(env, "reading the input", FE_ERR_NULL); return; }
    pthread_mutex_lock(&m->lock);
    run(env, m, data, inShape, output, outShape);
    pthread_mutex_unlock(&m->lock);
}

JNIEXPORT void JNICALL
Java_io_github_ogshrug_flatdocscanner_scan_FerriteNative_runArray(
        JNIEnv *env, jclass cls, jlong handle, jfloatArray input, jintArray inShape,
        jfloatArray output, jintArray outShape) {
    (void)cls;
    Model *m = (Model *)(intptr_t)handle;
    if (!m) { throw_io(env, "finding the model", FE_ERR_NULL); return; }
    jsize n = (*env)->GetArrayLength(env, input);
    float *copy = malloc(sizeof(float) * (size_t)n);
    if (!copy) { throw_io(env, "allocating", FE_ERR_NOMEM); return; }
    (*env)->GetFloatArrayRegion(env, input, 0, n, copy);
    pthread_mutex_lock(&m->lock);
    run(env, m, copy, inShape, output, outShape);
    pthread_mutex_unlock(&m->lock);
    free(copy);
}

JNIEXPORT void JNICALL
Java_io_github_ogshrug_flatdocscanner_scan_FerriteNative_free(JNIEnv *env, jclass cls,
                                                              jlong handle) {
    (void)env; (void)cls;
    Model *m = (Model *)(intptr_t)handle;
    if (!m) return;
    pthread_mutex_lock(&m->lock);
    free(m->act);
    free(m->weights);
    free(m->graph);
    pthread_mutex_unlock(&m->lock);
    pthread_mutex_destroy(&m->lock);
    free(m);
}
