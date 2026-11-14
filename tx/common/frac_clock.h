/* Sample deadlines at a rate that does not divide the CPU clock (ESP32-ATV, GPL-3): the interval is period cycles, one more
 * whenever the fraction carried in *acc overflows - the average is exactly cpu_hz / rate, the error under one cycle, and the
 * step needs no division. */
#pragma once
#include <stdint.h>

typedef struct {
    uint32_t period, frac, den;      /* cpu_hz = period * den + frac */
} frac_clock_t;

static inline frac_clock_t frac_clock_init(uint32_t cpu_hz, uint32_t rate) {
    const frac_clock_t c = {cpu_hz / rate, cpu_hz % rate, rate};
    return c;
}

/* The next interval in cycles; *acc (start at 0) carries the fraction across calls. */
static inline __attribute__((always_inline)) uint32_t frac_clock_step(frac_clock_t c, uint32_t *acc) {
    *acc += c.frac;
    if (*acc < c.den) return c.period;
    *acc -= c.den;
    return c.period + 1u;
}
