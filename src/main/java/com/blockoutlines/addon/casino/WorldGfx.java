package com.blockoutlines.addon.casino;

import com.blockoutlines.addon.util.WorldText;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Drawing on a flat panel standing (or lying) in the world. The panel is a canvas of cw x ch pixels: its top-left corner is O,
 * canvas x runs along the unit vector R and canvas y along the unit vector D, and one pixel is pw blocks.
 * The casino games draw on it exactly like on the 2D screen.
 */
public final class WorldGfx extends Gfx {
    public double ox, oy, oz, rx, ry, rz, dx, dy, dz, pw;
    public int cw, ch;

    private Renderer3D r;
    private final float[][] stack = new float[40][4];
    private int sp;

    public void plane(double ox, double oy, double oz, double rx, double ry, double rz, double dx, double dy, double dz, double pw, int cw, int ch) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.pw = pw;
        this.cw = cw;
        this.ch = ch;
    }

    public void begin(Renderer3D r) {
        this.r = r;
        sp = 0;
        stack[0][0] = 1;
        stack[0][1] = 1;
        stack[0][2] = 0;
        stack[0][3] = 0;
    }

    /** The canvas pixel (x, y) and the distance t along the ray where it hits this panel, or null if it points away from it. */
    public double[] pick(double ex, double ey, double ez, double vx, double vy, double vz) {
        double nx = ry * dz - rz * dy, ny = rz * dx - rx * dz, nz = rx * dy - ry * dx;
        double den = vx * nx + vy * ny + vz * nz;
        if (Math.abs(den) < 1e-9) return null;
        double t = ((ox - ex) * nx + (oy - ey) * ny + (oz - ez) * nz) / den;
        if (t < 0) return null;
        double px = ex + vx * t - ox, py = ey + vy * t - oy, pz = ez + vz * t - oz;
        return new double[]{(px * rx + py * ry + pz * rz) / pw, (px * dx + py * dy + pz * dz) / pw, t};
    }

    private float[] m() {
        return stack[sp];
    }

    @Override
    public void push() {
        if (sp < stack.length - 1) {
            System.arraycopy(stack[sp], 0, stack[sp + 1], 0, 4);
            sp++;
        }
    }

    @Override
    public void pop() {
        if (sp > 0) sp--;
    }

    @Override
    public void translate(float x, float y) {
        float[] m = m();
        m[2] += x * m[0];
        m[3] += y * m[1];
    }

    @Override
    public void scale(float x, float y) {
        float[] m = m();
        m[0] *= x;
        m[1] *= y;
    }

    private static Color col(int argb) {
        return new Color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255);
    }

    private void quadPx(double x0, double y0, double x1, double y1, int argb) {
        double ax = ox + rx * x0 * pw + dx * y0 * pw, ay = oy + ry * x0 * pw + dy * y0 * pw, az = oz + rz * x0 * pw + dz * y0 * pw;
        double bx = ox + rx * x1 * pw + dx * y0 * pw, by = oy + ry * x1 * pw + dy * y0 * pw, bz = oz + rz * x1 * pw + dz * y0 * pw;
        double cx = ox + rx * x1 * pw + dx * y1 * pw, cy = oy + ry * x1 * pw + dy * y1 * pw, cz = oz + rz * x1 * pw + dz * y1 * pw;
        double ex = ox + rx * x0 * pw + dx * y1 * pw, ey = oy + ry * x0 * pw + dy * y1 * pw, ez = oz + rz * x0 * pw + dz * y1 * pw;
        r.quad(ax, ay, az, bx, by, bz, cx, cy, cz, ex, ey, ez, col(argb));
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        if (((argb >>> 24) & 255) < 3) return;
        float[] m = m();
        double a = Math.min(x1, x2) * m[0] + m[2], b = Math.max(x1, x2) * m[0] + m[2];
        double c = Math.min(y1, y2) * m[1] + m[3], d = Math.max(y1, y2) * m[1] + m[3];
        if (a < 0) a = 0;
        if (c < 0) c = 0;
        if (b > cw) b = cw;
        if (d > ch) d = ch;
        if (b <= a || d <= c) return;
        quadPx(a, c, b, d, argb);
    }

    @Override
    public void text(String s, int x, int y, int argb, boolean shadow) {
        if (s == null || s.isEmpty() || ((argb >>> 24) & 255) < 3) return;
        float[] m = m();
        double wpx = UI.tr().getWidth(s);
        double cxp = (x + wpx / 2) * m[0] + m[2], cyp = (y + 4) * m[1] + m[3];
        if (cxp < 0 || cyp < 0 || cxp > cw || cyp > ch) return;
        double worldW = wpx * Math.abs(m[0]) * pw;
        double size = worldW / Math.max(1e-6, WorldText.width(s, 1.0));
        size = Math.min(size, 10.5 * Math.abs(m[1]) * pw);
        double wx = ox + rx * cxp * pw + dx * cyp * pw, wy = oy + ry * cxp * pw + dy * cyp * pw, wz = oz + rz * cxp * pw + dz * cyp * pw;
        WorldText.drawOn(r, s, wx, wy, wz, rx, ry, rz, -dx, -dy, -dz, size, argb);
    }

    @Override
    public void item(ItemStack st, int x, int y) {
        if (st.isOf(Items.DIAMOND)) {
            UI.diamond(this, x + 8, y + 8, 7, 0xFF2FB8C4);
            UI.diamond(this, x + 8, y + 8, 5, 0xFF58E8F0);
            UI.diamond(this, x + 7, y + 6, 2, 0xFFD8FFFF);
        } else if (st.isOf(Items.EMERALD)) {
            UI.diamond(this, x + 8, y + 8, 7, 0xFF1F9A55);
            UI.diamond(this, x + 8, y + 8, 5, 0xFF3DDC84);
            UI.diamond(this, x + 7, y + 6, 2, 0xFFD6FFE6);
        } else if (st.isOf(Items.TNT)) {
            fill(x + 2, y + 2, x + 14, y + 14, 0xFFC93B3B);
            fill(x + 2, y + 6, x + 14, y + 10, 0xFFF2F2F2);
            fill(x + 2, y + 2, x + 14, y + 3, 0xFFE86A6A);
        } else {
            fill(x + 2, y + 2, x + 14, y + 14, 0xFFFFC857);
            fill(x + 4, y + 4, x + 12, y + 12, 0xFFE0A030);
        }
    }
}
