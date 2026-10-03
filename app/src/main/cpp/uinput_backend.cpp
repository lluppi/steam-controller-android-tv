// /dev/uinput backend — upstream's implementation, behaviour unchanged.
//
// One evdev device per virtual device. Needs the calling process (the Shizuku user
// service, running as the shell UID) to be allowed to open /dev/uinput. On some
// device/ROM combinations SELinux denies that — e.g. NVIDIA Shield TV, where
// /dev/uinput belongs to system:bluetooth — in which case uhid_backend takes over.
#define LOG_TAG "uinput_backend"
#include "output_backend.h"
#include "hid_common.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {

constexpr const char* UINPUT_PATH = "/dev/uinput";
constexpr int MAX_FF_EFFECTS = 4;

class UinputBackend : public OutputBackend {
public:
    const char* name() const override { return "uinput"; }
    bool supportsRumble() const override { return true; }
    const char* probeDetail() const override { return detail_; }

    bool probe() override {
        const int fd = ::open(UINPUT_PATH, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            snprintf(detail_, sizeof(detail_), "%s", strerror(errno));
            return false;
        }
        ::close(fd);
        snprintf(detail_, sizeof(detail_), "ok");
        return true;
    }

    bool createDevices(int profileId) override {
        const gamepad_profile& prof = find_profile(profileId);
        LOGI("createDevices: profile=%d (VID=0x%04X PID=0x%04X name=\"%s\")",
             prof.id, prof.vid, prof.pid, prof.name);

        destroyDevices();

        if (prof.mouse_mode) {
            // Desktop: just mouse + keyboard, no gamepad.
            const int mouse_fd = create_mouse_fd(prof.vid, sidecar_mouse_pid(prof));
            if (mouse_fd < 0) return false;
            const int kbd_fd = create_keyboard_fd(prof.vid, sidecar_keyboard_pid(prof), /*full_alpha=*/true);
            if (kbd_fd < 0) {
                ioctl(mouse_fd, UI_DEV_DESTROY);
                close(mouse_fd);
                return false;
            }
            fd_mouse_ = mouse_fd;
            fd_kbd_ = kbd_fd;
            LOGI("Desktop devices created — mouse fd=%d, kbd fd=%d", mouse_fd, kbd_fd);
            return true;
        }

        // Gamepad path — the gamepad itself plus a mouse and a keyboard sidecar. The
        // sidecar pair lets the right trackpad drive a cursor and lets back-paddle
        // mappings hit keyboard keys, while games still see a proper gamepad.
        const int fd = open(UINPUT_PATH, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            LOGE("open %s failed: %s", UINPUT_PATH, strerror(errno));
            return false;
        }

        bool ok = set_bit(fd, UI_SET_EVBIT, EV_KEY)
               && set_bit(fd, UI_SET_EVBIT, EV_ABS)
               && set_bit(fd, UI_SET_EVBIT, EV_SYN)
               && set_bit(fd, UI_SET_EVBIT, EV_FF)
               && set_bit(fd, UI_SET_FFBIT, FF_RUMBLE)
               && set_bit(fd, UI_SET_FFBIT, FF_PERIODIC);
        for (int i = 0; ok && i < XB_COUNT; i++) {
            ok = set_bit(fd, UI_SET_KEYBIT, XBOX_BUTTONS[i].evdev);
        }
        if (ok) {
            ok = setup_abs(fd, ABS_X,     STICK_MIN, STICK_MAX, 16, 128)
              && setup_abs(fd, ABS_Y,     STICK_MIN, STICK_MAX, 16, 128)
              && setup_abs(fd, ABS_RX,    STICK_MIN, STICK_MAX, 16, 128)
              && setup_abs(fd, ABS_RY,    STICK_MIN, STICK_MAX, 16, 128)
              && setup_abs(fd, ABS_Z,     TRIG_MIN,  TRIG_MAX,   0,   0)
              && setup_abs(fd, ABS_RZ,    TRIG_MIN,  TRIG_MAX,   0,   0)
              && setup_abs(fd, ABS_HAT0X, HAT_MIN,   HAT_MAX,    0,   0)
              && setup_abs(fd, ABS_HAT0Y, HAT_MIN,   HAT_MAX,    0,   0);
        }
        if (!ok || finalize_device(fd, prof.vid, prof.pid, prof.name, MAX_FF_EFFECTS) < 0) {
            close(fd);
            return false;
        }

        memset(ff_effects_, 0, sizeof(ff_effects_));
        pending_strong_ = -1;
        pending_weak_ = -1;

        LOGI("Virtual gamepad created (profile=%d), fd=%d", prof.id, fd);
        fd_gamepad_ = fd;

        // Sidecar mouse + keyboard — non-fatal if either fails (gamepad still works).
        fd_mouse_ = create_mouse_fd(prof.vid, sidecar_mouse_pid(prof));
        if (fd_mouse_ < 0) {
            LOGE("Sidecar mouse creation failed — trackpad-as-cursor will be unavailable");
        }
        fd_kbd_ = create_keyboard_fd(prof.vid, sidecar_keyboard_pid(prof), /*full_alpha=*/false);
        if (fd_kbd_ < 0) {
            LOGE("Sidecar keyboard creation failed — key mappings on back paddles will be unavailable");
        }
        LOGI("Gamepad devices ready — gamepad=%d, mouse=%d, kbd=%d", fd_gamepad_, fd_mouse_, fd_kbd_);
        return true;
    }

