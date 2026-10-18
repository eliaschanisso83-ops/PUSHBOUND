/* compose_board.c — offline board compositor for visual QA of grid math.
 * Mirrors the Kotlin renderer's placement formulas exactly, using the real
 * approved sprite pixels. Reads raw RGBA sprites (tools/png_decode.sh),
 * composites onto a canvas, writes a PNG via zlib. Nearest-neighbor scaling.
 *
 * Commands on stdin (one per line):
 *   canvas W H R G B A
 *   load NAME FILE.raw SW SH
 *   stamp NAME sx sy sw sh dx dy dw dh
 *   rect X Y W H R G B A            (filled rect, alpha blend)
 *   hline X0 X1 Y T R G B A         (horizontal line, thickness T)
 *   vline X Y0 Y1 T R G B A         (vertical line, thickness T)
 *   emit OUT.png
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

typedef struct { int w, h; unsigned char *px; } Image;

static Image canvas;
static int clip_x0, clip_y0, clip_x1, clip_y1; /* current clip rect */
static int clip_on;

static void die(const char *m) { fprintf(stderr, "compose: %s\n", m); exit(1); }

static unsigned char *load_raw(const char *path, int w, int h) {
    FILE *f = fopen(path, "rb");
    if (!f) die(path);
    size_t n = (size_t)w * h * 4;
    unsigned char *p = malloc(n);
    if (!p) die("oom");
    if (fread(p, 1, n, f) != n) die("short read");
    fclose(f);
    return p;
}

static void blend_px(int x, int y, int r, int g, int b, int a) {
    if (x < 0 || y < 0 || x >= canvas.w || y >= canvas.h || a <= 0) return;
    unsigned char *d = canvas.px + ((size_t)y * canvas.w + x) * 4;
    if (a >= 255) { d[0] = r; d[1] = g; d[2] = b; d[3] = 255; return; }
    d[0] = (r * a + d[0] * (255 - a)) / 255;
    d[1] = (g * a + d[1] * (255 - a)) / 255;
    d[2] = (b * a + d[2] * (255 - a)) / 255;
    d[3] = 255;
}

static void stamp(Image *s, int sx, int sy, int sw, int sh,
                  int dx, int dy, int dw, int dh) {
    for (int y = 0; y < dh; y++) {
        int syy = sy + (int)((long long)y * sh / dh);
        int cy = dy + y;
        if (cy < 0 || cy >= canvas.h) continue;
        if (clip_on && (cy < clip_y0 || cy >= clip_y1)) continue;
        for (int x = 0; x < dw; x++) {
            int sxx = sx + (int)((long long)x * sw / dw);
            int cx = dx + x;
            if (cx < 0 || cx >= canvas.w) continue;
            if (clip_on && (cx < clip_x0 || cx >= clip_x1)) continue;
            unsigned char *p = s->px + ((size_t)syy * s->w + sxx) * 4;
            blend_px(cx, cy, p[0], p[1], p[2], p[3]);
        }
    }
}

static void fill_rect(int x, int y, int w, int h, int r, int g, int b, int a) {
    for (int j = y; j < y + h; j++)
        for (int i = x; i < x + w; i++) blend_px(i, j, r, g, b, a);
}

static void write_png(const char *path) {
    int w = canvas.w, h = canvas.h;
    size_t raw_n = (size_t)h * ((size_t)w * 4 + 1);
    unsigned char *raw = malloc(raw_n);
    if (!raw) die("oom");
    for (int y = 0; y < h; y++) {
        raw[(size_t)y * ((size_t)w * 4 + 1)] = 0; /* filter: none */
        memcpy(raw + (size_t)y * ((size_t)w * 4 + 1) + 1,
               canvas.px + (size_t)y * w * 4, (size_t)w * 4);
    }
    uLongf zn = compressBound(raw_n);
    unsigned char *z = malloc(zn);
    if (!z) die("oom");
    if (compress2(z, &zn, raw, raw_n, 6) != Z_OK) die("compress");

    FILE *f = fopen(path, "wb");
    if (!f) die(path);
    static const unsigned char sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    fwrite(sig, 1, 8, f);

    unsigned char chunk[16 + 1];
    /* IHDR */
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
    /* IDAT */
    c = crc32(0L, Z_NULL, 0);
    c = crc32(c, (const Bytef *)"IDAT", 4);
    c = crc32(c, z, (uInt)zn);
    chunk[0] = zn >> 24; chunk[1] = zn >> 16; chunk[2] = zn >> 8; chunk[3] = zn;
    fwrite(chunk, 1, 4, f); fwrite("IDAT", 1, 4, f); fwrite(z, 1, zn, f);
    chunk[0] = c >> 24; chunk[1] = c >> 16; chunk[2] = c >> 8; chunk[3] = c;
    fwrite(chunk, 1, 4, f);
    /* IEND */
    c = crc32(0L, Z_NULL, 0);
    c = crc32(c, (const Bytef *)"IEND", 4);
    chunk[0] = 0; chunk[1] = 0; chunk[2] = 0; chunk[3] = 0;
    fwrite(chunk, 1, 4, f); fwrite("IEND", 1, 4, f);
    chunk[0] = c >> 24; chunk[1] = c >> 16; chunk[2] = c >> 8; chunk[3] = c;
    fwrite(chunk, 1, 4, f);
    fclose(f);
    free(raw); free(z);
}

