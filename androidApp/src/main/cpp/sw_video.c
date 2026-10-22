// H.264/HEVC software decoding for ExoPlayer, through the FFmpeg libmpv ships, for streams the
// device's decoders can't take (10-bit H.264). Frames come out as 8-bit I420 and go to the
// surface as YV12.

#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libavcodec/avcodec.h"
#include "libavutil/frame.h"
#include "libavutil/mem.h"
#include "libswscale/swscale.h"

#define TAG "SoftwareVideo"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define RESULT_OK 0
#define RESULT_SKIP 1
#define RESULT_ERROR (-1)

#define COLORSPACE_UNKNOWN 0
#define COLORSPACE_BT601 1
#define COLORSPACE_BT709 2
#define COLORSPACE_BT2020 3

#define HAL_PIXEL_FORMAT_YV12 0x32315659
#define ALIGN(x, a) (((x) + ((a) - 1)) & ~((a) - 1))

#define AVCODEC(X) \
    X(avcodec_version) X(avcodec_find_decoder_by_name) X(avcodec_alloc_context3) X(avcodec_open2) \
    X(avcodec_free_context) X(avcodec_send_packet) X(avcodec_receive_frame) X(avcodec_flush_buffers) \
    X(av_packet_alloc) X(av_packet_free)
#define AVUTIL(X) X(avutil_version) X(av_frame_alloc) X(av_frame_free) X(av_frame_unref) X(av_mallocz)
#define SWSCALE(X) X(swscale_version) X(sws_getCachedContext) X(sws_scale) X(sws_freeContext)

typedef int32_t (*SetDataSpace)(ANativeWindow *window, int32_t dataSpace);

static struct {
#define MEMBER(name) __typeof__(name) *name;
    AVCODEC(MEMBER)
    AVUTIL(MEMBER)
    SWSCALE(MEMBER)
#undef MEMBER
    SetDataSpace set_data_space;
    int loaded;
} ff;

static int load_ffmpeg(void) {
    if (ff.loaded) return ff.loaded > 0;
    ff.loaded = -1;
    void *avutil = dlopen("libavutil.so", RTLD_NOW);
    void *swscale = dlopen("libswscale.so", RTLD_NOW);
    void *avcodec = dlopen("libavcodec.so", RTLD_NOW);
    if (!avutil || !swscale || !avcodec) {
        LOGW("FFmpeg libraries are missing");
        return 0;
    }
#define RESOLVE(lib, name) if (!(ff.name = dlsym(lib, #name))) { LOGW("missing %s", #name); return 0; }
#define FROM_AVCODEC(name) RESOLVE(avcodec, name)
#define FROM_AVUTIL(name) RESOLVE(avutil, name)
#define FROM_SWSCALE(name) RESOLVE(swscale, name)
    AVCODEC(FROM_AVCODEC)
    AVUTIL(FROM_AVUTIL)
    SWSCALE(FROM_SWSCALE)
#undef FROM_SWSCALE
#undef FROM_AVUTIL
#undef FROM_AVCODEC
#undef RESOLVE
    if ((ff.avcodec_version() >> 16) != LIBAVCODEC_VERSION_MAJOR ||
        (ff.avutil_version() >> 16) != LIBAVUTIL_VERSION_MAJOR ||
        (ff.swscale_version() >> 16) != LIBSWSCALE_VERSION_MAJOR) {
        LOGW("FFmpeg ABI mismatch: avcodec %u avutil %u swscale %u",
             ff.avcodec_version() >> 16, ff.avutil_version() >> 16, ff.swscale_version() >> 16);
        return 0;
    }
    void *android = dlopen("libandroid.so", RTLD_NOW);
    if (android) ff.set_data_space = (SetDataSpace) dlsym(android, "ANativeWindow_setBuffersDataSpace");
    ff.loaded = 1;
    return 1;
}

typedef struct {
    AVCodecContext *codec;
    AVPacket *packet;
    AVFrame *frame;
    int has_frame;
    struct SwsContext *sws;
    ANativeWindow *window;
    jobject surface;
    int window_width;
    int window_height;
    int32_t window_data_space;
    volatile int32_t frame_data_space;
} Decoder;

static int colorspace_of(const AVFrame *frame) {
    switch (frame->colorspace) {
        case AVCOL_SPC_BT709: return COLORSPACE_BT709;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M: return COLORSPACE_BT601;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return COLORSPACE_BT2020;
        default: return frame->height > 576 ? COLORSPACE_BT709 : COLORSPACE_BT601;
    }
}

