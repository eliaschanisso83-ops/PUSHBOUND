/* png_normalize_sprite.c — normalize a directional character sprite to the
 * approved master's exact scale and anchor.
 *
 * Measures the subject's soft bbox (alpha > 16, same threshold as png_bbox.sh),
 * rescales the whole image (premultiplied bilinear, so alpha edges stay clean)
 * so the soft bbox height equals TARGET_H, then places the soft bbox so that
 * its inclusive bottom row equals TARGET_BOTTOM and its center x equals TARGET_CX.
 * Output is a 1024x1024 RGBA PNG on a fully transparent canvas.
 *
 * Usage: png_normalize_sprite IN.png OUT.png TARGET_H TARGET_BOTTOM TARGET_CX
 * Example (Pushbound character master metrics):
 *   png_normalize_sprite in.png out.png 773 901 507.5
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <zlib.h>

typedef struct { int w, h; unsigned char *px; } Image;

static void die(const char *m) { fprintf(stderr, "normalize: %s\n", m); exit(1); }

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
        unsigned char *dst = raw + y * stride; /* unfilter in place, shifted */
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
    unsigned char *out = img;
    for (unsigned y = 0; y < h; y++) {
        const unsigned char *s = raw + y * stride;
        unsigned char *d = out + (size_t)y * w * 4;
        if (bpp == 4) {
            memcpy(d, s, (size_t)w * 4);
        } else {
            for (unsigned x = 0; x < w; x++) {
                d[x * 4 + 0] = s[x * 3 + 0];
                d[x * 4 + 1] = s[x * 3 + 1];
                d[x * 4 + 2] = s[x * 3 + 2];
                d[x * 4 + 3] = 255;
            }
        }
    }
    Image im = {(int)w, (int)h, out};
    return im;
}

static void write_png(const char *path, Image *cv) {
    int w = cv->w, h = cv->h;
    size_t raw_n = (size_t)h * ((size_t)w * 4 + 1);
    unsigned char *raw = malloc(raw_n);
    if (!raw) die("oom");
    for (int y = 0; y < h; y++) {
        raw[(size_t)y * ((size_t)w * 4 + 1)] = 0;
        memcpy(raw + (size_t)y * ((size_t)w * 4 + 1) + 1,
               cv->px + (size_t)y * w * 4, (size_t)w * 4);
    }
    uLongf zn = compressBound(raw_n);
    unsigned char *z = malloc(zn);
    if (!z) die("oom");
    if (compress2(z, &zn, raw, raw_n, 6) != Z_OK) die("compress");

    FILE *f = fopen(path, "wb");
    if (!f) die(path);
    static const unsigned char sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    fwrite(sig, 1, 8, f);

    unsigned char chunk[16];
    unsigned char ihdr[13];
    ihdr[0] = w >> 24; ihdr[1] = w >> 16; ihdr[2] = w >> 8; ihdr[3] = w;
    ihdr[4] = h >> 24; ihdr[5] = h >> 16; ihdr[6] = h >> 8; ihdr[7] = h;
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    uLong c = crc32(0L, Z_NULL, 0);
    c = crc32(c, (const Bytef *)"IHDR", 4);
    c = crc32(c, ihdr, 13);
    chunk[0] = 0; chunk[1] = 0; chunk[2] = 0; chunk[3] = 13;
    fwrite(chunk, 1, 4, f); fwrite("IHDR", 1, 4, f); fwrite(ihdr, 1, 13, f);
    chunk[0] = c >> 24; chunk[1] = c >> 16; chunk[2] = c >> 8; chunk[3] = c;
    fwrite(chunk, 1, 4, f);
    c = crc32(0L, Z_NULL, 0);
    c = crc32(c, (const Bytef *)"IDAT", 4);
    c = crc32(c, z, (uInt)zn);
    chunk[0] = zn >> 24; chunk[1] = zn >> 16; chunk[2] = zn >> 8; chunk[3] = zn;
    fwrite(chunk, 1, 4, f); fwrite("IDAT", 1, 4, f); fwrite(z, 1, zn, f);
    chunk[0] = c >> 24; chunk[1] = c >> 16; chunk[2] = c >> 8; chunk[3] = c;
    fwrite(chunk, 1, 4, f);
    c = crc32(0L, Z_NULL, 0);
    c = crc32(c, (const Bytef *)"IEND", 4);
    chunk[0] = 0; chunk[1] = 0; chunk[2] = 0; chunk[3] = 0;
    fwrite(chunk, 1, 4, f); fwrite("IEND", 1, 4, f);
    chunk[0] = c >> 24; chunk[1] = c >> 16; chunk[2] = c >> 8; chunk[3] = c;
    fwrite(chunk, 1, 4, f);
    fclose(f);
    free(raw); free(z);
}

