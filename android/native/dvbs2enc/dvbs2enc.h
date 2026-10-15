/* DVB-S2 encoder for transport streams (EN 302 307-1): BBFRAME, BCH, LDPC, bit interleaving, QPSK / 8PSK mapping,
 * PLHEADER, pilots and PL scrambling. Written for ESP32-ATV from the standard; GPL-3.0-or-later.
 *
 * Two framings: whole packets per frame (s2enc_frame: each BBFRAME carries packets_per_frame packets that start at
 * its data field, SYNCD 0, DFL = packets x 1504 bits, the rest zero padding - the ESP32-DATV beacon's layout, a loop
 * joins up), or a continuous stream (s2enc_push / s2enc_stream_frame: full data fields, packets across frames). Output: one byte per symbol, its phase index k (symbol = exp(j pi k / 4), unit amplitude): QPSK and the
 * PLHEADER's pi/2-BPSK use the odd k, 8PSK all eight. */
#pragma once
#include <stdint.h>

enum { S2_QPSK = 0, S2_8PSK = 1 };

typedef struct {
    int constellation;      /* S2_QPSK or S2_8PSK */
    const char *rate;       /* code rate: "1/4" .. "9/10" (QPSK), "3/5" .. "9/10" (8PSK); short frames: no 9/10 */
    int short_frame;        /* 0: 64800-bit FECFRAMEs, 1: 16200 */
    int pilots;
    int rolloff;            /* MATYPE RO: 0 = 0.35, 1 = 0.25, 2 = 0.20 */
} s2enc_cfg;

typedef struct s2enc s2enc;

/* NULL when the combination does not exist (the error says why in *err when err is not NULL). */
s2enc *s2enc_new(const s2enc_cfg *cfg, const char **err);
void s2enc_free(s2enc *e);

int s2enc_modcod(const s2enc *e);            /* PLS MODCOD 1..28 */
int s2enc_packets_per_frame(const s2enc *e); /* whole 188-byte TS packets per BBFRAME (default: as many as fit) */
/* Fewer packets per BBFRAME than fit (the ESP32-DATV beacon uses 32 at every normal-frame rate); 0 if n is out of range. */
int s2enc_set_packets_per_frame(s2enc *e, int n);
int s2enc_frame_symbols(const s2enc *e);     /* symbols per PLFRAME, PLHEADER and pilots included */
int s2enc_kbch(const s2enc *e);              /* BBFRAME bits */

/* CRC-8 that replaces the sync byte of the next packet (EN 302 307-1 5.1.4): the CRC of the previous packet's 187
 * bytes. A looped stream sets it to the CRC of the loop's last packet so that the loop joins up. */
void s2enc_set_prev_crc(s2enc *e, uint8_t crc);
uint8_t s2enc_packet_crc(const uint8_t *packet);   /* CRC-8 of bytes 1..187 of a TS packet */

/* Encodes packets_per_frame TS packets (188 bytes each, sync byte 0x47) into one PLFRAME of frame_symbols phase
 * indices. Returns the number of symbols, or -1 when a packet does not start with 0x47. */
int s2enc_frame(s2enc *e, const uint8_t *ts, uint8_t *out);

/* Continuous mode (the usual DVB-S2 TS framing, as ESP32-DATV's PC streaming): packets run across BBFRAMEs, every
 * data field is full (DFL = Kbch - 80) and SYNCD points to the first packet that starts in it. Push packets one at a
 * time (returns 1, 0 when the buffer is full - take a frame first, -1 without the 0x47 sync byte); when s2enc_ready(),
 * s2enc_stream_frame() writes the next PLFRAME and returns its symbols (0: not enough packets yet). */
int s2enc_push(s2enc *e, const uint8_t *packet);
int s2enc_ready(const s2enc *e);
int s2enc_stream_frame(s2enc *e, uint8_t *out);

/* Packs QPSK phase indices 4 per byte, first in the low bits, 2 bits each: bit 0 = I > 0, bit 1 = Q > 0 (the
 * ESP32-DATV beacon.bin layout). n must be a multiple of 4; returns the bytes written. */
int s2enc_pack_qpsk(const uint8_t *k, int n, uint8_t *out);
