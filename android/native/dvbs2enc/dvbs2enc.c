/* DVB-S2 encoder for transport streams (EN 302 307-1), see dvbs2enc.h. GPL-3.0-or-later.
 * BCH generator polynomials: EN 302 307-1 Tables 6a / 6b (as listed in leansdr's dvbs2.h, GPL-3);
 * LDPC tables: ldpc_tables.h (gen_ldpc_tables.py). */
#include "dvbs2enc.h"
#include "ldpc_tables.h"

#include <stdlib.h>
#include <string.h>

#define NMAX 64800
#define BODY_MAX 33192                       /* QPSK normal frame with pilots, without the PLHEADER */

typedef struct { const char *rate; int kbch_n, t_n, kbch_s; } rate_info;
/* Kbch for normal frames, BCH t for normal frames (short: always 12), Kbch for short frames (0: none) */
static const rate_info RATES[] = {
    { "1/4", 16008, 12,  3072 }, { "1/3", 21408, 12,  5232 }, { "2/5", 25728, 12,  6312 },
    { "1/2", 32208, 12,  7032 }, { "3/5", 38688, 12,  9552 }, { "2/3", 43040, 10, 10632 },
    { "3/4", 48408, 12, 11712 }, { "4/5", 51648, 12, 12432 }, { "5/6", 53840, 10, 13152 },
    { "8/9", 57472,  8, 14232 }, { "9/10", 58192, 8,     0 },
};
/* PLS MODCOD: QPSK 1/4 .. 9/10 = 1 .. 11, 8PSK 3/5 .. 9/10 = 12 .. 17 */
static const char *const QPSK_RATES[] = { "1/4", "1/3", "2/5", "1/2", "3/5", "2/3", "3/4", "4/5", "5/6", "8/9", "9/10" };
static const char *const PSK8_RATES[] = { "3/5", "2/3", "3/4", "5/6", "8/9", "9/10" };

/* minimal polynomials g1..g12, without the top bit (degree 16 normal, 14 short) */
static const uint32_t BCH_N[12] = { 0x1002d, 0x10173, 0x10fbd, 0x15a55, 0x11f2f, 0x1f7b5,
                                    0x1af65, 0x17367, 0x10ea1, 0x175a7, 0x13a2d, 0x11ae3 };
static const uint32_t BCH_S[12] = { 0x402b, 0x4941, 0x4647, 0x5591, 0x6b55, 0x6389,
                                    0x6ce5, 0x4f21, 0x460f, 0x5a49, 0x5811, 0x65ef };

struct s2enc {
    s2enc_cfg cfg;                       /* cfg.rate points to ratebuf */
    char ratebuf[8];
    int modcod, kbch, nbch, nldpc, ppf, slots, symbols, bits_per_sym;
    int bch_deg;                         /* parity bits of the BCH code */
    uint8_t g[193];                      /* BCH generator polynomial, g[0] = x^deg coefficient .. g[deg] = 1 */
    const ldpc_table *ldpc;
    const uint16_t **rows;               /* per group of 360 information bits: its addresses */
    uint8_t *row_deg;
    uint8_t prev_crc;
    uint8_t *stream;                     /* continuous mode: packets (sync byte -> CRC-8) not framed yet */
    int slen, scap;
    long spos;                           /* stream byte number of stream[0] */
    uint8_t bbscr[NMAX / 8];             /* BB scrambling sequence, MSB first */
    uint8_t plh[90];                     /* PLHEADER phase indices */
    uint8_t rot[BODY_MAX];               /* PL scrambling: 2 R_n(i) to add to the phase index */
    uint8_t bits[NMAX];                  /* FECFRAME, one bit per byte */
    uint8_t par[NMAX];                   /* LDPC parity accumulators */
};

static uint8_t crc8_bits(uint8_t crc, const uint8_t *b, int nbytes)
{
    for (int i = 0; i < nbytes; ++i) {
        crc ^= b[i];
        for (int j = 0; j < 8; ++j) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0xd5) : (uint8_t)(crc << 1);
    }
    return crc;
}