static int32_t data_space_of(const AVFrame *frame) {
    int32_t standard;
    switch (colorspace_of(frame)) {
        case COLORSPACE_BT2020: standard = 6 << 16; break;
        case COLORSPACE_BT601: standard = (frame->height == 480 ? 4 : 2) << 16; break;
        default: standard = 1 << 16; break;
    }
    int32_t transfer;
    switch (frame->color_trc) {
        case AVCOL_TRC_SMPTE2084: transfer = 7 << 22; break;
        case AVCOL_TRC_ARIB_STD_B67: transfer = 8 << 22; break;
        default: transfer = 3 << 22; break;
    }
    int32_t range = frame->color_range == AVCOL_RANGE_JPEG ? 1 << 27 : 2 << 27;
    return standard | transfer | range;
}

static void release_window(JNIEnv *env, Decoder *d) {
    if (d->window) ANativeWindow_release(d->window);
    if (d->surface) (*env)->DeleteGlobalRef(env, d->surface);
    d->window = NULL;
    d->surface = NULL;
    d->window_width = 0;
    d->window_height = 0;
    d->window_data_space = 0;
}

static void destroy(Decoder *d) {
    if (d->sws) ff.sws_freeContext(d->sws);
    if (d->frame) ff.av_frame_free(&d->frame);
    if (d->packet) ff.av_packet_free(&d->packet);
    if (d->codec) ff.avcodec_free_context(&d->codec);
    free(d);
}

#define METHOD(name) Java_com_nuvio_app_features_player_swdecoder_SoftwareVideoDecoder_##name

JNIEXPORT jboolean JNICALL METHOD(nativeLoad)(JNIEnv *env, jclass clazz) {
    return load_ffmpeg() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL METHOD(nativeCreate)(JNIEnv *env, jclass clazz, jstring codec_name,
                                              jbyteArray extra_data, jint threads) {
    if (!load_ffmpeg()) return 0;
    const char *name = (*env)->GetStringUTFChars(env, codec_name, NULL);
    const AVCodec *codec = ff.avcodec_find_decoder_by_name(name);
    (*env)->ReleaseStringUTFChars(env, codec_name, name);
    if (!codec) return 0;

    Decoder *d = calloc(1, sizeof(Decoder));
    if (!d) return 0;
    d->codec = ff.avcodec_alloc_context3(codec);
    d->packet = ff.av_packet_alloc();
    d->frame = ff.av_frame_alloc();
    if (!d->codec || !d->packet || !d->frame) {
        destroy(d);
        return 0;
    }
    if (extra_data) {
        jsize size = (*env)->GetArrayLength(env, extra_data);
        d->codec->extradata = ff.av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!d->codec->extradata) {
            destroy(d);
            return 0;
        }
        (*env)->GetByteArrayRegion(env, extra_data, 0, size, (jbyte *) d->codec->extradata);
        d->codec->extradata_size = size;
    }
    d->codec->thread_count = threads;
    d->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    d->codec->flags2 |= AV_CODEC_FLAG2_FAST;
    int result = ff.avcodec_open2(d->codec, codec, NULL);
    if (result < 0) {
        LOGW("avcodec_open2 failed: %d", result);
        destroy(d);
        return 0;
    }
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL METHOD(nativeFlush)(JNIEnv *env, jclass clazz, jlong handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    ff.avcodec_flush_buffers(d->codec);
    ff.av_frame_unref(d->frame);
    d->has_frame = 0;
}

JNIEXPORT void JNICALL METHOD(nativeRelease)(JNIEnv *env, jclass clazz, jlong handle) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d) return;
    release_window(env, d);
    destroy(d);
}

JNIEXPORT jint JNICALL METHOD(nativeSendPacket)(JNIEnv *env, jclass clazz, jlong handle,
                                                 jobject data, jint size, jlong time_us) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    uint8_t *bytes = (*env)->GetDirectBufferAddress(env, data);
    if (!bytes) return RESULT_ERROR;
    if (d->has_frame) {
        ff.av_frame_unref(d->frame);
        d->has_frame = 0;
    }
    d->packet->data = bytes;
    d->packet->size = size;
    d->packet->pts = time_us;
    int result = ff.avcodec_send_packet(d->codec, d->packet);
    if (result == AVERROR(EAGAIN)) {
        if (ff.avcodec_receive_frame(d->codec, d->frame) == 0) d->has_frame = 1;
        result = ff.avcodec_send_packet(d->codec, d->packet);
    }
    d->packet->data = NULL;
    d->packet->size = 0;
    if (result == 0) return RESULT_OK;
    if (result == AVERROR_INVALIDDATA || result == AVERROR(EAGAIN)) return RESULT_SKIP;
    LOGW("avcodec_send_packet failed: %d", result);
    return RESULT_ERROR;
}

