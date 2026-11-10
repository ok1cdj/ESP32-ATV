/* ESP32-C5 receiver for the 6 cm band (Waveshare ESP32-C5-Zero, 48 MHz crystal): first step, captures for the bench.
 *
 * Receive path as fedaykinofdune/esp32-C5-sdr-tx (GPL-3, esp_sdr_rx.c, esp_sdr_paced.c, doc/hardware-quirks.md):
 *   - tune with phy_set_chanfreq(MHz, 0) (the 5 GHz path from 4500 MHz) plus phy_set_rf_freq_offset(phy_param[49], MHz, kHz);
 *   - front end to RX: phy_stop_tx_tone(1), pbus work mode, TX off, RX on, RX clock on, a forced gain (force_rx_gain);
 *   - the IQ estimator (IQE): running sums of the 10-bit ADC I and Q at 80 MHz (CTRL0 0x600a044c mode 27:26 = 1, CTRL1
 *     0x600a0450 source 20:19 = 2, count 16:2, start bit 1, enable bit 0; sums at 0x600a0464 / 0x600a0468, >> 6). The difference of
 *     two reads is a boxcar of the samples in between: read at CAP_RATE, a sample is the sum of 80 MHz / CAP_RATE ADC samples.
 *     A run ends after 32768 inputs (409.6 us), so it is restarted every IQE_REARM samples, right after a read (see capture()).
 *
 * Commands (USB Serial/JTAG, one per line):
 *   F 5715.000        LO in MHz (kHz with up to three decimals, 2300-5900)
 *   G n               forced RX gain index (0-89, default 50)
 *   ANT 1|0           GPIO26 RF switch of the C5-Zero: 1 = U.FL (default), 0 = chip antenna
 *   CAP n             n complex samples (up to CAP_MAX) at CAP_RATE: "CAPD n rate late_max rearms\n", then n int16 I, Q pairs
 *   STREAM r2 fmt sh s  packets as the ESP32-S3 receiver sends them (E5 5D header, Q inverted: LO - RF) at 1 Msps / r2 (r2 2, 3, 4;
 *                     25: 400 ksps),
 *                     fmt 0 cs16 / 1 cs8 / 2 cs4, output >> sh (not cs4), for s seconds (0: until any byte), then "END ..."
 *                     (bench: no S3 protocol)
 *   BENCH             diagnostics: cycles of an IQE read, USB register reads, a halfband output, rdcycle
 *   USBC / USBT s     diagnostics: cost of USB FIFO stores (44 cycles a byte into an empty FIFO) / raw USB throughput for s seconds
 *   NOUSB 1|0         diagnostics: STREAM without touching the USB FIFO
 *   ST                state
 *
 * The ESP32-S3 receiver's binary control protocol (third_party/ESP32-DATV-RX/esp32-sdr-trx/firmware/protocol/control.h,
 * narrowband.h) is understood next to these, so that its hosts (espdr nb.py, rx_dvbs2.py, the Android app) can drive the C5:
 * requests B4 op arg seq crc32, responses B5 1 op status seq 0 value crc32. Differences from the S3:
 *   - ESP_SET_LO: Hz modulo 2^32 (5710 MHz does not fit 32 bits; a value below 2 GHz is taken as + 2^32), 2300-5900 MHz, kHz
 *     steps; the reply carries the LO in the same encoding;
 *   - ESP_SET_GAIN: the selector 0..127 is the C5's forced RX gain index, clamped to 89;
 *   - NB_SET_DECIM also takes 25 (R2_400K: 400 ksps, carried in the packet header as 25); 333 ksps keeps up, 400 ksps is the
 *     fast path (see stream_body), 500 ksps does not; NB_BENCH is unknown;
 *   - text lines other than the commands above are ignored (the Android app probes for a C3 with "INFO");
 *   - CTL_INFO 3 (build flags) has CTL_BUILD_C5 next to NARROWBAND: hosts pick cs4 at 250 / 333 ksps for it, and 400 ksps when
 *     CTL_BUILD_400K is set too.
 * 2026-11-10, 5710 MHz, C5 TX beacon 125 kBd (tx/c5cw) over the air: STREAM 4 2 (250 ksps cs4) decoded by rx/raw2cf32.py --decode,
 * 65 of 98 frames clean from a cold transmitter (later: 64 of 64, the count was wrong). 333 ksps cs4 keeps up, 500 ksps does not
 * (CPU), cs8 at 250 ksps hits the USB limit; 400 ksps (fast path) untested.
 */
#include <inttypes.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_cpu.h"
#include "esp_event.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "hal/usb_serial_jtag_ll.h"
#include "nvs_flash.h"
#include "esp_mac.h"
#include "esp_rom_crc.h"
#include "soc/soc.h"

#include "fir_taps.h"                /* tools/design_taps.py */

extern void phy_set_chanfreq(unsigned mhz, unsigned mode);
extern void phy_set_rf_freq_offset(unsigned xtal, unsigned mhz, int khz);
extern void phy_stop_tx_tone(unsigned en);
extern void phy_pbus_workmode(void);
extern void phy_pbus_xpd_tx_off(void);
extern void phy_pbus_xpd_rx_on(unsigned en);
extern void phy_set_rxclk_en(unsigned en);
extern void force_rx_gain(unsigned force, unsigned index, unsigned bt);
extern unsigned char phy_param[];
#define PHY_PARAM_XTAL 49

#define CPU_HZ 240000000u
#define IQE_CTRL0_REG 0x600a044cu
#define IQE_CTRL0_MODE (3u << 26)
#define IQE_CTRL1_REG 0x600a0450u
#define IQE_CTRL1_SRC (3u << 19)
#define IQE_CTRL1_COUNT (0x7fffu << 2)
#define IQE_CTRL1_START (1u << 1)
#define IQE_CTRL1_EN (1u << 0)
#define IQE_SUM_I_REG 0x600a0464u
#define IQE_SUM_Q_REG 0x600a0468u
#define AGC_FORCE_REG 0x600a702cu
#define ANT_GPIO 26

#define FMIN_KHZ 2300000u
#define FMAX_KHZ 5900000u
#define CAP_RATE 2000000u            /* 120 CPU cycles, 40 ADC samples per sample */
#define CAP_MAX 32768u
#define IQE_REARM 512u               /* samples per estimator run: 256 us of its 409.6 us */

