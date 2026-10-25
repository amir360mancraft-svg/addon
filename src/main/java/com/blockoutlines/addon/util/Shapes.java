package com.blockoutlines.addon.util;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.render.color.Color;

/** Small helpers to draw solid shaded boxes (for little models built from cubes) with Meteor's 3D renderer. */
public final class Shapes {
    private Shapes() {}

    /** Turns a point of a model (local x = right, y = up, z = forward) into a world point. */
    @FunctionalInterface
    public interface Tf {
        double[] apply(double lx, double ly, double lz);
    }

    /** Meteor's quad() wants the colors as top-left, top-right, bottom-right, bottom-left (= vertices 2, 3, 4, 1). Drawn from both sides. */
    public static void quad(Renderer3D r,
                            double x1, double y1, double z1, double x2, double y2, double z2,
                            double x3, double y3, double z3, double x4, double y4, double z4,
                            Color cv1, Color cv2, Color cv3, Color cv4) {
        r.quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, cv2, cv3, cv4, cv1);
        r.quad(x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1, cv1, cv4, cv3, cv2);
    }

    private static Color shade(int cr, int cg, int cb, int a, double k) {
        return new Color((int) Math.min(255, cr * k), (int) Math.min(255, cg * k), (int) Math.min(255, cb * k), a);
    }

    /**
     * A box centred on (cx, cy, cz) in model space with the given size, rotated around its own centre by {@code rotZ}
     * (radians, around the front-back axis; used for flippers and fingers). Faces get a fixed light so it looks solid.
     */
    public static void box(Renderer3D r, Tf tf, double cx, double cy, double cz, double sx, double sy, double sz,
                           double rotZ, int cr, int cg, int cb, int a) {
        double hx = sx / 2, hy = sy / 2, hz = sz / 2;
        double cs = Math.cos(rotZ), sn = Math.sin(rotZ);
        double[][] c = new double[8][];
        int i = 0;
        for (int yy = -1; yy <= 1; yy += 2) {
            for (int zz = -1; zz <= 1; zz += 2) {
                for (int xx = -1; xx <= 1; xx += 2) {
                    double lx = xx * hx, ly = yy * hy;
                    double rx = lx * cs - ly * sn, ry = lx * sn + ly * cs;
                    c[i++] = tf.apply(cx + rx, cy + ry, cz + zz * hz);
                }
            }
        }
        // corner index = (yy low/high)*4 + (zz back/front)*2 + (xx left/right)
        Color top = shade(cr, cg, cb, a, 1.0), bottom = shade(cr, cg, cb, a, 0.5);
        Color front = shade(cr, cg, cb, a, 0.88), back = shade(cr, cg, cb, a, 0.7);
        Color left = shade(cr, cg, cb, a, 0.78), right = shade(cr, cg, cb, a, 0.82);
        face(r, c, 4, 5, 7, 6, top);
        face(r, c, 0, 1, 3, 2, bottom);
        face(r, c, 2, 3, 7, 6, front);
        face(r, c, 0, 1, 5, 4, back);
        face(r, c, 0, 2, 6, 4, left);
        face(r, c, 1, 3, 7, 5, right);
    }

    private static void face(Renderer3D r, double[][] c, int a, int b, int d, int e, Color col) {
        quad(r, c[a][0], c[a][1], c[a][2], c[b][0], c[b][1], c[b][2], c[d][0], c[d][1], c[d][2], c[e][0], c[e][1], c[e][2], col, col, col, col);
    }

    /** A soft round glow facing the camera (right / up are the camera's unit vectors). */
    public static void glow(Renderer3D r, double x, double y, double z, double rad,
                            double rx, double ry, double rz, double ux, double uy, double uz,
                            int cr, int cg, int cb, int alpha) {
        int segs = 18;
        double[] fr = {0.0, 0.35, 0.7, 1.0};
        double[] al = {1.0, 0.5, 0.15, 0.0};
        for (int ring = 0; ring < 3; ring++) {
            for (int s = 0; s < segs; s++) {
                double t0 = Math.PI * 2 * s / segs, t1 = Math.PI * 2 * (s + 1) / segs;
                double f0 = fr[ring] * rad, f1 = fr[ring + 1] * rad;
                Color c0 = new Color(cr, cg, cb, (int) (alpha * al[ring])), c1 = new Color(cr, cg, cb, (int) (alpha * al[ring + 1]));
                double a0x = Math.cos(t0), a0y = Math.sin(t0), a1x = Math.cos(t1), a1y = Math.sin(t1);
                quad(r,
                    x + (rx * a0x + ux * a0y) * f0, y + (ry * a0x + uy * a0y) * f0, z + (rz * a0x + uz * a0y) * f0,
                    x + (rx * a1x + ux * a1y) * f0, y + (ry * a1x + uy * a1y) * f0, z + (rz * a1x + uz * a1y) * f0,
                    x + (rx * a1x + ux * a1y) * f1, y + (ry * a1x + uy * a1y) * f1, z + (rz * a1x + uz * a1y) * f1,
                    x + (rx * a0x + ux * a0y) * f1, y + (ry * a0x + uy * a0y) * f1, z + (rz * a0x + uz * a0y) * f1,
                    c0, c0, c1, c1);
            }
        }
    }
}
