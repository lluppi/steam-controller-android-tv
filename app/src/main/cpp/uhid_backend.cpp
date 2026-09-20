// /dev/uhid backend — presents real HID devices from userspace.
//
// Instead of declaring evdev devices directly (as uinput does), this writes HID report
// descriptors to /dev/uhid and then streams HID reports. The kernel binds hid-generic to
// each descriptor and that produces genuine InputDevices, so Android sees exactly the
// same class of device uinput would have created — without needing /dev/uinput, which is
// what makes the controller usable on devices where that node belongs to
// system:bluetooth and the Shizuku user service runs as the shell UID.
//
// Device layout mirrors the uinput backend 1:1 (gamepad + mouse + keyboard, see
// hid_descriptors.h for why they cannot be merged into one device).
#define LOG_TAG "uhid_backend"
#include "output_backend.h"
#include "hid_descriptors.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/uhid.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace {

constexpr const char* UHID_PATH = "/dev/uhid";

// The kernel rejects writes smaller than sizeof(uhid_event) - UHID_DATA_MAX, so every
// event goes out at full struct size. It is one copy per frame either way.
constexpr size_t UHID_EVENT_SIZE = sizeof(uhid_event);

struct UhidDevice {
    int fd = -1;
    const char* kind = nullptr;  // "gamepad" | "mouse" | "keyboard"

    bool open() const { return fd >= 0; }
};

void put_le16(uint8_t* dst, int value) {
    dst[0] = (uint8_t)(value & 0xFF);
    dst[1] = (uint8_t)((value >> 8) & 0xFF);
}

class UhidBackend : public OutputBackend {
public:
    const char* name() const override { return "uhid"; }
    const char* probeDetail() const override { return detail_; }

    bool probe() override {
        const int fd = ::open(UHID_PATH, O_RDWR | O_CLOEXEC);
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

        const bool desktop = prof.mouse_mode;

        if (!desktop) {
            if (!create(gamepad_, "gamepad", prof.name, prof.vid, prof.pid,
                        UHID_GAMEPAD_RD, sizeof(UHID_GAMEPAD_RD))) {
                LOGE("gamepad device creation failed");
                destroyDevices();
                return false;
            }
        }

        // Mouse + keyboard. In Desktop mode both are essential; in gamepad mode they are
        // sidecars, so a failure is logged but doesn't take the gamepad down with it.
        const bool mouse_ok = create(mouse_, "mouse", "Steam Controller Mouse",
                                     prof.vid, (uint16_t)(prof.pid + 0x100),
                                     UHID_MOUSE_RD, sizeof(UHID_MOUSE_RD));
        if (!mouse_ok) {
            LOGE("mouse device creation failed — trackpad-as-cursor will be unavailable");
        }

        const uint8_t* kbd_rd = desktop ? UHID_KBD_DESKTOP_RD : UHID_KBD_MINIMAL_RD;
        const size_t kbd_rd_size = desktop ? sizeof(UHID_KBD_DESKTOP_RD) : sizeof(UHID_KBD_MINIMAL_RD);
        const bool kbd_ok = create(kbd_, "keyboard", "Steam Controller Keyboard",
                                   prof.vid, (uint16_t)(prof.pid + 0x200), kbd_rd, kbd_rd_size);
        if (!kbd_ok) {
            LOGE("keyboard device creation failed — key mappings will be unavailable");
        }

        if (desktop && !(mouse_ok && kbd_ok)) {
            destroyDevices();
            return false;
        }

        LOGI("devices ready — gamepad=%d, mouse=%d, keyboard=%d", gamepad_.fd, mouse_.fd, kbd_.fd);
        return true;
    }

    void sendFrame(int buttons,
                   int lx, int ly, int rx, int ry,
                   int lt, int rt,
                   int dpadX, int dpadY) override {
        drainEvents();
        if (!gamepad_.open()) return;

        uint8_t report[UHID_GAMEPAD_REPORT_SIZE] = {};
        for (int i = 0; i < XB_COUNT; i++) {
            if ((buttons >> i) & 1) {
                const uint8_t index = XBOX_BUTTONS[i].hid_index - 1;  // 0-based bit
                report[index / 8] |= (uint8_t)(1 << (index % 8));
            }
        }
        report[2] = hat_value(dpadX, dpadY);  // low nibble; high nibble stays 0
        put_le16(report + 3,  lx);
        put_le16(report + 5,  ly);
        put_le16(report + 7,  rx);
        put_le16(report + 9,  ry);
        report[11] = (uint8_t)lt;
        report[12] = (uint8_t)rt;
        sendReport(gamepad_, report, sizeof(report));
    }