static uint32_t fkhz = 5715000, gain = 50, ant = 1, ctrl1;   /* boards.sh ATV6_FREQ */
static uint8_t *fifo;                /* stream output (STREAM_FIFO bytes), from the heap at boot */

/* ------------------------------------------------------------ USB */

static void usb_write(const void *data, size_t n) {
    const uint8_t *p = data;
    int64_t deadline = esp_timer_get_time() + 500000;
    while (n) {
        if (usb_serial_jtag_ll_txfifo_writable()) {
            int w = usb_serial_jtag_ll_write_txfifo(p, n > 64 ? 64 : n);
            usb_serial_jtag_ll_txfifo_flush();
            p += w;
            n -= w;
            deadline = esp_timer_get_time() + 500000;
        } else if (esp_timer_get_time() > deadline) {
            return;                  /* nobody reads */
        }
    }
}

static void say(const char *fmt, ...) {
    char b[200];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b, sizeof(b), fmt, ap);
    va_end(ap);
    if (n > 0) usb_write(b, n < (int)sizeof(b) ? (size_t)n : sizeof(b) - 1);
}

/* ------------------------------------------------------------ radio */

static void rx_prepare(void) {
    const uint32_t mhz = fkhz / 1000, khz = fkhz % 1000;
    phy_set_chanfreq(mhz, 0);
    if (khz) phy_set_rf_freq_offset(phy_param[PHY_PARAM_XTAL], mhz, (int)khz);
    phy_stop_tx_tone(1);
    phy_pbus_workmode();
    phy_pbus_xpd_tx_off();
    phy_pbus_xpd_rx_on(1);
    phy_set_rxclk_en(1);
    force_rx_gain(1, gain, 0);
    gpio_set_level(ANT_GPIO, ant);
    REG_WRITE(IQE_CTRL0_REG, (REG_READ(IQE_CTRL0_REG) & ~IQE_CTRL0_MODE) | (1u << 26));
    ctrl1 = (REG_READ(IQE_CTRL1_REG) & ~(IQE_CTRL1_SRC | IQE_CTRL1_COUNT | IQE_CTRL1_START | IQE_CTRL1_EN)) | (2u << 19) |
            IQE_CTRL1_COUNT;
    REG_WRITE(IQE_CTRL1_REG, ctrl1);
    esp_rom_delay_us(3000);          /* PLL and front end settle */
}

static inline void IRAM_ATTR iqe_start(void) {
    REG_WRITE(IQE_CTRL1_REG, ctrl1 | IQE_CTRL1_EN);
    REG_WRITE(IQE_CTRL1_REG, ctrl1 | IQE_CTRL1_EN | IQE_CTRL1_START);
}

typedef struct {
    uint32_t late_max, rearms;
} cap_stats_t;

/* n samples into buf (I, Q int16). Q is read before and after I, their mean is taken at the instant I is (no I/Q skew).
 * After a restart the estimator's sums start late by an unknown fraction of a period: the read in the slot after the restart only
 * takes the new base, and that sample is filled in from its neighbours (one in IQE_REARM). */
