import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Pushbound brand generator.
 *
 * One geometry model -> two backends (Java2D raster, SVG text), so the
 * vector source and every PNG are guaranteed identical. No system fonts are
 * used anywhere: the wordmark is drawn from geometric primitives, which is
 * what makes it proprietary rather than typed text.
 *
 * Coordinates are y-DOWN (SVG convention), world units per composition.
 */
public class LogoKit {

    static final int RECT = 0, RING = 1, ARCRING = 2, POLY = 3, STROKE = 4;

    static class Prim {
        int type; double[] p; String color; String d; Path2D.Double gp;
        Prim(int t, String c, double... p) { type = t; color = c; this.p = p; }
    }

    static Prim rect(String c, double x, double y, double w, double h) {
        return new Prim(RECT, c, x, y, w, h);
    }
    static Prim ring(String c, double cx, double cy, double rOut, double rIn) {
        return new Prim(RING, c, cx, cy, rOut, rIn);
    }
    /** Angles are y-down screen degrees: 0=E, 90=S, 180=W, 270=N (increasing = clockwise). */
    static Prim arcRing(String c, double cx, double cy, double ro, double ri, double a0, double a1) {
        return new Prim(ARCRING, c, cx, cy, ro, ri, a0, a1);
    }
    static Prim poly(String c, double... pts) { return new Prim(POLY, c, pts); }
    static Prim stroke(String c, double w, Path2D.Double gp, String d) {
        Prim pr = new Prim(STROKE, c, w); pr.gp = gp; pr.d = d; return pr;
    }