int main(int argc, char **argv) {
    if (argc != 6) {
        fprintf(stderr, "usage: png_normalize_sprite IN.png OUT.png TARGET_H TARGET_BOTTOM TARGET_CX\n");
        return 1;
    }
    int target_h = atoi(argv[3]);
    int target_bottom = atoi(argv[4]); /* inclusive row of soft bbox */
    double target_cx = atof(argv[5]);

    Image src = decode_png(argv[1]);

    /* soft bbox: alpha > 16, inclusive, same threshold as png_bbox.sh */
    int bx0 = src.w, by0 = src.h, bx1 = -1, by1 = -1;
    for (int y = 0; y < src.h; y++)
        for (int x = 0; x < src.w; x++)
            if (src.px[((size_t)y * src.w + x) * 4 + 3] > 16) {
                if (x < bx0) bx0 = x;
                if (x > bx1) bx1 = x;
                if (y < by0) by0 = y;
                if (y > by1) by1 = y;
            }
    if (bx1 < 0) die("empty alpha");
    int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
    fprintf(stderr, "src bbox x[%d..%d] y[%d..%d] w=%d h=%d\n", bx0, bx1, by0, by1, bw, bh);

    double s = (double)target_h / bh;
    int out_w = (int)llround(bw * s);   /* rescaled subject width  */
    int out_h = target_h;               /* rescaled subject height */

    /* place soft bbox: inclusive bottom row = target_bottom, center x = target_cx */
    int dx0 = (int)llround(target_cx - out_w / 2.0);
    int dy0 = target_bottom - out_h + 1; /* inclusive top row */

    Image dst = {1024, 1024, calloc((size_t)1024 * 1024, 4)};
    if (!dst.px) die("oom");

    /* premultiplied bilinear rescale of the full frame into the target rect */
    for (int y = 0; y < out_h; y++) {
        double sry = by0 + ((y + 0.5) / s) - 0.5;
        int iy = (int)floor(sry);
        double fy = sry - iy;
        int iy0 = iy, iy1 = iy + 1;
        if (iy0 < 0) iy0 = 0;
        if (iy1 < 0) iy1 = 0;
        if (iy0 > src.h - 1) iy0 = src.h - 1;
        if (iy1 > src.h - 1) iy1 = src.h - 1;
        int cy = dy0 + y;
        if (cy < 0 || cy >= dst.h) continue;
        for (int x = 0; x < out_w; x++) {
            double srx = bx0 + ((x + 0.5) / s) - 0.5;
            int ix = (int)floor(srx);
            double fx = srx - ix;
            int ix0 = ix, ix1 = ix + 1;
            if (ix0 < 0) ix0 = 0;
            if (ix1 < 0) ix1 = 0;
            if (ix0 > src.w - 1) ix0 = src.w - 1;
            if (ix1 > src.w - 1) ix1 = src.w - 1;

            double acc[4] = {0, 0, 0, 0};
            const int xs[2] = {ix0, ix1}, ys[2] = {iy0, iy1};
            const double wx[2] = {1 - fx, fx}, wy[2] = {1 - fy, fy};
            for (int j = 0; j < 2; j++)
                for (int i = 0; i < 2; i++) {
                    const unsigned char *p =
                        src.px + ((size_t)ys[j] * src.w + xs[i]) * 4;
                    double wgt = wx[i] * wy[j];
                    double a = p[3] / 255.0;
                    acc[0] += p[0] * a * wgt;
                    acc[1] += p[1] * a * wgt;
                    acc[2] += p[2] * a * wgt;
                    acc[3] += p[3] * wgt;
                }
            int cxp = dx0 + x;
            if (cxp < 0 || cxp >= dst.w) continue;
            unsigned char *d = dst.px + ((size_t)cy * dst.w + cxp) * 4;
            if (acc[3] < 1e-6) { d[0] = d[1] = d[2] = d[3] = 0; continue; }
            double an = acc[3] / 255.0;
            d[0] = (unsigned char)fmin(255.0, acc[0] / an + 0.5);
            d[1] = (unsigned char)fmin(255.0, acc[1] / an + 0.5);
            d[2] = (unsigned char)fmin(255.0, acc[2] / an + 0.5);
            d[3] = (unsigned char)fmin(255.0, acc[3] + 0.5);
        }
    }

    /* verify result bbox */
    int bx0b = dst.w, by0b = dst.h, bx1b = -1, by1b = -1;
    for (int y = 0; y < dst.h; y++)
        for (int x = 0; x < dst.w; x++)
            if (dst.px[((size_t)y * dst.w + x) * 4 + 3] > 16) {
                if (x < bx0b) bx0b = x;
                if (x > bx1b) bx1b = x;
                if (y < by0b) by0b = y;
                if (y > by1b) by1b = y;
            }
    fprintf(stderr, "dst bbox x[%d..%d] y[%d..%d] w=%d h=%d cx=%.1f bottom=%d\n",
            bx0b, bx1b, by0b, by1b, bx1b - bx0b + 1, by1b - by0b + 1,
            (bx0b + bx1b) / 2.0, by1b);

    write_png(argv[2], &dst);
    printf("%d %d\n", dst.w, dst.h);
    return 0;
}
