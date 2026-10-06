package com.blockoutlines.addon.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal black/white SVG reader. It understands {@code <polygon points="..." fill="...">} and
 * {@code <rect x y width height fill>}, nothing else. Each shape is normalised to the range -1..1
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

    private static final Pattern TAG = Pattern.compile("<(polygon|rect)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
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
