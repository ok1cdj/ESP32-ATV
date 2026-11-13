/* ESP32-C5 bench transmitter for the 6 cm band (2300-5900 MHz accepted): a carrier or a single tone, for a spectrum analyser.
 * The first step of porting the C3 transmitter (third_party/ESP32-DATV-SA) to the C5's 5 GHz radio.
 * On 5710 MHz (Waveshare ESP32-C5-Zero, 2026-11-10, tinySA Ultra+ over the air): TONE 100 200 gives LO leakage 39 dBc and
 * image 42 dBc down; the 48 MHz crystal warms up by about +100 kHz (19 ppm) in the first minutes of transmitting.
 *
 * Transmit path as fedaykinofdune/esp32-C5-sdr-tx (GPL-3, doc/hardware-quirks.md "ESP32-C5", esp_sdr_paced.c), measured
 * there on a Waveshare ESP32-C5-Zero at 2.4 GHz only:
 *   - the vendor CW (librftest esp_phy_wifi_tx_tone) tunes any MHz (5 GHz path from 4500 MHz) and turns the TX chain on;
 *   - phy_stop_tx_tone(1) stops its tone and leaves the chain on, the DAC then takes the TX DC value register;
 *   - TXDC (0x600a0c08): I 9:0, Q 19:10 (two's complement), bit 26 injects it, the other bits are kept;
 *   - kHz offset: phy_set_rf_freq_offset(phy_param[49] = crystal selector, MHz, kHz);
 *   - level = sample x DAC scale (0x600a0c04, one byte per branch in 23:16 and 15:8); compression from about 1000-1500.
 * A CPU store into TXDC takes 15 cycles; TONE paces it on the cycle counter at TONE_RATE with interrupts masked.
 *
 * Commands (USB Serial/JTAG, one per line):
 *   F 5715.000        frequency in MHz (kHz with up to three decimals, 2300-5900); retunes a running carrier
 *   CW [i q]          carrier: a constant DAC word (default 200 0); CW 0 0 leaves the LO leakage alone
 *   TONE kHz [amp]    single tone at F + kHz (negative: below), amp 1-511 (default 200); any byte stops it
 *   SCALE n           DAC scale, both branches (0-255, default 15)
 *   BO n              vendor tone backoff in quarter dB (no effect on the data path per the reference)
 *   ANT 1|0           GPIO26 RF switch of the C5-Zero: 1 = U.FL (default), 0 = chip antenna
 *   VT n              diagnostics: the vendor CW alone (esp_phy_wifi_tx_tone(1, n, BO), n = channel or MHz), no DAC path
 *   RFT               diagnostics: esp_phy_rftest_config(1) + esp_phy_rftest_init() (the cert-test set-up)
 *   BEACON [amp] [S] [dci dcq]  the DVB-S2 beacon (beacon.bin of the BEACON_DIR given to flash.sh) at F: QPSK, RRC 0.35, S samples per
 *                     symbol (8-48, default 16), amp = worst-case |I|, |Q| in DAC codes (default 150); any byte stops it.
 *                     S 8 and 16 take two 4-symbol tables (2 loads per sample), the others four 2-symbol tables (4 loads);
 *                     dci dcq: DC in DAC codes against the carrier leakage, as found with CW i q (tx/c5_dccal.py)
 *   PLAY              the loop stored with VIDW (also after power-up, by itself); any byte stops it
 *   VIDW ...          stores a loop in the "video" partition, as the C3 (the app's Save as beacon); INFO answers as the C3
 *   SPIT [dummy]      diagnostics: SPI1 reads of the stored loop (as its feed does them) against the mapped flash
 *   DC [i q]          the single DC correction in DAC codes, used without a BDC table (saved in NVS; tx/c5_dccal.py --save)
 *   BDC ...           DC correction per frequency, as the C3: [GET [f_MHz] | i q | SAVE | PUT f_MHz i q | LIST | RESET | TEST on [f_MHz] | off]
 *   QPSKT f_MHz baud sps [amp [secs [ifm [target [dcI dcQ]]]]]   live TX as the C3: raw symbols from USB (4 per byte), B7 fill
 *                     reports, off after 0.5 s without data; sps, ifm and the C3's IQ arguments are ignored (QPSK: S 16, 2-333 kBd)
 *   PSK8T ...         the same in 8PSK (a symbol per nibble, S from 8 to 64 for at least P8_MIN_PERIOD cycles per sample)
 *   PA 0              answered as the C3 (no PA output on the C5-Zero)
 *   BENCH             diagnostics: CPU cycles of a TXDC store, a TXDC store + a DRAM load, rdcycle, a DRAM load (1000 each)
 *   OFF               transmitter off
 *   ST                state
 * As on the C3, after power-up a stored loop (else the built-in beacon) transmits by itself; a byte from the host stops it, and it
 * starts again after BEACON_IDLE_US without a command, until a valid command has come (then BDC, VIDW END / ERASE or PLAY start it).
 */
#include <inttypes.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_cpu.h"
#include "esp_event.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "driver/gpio.h"
#include "c5_led.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "hal/usb_serial_jtag_ll.h"
#include "nvs_flash.h"
#include "soc/soc.h"
#include "hal/spimem_flash_ll.h"
#include "soc/spi_mem_struct.h"

#ifdef C5CW_NO_BEACON                /* the web build: only a stored loop (VIDW) */
#define BEACON_FREQ_KHZ 5715000u
#define BEACON_BAUD 250000u
#define BEACON_SYMBOLS 0u
#define BEACON_FEC "-"
#else
#include "beacon_cfg.h"              /* of the beacon directory (flash.sh) */
#endif
#include "sample_clock.h"            /* third_party/ESP32-DATV-SA/firmware/main */

extern void esp_phy_wifi_tx_tone(uint32_t start, uint32_t mhz, uint32_t backoff);
extern void esp_phy_rftest_config(uint8_t conf);
extern void esp_phy_rftest_init(void);
extern void phy_stop_tx_tone(unsigned en);
extern void phy_set_rf_freq_offset(unsigned xtal, unsigned mhz, int khz);
extern unsigned char phy_param[];
#define PHY_PARAM_XTAL 49

#define CPU_HZ 240000000u
#define TXDC_REG 0x600a0c08u
#define TXDC_IQ 0x000fffffu
#define TXDC_INJECT (1u << 26)
#define DAC_SCALE_REG 0x600a0c04u
#define DAC_SCALE_M 0x00ffff00u
#define ANT_GPIO 26

#define FMIN_KHZ 2300000u            /* 2.4 GHz too (the reference measured TX there); IARU R1 ATV segment 5700-5720 MHz */
#define FMAX_KHZ 5900000u
#define TONE_RATE 2000000u           /* 120 CPU cycles per sample */
#define TONE_MAX 4000u               /* samples per period: tones down to 0.5 kHz */

static uint32_t fkhz = BEACON_FREQ_KHZ >= FMIN_KHZ && BEACON_FREQ_KHZ <= FMAX_KHZ ? BEACON_FREQ_KHZ : 5715000u;   /* the beacon's (BAND=6cm) */
static uint32_t scale = 15, backoff = 0, ant = 1;
static uint32_t tone_arg;            /* the argument the vendor CW was started with */
static bool tx_on;
static uint32_t txdc_orig;
static uint32_t txdc_boot;           /* TXDC after the PHY's start, its I/Q cleared: written back after every run */
static uint32_t tone_w[TONE_MAX];
static bool host_seen;               /* a valid command came: the beacon stays off (as the C3) */
static bool beacon_restart;          /* the command wants the beacon (again) at once */

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

static inline uint32_t iq10(int32_t i, int32_t q) {
    i = i < -511 ? -511 : i > 511 ? 511 : i;
    q = q < -511 ? -511 : q > 511 ? 511 : q;
    return ((uint32_t)i & 0x3ffu) | (((uint32_t)q & 0x3ffu) << 10);
}

static void dac_word(uint32_t iq) {
    REG_WRITE(TXDC_REG, (txdc_orig & ~TXDC_IQ) | TXDC_INJECT | iq);
}

static void set_scale(void) {
    REG_WRITE(DAC_SCALE_REG, (REG_READ(DAC_SCALE_REG) & ~DAC_SCALE_M) | (scale << 16) | (scale << 8));
}

static void tx_off(void) {
    if (!tx_on) return;
    REG_WRITE(TXDC_REG, txdc_boot);
    esp_phy_wifi_tx_tone(0, tone_arg, backoff);
    tx_on = false;
    led(LED_IDLE);
}

/* The vendor CW on the MHz turns the chain on, its tone stop hands the DAC to TXDC, then only the PLL takes the kHz. */
static void tx_chain_on(void) {
    tx_off();
    REG_WRITE(TXDC_REG, txdc_boot);
    const uint32_t mhz = fkhz / 1000, khz = fkhz % 1000;
    esp_phy_wifi_tx_tone(1, tone_arg = mhz, backoff);
    phy_stop_tx_tone(1);
    if (khz) phy_set_rf_freq_offset(phy_param[PHY_PARAM_XTAL], mhz, (int)khz);
    set_scale();
    txdc_orig = REG_READ(TXDC_REG);
    gpio_set_level(ANT_GPIO, ant);               /* the vendor CW puts the switch back to the chip antenna */
    tx_on = true;
    led(LED_TX);
}

