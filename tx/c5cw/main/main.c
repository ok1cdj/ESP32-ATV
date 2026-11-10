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
 *   F 5710.000        frequency in MHz (kHz with up to three decimals, 2300-5900); retunes a running carrier
 *   CW [i q]          carrier: a constant DAC word (default 200 0); CW 0 0 leaves the LO leakage alone
 *   TONE kHz [amp]    single tone at F + kHz (negative: below), amp 1-511 (default 200); any byte stops it
 *   SCALE n           DAC scale, both branches (0-255, default 15)
 *   BO n              vendor tone backoff in quarter dB (no effect on the data path per the reference)
 *   ANT 1|0           GPIO26 RF switch of the C5-Zero: 1 = U.FL (default), 0 = chip antenna
 *   VT n              diagnostics: the vendor CW alone (esp_phy_wifi_tx_tone(1, n, BO), n = channel or MHz), no DAC path
 *   RFT               diagnostics: esp_phy_rftest_config(1) + esp_phy_rftest_init() (the cert-test set-up)
 *   BEACON [amp] [S]  the DVB-S2 beacon (beacon.bin of the BEACON_DIR given to flash.sh) at F: QPSK, RRC 0.35, S samples per
 *                     symbol (8-48, default 16), amp = worst-case |I|, |Q| in DAC codes (default 150); any byte stops it
 *   OFF               transmitter off
 *   ST                state
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
#include "esp_timer.h"
#include "esp_wifi.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "hal/usb_serial_jtag_ll.h"
#include "nvs_flash.h"
#include "soc/soc.h"

#include "beacon_cfg.h"              /* of the beacon directory (flash.sh) */
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

static uint32_t fkhz = 5710000, scale = 15, backoff = 0, ant = 1;
static uint32_t tone_arg;            /* the argument the vendor CW was started with */
static bool tx_on;
static uint32_t txdc_orig;
static uint32_t tone_w[TONE_MAX];

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
    REG_WRITE(TXDC_REG, txdc_orig);
    esp_phy_wifi_tx_tone(0, tone_arg, backoff);
    tx_on = false;
}

/* The vendor CW on the MHz turns the chain on, its tone stop hands the DAC to TXDC, then only the PLL takes the kHz. */
static void tx_chain_on(void) {
    tx_off();
    const uint32_t mhz = fkhz / 1000, khz = fkhz % 1000;
    esp_phy_wifi_tx_tone(1, tone_arg = mhz, backoff);
    phy_stop_tx_tone(1);
    if (khz) phy_set_rf_freq_offset(phy_param[PHY_PARAM_XTAL], mhz, (int)khz);
    set_scale();
    txdc_orig = REG_READ(TXDC_REG);
    gpio_set_level(ANT_GPIO, ant);               /* the vendor CW puts the switch back to the chip antenna */
    tx_on = true;
}