    void sendMouseFrame(int relX, int relY, int scrollY, int keys) override {
        drainEvents();

        const int kbd_bits = keys & ((1 << MK_COUNT) - 1);

        if (mouse_.open()) {
            uint8_t report[UHID_MOUSE_REPORT_SIZE] = {};
            if (keys & (1 << MK_BTN_LEFT_BIT))   report[0] |= 1 << 0;
            if (keys & (1 << MK_BTN_RIGHT_BIT))  report[0] |= 1 << 1;
            if (keys & (1 << MK_BTN_MIDDLE_BIT)) report[0] |= 1 << 2;
            put_le16(report + 1, relX);
            put_le16(report + 3, relY);
            report[5] = (uint8_t)coerce_i8(scrollY);
            sendReport(mouse_, report, sizeof(report));
        }

        if (kbd_.open()) {
            // Keyboard page keys go into the report's key array (one slot per held key).
            uint8_t kbd[UHID_KBD_REPORT_SIZE] = {};
            kbd[0] = UHID_REPORT_ID_KEYBOARD;
            int slot = 3;  // 0 = report id, 1 = modifiers, 2 = reserved
            for (int i = 0; i < MK_COUNT && slot < 3 + UHID_KBD_ARRAY_SLOTS; i++) {
                if (!(kbd_bits & (1 << i))) continue;
                const sidecar_key& key = SIDECAR_KEYS[i];
                if (!key.consumer) kbd[slot++] = key.code;
            }
            sendReport(kbd_, kbd, sizeof(kbd));

            // Consumer page keys are individual bits.
            uint8_t consumer[UHID_CONSUMER_REPORT_SIZE] = { UHID_REPORT_ID_CONSUMER, 0 };
            for (int i = 0; i < MK_COUNT; i++) {
                const sidecar_key& key = SIDECAR_KEYS[i];
                if (key.consumer && (kbd_bits & (1 << i))) {
                    consumer[1] |= (uint8_t)(1 << key.code);
                }
            }
            sendReport(kbd_, consumer, sizeof(consumer));
        }
    }

    // hid-generic implements no force feedback for either descriptor, so a game's FF
    // requests never reach us and there is nothing to forward. Events are still drained
    // here because this is called steadily (50Hz) even when no frames are flowing, and a
    // reply the kernel is waiting on would otherwise be dropped once its single event
    // slot is occupied.
    bool pollForceFeedback(int32_t* strong, int32_t* weak) override {
        (void)strong;
        (void)weak;
        drainEvents();
        return false;
    }

    void destroyDevices() override {
        bool destroyed = false;
        for (UhidDevice* dev : devices_) {
            if (!dev->open()) continue;
            uhid_event ev;
            memset(&ev, 0, sizeof(ev));
            ev.type = UHID_DESTROY;
            if (::write(dev->fd, &ev, UHID_EVENT_SIZE) != (ssize_t)UHID_EVENT_SIZE) {
                LOGE("%s: UHID_DESTROY failed: %s", dev->kind, strerror(errno));
            }
            ::close(dev->fd);
            dev->fd = -1;
            destroyed = true;
        }
        if (destroyed) wait_for_input_records_to_settle();
    }

private:
    UhidDevice gamepad_;
    UhidDevice mouse_;
    UhidDevice kbd_;
    // The device set, in creation order. Creation, event draining and teardown all iterate
    // this, so adding a device stays a one-line change instead of three parallel edits.
    UhidDevice* const devices_[3] = { &gamepad_, &mouse_, &kbd_ };
    char detail_[64] = "not probed";

    // HID hat: 1..8 clockwise from up, 0 = centred (null state). Inputs are in the same
    // convention as the uinput backend's ABS_HAT0 values (Y negative = up).
    static uint8_t hat_value(int hx, int hy) {
        if (hx == 0 && hy == 0) return 0;
        if (hx == 0) return (hy < 0) ? 1 : 5;
        if (hy == 0) return (hx > 0) ? 3 : 7;
        if (hy < 0)  return (hx > 0) ? 2 : 8;
        return (hx > 0) ? 4 : 6;
    }

    static int coerce_i8(int value) {
        if (value > 127) return 127;
        if (value < -127) return -127;
        return value;
    }

    bool create(UhidDevice& dev, const char* kind, const char* name,
                uint16_t vid, uint16_t pid, const uint8_t* rd, size_t rd_size) {
        const int fd = ::open(UHID_PATH, O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            LOGE("%s: open %s failed: %s", kind, UHID_PATH, strerror(errno));
            return false;
        }

        uhid_event ev;
        memset(&ev, 0, sizeof(ev));
        ev.type = UHID_CREATE2;
        snprintf(reinterpret_cast<char*>(ev.u.create2.name), sizeof(ev.u.create2.name), "%s", name);
        snprintf(reinterpret_cast<char*>(ev.u.create2.phys), sizeof(ev.u.create2.phys), "android/uhid");
        snprintf(reinterpret_cast<char*>(ev.u.create2.uniq), sizeof(ev.u.create2.uniq), "%s/%s", kind, name);
        ev.u.create2.rd_size = (uint16_t)rd_size;
        ev.u.create2.bus     = BUS_USB;
        ev.u.create2.vendor  = vid;
        ev.u.create2.product = pid;
        ev.u.create2.version = 0x0114;
        ev.u.create2.country = 0;
        memcpy(ev.u.create2.rd_data, rd, rd_size);

        if (::write(fd, &ev, UHID_EVENT_SIZE) != (ssize_t)UHID_EVENT_SIZE) {
            LOGE("%s: UHID_CREATE2 failed: %s", kind, strerror(errno));
            ::close(fd);
            return false;
        }

        dev.fd = fd;
        dev.kind = kind;
        LOGI("%s: created (VID=0x%04X PID=0x%04X name=\"%s\", %zu-byte descriptor)",
             kind, vid, pid, name, rd_size);

        consumeStart(dev);
        return true;
    }