    void sendFrame(int buttons,
                   int lx, int ly, int rx, int ry,
                   int lt, int rt,
                   int dpadX, int dpadY) override {
        if (fd_gamepad_ < 0) return;

        for (int i = 0; i < XB_COUNT; i++) {
            write_event(fd_gamepad_, EV_KEY, XBOX_BUTTONS[i].evdev, (buttons >> i) & 1);
        }
        write_event(fd_gamepad_, EV_ABS, ABS_X,     lx);
        write_event(fd_gamepad_, EV_ABS, ABS_Y,     ly);
        write_event(fd_gamepad_, EV_ABS, ABS_RX,    rx);
        write_event(fd_gamepad_, EV_ABS, ABS_RY,    ry);
        write_event(fd_gamepad_, EV_ABS, ABS_Z,     lt);
        write_event(fd_gamepad_, EV_ABS, ABS_RZ,    rt);
        write_event(fd_gamepad_, EV_ABS, ABS_HAT0X, dpadX);
        write_event(fd_gamepad_, EV_ABS, ABS_HAT0Y, dpadY);
        write_event(fd_gamepad_, EV_SYN, SYN_REPORT, 0);
    }

    // Mouse + keyboard frame. EV_KEY events are written only when the bit actually
    // changes, and SYN_REPORT only on an fd that emitted something this frame. This is
    // required for IME focus: if the mouse fd reports every frame (300Hz), Android keeps
    // the cursor "active" and routes DPAD events to it instead of the focused IME view.
    void sendMouseFrame(int relX, int relY, int scrollY, int keys) override {
        const int mouse_bits = keys & ((1 << MK_BTN_LEFT_BIT) | (1 << MK_BTN_RIGHT_BIT) | (1 << MK_BTN_MIDDLE_BIT));
        const int kbd_bits   = keys & ((1 << MK_COUNT) - 1);

        if (fd_mouse_ >= 0) {
            bool any_event = false;
            const int changed = mouse_bits ^ last_mouse_buttons_;
            if (changed & (1 << MK_BTN_LEFT_BIT)) {
                write_event(fd_mouse_, EV_KEY, BTN_LEFT, (mouse_bits >> MK_BTN_LEFT_BIT) & 1);
                any_event = true;
            }
            if (changed & (1 << MK_BTN_RIGHT_BIT)) {
                write_event(fd_mouse_, EV_KEY, BTN_RIGHT, (mouse_bits >> MK_BTN_RIGHT_BIT) & 1);
                any_event = true;
            }
            if (changed & (1 << MK_BTN_MIDDLE_BIT)) {
                write_event(fd_mouse_, EV_KEY, BTN_MIDDLE, (mouse_bits >> MK_BTN_MIDDLE_BIT) & 1);
                any_event = true;
            }
            if (relX != 0)    { write_event(fd_mouse_, EV_REL, REL_X,     relX);    any_event = true; }
            if (relY != 0)    { write_event(fd_mouse_, EV_REL, REL_Y,     relY);    any_event = true; }
            if (scrollY != 0) { write_event(fd_mouse_, EV_REL, REL_WHEEL, scrollY); any_event = true; }
            if (any_event) {
                write_event(fd_mouse_, EV_SYN, SYN_REPORT, 0);
                last_mouse_buttons_ = mouse_bits;
            }
        }

        if (fd_kbd_ >= 0) {
            const int changed = kbd_bits ^ last_kbd_keys_;
            if (changed != 0) {
                for (int i = 0; i < MK_COUNT; i++) {
                    if (changed & (1 << i)) {
                        write_event(fd_kbd_, EV_KEY, SIDECAR_KEYS[i].evdev, (kbd_bits >> i) & 1);
                    }
                }
                write_event(fd_kbd_, EV_SYN, SYN_REPORT, 0);
                last_kbd_keys_ = kbd_bits;
            }
        }
    }