uint8_t s2enc_packet_crc(const uint8_t *packet) { return crc8_bits(0, packet + 1, 187); }

/* poly multiply over GF(2): a (deg da) * b (deg db), MSB-first coefficient arrays */
static int poly_mul(uint8_t *out, const uint8_t *a, int da, const uint8_t *b, int db)
{
    memset(out, 0, (size_t)(da + db + 1));
    for (int i = 0; i <= da; ++i)
        if (a[i])
            for (int j = 0; j <= db; ++j) out[i + j] ^= b[j];
    return da + db;
}

static void gold_rotations(uint8_t *rot, int n)
{
    /* x: 1 + x^7 + x^18, x(0) = 1; y: 1 + y^5 + y^7 + y^10 + y^18, all ones; z(i) = x(i) ^ y(i) (gold code 0);
     * R(i) = 2 z((i + 131072) mod (2^18 - 1)) + z(i) */
    const int N = (1 << 18) - 1;
    uint8_t *x = malloc((size_t)N + 18), *y = malloc((size_t)N + 18);
    memset(x, 0, 18); x[0] = 1;
    memset(y, 1, 18);
    for (int i = 0; i < N; ++i) {
        x[i + 18] = x[i + 7] ^ x[i];
        y[i + 18] = y[i + 10] ^ y[i + 7] ^ y[i + 5] ^ y[i];
    }
    for (int i = 0; i < n; ++i) {
        int zi = x[i % N] ^ y[i % N], zj = x[(i + 131072) % N] ^ y[(i + 131072) % N];
        rot[i] = (uint8_t)(2 * (2 * zj + zi));          /* j^R = a phase step of R x 90 deg = 2R x 45 deg */
    }
    free(x); free(y);
}

static void plheader(uint8_t *k, int pls)
{
    static const uint64_t G[6] = { 0x55555555, 0x33333333, 0x0f0f0f0f, 0x00ff00ff, 0x0000ffff, 0xffffffff };
    const uint32_t SOF = 0x18d2e82;
    const uint64_t SCR = 0x719d83c953422dfaull;
    uint8_t b[90];
    for (int i = 0; i < 26; ++i) b[i] = (SOF >> (25 - i)) & 1;
    uint32_t y = 0;
    for (int i = 0; i < 6; ++i)
        if ((pls >> (6 - i)) & 1) y ^= (uint32_t)G[i];
    for (int i = 0; i < 32; ++i) {
        int v = (y >> (31 - i)) & 1;
        b[26 + 2 * i] = (uint8_t)v;
        b[27 + 2 * i] = (uint8_t)(v ^ (pls & 1));
    }
    for (int i = 0; i < 64; ++i) b[26 + i] ^= (SCR >> (63 - i)) & 1;
    /* pi/2-BPSK: even symbols (1 - 2b)(1 + j)/sqrt2 -> k 1 or 5, odd ones (1 - 2b)(-1 + j)/sqrt2 -> k 3 or 7 */
    for (int i = 0; i < 90; ++i) k[i] = (uint8_t)((i & 1 ? 3 : 1) + 4 * b[i]);
}

