#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <mutex>
#include <vector>
#include <opencv2/opencv.hpp>
#include "vision/DetectionCore.h"

// JNI boundary. Vision only: nothing here taps, clicks or controls any app.
static va::DetectionCore g_core;
static std::mutex g_mutex;

namespace {
constexpr int kHeader = 24;

jfloatArray pack(JNIEnv* env, const va::FrameResult& r) {
    std::vector<float> v(kHeader + r.history.size() * 2, 0.f);
    v[0]  = float(static_cast<int>(r.status));
    v[1]  = r.measured ? 1.f : 0.f;
    v[2]  = r.target.found ? 1.f : 0.f;
    v[3]  = r.target.bounds.x;      v[4] = r.target.bounds.y;
    v[5]  = r.target.bounds.width;  v[6] = r.target.bounds.height;
    v[7]  = float(r.target.confidence);
    v[8]  = r.velocityPxS.x;        v[9] = r.velocityPxS.y;
    v[10] = r.speedPxS;
    v[11] = r.fps;
    v[12] = r.destination.x;        v[13] = r.destination.y;
    v[14] = r.destinationRadius;
    v[15] = r.insideDestination ? 1.f : 0.f;
    v[16] = r.lantern.found ? 1.f : 0.f;
    v[17] = r.lantern.center.x;     v[18] = r.lantern.center.y;
    v[19] = float(r.lantern.colorCode);
    v[20] = r.movingTowardLantern ? 1.f : 0.f;
    v[21] = float(r.frameW);        v[22] = float(r.frameH);
    v[23] = float(r.history.size());
    for (size_t i = 0; i < r.history.size(); ++i) {
        v[kHeader + 2 * i] = r.history[i].x;
        v[kHeader + 2 * i + 1] = r.history[i].y;
    }
    jfloatArray out = env->NewFloatArray(jsize(v.size()));
    env->SetFloatArrayRegion(out, 0, jsize(v.size()), v.data());
    return out;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_visualautomation_NativeBridge_nativeInit(JNIEnv* env, jobject, jbyteArray encoded) {
    const jsize n = env->GetArrayLength(encoded);
    std::vector<uchar> buf(n);
    env->GetByteArrayRegion(encoded, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    cv::Mat img = cv::imdecode(buf, cv::IMREAD_COLOR);
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_core.loadTemplate(img) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_visualautomation_NativeBridge_nativeConfigure(
        JNIEnv*, jobject, jfloat dx, jfloat dy, jfloat dr,
        jfloat sx, jfloat sy, jfloat sw, jfloat sh,
        jfloat lx, jfloat ly, jfloat lw, jfloat lh) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_core.calibrateDestination({dx, dy}, dr);
    g_core.setSearchRegion(sx, sy, sw, sh);
    g_core.setLanternRegion(lx, ly, lw, lh);
}

JNIEXPORT void JNICALL
Java_com_example_visualautomation_NativeBridge_nativeSetCalibrating(JNIEnv*, jobject, jboolean on) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_core.setCalibrating(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_visualautomation_NativeBridge_nativeReset(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_core.reset();
}

// Live path: RGBA_8888 frame from ImageReader (direct ByteBuffer, row stride in bytes).
JNIEXPORT jfloatArray JNICALL
Java_com_example_visualautomation_NativeBridge_nativeProcess(
        JNIEnv* env, jobject, jobject buffer, jint w, jint h, jint rowStride, jlong tsMs) {
    auto* p = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!p || w <= 0 || h <= 0) return nullptr;
    cv::Mat rgba(h, w, CV_8UC4, p, size_t(rowStride));
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
    std::lock_guard<std::mutex> lock(g_mutex);
    return pack(env, g_core.process(bgr, double(tsMs)));
}

// Static path: used by the calibration screen to test detection on a preview Bitmap.
JNIEXPORT jfloatArray JNICALL
Java_com_example_visualautomation_NativeBridge_nativeProcessBitmap(
        JNIEnv* env, jobject, jobject bitmap, jlong tsMs) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;
    void* px = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    cv::Mat rgba(int(info.height), int(info.width), CV_8UC4, px, size_t(info.stride));
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
    AndroidBitmap_unlockPixels(env, bitmap);
    std::lock_guard<std::mutex> lock(g_mutex);
    return pack(env, g_core.process(bgr, double(tsMs)));
}

}  // extern "C"