/* Plays one period of tone_w until a byte arrives from the host. */
static uint32_t __attribute__((noinline)) IRAM_ATTR tone_run(uint32_t n, uint32_t step, uint32_t base) {
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
        if (usb_serial_jtag_ll_rxfifo_data_available()) break;
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
    const uint32_t late = tone_run(n, CPU_HZ / TONE_RATE, (txdc_orig & ~TXDC_IQ) | TXDC_INJECT);
    uint8_t c;
    while (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    tx_off();
    say("TONE off, worst lateness %lu cycles (%.1f us)\r\n", (unsigned long)late, late / (CPU_HZ / 1e6));
}

/* ------------------------------------------------------------ beacon
 * The C3's tables (qpsk_lut.h lut_build_t: 4 groups of 2 symbols, RRC span 8, entry T[(g * S + j) * 16 + idx], the sum of the four
 * entries xor LUT_XOR is the word). The C3's loops are cycle-exact for its core; here the loop is paced on the cycle counter instead
 * (absolute deadlines: a stall becomes jitter, not a lost sample): each sample slot stores the word of this symbol and computes the
 * same sample of the next one. Symbols: 2 bits each in beacon.bin, symbol n in byte n / 4 at bits 2 (n % 4) (bit 0 = I, 1 = Q). */

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

extern const uint8_t beacon_bin_start[] asm("_binary_beacon_bin_start");
extern const uint8_t beacon_bin_end[] asm("_binary_beacon_bin_end");

#define BEACON_SMAX 48u

typedef struct {
    uint32_t late_max, late;         /* worst lateness [cycles], samples a whole period late */
    uint64_t symbols;
} beacon_stats_t;

static uint32_t __attribute__((noinline)) IRAM_ATTR beacon_loop(const uint8_t *ring, uint32_t nsym, const int32_t *T, uint32_t S, sample_clock_t clk,
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

static void beacon(int32_t amp, uint32_t S) {
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
    int32_t *T = malloc(lutt_bytes(S));
    float *hb = malloc(8u * S * sizeof(float));
    if (!ring || !T || !hb) {
        say("ERR no memory\r\n");
        free(ring);
        free(T);
        free(hb);
        return;
    }
    memcpy(ring, beacon_bin_start, BEACON_SYMBOLS / 4u); /* flash reads would stall the loop */
    lut_build_t(T, S, 0, 0.35f, (float)amp, 0.0f, 0.0f, 1.0f, 0.0f, hb);
    free(hb);
    const sample_clock_t clk = sample_clock_init(CPU_HZ, BEACON_BAUD);
    tx_chain_on();
    say("BEACON %.3f MHz %lu Bd S %lu (%.3f MS/s, %lu cycles per sample) amp %ld scale %lu, QPSK %s: any key stops\r\n", fkhz / 1000.0,
        (unsigned long)BEACON_BAUD, (unsigned long)S, BEACON_BAUD * (double)S / 1e6, (unsigned long)(clk.period / S), (long)amp,
        (unsigned long)scale, BEACON_FEC);
    beacon_stats_t st;
    beacon_loop(ring, BEACON_SYMBOLS, T, S, clk, ((txdc_orig & ~TXDC_IQ) | TXDC_INJECT) ^ LUT_XOR, &st);
    uint8_t c;
    while (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {}
    tx_off();
    free(ring);
    free(T);
    say("BEACON off after %.1f s, worst lateness %lu cycles (%.2f us), %lu samples a period late\r\n",
        (double)st.symbols / BEACON_BAUD, (unsigned long)st.late_max, st.late_max / (CPU_HZ / 1e6), (unsigned long)st.late);
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
    char *cmd = strtok(line, " "), *a1 = strtok(NULL, " "), *a2 = strtok(NULL, " ");
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
        say("VT %lu\r\n", (unsigned long)tone_arg);
    } else if (!strcmp(cmd, "RFT")) {
        tx_off();
        esp_phy_rftest_config(1);
        esp_phy_rftest_init();
        say("RFT done\r\n");
    } else if (!strcmp(cmd, "BEACON")) {
        beacon(a1 ? atoi(a1) : 150, a2 ? (uint32_t)atoi(a2) : 16u);
        return;
    } else if (!strcmp(cmd, "OFF")) {
        tx_off();
    } else if (strcmp(cmd, "ST")) {
        say("ERR commands: F CW TONE BEACON SCALE BO ANT VT RFT OFF ST\r\n");
        return;
    }
    state();
}

void app_main(void) {
    gpio_reset_pin(ANT_GPIO);
    gpio_set_direction(ANT_GPIO, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_level(ANT_GPIO, ant);               /* U.FL: the external antenna or coax */
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
    txdc_orig = REG_READ(TXDC_REG);
    say("c5cw ready, TX off\r\n");
    state();

    char line[64];
    size_t used = 0;
    for (;;) {
        uint8_t c;
        if (usb_serial_jtag_ll_read_rxfifo(&c, 1)) {
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
