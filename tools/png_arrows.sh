#!/data/data/com.termux/files/usr/bin/bash
# png_arrows.sh FILE — measure dark-arrow bbox + shaft thickness per D-pad button region.
set -u
F="$1"
TMP=/data/data/com.termux/files/usr/tmp/opencode
B=$(basename "$F" .png)_arw

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

od -An -v -tu1 "$TMP/$B.raw" | gawk -v W="$W" -v H="$H" -v BPP="$BPP" -v STRIDE="$STRIDE" -v FILE="$(basename "$F")" '
function dark(o,  s){ s=b[o]+b[o+1]+b[o+2]; return (s<240) }   # arrow ~124; rim/shadow >=300
function tilep(o,  R,G,Bv){ R=b[o];G=b[o+1];Bv=b[o+2]; return ((R-Bv)>8) }  # beige tiles; grey bg/shadow neutral
BEGIN{
  i=0; while((getline line)>0){n=split(line,a," ");for(k=1;k<=n;k++)b[i++]=a[k]+0}
  # regions: name x0 y0 x1 y1  (tile interiors only, avoid neighbours)
  reg[1]="UP 375 210 635 460"
  reg[2]="LEFT 75 510 335 760"
  reg[3]="DOWN 375 510 635 760"
  reg[4]="RIGHT 675 510 935 760"
  for(r=1;r<=4;r++){
    split(reg[r],f," "); nm=f[1]; x0=f[2]; y0=f[3]; x1=f[4]; y1=f[5]
    minx=1e9;maxx=-1;miny=1e9;maxy=-1
    for(y=y0;y<=y1;y++)for(x=x0;x<=x1;x++){ o=y*STRIDE+1+x*BPP
      if(dark(o)){ if(x<minx)minx=x; if(x>maxx)maxx=x; if(y<miny)miny=y; if(y>maxy)maxy=y } }
    aw=maxx-minx+1; ah=maxy-miny+1
    # vertical arrows (UP/DOWN): shaft thickness measured at 72% of length from head end
    if(nm=="UP"){   # head at top: sample in shaft at y = maxy - 0.28*ah
      ys=int(maxy-0.28*ah); run=0; best=0
      for(x=minx;x<=maxx;x++){ if(dark(ys*STRIDE+1+x*BPP)) run++; else {if(run>best)best=run; run=0} }
      if(run>best)best=run
      shaft=best
    } else if(nm=="DOWN"){ # head at bottom: shaft at y = miny + 0.28*ah
      ys=int(miny+0.28*ah); run=0; best=0
      for(x=minx;x<=maxx;x++){ if(dark(ys*STRIDE+1+x*BPP)) run++; else {if(run>best)best=run; run=0} }
      if(run>best)best=run
      shaft=best
    } else if(nm=="LEFT"){ # head at left: shaft at x = maxx - 0.28*aw
      xs=int(maxx-0.28*aw); run=0; best=0
      for(y=miny;y<=maxy;y++){ if(dark(y*STRIDE+1+xs*BPP)) run++; else {if(run>best)best=run; run=0} }
      if(run>best)best=run
      shaft=best
    } else { # RIGHT: head at right: shaft at x = minx + 0.28*aw
      xs=int(minx+0.28*aw); run=0; best=0
      for(y=miny;y<=maxy;y++){ if(dark(y*STRIDE+1+xs*BPP)) run++; else {if(run>best)best=run; run=0} }
      if(run>best)best=run
      shaft=best
    }
    printf "%-6s arrow bbox x[%d..%d] y[%d..%d]  len=%d head=%d shaft=%d  center=(%.1f,%.1f)\n", \
      nm, minx,maxx,miny,maxy, (nm=="UP"||nm=="DOWN")?ah:aw, (nm=="UP"||nm=="DOWN")?aw:ah, shaft, minx+(maxx-minx)/2, miny+(maxy-miny)/2
    # button tile bbox: warmth criterion (beige tile vs neutral grey bg/shadow)
    tminx=1e9;tmaxx=-1;tminy=1e9;tmaxy=-1
    for(y=y0-10;y<=y1+10;y++)for(x=x0-10;x<=x1+10;x++){
      if(x<0||y<0||x>=W||y>=H)continue
      o=y*STRIDE+1+x*BPP
      if(tilep(o)){ if(x<tminx)tminx=x; if(x>tmaxx)tmaxx=x; if(y<tminy)tminy=y; if(y>tmaxy)tmaxy=y } }
    printf "       tile bbox x[%d..%d] y[%d..%d]  w=%d h=%d\n", tminx,tmaxx,tminy,tmaxy, tmaxx-tminx+1, tmaxy-tminy+1
    TX[nm]=tminx; TY[nm]=tminy; TX2[nm]=tmaxx; TY2[nm]=tmaxy
    AX[nm]=minx; AY[nm]=miny; AX2[nm]=maxx; AY2[nm]=maxy
  }
  printf "GAPS: h LEFT|DOWN=%d DOWN|RIGHT=%d   v UP|row=%d\n", TX["DOWN"]-TX2["LEFT"], TX["RIGHT"]-TX2["DOWN"], TY["DOWN"]-TY2["UP"]
  printf "ARROW CENTERING (arrow center - tile center): UP(%.1f,%.1f) DOWN(%.1f,%.1f) LEFT(%.1f,%.1f) RIGHT(%.1f,%.1f)\n", \
    (AX["UP"]+AX2["UP"])/2-(TX["UP"]+TX2["UP"])/2, (AY["UP"]+AY2["UP"])/2-(TY["UP"]+TY2["UP"])/2, \
    (AX["DOWN"]+AX2["DOWN"])/2-(TX["DOWN"]+TX2["DOWN"])/2, (AY["DOWN"]+AY2["DOWN"])/2-(TY["DOWN"]+TY2["DOWN"])/2, \
    (AX["LEFT"]+AX2["LEFT"])/2-(TX["LEFT"]+TX2["LEFT"])/2, (AY["LEFT"]+AY2["LEFT"])/2-(TY["LEFT"]+TY2["LEFT"])/2, \
    (AX["RIGHT"]+AX2["RIGHT"])/2-(TX["RIGHT"]+TX2["RIGHT"])/2, (AY["RIGHT"]+AY2["RIGHT"])/2-(TY["RIGHT"]+TY2["RIGHT"])/2
}' 
rm -f "$TMP/$B.idat" "$TMP/$B.deflate" "$TMP/$B.d" "$TMP/$B.gz" "$TMP/$B.raw"