int main(void) {
    #define MAXS 16
    static Image sprites[MAXS];
    static char names[MAXS][32];
    static int ns = 0;

    char line[1024];
    while (fgets(line, sizeof line, stdin)) {
        char a0[64];
        if (sscanf(line, "%63s", a0) != 1) continue;
        if (!strcmp(a0, "canvas")) {
            int w, h, r, g, b, a;
            if (sscanf(line, "canvas %d %d %d %d %d %d", &w, &h, &r, &g, &b, &a) != 6) die("canvas");
            canvas.w = w; canvas.h = h;
            canvas.px = malloc((size_t)w * h * 4);
            if (!canvas.px) die("oom");
            fill_rect(0, 0, w, h, r, g, b, a);
        } else if (!strcmp(a0, "load")) {
            char nm[32], path[512];
            int w, h;
            if (sscanf(line, "load %31s %511s %d %d", nm, path, &w, &h) != 4) die("load");
            if (ns >= MAXS) die("too many sprites");
            strcpy(names[ns], nm);
            sprites[ns].w = w; sprites[ns].h = h;
            sprites[ns].px = load_raw(path, w, h);
            ns++;
        } else if (!strcmp(a0, "stamp")) {
            char nm[32];
            int sx, sy, sw, sh, dx, dy, dw, dh;
            if (sscanf(line, "stamp %31s %d %d %d %d %d %d %d %d",
                       nm, &sx, &sy, &sw, &sh, &dx, &dy, &dw, &dh) != 9) die("stamp");
            int i;
            for (i = 0; i < ns; i++) if (!strcmp(names[i], nm)) break;
            if (i == ns) die("unknown sprite");
            stamp(&sprites[i], sx, sy, sw, sh, dx, dy, dw, dh);
        } else if (!strcmp(a0, "rect")) {
            int x, y, w, h, r, g, b, a;
            if (sscanf(line, "rect %d %d %d %d %d %d %d %d", &x, &y, &w, &h, &r, &g, &b, &a) != 8) die("rect");
            fill_rect(x, y, w, h, r, g, b, a);
        } else if (!strcmp(a0, "hline")) {
            int x0, x1, y, t, r, g, b, a;
            if (sscanf(line, "hline %d %d %d %d %d %d %d %d", &x0, &x1, &y, &t, &r, &g, &b, &a) != 8) die("hline");
            fill_rect(x0, y, x1 - x0, t, r, g, b, a);
        } else if (!strcmp(a0, "vline")) {
            int x, y0, y1, t, r, g, b, a;
            if (sscanf(line, "vline %d %d %d %d %d %d %d %d", &x, &y0, &y1, &t, &r, &g, &b, &a) != 8) die("vline");
            fill_rect(x, y0, t, y1 - y0, r, g, b, a);
        } else if (!strcmp(a0, "clip")) {
            int x, y, w, h;
            if (sscanf(line, "clip %d %d %d %d", &x, &y, &w, &h) != 4) die("clip");
            clip_on = 1; clip_x0 = x; clip_y0 = y; clip_x1 = x + w; clip_y1 = y + h;
        } else if (!strcmp(a0, "clipoff")) {
            clip_on = 0;
        } else if (!strcmp(a0, "emit")) {
            char path[512];
            if (sscanf(line, "emit %511s", path) != 1) die("emit");
            write_png(path);
        } else if (!strcmp(a0, "#")) {
            continue;
        } else {
            die("unknown command");
        }
    }
    return 0;
}
