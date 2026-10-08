package com.blockoutlines.addon.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal black/white SVG reader. It understands {@code <polygon points="..." fill="...">} and
 * {@code <rect x y width height fill>} and {@code <path d fill>} (no arcs, no holes). Each shape is normalised to the range -1..1
 * (centred on the viewBox) and split into triangles, so the module can draw it flat on top of a block.
 * Shapes are drawn in file order; fills that are brighter than mid-grey count as white, the rest as black.
 */
public final class SvgIcon {
    public static final class Layer {
        public final float[] u, v;   // normalised corner coordinates
        public final int[] tris;     // index triples into u/v
        public final boolean white;

        Layer(float[] u, float[] v, int[] tris, boolean white) {
            this.u = u;
            this.v = v;
            this.tris = tris;
            this.white = white;
        }
    }

    public final List<Layer> layers = new ArrayList<>();

    private static final Pattern TAG = Pattern.compile("<(polygon|rect|path)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEWBOX = Pattern.compile("viewBox\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Loads an SVG from the jar; falls back to a plain white square if it is missing or unreadable. */
    public static SvgIcon load(String resourcePath) {
        try (InputStream in = SvgIcon.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                SvgIcon icon = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!icon.layers.isEmpty()) return icon;
            }
        } catch (Exception ignored) {
        }
        return parse("<svg viewBox=\"0 0 100 100\"><polygon points=\"10,10 90,10 90,90 10,90\" fill=\"#fff\"/></svg>");
    }

    /** Paints the shapes onto a grid x grid bitmap: 0 = empty, 1 = light (white) shape, 2 = dark (black) shape. */
    public byte[] raster(int grid) {
        byte[] out = new byte[grid * grid];
        double half = grid / 2.0;
        for (Layer l : layers) {
            byte kind = (byte) (l.white ? 1 : 2);
            for (int t = 0; t < l.tris.length; t += 3) {
                double ax = (l.u[l.tris[t]] + 1) * half, ay = (l.v[l.tris[t]] + 1) * half;
                double bx = (l.u[l.tris[t + 1]] + 1) * half, by = (l.v[l.tris[t + 1]] + 1) * half;
                double cx = (l.u[l.tris[t + 2]] + 1) * half, cy = (l.v[l.tris[t + 2]] + 1) * half;
                int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
                int x1 = Math.min(grid - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
                int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
                int y1 = Math.min(grid - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        double px = x + 0.5, py = y + 0.5;
                        double d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
                        double d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
                        double d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
                        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
                        if (!(neg && pos)) out[y * grid + x] = kind;
                    }
                }
            }
        }
        return out;
    }

    public static SvgIcon parse(String svg) {
        SvgIcon icon = new SvgIcon();

        double vx = 0, vy = 0, vw = 100, vh = 100;
        Matcher vb = VIEWBOX.matcher(svg);
        if (vb.find()) {
            String[] p = vb.group(1).trim().split("[\\s,]+");
            if (p.length == 4) {
                vx = Double.parseDouble(p[0]);
                vy = Double.parseDouble(p[1]);
                vw = Double.parseDouble(p[2]);
                vh = Double.parseDouble(p[3]);
            }
        }
        double half = Math.max(vw, vh) / 2.0, cx = vx + vw / 2.0, cy = vy + vh / 2.0;

        Matcher m = TAG.matcher(svg);
        while (m.find()) {
            String attrs = m.group(2);
            double[] xs, ys;

            if (m.group(1).equalsIgnoreCase("path")) {
                String d = attr(attrs, "d");
                if (d == null) continue;
                boolean white = isWhite(attr(attrs, "fill"));
                for (double[][] poly : flattenPath(d)) {
                    double[] px = poly[0], py = poly[1];
                    float[] pu = new float[px.length], pv = new float[px.length];
                    for (int i = 0; i < px.length; i++) {
                        pu[i] = (float) ((px[i] - cx) / half);
                        pv[i] = (float) ((py[i] - cy) / half);
                    }
                    int[] t = triangulate(px, py);
                    if (t.length > 0) icon.layers.add(new Layer(pu, pv, t, white));
                }
                continue;
            }

            if (m.group(1).equalsIgnoreCase("polygon")) {
                String pts = attr(attrs, "points");
                if (pts == null) continue;
                String[] n = pts.trim().split("[\\s,]+");
                if (n.length < 6) continue;
                xs = new double[n.length / 2];
                ys = new double[n.length / 2];
                for (int i = 0; i < xs.length; i++) {
                    xs[i] = Double.parseDouble(n[i * 2]);
                    ys[i] = Double.parseDouble(n[i * 2 + 1]);
                }
            } else {
                double x = num(attr(attrs, "x")), y = num(attr(attrs, "y"));
                double w = num(attr(attrs, "width")), h = num(attr(attrs, "height"));
                xs = new double[]{x, x + w, x + w, x};
                ys = new double[]{y, y, y + h, y + h};
            }

            float[] u = new float[xs.length], v = new float[xs.length];
            for (int i = 0; i < xs.length; i++) {
                u[i] = (float) ((xs[i] - cx) / half);
                v[i] = (float) ((ys[i] - cy) / half);
            }
            int[] tris = triangulate(xs, ys);
            if (tris.length > 0) icon.layers.add(new Layer(u, v, tris, isWhite(attr(attrs, "fill"))));
        }
        return icon;
    }

    /** Turns path data (M L H V C S Q T Z, absolute or relative; arcs are skipped) into polygons. */
    private static List<double[][]> flattenPath(String d) {
        List<double[][]> out = new ArrayList<>();
        Matcher t = Pattern.compile("([MmLlHhVvCcSsQqTtZzAa])|(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)").matcher(d);
        List<String> tok = new ArrayList<>();
        while (t.find()) tok.add(t.group());
        List<double[]> pts = new ArrayList<>();
        double x = 0, y = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;
        char cmd = 'M';
        int i = 0;
        while (i < tok.size()) {
            String k = tok.get(i);
            if (Character.isLetter(k.charAt(0))) { cmd = k.charAt(0); i++; if (cmd == 'Z' || cmd == 'z') { x = sx; y = sy; flush(pts, out); } continue; }
            boolean rel = Character.isLowerCase(cmd);
            char c = Character.toUpperCase(cmd);
            int need = switch (c) { case 'H', 'V' -> 1; case 'M', 'L', 'T' -> 2; case 'S', 'Q' -> 4; case 'C' -> 6; default -> 7; };
            if (c == 'A' || i + need > tok.size()) { i += Math.max(need, 1); continue; }
            double[] a = new double[need];
            for (int j = 0; j < need; j++) a[j] = Double.parseDouble(tok.get(i + j));
            i += need;
            double ox = rel ? x : 0, oy = rel ? y : 0;
            switch (c) {
                case 'M' -> { flush(pts, out); x = a[0] + ox; y = a[1] + oy; sx = x; sy = y; pts.add(new double[]{x, y}); cmd = rel ? 'l' : 'L'; }
                case 'L' -> { x = a[0] + ox; y = a[1] + oy; pts.add(new double[]{x, y}); }
                case 'H' -> { x = a[0] + (rel ? x : 0); pts.add(new double[]{x, y}); }
                case 'V' -> { y = a[0] + (rel ? y : 0); pts.add(new double[]{x, y}); }
                case 'C', 'S' -> {
                    double x1, y1, x2, y2, x3, y3;
                    if (c == 'C') { x1 = a[0] + ox; y1 = a[1] + oy; x2 = a[2] + ox; y2 = a[3] + oy; x3 = a[4] + ox; y3 = a[5] + oy; }
                    else { x1 = 2 * x - lcx; y1 = 2 * y - lcy; x2 = a[0] + ox; y2 = a[1] + oy; x3 = a[2] + ox; y3 = a[3] + oy; }
                    for (int n = 1; n <= 8; n++) {
                        double u = n / 8.0, w = 1 - u;
                        pts.add(new double[]{w*w*w*x + 3*w*w*u*x1 + 3*w*u*u*x2 + u*u*u*x3, w*w*w*y + 3*w*w*u*y1 + 3*w*u*u*y2 + u*u*u*y3});
                    }
                    lcx = x2; lcy = y2; x = x3; y = y3;
                }
                default -> { // Q, T
                    double x1, y1, x2, y2;
                    if (c == 'Q') { x1 = a[0] + ox; y1 = a[1] + oy; x2 = a[2] + ox; y2 = a[3] + oy; }
                    else { x1 = 2 * x - lcx; y1 = 2 * y - lcy; x2 = a[0] + ox; y2 = a[1] + oy; }
                    for (int n = 1; n <= 8; n++) {
                        double u = n / 8.0, w = 1 - u;
                        pts.add(new double[]{w*w*x + 2*w*u*x1 + u*u*x2, w*w*y + 2*w*u*y1 + u*u*y2});
                    }
                    lcx = x1; lcy = y1; x = x2; y = y2;
                }
            }
        }
        flush(pts, out);
        return out;
    }

    private static void flush(List<double[]> pts, List<double[][]> out) {
        if (pts.size() > 1) {
            double[] f = pts.get(0), l = pts.get(pts.size() - 1);
            if (f[0] == l[0] && f[1] == l[1]) pts.remove(pts.size() - 1);
        }
        if (pts.size() >= 3) {
            double[] xs = new double[pts.size()], ys = new double[pts.size()];
            for (int i = 0; i < xs.length; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
            out.add(new double[][]{xs, ys});
        }
        pts.clear();
    }

    private static String attr(String attrs, String name) {
        Matcher a = Pattern.compile("(?<![\\w-])" + name + "\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(attrs);
        return a.find() ? a.group(1) : null;
    }

    private static double num(String s) {
        return s == null ? 0 : Double.parseDouble(s.replaceAll("[^0-9eE+.-]", ""));
    }

    private static boolean isWhite(String fill) {
        if (fill == null) return false;
        String f = fill.trim().toLowerCase();
        if (f.equals("white")) return true;
        if (f.equals("black") || f.equals("none")) return false;
        if (f.startsWith("#")) {
            f = f.substring(1);
            if (f.length() == 3) f = "" + f.charAt(0) + f.charAt(0) + f.charAt(1) + f.charAt(1) + f.charAt(2) + f.charAt(2);
            if (f.length() >= 6) {
                int r = Integer.parseInt(f.substring(0, 2), 16);
                int g = Integer.parseInt(f.substring(2, 4), 16);
                int b = Integer.parseInt(f.substring(4, 6), 16);
                return (r + g + b) / 3 > 127;
            }
        }
        return false;
    }

    /** Ear-clipping triangulation for simple polygons (convex or concave). */
    static int[] triangulate(double[] x, double[] y) {
        int n = x.length;
        if (n < 3) return new int[0];

        List<Integer> idx = new ArrayList<>();
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += x[i] * y[j] - x[j] * y[i];
            idx.add(i);
        }
        if (area < 0) java.util.Collections.reverse(idx); // make it counter-clockwise

        List<Integer> out = new ArrayList<>();
        int guard = 0;
        while (idx.size() > 3 && guard++ < 10000) {
            boolean clipped = false;
            for (int i = 0; i < idx.size(); i++) {
                int a = idx.get((i + idx.size() - 1) % idx.size()), b = idx.get(i), c = idx.get((i + 1) % idx.size());
                if (cross(x, y, a, b, c) <= 1e-9) continue; // reflex or degenerate corner
                boolean blocked = false;
                for (int p : idx) {
                    if (p == a || p == b || p == c) continue;
                    if (inside(x, y, p, a, b, c)) {
                        blocked = true;
                        break;
                    }
                }
                if (blocked) continue;
                out.add(a);
                out.add(b);
                out.add(c);
                idx.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) break;
        }
        if (idx.size() == 3) out.addAll(idx);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double cross(double[] x, double[] y, int a, int b, int c) {
        return (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a]);
    }

    private static boolean inside(double[] x, double[] y, int p, int a, int b, int c) {
        return cross(x, y, a, b, p) >= -1e-9 && cross(x, y, b, c, p) >= -1e-9 && cross(x, y, c, a, p) >= -1e-9;
    }
}