s2enc *s2enc_new(const s2enc_cfg *cfg, const char **err)
{
    const char *dummy;
    if (!err) err = &dummy;
    const rate_info *ri = NULL;
    for (size_t i = 0; i < sizeof RATES / sizeof RATES[0]; ++i)
        if (!strcmp(RATES[i].rate, cfg->rate)) ri = &RATES[i];
    if (!ri) { *err = "unknown code rate"; return NULL; }
    int modcod = 0;
    if (cfg->constellation == S2_QPSK) {
        for (int i = 0; i < 11; ++i) if (!strcmp(QPSK_RATES[i], cfg->rate)) modcod = 1 + i;
    } else if (cfg->constellation == S2_8PSK) {
        for (int i = 0; i < 6; ++i) if (!strcmp(PSK8_RATES[i], cfg->rate)) modcod = 12 + i;
    } else { *err = "constellation must be QPSK or 8PSK"; return NULL; }
    if (!modcod) { *err = "no such code rate for this constellation"; return NULL; }
    if (cfg->short_frame && !ri->kbch_s) { *err = "no 9/10 with short frames"; return NULL; }
    if (cfg->rolloff < 0 || cfg->rolloff > 2) { *err = "roll-off must be 0 (0.35), 1 (0.25) or 2 (0.20)"; return NULL; }
    const ldpc_table *lt = NULL;
    for (size_t i = 0; i < LDPC_TABLES; ++i)
        if (!strcmp(ldpc_tables[i].rate, cfg->rate) && ldpc_tables[i].short_frame == !!cfg->short_frame) lt = &ldpc_tables[i];
    if (!lt) { *err = "no LDPC table"; return NULL; }

    s2enc *e = calloc(1, sizeof *e);
    if (!e) { *err = "out of memory"; return NULL; }
    e->cfg = *cfg;
    strncpy(e->ratebuf, cfg->rate, sizeof e->ratebuf - 1);
    e->cfg.rate = e->ratebuf;
    e->modcod = modcod;
    e->ldpc = lt;
    e->nldpc = lt->n;
    e->nbch = lt->k;
    e->kbch = cfg->short_frame ? ri->kbch_s : ri->kbch_n;
    e->ppf = (e->kbch - 80) / 1504;
    e->bits_per_sym = cfg->constellation == S2_8PSK ? 3 : 2;
    e->slots = e->nldpc / e->bits_per_sym / 90;
    e->symbols = 90 + e->slots * 90 + (cfg->pilots ? (e->slots - 1) / 16 * 36 : 0);

    /* BCH generator: product of the first t minimal polynomials */
    int t = cfg->short_frame ? 12 : ri->t_n, dp = cfg->short_frame ? 14 : 16;
    uint8_t acc[193] = { 1 }, tmp[193], m[17];
    int da = 0;
    for (int i = 0; i < t; ++i) {
        uint32_t p = cfg->short_frame ? BCH_S[i] : BCH_N[i];
        for (int j = 0; j <= dp; ++j) m[j] = (p >> (dp - j)) & 1;
        da = poly_mul(tmp, acc, da, m, dp);
        memcpy(acc, tmp, (size_t)da + 1);
    }
    e->bch_deg = da;
    memcpy(e->g, acc, (size_t)da + 1);
    if (e->nbch - e->kbch != da) { free(e); *err = "BCH parity length mismatch"; return NULL; }

    /* LDPC rows */
    int ngroups = e->nbch / 360;
    e->rows = calloc((size_t)ngroups, sizeof *e->rows);
    e->row_deg = calloc((size_t)ngroups, 1);
    const uint16_t *pos = lt->pos;
    for (int g = 0, r = 0; g < lt->ngroups; ++g)
        for (int i = 0; i < lt->len[g]; ++i, ++r) { e->rows[r] = pos; e->row_deg[r] = lt->deg[g]; pos += lt->deg[g]; }

    /* BB scrambler: 1 + x^14 + x^15, initial 100101010000000 */
    uint16_t reg = 0x4a80;                  /* bit 14 = first stage */
    memset(e->bbscr, 0, sizeof e->bbscr);
    for (int i = 0; i < e->kbch; ++i) {
        int o = ((reg >> 1) ^ reg) & 1;    /* stages 14 and 15 */
        reg = (uint16_t)((reg >> 1) | (o << 14));
        if (o) e->bbscr[i / 8] |= (uint8_t)(0x80 >> (i % 8));
    }

    plheader(e->plh, modcod << 2 | (cfg->short_frame ? 2 : 0) | (cfg->pilots ? 1 : 0));
    gold_rotations(e->rot, e->symbols - 90);
    e->scap = NMAX / 8 + 188 * 64;
    e->stream = malloc((size_t)e->scap);
    return e;
}

