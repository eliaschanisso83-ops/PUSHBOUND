#!/data/data/com.termux/files/usr/bin/bash
# png_analyze.sh — numeric QA for PNGs without Pillow/ImageMagick/Node.
# Hand-rolled: chunk parse -> IDAT concat -> strip zlib hdr/adler -> gzip wrap -> gunzip -c
# -> gawk unfilter (types 0-4) -> stats.
# Usage: png_analyze.sh FILE
# Prints: dims, gold-mask bbox, ovality, mean colors, contrast, background stats.
set -u
F="$1"
TMP=/data/data/com.termux/files/usr/tmp/opencode
mkdir -p "$TMP"
B=$(basename "$F" .png)

# --- IHDR ---
read W H DEPTH CTYPE < <(dd if="$F" bs=1 skip=16 count=10 2>/dev/null | od -An -tu1 -w10 | awk '{print $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8, $9, $10}')
BPP=3
[ "$CTYPE" = 6 ] && BPP=4
STRIDE=$((1 + W * BPP))
UNCOMP=$((H * STRIDE))

# --- IDAT offsets ---
grep -abo 'IDAT' "$F" | cut -d: -f1 > "$TMP/$B.idat"
: > "$TMP/$B.deflate"
while read -r off; do
  # length = 4 bytes big-endian right before the type
  LEN=$(dd if="$F" bs=1 skip=$((off-4)) count=4 2>/dev/null | od -An -tu1 -w4 | awk '{print $1*16777216+$2*65536+$3*256+$4}')
  # chunk layout: [len@off-4][type@off][payload off+4 .. off+3+LEN][crc off+4+LEN..off+7+LEN][next type@off+LEN+12]
  NEXTOFF=$((off + LEN + 12))
  NEXTOFF_TYPE=$(dd if="$F" bs=1 skip=$NEXTOFF count=4 2>/dev/null | tr -cd 'A-Z')
  if [ -z "$NEXTOFF_TYPE" ] || [ "$LEN" -le 0 ] || [ "$LEN" -gt 52428800 ]; then
    echo "SKIP suspicious IDAT at off=$off LEN=$LEN next=$NEXTOFF_TYPE" >&2
    continue
  fi
  dd if="$F" bs=1 skip=$((off+4)) count=$LEN 2>/dev/null >> "$TMP/$B.deflate"
done < "$TMP/$B.idat"

# strip 2B zlib header + 4B adler32
defl_sz=$(wc -c < "$TMP/$B.deflate")
dd if="$TMP/$B.deflate" bs=1 skip=2 count=$((defl_sz - 6)) 2>/dev/null > "$TMP/$B.raw_deflate"

# gzip wrap: header + deflate + crc0 + isize(uncomp, LE)
{
  printf '\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x03'
  cat "$TMP/$B.raw_deflate"
  printf '\x00\x00\x00\x00'
  printf "$(printf '\\x%02x\\x%02x\\x%02x\\x%02x' \
    $(( UNCOMP & 255 )) $(( (UNCOMP>>8) & 255 )) $(( (UNCOMP>>16) & 255 )) $(( (UNCOMP>>24) & 255 )))"
} > "$TMP/$B.gz"

gunzip -c "$TMP/$B.gz" > "$TMP/$B.raw" 2>/dev/null
RAW_SZ=$(wc -c < "$TMP/$B.raw")
if [ "$RAW_SZ" -ne "$UNCOMP" ]; then
  echo "DECOMPRESS SIZE MISMATCH: got $RAW_SZ want $UNCOMP (W=$W H=$H bpp=$BPP)" >&2
  exit 1
fi

od -An -v -tu1 "$TMP/$B.raw" | gawk -v W="$W" -v H="$H" -v BPP="$BPP" -v STRIDE="$STRIDE" '
BEGIN {
  i = 0
  while ((getline line) > 0) {
    n = split(line, a, " ")
    for (k = 1; k <= n; k++) b[i++] = a[k] + 0
  }
  # unfilter
  for (r = 0; r < H; r++) {
    base = r * STRIDE
    ft = b[base]
    for (x = 0; x < STRIDE - 1; x++) {
      idx = base + 1 + x
      cur = b[idx]
      left  = (x >= BPP)         ? b[idx - BPP] : 0
      up    = (r > 0)            ? b[idx - STRIDE] : 0
      ul    = (r > 0 && x >= BPP)? b[idx - STRIDE - BPP] : 0
      if (ft == 1)      v = cur + left
      else if (ft == 2) v = cur + up
      else if (ft == 3) v = cur + int((left + up) / 2)
      else if (ft == 4) {
        p = left + up - ul
        da = (p > left) ? p - left : left - p
        db = (p > up)   ? p - up   : up - p
        dc = (p > ul)   ? p - ul   : ul - p
        if (da <= db && da <= dc) pr = left
        else if (db <= dc)        pr = up
        else                      pr = ul
        v = cur + pr
      } else v = cur
      b[idx] = ((v % 256) + 256) % 256
    }
  }
  # stats
  minx = 1e9; maxx = -1; miny = 1e9; maxy = -1
  nGold = 0; sr = 0; sg = 0; sb = 0
  nGrey = 0; gr = 0; gg = 0; gb = 0
  # shadow probes: bg ring samples
  for (r = 0; r < H; r++) {
    base = r * STRIDE + 1
    for (x = 0; x < W; x++) {
      o = base + x * BPP
      R = b[o]; G = b[o+1]; B = b[o+2]
      if (BPP == 4) A = b[o+3]
      chroma = R - B
      if (chroma > 35 && R > 90) {   # gold mask
        nGold++; sr += R; sg += G; sb += B
        if (x < minx) minx = x
        if (x > maxx) maxx = x
        if (r < miny) miny = r
        if (r > maxy) maxy = r
      }
      # background: corners region only (far from center disc)
      if ((x < 60 || x > W-60) && (r < 60 || r > H-60)) {
        nGrey++; gr += R; gg += G; gb += B
      }
    }
  }
  gw = maxx - minx + 1; gh = maxy - miny + 1
  printf "DIMS           %dx%d depth=%d ctype=%d\n", W, H, 8, (BPP == 4 ? 6 : 2)
  printf "GOLD_BBOX      x[%d..%d] y[%d..%d]  w=%d h=%d  ovality=%.4f\n", minx, maxx, miny, maxy, gw, gh, gh/gw
  printf "GOLD_CENTER    (%.1f,%.1f)  cell_frac=%.3f\n", minx + gw/2, miny + gh/2, gw / W
  printf "GOLD_MEAN_RGB  %.1f %.1f %.1f   (target #D9B24C = 217 178 76)\n", sr/nGold, sg/nGold, sb/nGold
  printf "GOLD_N         %d  (%.1f%% of frame)\n", nGold, 100.0*nGold/(W*H)
  printf "GREY_BG_MEAN   %.1f %.1f %.1f  n=%d\n", gr/nGrey, gg/nGrey, gb/nGrey, nGrey
  # saturation of gold (S ~ chroma/R)
  gmR = sr/nGold
  printf "GOLD_DOMINANCE vs bg: dR=%+.1f dG=%+.1f dB=%+.1f\n", gmR - gr/nGrey, sg/nGold - gg/nGrey, sb/nGold - gb/nGrey
}'
rm -f "$TMP/$B.idat" "$TMP/$B.deflate" "$TMP/$B.raw_deflate" "$TMP/$B.gz"
echo "RAW kept: $TMP/$B.raw"
