/* png_profile.c — per-row / per-column mean-brightness profiler for material
 * analysis (mortar lines, brick periods). Decodes with the same trusted path
 * as png_decode.c.
 *
 * Usage:
 *   png_profile FILE rows            mean brightness + alpha coverage per row
 *   png_profile FILE cols Y0 Y1      mean brightness per column inside band
 *   png_profile FILE bbox X0 Y0 X1 Y1  alpha bbox (a>16) of region, as x y w h
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

typedef struct { int w, h; unsigned char *px; } Image;

static void die(const char *m) { fprintf(stderr, "profile: %s\n", m); exit(1); }

static unsigned char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) die(path);
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *p = malloc(n);
    if (!p || fread(p, 1, n, f) != (size_t)n) die("read fail");
    fclose(f);
    *len = n;
    return p;
}

static unsigned be32(const unsigned char *p) {
    return ((unsigned)p[0] << 24) | ((unsigned)p[1] << 16) | ((unsigned)p[2] << 8) | p[3];
}

static int paeth(int a, int b, int c) {
    int p = a + b - c, pa = abs(p - a), pb = abs(p - b), pc = abs(p - c);
    if (pa <= pb && pa <= pc) return a;
    if (pb <= pc) return b;
    return c;
}

static Image decode_png(const char *path) {
    size_t len;
    unsigned char *png = read_file(path, &len);
    if (len < 8 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') die("not png");

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
            if (data[8] != 8) die("only 8-bit");
            ctype = data[9];
            if (ctype == 2) bpp = 3; else if (ctype == 6) bpp = 4;
            else die("unsupported ctype");
            if (data[12] != 0) die("interlaced");
        } else if (!memcmp(type, "IDAT", 4)) {
            idat = realloc(idat, idat_len + clen);
            memcpy(idat + idat_len, data, clen);
            idat_len += clen;
        } else if (!memcmp(type, "IEND", 4)) break;
        pos += 12 + clen;
    }
    if (!w || !idat) die("missing chunks");

    size_t stride = (size_t)w * bpp;
    size_t raw_len = (stride + 1) * h;
    unsigned char *raw = malloc(raw_len);
    uLongf rl = raw_len;
    if (uncompress(raw, &rl, idat, idat_len) != Z_OK || rl != raw_len) die("inflate fail");

    unsigned char *img = malloc((size_t)w * h * 4);
    for (unsigned y = 0; y < h; y++) {
        unsigned char ft = raw[y * (stride + 1)];
        const unsigned char *src = raw + y * (stride + 1) + 1;
        unsigned char *dst = raw + y * stride;
        unsigned char *up = y ? dst - stride : NULL;
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
                default: die("bad filter");
            }
            dst[x] = (unsigned char)(v & 255);
        }
    }
    for (unsigned y = 0; y < h; y++) {
        const unsigned char *s = raw + y * stride;
        unsigned char *d = img + (size_t)y * w * 4;
        if (bpp == 4) memcpy(d, s, (size_t)w * 4);
        else for (unsigned x = 0; x < w; x++) {
            d[x * 4 + 0] = s[x * 3 + 0];
            d[x * 4 + 1] = s[x * 3 + 1];
            d[x * 4 + 2] = s[x * 3 + 2];
            d[x * 4 + 3] = 255;
        }
    }
    Image im = {(int)w, (int)h, img};
    return im;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: png_profile FILE rows|cols [Y0 Y1]\n"); return 1; }
    Image im = decode_png(argv[1]);
    if (!strcmp(argv[2], "rows")) {
        for (int y = 0; y < im.h; y++) {
            double sum = 0; int cnt = 0;
            for (int x = 0; x < im.w; x++) {
                const unsigned char *p = im.px + ((size_t)y * im.w + x) * 4;
                if (p[3] <= 16) continue;
                sum += (p[0] * 299 + p[1] * 587 + p[2] * 114) / 1000.0;
                cnt++;
            }
            if (cnt > im.w / 2)  /* opaque rows only */
                printf("%d %.1f %d\n", y, sum / cnt, cnt);
        }
    } else if (argc == 5 && !strcmp(argv[2], "cols")) {
        int y0 = atoi(argv[3]), y1 = atoi(argv[4]);
        for (int x = 0; x < im.w; x++) {
            double sum = 0; int cnt = 0;
            for (int y = y0; y <= y1 && y < im.h; y++) {
                const unsigned char *p = im.px + ((size_t)y * im.w + x) * 4;
                if (p[3] <= 16) continue;
                sum += (p[0] * 299 + p[1] * 587 + p[2] * 114) / 1000.0;
                cnt++;
            }
            printf("%d %.1f %d\n", x, cnt ? sum / cnt : -1, cnt);
        }
    } else if (argc == 7 && !strcmp(argv[2], "bbox")) {
        int x0 = atoi(argv[3]), y0 = atoi(argv[4]), x1 = atoi(argv[5]), y1 = atoi(argv[6]);
        if (x1 >= im.w) x1 = im.w - 1;
        if (y1 >= im.h) y1 = im.h - 1;
        int minx = -1, miny = -1, maxx = -1, maxy = -1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                const unsigned char *p = im.px + ((size_t)y * im.w + x) * 4;
                if (p[3] <= 16) continue;
                if (minx < 0 || x < minx) minx = x;
                if (miny < 0 || y < miny) miny = y;
                if (x > maxx) maxx = x;
                if (y > maxy) maxy = y;
            }
        }
        if (minx < 0) { printf("-1 -1 -1 -1\n"); return 0; }
        printf("%d %d %d %d\n", minx, miny, maxx - minx + 1, maxy - miny + 1);
    } else if (argc == 5 && !strcmp(argv[2], "rowsx")) {
        int x0 = atoi(argv[3]), x1 = atoi(argv[4]);
        if (x1 >= im.w) x1 = im.w - 1;
        for (int y = 0; y < im.h; y++) {
            double sum = 0; int cnt = 0;
            for (int x = x0; x <= x1; x++) {
                const unsigned char *p = im.px + ((size_t)y * im.w + x) * 4;
                if (p[3] <= 16) continue;
                sum += (p[0] * 299 + p[1] * 587 + p[2] * 114) / 1000.0;
                cnt++;
            }
            printf("%d %.1f %d\n", y, cnt ? sum / cnt : -1, cnt);
        }
    } else {
        die("unknown mode");
    }
    return 0;
}