/* Plays one period of tone_w until a byte arrives from the host, or for that many periods (0: no limit). */
static uint32_t __attribute__((noinline)) IRAM_ATTR tone_run(uint32_t n, uint32_t step, uint32_t base, uint32_t periods) {
    uint32_t late = 0;
    const unsigned s = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t t = esp_cpu_get_cycle_count() + step;
    for (;;) {
        for (uint32_t k = 0; k < n; ++k) {
            uint32_t now;
            while ((int32_t)((now = esp_cpu_get_cycle_count()) - t) < 0) {}
            REG_WRITE(TXDC_REG, base | tone_w[k]);
            if (now - t > late) late = now - t;
            t += step;
        }
        if (usb_serial_jtag_ll_rxfifo_data_available() || (periods && !--periods)) break;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(s);
    return late;
}

static void tone(int32_t khz, int32_t amp) {
    uint32_t n = (uint32_t)lroundf((float)TONE_RATE / (1000.0f * fabsf((float)khz)));
    if (khz == 0 || n < 4 || n > TONE_MAX) {
        say("ERR TONE: 0.5-500 kHz either side\r\n");
        return;
    }
    const float w = (khz > 0 ? 2.0f : -2.0f) * (float)M_PI / (float)n;
    for (uint32_t k = 0; k < n; ++k)
        tone_w[k] = iq10(lroundf(amp * cosf(w * k)), lroundf(amp * sinf(w * k)));
    tx_chain_on();
    const float f = (khz > 0 ? 1.0f : -1.0f) * TONE_RATE / 1000.0f / n;
    say("TONE %.3f MHz %+.3f kHz amp %ld scale %lu: any key stops\r\n", fkhz / 1000.0, f, (long)amp, (unsigned long)scale);
    const uint32_t late = tone_run(n, CPU_HZ / TONE_RATE, (txdc_orig & ~TXDC_IQ) | TXDC_INJECT, 0);
    uint8_t c;
    while (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    tx_off();
    say("TONE off, worst lateness %lu cycles (%.1f us)\r\n", (unsigned long)late, late / (CPU_HZ / 1e6));
}

/* ------------------------------------------------------------ DC correction (BDC, as the C3)
 * The carrier leakage of the DAC / mixer is a tone in the middle of the signal; it and its correction depend on the frequency, so
 * "BDC PUT f i q" / "BDC SAVE" keep a table of up to DCT_MAX frequencies (NVS "c5cw"/"dct"), linearly interpolated, the nearest entry
 * outside it. Without a table: the single value of "DC i q" (NVS "c5cw" dci/dcq, tx/c5_dccal.py), else none. "BDC i q" sets one by
 * hand for every frequency until BDC SAVE / RESET or a reset. "BDC TEST on [f_MHz]": the leakage alone (dc_test_run). */
#include "nvs.h"

#define DCT_MAX 16
#define DCT_TEST_US 120000000LL
typedef struct { uint32_t fkhz; float i, q; } dct_entry_t;
static dct_entry_t dct[DCT_MAX];                /* sorted by frequency */
static uint32_t dct_n;
static float dc_single[2];                      /* DC i q */
static bool dc_single_saved;
static float dc_hand[2];                        /* BDC i q */
static bool dc_by_hand;
static bool dc_test;                            /* BDC TEST on */
static uint32_t dc_test_fkhz;                   /* 0: the beacon's frequency */
static int64_t dc_test_until;                   /* esp_timer time */

static bool dc_ok(float i, float q) { return fabsf(i) <= 512.0f && fabsf(q) <= 512.0f; }

static void dc_load(void) {
    nvs_handle_t h;
    if (nvs_open("c5cw", NVS_READONLY, &h) != ESP_OK) return;
    uint32_t i = 0, q = 0;
    if (nvs_get_u32(h, "dci", &i) == ESP_OK && nvs_get_u32(h, "dcq", &q) == ESP_OK) {
        memcpy(&dc_single[0], &i, 4);
        memcpy(&dc_single[1], &q, 4);
        dc_single_saved = dc_ok(dc_single[0], dc_single[1]);
        if (!dc_single_saved) dc_single[0] = dc_single[1] = 0.0f;
    }
    size_t n = sizeof(dct);
    if (nvs_get_blob(h, "dct", dct, &n) == ESP_OK && n % sizeof(dct_entry_t) == 0) {
        dct_n = (uint32_t)(n / sizeof(dct_entry_t));
        for (uint32_t k = 0; k < dct_n; ++k)
            if (!dc_ok(dct[k].i, dct[k].q) || (k && dct[k].fkhz <= dct[k - 1].fkhz)) dct_n = 0;     /* damaged: ignore it */
    }
    nvs_close(h);
}

static void dc_save(void) {                     /* DC i q */
    nvs_handle_t h;
    if (nvs_open("c5cw", NVS_READWRITE, &h) != ESP_OK) return;
    uint32_t i, q;
    memcpy(&i, &dc_single[0], 4);
    memcpy(&q, &dc_single[1], 4);
    nvs_set_u32(h, "dci", i);
    nvs_set_u32(h, "dcq", q);
    nvs_commit(h);
    nvs_close(h);
    dc_single_saved = true;
}

/* The correction for fkhz: by hand, else the table, else the single value. *src: "hand", "table", "saved", "none". */
static void dc_for(uint32_t f, float *i, float *q, const char **src) {
    if (dc_by_hand) { *i = dc_hand[0]; *q = dc_hand[1]; *src = "hand"; return; }
    if (!dct_n) { *i = dc_single[0]; *q = dc_single[1]; *src = dc_single_saved ? "saved" : "none"; return; }
    *src = "table";
    uint32_t k = 0;
    while (k < dct_n && dct[k].fkhz < f) ++k;
    if (k == 0 || k == dct_n) { const dct_entry_t *e = &dct[k ? k - 1 : 0]; *i = e->i; *q = e->q; return; }
    const dct_entry_t *a = &dct[k - 1], *b = &dct[k];
    const float t = (float)(f - a->fkhz) / (float)(b->fkhz - a->fkhz);
    *i = a->i + t * (b->i - a->i);
    *q = a->q + t * (b->q - a->q);
}

static bool dct_store(void) {
    nvs_handle_t h;
    if (nvs_open("c5cw", NVS_READWRITE, &h) != ESP_OK) return false;
    const esp_err_t e = dct_n ? nvs_set_blob(h, "dct", dct, dct_n * sizeof(dct_entry_t)) : nvs_erase_key(h, "dct");
    const bool ok = (e == ESP_OK || (!dct_n && e == ESP_ERR_NVS_NOT_FOUND)) && nvs_commit(h) == ESP_OK;
    nvs_close(h);
    return ok;
}

/* i q for f into the table (replaces an entry within 50 kHz; the table full: refused). */
static bool dct_put(uint32_t f, float i, float q) {
    uint32_t k = 0;
    while (k < dct_n && dct[k].fkhz + 50u < f) ++k;
    if (k < dct_n && dct[k].fkhz <= f + 50u) {
        dct[k].fkhz = f;
    } else {
        if (dct_n == DCT_MAX) return false;
        memmove(&dct[k + 1], &dct[k], (dct_n - k) * sizeof(dct_entry_t));
        ++dct_n;
        dct[k].fkhz = f;
    }
    dct[k].i = i;
    dct[k].q = q;
    return dct_store();
}

static bool dc_reset(void) {                    /* BDC RESET: the table and the single value gone */
    dct_n = 0;
    bool ok = dct_store();
    nvs_handle_t h;
    if (nvs_open("c5cw", NVS_READWRITE, &h) == ESP_OK) {
        esp_err_t e = nvs_erase_key(h, "dci");
        ok = ok && (e == ESP_OK || e == ESP_ERR_NVS_NOT_FOUND);
        e = nvs_erase_key(h, "dcq");
        ok = ok && (e == ESP_OK || e == ESP_ERR_NVS_NOT_FOUND) && nvs_commit(h) == ESP_OK;
        nvs_close(h);
    } else {
        ok = false;
    }
    dc_single[0] = dc_single[1] = 0.0f;
    dc_single_saved = false;
    dc_by_hand = false;
    return ok;
}

static void dc_say(uint32_t f) {
    float i, q;
    const char *src;
    dc_for(f, &i, &q, &src);
    say("BDC %.3f %.3f (%s) F %lu TEST %u\r\n", (double)i, (double)q, src, (unsigned long)f, (unsigned)dc_test);
}

/* ------------------------------------------------------------ beacon
 * The C3's tables (qpsk_lut.h lut_build_t: 4 groups of 2 symbols, RRC span 8, entry T[(g * S + j) * 16 + idx], the sum of the four
 * entries xor LUT_XOR is the word). The C3's loops are cycle-exact for its core; here the loop is paced on the cycle counter instead
 * (absolute deadlines: a stall becomes jitter, not a lost sample): each sample slot stores the word of this symbol and computes the
 * same sample of the next one. Symbols: 2 bits each in beacon.bin, symbol n in byte n / 4 at bits 2 (n % 4) (bit 0 = I, 1 = Q).
 * With four tables a sample costs about 30 instructions plus the 15-cycle store: 60 cycles (S 12 at 333 kBd) keep up, 45 (S 16) not.
 * S 8 and 16 use qpsk_lut.h lut_build_g with two groups of 4 symbols instead (same span 8, entry T[(k * 256 + idx) * S + j]):
 * two loads and one add per sample. */

static float rrc_pulse(float t, float b) {
    const float pi = 3.14159265f;
    if (fabsf(t) < 1e-6f) return 1.0f - b + 4.0f * b / pi;
    if (fabsf(fabsf(t) - 1.0f / (4.0f * b)) < 1e-4f)
        return b / 1.41421356f * ((1.0f + 2.0f / pi) * sinf(pi / (4.0f * b)) + (1.0f - 2.0f / pi) * cosf(pi / (4.0f * b)));
    const float num = sinf(pi * t * (1.0f - b)) + 4.0f * b * t * cosf(pi * t * (1.0f + b));
    const float den = pi * t * (1.0f - (4.0f * b * t) * (4.0f * b * t));
    return num / den;
}

#include "qpsk_lut.h"

#ifndef C5CW_NO_BEACON
extern const uint8_t beacon_bin_start[] asm("_binary_beacon_bin_start");
extern const uint8_t beacon_bin_end[] asm("_binary_beacon_bin_end");
#endif

#define BEACON_SMAX 48u

typedef struct {
    uint32_t late_max, late;         /* worst lateness [cycles], samples a whole period late (S 8, 16: first samples of symbols only) */
    uint32_t end_max;                /* S 8, 16: worst lateness of the last sample of a symbol (the loop caught up when small) */
    uint64_t symbols;
} beacon_stats_t;

static uint32_t __attribute__((noinline, unused)) IRAM_ATTR beacon_loop(const uint8_t *ring, uint32_t nsym, const int32_t *T, uint32_t S, sample_clock_t clk,
                                      uint32_t xb, beacon_stats_t *st) {
    uint32_t w[2][BEACON_SMAX], off[BEACON_SMAX];
    const uint32_t step = clk.period / S;
    for (uint32_t j = 0; j < S; ++j) off[j] = j * clk.period / S;
    const int32_t *const T1 = T + S * 16u, *const T2 = T + 2u * S * 16u, *const T3 = T + 3u * S * 16u;
    uint32_t q = nsym - 1000u, C = 0, phase = 0, cur = 0, late_max = 0, late = 0, n = 0;
    for (uint32_t k = 0; k < 8; ++k) {                        /* history of the first symbol */
        C = C << 2 | (ring[q >> 2] >> ((q & 3u) * 2u) & 3u);
        q = q + 1u == nsym ? 0 : q + 1u;
    }
    for (uint32_t j = 0; j < S; ++j)
        w[0][j] = (uint32_t)(T[j * 16u + (C & 15u)] + T1[j * 16u + (C >> 4 & 15u)] + T2[j * 16u + (C >> 8 & 15u)] +
                             T3[j * 16u + (C >> 12 & 15u)]) ^ xb;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t t = esp_cpu_get_cycle_count() + 2000u;
    for (;;) {
        C = C << 2 | (ring[q >> 2] >> ((q & 3u) * 2u) & 3u);   /* the next symbol */
        q = q + 1u == nsym ? 0 : q + 1u;
        const int32_t *p0 = T + (C & 15u), *p1 = T1 + (C >> 4 & 15u), *p2 = T2 + (C >> 8 & 15u), *p3 = T3 + (C >> 12 & 15u);
        const uint32_t *now_w = w[cur];
        uint32_t *next_w = w[cur ^ 1u];
        for (uint32_t j = 0; j < S; ++j) {
            const uint32_t tj = t + off[j];
            uint32_t c;
            while ((int32_t)((c = esp_cpu_get_cycle_count()) - tj) < 0) {}
            REG_WRITE(TXDC_REG, now_w[j]);
            c -= tj;
            if (c > late_max) late_max = c;
            if (c >= step) ++late;
            next_w[j] = (uint32_t)(p0[0] + p1[0] + p2[0] + p3[0]) ^ xb;
            p0 += 16;
            p1 += 16;
            p2 += 16;
            p3 += 16;
        }
        cur ^= 1u;
        t += sample_clock_next(clk, &phase);
        if (!(++n & 255u) && usb_serial_jtag_ll_rxfifo_data_available()) break;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    st->late_max = late_max;
    st->late = late;
    st->symbols = n;
    return n;
}

/* Two 4-symbol tables (lut_build_g, ngroups 2), S 8 or 16. */
static uint32_t __attribute__((noinline, unused)) IRAM_ATTR beacon_loop_g(const uint8_t *ring, uint32_t nsym, const int32_t *T, uint32_t S,
                                                                  sample_clock_t clk, uint32_t xb, beacon_stats_t *st) {
    uint32_t w[2][BEACON_SMAX];
    const uint32_t step = clk.period / S;                     /* the symbol's spare cycles go to its last sample */
    const int32_t *const T1 = T + 256u * S;
    uint32_t q = nsym - 1000u, C = 0, phase = 0, cur = 0, late_max = 0, late = 0, end_max = 0, n = 0;
    for (uint32_t k = 0; k < 8; ++k) {                        /* history of the first symbol */
        C = C << 2 | (ring[q >> 2] >> ((q & 3u) * 2u) & 3u);
        q = q + 1u == nsym ? 0 : q + 1u;
    }
    for (uint32_t j = 0; j < S; ++j) w[0][j] = (uint32_t)(T[(C & 255u) * S + j] + T1[(C >> 8 & 255u) * S + j]) ^ xb;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t t = esp_cpu_get_cycle_count() + 2000u;
    for (;;) {
        C = C << 2 | (ring[q >> 2] >> ((q & 3u) * 2u) & 3u);   /* the next symbol */
        q = q + 1u == nsym ? 0 : q + 1u;
        const int32_t *p0 = T + (C & 255u) * S, *p1 = T1 + (C >> 8 & 255u) * S;
        const uint32_t *now_w = w[cur];
        uint32_t *next_w = w[cur ^ 1u];
        uint32_t c;
        while ((int32_t)((c = esp_cpu_get_cycle_count()) - t) < 0) {}
        REG_WRITE(TXDC_REG, now_w[0]);
        c -= t;                                               /* lateness after the symbol's overhead: the worst sample */
        if (c > late_max) late_max = c;
        if (c >= step) ++late;
        next_w[0] = (uint32_t)(p0[0] + p1[0]) ^ xb;
        uint32_t tj = t;
        for (uint32_t j = 1; j < S; ++j) {
            tj += step;
            while ((int32_t)(esp_cpu_get_cycle_count() - tj) < 0) {}
            REG_WRITE(TXDC_REG, now_w[j]);
            next_w[j] = (uint32_t)(p0[j] + p1[j]) ^ xb;
        }
        c = esp_cpu_get_cycle_count() - tj;
        if (c > end_max) end_max = c;
        cur ^= 1u;
        t += sample_clock_next(clk, &phase);
        if (!(++n & 255u) && usb_serial_jtag_ll_rxfifo_data_available()) break;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    st->late_max = late_max;
    st->late = late;
    st->symbols = n;
    st->end_max = end_max;
    return n;
}

/* S 16 unrolled. Measured costs (BENCH, cycles): a TXDC store 15, a DRAM load right after it about 9 more, rdcycle about 3. Each slot
 * loads its two table entries and stores their sum (no word buffer: one load and one store fewer than beacon_loop_g); the
 * symbol-boundary work (next symbol, its table rows, the symbol clock, the USB check) is spread over the slots.
 * fl (a loop stored in the "video" partition, too big for the RAM; reading it through the flash cache would stall the loop): as the
 * C3's lutf.S, the symbols come from a 512 byte RAM ring that SPI1 fills: a 64 byte read starts every 256 symbols and its 16 words
 * move into the ring one per 16 symbols, 8 symbols after the start onwards (the read takes about 14 us). The ring keeps VIDEO_LEAD
 * bytes ahead of the loop; the stream joins up over the loop's end through the 64 bytes repeated after it (VIDEO_TAIL). */
#define B16_SLOT(j, extra)                                                     \
    do {                                                                       \
        const uint32_t wj = (uint32_t)(p0[j] + p1[j]) ^ xb;                    \
        while ((int32_t)(esp_cpu_get_cycle_count() - tj) < 0) {}               \
        REG_WRITE(TXDC_REG, wj);                                               \
        extra;                                                                 \
        tj += step;                                                            \
    } while (0)

#define VIDEO_RING 512u
#define VIDEO_LEAD 256u

typedef struct {
    uint32_t base, off, bytes;       /* flash address of the loop's symbols, the next read's offset in them, nsym / 4 */
    uint32_t *ring;                  /* VIDEO_RING bytes, VIDEO_LEAD of them filled from offset 0 and the read of the next 64 started */
} flash_feed_t;

static inline __attribute__((always_inline)) uint32_t b16_body(const uint8_t *ring, uint32_t nsym, const int32_t *T, sample_clock_t clk,
                                                               uint32_t xb, beacon_stats_t *st, flash_feed_t *ff, const bool fl) {
    const uint32_t step = clk.period / 16u;                   /* the symbol's spare cycles go to its last sample */
    const int32_t *const T1 = T + 256u * 16u;
    uint32_t q = fl ? 0 : nsym - 1000u, C = 0, phase = 0, late_max = 0, late = 0, end_max = 0, n = 0;
    if (!fl)
        for (uint32_t k = 0; k < 8; ++k) {                    /* history of the first symbol */
            C = C << 2 | (ring[q >> 2] >> ((q & 3u) * 2u) & 3u);
            q = q + 1u == nsym ? 0 : q + 1u;
        }
    const uint8_t *const fr = fl ? (const uint8_t *)ff->ring : ring;
    uint32_t *const fw = fl ? ff->ring : NULL;
    uint32_t foff = fl ? ff->off : 0, fadr = 0, wpos = VIDEO_LEAD / 4u;
    const int32_t *p0 = T + (C & 255u) * 16u, *p1 = T1 + (C >> 8 & 255u) * 16u, *np0, *np1;
    uint32_t b = 0, sym = 0, t1 = 0, brk = 0;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t t = esp_cpu_get_cycle_count() + 2000u;
    for (;;) {
        uint32_t tj = t;
        B16_SLOT(0, {
            const uint32_t c = esp_cpu_get_cycle_count() - t;
            if (c > late_max) late_max = c;
            if (c >= step) ++late;
        });
        B16_SLOT(1, );
        B16_SLOT(2, b = fl ? fr[(q >> 2) & (VIDEO_RING - 1u)] : ring[q >> 2]);
        B16_SLOT(3, sym = b >> ((q & 3u) * 2u) & 3u);
        B16_SLOT(4, q = fl ? q + 1u : q + 1u == nsym ? 0 : q + 1u);
        B16_SLOT(5, C = C << 2 | sym);
        B16_SLOT(6, np0 = T + (C & 255u) * 16u);
        B16_SLOT(7, np1 = T1 + (C >> 8 & 255u) * 16u);
        B16_SLOT(8, if (fl && (q & 255u) == 255u) fadr = ff->base + foff);
        B16_SLOT(9, t1 = t + sample_clock_next(clk, &phase));
        B16_SLOT(10, if (fl && (q & 255u) == 255u) { foff += 64u; if (foff >= ff->bytes) foff -= ff->bytes; });
        B16_SLOT(11, brk = !(++n & 255u) && usb_serial_jtag_ll_rxfifo_data_available());
        B16_SLOT(12, if (fl && (q & 15u) == 8u) {             /* a word of the last read into the ring */
            fw[wpos] = SPIMEM1.data_buf[(q >> 4) & 15u];
            wpos = (wpos + 1u) & (VIDEO_RING / 4u - 1u);
        });
        B16_SLOT(13, if (fl && (q & 255u) == 0u) SPIMEM1.addr = fadr);   /* fadr was set at q & 255 == 255 */
        B16_SLOT(14, if (fl && (q & 255u) == 0u) SPIMEM1.cmd.val = 0x40000u);   /* SPI_MEM_USR: the next 64 bytes */
        B16_SLOT(15, {
            const uint32_t c = esp_cpu_get_cycle_count() - tj;
            if (c > end_max) end_max = c;
        });
        if (brk) break;
        t = t1;
        p0 = np0;
        p1 = np1;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    st->late_max = late_max;
    st->late = late;
    st->symbols = n;
    st->end_max = end_max;
    return n;
}

static uint32_t __attribute__((noinline, unused)) IRAM_ATTR beacon_loop_16(const uint8_t *ring, uint32_t nsym, const int32_t *T,
                                                                   sample_clock_t clk, uint32_t xb, beacon_stats_t *st) {
    return b16_body(ring, nsym, T, clk, xb, st, NULL, false);
}

static uint32_t __attribute__((noinline)) IRAM_ATTR beacon_loop_16f(flash_feed_t *ff, const int32_t *T, sample_clock_t clk, uint32_t xb,
                                                                    beacon_stats_t *st) {
    return b16_body(NULL, 0, T, clk, xb, st, ff, true);
}

/* dci, dcq: DC added to the I and Q words [DAC codes, as CW i q] against the carrier leakage (tx/c5_dccal.py finds them).
 * drain: the bytes that stopped it are thrown away (BEACON typed by hand), else they are the start of the host's next line. */
static void beacon(int32_t amp, uint32_t S, float dci, float dcq, bool drain) {
#ifdef C5CW_NO_BEACON
    (void)amp, (void)S, (void)dci, (void)dcq, (void)drain;
    say("ERR no built-in beacon in this build: store a loop (the app's Save as beacon, VIDW)\r\n");
}
#else
    const size_t bytes = (size_t)(beacon_bin_end - beacon_bin_start);
    if (S < 8 || S > BEACON_SMAX || amp < 1 || amp > 480) {
        say("ERR BEACON [amp 1-480] [S 8-%u]\r\n", (unsigned)BEACON_SMAX);
        return;
    }
    if (bytes < BEACON_SYMBOLS / 4u) {
        say("ERR beacon.bin: %u bytes\r\n", (unsigned)bytes);
        return;
    }
    uint8_t *ring = malloc(BEACON_SYMBOLS / 4u);
    const bool g4 = S == 8u || S == 16u;
    int32_t *T = malloc(g4 ? 2u * 256u * S * sizeof(int32_t) : lutt_bytes(S));
    float *hb = malloc(8u * S * sizeof(float));
    if (!ring || !T || !hb) {
        say("ERR no memory\r\n");
        free(ring);
        free(T);
        free(hb);
        return;
    }
    memcpy(ring, beacon_bin_start, BEACON_SYMBOLS / 4u); /* flash reads would stall the loop */
    if (g4) lut_build_g(T, S, 0, 0.35f, (float)amp, dci, dcq, 1.0f, 0.0f, 2u);
    else lut_build_t(T, S, 0, 0.35f, (float)amp, dci, dcq, 1.0f, 0.0f, hb);
    free(hb);
    const sample_clock_t clk = sample_clock_init(CPU_HZ, BEACON_BAUD);
    tx_chain_on();
    say("BEACON %.3f MHz %lu Bd S %lu (%.3f MS/s, %lu cycles per sample) amp %ld scale %lu dc %.1f %.1f, QPSK %s: any key stops\r\n",
        fkhz / 1000.0, (unsigned long)BEACON_BAUD, (unsigned long)S, BEACON_BAUD * (double)S / 1e6, (unsigned long)(clk.period / S),
        (long)amp, (unsigned long)scale, (double)dci, (double)dcq, BEACON_FEC);
    beacon_stats_t st = {0};
    const uint32_t xb = ((txdc_orig & ~TXDC_IQ) | TXDC_INJECT) ^ LUT_XOR;
    if (S == 16u) beacon_loop_16(ring, BEACON_SYMBOLS, T, clk, xb, &st);
    else if (g4) beacon_loop_g(ring, BEACON_SYMBOLS, T, S, clk, xb, &st);
    else beacon_loop(ring, BEACON_SYMBOLS, T, S, clk, xb, &st);
    uint8_t c;
    while (drain && usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    tx_off();
    free(ring);
    free(T);
    say("BEACON off after %.1f s, worst lateness %lu cycles (%.2f us), %lu samples a period late\r\n",
        (double)st.symbols / BEACON_BAUD, (unsigned long)st.late_max, st.late_max / (CPU_HZ / 1e6), (unsigned long)st.late);
    if (g4) say("  last sample of a symbol: worst %lu cycles after its deadline (store and table work included)\r\n", (unsigned long)st.end_max);
}
#endif


/* ------------------------------------------------------------ stored loop (VIDW)
 * As the C3's fork of ESP32-DATV-SA, so that the app's "Save as beacon" (LoopMaker, C3Link.storeLoop) writes it unchanged: the
 * "video" partition holds a DATVVID1 version 3 header (4 KB) and the symbols, 4 per byte (as beacon.bin), the first 64 bytes
 * repeated after them. A valid loop starts by itself after power-up, at its frequency; any byte from USB stops it.
 * The loop's level is the C3's DAC amplitude (AMP on the TX screen, 300 by default): here amp x 150 / 300, the beacon's default
 * 150 (its spectrum checked over the air on the tinySA, 2026-11-10; the output power has not been measured - no cable to the analyser). DC against the carrier leakage: DC i q (kept in NVS, tx/c5_dccal.py). */
#include "esp_app_desc.h"
#include "esp_partition.h"
#include "esp_rom_crc.h"
#include "nvs.h"

typedef struct {
    char magic[8];
    uint32_t version, nsym, baud, bytes, crc;                        /* bytes: the symbol data and VIDEO_TAIL bytes repeated past its end */
    uint8_t pilots, modcod, flags, pad;                              /* flags bit 0: short FECFRAMEs */
    char service[32], provider[32];
    uint32_t freq_khz;                                               /* 0: F as it is */
    uint16_t amp, pad2;
} video_hdr_t;
#define VIDEO_HEADER 4096u
#define VIDEO_TAIL 64u
#define VIDEO_SHORT 1u
#define VIDEO_BAUD_MAX 333334u                                       /* S 16: 45 cycles per sample */

static const video_hdr_t *video;
static uint32_t video_addr;                                          /* flash address of the partition */
static esp_partition_mmap_handle_t video_map;
static bool video_checked;

static uint32_t video_frame_syms(const video_hdr_t *v) {           /* a PLFRAME in QPSK: 360 slots normal, 90 short */
    const uint32_t slots = (v->flags & VIDEO_SHORT) ? 90u : 360u;
    return 90u + slots * 90u + (v->pilots ? 36u * ((slots - 1u) / 16u) : 0u);
}

static const video_hdr_t *video_find(void) {                        /* the mapped partition when it holds a valid loop (checked once) */
    if (video_checked) return video;
    video_checked = true;
    const esp_partition_t *part = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, 0x40, "video");
    const void *map;
    esp_partition_mmap_handle_t h;
    if (!part || esp_partition_mmap(part, 0, part->size, ESP_PARTITION_MMAP_DATA, &map, &h) != ESP_OK) {
        say("VIDEO no partition\r\n");
        return NULL;
    }
    const video_hdr_t *v = map;
    const char *why = NULL;
    if (memcmp(v->magic, "DATVVID1", 8) || v->version != 3u) why = "none";
    else if (v->baud < 1000u || v->baud > VIDEO_BAUD_MAX || v->modcod < 1u || v->modcod > 11u || v->nsym == 0 ||
             v->nsym % (2u * video_frame_syms(v)) || v->nsym / 4u < 2u * VIDEO_LEAD)
        why = "unsupported format";
    else if (v->bytes != v->nsym / 4u + VIDEO_TAIL || VIDEO_HEADER + v->bytes > part->size) why = "bad length";
    else if (esp_rom_crc32_le(0, (const uint8_t *)map + VIDEO_HEADER, v->bytes) != v->crc) why = "bad CRC";
    if (why) {
        say("VIDEO %s\r\n", why);
        esp_partition_munmap(h);
        return NULL;
    }
    video_map = h;
    video_addr = part->address;
    say("VIDEO %.31s / %.31s, %lu symbols at %lu Bd, MODCOD %u%s, %lu kHz amp %u\r\n", v->service, v->provider, (unsigned long)v->nsym,
        (unsigned long)v->baud, (unsigned)v->modcod, (v->flags & VIDEO_SHORT) ? " short" : "", (unsigned long)v->freq_khz, (unsigned)v->amp);
    return video = v;
}

static void video_forget(void) {                                    /* before the partition is written */
    if (video) esp_partition_munmap(video_map);
    video = NULL;
    video_checked = false;
}

typedef struct { uint32_t ctrl, user, user1, user2, miso, mosi; } spi1_saved_t;
static uint32_t spi_dummy = 7;      /* the C3 takes 8 (0x0B: 8 dummy clocks); on the C5 8 read every bit one clock late (SPIT, 2026-11-11) */

static void spi1_setup(spi1_saved_t *sv) {                          /* fast read 0x0B, single line, 24 bit address, spi_dummy, 64 bytes */
    spi_mem_dev_t *d = &SPIMEM1;
    spimem_flash_ll_get_common_command_register_info(d, &sv->ctrl, &sv->user, &sv->user1, &sv->user2);
    sv->miso = d->miso_dlen.val;
    sv->mosi = d->mosi_dlen.val;
    spimem_flash_ll_reset(d);
    spimem_flash_ll_set_read_mode(d, SPI_FLASH_FASTRD);
    spimem_flash_ll_set_command(d, 0x0B, 8);
    spimem_flash_ll_set_addr_bitlen(d, 24);
    spimem_flash_ll_set_dummy(d, spi_dummy);
    spimem_flash_ll_set_mosi_bitlen(d, 0);
    spimem_flash_ll_set_miso_bitlen(d, 512);
}

static void spi1_restore(const spi1_saved_t *sv) {                  /* the flash driver's own setting back */
    spi_mem_dev_t *d = &SPIMEM1;
    while (!spimem_flash_ll_cmd_is_done(d)) {}
    spimem_flash_ll_set_common_command_register_info(d, sv->ctrl, sv->user, sv->user1, sv->user2);
    d->miso_dlen.val = sv->miso;
    d->mosi_dlen.val = sv->mosi;
}

/* Plays the stored loop until a byte arrives (drain: as beacon()); false if there is none. */
static bool video_play(bool drain) {
    const video_hdr_t *v = video_find();
    if (!v) return false;
    const uint32_t S = 16u;
    int32_t amp = (int32_t)(v->amp ? v->amp : 300u) * 150 / 300;
    amp = amp < 1 ? 1 : amp > 480 ? 480 : amp;
    if (v->freq_khz >= FMIN_KHZ && v->freq_khz <= FMAX_KHZ) fkhz = v->freq_khz;
    int32_t *T = malloc(2u * 256u * S * sizeof(int32_t));
    uint32_t *ring = heap_caps_aligned_alloc(4, VIDEO_RING, MALLOC_CAP_INTERNAL);
    float dc_i, dc_q;
    const char *dc_src;
    dc_for(fkhz, &dc_i, &dc_q, &dc_src);
    if (!T || !ring) {
        say("ERR no memory\r\n");
        free(T);
        free(ring);
        return true;
    }
    lut_build_g(T, S, 0, 0.35f, (float)amp, dc_i, dc_q, 1.0f, 0.0f, 2u);
    memcpy(ring, (const uint8_t *)v + VIDEO_HEADER, VIDEO_LEAD);    /* the lead through the cache, before the loop */
    flash_feed_t ff = {video_addr + VIDEO_HEADER, VIDEO_LEAD + 64u, v->nsym / 4u, ring};
    const sample_clock_t clk = sample_clock_init(CPU_HZ, v->baud);
    tx_chain_on();
    say("BEACON %.3f MHz %lu Bd S 16 (%lu cycles per sample) amp %ld scale %lu dc %.1f %.1f, stored loop %.31s MODCOD %u%s, %.1f s: any key stops\r\n",
        fkhz / 1000.0, (unsigned long)v->baud, (unsigned long)(clk.period / S), (long)amp, (unsigned long)scale, (double)dc_i, (double)dc_q,
        v->service, (unsigned)v->modcod, (v->flags & VIDEO_SHORT) ? " short" : "", (double)v->nsym / v->baud);
    spi1_saved_t sv;
    spi1_setup(&sv);
    spimem_flash_ll_set_address(&SPIMEM1, video_addr + VIDEO_HEADER + VIDEO_LEAD);   /* the first read, done before its words are due */
    spimem_flash_ll_user_start(&SPIMEM1, false);
    beacon_stats_t st = {0};
    beacon_loop_16f(&ff, T, clk, ((txdc_orig & ~TXDC_IQ) | TXDC_INJECT) ^ LUT_XOR, &st);
    spi1_restore(&sv);
    uint8_t c;
    while (drain && usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    tx_off();
    free(ring);
    free(T);
    say("BEACON off after %.1f s, worst lateness %lu cycles (%.2f us), %lu symbols late, last sample worst %lu cycles\r\n",
        (double)st.symbols / v->baud, (unsigned long)st.late_max, st.late_max / (CPU_HZ / 1e6), (unsigned long)st.late,
        (unsigned long)st.end_max);
    return true;
}

/* SPIT: diagnostics of the flash feed - SPI1 set up as for the loop, 64 byte reads at a few offsets compared with the mapped loop */
static void spi_test(void) {
    const video_hdr_t *v = video_find();
    if (!v) {
        say("ERR SPIT: no stored loop\r\n");
        return;
    }
    spi1_saved_t sv;
    uint32_t got[16], bad = 0, cyc = 0;
    const uint32_t offs[4] = {VIDEO_LEAD, VIDEO_LEAD + 64u, v->nsym / 4u - 64u, 65536u};
    for (int k = 0; k < 4; ++k) {
        const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
        spi1_setup(&sv);
        spimem_flash_ll_set_address(&SPIMEM1, video_addr + VIDEO_HEADER + offs[k]);
        const uint32_t c0 = esp_cpu_get_cycle_count();
        SPIMEM1.cmd.val = 0x40000u;
        while (!spimem_flash_ll_cmd_is_done(&SPIMEM1)) {}
        cyc = esp_cpu_get_cycle_count() - c0;
        for (int i = 0; i < 16; ++i) got[i] = SPIMEM1.data_buf[i];
        spi1_restore(&sv);
        portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
        const uint32_t *want = (const uint32_t *)((const uint8_t *)v + VIDEO_HEADER + offs[k]);
        uint32_t diff = 0;
        for (int i = 0; i < 16; ++i) diff += got[i] != want[i];
        bad += diff;
        say("SPIT @%lu: read %lu cycles (%.1f us), %lu of 16 words differ; got %08lx %08lx want %08lx %08lx\r\n", (unsigned long)offs[k], (unsigned long)cyc, cyc / (CPU_HZ / 1e6), (unsigned long)diff,
            (unsigned long)got[0], (unsigned long)got[1], (unsigned long)want[0], (unsigned long)want[1]);
    }
    say("SPIT dummy %lu: %s\r\n", (unsigned long)spi_dummy, bad ? "FAIL" : "OK");
}

static bool usb_read_exact(uint8_t *b, uint32_t n, int64_t timeout_us) {
    const int64_t end = esp_timer_get_time() + timeout_us;
    while (n) {
        const int k = usb_serial_jtag_ll_read_rxfifo(b, n);
        b += k;
        n -= (uint32_t)k;
        if (!k && esp_timer_get_time() > end) return false;
    }
    return true;
}

/* VIDW BEGIN bytes crc32   erase what the file needs; OK VIDW BEGIN bytes
 * VIDW DATA offset n crc32 then exactly n raw bytes (n <= 4096); written at offset; OK VIDW DATA offset
 * VIDW END                 CRC32 of all against BEGIN's, the loop checked: OK VIDW END crc (VIDEO ... before it), then it plays
 * VIDW ERASE               erase the header: nothing is transmitted after power-up */
static uint32_t vidw_total, vidw_crc, vidw_written;
static bool vidw_open;

static void vidw(const char *a0, const char *a1s, const char *a2s, const char *a3s) {
    const esp_partition_t *part = esp_partition_find_first(ESP_PARTITION_TYPE_DATA, 0x40, "video");
    if (!part) {
        say("ERR VIDW no video partition\r\n");
        return;
    }
    const uint32_t a1 = a1s ? strtoul(a1s, NULL, 10) : 0, a2 = a2s ? strtoul(a2s, NULL, 10) : 0, a3 = a3s ? strtoul(a3s, NULL, 16) : 0;
    if (a0 && !strcmp(a0, "BEGIN")) {
        if (a1 < VIDEO_HEADER || a1 > part->size) {
            say("ERR VIDW %lu bytes do not fit %lu\r\n", (unsigned long)a1, (unsigned long)part->size);
            return;
        }
        video_forget();
        const uint32_t erase = (a1 + 4095u) & ~4095u;
        if (esp_partition_erase_range(part, 0, erase) != ESP_OK) {
            say("ERR VIDW erase\r\n");
            return;
        }
        vidw_total = a1;
        vidw_crc = (uint32_t)strtoul(a2s ? a2s : "0", NULL, 16);
        vidw_written = 0;
        vidw_open = true;
        say("OK VIDW BEGIN %lu\r\n", (unsigned long)vidw_total);
    } else if (a0 && !strcmp(a0, "DATA")) {
        static uint8_t buf[4096];
        if (!vidw_open || a2 == 0 || a2 > 4096u || a1 + a2 > vidw_total) {
            say("ERR VIDW DATA out of order\r\n");
            return;
        }
        if (!usb_read_exact(buf, a2, 2000000)) {
            say("ERR VIDW DATA timeout\r\n");
            return;
        }
        if (esp_rom_crc32_le(0, buf, a2) != a3) {
            say("ERR VIDW DATA crc %lu\r\n", (unsigned long)a1);
            return;
        }
        if (esp_partition_write(part, a1, buf, a2) != ESP_OK) {
            say("ERR VIDW write %lu\r\n", (unsigned long)a1);
            return;
        }
        vidw_written += a2;
        say("OK VIDW DATA %lu\r\n", (unsigned long)a1);
    } else if (a0 && !strcmp(a0, "END")) {
        if (!vidw_open) {
            say("ERR VIDW END without BEGIN\r\n");
            return;
        }
        vidw_open = false;
        static uint8_t buf[4096];
        uint32_t crc = 0;
        for (uint32_t o = 0; o < vidw_total; o += sizeof(buf)) {
            const uint32_t n = vidw_total - o < sizeof(buf) ? vidw_total - o : sizeof(buf);
            if (esp_partition_read(part, o, buf, n) != ESP_OK) {
                say("ERR VIDW read\r\n");
                return;
            }
            crc = esp_rom_crc32_le(crc, buf, n);
        }
        if (crc != vidw_crc) {
            say("ERR VIDW crc %08lx, expected %08lx (%lu bytes written)\r\n", (unsigned long)crc, (unsigned long)vidw_crc,
                (unsigned long)vidw_written);
            return;
        }
        video_forget();
        const bool ok = video_find() != NULL;
        say("%s VIDW END %08lx\r\n", ok ? "OK" : "ERR", (unsigned long)crc);
        host_seen = false;                                           /* as the C3: the new loop goes on air at once */
        beacon_restart = true;
    } else if (a0 && !strcmp(a0, "ERASE")) {
        video_forget();
        if (esp_partition_erase_range(part, 0, 4096) != ESP_OK) {
            say("ERR VIDW erase\r\n");
            return;
        }
        say("OK VIDW ERASE\r\n");
        host_seen = false;                                           /* the built-in beacon, if there is one */
        beacon_restart = true;
    } else {
        say("ERR VIDW BEGIN bytes crc32hex | DATA offset n crc32hex + n bytes | END | ERASE\r\n");
    }
}

/* ------------------------------------------------------------ BDC TEST, BDC */

static bool parse_freq(const char *s, uint32_t *khz);

/* The frequency the DC correction is for now: BDC TEST's, else the stored loop's, else F. */
static uint32_t dc_fkhz(void) {
    if (dc_test && dc_test_fkhz) return dc_test_fkhz;
    const video_hdr_t *v = video_find();
    return v && v->freq_khz >= FMIN_KHZ && v->freq_khz <= FMAX_KHZ ? v->freq_khz : fkhz;
}

/* BDC TEST: the leakage alone. The DC correction as 16 DAC words at TONE_RATE: the 10-bit fields take whole codes, so the fraction is
 * spread over them by error feedback (the mean exact to 1/16 code, the rest lines at multiples of 125 kHz, away from the leakage). */
static void dc_test_words(float dci, float dcq) {
    float ei = 0.0f, eq = 0.0f;
    for (uint32_t j = 0; j < 16u; ++j) {
        const int32_t ri = (int32_t)lroundf(dci + ei), rq = (int32_t)lroundf(dcq + eq);
        ei += dci - (float)ri;
        eq += dcq - (float)rq;
        tone_w[j] = iq10(ri, rq);
    }
}

/* A command line read while the test transmits. "BDC i q", "BDC" and "BDC GET" are answered here: a new correction only rewrites the
 * words, the PHY stays as it is (starting the transmitter again calibrates it afresh, which moves the leakage by tenths of a code).
 * Any other line goes to dc_pending for the main loop. Returns true to go on transmitting. */
static char dc_pending[128];

static bool dc_test_line(void) {
    char line[128];
    size_t n = 0;
    const int64_t t0 = esp_timer_get_time();
    for (;;) {
        uint8_t ch;
        if (usb_serial_jtag_ll_read_rxfifo(&ch, 1)) {
            if (ch == '\r') continue;
            if (ch == '\n') break;
            if (n < sizeof(line) - 1) line[n++] = (char)ch;
        } else if (esp_timer_get_time() - t0 > 100000) {             /* no end of line within 0.1 s */
            break;
        }
    }
    line[n] = 0;
    if (!n) return true;
    const char *arg = line + 3;
    while (*arg == ' ') ++arg;
    if (!strncmp(line, "BDC", 3) && (line[3] == 0 || line[3] == ' ')) {
        char *e1, *e2;
        const float i = strtof(arg, &e1), q = strtof(e1, &e2);
        if (!*arg || !strcmp(arg, "GET")) {
            dc_test_until = esp_timer_get_time() + DCT_TEST_US;
            dc_say(dc_fkhz());
            return true;
        }
        if (e1 != arg && e2 != e1 && !*e2 && dc_ok(i, q)) {
            dc_hand[0] = i;
            dc_hand[1] = q;
            dc_by_hand = true;
            dc_test_words(i, q);
            dc_test_until = esp_timer_get_time() + DCT_TEST_US;
            dc_say(dc_fkhz());
            return true;
        }
    }
    strcpy(dc_pending, line);
    return false;
}

/* Returns 3: a line for the main loop (in dc_pending) or bytes that were no line, 5: DCT_TEST_US without a BDC command. */
static int dc_test_run(void) {
    float i, q;
    const char *src;
    fkhz = dc_fkhz();
    dc_for(fkhz, &i, &q, &src);
    dc_test_words(i, q);
    tx_chain_on();
    say("BDC TEST %.3f MHz dc %.3f %.3f (%s): the leakage alone\r\n", fkhz / 1000.0, (double)i, (double)q, src);
    const uint32_t step = CPU_HZ / TONE_RATE, base = (txdc_orig & ~TXDC_IQ) | TXDC_INJECT;
    int why = 5;
    for (;;) {
        const int64_t left = dc_test_until - esp_timer_get_time();
        if (left <= 0) break;
        const uint64_t periods = (uint64_t)left * (TONE_RATE / 16u) / 1000000u + 1u;
        tone_run(16, step, base, (uint32_t)periods);
        if (!usb_serial_jtag_ll_rxfifo_data_available()) continue;   /* the time is up (checked above) */
        if (!dc_test_line()) {
            why = 3;
            break;
        }
    }
    tx_off();
    return why;
}

/* BDC [GET [f_MHz] | i q | SAVE | PUT f_MHz i q | LIST | RESET | TEST on [f_MHz] | off], answered as the C3 (C3Link.dc, dcTable,
 * dcPut, CalibrationActivity). A beacon command: the beacon keeps (or starts) running. */
static void bdc(const char *arg) {
    host_seen = false;                          /* a beacon command, as on the C3: the beacon keeps (or starts) running - unless refused */
    while (*arg == ' ') ++arg;
    if (dc_test) dc_test_until = esp_timer_get_time() + DCT_TEST_US;    /* every BDC command keeps the test alive */
    if (!strncmp(arg, "GET", 3) && (arg[3] == 0 || arg[3] == ' ')) {
        const char *f = arg + 3;
        while (*f == ' ') ++f;
        char *e;
        const double mhz = *f ? strtod(f, &e) : 0.0;
        if (*f && (e == f || *e || mhz < FMIN_KHZ / 1000.0 || mhz > FMAX_KHZ / 1000.0)) {
            say("ERR BDC GET [f_MHz %lu..%lu]\r\n", (unsigned long)(FMIN_KHZ / 1000), (unsigned long)(FMAX_KHZ / 1000));
            return;
        }
        dc_say(*f ? (uint32_t)llround(mhz * 1000.0) : dc_fkhz());
        beacon_restart = true;
        return;
    }
    if (!strcmp(arg, "LIST")) {
        say("BDCT %lu\r\n", (unsigned long)dct_n);
        for (uint32_t k = 0; k < dct_n; ++k)
            say("BDCT %lu %.3f %.3f\r\n", (unsigned long)dct[k].fkhz, (double)dct[k].i, (double)dct[k].q);
        beacon_restart = true;
        return;
    }
    if (!strncmp(arg, "TEST", 4) && (arg[4] == 0 || arg[4] == ' ')) {
        const char *t = arg + 4;
        while (*t == ' ') ++t;
        if (!strcmp(t, "off")) {
            dc_test = false;
        } else if (!strncmp(t, "on", 2) && (t[2] == 0 || t[2] == ' ')) {
            const char *f = t + 2;
            while (*f == ' ') ++f;
            char *e;
            const double mhz = *f ? strtod(f, &e) : 0.0;
            if (*f && (e == f || *e || mhz < FMIN_KHZ / 1000.0 || mhz > FMAX_KHZ / 1000.0)) {
                say("ERR BDC TEST on [f_MHz %lu..%lu] | off\r\n", (unsigned long)(FMIN_KHZ / 1000), (unsigned long)(FMAX_KHZ / 1000));
                return;
            }
            dc_test = true;
            dc_test_fkhz = *f ? (uint32_t)llround(mhz * 1000.0) : 0;
            dc_test_until = esp_timer_get_time() + DCT_TEST_US;
        } else {
            say("ERR BDC TEST on [f_MHz] | off\r\n");
            return;
        }
    } else if (!strncmp(arg, "PUT ", 4)) {                       /* an entry of the table as it is, without transmitting */
        char *e0, *e1, *e2;
        const double mhz = strtod(arg + 4, &e0);
        const float i = strtof(e0, &e1), q = strtof(e1, &e2);
        if (e0 == arg + 4 || e1 == e0 || e2 == e1 || *e2 || mhz < FMIN_KHZ / 1000.0 || mhz > FMAX_KHZ / 1000.0 || !dc_ok(i, q)) {
            say("ERR BDC PUT f_MHz i q\r\n");
            return;
        }
        const uint32_t f = (uint32_t)llround(mhz * 1000.0);
        if (!dct_put(f, i, q)) say("ERR BDC PUT%s\r\n", dct_n == DCT_MAX ? " (table full: BDC RESET)" : "");
        dc_say(f);
        beacon_restart = true;
        return;
    } else if (!strcmp(arg, "SAVE")) {
        float i, q;
        const char *src;
        const uint32_t f = dc_fkhz();
        dc_for(f, &i, &q, &src);
        if (dct_put(f, i, q)) dc_by_hand = false;
        else say("ERR BDC SAVE%s\r\n", dct_n == DCT_MAX ? " (table full: BDC RESET)" : "");
    } else if (!strcmp(arg, "RESET")) {
        if (!dc_reset()) say("ERR BDC RESET\r\n");
    } else if (*arg) {
        char *e1, *e2;
        const float i = strtof(arg, &e1), q = strtof(e1, &e2);
        if (e1 == arg || e2 == e1 || *e2 || !dc_ok(i, q)) {
            say("ERR BDC i q (DAC codes, -512..512) | GET [f_MHz] | SAVE | PUT f_MHz i q | LIST | RESET | TEST on [f_MHz] | off\r\n");
            return;
        }
        dc_hand[0] = i;
        dc_hand[1] = q;
        dc_by_hand = true;
    }
    dc_say(dc_fkhz());
    beacon_restart = true;
}

/* ------------------------------------------------------------ live TX (QPSKT, PSK8T), as the C3's streaming mode
 * The host (the app's Transmitter, ESP32-DATV-SA host/tx_dvbs.py) encodes DVB-S2 and sends raw symbols: QPSK 4 per byte, the first in
 * the low bits (bit 0 = I > 0, bit 1 = Q > 0); 8PSK the phase index k (point e^(j pi k / 4)) in a nibble, the low one first. No frames
 * and no parser. The loop reads at most one byte per symbol into a LIVE_RING byte ring, only while it has 64 bytes free (USB holds
 * the host back meanwhile); an empty ring sends symbol 0 and counts an underrun. Every 2048 symbols it sends the 4 bytes
 * B7 fill_lo fill_hi underruns (fill in pairs of bytes; underruns: 8-symbol blocks sent from an empty ring, up to 255), one byte per
 * symbol, then the flush. It stops after
 * 0.5 s without a new byte (3 s before the first one) or after secs; the app's A5 FF at the end is just data. */
#define LIVE_RING 16384u
#define P8_MIN_PERIOD 100u              /* cycles per sample of the 8PSK loop (4 loads, the clock, the symbol; 75 as on the C3 left late samples) */

typedef struct {
    uint32_t qw, qr, under, late_max, late, end_max, symbols;
} live_stats_t;

/* QPSK, S 16: b16_body's slots (two 4-symbol tables, lut_build_g) with the ring from USB instead of the loop's symbols. */
static uint32_t __attribute__((noinline)) IRAM_ATTR live_qpsk(uint8_t *qb, const int32_t *T, sample_clock_t clk, uint32_t xb,
                                                              uint32_t max_blk, live_stats_t *st) {
    const uint32_t step = clk.period / 16u;                   /* the symbol's spare cycles go to its last sample */
    const int32_t *const T1 = T + 256u * 16u;
    uint32_t C = 0, phase = 0, late_max = 0, late = 0, end_max = 0, n = 0, under = 0, blk = 0;
    uint32_t qw = 0, qr = 0, qs = 0, qw_seen = 0, b = 0, sym = 0, ok = 0, have = 0, t1 = 0, brk = 0, repw = 0, repn = 0;
    const int32_t *p0 = T, *p1 = T1, *np0 = T, *np1 = T1;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t t = esp_cpu_get_cycle_count() + 2000u, t_rx = t;
    for (;;) {
        uint32_t tj = t;
        B16_SLOT(0, {
            const uint32_t c = esp_cpu_get_cycle_count() - t;
            if (c > late_max) late_max = c;
            if (c >= step) ++late;
        });
        B16_SLOT(1, have = USB_SERIAL_JTAG.ep1_conf.serial_out_ep_data_avail);
        B16_SLOT(2, { ok = qw != qr; b = qb[qr & (LIVE_RING - 1u)]; });
        B16_SLOT(3, sym = ok ? b >> (qs * 2u) & 3u : 0u);
        B16_SLOT(4, { qs += ok; qr += qs >> 2; qs &= 3u; under += !ok && !(n & 7u); });   /* per 8 symbols, as the C3 */
        B16_SLOT(5, C = C << 2 | sym);
        B16_SLOT(6, np0 = T + (C & 255u) * 16u);
        B16_SLOT(7, np1 = T1 + (C >> 8 & 255u) * 16u);
        B16_SLOT(8, if (have && qw - qr < LIVE_RING - 64u) {     /* ring full: USB holds the host back */
            qb[qw & (LIVE_RING - 1u)] = (uint8_t)USB_SERIAL_JTAG.ep1.val;
            ++qw;
        });
        B16_SLOT(9, t1 = t + sample_clock_next(clk, &phase));
        B16_SLOT(10, if (!(++n & 255u)) {
            const uint32_t c = esp_cpu_get_cycle_count();
            if (qw != qw_seen) { qw_seen = qw; t_rx = c; }
            else if (c - t_rx > (qw ? CPU_HZ / 2u : 3u * CPU_HZ)) brk = 1;
            if (++blk >= max_blk) brk = 1;
        });
        B16_SLOT(11, if (!(n & 2047u) && USB_SERIAL_JTAG.ep1_conf.serial_in_ep_data_free) {
            const uint32_t fill = (qw - qr) >> 1;
            repw = 0xB7u | (fill & 255u) << 8 | (fill >> 8 & 255u) << 16 | (under > 255u ? 255u : under) << 24;
            repn = 5u;
        });
        B16_SLOT(12, if (repn > 1u) {
            USB_SERIAL_JTAG.ep1.val = repw & 255u;            /* the whole register (a bit-field store reads it first) */
            repw >>= 8;
            --repn;
        });
        B16_SLOT(13, if (repn == 1u) {
            usb_serial_jtag_ll_txfifo_flush();
            repn = 0;
        });
        B16_SLOT(14, );
        B16_SLOT(15, {
            const uint32_t c = esp_cpu_get_cycle_count() - tj;
            if (c > end_max) end_max = c;
        });
        if (brk) break;
        t = t1;
        p0 = np0;
        p1 = np1;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    *st = (live_stats_t){qw, qr, under, late_max, late, end_max, n};
    return n;
}

/* 8PSK: the C3's narrowband loop (tx_p8_c) - lut_build_p8 tables (four groups of two symbols, 4 loads per sample, the row pointers
 * 64 words further per sample), a deadline per sample from the sample clock; symbol work in slots 0-2, USB in 3, 5 and 7. */
static uint32_t __attribute__((noinline)) IRAM_ATTR live_p8(uint8_t *qb, const int32_t *T, uint32_t S, sample_clock_t clk, uint32_t xb,
                                                            uint32_t max_blk, live_stats_t *st) {
    const uint32_t gs = 64u * S;                              /* words from one group's tables to the next */
    const int32_t *r0 = T, *r1 = T + gs, *r2 = T + 2u * gs, *r3 = T + 3u * gs, *n0 = r0, *n1 = r1, *n2 = r2, *n3 = r3;
    uint32_t C = 0, qw = 0, qr = 0, qh = 0, under = 0, late = 0, late_max = 0, n = 0, qw_seen = 0, phase = 0, blk = 0;
    uint32_t repw = 0, repn = 0, brk = 0;
    bool have = false;
    uint32_t word = ((uint32_t)*r0 + (uint32_t)*r1 + (uint32_t)*r2 + (uint32_t)*r3) ^ xb;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t tn = esp_cpu_get_cycle_count() + 20000u, t_rx = tn;
    for (;;) {
        const bool chk = (n & 255u) == 255u, rep = (n & 2047u) == 2047u;
        for (uint32_t j = 0; j < S; ++j) {
            uint32_t now;
            while ((int32_t)((now = esp_cpu_get_cycle_count()) - tn) < 0) {}
            REG_WRITE(TXDC_REG, word);
            now -= tn;
            if (now > late_max) late_max = now;
            if (now >= clk.period) ++late;
            tn += sample_clock_next(clk, &phase);
            if (j == 0) {                                     /* the symbol that the next one's history needs */
                uint32_t sym = 0;
                if (qw != qr) {
                    sym = qb[qr & (LIVE_RING - 1u)] >> (qh * 4u) & 7u;
                    qh ^= 1u;
                    qr += !qh;
                } else {
                    under += !(n & 7u);                       /* per 8 symbols, as the C3's QPSK */
                }
                C = (C << 3 | sym) & 0xFFFFFFu;
            } else if (j == 1) {
                n0 = T + (C & 63u);
                n1 = T + gs + (C >> 6 & 63u);
            } else if (j == 2) {
                n2 = T + 2u * gs + (C >> 12 & 63u);
                n3 = T + 3u * gs + (C >> 18 & 63u);
            } else if (j == 3) {
                have = USB_SERIAL_JTAG.ep1_conf.serial_out_ep_data_avail;   /* the USB registers are slow: look in one slot, read in another */
            } else if (j == 4) {
                if (chk) {
                    const uint32_t c = esp_cpu_get_cycle_count();
                    if (qw != qw_seen) { qw_seen = qw; t_rx = c; }
                    else if (c - t_rx > (qw ? CPU_HZ / 2u : 3u * CPU_HZ)) brk = 1;
                    if (++blk >= max_blk) brk = 1;
                }
                if (rep && USB_SERIAL_JTAG.ep1_conf.serial_in_ep_data_free) {
                    const uint32_t fill = (qw - qr) >> 1;
                    repw = 0xB7u | (fill & 255u) << 8 | (fill >> 8 & 255u) << 16 | (under > 255u ? 255u : under) << 24;
                    repn = 5u;
                }
            } else if (j == 5) {
                if (have && qw - qr < LIVE_RING - 64u) {      /* ring full: USB holds the host back */
                    qb[qw & (LIVE_RING - 1u)] = (uint8_t)USB_SERIAL_JTAG.ep1.val;
                    ++qw;
                }
            } else if (j == 7) {                              /* the report, a byte per symbol, then the flush */
                if (repn > 1u) {
                    USB_SERIAL_JTAG.ep1.val = repw & 255u;
                    repw >>= 8;
                    --repn;
                } else if (repn == 1u) {
                    usb_serial_jtag_ll_txfifo_flush();
                    repn = 0;
                }
            }
            if (j + 1u < S) { r0 += 64; r1 += 64; r2 += 64; r3 += 64; }
            else { r0 = n0; r1 = n1; r2 = n2; r3 = n3; }
            word = ((uint32_t)*r0 + (uint32_t)*r1 + (uint32_t)*r2 + (uint32_t)*r3) ^ xb;
        }
        ++n;
        if (brk) break;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    *st = (live_stats_t){qw, qr, under, late_max, late, 0, n};
    return n;
}

/* QPSKT / PSK8T f_MHz baud sps [amp [secs [ifm [target [dcI dcQ [g [phi]]]]]]] as the C3 takes them: dcI dcQ in 1/16 code (without
 * them the BDC value for f), amp in the C3's DAC codes (300 = the app's default, here x 150 / 300 as a stored loop); sps, ifm, g, phi
 * are ignored, target 0 (the C3's own PRBS) is refused. Answers "OK QPSKT ..." for both (the app waits for that), "TX END ..." at the end. */
static void tx_live(const char *args, bool psk8) {
    char fs[32];
    uint32_t fk = 0;
    long baud = 0, sps = 0, amp = 300, secs = 1800, ifm = 0, tgt = 1, dc4i = 0, dc4q = 0;
    const int na = sscanf(args, "%31s %ld %ld %ld %ld %ld %ld %ld %ld", fs, &baud, &sps, &amp, &secs, &ifm, &tgt, &dc4i, &dc4q);
    const char *mod = psk8 ? "PSK8T" : "QPSKT";
    if (na < 3 || !parse_freq(fs, &fk) || baud < 2000 || baud > (long)VIDEO_BAUD_MAX || amp < 1 || amp > 480 || secs < 1 || secs > 86400 || tgt < 0) {
        say("ERR %s f_MHz baud(2000..%lu) sps [amp 1..480 [seconds 1..86400 [ifm [target > 0 [dcI dcQ in 1/16 code]]]]]\r\n", mod,
            (unsigned long)VIDEO_BAUD_MAX);
        return;
    }
    if (tgt == 0) {
        say("ERR %s needs the USB stream (target > 0): the ESP32-C5 has no PRBS of its own\r\n", mod);
        return;
    }
    const double half_bw = baud * 1.35 / 2.0;
    if (fk * 1000.0 - half_bw < FMIN_KHZ * 1000.0 || fk * 1000.0 + half_bw > FMAX_KHZ * 1000.0) {
        say("ERR %s the signal must lie in %lu..%lu MHz\r\n", mod, (unsigned long)(FMIN_KHZ / 1000), (unsigned long)(FMAX_KHZ / 1000));
        return;
    }
    uint32_t S = 16u;
    if (psk8) {
        S = CPU_HZ / ((uint32_t)baud * P8_MIN_PERIOD);
        if (S > 64u) S = 64u;
        if (S < 8u) {
            say("ERR PSK8T at most %lu Bd (8 samples per symbol of %lu cycles)\r\n", (unsigned long)(CPU_HZ / (8u * P8_MIN_PERIOD)),
                (unsigned long)P8_MIN_PERIOD);
            return;
        }
    }
    float dci = dc4i / 16.0f, dcq = dc4q / 16.0f;
    if (na < 9) {
        const char *src;
        dc_for(fk, &dci, &dcq, &src);
    }
    int32_t a = (int32_t)(amp * 150 / 300);
    a = a < 1 ? 1 : a;
    uint8_t *ring = malloc(LIVE_RING);
    int32_t *T = malloc(psk8 ? lutt8_bytes(S) : 2u * 256u * 16u * sizeof(int32_t));
    float *hb = psk8 ? malloc(8u * S * sizeof(float)) : NULL;
    if (!ring || !T || (psk8 && !hb)) {
        say("ERR out of memory\r\n");
        free(ring);
        free(T);
        free(hb);
        return;
    }
    if (psk8) lut_build_p8(T, S, 0, 0.35f, (float)a, dci, dcq, 1.0f, 0.0f, hb);
    else lut_build_g(T, 16u, 0, 0.35f, (float)a, dci, dcq, 1.0f, 0.0f, 2u);
    free(hb);
    const sample_clock_t clk = sample_clock_init(CPU_HZ, psk8 ? (uint32_t)baud * S : (uint32_t)baud);
    const uint32_t max_blk = (uint32_t)((double)secs * baud / 256.0) + 1u;
    fkhz = fk;
    tx_chain_on();
    uint8_t c;
    while (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    say("OK QPSKT LO %.0f BAUD %.3f OUT %.0f AMP %ld SPS %lu PERIOD %lu MOD %s DC %.2f %.2f\r\n", fk * 1000.0, (double)baud,
        (double)baud * S, (long)a, (unsigned long)S, (unsigned long)(psk8 ? clk.period : clk.period / 16u), psk8 ? "8PSK" : "QPSK",
        (double)dci, (double)dcq);
    live_stats_t st;
    const uint32_t xb = ((txdc_orig & ~TXDC_IQ) | TXDC_INJECT) ^ LUT_XOR;
    if (psk8) live_p8(ring, T, S, clk, xb, max_blk, &st);
    else live_qpsk(ring, T, clk, xb, max_blk, &st);
    tx_off();
    free(T);
    free(ring);
    vTaskDelay(pdMS_TO_TICKS(30));
    while (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    say("\r\nTX END symbols=%lu bytes_in=%lu underruns=%lu late_slots=%lu worst=%lu cycles%s buffer=%lu pairs (host silent or time over)\r\n",
        (unsigned long)st.symbols, (unsigned long)st.qw, (unsigned long)st.under, (unsigned long)st.late, (unsigned long)st.late_max,
        psk8 ? "" : " (symbol start)", (unsigned long)((st.qw - st.qr) / 2u));
    if (!psk8) say("  last sample of a symbol: worst %lu cycles after its deadline\r\n", (unsigned long)st.end_max);
}

/* INFO as the C3 answers it (C3Link.info: the ESP32DATV line, FW, BEACON, VIDW); C5 marks this transmitter */
static void info(void) {
    say("ESP32DATV 1 C5\r\n");
    say("FW %s c5 live%s\r\n", esp_app_get_description()->version, BEACON_SYMBOLS ? "" : " no-builtin-beacon");   /* live: QPSKT / PSK8T */
    if (BEACON_SYMBOLS) say("BEACON %lu Bd QPSK %s, %.3f MHz (built-in, BEACON)\r\n", (unsigned long)BEACON_BAUD, BEACON_FEC, BEACON_FREQ_KHZ / 1000.0);
    else say("BEACON none (no built-in beacon)\r\n");          /* the C3 always has this line; C3Link.info waits for it */
    const video_hdr_t *v = video_find();
    if (v) say("VIDW 1 VIDEO %.31s %lu Bd MODCOD %u%s %lu symbols\r\n", v->service, (unsigned long)v->baud, (unsigned)v->modcod,
               (v->flags & VIDEO_SHORT) ? " short" : "", (unsigned long)v->nsym);
    else say("VIDW 1 VIDEO none\r\n");
}

static void __attribute__((noinline)) IRAM_ATTR bench(void) {
    static volatile uint32_t ram[64];
    volatile uint32_t *const dc = (volatile uint32_t *)TXDC_REG;
    const uint32_t v = REG_READ(TXDC_REG);
    uint32_t r[4], x = 0;
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) *dc = v;
    r[0] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) {
        *dc = v;
        x += ram[i & 63];
    }
    r[1] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) x += esp_cpu_get_cycle_count();
    r[2] = esp_cpu_get_cycle_count() - c;
    c = esp_cpu_get_cycle_count();
    for (int i = 0; i < 1000; ++i) x += ram[i & 63];
    r[3] = esp_cpu_get_cycle_count() - c;
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    say("BENCH per 1000 (loop included): TXDC store %lu, store+load %lu, rdcycle %lu, DRAM load %lu (%lu)\r\n", (unsigned long)r[0],
        (unsigned long)r[1], (unsigned long)r[2], (unsigned long)r[3], (unsigned long)(x & 1));
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

static int32_t cw_i = 200, cw_q = 0;

static void state(void) {
    say("ST f %.3f MHz tx %s cw %ld %ld scale %lu bo %lu ant %s xtal %u txdc %08lx\r\n", fkhz / 1000.0, tx_on ? "on" : "off",
        (long)cw_i, (long)cw_q, (unsigned long)scale, (unsigned long)backoff, gpio_get_level(ANT_GPIO) ? "ufl" : "chip",
        phy_param[PHY_PARAM_XTAL], (unsigned long)REG_READ(TXDC_REG));
}

static void handle(char *line) {
    const bool seen = host_seen;
    host_seen = true;                                /* a valid command: the host has taken over */
    if (!strncmp(line, "QPSKT ", 6) || !strncmp(line, "PSK8T ", 6)) {
        tx_live(line + 6, line[0] == 'P');
        return;
    }
    if (!strncmp(line, "BDC", 3) && (line[3] == 0 || line[3] == ' ')) {
        bdc(line + 3);
        return;
    }
    char *cmd = strtok(line, " "), *a1 = strtok(NULL, " "), *a2 = strtok(NULL, " "), *a3 = strtok(NULL, " "), *a4 = strtok(NULL, " ");
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
        if (tx_on) {
            tx_chain_on();
            dac_word(iq10(cw_i, cw_q));
        }
    } else if (!strcmp(cmd, "CW")) {
        if (a1) cw_i = atoi(a1);
        if (a2) cw_q = atoi(a2);
        if (!tx_on) tx_chain_on();
        dac_word(iq10(cw_i, cw_q));
    } else if (!strcmp(cmd, "TONE")) {
        if (!a1) {
            say("ERR TONE kHz [amp]\r\n");
            return;
        }
        tone(atoi(a1), a2 ? atoi(a2) : 200);
        return;
    } else if (!strcmp(cmd, "SCALE")) {
        if (!a1 || atoi(a1) < 0 || atoi(a1) > 255) {
            say("ERR SCALE 0-255\r\n");
            return;
        }
        scale = atoi(a1);
        if (tx_on) set_scale();
    } else if (!strcmp(cmd, "BO")) {
        if (!a1) {
            say("ERR BO n\r\n");
            return;
        }
        backoff = atoi(a1);
        if (tx_on) {
            tx_chain_on();
            dac_word(iq10(cw_i, cw_q));
        }
    } else if (!strcmp(cmd, "ANT")) {
        ant = a1 && atoi(a1) ? 1 : 0;
        gpio_set_level(ANT_GPIO, ant);
    } else if (!strcmp(cmd, "VT")) {
        if (!a1) {
            say("ERR VT n\r\n");
            return;
        }
        tx_off();
        esp_phy_wifi_tx_tone(1, tone_arg = atoi(a1), backoff);
        gpio_set_level(ANT_GPIO, ant);
        tx_on = true;
        led(LED_TX);
        say("VT %lu\r\n", (unsigned long)tone_arg);
    } else if (!strcmp(cmd, "RFT")) {
        tx_off();
        esp_phy_rftest_config(1);
        esp_phy_rftest_init();
        say("RFT done\r\n");
    } else if (!strcmp(cmd, "BEACON")) {
        float i, q;
        const char *src;
        dc_for(fkhz, &i, &q, &src);
        beacon(a1 ? atoi(a1) : 150, a2 ? (uint32_t)atoi(a2) : 16u, a3 ? strtof(a3, NULL) : i, a4 ? strtof(a4, NULL) : q, true);
        return;
    } else if (!strcmp(cmd, "PLAY")) {               /* the stored loop (else the built-in beacon) as after power-up */
        if (!video_find() && !BEACON_SYMBOLS) {
            say("ERR no stored loop (VIDW)\r\n");
            return;
        }
        host_seen = false;
        beacon_restart = true;
        return;
    } else if (!strcmp(cmd, "LED")) {
        led_level = a1 ? (uint32_t)atoi(a1) : 12u;
        led(tx_on ? LED_TX : LED_IDLE);
        say("LED %lu\r\n", (unsigned long)led_level);
        return;
    } else if (!strcmp(cmd, "SPIT")) {
        if (a1) spi_dummy = (uint32_t)atoi(a1);
        spi_test();
        return;
    } else if (!strcmp(cmd, "INFO")) {
        info();
        return;
    } else if (!strcmp(cmd, "VIDW")) {
        vidw(a1, a2, a3, a4);
        return;
    } else if (!strcmp(cmd, "DC")) {
        if (a1 && a2) {
            const float i = strtof(a1, NULL), q = strtof(a2, NULL);
            if (!dc_ok(i, q)) {
                say("ERR DC i q (DAC codes, -512..512)\r\n");
                return;
            }
            dc_single[0] = i;
            dc_single[1] = q;
            dc_save();
        }
        say("DC %.2f %.2f (%s; used where there is no BDC table)\r\n", (double)dc_single[0], (double)dc_single[1],
            dc_single_saved ? "saved" : "none");
        return;
    } else if (!strcmp(cmd, "QPSKT") || !strcmp(cmd, "PSK8T") || !strcmp(cmd, "A16T") || !strcmp(cmd, "A32T")) {
        say("ERR %s: the ESP32-C5 takes QPSKT f_MHz baud sps ... and PSK8T (no 16APSK / 32APSK)\r\n", cmd);
        return;
    } else if (!strcmp(cmd, "PA")) {                 /* the app switches the C3's PA off before a transmission */
        if (a1 && !strcmp(a1, "0")) say("OK PA 0 GPIO none\r\n");
        else say("ERR PA: no PA output on the ESP32-C5\r\n");
        return;
    } else if (!strcmp(cmd, "HEAP")) {
        say("HEAP free %u largest %u\r\n", (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT),
            (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
        return;
    } else if (!strcmp(cmd, "BENCH")) {
        bench();
        return;
    } else if (!strcmp(cmd, "OFF")) {
        tx_off();
    } else if (strcmp(cmd, "ST")) {
        host_seen = seen;                            /* e.g. a modem probe: does not stop the beacon for good */
        say("ERR commands: F CW TONE BEACON PLAY VIDW DC BDC QPSKT PSK8T INFO SCALE BO ANT VT RFT OFF ST\r\n");
        return;
    }
    state();
}

/* The stored loop, else the built-in beacon at its frequency, until a byte from the host: 3; 0 if there is nothing to transmit. */
#define BEACON_IDLE_US 5000000
static int beacon_auto(void) {
    if (video_play(false)) return 3;
#ifndef C5CW_NO_BEACON
    if (BEACON_SYMBOLS) {
        float i, q;
        const char *src;
        fkhz = BEACON_FREQ_KHZ >= FMIN_KHZ && BEACON_FREQ_KHZ <= FMAX_KHZ ? BEACON_FREQ_KHZ : fkhz;
        dc_for(fkhz, &i, &q, &src);
        beacon(150, 16u, i, q, false);
        return 3;
    }
#endif
    return 0;
}

void app_main(void) {
    /* TXDC survives a reset (not a power cycle): a run leaves its last sample in it, with the DC injection on, and the PHY's start-up
     * calibration then nulled that instead of the leakage - about 100 DAC codes of carrier afterwards, every DC trim far off
     * (2026-11-11). Cleared before the PHY starts. */
    REG_WRITE(TXDC_REG, REG_READ(TXDC_REG) & ~TXDC_IQ);
    gpio_reset_pin(ANT_GPIO);
    gpio_set_direction(ANT_GPIO, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_level(ANT_GPIO, ant);               /* U.FL: the external antenna or coax */
    led(LED_IDLE);
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
    txdc_boot = txdc_orig = REG_READ(TXDC_REG) & ~TXDC_IQ;
    say("c5cw ready, TX off\r\n");
    state();
    dc_load();

    char line[128];
    size_t used = 0;
    int64_t resume_at = 0;                       /* the beacon may (re)start from this time on */
    bool failed = false;                         /* nothing to transmit: wait for a command */
    for (;;) {
        if (!host_seen && !failed && esp_timer_get_time() >= resume_at) {
            const int why = dc_test ? dc_test_run() : beacon_auto();
            if (why == 3) {                      /* the host: it gets BEACON_IDLE_US to send a command */
                used = 0;
                resume_at = esp_timer_get_time() + BEACON_IDLE_US;
                if (dc_pending[0]) {             /* a line BDC TEST read already */
                    handle(dc_pending);
                    dc_pending[0] = 0;
                    if (beacon_restart) {
                        beacon_restart = false;
                        resume_at = 0;
                    }
                }
            } else if (why == 5) {               /* BDC TEST forgotten: off, as after INFO, until a command or a reset */
                dc_test = false;
                host_seen = true;
                say("BDC TEST timeout: TX off\r\n");
            } else {
                failed = true;
            }
            continue;
        }
        uint8_t c;
        if (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {
            resume_at = esp_timer_get_time() + BEACON_IDLE_US;
            if (c == '\r') continue;
            if (c != '\n') {
                if (used < sizeof(line) - 1) line[used++] = (char)c;
                continue;
            }
            line[used] = 0;
            used = 0;
            if (line[0]) handle(line);
            if (beacon_restart) {                /* BDC, VIDW END, PLAY: the beacon again, now */
                beacon_restart = false;
                failed = false;
                resume_at = 0;
            }
            continue;
        }
        vTaskDelay(1);
    }
}
