/* png_decode.c — fast PNG -> raw RGBA decoder for the QA pipeline.
 * Usage: png_decode FILE OUT.raw   (prints "W H" on success)
 * Supports 8-bit RGB (ctype 2) and RGBA (ctype 6), all PNG filters.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

static unsigned char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) { perror(path); exit(1); }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *p = malloc(n);
    if (!p || fread(p, 1, n, f) != (size_t)n) { fprintf(stderr, "read fail\n"); exit(1); }
    fclose(f);
    *len = n;
    return p;
}

static unsigned be32(const unsigned char *p) {
    return ((unsigned)p[0] << 24) | ((unsigned)p[1] << 16) | ((unsigned)p[2] << 8) | p[3];
}

static int paeth(int a, int b, int c) {
    int p = a + b - c, pa = p > a ? p - a : a - p, pb = p > b ? p - b : b - p, pc = p > c ? p - c : c - p;
    if (pa <= pb && pa <= pc) return a;
    if (pb <= pc) return b;
    return c;
}

int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: png_decode FILE OUT.raw\n"); return 1; }
    size_t len;
    unsigned char *png = read_file(argv[1], &len);
    if (len < 8 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') { fprintf(stderr, "not png\n"); return 1; }

    unsigned w = 0, h = 0;
    int bpp = 0, ctype = 0;
    unsigned char *idat = NULL; size_t idat_len = 0;

    size_t pos = 8;
    while (pos + 8 <= len) {
        unsigned clen = be32(png + pos);
        const unsigned char *type = png + pos + 4;
        const unsigned char *data = png + pos + 8;
        if (!memcmp(type, "IHDR", 4)) {
            w = be32(data); h = be32(data + 4);
            if (data[8] != 8) { fprintf(stderr, "only 8-bit\n"); return 1; }
            ctype = data[9];
            if (ctype == 2) bpp = 3; else if (ctype == 6) bpp = 4;
            else { fprintf(stderr, "unsupported ctype %d\n", ctype); return 1; }
            if (data[12] != 0) { fprintf(stderr, "interlaced\n"); return 1; }
        } else if (!memcmp(type, "IDAT", 4)) {
            idat = realloc(idat, idat_len + clen);
            memcpy(idat + idat_len, data, clen);
            idat_len += clen;
        } else if (!memcmp(type, "IEND", 4)) break;
        pos += 12 + clen;
    }
    if (!w || !idat) { fprintf(stderr, "missing chunks\n"); return 1; }

    size_t stride = (size_t)w * bpp;
    size_t raw_len = (stride + 1) * h;
    unsigned char *raw = malloc(raw_len);
    uLongf rl = raw_len;
    if (uncompress(raw, &rl, idat, idat_len) != Z_OK || rl != raw_len) {
        fprintf(stderr, "inflate fail (%lu != %lu)\n", (unsigned long)rl, (unsigned long)raw_len);
        return 1;
    }

    /* Unfilter in place (shift right by 1 byte to reuse the buffer layout). */
    unsigned char *img = malloc(stride * h);
    for (unsigned y = 0; y < h; y++) {
        unsigned char ft = raw[y * (stride + 1)];
        const unsigned char *src = raw + y * (stride + 1) + 1;
        unsigned char *dst = img + y * stride;
        const unsigned char *up = y ? dst - stride : NULL;
        for (size_t x = 0; x < stride; x++) {
            int a = (x >= (size_t)bpp) ? dst[x - bpp] : 0;
            int b = up ? up[x] : 0;
            int c = (up && x >= (size_t)bpp) ? up[x - bpp] : 0;
            int v = src[x];
            switch (ft) {
                case 0: break;
                case 1: v += a; break;
                case 2: v += b; break;
                case 3: v += (a + b) / 2; break;
                case 4: v += paeth(a, b, c); break;
                default: fprintf(stderr, "bad filter\n"); return 1;
            }
            dst[x] = (unsigned char)(v & 255);
        }
    }

    FILE *out = fopen(argv[2], "wb");
    if (!out) { perror(argv[2]); return 1; }
    if (bpp == 4) {
        fwrite(img, 1, stride * h, out);
    } else {
        for (unsigned y = 0; y < h; y++)
            for (unsigned x = 0; x < w; x++) {
                const unsigned char *p = img + y * stride + (size_t)x * 3;
                unsigned char rgba[4] = {p[0], p[1], p[2], 255};
                fwrite(rgba, 1, 4, out);
            }
    }
    fclose(out);
    printf("%u %u\n", w, h);
    return 0;
}
