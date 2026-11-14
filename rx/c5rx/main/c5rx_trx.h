/* rx/c5rx inside the ESP32-C5 transceiver image: tx/c5cw compiles rx/c5rx/main/main.c with C5_TRX, which replaces its
 * app_main by these calls. One USB port: the transmitter's text commands (as the C3) and the receiver's binary requests
 * (as the S3: 0xB4 at the start of a line) side by side; the radio goes back to receiving before the next run after the
 * transmitter has used it. */
#pragma once
#include <stdbool.h>
#include <stdint.h>

void c5rx_setup(void);                       /* after esp_wifi_start: no promiscuous frames, the stream FIFO */
int c5rx_byte(uint8_t c, bool line_idle);    /* a host byte: 0 not the receiver's, 1 part of a binary request, 2 a request done */
int c5rx_pending(void);                      /* a byte the host sent during the last run (its STOP), -1 if none */
bool c5rx_text(char *line);                  /* the receiver's own text commands (G CAP STREAM NOUSB USBC USBT): true if handled */
void c5rx_set_freq(uint32_t khz);            /* F: the receiver's frequency too (tuned before its next run or capture) */
void c5rx_set_ant(uint32_t ant);             /* ANT: the receiver's antenna switch too */
void c5rx_radio_lost(void);                  /* the transmitter used the radio: set the receiver up again before its next run */