void s2enc_free(s2enc *e)
{
    if (!e) return;
    free(e->rows); free(e->row_deg); free(e->stream); free(e);
}

int s2enc_modcod(const s2enc *e) { return e->modcod; }
int s2enc_packets_per_frame(const s2enc *e) { return e->ppf; }

int s2enc_set_packets_per_frame(s2enc *e, int n)
{
    if (n < 1 || n > (e->kbch - 80) / 1504) return 0;
    e->ppf = n;
    return 1;
}
int s2enc_frame_symbols(const s2enc *e) { return e->symbols; }
int s2enc_kbch(const s2enc *e) { return e->kbch; }
void s2enc_set_prev_crc(s2enc *e, uint8_t crc) { e->prev_crc = crc; }

/* BBHEADER: MATYPE-1 = TS, single stream, CCM, no ISSY, no NPD, roll-off; MATYPE-2 0; UPL 1504; DFL; SYNC 0x47;
 * SYNCD; CRC-8 */
static void bbheader(const s2enc *e, uint8_t *bb, int dfl, int syncd)
{
    bb[0] = (uint8_t)(0xf0 | e->cfg.rolloff);
    bb[1] = 0;
    bb[2] = 1504 >> 8; bb[3] = 1504 & 0xff;
    bb[4] = (uint8_t)(dfl >> 8); bb[5] = (uint8_t)dfl;
    bb[6] = 0x47;
    bb[7] = (uint8_t)(syncd >> 8); bb[8] = (uint8_t)syncd;
    bb[9] = crc8_bits(0, bb, 9);
}

static int encode_bb(s2enc *e, uint8_t *bb, uint8_t *out);

int s2enc_frame(s2enc *e, const uint8_t *ts, uint8_t *out)
{
    uint8_t bb[NMAX / 8];
    memset(bb, 0, sizeof bb);
    bbheader(e, bb, e->ppf * 1504, 0);                 /* SYNCD 0: a packet starts the data field */
    for (int p = 0; p < e->ppf; ++p) {
        const uint8_t *pk = ts + 188 * p;
        if (pk[0] != 0x47) return -1;
        uint8_t *d = bb + 10 + 188 * p;
        d[0] = e->prev_crc;
        memcpy(d + 1, pk + 1, 187);
        e->prev_crc = s2enc_packet_crc(pk);
    }
    return encode_bb(e, bb, out);
}

int s2enc_push(s2enc *e, const uint8_t *packet)
{
    if (packet[0] != 0x47) return -1;
    if (e->slen + 188 > e->scap) return 0;
    uint8_t *d = e->stream + e->slen;
    d[0] = e->prev_crc;
    memcpy(d + 1, packet + 1, 187);
    e->prev_crc = s2enc_packet_crc(packet);
    e->slen += 188;
    return 1;
}

int s2enc_ready(const s2enc *e) { return e->slen >= (e->kbch - 80) / 8; }

int s2enc_stream_frame(s2enc *e, uint8_t *out)
{
    int nb = (e->kbch - 80) / 8;
    if (e->slen < nb) return 0;
    uint8_t bb[NMAX / 8];
    /* SYNCD: bits from the start of the data field to the first packet that starts in it (65535: none) */
    long first = (e->spos + 187) / 188 * 188;
    int syncd = first < e->spos + nb ? (int)(first - e->spos) * 8 : 65535;
    bbheader(e, bb, nb * 8, syncd);
    memcpy(bb + 10, e->stream, (size_t)nb);
    memmove(e->stream, e->stream + nb, (size_t)(e->slen - nb));
    e->slen -= nb;
    e->spos += nb;
    return encode_bb(e, bb, out);
}