static void __attribute__((noinline)) IRAM_ATTR capture(int16_t *buf, uint32_t n, cap_stats_t *st) {
    const uint32_t step = CPU_HZ / CAP_RATE;
    uint32_t late_max = 0, rearms = 0, left = IQE_REARM;
    int32_t pi = 0, pq = 0;
    bool base = true;                                         /* this read only takes the base */
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    REG_WRITE(IQE_CTRL1_REG, ctrl1 | IQE_CTRL1_EN);
    esp_rom_delay_us(1);
    REG_WRITE(IQE_CTRL1_REG, ctrl1 | IQE_CTRL1_EN | IQE_CTRL1_START);
    uint32_t t = esp_cpu_get_cycle_count() + step;
    for (uint32_t k = 0; k < 4u + n; ++k) {                  /* the first 4 settle the estimator, they are not kept */
        uint32_t c;
        while ((int32_t)((c = esp_cpu_get_cycle_count()) - t) < 0) {}
        const int32_t q1 = (int32_t)REG_READ(IQE_SUM_Q_REG) >> 6;
        const int32_t si = (int32_t)REG_READ(IQE_SUM_I_REG) >> 6;
        const int32_t sq = (q1 + ((int32_t)REG_READ(IQE_SUM_Q_REG) >> 6)) / 2;
        const int32_t vi = si - pi, vq = sq - pq;
        pi = si;
        pq = sq;
        if (k >= 4u) {
            buf[2u * (k - 4u)] = base ? INT16_MIN : (int16_t)vi;   /* INT16_MIN: filled in afterwards */
            buf[2u * (k - 4u) + 1u] = (int16_t)vq;
        }
        base = false;
        if (c - t > late_max) late_max = c - t;
        if (--left == 0) {
            iqe_start();
            base = true;
            left = IQE_REARM;
            ++rearms;
        }
        t += step;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    for (uint32_t k = 0; k < n; ++k)
        if (buf[2u * k] == INT16_MIN) {
            const uint32_t a = k ? k - 1u : k + 1u, b = k + 1u < n ? k + 1u : a;
            buf[2u * k] = (int16_t)((buf[2u * a] + buf[2u * b]) / 2);
            buf[2u * k + 1u] = (int16_t)((buf[2u * a + 1u] + buf[2u * b + 1u]) / 2);
        }
    st->late_max = late_max;
    st->rearms = rearms;
}

/* ------------------------------------------------------------ stream
 * The estimator is read at twice the output rate (1 Msps / 800 / 666.7 / 500 ksps for 500 / 400 / 333.3 / 250 ksps out: 240 / 300 /
 * 360 / 480 CPU cycles per input), interrupts masked; one halfband FIR (tools/design_taps.py) decimates by 2. Per input: read the estimator
 * (3 register reads, ~54 cycles; 2 in the fast path), put the previous input into the FIR history (a sample after a restart is filled in from its
 * neighbours, as in capture()), compute one half of an output (I in the slot of the even input, Q in the next one: 8 multiplies
 * each, 6 in the fast path) and move at most one byte into the USB FIFO. Packets are assembled in place in a byte FIFO and dropped whole when it is
 * full, as on the S3. */

#define R2_400K 25u                  /* ours: NB_SET_DECIM 25 = 400 ksps (1 Msps / 2.5), also in the packet header */
#define REARM_US 256u                /* estimator runs of 256 us (of its 409.6 us) */
#define HIST 64u                     /* FIR history ring (power of 2 > FIR_TAPS + 2), mirrored for contiguous windows */
#define STREAM_FIFO 12288u
#define NB_HEADER 20u
#define USB_BYTES 1u                 /* per slot at most, checked (24 + 44 cycles). cs4 needs 0.5 per input; cs8 needs 1, and the
                                     * FIFO is not always free: cs8 loses packets at 250 ksps and up (2 per slot: ~450 kB/s and 333
                                     * ksps too slow; 4 unchecked put the loop 3000 cycles behind) */

typedef struct {
    uint32_t late_max, late, slots, dropped, fifo_peak, packets, samples, busy_max;
    uint64_t busy_sum;               /* cycles from a slot's deadline to the end of its work */
} stream_stats_t;

static int32_t hist_i[2 * HIST], hist_q[2 * HIST];
static bool stream_nousb;
static uint32_t usb_max, usb_slow, work_max, work_over;   /* the USB part of a slot: worst cycles, slots above 200 */            /* diagnostics (NOUSB 1): the stream runs without touching the USB FIFO */
#define STREAM_PROF 0                /* 1: cycles per section (the counting itself costs about 80 cycles per slot) */
#define STREAM_DIAG 0                /* 1: slot work, USB part and NOUSB in the loop (about 40 cycles per slot) */
static uint64_t prof[4];             /* profile: reads + fill, Q half + packet, history + I half, USB */
#if STREAM_PROF
#define PROF(k) (prof[k] += esp_cpu_get_cycle_count() - pc, pc = esp_cpu_get_cycle_count())
#else
#define PROF(k) ((void)pc)
#endif

/* cs4 as the S3 (third_party/ESP32-DATV-RX/esp32-sdr-trx/firmware/protocol/narrowband.h): the cs16 samples minus a running DC
 * estimate, times g = CS4_MANT[j] / 2^(8 + e), floored to -8..7; gain byte e | j << 4 in the header. The S3 picks g from the
 * packet's own mean |x| (a second pass); here it comes from the packet before (one pass), the header carrying the g used. */
static const uint16_t cs4_mant[8] = {256, 279, 304, 332, 362, 395, 431, 470};
#define CS4_TARGET 279u              /* mean |x| target in LSB/100 */
#define CS4_MAX_E 15u
#define CS4_DC_SHIFT 3               /* DC estimate: first order over the packets' means, 8 packets */

static inline int32_t clamp4(int32_t v) { return v < -8 ? -8 : v > 7 ? 7 : v; }
static inline int32_t clamp8(int32_t v) { return v < -128 ? -128 : v > 127 ? 127 : v; }
static inline int32_t clamp16(int32_t v) { return v < -32768 ? -32768 : v > 32767 ? 32767 : v; }

/* x: the oldest of FIR_TAPS (fast: FIR_FAST_TAPS) inputs */
static inline __attribute__((always_inline)) int32_t halfband(const int32_t *x, const bool fast) {
    const int taps = fast ? FIR_FAST_TAPS : FIR_TAPS, side = fast ? FIR_FAST_SIDE : FIR_SIDE;
    const int16_t *const s = fast ? fir_fast_side : fir_side;
    const int32_t *const c = x + (taps - 1) / 2;
    int32_t acc = (fast ? FIR_FAST_CENTRE : FIR_CENTRE) * c[0] + (1 << 14);
    for (int j = 0; j < side; ++j) acc += s[j] * (c[-2 * j - 1] + c[2 * j + 1]);
    return acc >> 15;
}

/* r2: 2, 3, 4 as the S3 (1 Msps / r2 out), R2_400K for 400 ksps; inputs per second, twice the output rate */
static inline uint32_t stream_inputs(uint32_t r2) { return r2 >= 10u ? 20000000u / r2 : 2000000u / r2; }

/* fast (fewer than 360 cycles per input: 400 / 500 ksps): Q read once, after I (75 ns late: an image of -28 dB at 170 kHz from
 * the centre, less nearer it), and the shorter halfband (FIR_FAST_TAPS, -40 dB) */
static inline __attribute__((always_inline)) void stream_body(uint32_t r2, uint32_t fmt, uint32_t sh, uint32_t secs, stream_stats_t *st,
                                                               const bool fast) {
    const uint32_t rate = stream_inputs(r2), step = CPU_HZ / rate, rearm = REARM_US * rate / 1000000u;
    const uint32_t taps = fast ? FIR_FAST_TAPS : FIR_TAPS;
    const uint32_t per_pkt = rate / 2000u, bps = fmt == 2u ? 1u : fmt == 1u ? 2u : 4u;   /* 1 ms packets */
    /* cs4: the gain in use (mantissa, shift, header byte), the DC estimate (1/16 LSB) and the packet's sums for the next ones */
    int32_t c4_m = cs4_mant[0], c4_dci = 0, c4_dcq = 0, c4_si = 0, c4_sq = 0;
    uint32_t c4_sh = 8, c4_gb = 0, c4_gb_pkt = 0, c4_abs = 0;
    bool c4_dc_valid = false;
    const uint32_t end_slots = secs ? secs * rate : 0u;
    uint32_t late_max = 0, late = 0, slots = 0, dropped = 0, fifo_peak = 0, packets = 0, samples = 0, busy_max = 0;
    uint64_t busy_sum = 0;
    uint32_t left = rearm, w = 0, inputs = 0, seq = 0, first = 0;
    int32_t pi = 0, pq = 0, vi1 = 0, vq1 = 0, xi2 = 0, xq2 = 0, yi = 0;
    bool base = true, fill1 = false, q_due = false;
    const int32_t *win_q = hist_q;
    /* the packet being filled: start in the FIFO (UINT32_MAX: none, or dropped), samples in it */
    uint32_t head = 0, tail = 0, pkt = UINT32_MAX, pkt_n = 0, used = 0, chunk = 0;
    bool pkt_drop = false;
    /* the end of a packet is spread over the two light slots (no Q half) after it: 1 cs4 DC and gain, 2 the header, written at
     * hdr (UINT32_MAX: none, the packet was dropped) with what was current at the end; USB stops at hdr until then */
    uint32_t end_due = 0, hdr = UINT32_MAX, hdr_gb = 0, hdr_dropped = 0, hdr_first = 0;
    memset(hist_i, 0, sizeof(hist_i));
    memset(hist_q, 0, sizeof(hist_q));
    memset(prof, 0, sizeof(prof));
    usb_max = usb_slow = work_max = work_over = 0;
#define FIFO_PUT(b)                                                            \
    do {                                                                       \
        fifo[head] = (uint8_t)(b);                                             \
        head = head + 1u == STREAM_FIFO ? 0 : head + 1u;                        \
    } while (0)
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    iqe_start();
    uint32_t t = esp_cpu_get_cycle_count() + step;
    for (;; ++slots) {
        uint32_t c;
        while ((int32_t)((c = esp_cpu_get_cycle_count()) - t) < 0) {}
        uint32_t pc = c;
        int32_t si, sq;
        if (fast) {
            si = (int32_t)REG_READ(IQE_SUM_I_REG) >> 6;
            sq = (int32_t)REG_READ(IQE_SUM_Q_REG) >> 6;
        } else {
            const int32_t q1 = (int32_t)REG_READ(IQE_SUM_Q_REG) >> 6;
            si = (int32_t)REG_READ(IQE_SUM_I_REG) >> 6;
            sq = (q1 + ((int32_t)REG_READ(IQE_SUM_Q_REG) >> 6)) / 2;
        }
        c -= t;
        if (c > late_max) late_max = c;
        if (c >= step) ++late;
        const int32_t vi = si - pi, vq = sq - pq;
        pi = si;
        pq = sq;
        /* the previous input goes into the history; a base read is filled in from its neighbours */
        const int32_t xi = fill1 ? (xi2 + vi) / 2 : vi1, xq = fill1 ? (xq2 + vq) / 2 : vq1;
        fill1 = base;
        vi1 = vi;
        vq1 = vq;
        xi2 = xi;
        xq2 = xq;
        base = false;
        if (--left == 0) {
            iqe_start();
            base = true;
            left = rearm;
        }
        PROF(0);
        const bool q_now = q_due;
        if (q_now) {                                          /* the Q half of the output started in the slot before */
            q_due = false;
            const int32_t yq = halfband(win_q, fast);
            if (pkt_n == 0) {                                 /* a new packet: room for the whole of it? */
                pkt_drop = STREAM_FIFO - used < NB_HEADER + per_pkt * bps;
                if (pkt_drop) {
                    ++dropped;
                } else {
                    pkt = head;                               /* the header is written when the packet is complete */
                    head += NB_HEADER;
                    if (head >= STREAM_FIFO) head -= STREAM_FIFO;
                    used += NB_HEADER;
                }
            }
            if (pkt_n == 0) c4_gb_pkt = c4_gb;                /* the gain this packet is sent with */
            if (fmt == 2u) {                                  /* cs4: the sums for the next packet's gain and DC, then pack */
                const int32_t i = yi - ((c4_dci + 8) >> 4), q = -yq - ((c4_dcq + 8) >> 4);
                c4_si += yi;
                c4_sq += -yq;
                c4_abs += (uint32_t)(i < 0 ? -i : i) + (uint32_t)(q < 0 ? -q : q);
                if (!pkt_drop) {
                    FIFO_PUT((clamp4((i * c4_m) >> c4_sh) & 15) | (clamp4((q * c4_m) >> c4_sh) & 15) << 4);
                    used += 1u;
                }
            } else if (!pkt_drop) {
                const int32_t oi = yi >> sh, oq = -yq >> sh; /* Q inverted: LO - RF, as the S3 */
                if (fmt == 1u) {
                    FIFO_PUT(clamp8(oi));
                    FIFO_PUT(clamp8(oq));
                } else {
                    const int32_t a = clamp16(oi), b = clamp16(oq);
                    FIFO_PUT(a);
                    FIFO_PUT(a >> 8);
                    FIFO_PUT(b);
                    FIFO_PUT(b >> 8);
                }
                used += bps;
            }
            if (++pkt_n == per_pkt) {                         /* the rest in the next light slots (end_due) */
                if (!pkt_drop) {
                    hdr = pkt;
                    hdr_gb = c4_gb_pkt;
                    hdr_dropped = dropped;
                    hdr_first = first;
                }
                end_due = 1;
                first += per_pkt;
                samples += per_pkt;
                pkt = UINT32_MAX;
                pkt_n = 0;
            }
        } else if (end_due == 1u) {                           /* before the next packet's first sample, two slots later */
            end_due = 2;
            if (fmt == 2u) {                                  /* cs4: DC estimate and the gain for the next packet */
                const int32_t mi = c4_si * 16 / (int32_t)per_pkt, mq = c4_sq * 16 / (int32_t)per_pkt;
                if (!c4_dc_valid) {
                    c4_dci = mi;
                    c4_dcq = mq;
                    c4_dc_valid = true;
                } else {
                    c4_dci += (mi - c4_dci) >> CS4_DC_SHIFT;
                    c4_dcq += (mq - c4_dcq) >> CS4_DC_SHIFT;
                }
                /* the S3's search for the largest g with g * mean|x| <= target, without its up to 120 steps: with
                 * v = 2^20 target / mean100 the first e that can hold a mantissa is about 20 - log2(v) */
                const uint32_t mean100 = c4_abs * 50u / per_pkt;
                uint32_t e = 0, j = 7;
                if (mean100) {
                    const uint32_t v = (CS4_TARGET << 20) / mean100, msb = 31u - (uint32_t)__builtin_clz(v | 1u);
                    e = msb >= 20u ? 0u : 20u - msb;
                    if (e > CS4_MAX_E - 1u) e = CS4_MAX_E - 1u;
                    for (;;) {                            /* the S3's test itself: at most 8 + 8 steps */
                        j = 8;
                        while (j-- > 0 && ((uint32_t)cs4_mant[j] * mean100) >> 8 > (CS4_TARGET << e)) {}
                        if (j < 8u || e == CS4_MAX_E - 1u) break;
                        ++e;
                    }
                    if (j >= 8u) j = 0;
                }
                c4_m = cs4_mant[j];
                c4_sh = 8u + e;
                c4_gb = e | j << 4;
                c4_si = c4_sq = 0;
                c4_abs = 0;
            }
        } else if (end_due == 2u) {
            end_due = 0;
            if (hdr != UINT32_MAX) {                          /* the header, now that the packet is complete */
                uint16_t hw[10] = {0x5DE5u, (uint16_t)(fmt | r2 << 8), (uint16_t)per_pkt,
                                   (uint16_t)(hdr_dropped > 65535u ? 65535u : hdr_dropped), (uint16_t)seq, (uint16_t)(seq >> 16),
                                   (uint16_t)hdr_first, (uint16_t)(hdr_first >> 16), (uint16_t)(fmt == 2u ? hdr_gb << 8 : sh), 0};
                uint32_t sum = 0;
                for (uint32_t j = 0; j < 9; ++j) sum += hw[j];
                hw[9] = (uint16_t)(0xFFFFu - (sum & 0xFFFFu));
                uint32_t p = hdr;
                for (uint32_t j = 0; j < 10; ++j) {
                    fifo[p] = (uint8_t)hw[j];
                    p = p + 1u == STREAM_FIFO ? 0 : p + 1u;
                    fifo[p] = (uint8_t)(hw[j] >> 8);
                    p = p + 1u == STREAM_FIFO ? 0 : p + 1u;
                }
                hdr = UINT32_MAX;
                ++seq;
                ++packets;
                if (used > fifo_peak) fifo_peak = used;
            }
        }
        PROF(1);
        if (slots >= 4u) {                                    /* the first reads settle the estimator */
            hist_i[w] = hist_i[w + HIST] = xi;
            hist_q[w] = hist_q[w + HIST] = xq;
            w = (w + 1u) & (HIST - 1u);
            if (++inputs >= taps && !(inputs & 1u) && !q_now) {   /* an output is due: its window ends with this input */
                const uint32_t o = (w - taps) & (HIST - 1u);
                yi = halfband(hist_i + o, fast);
                win_q = hist_q + o;
                q_due = true;
            }
        }
        PROF(2);
        /* USB: up to USB_BYTES bytes of finished packets (the one being filled starts at pkt, a header due at hdr) per slot, each while the FIFO's
         * not-full flag (a 24-cycle register read) is set; the FIFO takes the next chunk while the last one is being sent.
         * (Waiting for SERIAL_IN_EMPTY instead idles between chunks: about 390 kB/s.) */
        if (STREAM_DIAG && used && tail != pkt && tail != hdr && stream_nousb) {             /* diagnostics: the packets are thrown away */
            tail = head;
            used = 0;
        } else {
            for (uint32_t b = 0; b < USB_BYTES && used && tail != pkt && tail != hdr && USB_SERIAL_JTAG.ep1_conf.serial_in_ep_data_free;
                 ++b) {
                USB_SERIAL_JTAG.ep1.val = fifo[tail];          /* the whole register: a bit-field store reads it first, and
                                                                 * reading it takes a byte out of the receive FIFO */
                tail = tail + 1u == STREAM_FIFO ? 0 : tail + 1u;
                --used;
                if (++chunk == 64u || !used || tail == pkt || tail == hdr) {
                    usb_serial_jtag_ll_txfifo_flush();
                    chunk = 0;
                    break;
                }
            }
        }
#if STREAM_DIAG
        {
            const uint32_t ud = esp_cpu_get_cycle_count() - u0;
            if (ud > usb_max) usb_max = ud;
            if (ud > 200u) ++usb_slow;
        }
#endif
        PROF(3);
#if STREAM_DIAG
        {
            const uint32_t wk = esp_cpu_get_cycle_count() - (c + t);     /* this slot's own work (c is its lateness here) */
            if (wk > work_max) work_max = wk;
            if (wk > step) ++work_over;
            const uint32_t busy = esp_cpu_get_cycle_count() - t;
            busy_sum += busy;
            if (busy > busy_max) busy_max = busy;
        }
#endif
        /* any byte from the host ends the run (its ESP_STOP, as on the S3); not while a packet's end is due */
        if (!end_due && (slots & 1023u) == 0 && usb_serial_jtag_ll_rxfifo_data_available()) break;
        if (end_slots && slots >= end_slots && !end_due) break;
        t += step;
    }
#undef FIFO_PUT
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    while (used && tail != pkt) {                             /* the rest of the finished packets */
        const uint32_t n = tail + used <= STREAM_FIFO ? used : STREAM_FIFO - tail;
        const uint32_t m = pkt != UINT32_MAX && pkt >= tail && pkt - tail < n ? pkt - tail : n;
        usb_write(fifo + tail, m);
        tail = (tail + m) % STREAM_FIFO;
        used -= m;
        if (m < n) break;
    }
    *st = (stream_stats_t){late_max, late, slots, dropped, fifo_peak, packets, samples, busy_max, busy_sum};
}

static void __attribute__((noinline)) IRAM_ATTR stream_std(uint32_t r2, uint32_t fmt, uint32_t sh, uint32_t secs, stream_stats_t *st) {
    stream_body(r2, fmt, sh, secs, st, false);
}

static void __attribute__((noinline)) IRAM_ATTR stream_fast(uint32_t r2, uint32_t fmt, uint32_t sh, uint32_t secs, stream_stats_t *st) {
    stream_body(r2, fmt, sh, secs, st, true);
}

static void stream(uint32_t r2, uint32_t fmt, uint32_t sh, uint32_t secs, stream_stats_t *st) {
    (CPU_HZ / stream_inputs(r2) < 360u ? stream_fast : stream_std)(r2, fmt, sh, secs, st);
}

static void __attribute__((noinline)) IRAM_ATTR bench(void) {
    uint32_t r[6], x = 0;
    static int32_t hb[FIR_TAPS];
    for (int i = 0; i < FIR_TAPS; ++i) hb[i] = i * 37 - 500;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) x += REG_READ(IQE_SUM_I_REG);
    r[0] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) x += USB_SERIAL_JTAG.ep1_conf.serial_in_ep_data_free;
    r[1] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) x += usb_serial_jtag_ll_rxfifo_data_available();
    r[2] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) {
        hb[i & 15] += (int32_t)x;
        x += (uint32_t)halfband(hb, false);
    }
    r[3] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) x += esp_cpu_get_cycle_count();
    r[4] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) fifo[i & 4095] = (uint8_t)x;
    r[5] = esp_cpu_get_cycle_count() - c;
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    say("BENCH per 1000 (loop included): IQE read %lu, USB data_free %lu, USB rx avail %lu, halfband %lu, rdcycle %lu, fifo store %lu "
        "(%lu)\r\n", (unsigned long)r[0], (unsigned long)r[1], (unsigned long)r[2], (unsigned long)r[3], (unsigned long)r[4],
        (unsigned long)r[5], (unsigned long)(x & 1));
}

