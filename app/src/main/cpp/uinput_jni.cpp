// JNI bridge for UInputNative — owns backend selection and forwards frames to whichever
// backend was chosen.
//
// Despite the historical class/library name (it is the AIDL-visible identity the app
// already ships), this layer is backend-agnostic: it probes both backends and picks one.
#define LOG_TAG "virtual_input"
#include "output_backend.h"
#include "hid_common.h"

#include <jni.h>
#include <mutex>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>

namespace {

// Binder calls arrive on several threads and the liveness watchdog can tear devices down from
// another, while the backends themselves are single-threaded. Every entry point holds this.
// All backend calls are non-blocking (bar a bounded poll during device creation), so frames
// never wait long behind each other.
std::mutex g_lock;

OutputBackend* g_backend = nullptr;
int g_backend_id = BACKEND_NONE;
char g_detail[256] = "not probed";

struct Candidate {
    int id;
    OutputBackend* (*factory)();
};

// Preference order. uinput comes first so devices where it already works keep exactly the
// behaviour they have today — including force feedback, which the uhid backend cannot
// offer (hid-generic implements no FF for these descriptors). uhid is the fallback that
// also works where /dev/uinput is closed to the shell, e.g. NVIDIA Shield TV.
constexpr Candidate CANDIDATES[] = {
    { BACKEND_UINPUT, uinput_backend },
    { BACKEND_UHID,   uhid_backend   },
};

size_t appendDetail(size_t offset, const char* format, ...) {
    if (offset >= sizeof(g_detail) - 1) return offset;
    va_list args;
    va_start(args, format);
    const int written = vsnprintf(g_detail + offset, sizeof(g_detail) - offset, format, args);
    va_end(args);
    if (written <= 0) return offset;
    const size_t next = offset + (size_t)written;
    return next < sizeof(g_detail) ? next : sizeof(g_detail) - 1;
}

// Probe every candidate so the UI can explain what happened, but only adopt the first
// usable one in candidate order.
void selectBackend() {
    // Whatever the previous backend created must not outlive the switch, or a uinput -> uhid
    // change would leave the old devices registered next to the new ones.
    if (g_backend != nullptr) g_backend->destroyDevices();
    g_backend = nullptr;
    g_backend_id = BACKEND_NONE;

    size_t offset = 0;
    for (const Candidate& candidate : CANDIDATES) {
        OutputBackend* backend = candidate.factory();
        const bool available = backend->probe();

        offset = appendDetail(offset, "%s/dev/%s: %s",
                              offset ? ", " : "", backend->name(),
                              available ? "ok" : backend->probeDetail());

        if (available && g_backend == nullptr) {
            g_backend = backend;
            g_backend_id = candidate.id;
        }
    }

    if (g_backend == nullptr) {
        appendDetail(offset, " — no usable backend");
    }
    LOGI("backend=%s — %s", g_backend ? g_backend->name() : "none", g_detail);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_selectBackend(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    selectBackend();
    return g_backend_id;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_backendDetail(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    // g_detail embeds strerror() text; keep it 7-bit so NewStringUTF never sees invalid
    // modified UTF-8.
    char ascii[sizeof(g_detail)];
    size_t i = 0;
    for (; i < sizeof(ascii) - 1 && g_detail[i] != '\0'; i++) {
        const unsigned char c = (unsigned char)g_detail[i];
        ascii[i] = c < 0x80 ? (char)c : '?';
    }
    ascii[i] = '\0';
    return env->NewStringUTF(ascii);
}

// Whether games can receive rumble through the selected backend.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_supportsRumble(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_backend != nullptr && g_backend->supportsRumble() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_createDevice(JNIEnv*, jclass, jint profileId) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_backend == nullptr) {
        LOGE("createDevice called with no backend selected");
        return JNI_FALSE;
    }
    return g_backend->createDevices(profileId) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_sendFrame(
        JNIEnv*, jclass,
        jint buttons,
        jint lx, jint ly, jint rx, jint ry,
        jint lt, jint rt,
        jint dpadX, jint dpadY) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_backend == nullptr) return;
    g_backend->sendFrame(buttons, lx, ly, rx, ry, lt, rt, dpadX, dpadY);
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_sendMouseFrame(
        JNIEnv*, jclass, jint relX, jint relY, jint scrollY, jint keys) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_backend == nullptr) return;
    g_backend->sendMouseFrame(relX, relY, scrollY, keys);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_pollFFEvent(JNIEnv* env, jclass) {
    int32_t strong = 0;
    int32_t weak = 0;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (g_backend == nullptr) return nullptr;
        if (!g_backend->pollForceFeedback(&strong, &weak)) return nullptr;
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;  // OutOfMemoryError is pending
    const jint values[2] = { strong, weak };
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_destroy(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_backend == nullptr) return;
    g_backend->destroyDevices();
    LOGI("Virtual devices destroyed (%s)", g_backend->name());
}
