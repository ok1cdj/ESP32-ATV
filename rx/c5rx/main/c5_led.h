/* The RGB LED of the Waveshare ESP32-C5-Zero: one WS2812 on GPIO27, shared by rx/c5rx and tx/c5cw. Idle (firmware
 * running, waiting) dim blue, RX streaming green, TX on air red; dark in the ROM loader. Bit-banged on the cycle counter
 * (24 bits, 30 us with interrupts masked), only between runs: never inside a stream or a beacon loop. */
#pragma once
#include <stdint.h>
#include "driver/gpio.h"
#include "esp_cpu.h"
#include "esp_rom_sys.h"
#include "freertos/FreeRTOS.h"
#include "soc/gpio_reg.h"

#define LED_GPIO 27
#define LED_LEVEL 12u                /* of 255: the bare LED is bright */
enum { LED_IDLE, LED_RX, LED_TX };

static void __attribute__((noinline)) IRAM_ATTR led_bits(uint32_t grb) {
    const uint32_t cpu = 240u;       /* cycles per us (CPU_HZ) */
    const uint32_t t0 = 35u * cpu / 100u, t1 = 70u * cpu / 100u, period = 125u * cpu / 100u;   /* 0.35 / 0.7 / 1.25 us */
    const unsigned irq = portSET_INTERRUPT_MASK_FROM_ISR();
    uint32_t t = esp_cpu_get_cycle_count();
    for (int b = 23; b >= 0; --b) {
        const uint32_t high = (grb >> b) & 1u ? t1 : t0;
        REG_WRITE(GPIO_OUT_W1TS_REG, 1u << LED_GPIO);
        while (esp_cpu_get_cycle_count() - t < high) {}
        REG_WRITE(GPIO_OUT_W1TC_REG, 1u << LED_GPIO);
        while (esp_cpu_get_cycle_count() - t < period) {}
        t += period;
    }
    portCLEAR_INTERRUPT_MASK_FROM_ISR(irq);
    esp_rom_delay_us(60);            /* reset: low for > 50 us latches the colour */
}

static inline void led(int state) {
    static bool init;
    if (!init) {
        gpio_reset_pin(LED_GPIO);
        gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);
        gpio_set_level(LED_GPIO, 0);
        esp_rom_delay_us(60);
        init = true;
    }
    const uint32_t g = state == LED_RX ? LED_LEVEL : 0u, r = state == LED_TX ? LED_LEVEL : 0u,
                   b = state == LED_IDLE ? LED_LEVEL / 3u : 0u;
    led_bits(g << 16 | r << 8 | b);
}
