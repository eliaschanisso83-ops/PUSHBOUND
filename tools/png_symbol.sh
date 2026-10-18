#!/data/data/com.termux/files/usr/bin/bash
# png_symbol.sh FILE [cx cy rmin rmax] — measure dark symbol bbox, tile bbox (warmth),
# and angular thickness profile to locate the arrow head (direction check).
set -u
F="$1"
CX=${2:-512}; CY=${3:-512}; RMIN=${4:-0}; RMAX=${5:-400}
TMP=/data/data/com.termux/files/usr/tmp/opencode
B=$(basename "$F" .png)_sym

read W H DEPTH CTYPE < <(dd if="$F" bs=1 skip=16 count=10 2>/dev/null | od -An -tu1 -w10 | awk '{print $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8, $9, $10}')
BPP=3; [ "$CTYPE" = 6 ] && BPP=4
STRIDE=$((1 + W * BPP)); UNCOMP=$((H * STRIDE))

grep -abo 'IDAT' "$F" | cut -d: -f1 > "$TMP/$B.idat"
: > "$TMP/$B.deflate"
while read -r off; do
  LEN=$(dd if="$F" bs=1 skip=$((off-4)) count=4 2>/dev/null | od -An -tu1 -w4 | awk '{print $1*16777216+$2*65536+$3*256+$4}')
  NEXT=$(dd if="$F" bs=1 skip=$((off+LEN+12)) count=4 2>/dev/null | tr -cd 'A-Z')
  case "$NEXT" in [A-Z][A-Z][A-Z][A-Z]) ;; *) continue;; esac
  dd if="$F" bs=1 skip=$((off+4)) count=$LEN 2>/dev/null >> "$TMP/$B.deflate"
done < "$TMP/$B.idat"
sz=$(wc -c < "$TMP/$B.deflate")
dd if="$TMP/$B.deflate" bs=1 skip=2 count=$((sz-6)) 2>/dev/null > "$TMP/$B.d"
{
  printf '\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x03'
  cat "$TMP/$B.d"
  printf '\x00\x00\x00\x00'
  printf "$(printf '\\x%02x\\x%02x\\x%02x\\x%02x' $((UNCOMP&255)) $(((UNCOMP>>8)&255)) $(((UNCOMP>>16)&255)) $(((UNCOMP>>24)&255)))"
} > "$TMP/$B.gz"
gunzip -c "$TMP/$B.gz" > "$TMP/$B.raw" 2>/dev/null
RAW_SZ=$(wc -c < "$TMP/$B.raw")
[ "$RAW_SZ" -ne "$UNCOMP" ] && { echo "DECOMPRESS FAIL got=$RAW_SZ want=$UNCOMP" >&2; exit 1; }

od -An -v -tu1 "$TMP/$B.raw" | gawk -v W="$W" -v H="$H" -v BPP="$BPP" -v STRIDE="$STRIDE" \
  -v CX="$CX" -v CY="$CY" -v RMIN="$RMIN" -v RMAX="$RMAX" -v FILE="$(basename "$F")" '
function dark(o,  s){ s=b[o]+b[o+1]+b[o+2]; return (s<240) }
function tilep(o,  R,Bv){ R=b[o];Bv=b[o+2]; return ((R-Bv)>8) }
BEGIN{
  i=0; while((getline line)>0){n=split(line,a," ");for(k=1;k<=n;k++)b[i++]=a[k]+0}
  # dark symbol bbox + per-angle radial max/min
  sminx=1e9;smaxx=-1;sminy=1e9;smaxy=-1; sx=0; sy=0; sc=0
  tminx=1e9;tmaxx=-1;tminy=1e9;tmaxy=-1
  for(y=0;y<H;y++)for(x=0;x<W;x++){
    o=y*STRIDE+1+x*BPP
    if(dark(o)){
      if(x<sminx)sminx=x; if(x>smaxx)smaxx=x
      if(y<sminy)sminy=y; if(y>smaxy)smaxy=y
      sx+=x; sy+=y; sc++
      dx=x-CX; dy=y-CY; r=sqrt(dx*dx+dy*dy)
      if(r>=RMIN && r<=RMAX){
        ang=atan2(dy,dx); if(ang<0)ang+=6.283185307
        bucket=int(ang*18/3.141592654)   # 36 buckets of 10 deg, 0=east, increasing => clockwise on screen(y down)
        if(r>rmax[bucket])rmax[bucket]=r
        if(rmin[bucket]==0 || r<rmin[bucket])rmin[bucket]=r
        rc[bucket]++
      }
    }
    if(tilep(o)){
      if(x<tminx)tminx=x; if(x>tmaxx)tmaxx=x
      if(y<tminy)tminy=y; if(y>tmaxy)tmaxy=y
    }
  }
  printf "%s symbol dark bbox x[%d..%d] y[%d..%d] w=%d h=%d centroid=(%.1f,%.1f) px=%d\n", \
    FILE, sminx,smaxx,sminy,smaxy, smaxx-sminx+1, smaxy-sminy+1, sx/sc, sy/sc, sc
  printf "    tile bbox x[%d..%d] y[%d..%d] w=%d h=%d\n", tminx,tmaxx,tminy,tmaxy, tmaxx-tminx+1, tmaxy-tminy+1
  # angular thickness (maxr-minr) — head = thickest
  n=0
  for(k=0;k<36;k++){ if(rc[k]>3){ th[k]=rmax[k]-rmin[k]; n++ } }
  # print sorted by thickness desc (top 6)
  for(s=0;s<6;s++){
    best=-1; bi=-1
    for(k=0;k<36;k++){ if(done[k])continue; if(rc[k]<=3)continue; if(th[k]>best){best=th[k];bi=k} }
    if(bi<0)break
    done[bi]=1
    deg=bi*10
    # screen coords: angle deg measured with y down => 0=E, 90=S, 180=W, 270=N
    dirn = (deg<45||(deg>=315))?"E":(deg<135?"S":(deg<225?"W":"N"))
    printf "    angle %3d deg (%s): radial span r[%.0f..%.0f] thickness=%.0f count=%d\n", deg, dirn, rmin[bi], rmax[bi], best, rc[bi]
  }
}'
rm -f "$TMP/$B.idat" "$TMP/$B.deflate" "$TMP/$B.d" "$TMP/$B.gz" "$TMP/$B.raw"
