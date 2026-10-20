import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;

/** Decisive: probe at 45 deg (strictly inside a 90deg wedge), no boundary ambiguity. */
public class ArcQuad {
    public static void main(String[] a) {
        pie(0, 90, "start=0    extent=90  (first quadrant of Java2D angles)");
        pie(90, 90, "start=90   extent=90");
        pie(180, 90, "start=180  extent=90");
        pie(270, 90, "start=270  extent=90");
        pie(0, 180, "start=0    extent=180");
        pie(-180, 180, "start=-180 extent=180");
        pie(-90, 180, "start=-90  extent=180");
        pie(-180, -180, "start=-180 extent=-180");
        pie(180, 0 - 180, "start=180  extent=-180");
    }

    static void pie(double start, double extent, String label) {
        int S = 81, c = S / 2, r = 34, d = 13;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, S, S);
        g.translate(c, c);
        g.setColor(Color.BLACK);
        g.fill(new Arc2D.Double(-r, -r, 2 * r, 2 * r, start, extent, Arc2D.PIE));
        g.dispose();
        boolean NE = ink(img, c + d, c - d);
        boolean SE = ink(img, c + d, c + d);
        boolean SW = ink(img, c - d, c + d);
        boolean NW = ink(img, c - d, c - d);
        String verdict;
        if (NE && SE && !SW && !NW) verdict = "EAST half  (through 0 deg)";
        else if (SE && SW && !NE && !NW) verdict = "SOUTH half (through +90)";
        else if (SW && NW && !NE && !SE) verdict = "WEST half  (through 180)";
        else if (NW && NE && !SE && !SW) verdict = "NORTH half (through 270)";
        else if (NE && !SE && !SW && !NW) verdict = "NE quadrant only";
        else if (SE && !NE && !SW && !NW) verdict = "SE quadrant only";
        else if (SW && !NE && !SE && !NW) verdict = "SW quadrant only";
        else if (NW && !NE && !SE && !SW) verdict = "NW quadrant only";
        else verdict = "?";
        System.out.printf("%-52s NE=%-4s SE=%-4s SW=%-4s NW=%-4s -> %s%n",
                label, on(NE), on(SE), on(SW), on(NW), verdict);
    }

    static String on(boolean b) { return b ? "ink" : "-"; }

    static boolean ink(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) & 0xFF) < 128;
    }
}