JNIEXPORT jint JNICALL METHOD(nativeReceiveFrame)(JNIEnv *env, jclass clazz, jlong handle,
                                                   jlongArray info) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d->has_frame) {
        int result = ff.avcodec_receive_frame(d->codec, d->frame);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return RESULT_SKIP;
        if (result < 0) {
            LOGW("avcodec_receive_frame failed: %d", result);
            return RESULT_ERROR;
        }
        d->has_frame = 1;
    }
    AVFrame *frame = d->frame;
    jlong values[4] = {
        frame->pts != AV_NOPTS_VALUE ? frame->pts : frame->best_effort_timestamp,
        frame->width,
        frame->height,
        colorspace_of(frame),
    };
    (*env)->SetLongArrayRegion(env, info, 0, 4, values);
    d->frame_data_space = data_space_of(frame);
    return RESULT_OK;
}

JNIEXPORT jboolean JNICALL METHOD(nativeConvertFrame)(JNIEnv *env, jclass clazz, jlong handle,
                                                       jobject output, jint y_stride, jint uv_stride) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    if (!d->has_frame) return JNI_FALSE;
    AVFrame *frame = d->frame;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, output);
    d->sws = ff.sws_getCachedContext(d->sws, frame->width, frame->height, frame->format,
                                     frame->width, frame->height, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR, NULL, NULL, NULL);
    jboolean converted = JNI_FALSE;
    if (base && d->sws) {
        int uv_height = (frame->height + 1) / 2;
        uint8_t *planes[4] = {
            base,
            base + (size_t) y_stride * frame->height,
            base + (size_t) y_stride * frame->height + (size_t) uv_stride * uv_height,
            NULL,
        };
        int strides[4] = {y_stride, uv_stride, uv_stride, 0};
        converted = ff.sws_scale(d->sws, (const uint8_t *const *) frame->data, frame->linesize, 0,
                                 frame->height, planes, strides) > 0;
    }
    ff.av_frame_unref(frame);
    d->has_frame = 0;
    return converted;
}

JNIEXPORT jint JNICALL METHOD(nativeRender)(JNIEnv *env, jclass clazz, jlong handle, jobject surface,
                                            jobject source, jint width, jint height, jint y_stride,
                                            jint uv_stride) {
    Decoder *d = (Decoder *) (intptr_t) handle;
    const uint8_t *src = (*env)->GetDirectBufferAddress(env, source);
    if (!src) return RESULT_ERROR;
    if (!d->surface || !(*env)->IsSameObject(env, d->surface, surface)) {
        release_window(env, d);
        d->window = ANativeWindow_fromSurface(env, surface);
        if (!d->window) return RESULT_ERROR;
        d->surface = (*env)->NewGlobalRef(env, surface);
    }
    if (d->window_width != width || d->window_height != height) {
        if (ANativeWindow_setBuffersGeometry(d->window, width, height, HAL_PIXEL_FORMAT_YV12)) {
            return RESULT_ERROR;
        }
        d->window_width = width;
        d->window_height = height;
    }
    int32_t data_space = d->frame_data_space;
    if (ff.set_data_space && data_space && d->window_data_space != data_space) {
        ff.set_data_space(d->window, data_space);
        d->window_data_space = data_space;
    }

    ANativeWindow_Buffer buffer;
    int result = ANativeWindow_lock(d->window, &buffer, NULL);
    if (result == -ENODEV) {
        release_window(env, d);
        return RESULT_OK;
    }
    if (result || !buffer.bits) return RESULT_ERROR;

    uint8_t *dst = buffer.bits;
    int rows = height < buffer.height ? height : buffer.height;
    int columns = width < buffer.width ? width : buffer.width;
    for (int y = 0; y < rows; y++) {
        memcpy(dst + (size_t) y * buffer.stride, src + (size_t) y * y_stride, columns);
    }
    int chroma_stride = ALIGN(buffer.stride / 2, 16);
    int chroma_rows = (rows + 1) / 2;
    int chroma_columns = (columns + 1) / 2;
    uint8_t *dst_v = dst + (size_t) buffer.stride * buffer.height;
    uint8_t *dst_u = dst_v + (size_t) chroma_stride * ((buffer.height + 1) / 2);
    const uint8_t *src_u = src + (size_t) y_stride * height;
    const uint8_t *src_v = src_u + (size_t) uv_stride * ((height + 1) / 2);
    for (int y = 0; y < chroma_rows; y++) {
        memcpy(dst_v + (size_t) y * chroma_stride, src_v + (size_t) y * uv_stride, chroma_columns);
        memcpy(dst_u + (size_t) y * chroma_stride, src_u + (size_t) y * uv_stride, chroma_columns);
    }
    return ANativeWindow_unlockAndPost(d->window) ? RESULT_ERROR : RESULT_OK;
}
