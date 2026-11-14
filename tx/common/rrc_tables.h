/* Root-raised-cosine modulator tables for the cycle-counter transmit loops of tx/c5cw (ESP32-ATV, GPL-3).
 *
 * The DAC takes I in bits 9:0 and Q in bits 19:10 of a word, both two's complement. A loop adds one table entry per group of
 * symbols for every sample and xors the sum with RRC_XOR: each entry holds the group's share of I and Q as I + 1024 Q, the
 * +512 of both fields is in the last group only, so the sum is offset binary and the xor (bits 9 and 19) makes it two's
 * complement. The DC trim (dci, dcq in DAC codes, against the carrier leakage) is in group 0.
 *
 * The filter spans RRC_SPAN = 8 symbols: sample j (0 .. S-1) of a symbol period is the sum over the 8 newest symbols, the one
 * of age a (0 = the newest) weighted with h(a + j / S - 4), h the RRC impulse response with roll-off RRC_BETA (time in
 * symbols). amp is the largest |I| (and |Q|) any symbol pattern can produce; QPSK and 8PSK points have |I|, |Q| <= 1.
 *
 * Layouts (what the loops index):
 *   rrc_qpsk4  two groups of four QPSK symbols:  T[(g * 256 + idx) * S + j], idx bits 2b / 2b+1 = I / Q of symbol b of the
 *              group (b = 0 the newest, 1 = +1), the history word's bits 8g .. 8g+7
 *   rrc_qpsk2  four groups of two QPSK symbols:  T[(g * S + j) * 16 + idx], idx = the history word's bits 4g .. 4g+3
 *   rrc_psk8   four groups of two 8PSK symbols:  T[(g * S + j) * 64 + idx], idx = newer | older << 3, a symbol k is the point
 *              e^(j pi k / 4) */
#pragma once
#include <math.h>
#include <stdint.h>

#define RRC_XOR  0x80200u
#define RRC_BETA 0.35f
#define RRC_SPAN 8u

static inline uint32_t rrc_qpsk4_words(uint32_t S) { return 2u * 256u * S; }
static inline uint32_t rrc_qpsk2_words(uint32_t S) { return 4u * 16u * S; }
static inline uint32_t rrc_psk8_words(uint32_t S) { return 4u * 64u * S; }

/* The RRC impulse response at t symbols (any scale: the tables are scaled to amp). */
static float rrc_h(float t) {
    const float b = RRC_BETA, pi = 3.14159265f, x = 4.0f * b * t;
    if (fabsf(t) < 1e-5f) return 1.0f + b * (4.0f / pi - 1.0f);
    if (fabsf(fabsf(x) - 1.0f) < 1e-4f) {                               /* t = +-1 / (4 b): the limit */
        const float a = pi / (4.0f * b);
        return b / sqrtf(2.0f) * ((1.0f + 2.0f / pi) * sinf(a) + (1.0f - 2.0f / pi) * cosf(a));
    }
    return (sinf(pi * t * (1.0f - b)) + x * cosf(pi * t * (1.0f + b))) / (pi * t * (1.0f - x * x));
}

static inline float rrc_tap(uint32_t age, uint32_t j, uint32_t S) {
    return rrc_h((float)age + (float)j / (float)S - 0.5f * (float)RRC_SPAN);
}

/* amp over the largest sum of |taps| of any sample phase */
static float rrc_gain(uint32_t S, float amp) {
    float worst = 0.0f;
    for (uint32_t j = 0; j < S; ++j) {
        float s = 0.0f;
        for (uint32_t a = 0; a < RRC_SPAN; ++a) s += fabsf(rrc_tap(a, j, S));
        if (s > worst) worst = s;
    }
    return amp / worst;
}

/* one group's share: i, q scaled, the DC (group 0) and the offset (last group) added, packed I + 1024 Q */
static inline int32_t rrc_entry(float i, float q, uint32_t g, uint32_t last, float dci, float dcq) {
    if (g == 0) { i += dci; q += dcq; }
    if (g == last) { i += 512.0f; q += 512.0f; }
    return (int32_t)lroundf(i) + (int32_t)lroundf(q) * 1024;
}

/* QPSK, n symbols of a group from the bits of idx (2 per symbol, bit 0 = I > 0, bit 1 = Q > 0, the newest in the low bits) */
static void rrc_qpsk_group(float *i, float *q, uint32_t idx, const float *tap, uint32_t n) {
    *i = *q = 0.0f;
    for (uint32_t b = 0; b < n; ++b, idx >>= 2) {
        *i += (idx & 1u) ? tap[b] : -tap[b];
        *q += (idx & 2u) ? tap[b] : -tap[b];
    }
}

static void rrc_qpsk4(int32_t *T, uint32_t S, float amp, float dci, float dcq) {
    const float k = rrc_gain(S, amp);
    for (uint32_t g = 0; g < 2u; ++g)
        for (uint32_t j = 0; j < S; ++j) {
            float tap[4];
            for (uint32_t b = 0; b < 4u; ++b) tap[b] = k * rrc_tap(4u * g + b, j, S);
            for (uint32_t idx = 0; idx < 256u; ++idx) {
                float i, q;
                rrc_qpsk_group(&i, &q, idx, tap, 4u);
                T[(g * 256u + idx) * S + j] = rrc_entry(i, q, g, 1u, dci, dcq);
            }
        }
}

static void rrc_qpsk2(int32_t *T, uint32_t S, float amp, float dci, float dcq) {
    const float k = rrc_gain(S, amp);
    for (uint32_t g = 0; g < 4u; ++g)
        for (uint32_t j = 0; j < S; ++j) {
            const float tap[2] = {k * rrc_tap(2u * g, j, S), k * rrc_tap(2u * g + 1u, j, S)};
            for (uint32_t idx = 0; idx < 16u; ++idx) {
                float i, q;
                rrc_qpsk_group(&i, &q, idx, tap, 2u);
                T[(g * S + j) * 16u + idx] = rrc_entry(i, q, g, 3u, dci, dcq);
            }
        }
}

static void rrc_psk8(int32_t *T, uint32_t S, float amp, float dci, float dcq) {
    const float k = rrc_gain(S, amp), pi = 3.14159265f;
    float ci[8], cq[8];
    for (uint32_t p = 0; p < 8u; ++p) {
        ci[p] = cosf(pi * (float)p / 4.0f);
        cq[p] = sinf(pi * (float)p / 4.0f);
    }
    for (uint32_t g = 0; g < 4u; ++g)
        for (uint32_t j = 0; j < S; ++j) {
            const float t0 = k * rrc_tap(2u * g, j, S), t1 = k * rrc_tap(2u * g + 1u, j, S);
            for (uint32_t idx = 0; idx < 64u; ++idx) {
                const uint32_t n = idx & 7u, o = idx >> 3;          /* the newer and the older symbol */
                T[(g * S + j) * 64u + idx] = rrc_entry(ci[n] * t0 + ci[o] * t1, cq[n] * t0 + cq[o] * t1, g, 3u, dci, dcq);
            }
        }
}