    // The kernel reports UHID_START once the device exists. Draining it before the first
    // report keeps that report from racing the device's creation. A timeout is not fatal.
    void consumeStart(UhidDevice& dev) {
        struct pollfd pfd = { dev.fd, POLLIN, 0 };
        if (::poll(&pfd, 1, 500) <= 0) {
            LOGI("%s: no UHID_START within 500ms (continuing)", dev.kind);
            return;
        }
        uhid_event ev;
        const ssize_t got = ::read(dev.fd, &ev, sizeof(ev));
        if (got < (ssize_t)sizeof(uint32_t)) {
            LOGE("%s: short read while waiting for UHID_START", dev.kind);
            return;
        }
        if (ev.type == UHID_START) {
            LOGI("%s: UHID_START (dev_flags=0x%llX)", dev.kind, (unsigned long long)ev.u.start.dev_flags);
        } else {
            handleEvent(dev, ev);
        }
    }

    bool sendReport(const UhidDevice& dev, const uint8_t* data, size_t len) {
        uhid_event ev;
        memset(&ev, 0, sizeof(ev));
        ev.type = UHID_INPUT2;
        ev.u.input2.size = (uint16_t)len;
        memcpy(ev.u.input2.data, data, len);
        if (::write(dev.fd, &ev, UHID_EVENT_SIZE) != (ssize_t)UHID_EVENT_SIZE) {
            LOGE("%s: UHID_INPUT2 failed: %s", dev.kind, strerror(errno));
            return false;
        }
        return true;
    }

    // Drain everything the kernel has queued for us. Called from the frame path rather
    // than from a reader thread, which keeps this backend single-threaded and lock-free:
    // frames arrive at 50-300Hz, far faster than the kernel needs a reply.
    void drainEvents() {
        for (UhidDevice* dev : devices_) {
            if (!dev->open()) continue;
            for (;;) {
                struct pollfd pfd = { dev->fd, POLLIN, 0 };
                if (::poll(&pfd, 1, 0) <= 0) break;
                uhid_event ev;
                const ssize_t got = ::read(dev->fd, &ev, sizeof(ev));
                if (got < (ssize_t)sizeof(uint32_t)) break;
                handleEvent(*dev, ev);
            }
        }
    }

    void handleEvent(const UhidDevice& dev, const uhid_event& ev) {
        switch (ev.type) {
            case UHID_START:
                LOGI("%s: UHID_START (dev_flags=0x%llX)", dev.kind, (unsigned long long)ev.u.start.dev_flags);
                break;
            case UHID_STOP:
                LOGI("%s: UHID_STOP", dev.kind);
                break;
            case UHID_OPEN:
                LOGI("%s: UHID_OPEN (Android attached the input device)", dev.kind);
                break;
            case UHID_CLOSE:
                LOGI("%s: UHID_CLOSE", dev.kind);
                break;
            case UHID_GET_REPORT: {
                uhid_event reply;
                memset(&reply, 0, sizeof(reply));
                reply.type = UHID_GET_REPORT_REPLY;
                reply.u.get_report_reply.id = ev.u.get_report.id;
                reply.u.get_report_reply.err = (uint16_t)(-EIO);
                if (::write(dev.fd, &reply, UHID_EVENT_SIZE) != (ssize_t)UHID_EVENT_SIZE) {
                    LOGE("%s: GET_REPORT reply failed: %s", dev.kind, strerror(errno));
                }
                break;
            }
            case UHID_SET_REPORT: {
                // Ack and ignore: none of our descriptors declare an output report the
                // host is required to act on (rumble would be the one, see PLAN P6).
                uhid_event reply;
                memset(&reply, 0, sizeof(reply));
                reply.type = UHID_SET_REPORT_REPLY;
                reply.u.set_report_reply.id = ev.u.set_report.id;
                reply.u.set_report_reply.err = 0;
                if (::write(dev.fd, &reply, UHID_EVENT_SIZE) != (ssize_t)UHID_EVENT_SIZE) {
                    LOGE("%s: SET_REPORT reply failed: %s", dev.kind, strerror(errno));
                }
                break;
            }
            case UHID_OUTPUT:
                // An output report from a HID driver — the hook rumble would arrive on.
                // Nothing consumes it yet; logged so the P6 spike has something to read.
                LOGI("%s: UHID_OUTPUT (rtype=%u, %u bytes)", dev.kind, ev.u.output.rtype, ev.u.output.size);
                break;
            default:
                LOGI("%s: unhandled event type %u", dev.kind, ev.type);
                break;
        }
    }
};

UhidBackend g_backend;

}  // namespace

OutputBackend* uhid_backend() { return &g_backend; }