    // Drain any pending events from the gamepad fd. We care about:
    //   - UI_FF_UPLOAD: a game uploads an effect. Read the ff_effect, store it by id.
    //   - UI_FF_ERASE:  effect removed by the game. Free the slot.
    //   - EV_FF code=effect_id value=N: play the effect N times (N=0 → stop).
    bool pollForceFeedback(int32_t* strong, int32_t* weak) override {
        if (fd_gamepad_ < 0) return false;

        struct input_event ev;
        while (read(fd_gamepad_, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
            if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
                struct uinput_ff_upload upload;
                memset(&upload, 0, sizeof(upload));
                upload.request_id = ev.value;
                if (ioctl(fd_gamepad_, UI_BEGIN_FF_UPLOAD, &upload) >= 0) {
                    if (upload.effect.type == FF_RUMBLE) {
                        int slot = -1;
                        for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                            if (ff_effects_[i].id == upload.effect.id) { slot = i; break; }
                        }
                        if (slot < 0) {
                            for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                                if (ff_effects_[i].id == 0) { slot = i; break; }
                            }
                        }
                        if (slot >= 0) {
                            ff_effects_[slot].id     = upload.effect.id;
                            ff_effects_[slot].strong = upload.effect.u.rumble.strong_magnitude;
                            ff_effects_[slot].weak   = upload.effect.u.rumble.weak_magnitude;
                        }
                    }
                    upload.retval = 0;
                    ioctl(fd_gamepad_, UI_END_FF_UPLOAD, &upload);
                }
            } else if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
                struct uinput_ff_erase erase;
                memset(&erase, 0, sizeof(erase));
                erase.request_id = ev.value;
                if (ioctl(fd_gamepad_, UI_BEGIN_FF_ERASE, &erase) >= 0) {
                    for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                        if (ff_effects_[i].id == (int)erase.effect_id) {
                            ff_effects_[i] = {};
                            break;
                        }
                    }
                    erase.retval = 0;
                    ioctl(fd_gamepad_, UI_END_FF_ERASE, &erase);
                }
            } else if (ev.type == EV_FF) {
                // Play or stop. ev.code = effect id, ev.value = play count (0 = stop).
                if (ev.value == 0) {
                    pending_strong_ = 0;
                    pending_weak_ = 0;
                } else {
                    for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                        if (ff_effects_[i].id == ev.code) {
                            pending_strong_ = ff_effects_[i].strong;
                            pending_weak_ = ff_effects_[i].weak;
                            break;
                        }
                    }
                }
            }
        }

        if (pending_strong_ < 0) return false;
        *strong = pending_strong_;
        *weak = pending_weak_;
        pending_strong_ = -1;
        pending_weak_ = -1;
        return true;
    }

    void destroyDevices() override {
        bool destroyed = false;
        int* const devices[] = { &fd_gamepad_, &fd_mouse_, &fd_kbd_ };
        for (int* fd : devices) {
            if (*fd < 0) continue;
            ioctl(*fd, UI_DEV_DESTROY);
            close(*fd);
            *fd = -1;
            destroyed = true;
        }
        // Any cached delta-state belonged to the destroyed device — reset.
        last_mouse_buttons_ = 0;
        last_kbd_keys_ = 0;
        if (destroyed) wait_for_input_records_to_settle();
    }

