package com.blockoutlines.addon.util;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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


    /**
     * Collects the boxes of a small model and draws their faces from far to near, skipping the faces that look away
     * from the camera. Meteor's renderer does not write depth, so without this the far side shows through the near side
     * and the model looks hollow.
     */
    public static final class Batch {
        private final List<double[]> faces = new ArrayList<>(); // x1..z4 (12), color r,g,b,a (4), distance (1)

        public void clear() {
            faces.clear();
        }

        public void add(Tf tf, double cx, double cy, double cz, double sx, double sy, double sz, double rotZ,
                        int cr, int cg, int cb, int a) {
            double hx = sx / 2, hy = sy / 2, hz = sz / 2;
            double cs = Math.cos(rotZ), sn = Math.sin(rotZ);
            double[][] c = new double[8][];
            int i = 0;
            for (int yy = -1; yy <= 1; yy += 2) {
                for (int zz = -1; zz <= 1; zz += 2) {
                    for (int xx = -1; xx <= 1; xx += 2) {
                        double lx = xx * hx, ly = yy * hy;
                        c[i++] = tf.apply(cx + lx * cs - ly * sn, cy + lx * sn + ly * cs, cz + zz * hz);
                    }
                }
            }
            double mx = 0, my = 0, mz = 0;
            for (double[] p : c) { mx += p[0]; my += p[1]; mz += p[2]; }
            mx /= 8; my /= 8; mz /= 8;
            int[][] f = {{4, 5, 7, 6}, {0, 1, 3, 2}, {2, 3, 7, 6}, {0, 1, 5, 4}, {0, 2, 6, 4}, {1, 3, 7, 5}};
            double[] k = {1.0, 0.5, 0.88, 0.7, 0.78, 0.82};
            for (int n = 0; n < 6; n++) {
                double[] e = new double[17];
                for (int v = 0; v < 4; v++) {
                    e[v * 3] = c[f[n][v]][0];
                    e[v * 3 + 1] = c[f[n][v]][1];
                    e[v * 3 + 2] = c[f[n][v]][2];
                }
                e[12] = Math.min(255, cr * k[n]);
                e[13] = Math.min(255, cg * k[n]);
                e[14] = Math.min(255, cb * k[n]);
                e[15] = a;
                // keep the face centre and the box centre to decide later if it looks at the camera
                e[16] = 0;
                double fx = (e[0] + e[3] + e[6] + e[9]) / 4, fy = (e[1] + e[4] + e[7] + e[10]) / 4, fz = (e[2] + e[5] + e[8] + e[11]) / 4;
                faces.add(append(e, fx - mx, fy - my, fz - mz, fx, fy, fz));
            }
        }

        private static double[] append(double[] e, double... more) {
            double[] out = new double[e.length + more.length];
            System.arraycopy(e, 0, out, 0, e.length);
            System.arraycopy(more, 0, out, e.length, more.length);
            return out;
        }

        /** Draw everything, farthest first. (camX, camY, camZ) is the camera position in the world. */
        public void flush(Renderer3D r, double camX, double camY, double camZ) {
            List<double[]> visible = new ArrayList<>();
            for (double[] e : faces) {
                // e[17..19] = outward direction of the face, e[20..22] = face centre
                double vx = camX - e[20], vy = camY - e[21], vz = camZ - e[22];
                if (e[17] * vx + e[18] * vy + e[19] * vz <= 0) continue;
                e[16] = vx * vx + vy * vy + vz * vz;
                visible.add(e);
            }
            visible.sort(Comparator.comparingDouble((double[] e) -> e[16]).reversed());
            for (double[] e : visible) {
                Color col = new Color((int) e[12], (int) e[13], (int) e[14], (int) e[15]);
                quad(r, e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7], e[8], e[9], e[10], e[11], col, col, col, col);
            }
            faces.clear();
        }
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