/* Cost of USB FIFO stores: 100 chunks of 64 bytes, each into a FIFO that reported empty (SERIAL_IN_EMPTY); the host gets them. */
static void __attribute__((noinline)) IRAM_ATTR usb_store_cost(void) {
    uint32_t store = 0, flush = 0, wait = 0, store_max = 0;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    for (int k = 0; k < 100; ++k) {
        uint32_t c = esp_cpu_get_cycle_count();
        while (!(usb_serial_jtag_ll_get_intraw_mask() & USB_SERIAL_JTAG_INTR_SERIAL_IN_EMPTY)) {}
        usb_serial_jtag_ll_clr_intsts_mask(USB_SERIAL_JTAG_INTR_SERIAL_IN_EMPTY);
        wait += esp_cpu_get_cycle_count() - c;
        for (int i = 0; i < 64; ++i) {
            c = esp_cpu_get_cycle_count();
            USB_SERIAL_JTAG.ep1.val = (uint8_t)'.';
            const uint32_t d = esp_cpu_get_cycle_count() - c;
            store += d;
            if (d > store_max) store_max = d;
        }
        c = esp_cpu_get_cycle_count();
        usb_serial_jtag_ll_txfifo_flush();
        flush += esp_cpu_get_cycle_count() - c;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    say("\nUSBC store %lu cycles per byte (max %lu), flush %lu, wait for empty %lu cycles per chunk\r\n", (unsigned long)(store / 6400u),
        (unsigned long)store_max, (unsigned long)(flush / 100u), (unsigned long)(wait / 100u));
}

/* Raw USB throughput: 64-byte chunks as fast as the FIFO takes them, for s seconds; the host counts the bytes. */
static void usb_throughput(uint32_t secs) {
    static uint8_t chunk[64];
    for (int i = 0; i < 64; ++i) chunk[i] = (uint8_t)i;
    const int64_t end = esp_timer_get_time() + (int64_t)secs * 1000000;
    uint32_t bytes = 0, stalls = 0;
    while (esp_timer_get_time() < end) {
        if (!usb_serial_jtag_ll_txfifo_writable()) {
            ++stalls;
            continue;
        }
        bytes += usb_serial_jtag_ll_write_txfifo(chunk, 64);
        usb_serial_jtag_ll_txfifo_flush();
    }
    say("\nEND usbt %lu bytes in %lu s = %lu kB/s, %lu not-free polls\n", (unsigned long)bytes, (unsigned long)secs,
        (unsigned long)(bytes / secs / 1000u), (unsigned long)stalls);
}

/* ------------------------------------------------------------ S3 control protocol */

#define CTL_OK 0
#define CTL_UNKNOWN_OP 1
#define CTL_BAD_ARGUMENT 2
#define CTL_ESP_FIRMWARE_ID 0x49515306u
#define CTL_BUILD_NARROWBAND 1u
#define CTL_BUILD_C5 4u                  /* ours: this ESP32-C5 receiver (2 is the S3's transmitter build) */
#define CTL_BUILD_400K 8u                /* ours: NB_SET_DECIM R2_400K */
enum {
    CTL_INFO = 1, CTL_SAFE = 2, CTL_STATUS = 3, ESP_OUTPUTS = 16, ESP_RUN = 17, ESP_STOP = 18, ESP_ARG_HIGH = 19, ESP_SET_LO = 20,
    ESP_SET_RATE = 21, ESP_SET_WIDTH = 22, ESP_SET_FILTER = 23, ESP_SET_GAIN = 24, ESP_SET_RF_GAIN = 25, ESP_SET_BB_GAIN = 26,
    ESP_SET_DC = 27, ESP_SET_IQ = 28, NB_SET_DECIM = 29, NB_SET_FORMAT = 30, NB_SET_OUTSHIFT = 31,
};
enum { ESP_STAT_SERVICE_MAX0 = 11, ESP_STAT_SERVICE_MAX1 = 12, ESP_STAT_RADIO = 13, ESP_STAT_STOPPED_BY_HOST = 14, ESP_STAT_LO_HZ = 15,
       ESP_STAT_RATE = 16, ESP_STAT_WIDTH = 17, ESP_STAT_GAIN = 19, NB_STAT_DROPPED = 32, NB_STAT_FIFO_PEAK = 33 };

static struct {
    uint32_t decim, format, outshift, gain_sel, arg_high;
    bool has_high, stopped;
    stream_stats_t last;
    uint8_t pend[64];                /* what the host sent during a run (its ESP_STOP), parsed after the RUN reply */
    uint32_t npend, stop_bytes;
} nb = {3, 1, 4, 50, 0, false, false, {0}, {0}, 0, 0};

static void respond(uint8_t op, uint8_t status, uint16_t seq, uint32_t value) {
    uint8_t r[16] = {0xB5, 1, op, status, (uint8_t)seq, (uint8_t)(seq >> 8), 0, 0,
                     (uint8_t)value, (uint8_t)(value >> 8), (uint8_t)(value >> 16), (uint8_t)(value >> 24)};
    const uint32_t crc = esp_rom_crc32_le(0, r, 12);
    for (int i = 0; i < 4; ++i) r[12 + i] = (uint8_t)(crc >> (8 * i));
    usb_write(r, sizeof(r));
}

static uint32_t lo_wire(void) { return (uint32_t)((uint64_t)fkhz * 1000u); }   /* Hz modulo 2^32 */

static uint32_t stat_value(uint32_t k) {
    switch (k) {
    case ESP_STAT_SERVICE_MAX0:
    case ESP_STAT_SERVICE_MAX1: return nb.last.busy_max;
    case ESP_STAT_STOPPED_BY_HOST: return nb.stopped;
    case ESP_STAT_LO_HZ: return lo_wire();
    case ESP_STAT_RATE: return 1;
    case ESP_STAT_WIDTH: return 20;
    case ESP_STAT_GAIN: return nb.gain_sel;
    case NB_STAT_DROPPED: return nb.last.dropped;
    case NB_STAT_FIFO_PEAK: return nb.last.fifo_peak;
    case 40: return nb.stop_bytes;                            /* ours: bytes read when the last run ended */
    default: return 0;                                        /* ESP_STAT_RADIO and the rest: 0 */
    }
}

static void control(const uint8_t *q) {
    const uint8_t op = q[1];
    const uint16_t seq = (uint16_t)(q[4] | q[5] << 8);
    const uint32_t arg = (nb.has_high ? nb.arg_high << 16 : 0u) | (uint32_t)(q[2] | q[3] << 8);
    nb.has_high = false;
    switch (op) {
    case CTL_INFO: {
        uint8_t mac[6] = {0};
        esp_efuse_mac_get_default(mac);
        const uint32_t v = arg == 0 ? CTL_ESP_FIRMWARE_ID : arg == 1 ? (uint32_t)(mac[0] | mac[1] << 8 | mac[2] << 16 | (uint32_t)mac[3] << 24)
                           : arg == 2 ? (uint32_t)(mac[4] | mac[5] << 8) : arg == 3 ? CTL_BUILD_NARROWBAND | CTL_BUILD_C5 | CTL_BUILD_400K : 0u;
        respond(op, CTL_OK, seq, v);
        return;
    }
    case CTL_STATUS: respond(op, CTL_OK, seq, stat_value(arg)); return;
    case ESP_ARG_HIGH:
        nb.arg_high = arg & 0xFFFFu;
        nb.has_high = true;
        respond(op, CTL_OK, seq, nb.arg_high);
        return;
    case ESP_SET_LO: {
        const uint64_t hz = arg < 2000000000u ? (uint64_t)arg + (1ull << 32) : arg;
        if (hz < (uint64_t)FMIN_KHZ * 1000u || hz > (uint64_t)FMAX_KHZ * 1000u) {
            respond(op, CTL_BAD_ARGUMENT, seq, lo_wire());
            return;
        }
        fkhz = (uint32_t)((hz + 500u) / 1000u);
        rx_prepare();
        respond(op, CTL_OK, seq, lo_wire());
        return;
    }
    case ESP_SET_GAIN:
        if (arg > 127u) {
            respond(op, CTL_BAD_ARGUMENT, seq, nb.gain_sel);
            return;
        }
        nb.gain_sel = arg;
        gain = arg > 89u ? 89u : arg;
        force_rx_gain(1, gain, 0);
        respond(op, CTL_OK, seq, arg);
        return;
    case NB_SET_DECIM:
        if ((arg < 2u || arg > 4u) && arg != R2_400K) {
            respond(op, CTL_BAD_ARGUMENT, seq, nb.decim);
            return;
        }
        nb.decim = arg;
        respond(op, CTL_OK, seq, arg);
        return;
    case NB_SET_FORMAT:
        if (arg > 2u) {
            respond(op, CTL_BAD_ARGUMENT, seq, nb.format);
            return;
        }
        nb.format = arg;
        respond(op, CTL_OK, seq, arg);
        return;
    case NB_SET_OUTSHIFT:
        if (arg > 12u) {
            respond(op, CTL_BAD_ARGUMENT, seq, nb.outshift);
            return;
        }
        nb.outshift = arg;
        respond(op, CTL_OK, seq, arg);
        return;
    case ESP_SET_RATE: respond(op, CTL_OK, seq, 1); return;          /* the S3's 16 Msps; the C5 has its own rates */
    case ESP_SET_WIDTH: respond(op, CTL_OK, seq, 20); return;
    case CTL_SAFE:
    case ESP_OUTPUTS:
    case ESP_SET_FILTER:
    case ESP_SET_RF_GAIN:
    case ESP_SET_BB_GAIN:
    case ESP_SET_DC:
    case ESP_SET_IQ:
    case ESP_STOP: respond(op, CTL_OK, seq, 0); return;             /* STOP outside a run: nothing to stop */
    case ESP_RUN: {
        /* Packets until the time is up or any byte arrives (the host's STOP, answered after the RUN reply). */
        force_rx_gain(1, gain, 0);
        stream(nb.decim, nb.format, nb.format == 2u ? 0u : nb.outshift, arg & 0xFFFFu, &nb.last);
        /* the bytes that ended the run, read now: they are answered after the RUN reply, as on the S3 */
        const int64_t until = esp_timer_get_time() + 20000;
        nb.npend = 0;
        while (nb.npend < sizeof(nb.pend) && esp_timer_get_time() < until)
            nb.npend += usb_serial_jtag_ll_read_rxfifo(nb.pend + nb.npend, sizeof(nb.pend) - nb.npend);
        nb.stop_bytes = nb.npend;
        nb.stopped = nb.npend != 0;
        respond(op, CTL_OK, seq, 0);
        return;
    }
    default: respond(op, CTL_UNKNOWN_OP, seq, 0); return;
    }
}

/* ------------------------------------------------------------ commands */

/* "5710.25" = MHz with up to three decimals -> kHz */
static bool parse_freq(const char *s, uint32_t *khz) {
    char *e;
    unsigned long whole = strtoul(s, &e, 10);
    if (e == s) return false;
    uint32_t frac = 0, sc = 100;
    if (*e == '.') {
        for (++e; *e >= '0' && *e <= '9'; ++e) {
            frac += (*e - '0') * sc;
            sc /= 10;
            if (!sc && e[1] >= '0' && e[1] <= '9') return false;
        }
    }
    if (*e) return false;
    *khz = whole * 1000 + frac;
    return true;
}

static void state(void) {
    say("ST f %.3f MHz gain %lu ant %s xtal %u agc %08lx\r\n", fkhz / 1000.0, (unsigned long)gain,
        gpio_get_level(ANT_GPIO) ? "ufl" : "chip", phy_param[PHY_PARAM_XTAL], (unsigned long)REG_READ(AGC_FORCE_REG));
}

static void handle(char *line) {
    char *cmd = strtok(line, " "), *a1 = strtok(NULL, " ");
    if (!cmd) return;
    for (char *p = cmd; *p; ++p)
        if (*p >= 'a' && *p <= 'z') *p -= 32;
    if (!strcmp(cmd, "F")) {
        uint32_t k;
        if (!a1 || !parse_freq(a1, &k) || k < FMIN_KHZ || k > FMAX_KHZ) {
            say("ERR F: %lu-%lu MHz\r\n", (unsigned long)(FMIN_KHZ / 1000), (unsigned long)(FMAX_KHZ / 1000));
            return;
        }
        fkhz = k;
        rx_prepare();
    } else if (!strcmp(cmd, "G")) {
        if (!a1 || atoi(a1) < 0 || atoi(a1) > 89) {
            say("ERR G 0-89\r\n");
            return;
        }
        gain = atoi(a1);
        force_rx_gain(1, gain, 0);
    } else if (!strcmp(cmd, "ANT")) {
        ant = a1 && atoi(a1) ? 1 : 0;
        gpio_set_level(ANT_GPIO, ant);
    } else if (!strcmp(cmd, "CAP")) {
        const uint32_t n = a1 ? (uint32_t)atoi(a1) : 0;
        if (n < 1 || n > CAP_MAX) {
            say("ERR CAP 1-%u\r\n", (unsigned)CAP_MAX);
            return;
        }
        int16_t *buf = malloc(4u * n);
        if (!buf) {
            say("ERR no memory\r\n");
            return;
        }
        cap_stats_t st;
        force_rx_gain(1, gain, 0);
        capture(buf, n, &st);
        say("CAPD %lu %lu %lu %lu\n", (unsigned long)n, (unsigned long)CAP_RATE, (unsigned long)st.late_max, (unsigned long)st.rearms);
        usb_write(buf, 4u * n);
        free(buf);
        return;
    } else if (!strcmp(cmd, "STREAM")) {
        char *a2 = strtok(NULL, " "), *a3 = strtok(NULL, " "), *a4 = strtok(NULL, " ");
        uint32_t r2 = a1 ? (uint32_t)atoi(a1) : 3u;
        const uint32_t fmt = a2 ? (uint32_t)atoi(a2) : 1u, sh = a3 ? (uint32_t)atoi(a3) : 4u,
                       secs = a4 ? (uint32_t)atoi(a4) : 0u;
        if ((r2 < 2u || r2 > 4u) && r2 != R2_400K) r2 = 0;
        if (!r2 || fmt > 2u || sh > 12u || !fifo) {
            say("ERR STREAM r2(2-4, 25: 400 ksps) fmt(0 cs16, 1 cs8, 2 cs4) shift(0-12) seconds\r\n");
            return;
        }
        stream_stats_t st;
        force_rx_gain(1, gain, 0);
        stream(r2, fmt, sh, secs, &st);
        uint8_t c;
        while (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
        say("\nEND slots %lu late %lu worst %lu cycles, busy mean %lu max %lu, packets %lu dropped %lu fifo peak %lu\n",
            (unsigned long)st.slots, (unsigned long)st.late, (unsigned long)st.late_max,
            (unsigned long)(st.slots ? st.busy_sum / st.slots : 0), (unsigned long)st.busy_max, (unsigned long)st.packets,
            (unsigned long)st.dropped, (unsigned long)st.fifo_peak);
        if (STREAM_DIAG) say("USB part: worst %lu cycles, %lu slots above 200; slot work: worst %lu cycles, %lu slots above a period\n",
            (unsigned long)usb_max, (unsigned long)usb_slow, (unsigned long)work_max, (unsigned long)work_over);
        if (STREAM_PROF && st.slots)
            say("PROF per slot: reads %lu, Q+packet %lu, history+I %lu, USB %lu\n", (unsigned long)(prof[0] / st.slots),
                (unsigned long)(prof[1] / st.slots), (unsigned long)(prof[2] / st.slots), (unsigned long)(prof[3] / st.slots));
        return;
    } else if (!strcmp(cmd, "NOUSB")) {
        stream_nousb = a1 && atoi(a1);
    } else if (!strcmp(cmd, "USBC")) {
        usb_store_cost();
        return;
    } else if (!strcmp(cmd, "USBT")) {
        usb_throughput(a1 && atoi(a1) > 0 ? (uint32_t)atoi(a1) : 3u);
        return;
    } else if (!strcmp(cmd, "BENCH")) {
        bench();
        return;
    } else if (!strcmp(cmd, "INFO")) {
        return;                                               /* the Android app's C3 probe: not a C3, so no answer */
    } else if (strcmp(cmd, "ST")) {
        say("ERR commands: F G ANT CAP STREAM ST\r\n");
        return;
    }
    state();
}

void app_main(void) {
    gpio_reset_pin(ANT_GPIO);
    gpio_set_direction(ANT_GPIO, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_level(ANT_GPIO, ant);               /* U.FL: the external antenna */
    esp_err_t e = nvs_flash_init();
    if (e == ESP_ERR_NVS_NO_FREE_PAGES || e == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        e = nvs_flash_init();
    }
    ESP_ERROR_CHECK(e);
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL));
    ESP_ERROR_CHECK(esp_wifi_start());           /* brings the PHY up and calibrates it */
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous(true));
    const wifi_promiscuous_filter_t none = {.filter_mask = 0};
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_filter(&none));   /* no frames: the driver task stays quiet */
    fifo = malloc(STREAM_FIFO);
    rx_prepare();
    say("c5rx ready\r\n");
    state();

    char line[64];
    size_t used = 0;
    uint8_t req[10];
    size_t got = 0;                                           /* bytes of a binary request (0: none in progress) */
    int64_t req_t = 0;
    uint32_t pend_i = 0;
    for (;;) {
        uint8_t c;
        if (got && esp_timer_get_time() - req_t > 100000) got = 0;   /* a partial request is dropped after 100 ms, as on the S3 */
        bool have = false;
        if (pend_i < nb.npend) {                              /* first what ended a run */
            c = nb.pend[pend_i++];
            have = true;
        } else {
            pend_i = nb.npend = 0;
            have = usb_serial_jtag_ll_read_rxfifo(&c, 1);
        }
        if (have) {
            if (got || (c == 0xB4 && used == 0)) {            /* binary request */
                if (!got) req_t = esp_timer_get_time();
                req[got++] = c;
                if (got == sizeof(req)) {
                    got = 0;
                    const uint32_t crc = esp_rom_crc32_le(0, req, 6);
                    if ((uint32_t)(req[6] | req[7] << 8 | req[8] << 16 | (uint32_t)req[9] << 24) == crc) control(req);
                }
                continue;
            }
            if (c == '\r') continue;
            if (c != '\n') {
                if (used < sizeof(line) - 1) line[used++] = (char)c;
                continue;
            }
            line[used] = 0;
            used = 0;
            if (line[0]) handle(line);
            continue;
        }
        vTaskDelay(1);
    }
}
