#!/data/data/com.termux/files/usr/bin/bash
# png_qa_sprite.sh FILE — single-pass sprite QA: bbox, alpha histogram,
# footprint:height ratio vs 168:72, center-x, teal share, contact-shadow
# partial-alpha near feet, hidden RGB under a=0.
set -u
F="$1"
TMP=/data/data/com.termux/files/usr/tmp/opencode
B=$(basename "$F" .png)_qa

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

od -An -v -tu1 "$TMP/$B.raw" | gawk -v W="$W" -v H="$H" -v BPP="$BPP" -v STRIDE="$STRIDE" -v CT="$CTYPE" -v FILE="$(basename "$F")" '
BEGIN{
  i=0; while((getline line)>0){n=split(line,a," ");for(k=1;k<=n;k++)b[i++]=a[k]+0}
  for(r=0;r<H;r++){base=r*STRIDE;ft=b[base]
    for(x=0;x<STRIDE-1;x++){idx=base+1+x;cur=b[idx]
      left=(x>=BPP)?b[idx-BPP]:0; up=(r>0)?b[idx-STRIDE]:0; ul=(r>0&&x>=BPP)?b[idx-STRIDE-BPP]:0
      if(ft==1)v=cur+left; else if(ft==2)v=cur+up; else if(ft==3)v=cur+int((left+up)/2)
      else if(ft==4){p=left+up-ul;da=(p>left)?p-left:left-p;db=(p>up)?p-up:up-p;dc=(p>ul)?p-ul:ul-p
        if(da<=db&&da<=dc)pr=left; else if(db<=dc)pr=up; else pr=ul; v=cur+pr}
      else v=cur
      b[idx]=((v%256)+256)%256}}
  hasA=(BPP==4)
  sminx=1e9;smaxx=-1;sminy=1e9;smaxy=-1   # core a>200
  fminx=1e9;fmaxx=-1;fminy=1e9;fmaxy=-1   # soft a>16
  nA0=0;nA255=0;nPart=0
  tealN=0; coolN=0; sumR=0;sumG=0;sumB=0   # cool-hue share among core
  hidR=0;hidG=0;hidB=0;hidN=0
  for(y=0;y<H;y++)for(x=0;x<W;x++){o=y*STRIDE+1+x*BPP
    R=b[o];G=b[o+1];B=b[o+2];A=hasA?b[o+3]:255
    if(A==0){nA0++;hidR+=R;hidG+=G;hidB+=B;hidN++}
    else if(A==255)nA255++
    else nPart++
    if(A>16){if(x<fminx)fminx=x;if(x>fmaxx)fmaxx=x;if(y<fminy)fminy=y;if(y>fmaxy)fmaxy=y}
    if(A>200){if(x<sminx)sminx=x;if(x>smaxx)smaxx=x;if(y<sminy)sminy=y;if(y>smaxy)smaxy=y
      coreN++;sumR+=R;sumG+=G;sumB+=B
      if(B>R+10 && G>R+5 && B<200)coolN++}
  }
  # contact shadow: partial-alpha pixels in bottom 140 rows of figure
  shad=0
  if(hasA){for(y=(smaxy-140>0?smaxy-140:0);y<=smaxy && y<H;y++)for(x=0;x<W;x++){o=y*STRIDE+1+x*BPP
      A=b[o+3]; if(A>4 && A<200)shad++}}
  tot=nA0+nA255+nPart
  cw=smaxx-sminx+1; ch=smaxy-sminy+1
  printf "FILE %s  %dx%d ct=%d\n", FILE, W, H, CT
  printf "CORE(a>200) x[%d..%d] y[%d..%d] w=%d h=%d  ratio h/w=%.4f (spec 168/72=%.4f)  cx=%.1f bottom=%d\n", sminx,smaxx,sminy,smaxy, cw, ch, ch/cw, 168/72, sminx+(smaxx-sminx)/2, smaxy
  printf "SOFT(a>16)  x[%d..%d] y[%d..%d] w=%d h=%d\n", fminx,fmaxx,fminy,fmaxy, fmaxx-fminx+1, fmaxy-fminy+1
  printf "ALPHA  a=0: %d (%.2f%%)  a=255: %d (%.2f%%)  partial: %d (%.2f%%)\n", nA0, 100*nA0/tot, nA255, 100*nA255/tot, nPart, 100*nPart/tot
  n=coreN>0?coreN:1
  printf "COOL/TEAL share of core: %.1f%%   core mean RGB: (%.0f,%.0f,%.0f)\n", 100*coolN/n, sumR/n, sumG/n, sumB/n
  printf "HIDDEN RGB under a=0: mean (%.1f,%.1f,%.1f) over %d px\n", hidR/hidN, hidG/hidN, hidB/hidN, hidN
  printf "CONTACT SHADOW partial-alpha in bottom 140 rows: %d px\n", shad
}'
rm -f "$TMP/$B.idat" "$TMP/$B.deflate" "$TMP/$B.d" "$TMP/$B.gz" "$TMP/$B.raw"
