import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** For arcRing(a0,a1): which half does the OUTER arc actually cover? */
public class ArcTest {
    static double[][] pairs = {
        { -90, 90 }, { 90, -90 }, { -90, -90 }, { 90, 90 },
        { -180, 0 }, { -180, -360 }, { 0, -180 }, { 0, 180 },
        { -180, 180 }, { 0, -360 }, { -270, -450 }, { -270, -90 },
        { 180, 360 }, { 180, 0 }, { 90, -90 }, { 270, 450 },
    };

    public static void main(String[] a) {
        System.out.println("centre (0,0) r=100, y-DOWN screen.  half = which side is inked:");
        for (double[] p : pairs) {
            Path2D.Double path = new Path2D.Double();
            path.append(new Arc2D.Double(-100, -100, 200, 200, p[0], p[1] - p[0], Arc2D.OPEN), true);
            Rectangle2D b = path.getBounds2D();
            String half = classify(b);
            System.out.printf("a0=%6.0f a1=%6.0f (ext %6.0f) -> x %6.0f..%6.0f y %6.0f..%6.0f  %s%n",
                    p[0], p[1], p[1] - p[0], b.getMinX(), b.getMaxX(), b.getMinY(), b.getMaxY(), half);
        }
    }

    static String classify(Rectangle2D b) {
        double minY = b.getMinY(), maxY = b.getMaxY(), minX = b.getMinX(), maxX = b.getMaxX();
        if (maxY < 1 && minY < -90) return "TOP";
        if (minY > -1 && maxY > 90) return "BOTTOM";
        if (maxX < 1 && minX < -90) return "LEFT";
        if (minX > -1 && maxX > 90) return "RIGHT";
        return "other";
    }
}