static int encode_bb(s2enc *e, uint8_t *bb, uint8_t *out)
{
    int kb = e->kbch / 8;
    for (int i = 0; i < kb; ++i) bb[i] ^= e->bbscr[i];

    /* BCH: systematic, parity = message x^deg mod g */
    uint8_t *b = e->bits;
    for (int i = 0; i < e->kbch; ++i) b[i] = (bb[i / 8] >> (7 - i % 8)) & 1;
    uint8_t rem[193] = { 0 };
    int deg = e->bch_deg;
    for (int i = 0; i < e->kbch; ++i) {
        int fb = b[i] ^ rem[0];
        memmove(rem, rem + 1, (size_t)deg - 1);
        rem[deg - 1] = 0;
        if (fb)
            for (int j = 0; j < deg; ++j) rem[j] ^= e->g[j + 1];
    }
    memcpy(b + e->kbch, rem, (size_t)deg);

    /* LDPC: accumulate the information bits into the parity addresses, then p_i ^= p_(i-1) */
    int np = e->nldpc - e->nbch, q = np / 360;
    uint8_t *par = e->par;
    memset(par, 0, (size_t)np);
    for (int m = 0; m < e->nbch; ++m) {
        if (!b[m]) continue;
        const uint16_t *row = e->rows[m / 360];
        int off = (m % 360) * q;
        for (int i = 0; i < e->row_deg[m / 360]; ++i) {
            int a = row[i] + off;
            if (a >= np) a -= np * (a / np);
            par[a] ^= 1;
        }
    }
    for (int i = 1; i < np; ++i) par[i] ^= par[i - 1];
    memcpy(b + e->nbch, par, (size_t)np);

    /* map into slots of 90 symbols, pilots (36 x (1 + j)/sqrt2) after every 16 slots, then PL scrambling */
    memcpy(out, e->plh, 90);
    uint8_t *o = out + 90;
    int n = e->nldpc, rows = n / 3;
    int sym = 0;
    for (int s = 0; s < e->slots; ++s) {
        if (e->cfg.pilots && s && s % 16 == 0)
            for (int i = 0; i < 36; ++i) *o++ = 1;
        for (int i = 0; i < 90; ++i, ++sym) {
            int k;
            if (e->cfg.constellation == S2_QPSK) {
                /* (1 - 2 b0) + j (1 - 2 b1): 00 -> k1, 10 -> k3, 11 -> k5, 01 -> k7 */
                static const uint8_t QK[4] = { 1, 7, 3, 5 };
                k = QK[b[2 * sym] << 1 | b[2 * sym + 1]];
            } else {
                /* bit interleaver: written in 3 columns of n/3, read in rows; 3/5 reads the columns backwards */
                int c0 = b[sym], c1 = b[rows + sym], c2 = b[2 * rows + sym];
                int v = !strcmp(e->cfg.rate, "3/5") ? (c2 << 2 | c1 << 1 | c0) : (c0 << 2 | c1 << 1 | c2);
                /* 000 pi/4, 001 0, 010 pi, 011 5pi/4, 100 pi/2, 101 7pi/4, 110 3pi/4, 111 3pi/2 */
                static const uint8_t PK[8] = { 1, 0, 4, 5, 2, 7, 3, 6 };
                k = PK[v];
            }
            *o++ = (uint8_t)k;
        }
    }
    int nb = e->symbols - 90;
    for (int i = 0; i < nb; ++i) out[90 + i] = (uint8_t)((out[90 + i] + e->rot[i]) & 7);
    return e->symbols;
}

int s2enc_pack_qpsk(const uint8_t *k, int n, uint8_t *out)
{
    /* k 1: I+ Q+ -> 3, k 3: I- Q+ -> 2, k 5: I- Q- -> 0, k 7: I+ Q- -> 1 */
    static const uint8_t CODE[8] = { 0, 3, 0, 2, 0, 0, 0, 1 };
    for (int i = 0; i < n / 4; ++i)
        out[i] = (uint8_t)(CODE[k[4 * i] & 7] | CODE[k[4 * i + 1] & 7] << 2 | CODE[k[4 * i + 2] & 7] << 4 | CODE[k[4 * i + 3] & 7] << 6);
    return n / 4;
}
