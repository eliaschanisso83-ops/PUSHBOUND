#!/data/data/com.termux/files/usr/bin/bash
# gen_preview.sh MODE CELL OUT.png LEVEL.txt [DEBUG]
# Renders a board preview with the real sprite pixels, mirroring the Kotlin
# renderer's grid math exactly (MODE: old = naive frame-to-cell, new =
# footprint system). LEVEL.txt uses the LevelDefinition ASCII symbols.
# DEBUG=1 adds the debug-grid overlay (boundaries + semantic cell fills).
set -eu
MODE="$1"; CELL="$2"; OUT="$3"; LEVEL="$4"; DEBUG="${5:-0}"
T=/data/data/com.termux/files/usr/tmp/opencode

gawk -v MODE="$MODE" -v CELL="$CELL" -v OUT="$OUT" -v DEBUG="$DEBUG" -v SPR="$T/spr" -v LEVEL="$LEVEL" '
function rint(x) { return int(x + 0.5) }
BEGIN {
  # ---- level ------------------------------------------------------------
  n = 0
  while ((getline row < LEVEL) > 0) {
    n++
    H = n; W = length(row)
    for (i = 1; i <= W; i++) {
      ch = substr(row, i, 1); c = i - 1
      if (ch == "#") { nw++; wc[nw] = c; wr[nw] = n - 1 }
      else if (ch == "G") { ng++; gc[ng] = c; gr[ng] = n - 1 }
      else if (ch == "B") { nb++; bc[nb] = c; br[nb] = n - 1 }
      else if (ch == "+") { ng++; gc[ng] = c; gr[ng] = n - 1; nb++; bc[nb] = c; br[nb] = n - 1 }
      else if (ch == "P") { pc = c; pr = n - 1 }
    }
  }
  # index walls for run-end queries
  for (i = 1; i <= nw; i++) iswall[wr[i] "," wc[i]] = 1

  CW = W * CELL; CH = H * CELL
  print "canvas " CW " " CH " 43 38 34 255"
  print "load floor " SPR "/environment_floor_base.rgba 1024 1024"
  print "load wall " SPR "/environment_wall_straight.rgba 1024 1024"
  print "load goal " SPR "/object_goal.rgba 1024 1024"
  print "load box " SPR "/object_box.rgba 1024 1024"
  print "load player " SPR "/character_idle.rgba 1024 1024"

  k = CELL / 1024
  # measured subjects (tools/png_bbox.sh on the approved art)
  boxX = 154; boxY = 146; boxW = 694; boxH = 694; boxFP = 0.74
  goalX = 105; goalY = 102; goalW = 807; goalH = 819; goalFP = 0.86
  plX = 319; plY = 129; plW = 378; plH = 773; plFP = 0.42
  # wall internals (tools/png_profile.c): face = one masonry course
  # period 392..529 (mortar + brick face + AO under-shadow)
  capY = 332; capH = 63
  faceY = 392; faceH = 137
  bond = 88
  pitch = faceH * k
  bondPx = rint(bond * k)

  # ---- floor (exact cell fill; tiles edge to edge) -----------------------
  for (r = 0; r < H; r++) for (c = 0; c < W; c++)
    print "stamp floor 0 0 1024 1024 " rint(c*CELL) " " rint(r*CELL) " " CELL " " CELL

  # ---- goals -------------------------------------------------------------
  for (i = 1; i <= ng; i++) {
    if (MODE == "old") {
      print "stamp goal 0 0 1024 1024 " rint(gc[i]*CELL) " " rint(gr[i]*CELL) " " CELL " " CELL
    } else {
      w = goalFP * CELL; h = rint(w * goalH / goalW)
      cx = rint((gc[i] + 0.5) * CELL); cy = rint((gr[i] + 0.5) * CELL)
      print "stamp goal " goalX " " goalY " " goalW " " goalH " " rint(cx - w/2) " " rint(cy - h/2) " " rint(w) " " h
    }
  }

  # ---- walls -------------------------------------------------------------
  for (i = 1; i <= nw; i++) {
    if (MODE == "old") {
      print "stamp wall 0 0 1024 1024 " rint(wc[i]*CELL) " " rint(wr[i]*CELL) " " CELL " " CELL
    } else {
      cx0 = rint(wc[i] * CELL); cy0 = rint(wr[i] * CELL)
      print "clip " cx0 " " cy0 " " CELL " " CELL
      m0 = int(cy0 / pitch) - 1; m1 = int((cy0 + CELL) / pitch) + 2
      for (m = m0; m <= m1; m++) {
        y0 = rint(m*pitch); y1 = rint((m+1)*pitch)
        if (bondPx > 0 && bondPx < CELL && (m % 2 + 2) % 2 == 1) {
          print "stamp wall " bond " " faceY " " (1024-bond) " " faceH " " (cx0+bondPx) " " y0 " " (CELL-bondPx) " " (y1-y0)
          print "stamp wall 0 " faceY " " bond " " faceH " " cx0 " " y0 " " bondPx " " (y1-y0)
        } else {
          print "stamp wall 0 " faceY " 1024 " faceH " " cx0 " " y0 " " CELL " " (y1-y0)
        }
      }
      above = ((wr[i]-1) "," wc[i]) in iswall
      below = ((wr[i]+1) "," wc[i]) in iswall
      if (!above || !below)
        print "stamp wall 0 " capY " 1024 " capH " " cx0 " " cy0 " " CELL " " rint(capH * k)
      print "clipoff"
    }
  }

  # ---- boxes -------------------------------------------------------------
  for (i = 1; i <= nb; i++) {
    if (MODE == "old") {
      print "stamp box 0 0 1024 1024 " rint(bc[i]*CELL) " " rint(br[i]*CELL) " " CELL " " CELL
    } else {
      w = boxFP * CELL; h = rint(w * boxH / boxW)
      cx = rint((bc[i] + 0.5) * CELL); cb = rint((br[i] + 1) * CELL)
      print "stamp box " boxX " " boxY " " boxW " " boxH " " rint(cx - w/2) " " (cb - h) " " rint(w) " " h
    }
  }

  # ---- player ------------------------------------------------------------
  if (MODE == "old") {
    print "stamp player 0 0 1024 1024 " rint(pc*CELL) " " rint(pr*CELL) " " CELL " " CELL
  } else {
    w = plFP * CELL; h = rint(w * plH / plW)
    cx = rint((pc + 0.5) * CELL); cb = rint((pr + 1) * CELL)
    print "stamp player " plX " " plY " " plW " " plH " " rint(cx - w/2) " " (cb - h) " " rint(w) " " h
  }

  # ---- debug grid overlay -------------------------------------------------
  if (DEBUG) {
    for (i = 1; i <= nw; i++) print "rect " rint(wc[i]*CELL) " " rint(wr[i]*CELL) " " CELL " " CELL " 255 149 0 60"
    for (i = 1; i <= ng; i++) print "rect " rint(gc[i]*CELL) " " rint(gr[i]*CELL) " " CELL " " CELL " 255 214 10 60"
    for (i = 1; i <= nb; i++) print "rect " rint(bc[i]*CELL) " " rint(br[i]*CELL) " " CELL " " CELL " 48 209 88 60"
    print "rect " rint(pc*CELL) " " rint(pr*CELL) " " CELL " " CELL " 10 132 255 60"
    for (r = 0; r <= H; r++) print "hline 0 " CW " " rint(r*CELL) " 1 255 59 48 170"
    for (c = 0; c <= W; c++) print "vline " rint(c*CELL) " 0 " CH " 1 255 59 48 170"
  }

  print "emit " OUT
}' > "$T/cmds.txt"

"$T/compose_board" < "$T/cmds.txt"
ls -la "$OUT" | awk '{print $5, $9}'