private:
    // Slot for one stored FF effect — we only track FF_RUMBLE for now.
    struct ff_slot {
        int id;
        uint16_t strong;  // left motor magnitude
        uint16_t weak;    // right motor magnitude
    };

    int fd_gamepad_ = -1;
    int fd_mouse_ = -1;
    int fd_kbd_ = -1;
    int last_mouse_buttons_ = 0;
    int last_kbd_keys_ = 0;
    ff_slot ff_effects_[MAX_FF_EFFECTS] = {};
    int32_t pending_strong_ = -1;
    int32_t pending_weak_ = -1;
    char detail_[64] = "not probed";

    static bool set_bit(int fd, unsigned long request, int bit) {
        if (ioctl(fd, request, bit) < 0) {
            LOGE("ioctl 0x%lx bit=%d failed: %s", request, bit, strerror(errno));
            return false;
        }
        return true;
    }

    static bool setup_abs(int fd, int code, int min, int max, int fuzz, int flat) {
        if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return false;
        struct uinput_abs_setup s;
        memset(&s, 0, sizeof(s));
        s.code = code;
        s.absinfo.minimum = min;
        s.absinfo.maximum = max;
        s.absinfo.fuzz    = fuzz;
        s.absinfo.flat    = flat;
        if (ioctl(fd, UI_ABS_SETUP, &s) < 0) {
            LOGE("UI_ABS_SETUP code=%d failed: %s", code, strerror(errno));
            return false;
        }
        return true;
    }

    static bool write_event(int fd, uint16_t type, uint16_t code, int32_t value) {
        struct input_event ev;
        memset(&ev, 0, sizeof(ev));
        ev.type  = type;
        ev.code  = code;
        ev.value = value;
        if (write(fd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
            LOGE("write event t=%d c=%d v=%d failed: %s", type, code, value, strerror(errno));
            return false;
        }
        return true;
    }

    // Finalise a uinput device with the given identity strings and create it.
    static int finalize_device(int fd, uint16_t vid, uint16_t pid, const char* name,
                               uint32_t ff_effects_max) {
        struct uinput_setup us;
        memset(&us, 0, sizeof(us));
        us.id.bustype = BUS_USB;
        us.id.vendor  = vid;
        us.id.product = pid;
        us.id.version = 0x0114;
        us.ff_effects_max = ff_effects_max;
        strncpy(us.name, name, UINPUT_MAX_NAME_SIZE - 1);
        if (ioctl(fd, UI_DEV_SETUP, &us) < 0) {
            LOGE("UI_DEV_SETUP failed: %s", strerror(errno));
            return -1;
        }
        if (ioctl(fd, UI_DEV_CREATE) < 0) {
            LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
            return -1;
        }
        return 0;
    }

    // Pure mouse device: EV_REL + 3 mouse buttons.
    static int create_mouse_fd(uint16_t vid, uint16_t pid) {
        const int fd = open(UINPUT_PATH, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) { LOGE("open %s (mouse) failed: %s", UINPUT_PATH, strerror(errno)); return -1; }
        const bool ok = set_bit(fd, UI_SET_EVBIT,  EV_KEY)
                     && set_bit(fd, UI_SET_EVBIT,  EV_REL)
                     && set_bit(fd, UI_SET_EVBIT,  EV_SYN)
                     && set_bit(fd, UI_SET_RELBIT, REL_X)
                     && set_bit(fd, UI_SET_RELBIT, REL_Y)
                     && set_bit(fd, UI_SET_RELBIT, REL_WHEEL)
                     && set_bit(fd, UI_SET_KEYBIT, BTN_LEFT)
                     && set_bit(fd, UI_SET_KEYBIT, BTN_RIGHT)
                     && set_bit(fd, UI_SET_KEYBIT, BTN_MIDDLE);
        if (!ok || finalize_device(fd, vid, pid, "Steam Controller Mouse", 0) < 0) {
            close(fd);
            return -1;
        }
        return fd;
    }

    // Pure keyboard device (EV_KEY only).
    //
    // `full_alpha` declares the full A-Z/0-9 alphabet so Android's EventHub classifies
    // this device as INPUT_DEVICE_CLASS_ALPHAKEY + DPAD (needed on Android TV so the
    // Leanback IME routes DPAD navigation correctly). ONLY pass true for the Desktop-mode
    // keyboard. Passing true for the gamepad-mode sidecar makes Android believe a real
    // hardware QWERTY keyboard is attached at all times, which suppresses the on-screen
    // keyboard for every text field system-wide while the controller is connected — the
    // gamepad already exposes its own ABS_HAT dpad, so the sidecar doesn't need this
    // trick, only the small SIDECAR_KEYS set used by back-paddle key mappings.
    //
    // The uhid backend has to express the same distinction in its descriptor instead
    // (see UHID_KBD_DESKTOP_RD / UHID_KBD_MINIMAL_RD in hid_descriptors.h).
    static int create_keyboard_fd(uint16_t vid, uint16_t pid, bool full_alpha) {
        const int fd = open(UINPUT_PATH, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) { LOGE("open %s (kbd) failed: %s", UINPUT_PATH, strerror(errno)); return -1; }

        bool ok = set_bit(fd, UI_SET_EVBIT, EV_KEY) && set_bit(fd, UI_SET_EVBIT, EV_SYN);
        for (int i = 0; ok && i < MK_COUNT; i++) {
            ok = set_bit(fd, UI_SET_KEYBIT, SIDECAR_KEYS[i].evdev);
        }

        // Declare the full alphabet + digits so Android's EventHub classifies this as
        // INPUT_DEVICE_CLASS_ALPHAKEY (cheap test: KEY_Q present).
        //
        // Also declare KEY_SELECT (Linux 353) — Generic.kl maps it to DPAD_CENTER, which
        // is the 5th key needed for INPUT_DEVICE_CLASS_DPAD. Without DPAD class the source
        // is SOURCE_KEYBOARD only, and the Leanback IME on Android TV source-filters DPAD
        // navigation to SOURCE_DPAD — that's why arrow presses worked outside the IME but
        // not on the soft keyboard. (On the uhid side this key arrives as consumer usage
        // "Menu Pick", see SIDECAR_KEYS[MK_DPAD_CENTER].)
        if (ok && full_alpha) {
            const int alpha_keys[] = {
                KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J,
                KEY_K, KEY_L, KEY_M, KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T,
                KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z,
                KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9,
                KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTCTRL, KEY_LEFTALT, KEY_CAPSLOCK,
                KEY_COMMA, KEY_DOT, KEY_SLASH, KEY_SEMICOLON, KEY_APOSTROPHE,
                KEY_MINUS, KEY_EQUAL,
                KEY_SELECT,
            };
            for (int k : alpha_keys) {
                if (!set_bit(fd, UI_SET_KEYBIT, k)) { ok = false; break; }
            }
        }

        // PID +1 keeps a stable, distinct identity vs the mouse half.
        if (!ok || finalize_device(fd, vid, pid, "Steam Controller Keyboard", 0) < 0) {
            close(fd);
            return -1;
        }
        return fd;
    }
};

UinputBackend g_backend;

}  // namespace

OutputBackend* uinput_backend() { return &g_backend; }
