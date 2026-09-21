// JNI bridge for UInputNative — owns backend selection and forwards frames to whichever
// backend was chosen.
//
// Despite the historical class/library name (it is the AIDL-visible identity the app
// already ships), this layer is backend-agnostic: it probes both backends and picks one.
#define LOG_TAG "virtual_input"
#include "output_backend.h"
#include "hid_common.h"

#include <jni.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

namespace {

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

// Probe every candidate so the UI can explain what happened, but only adopt one:
// `preferred` of PREF_AUTO accepts anything available, otherwise the requested backend.
void selectBackend(int preferred) {
    g_backend = nullptr;
    g_backend_id = BACKEND_NONE;

    size_t offset = 0;
    for (const Candidate& candidate : CANDIDATES) {
        OutputBackend* backend = candidate.factory();
        const bool available = backend->probe();
        const bool wanted = (preferred == PREF_AUTO) || (preferred == candidate.id);

        offset = appendDetail(offset, "%s/dev/%s: %s",
                              offset ? ", " : "", backend->name(),
                              available ? "ok" : backend->probeDetail());

        if (available && wanted && g_backend == nullptr) {
            g_backend = backend;
            g_backend_id = candidate.id;
        }
    }

    if (g_backend == nullptr) {
        appendDetail(offset, preferred == PREF_AUTO
                                 ? " — no usable backend"
                                 : " — requested backend unavailable");
    }
    LOGI("backend=%s — %s", g_backend ? g_backend->name() : "none", g_detail);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_selectBackend(JNIEnv*, jclass, jint preferred) {
    selectBackend(preferred);
    return g_backend_id;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_currentBackend(JNIEnv*, jclass) {
    return g_backend_id;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_backendDetail(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_detail);
}

// Whether games can receive rumble through the selected backend.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_supportsRumble(JNIEnv*, jclass) {
    return g_backend != nullptr && g_backend->supportsRumble() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_createDevice(JNIEnv*, jclass, jint profileId) {
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
    if (g_backend == nullptr) return;
    g_backend->sendFrame(buttons, lx, ly, rx, ry, lt, rt, dpadX, dpadY);
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_sendMouseFrame(
        JNIEnv*, jclass, jint relX, jint relY, jint scrollY, jint keys) {
    if (g_backend == nullptr) return;
    g_backend->sendMouseFrame(relX, relY, scrollY, keys);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_pollFFEvent(JNIEnv* env, jclass) {
    if (g_backend == nullptr) return nullptr;

    int32_t strong = 0;
    int32_t weak = 0;
    if (!g_backend->pollForceFeedback(&strong, &weak)) return nullptr;

    jintArray result = env->NewIntArray(2);
    const jint values[2] = { strong, weak };
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_destroy(JNIEnv*, jclass) {
    if (g_backend == nullptr) return;
    g_backend->destroyDevices();
    LOGI("Virtual devices destroyed (%s)", g_backend->name());
}
