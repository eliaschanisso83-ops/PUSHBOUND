#!/data/data/com.termux/files/usr/bin/bash
# png_rowprofile.sh FILE Y0 Y1 — print per-row avg brightness and avg alpha
# within the row range (inclusive). Locates mortar lines / course seams.
set -u
F="$1"; Y0="$2"; Y1="$3"
TMP=/data/data/com.termux/files/usr/tmp/opencode
B=$(basename "$F" .png)_prof

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

od -An -v -tu1 "$TMP/$B.raw" | gawk -v W="$W" -v H="$H" -v BPP="$BPP" -v STRIDE="$STRIDE" -v CT="$CTYPE" \
  -v Y0="$Y0" -v Y1="$Y1" '
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
  for(r=Y0;r<=Y1;r++){
    sb=0; sa=0
    for(x=0;x<W;x++){o=r*STRIDE+1+x*BPP
      sb+=(b[o]+b[o+1]+b[o+2])/3
      if(BPP==4) sa+=b[o+3]}
    printf "%d %d %d\n", r, sb/W, sa/W}
}' FILE=/dev/null
rm -f "$TMP/$B.idat" "$TMP/$B.deflate" "$TMP/$B.d" "$TMP/$B.gz" "$TMP/$B.raw"