    // ----------------------------------------------------------------- raster
    static void paint(Graphics2D g, List<Prim> prims) {
        for (Prim pr : prims) {
            g.setColor(new Color(Integer.parseInt(pr.color.substring(1), 16)));
            switch (pr.type) {
                case RECT:
                    g.fill(new Rectangle2D.Double(pr.p[0], pr.p[1], pr.p[2], pr.p[3]));
                    break;
                case RING: {
                    double cx = pr.p[0], cy = pr.p[1], ro = pr.p[2], ri = pr.p[3];
                    Area a = new Area(new Ellipse2D.Double(cx - ro, cy - ro, 2 * ro, 2 * ro));
                    a.subtract(new Area(new Ellipse2D.Double(cx - ri, cy - ri, 2 * ri, 2 * ri)));
                    g.fill(a);
                    break;
                }
                case ARCRING: {
                    double cx = pr.p[0], cy = pr.p[1], ro = pr.p[2], ri = pr.p[3];
                    double a0 = pr.p[4], a1 = pr.p[5];
                    Path2D.Double path = new Path2D.Double();
                    path.append(new Arc2D.Double(cx - ro, cy - ro, 2 * ro, 2 * ro, a0, a1 - a0, Arc2D.OPEN), true);
                    path.lineTo(cx + ri * Math.cos(Math.toRadians(a1)), cy + ri * Math.sin(Math.toRadians(a1)));
                    path.append(new Arc2D.Double(cx - ri, cy - ri, 2 * ri, 2 * ri, a1, a0 - a1, Arc2D.OPEN), true);
                    path.closePath();
                    g.fill(path);
                    break;
                }
                case POLY: {
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(pr.p[0], pr.p[1]);
                    for (int i = 2; i < pr.p.length; i += 2) path.lineTo(pr.p[i], pr.p[i + 1]);
                    path.closePath();
                    g.fill(path);
                    break;
                }
                case STROKE:
                    g.setStroke(new BasicStroke((float) pr.p[0], BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                    g.draw(pr.gp);
                    break;
            }
        }
    }

    static String f(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        if (s.endsWith("00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String svg(List<Prim> prims, double w, double h) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(f(w)).append(' ').append(f(h));
        sb.append("\" width=\"").append(f(w)).append("\" height=\"").append(f(h)).append("\">\n");
        for (Prim pr : prims) {
            sb.append("  <path fill=\"").append(pr.color).append("\"");
            String d;
            switch (pr.type) {
                case RECT: {
                    double x = pr.p[0], y = pr.p[1], ww = pr.p[2], hh = pr.p[3];
                    d = "M" + f(x) + " " + f(y) + "H" + f(x + ww) + "V" + f(y + hh) + "H" + f(x) + "Z";
                    break;
                }
                case RING: {
                    double cx = pr.p[0], cy = pr.p[1], ro = pr.p[2], ri = pr.p[3];
                    d = circle(cx, cy, ro) + circle(cx, cy, ri);
                    sb.setLength(sb.length() - 2); // drop the closing quote, add fill-rule
                    sb.append(" fill-rule=\"evenodd\">");
                    sb.append("  <path fill=\"").append(pr.color).append("\" fill-rule=\"evenodd\" d=\"").append(d).append("\"/>\n");
                    continue;
                }
                case ARCRING: {
                    double cx = pr.p[0], cy = pr.p[1], ro = pr.p[2], ri = pr.p[3], a0 = pr.p[4], a1 = pr.p[5];
                    double t1 = Math.toRadians(a1);
                    d = arc(cx, cy, ro, a0, a1)
                        + "L" + f(cx + ri * Math.cos(t1)) + " " + f(cy - ri * Math.sin(t1))
                        + arc(cx, cy, ri, a1, a0) + "Z";
                    break;
                }
                case POLY: {
                    StringBuilder p = new StringBuilder("M" + f(pr.p[0]) + " " + f(pr.p[1]));
                    for (int i = 2; i < pr.p.length; i += 2) p.append("L").append(f(pr.p[i])).append(" ").append(f(pr.p[i + 1]));
                    d = p.append("Z").toString();
                    break;
                }
                default:
                    sb.setLength(sb.length() - 2);
                    sb.append(" fill=\"none\" stroke=\"").append(pr.color)
                      .append("\" stroke-width=\"").append(f(pr.p[0]))
                      .append("\" stroke-linecap=\"butt\" stroke-linejoin=\"round\" d=\"").append(pr.d).append("\"/>\n");
                    continue;
            }
            sb.append(" d=\"").append(d).append("\"/>\n");
        }
        sb.append("</svg>\n");
        return sb.toString();
    }

    static String circle(double cx, double cy, double r) {
        return "M" + f(cx - r) + " " + f(cy) + "A" + f(r) + " " + f(r) + " 0 1 1 " + f(cx + r) + " " + f(cy)
            + "A" + f(r) + " " + f(r) + " 0 1 1 " + f(cx - r) + " " + f(cy) + "Z";
    }

    /**
     * Arc matching the raster backend exactly.
     *
     * <p>Java2D measures arc angles from east, increasing COUNTERclockwise on
     * screen — verified at pixel level: {@code Arc2D(0, 90)} fills the
     * north-east quadrant, and {@code Arc2D(0, 180)} spans the north half.
     * SVG's sweep-flag is the opposite way round (1 = clockwise on screen), so
     * the flag is the inverse of the sign of the sweep, and the endpoints use
     * {@code cy - r sin(a)} rather than {@code cy + r sin(a)}.
     */
    static String arc(double cx, double cy, double r, double a0, double a1) {
        double sweep = a1 - a0;
        int large = Math.abs(sweep) > 180 ? 1 : 0;
        int dir = sweep < 0 ? 1 : 0;          // Java2D CCW  <=>  SVG sweep-flag 0
        double t0 = Math.toRadians(a0), t1 = Math.toRadians(a1);
        double x0 = cx + r * Math.cos(t0), y0 = cy - r * Math.sin(t0);
        double x1 = cx + r * Math.cos(t1), y1 = cy - r * Math.sin(t1);
        return "M" + f(x0) + " " + f(y0) + "A" + f(r) + " " + f(r) + " 0 " + large + " " + dir + " " + f(x1) + " " + f(y1);
    }

    /** Supersampled render + area-average downscale = clean edges at any size. */
    static BufferedImage renderImage(List<Prim> prims, double cw, double ch, int outW, int outH, String bg) {
        int ss = outW >= 256 ? 4 : 3;
        BufferedImage big = new BufferedImage(outW * ss, outH * ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (bg != null) { g.setColor(new Color(Integer.parseInt(bg.substring(1), 16))); g.fillRect(0, 0, big.getWidth(), big.getHeight()); }
        double s = (double) big.getWidth() / cw;
        g.scale(s, s);
        paint(g, prims);
        g.dispose();
        Image scaled = outW * ss == outW ? big : big.getScaledInstance(outW, outH, Image.SCALE_AREA_AVERAGING);
        BufferedImage fin = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = fin.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(scaled, 0, 0, null);
        g2.dispose();
        return fin;
    }

    static void render(List<Prim> prims, double cw, double ch, int outW, int outH, String out, String bg) throws IOException {
        ImageIO.write(renderImage(prims, cw, ch, outW, outH, bg), "png", new File(out));
    }

    static void write(String path, String content) throws IOException {
        File f = new File(path);
        f.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) { w.write(content); }
    }

    // ============================================================ palette ===
    // Primary  Kiln Red   - the block (energy, the thing that moves)
    // Accent   Deep Teal  - the bound / wall (constraint, structure)
    // Ink      Warm black - monochrome
    // Paper    Bone       - light ground
    static final String RED  = "#D6462B";
    static final String TEAL = "#0F5A63";
    static final String INK  = "#191817";
    static final String PAPER= "#F4EFE6";
    static final String BONE = "#EFE8DA";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "concepts";
        String out = args.length > 1 ? args[1] : ".";
        new File(out).mkdirs();
        switch (mode) {
            case "concepts": concepts(out); break;
            case "sheet": contactSheet(out); break;
            case "round2": round2(out); break;
            case "round3": round3(out); break;
            case "round4": round4(out); break;
            case "round5": round5(out); break;
            case "round6": round6(out); break;
            case "wordmark": renderWordmark(out); break;
            case "glyphs": renderGlyphs(out); break;
            case "solo": renderSolo(out); break;
            case "lockups": renderLockups(out); break;
            case "symtest": renderSymbolTests(out); break;
            case "proof": renderProof(out); break;
            case "kit": renderKit(out); break;
            case "qc": renderQc(args.length > 2 ? args[2] : "kit", out); break;
            default: throw new IllegalArgumentException(mode);
        }
    }

    // ------------------------------------------------------------ concepts --
    static final int SZ = 1000;

    /** Contact sheet: 6 candidates, large + at 48px to judge the silhouette. */
    static void concepts(String out) throws Exception {
        String[][] sheets = { { "c1", "c2" }, { "c3", "c4" }, { "c5", "c6" } };
        for (String[] row : sheets) {
            for (String id : row) {
                List<Prim> p = concept(id);
                render(p, SZ, SZ, 520, 520, out + "/concept-" + id + ".png", PAPER);
                render(p, SZ, SZ, 48, 48, out + "/concept-" + id + "-48.png", PAPER);
            }
        }
    }

    static List<Prim> concept(String id) {
        switch (id) {
            case "c1": return c1();
            case "c2": return c2();
            case "c3": return c3();
            case "c4": return c4();
            case "c5": return c5();
            case "c6": return c6();
            default: throw new IllegalArgumentException(id);
        }
    }

    // C1 "Corner Bound": L-bracket wall + block pushed into the crook.
    static List<Prim> c1() {
        List<Prim> p = new ArrayList<>();
        p.add(rect(TEAL, 150, 150, 130, 700));
        p.add(rect(TEAL, 150, 720, 700, 130));
        p.add(rect(RED, 430, 390, 300, 300));
        return p;
    }

    // C2 "Push Out": broken square bound, block leaving through the opening.
    static List<Prim> c2() {
        List<Prim> p = new ArrayList<>();
        int o = 130, st = 120, gapTop = 350, gapBot = 550;
        p.add(rect(TEAL, o, o, SZ - 2 * o, st));                       // top
        p.add(rect(TEAL, o, SZ - o - st, SZ - 2 * o, st));             // bottom
        p.add(rect(TEAL, o, o, st, SZ - 2 * o));                       // left
        p.add(rect(TEAL, SZ - o - st, o, st, gapTop - o));             // right upper
        p.add(rect(TEAL, SZ - o - st, gapBot, st, SZ - o - gapBot));   // right lower
        p.add(rect(RED, 560, gapTop, 200, gapBot - gapTop));           // block in the doorway
        return p;
    }

    // C3 "Chevron Block": block that IS the arrow, wall it pushed from behind.
    static List<Prim> c3() {
        List<Prim> p = new ArrayList<>();
        p.add(rect(TEAL, 90, 250, 110, 400));
        p.add(poly(RED, 300, 250, 620, 250, 830, 450, 620, 650, 300, 650));
        return p;
    }

    // C4 "Constriction": two walls, block held in the channel between them.
    static List<Prim> c4() {
        List<Prim> p = new ArrayList<>();
        p.add(rect(TEAL, 150, 150, 140, 700));
        p.add(rect(TEAL, 710, 150, 140, 700));
        p.add(rect(RED, 370, 370, 260, 260));
        return p;
    }

    // C5 "Block Monogram": squared P - stem = wall, bowl = block.
    static List<Prim> c5() {
        List<Prim> p = new ArrayList<>();
        p.add(rect(TEAL, 180, 130, 140, 740));
        p.add(rect(RED, 180, 130, 620, 430));
        p.add(rect(PAPER, 320, 270, 340, 150));
        return p;
    }

    // C6 "Stair Push": blocks ascending into an outlined goal square.
    static List<Prim> c6() {
        List<Prim> p = new ArrayList<>();
        p.add(rect(TEAL, 130, 620, 190, 190));
        p.add(rect(TEAL, 345, 435, 190, 190));
        p.add(rect(RED, 560, 250, 190, 190));
        // goal = a square outline straddling the top step
        p.add(rect(PAPER, 700, 110, 170, 170));
        p.add(rect(TEAL, 700, 110, 170, 34));
        p.add(rect(TEAL, 700, 246, 170, 34));
        p.add(rect(TEAL, 700, 110, 34, 170));
        p.add(rect(TEAL, 836, 110, 34, 170));
        return p;
    }

    /** 3x2 grid of the six round-1 candidates: full size plus a silhouette test. */
    static void contactSheet(String out) throws Exception {
        sheet(out, "contact-sheet", new String[]{ "c1", "c2", "c3", "c4", "c5", "c6" }, true);
    }

    // ------------------------------------------------------------- round 2 --
    /** Square bound with an opening cut into the right edge (the doorway). */
    static void boundDoorway(List<Prim> p, String c, int x0, int y0, int size, int t, int d0, int d1) {
        int x1 = x0 + size, y1 = y0 + size;
        p.add(rect(c, x0, y0, size, t));            // top
        p.add(rect(c, x0, y1 - t, size, t));        // bottom
        p.add(rect(c, x0, y0, t, size));            // left
        p.add(rect(c, x1 - t, y0, t, d0 - y0));     // right, above the doorway
        p.add(rect(c, x1 - t, d1, t, y1 - d1));     // right, below the doorway
    }

    static List<Prim> variant(String id) {
        switch (id) {
            case "r1": {   // Threshold: block pushed flush out through the doorway
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 130, 360, 660);
                p.add(rect(RED, 590, 400, 260, 220));
                return p;
            }
            case "r2": {   // Progression: one block still inside, one already out
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 130, 350, 670);
                p.add(rect(RED, 400, 400, 220, 220));
                p.add(rect(RED, 700, 400, 220, 220));
                return p;
            }
            case "r3": {   // Corner: block nested in the crook of the bound
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 140, 700));
                p.add(rect(TEAL, 150, 710, 700, 140));
                p.add(rect(RED, 400, 340, 260, 260));
                return p;
            }
            case "r4": {   // Rise: block lifting out of an open-topped bound
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 300, 140, 550));
                p.add(rect(TEAL, 710, 300, 140, 550));
                p.add(rect(TEAL, 150, 710, 700, 140));
                p.add(rect(RED, 390, 140, 220, 260));
                return p;
            }
            case "r5": {   // Contained: closed bound, block leaning on the far wall
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 700, 130));
                p.add(rect(TEAL, 150, 720, 700, 130));
                p.add(rect(TEAL, 150, 150, 130, 700));
                p.add(rect(TEAL, 720, 150, 130, 700));
                p.add(rect(RED, 420, 390, 260, 260));
                return p;
            }
            case "r6": {   // Block monogram: stem = wall, bowl = block
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 200, 130, 130, 740));
                p.add(rect(RED, 200, 130, 590, 440));
                p.add(rect(PAPER, 330, 250, 330, 200));
                return p;
            }
            default: throw new IllegalArgumentException(id);
        }
    }

    static void round2(String out) throws Exception {
        sheet(out, "round2-sheet", new String[]{ "r1", "r2", "r3", "r4", "r5", "r6" }, false);
    }

    // ------------------------------------------------------------- round 3 --
    static List<Prim> fine(String id) {
        switch (id) {
            case "t1": {   // Exact fit: block fills the doorway, flush with the outer face
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 130, 390, 610);
                p.add(rect(RED, 630, 390, 220, 220));
                return p;
            }
            case "t2": {   // Escape: block overhangs past the outer face
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 130, 350, 650);
                p.add(rect(RED, 620, 390, 280, 220));
                return p;
            }
            case "t3": {   // Corner: block nested in the crook of two walls
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 150, 700));
                p.add(rect(TEAL, 150, 700, 700, 150));
                p.add(rect(RED, 400, 330, 290, 290));
                return p;
            }
            case "t4": {   // Chamfer: block with a bevelled leading edge, mid-exit
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 130, 350, 650);
                p.add(poly(RED, 620, 390, 820, 390, 880, 450, 880, 550, 820, 610, 620, 610));
                return p;
            }
            case "t5": {   // Contained (round-2 control)
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 700, 130));
                p.add(rect(TEAL, 150, 720, 700, 130));
                p.add(rect(TEAL, 150, 150, 130, 700));
                p.add(rect(TEAL, 720, 150, 130, 700));
                p.add(rect(RED, 420, 390, 260, 260));
                return p;
            }
            case "t6": {   // Threshold (round-2 control)
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 130, 360, 660);
                p.add(rect(RED, 590, 400, 260, 220));
                return p;
            }
            default: throw new IllegalArgumentException(id);
        }
    }

    static void round3(String out) throws Exception {
        sheet(out, "round3-sheet", new String[]{ "t1", "t2", "t3", "t4", "t5", "t6" }, false, true);
    }

    // ------------------------------------------------------------- round 4 --
    // Proportion tuning of the winning Threshold direction, checked in colour
    // AND in flat monochrome (the real test: if the block merges into the wall
    // when everything is one colour, the gaps are too tight for 16px).
    static List<Prim> tune(String id) {
        int M = 140, S = 720;                       // bound outer box: 140..860
        switch (id) {
            case "u1": return tune1(M, S, 120, 320, 680, 640, 400, 220);
            case "u2": return tune1(M, S, 120, 320, 680, 680, 400, 220);
            case "u3": return tune1(M, S, 120, 300, 700, 660, 380, 240);
            case "u4": return tune1(M, S, 150, 330, 670, 660, 400, 200);
            case "u5": return tune1(M, S, 120, 340, 660, 700, 400, 200);
            case "u6": return tune1(M, S, 120, 360, 640, 660, 400, 200);
            default: throw new IllegalArgumentException(id);
        }
    }

    /** Square bound with a doorway in the right edge + a square block in it. */
    static List<Prim> tune1(int M, int S, int T, int d0, int d1, int bx, int by, int bs) {
        List<Prim> p = new ArrayList<>();
        boundDoorway(p, TEAL, M, M, S, T, d0, d1);
        p.add(rect(RED, bx, by, bs, bs));
        return p;
    }

    static void round4(String out) throws Exception {
        String[] ids = { "u1", "u2", "u3", "u4", "u5", "u6" };
        sheet(out, "round4-sheet", ids, false, false, false);
        sheet(out, "round4-mono", ids, false, false, true);
    }

    // ------------------------------------------------------------- round 5 --
    // Round 4's lesson: a mouth taller than ~40% of the bound reads as the
    // letter C. Here the structures differ, not just the numbers.
    static List<Prim> fifth(String id) {
        switch (id) {
            case "v1": {   // Tight mouth, block protruding (the push, square intact)
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 110, 360, 650);
                p.add(rect(RED, 690, 405, 200, 200));
                return p;
            }
            case "v2": {   // Fully closed bound, block leaning on the far wall
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 700, 110));
                p.add(rect(TEAL, 150, 740, 700, 110));
                p.add(rect(TEAL, 150, 150, 110, 700));
                p.add(rect(TEAL, 740, 150, 110, 700));
                p.add(rect(RED, 460, 380, 240, 240));
                return p;
            }
            case "v3": {   // Pocket: outer square unbroken, block seated in a recess
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 700, 110));
                p.add(rect(TEAL, 150, 740, 700, 110));
                p.add(rect(TEAL, 150, 150, 110, 700));
                p.add(rect(TEAL, 740, 150, 110, 230));
                p.add(rect(TEAL, 740, 620, 110, 230));
                p.add(rect(TEAL, 800, 380, 50, 240));
                p.add(rect(RED, 620, 425, 150, 150));
                return p;
            }
            case "v4": {   // Slot + strong overhang (most literal push)
                List<Prim> p = new ArrayList<>();
                boundDoorway(p, TEAL, 150, 150, 700, 110, 350, 660);
                p.add(rect(RED, 700, 400, 210, 210));
                return p;
            }
            case "v5": {   // Bound left behind, block already clear of it
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 130, 130, 600, 100));
                p.add(rect(TEAL, 130, 630, 600, 100));
                p.add(rect(TEAL, 130, 130, 100, 600));
                p.add(rect(TEAL, 630, 130, 100, 600));
                p.add(rect(RED, 780, 410, 180, 180));
                return p;
            }
            case "v6": {   // Corner bound, block nested with an even keyline gap
                List<Prim> p = new ArrayList<>();
                p.add(rect(TEAL, 150, 150, 110, 700));
                p.add(rect(TEAL, 150, 740, 700, 110));
                p.add(rect(RED, 370, 410, 260, 260));
                return p;
            }
            default: throw new IllegalArgumentException(id);
        }
    }

    static void round5(String out) throws Exception {
        String[] ids = { "v1", "v2", "v3", "v4", "v5", "v6" };
        for (int i = 0; i < ids.length; i++) {
            render(resolve(ids[i]), SZ, SZ, 300, 300, out + "/r5-" + ids[i] + ".png", PAPER);
        }
        sheet(out, "round5-sheet", ids, false, false, false);
        sheet(out, "round5-mono", ids, false, false, true);
    }

    // ------------------------------------------------------------- round 6 --
    /** Closed square bound: four walls, no opening (silhouette-proof). */
    static void frame(List<Prim> p, String c, int x0, int y0, int size, int t) {
        p.add(rect(c, x0, y0, size, t));
        p.add(rect(c, x0, y0 + size - t, size, t));
        p.add(rect(c, x0, y0, t, size));
        p.add(rect(c, x0 + size - t, y0, t, size));
    }

    /**
     * Bound with a recess bitten out of the INNER face of the right wall.
     * The outer edge stays continuous, so the silhouette never becomes a C —
     * it degrades to a plain frame at tiny sizes instead of falling apart.
     */
    static void recessed(List<Prim> p, String c, int x0, int y0, int size, int t, int depth, int r0, int r1) {
        int x1 = x0 + size, y1 = y0 + size;
        int xi = x1 - t;                          // inner face of the right wall
        int xb = xi + depth;                      // back of the recess
        p.add(rect(c, x0, y0, size, t));
        p.add(rect(c, x0, y1 - t, size, t));
        p.add(rect(c, x0, y0, t, size));
        p.add(rect(c, xi, y0, t, r0 - y0));       // right wall, above the recess
        p.add(rect(c, xi, r1, t, y1 - r1));       // right wall, below the recess
        p.add(rect(c, xb, r0, x1 - xb, r1 - r0)); // sliver of wall left behind it
    }

    static List<Prim> last(String id) {
        switch (id) {
            case "w1": {   // Closed bound, block pushed to the right
                List<Prim> p = new ArrayList<>();
                frame(p, TEAL, 150, 150, 700, 115);
                p.add(rect(RED, 430, 375, 250, 250));
                return p;
            }
            case "w2": {   // Closed bound, block driven into the far corner
                List<Prim> p = new ArrayList<>();
                frame(p, TEAL, 150, 150, 700, 115);
                p.add(rect(RED, 430, 430, 250, 250));
                return p;
            }
            case "w3": {   // Recess, block seated against the back of it
                List<Prim> p = new ArrayList<>();
                recessed(p, TEAL, 150, 150, 700, 115, 70, 340, 660);
                p.add(rect(RED, 520, 380, 240, 240));
                return p;
            }
            case "w4": {   // Deeper recess, heavier block
                List<Prim> p = new ArrayList<>();
                recessed(p, TEAL, 150, 150, 700, 115, 85, 320, 680);
                p.add(rect(RED, 500, 370, 260, 260));
                return p;
            }
            case "w5": {   // Control: closed bound, block dead centre
                List<Prim> p = new ArrayList<>();
                frame(p, TEAL, 150, 150, 700, 115);
                p.add(rect(RED, 405, 405, 250, 250));
                return p;
            }
            case "w6": {   // Recess + block driven into its lower corner
                List<Prim> p = new ArrayList<>();
                recessed(p, TEAL, 150, 150, 700, 115, 75, 330, 670);
                p.add(rect(RED, 505, 425, 255, 255));
                return p;
            }
            default: throw new IllegalArgumentException(id);
        }
    }

    static void round6(String out) throws Exception {
        String[] ids = { "w1", "w2", "w3", "w4", "w5", "w6" };
        sheet(out, "round6-sheet", ids, false, false, false);
        sheet(out, "round6-mono", ids, false, false, true);
    }

    static List<Prim> resolve(String id) {
        if (id.startsWith("c")) return concept(id);
        if (id.startsWith("r")) return variant(id);
        if (id.startsWith("t")) return fine(id);
        if (id.startsWith("u")) return tune(id);
        if (id.startsWith("v")) return fifth(id);
        if (id.startsWith("w")) return last(id);
        throw new IllegalArgumentException(id);
    }

    static List<Prim> recolor(List<Prim> prims, String color) {
        List<Prim> q = new ArrayList<>();
        for (Prim pr : prims) {
            Prim n = new Prim(pr.type, color, pr.p.clone());
            n.d = pr.d; n.gp = pr.gp;
            q.add(n);
        }
        return q;
    }

    static void sheet(String out, String file, String[] ids, boolean useConcept) throws Exception {
        sheet(out, file, ids, useConcept, false, false);
    }

    static void sheet(String out, String file, String[] ids, boolean useConcept, boolean useFine) throws Exception {
        sheet(out, file, ids, useConcept, useFine, false);
    }

    static void sheet(String out, String file, String[] ids, boolean useConcept, boolean useFine, boolean mono) throws Exception {
        int tile = 380, gap = 26, cols = 3, rows = 2, smallRow = 78;
        int cw = cols * tile + (cols + 1) * gap;
        int ch = rows * (tile + smallRow) + (rows + 1) * gap;
        BufferedImage sh = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sh.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(Integer.parseInt(BONE.substring(1), 16)));
        g.fillRect(0, 0, cw, ch);
        for (int i = 0; i < ids.length; i++) {
            List<Prim> p = resolve(ids[i]);
            if (mono) p = recolor(p, INK);
            int cx = gap + (i % cols) * (tile + gap);
            int cy = gap + (i / cols) * (tile + smallRow + gap);
            g.setColor(Color.WHITE);
            g.fillRoundRect(cx, cy, tile, tile, 18, 18);
            g.drawImage(renderImage(p, SZ, SZ, tile - 40, tile - 40, null), cx + 20, cy + 20, null);
            g.drawImage(renderImage(p, SZ, SZ, 48, 48, null), cx + tile / 2 - 24, cy + tile + 8, null);
            g.drawImage(renderImage(p, SZ, SZ, 24, 24, null), cx + tile / 2 + 40, cy + tile + 20, null);
            g.drawImage(renderImage(p, SZ, SZ, 16, 16, null), cx + tile / 2 + 76, cy + tile + 24, null);
        }
        g.dispose();
        ImageIO.write(sh, "png", new File(out + "/" + file + ".png"));
    }

    // =========================================================== wordmark ===
    // A geometric sans drawn from primitives: every round form is a true
    // circle or a true half-circle on one shared grid, so the alphabet reads
    // as proprietary construction rather than typed text. No font is loaded.
    //
    //   stem 16   x-height 76   cap/ascender 105   bowl R 38   counter R 22
    //
    static final double W_STEM = 16, W_XH = 76, W_CAP = 105, W_BOWL = 38, W_CNT = 22, W_GAP = 10;

    static double glyphWidth(char ch) {
        if (ch == 'P') return 50;
        if (ch == 's') return 62;
        return W_XH;                       // u, h, b, o, n, d
    }

    static double wordmarkWidth(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) w += W_GAP;
            w += glyphWidth(s.charAt(i));
        }
        return w;
    }

    /** Draws one glyph with its ink starting at x, standing on baseline b. */
    static void glyph(List<Prim> out, char ch, double x, double b, double S, String c) {
        double stem = W_STEM * S;
        switch (ch) {
            case 'P': {
                out.add(rect(c, x, b - W_CAP * S, stem, W_CAP * S));
                // bowl: right half of an annulus, centre y-up 71, R 34/18
                out.add(arcRing(c, x + 16 * S, b - 71 * S, 34 * S, 18 * S, -90, 90));
                break;
            }
            // NB: Arc2D normalises its start angle, so a negative start silently
            // reverses which half you get. These pairs were verified empirically:
            //   BOTTOM = (-180, 0)   TOP = (180, 0)   RIGHT = (-90, 90)
            case 'u': {
                out.add(rect(c, x, b - W_XH * S, stem, (W_XH - 38) * S));
                out.add(rect(c, x + 60 * S, b - W_XH * S, stem, (W_XH - 38) * S));
                out.add(arcRing(c, x + 38 * S, b - 38 * S, W_BOWL * S, W_CNT * S, -180, 0));
                break;
            }
            case 'n': {
                out.add(rect(c, x, b - 38 * S, stem, 38 * S));
                out.add(rect(c, x + 60 * S, b - 38 * S, stem, 38 * S));
                out.add(arcRing(c, x + 38 * S, b - 38 * S, W_BOWL * S, W_CNT * S, 180, 0));
                break;
            }
            case 'h': {
                out.add(rect(c, x, b - W_CAP * S, stem, W_CAP * S));
                out.add(rect(c, x + 60 * S, b - 38 * S, stem, 38 * S));
                out.add(arcRing(c, x + 38 * S, b - 38 * S, W_BOWL * S, W_CNT * S, 180, 0));
                break;
            }
            case 'b': {
                out.add(rect(c, x, b - W_CAP * S, stem, W_CAP * S));
                out.add(ring(c, x + 38 * S, b - 38 * S, W_BOWL * S, W_CNT * S));
                break;
            }
            case 'd': {
                out.add(rect(c, x + 60 * S, b - W_CAP * S, stem, W_CAP * S));
                out.add(ring(c, x + 38 * S, b - 38 * S, W_BOWL * S, W_CNT * S));
                break;
            }
            case 'o': {
                out.add(ring(c, x + 38 * S, b - 38 * S, W_BOWL * S, W_CNT * S));
                break;
            }
            case 's': {
                // single stroked centreline; ink box 0..62 x 0..76 after offset
                double[][] pts = {
                    { 53, 57 },
                    { 53, 65 }, { 43, 68 }, { 31, 68 },
                    { 17, 68 }, {  8, 62 }, {  8, 53 },
                    {  8, 45 }, { 16, 41 }, { 31, 38 },
                    { 45, 35 }, { 54, 31 }, { 54, 23 },
                    { 54, 14 }, { 44,  8 }, { 31,  8 },
                    { 19,  8 }, {  9, 12 }, {  9, 18 }
                };
                Path2D.Double p = new Path2D.Double();
                StringBuilder d = new StringBuilder();
                double sx = x + pts[0][0] * S, sy = b - pts[0][1] * S;
                p.moveTo(sx, sy);
                d.append("M").append(f(sx)).append(" ").append(f(sy));
                for (int i = 1; i + 2 <= pts.length - 1; i += 3) {
                    double c1x = x + pts[i][0] * S, c1y = b - pts[i][1] * S;
                    double c2x = x + pts[i + 1][0] * S, c2y = b - pts[i + 1][1] * S;
                    double ex = x + pts[i + 2][0] * S, ey = b - pts[i + 2][1] * S;
                    p.curveTo(c1x, c1y, c2x, c2y, ex, ey);
                    d.append("C").append(f(c1x)).append(" ").append(f(c1y))
                     .append(" ").append(f(c2x)).append(" ").append(f(c2y))
                     .append(" ").append(f(ex)).append(" ").append(f(ey));
                }
                out.add(stroke(c, W_STEM * S, p, d.toString()));
                break;
            }
            default:
                throw new IllegalArgumentException("glyph " + ch);
        }
    }

    /** Full wordmark laid out left-to-right, standing on baseline b. */
    static List<Prim> wordmark(String c, double x, double b, double S) {
        return wordmark(c, "Pushbound", x, b, S);
    }

    static List<Prim> wordmark(String c, String text, double x, double b, double S) {
        List<Prim> out = new ArrayList<>();
        double cur = x;
        for (int i = 0; i < text.length(); i++) {
            glyph(out, text.charAt(i), cur, b, S, c);
            cur += glyphWidth(text.charAt(i)) * S;
            if (i < text.length() - 1) cur += W_GAP * S;
        }
        return out;
    }

    static void renderWordmark(String out) throws Exception {
        double wm = wordmarkWidth("Pushbound");
        for (double S : new double[]{ 5, 2, 1 }) {
            int w = (int) Math.ceil((wm + 40) * S);
            int h = (int) Math.ceil((W_CAP + 40) * S);
            double b = (W_CAP + 20) * S;              // baseline: cap + margin
            List<Prim> p = wordmark(INK, 20 * S, b, S);
            String tag = S >= 5 ? "" : S >= 2 ? "-sm" : "-tiny";
            render(p, w, h, w, h, out + "/wordmark" + tag + ".png", PAPER);
        }
    }

    /** One glyph per row, so each letter can be judged on its own. */
    static void renderGlyphs(String out) throws Exception {
        String letters = "Pushbond";
        double S = 3;
        double box = W_CAP + 24;                       // square world box: no clipping
        int cell = (int) (box * S), gapx = 24;
        int cols = 4, rows = (letters.length() + cols - 1) / cols;
        int cw = cols * cell + (cols + 1) * gapx;
        int ch = rows * cell + (rows + 1) * gapx;
        BufferedImage sh = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sh.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(Integer.parseInt(PAPER.substring(1), 16)));
        g.fillRect(0, 0, cw, ch);
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            List<Prim> p = new ArrayList<>();
            glyph(p, c, (box - glyphWidth(c)) / 2 * S, (W_CAP + 12) * S, S, INK);
            BufferedImage im = renderImage(p, box * S, box * S, cell, cell, null);
            int cx = gapx + (i % cols) * (cell + gapx);
            int cy = gapx + (i / cols) * (cell + gapx);
            g.setColor(Color.WHITE);
            g.fillRoundRect(cx, cy, cell, cell, 14, 14);
            g.drawImage(im, cx, cy, null);
        }
        g.dispose();
        ImageIO.write(sh, "png", new File(out + "/glyphs.png"));
    }

    /** Single letters at large size, for judging stroke joins and terminals. */
    static void renderSolo(String out) throws Exception {
        double S = 6;
        double box = W_CAP + 24;
        int px = (int) (box * S);
        for (char c : new char[]{ 's', 'P', 'u', 'h' }) {
            List<Prim> p = new ArrayList<>();
            glyph(p, c, (box - glyphWidth(c)) / 2 * S, (W_CAP + 12) * S, S, INK);
            render(p, box * S, box * S, px, px, out + "/solo-" + c + ".png", PAPER);
        }
    }

    // ========================================================= logo system ===
    //
    // FINAL SYMBOL — "the bound yields to the block".
    //   A closed square bound (so the silhouette is always a square, never a C),
    //   with a recess bitten out of the INNER face of its right wall and the
    //   block seated in that recess behind an even 45-unit keyline. Reads as a
    //   block pressing against the limit; degrades gracefully at tiny sizes.
    //
    //   box 1000 · mark 150..850 (700) · wall 130 · recess 315..685 @ depth 70
    //   block 280 at (465,360) → even 45-unit keyline on the three recessed
    //   sides, 80 above/below inside the bound, 35 between the recess and the
    //   top/bottom walls (so the wall keeps its corners), 60-unit sliver left
    //   standing at x790..850 so the outer silhouette never breaks.
    //
    //   The block sits 105 units right of the interior centre: the empty left
    //   is the distance it has already travelled, which is what gives the mark
    //   its direction instead of a centred, static square.
    //
    static final double BOX = 1000, MARK = 150, MARKSZ = 700;
    static final String WM = "Pushbound";

    static List<Prim> symbol(String bound, String block) {
        List<Prim> p = new ArrayList<>();
        recessed(p, bound, 150, 150, 700, 130, 70, 315, 685);
        p.add(rect(block, 465, 360, 280, 280));
        return p;
    }

    /** Uniform scale + translate. Angles are scale-invariant, so arcs survive. */
    static List<Prim> xform(List<Prim> prims, double k, double tx, double ty) {
        List<Prim> out = new ArrayList<>();
        for (Prim pr : prims) {
            switch (pr.type) {
                case RECT:
                    out.add(new Prim(RECT, pr.color, tx + pr.p[0] * k, ty + pr.p[1] * k,
                            pr.p[2] * k, pr.p[3] * k));
                    break;
                case RING:
                    out.add(new Prim(RING, pr.color, tx + pr.p[0] * k, ty + pr.p[1] * k,
                            pr.p[2] * k, pr.p[3] * k));
                    break;
                case ARCRING:
                    out.add(new Prim(ARCRING, pr.color, tx + pr.p[0] * k, ty + pr.p[1] * k,
                            pr.p[2] * k, pr.p[3] * k, pr.p[4], pr.p[5]));
                    break;
                case POLY: {
                    double[] q = new double[pr.p.length];
                    for (int i = 0; i < q.length; i += 2) {
                        q[i] = tx + pr.p[i] * k;
                        q[i + 1] = ty + pr.p[i + 1] * k;
                    }
                    out.add(new Prim(POLY, pr.color, q));
                    break;
                }
                default:
                    // stroked paths carry a pre-formatted SVG d; regenerate them
                    // at the target scale via wordmark() instead of transforming.
                    throw new UnsupportedOperationException("xform cannot carry STROKE");
            }
        }
        return out;
    }

    /** Symbol with its VISIBLE mark (700 units) placed at (sx,sy) sized `size`. */
    static List<Prim> symbolAt(String bound, String block, double sx, double sy, double size) {
        double k = size / MARKSZ;
        return xform(symbol(bound, block), k, sx - MARK * k, sy - MARK * k);
    }

    static class Comp {
        List<Prim> prims = new ArrayList<>();
        double w, h;
    }

    static Comp mono(Comp c, String color) {
        Comp m = new Comp();
        m.w = c.w;
        m.h = c.h;
        m.prims = recolor(c.prims, color);
        return m;
    }

    /** Symbol left, wordmark right; wordmark cap block optically centred. */
    static Comp horizontal(String bound, String block, String word, double C) {
        Comp c = new Comp();
        double pad = 0.5 * C, symH = 2.0 * C, gap = 0.9 * C;
        double ws = C / W_CAP, wmW = wordmarkWidth(WM) * ws;
        c.w = pad + symH + gap + wmW + pad;
        c.h = pad + symH + pad;
        c.prims.addAll(symbolAt(bound, block, pad, pad, symH));
        c.prims.addAll(wordmark(word, pad + symH + gap, pad + 1.5 * C, ws));
        return c;
    }

    /** Symbol above, wordmark below, both centred on the same axis. */
    static Comp stacked(String bound, String block, String word, double C) {
        Comp c = new Comp();
        double pad = 0.5 * C, symS = 3.1 * C, gap = 0.65 * C;
        double ws = C / W_CAP, wmW = wordmarkWidth(WM) * ws;
        c.w = pad + wmW + pad;
        c.h = pad + symS + gap + C + pad;
        c.prims.addAll(symbolAt(bound, block, (c.w - symS) / 2, pad, symS));
        c.prims.addAll(wordmark(word, pad, pad + symS + gap + C, ws));
        return c;
    }

    /** Standalone mark in its 1000-unit box (the app-icon artwork). */
    static Comp symbolOnly(String bound, String block) {
        Comp c = new Comp();
        c.prims.addAll(symbol(bound, block));
        c.w = BOX;
        c.h = BOX;
        return c;
    }

    static void emit(String base, Comp c, int px, String bg) throws Exception {
        int outW = px, outH = (int) Math.max(1, Math.round((double) px * c.h / c.w));
        render(c.prims, c.w, c.h, outW, outH, base + ".png", bg);
        write(base + ".svg", svg(c.prims, c.w, c.h));
    }

    /** Review pass: every required composition, colour and monochrome. */
    static void renderLockups(String out) throws Exception {
        double C = 300;
        Comp hz = horizontal(TEAL, RED, INK, C);
        Comp st = stacked(TEAL, RED, INK, C);
        Comp sy = symbolOnly(TEAL, RED);
        emit(out + "/logo-horizontal", hz, 2400, null);
        emit(out + "/logo-stacked", st, 1400, null);
        emit(out + "/logo-symbol", sy, 1024, null);
        emit(out + "/logo-horizontal-mono", mono(hz, INK), 2400, null);
        emit(out + "/logo-symbol-mono", mono(sy, INK), 1024, null);
        emit(out + "/logo-horizontal-rev", mono(hz, PAPER), 2400, INK);
        emit(out + "/logo-symbol-rev", mono(sy, PAPER), 1024, INK);
    }

    /**
     * Three readings of the same idea, judged at logo size AND at app-icon
     * sizes (96/48/16), because a mark that only works big is not finished.
     */
    static void renderSymbolTests(String out) throws Exception {
        // A: recess 330..670, block 250 (the lockup draft)
        List<Prim> A = new ArrayList<>();
        recessed(A, TEAL, 150, 150, 700, 130, 70, 330, 670);
        A.add(rect(RED, 495, 375, 250, 250));

        // B: deeper recess, block 300 — block dominant, less dead space
        List<Prim> B = new ArrayList<>();
        recessed(B, TEAL, 150, 150, 700, 130, 70, 305, 695);
        B.add(rect(RED, 445, 350, 300, 300));

        // C: no recess — closed bound, block leaning on the far wall
        List<Prim> C = new ArrayList<>();
        frame(C, TEAL, 150, 150, 700, 130);
        C.add(rect(RED, 375, 350, 300, 300));

        String[] ids = { "A", "B", "C" };
        @SuppressWarnings("unchecked")
        List<Prim>[] all = new List[]{ A, B, C };

        int big = 760, small = 96, tiny = 48, dot = 16, gap = 28;
        int colW = big + gap;
        int h = gap + big + gap + small + gap + tiny + dot + gap;
        BufferedImage sh = new BufferedImage(colW * 3 + gap, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sh.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(Integer.parseInt(BONE.substring(1), 16)));
        g.fillRect(0, 0, sh.getWidth(), sh.getHeight());
        for (int i = 0; i < 3; i++) {
            int x = gap + i * colW;
            g.setColor(Color.WHITE);
            g.fillRoundRect(x, gap, big, big, 18, 18);
            g.drawImage(renderImage(all[i], BOX, BOX, big, big, null), x, gap, null);
            // colour row, then monochrome row — mono is the real legibility test
            g.drawImage(renderImage(all[i], BOX, BOX, small, small, null), x, gap + big + gap, null);
            g.drawImage(renderImage(recolor(all[i], INK), BOX, BOX, small, small, null),
                    x + small + gap, gap + big + gap, null);
            g.drawImage(renderImage(all[i], BOX, BOX, tiny, tiny, null),
                    x, gap + big + gap + small + gap, null);
            g.drawImage(renderImage(recolor(all[i], INK), BOX, BOX, dot, dot, null),
                    x + tiny + gap, gap + big + gap + small + gap + tiny / 2, null);
            g.drawImage(renderImage(all[i], BOX, BOX, dot, dot, null),
                    x + tiny + gap + dot + gap, gap + big + gap + small + gap + tiny / 2, null);
            // NB: no drawString — the device has zero fonts installed; the
            // columns are read left-to-right as A, B, C.
        }
        g.dispose();
        ImageIO.write(sh, "png", new File(out + "/symtest.png"));
    }

    /**
     * The only honest tiny-size test: render at the real pixel size, then blow
     * it up NEAREST-NEIGHBOUR so the actual pixels are visible rather than a
     * smoothed preview that hides every failure. Colour row, mono row.
     */
    static void renderProof(String out) throws Exception {
        int[] sizes = { 16, 24, 32, 48, 64, 96 };
        int target = 168, gap = 20;
        List<Prim> sym = symbol(TEAL, RED);
        List<Prim> mono = recolor(sym, INK);
        int w = gap + sizes.length * (target + gap);
        int h = gap * 3 + target * 2;
        BufferedImage sh = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sh.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(Integer.parseInt(BONE.substring(1), 16)));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int x = gap;
        for (int s : sizes) {
            g.drawImage(renderImage(sym, BOX, BOX, s, s, null), x, gap, target, target, null);
            g.drawImage(renderImage(mono, BOX, BOX, s, s, null), x, gap * 2 + target, target, target, null);
            x += target + gap;
        }
        g.dispose();
        ImageIO.write(sh, "png", new File(out + "/proof.png"));
    }

    // ------------------------------------------------------ deliverables ---

    /** Reverse-ground helper: a background rect behind the artwork. */
    static Comp onBackground(Comp c, String bg) {
        Comp m = new Comp();
        m.w = c.w;
        m.h = c.h;
        m.prims.add(rect(bg, 0, 0, c.w, c.h));
        m.prims.addAll(c.prims);
        return m;
    }

    /** Same artwork, re-emitted at another pixel width (SVG stays canonical). */
    static void emitPng(String base, Comp c, int px, String bg) throws Exception {
        int outH = (int) Math.max(1, Math.round((double) px * c.h / c.w));
        render(c.prims, c.w, c.h, px, outH, base + ".png", bg);
    }

    /**
     * Horizontal lockup centred on an arbitrary canvas (0 = tight bounds).
     * Rebuilt in place rather than transformed, because the stroked `s` carries
     * a pre-formatted SVG path that must be regenerated at the target scale.
     */
    static Comp horizontalAt(String bound, String block, String word, double C, double vw, double vh) {
        Comp c = new Comp();
        double pad = 0.5 * C, symH = 2.0 * C, gap = 0.9 * C;
        double ws = C / W_CAP, wmW = wordmarkWidth(WM) * ws;
        double lw = pad + symH + gap + wmW + pad, lh = pad + symH + pad;
        if (vw <= 0) { vw = lw; vh = lh; }
        double ox = (vw - lw) / 2, oy = (vh - lh) / 2;
        c.w = vw;
        c.h = vh;
        c.prims.addAll(symbolAt(bound, block, ox + pad, oy + pad, symH));
        c.prims.addAll(wordmark(word, ox + pad + symH + gap, oy + pad + 1.5 * C, ws));
        return c;
    }

    /** Stacked lockup centred on an arbitrary canvas (0 = tight bounds). */
    static Comp stackedAt(String bound, String block, String word, double C, double vw, double vh) {
        Comp c = new Comp();
        double pad = 0.5 * C, symS = 3.1 * C, gap = 0.65 * C;
        double ws = C / W_CAP, wmW = wordmarkWidth(WM) * ws;
        double lw = wmW, lh = symS + gap + C;
        if (vw <= 0) { vw = lw + 2 * pad; vh = lh + 2 * pad; }
        double ox = (vw - lw) / 2, oy = (vh - lh) / 2;
        c.w = vw;
        c.h = vh;
        c.prims.addAll(symbolAt(bound, block, ox + (lw - symS) / 2, oy, symS));
        c.prims.addAll(wordmark(word, ox, oy + symS + gap + C, ws));
        return c;
    }

    /**
     * The whole brand kit: three lockups in colour / mono / reversed, the
     * standalone mark, the Android icon set, and the splash.
     */
    static void renderKit(String out) throws Exception {
        double C = 300;
        Comp hz = horizontal(TEAL, RED, INK, C);
        Comp st = stacked(TEAL, RED, INK, C);
        Comp sy = symbolOnly(TEAL, RED);

        // --- primary lockups, full colour, transparent ground -------------
        emit(out + "/lockup-horizontal-color", hz, 2400, null);
        emitPng(out + "/lockup-horizontal-color-1200", hz, 1200, null);
        emitPng(out + "/lockup-horizontal-color-800", hz, 800, null);
        emit(out + "/lockup-stacked-color", st, 1400, null);
        emitPng(out + "/lockup-stacked-color-800", st, 800, null);
        emit(out + "/mark-color", sy, 1024, null);
        emitPng(out + "/mark-color-512", sy, 512, null);
        emitPng(out + "/mark-color-256", sy, 256, null);
        emitPng(out + "/mark-color-128", sy, 128, null);

        // --- monochrome: one ink, the real print/single-colour test -------
        emit(out + "/lockup-horizontal-mono", mono(hz, INK), 2400, null);
        emit(out + "/lockup-stacked-mono", mono(st, INK), 1400, null);
        emit(out + "/mark-mono", mono(sy, INK), 1024, null);

        // --- reversed: paper ink on a solid ground ------------------------
        emit(out + "/lockup-horizontal-reversed", onBackground(mono(hz, PAPER), INK), 2400, null);
        emit(out + "/lockup-stacked-reversed", onBackground(mono(st, PAPER), INK), 1400, null);
        emit(out + "/mark-reversed", onBackground(mono(sy, PAPER), INK), 1024, null);

        // --- Android icon set --------------------------------------------
        // Full colour on paper is the primary; the mark sits at 70% of the
        // canvas (150..850 of 1000), inside the adaptive-icon safe zone.
        Comp iconPaper = onBackground(symbolOnly(TEAL, RED), PAPER);
        Comp iconInk = onBackground(symbolOnly(PAPER, RED), INK);
        Comp iconMono = onBackground(mono(symbolOnly(TEAL, RED), INK), PAPER);
        for (int s : new int[]{ 512, 192, 144, 96, 48 }) {
            emitPng(out + "/app-icon-" + s, iconPaper, s, null);
            emitPng(out + "/app-icon-dark-" + s, iconInk, s, null);
            emitPng(out + "/app-icon-mono-" + s, iconMono, s, null);
        }

        // --- splash ------------------------------------------------------
        Comp sp = stackedAt(TEAL, RED, INK, 124, 1080, 1920);
        render(onBackground(sp, PAPER).prims, sp.w, sp.h, 1080, 1920,
                out + "/splash-1080x1920.png", null);
        write(out + "/splash-1080x1920.svg", svg(onBackground(sp, PAPER).prims, sp.w, sp.h));
    }

    /**
     * Final visual audit of every deliverable. The bone ground is deliberate:
     * anything showing through is a genuinely transparent file, and the
     * reversed set should be solid ink. Order is left-to-right, top-to-bottom.
     */
    static void renderQc(String kit, String out) throws Exception {
        String[] files = {
            "lockup-horizontal-color-1200.png",
            "lockup-horizontal-mono.png",
            "lockup-horizontal-reversed.png",
            "lockup-stacked-color.png",
            "lockup-stacked-mono.png",
            "lockup-stacked-reversed.png",
            "mark-color.png",
            "mark-mono.png",
            "mark-reversed.png",
            "app-icon-192.png",
            "app-icon-dark-192.png",
            "app-icon-mono-192.png",
        };
        int cols = 3, cellW = 820, cellH = 320, gap = 24;
        int rows = (files.length + cols - 1) / cols;
        BufferedImage sh = new BufferedImage(gap + cols * (cellW + gap),
                gap + rows * (cellH + gap), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sh.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(Integer.parseInt(BONE.substring(1), 16)));
        g.fillRect(0, 0, sh.getWidth(), sh.getHeight());
        for (int i = 0; i < files.length; i++) {
            File f = new File(kit, files[i]);
            if (!f.exists()) continue;
            BufferedImage im = ImageIO.read(f);
            double k = Math.min((double) cellW / im.getWidth(), (double) cellH / im.getHeight());
            int w = Math.max(1, (int) (im.getWidth() * k)), h = Math.max(1, (int) (im.getHeight() * k));
            int x = gap + (i % cols) * (cellW + gap) + (cellW - w) / 2;
            int y = gap + (i / cols) * (cellH + gap) + (cellH - h) / 2;
            g.drawImage(im, x, y, w, h, null);
        }
        g.dispose();
        ImageIO.write(sh, "png", new File(out + "/qc.png"));
    }
}
