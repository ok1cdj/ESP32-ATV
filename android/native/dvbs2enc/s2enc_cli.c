/* s2enc: DVB-S2 encode a transport stream file (dvbs2enc.c), for tests on a PC. GPL-3.0-or-later.
 *
 *   s2enc [--const qpsk|8psk] [--rate 3/4] [--frame normal|short] [--pilots] [--frames N] [--skip PACKETS] [--loop]
 *         [--packets N] [--stream]
 *         [--format phase|qpsk] IN.ts OUT
 *
 * Takes packets_per_frame packets per PLFRAME from IN.ts (after --skip), N frames (default: as many as fit).
 * --loop: the first packet's CRC-8 slot carries the CRC of the last packet used, as in a looped beacon.
 * --stream: continuous framing (packets across frames, full data fields) instead of whole packets per frame.
 * --format phase: one byte per symbol (phase index k, symbol = exp(j pi k / 4)); qpsk: 4 symbols per byte as
 * ESP32-DATV's beacon.bin. Prints packets per frame and symbols per frame on stderr. */
#include "dvbs2enc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    s2enc_cfg cfg = { S2_QPSK, "3/4", 0, 0, 0 };
    long frames = -1, skip = 0, packets = 0;
    int loop = 0, qpsk_fmt = 0, stream = 0;
    const char *in = NULL, *outp = NULL;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--const") && i + 1 < argc) cfg.constellation = !strcmp(argv[++i], "8psk") ? S2_8PSK : S2_QPSK;
        else if (!strcmp(argv[i], "--rate") && i + 1 < argc) cfg.rate = argv[++i];
        else if (!strcmp(argv[i], "--frame") && i + 1 < argc) cfg.short_frame = !strcmp(argv[++i], "short");
        else if (!strcmp(argv[i], "--pilots")) cfg.pilots = 1;
        else if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atol(argv[++i]);
        else if (!strcmp(argv[i], "--skip") && i + 1 < argc) skip = atol(argv[++i]);
        else if (!strcmp(argv[i], "--loop")) loop = 1;
        else if (!strcmp(argv[i], "--stream")) stream = 1;
        else if (!strcmp(argv[i], "--packets") && i + 1 < argc) packets = atol(argv[++i]);
        else if (!strcmp(argv[i], "--format") && i + 1 < argc) qpsk_fmt = !strcmp(argv[++i], "qpsk");
        else if (!in) in = argv[i];
        else if (!outp) outp = argv[i];
        else { fprintf(stderr, "unknown argument %s\n", argv[i]); return 2; }
    }
    if (!in || !outp) { fprintf(stderr, "usage: s2enc [options] IN.ts OUT\n"); return 2; }
    const char *err;
    s2enc *e = s2enc_new(&cfg, &err);
    if (!e) { fprintf(stderr, "s2enc: %s\n", err); return 1; }
    if (qpsk_fmt && cfg.constellation != S2_QPSK) { fprintf(stderr, "--format qpsk needs QPSK\n"); return 2; }
    if (packets && !s2enc_set_packets_per_frame(e, (int)packets)) { fprintf(stderr, "s2enc: --packets out of range\n"); return 2; }

    FILE *f = fopen(in, "rb");
    if (!f) { perror(in); return 1; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *ts = malloc((size_t)size);
    if (fread(ts, 1, (size_t)size, f) != (size_t)size) { perror(in); return 1; }
    fclose(f);
    long npk = size / 188 - skip;
    int ppf = s2enc_packets_per_frame(e), ns = s2enc_frame_symbols(e);
    if (frames < 0) frames = stream ? npk * 1504L / (s2enc_kbch(e) - 80) : npk / ppf;
    if (!stream && npk < frames * ppf) { fprintf(stderr, "s2enc: %ld packets, %ld needed\n", npk, frames * ppf); return 1; }
    const uint8_t *p0 = ts + 188 * skip;
    if (loop) s2enc_set_prev_crc(e, s2enc_packet_crc(p0 + 188 * (frames * ppf - 1)));

    FILE *o = fopen(outp, "wb");
    if (!o) { perror(outp); return 1; }
    uint8_t *sym = malloc((size_t)ns + 4), *packed = malloc((size_t)ns / 4 + 1);
    uint8_t carry[4];
    int nc = 0;
    long next = 0;                              /* --stream: next packet to push */
    for (long fr = 0; fr < frames; ++fr) {
        if (stream) {
            while (!s2enc_ready(e) && next < npk)
                if (s2enc_push(e, p0 + 188 * next++) < 0) { fprintf(stderr, "s2enc: lost TS sync at packet %ld\n", next - 1); return 1; }
            if (!s2enc_stream_frame(e, sym)) break;
        } else if (s2enc_frame(e, p0 + 188 * fr * ppf, sym) < 0) { fprintf(stderr, "s2enc: lost TS sync in frame %ld\n", fr); return 1; }
        if (!qpsk_fmt) { fwrite(sym, 1, (size_t)ns, o); continue; }
        /* 4 symbols per byte across frame boundaries (a QPSK PLFRAME need not be a multiple of 4 symbols) */
        uint8_t *buf = malloc((size_t)ns + 4);
        memcpy(buf, carry, (size_t)nc);
        memcpy(buf + nc, sym, (size_t)ns);
        int n = nc + ns, whole = n / 4 * 4;
        fwrite(packed, 1, (size_t)s2enc_pack_qpsk(buf, whole, packed), o);
        nc = n - whole;
        memcpy(carry, buf + whole, (size_t)nc);
        free(buf);
    }
    if (qpsk_fmt && nc) {                       /* last partial byte, padded with code 0 symbols (k 5) */
        while (nc < 4) carry[nc++] = 5;
        fwrite(packed, 1, (size_t)s2enc_pack_qpsk(carry, 4, packed), o);
    }
    fclose(o);
    fprintf(stderr, "modcod %d, %d packets and %d symbols per frame, %ld frames\n", s2enc_modcod(e), ppf, ns, frames);
    s2enc_free(e);
    return 0;
}
