package com.blockoutlines.addon.util;

/**
 * A small pixel drawing: every cell is empty, white or black. It converts to and from an SVG made of
 * {@code <rect>} shapes, so a drawing is saved in the same format the crosshair already loads.
 * No Minecraft code in here, so it can be tested on its own.
 */
public final class PixelGrid {
    public static final int SIZE = 33; // odd, so there is a real centre cell (16)

    public static final byte EMPTY = 0, WHITE = 1, BLACK = 2;

    private final byte[] cells = new byte[SIZE * SIZE];

    public byte get(int x, int y) {
        return inside(x, y) ? cells[y * SIZE + x] : EMPTY;
    }

    /** Sets one cell. Returns true when the cell really changed. */
    public boolean set(int x, int y, byte value) {
        if (!inside(x, y) || cells[y * SIZE + x] == value) return false;
        cells[y * SIZE + x] = value;
        return true;
    }

    public void clear() {
        java.util.Arrays.fill(cells, EMPTY);
    }

    public boolean isEmpty() {
        for (byte b : cells) if (b != EMPTY) return false;
        return true;
    }

    public static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
    }

    /** Sets every cell on the straight line between two cells, so fast mouse drags leave no gaps. */
    public void line(int x0, int y0, int x1, int y1, byte value) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            set(x0, y0, value);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    /** Writes the drawing as an SVG: runs of equal cells in a row become one rectangle, equal rows are merged. */
    public String toSvg() {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(SIZE).append(' ').append(SIZE)
            .append("\" width=\"").append(SIZE).append("\" height=\"").append(SIZE).append("\">\n");

        java.util.List<int[]> open = new java.util.ArrayList<>(); // x, y, width, height, kind
        java.util.List<int[]> done = new java.util.ArrayList<>();

        for (int y = 0; y <= SIZE; y++) {
            java.util.List<int[]> row = new java.util.ArrayList<>();
            if (y < SIZE) {
                int x = 0;
                while (x < SIZE) {
                    byte k = cells[y * SIZE + x];
                    if (k == EMPTY) { x++; continue; }
                    int start = x;
                    while (x < SIZE && cells[y * SIZE + x] == k) x++;
                    row.add(new int[]{start, y, x - start, 1, k});
                }
            }

            java.util.List<int[]> next = new java.util.ArrayList<>();
            for (int[] r : row) {
                int[] match = null;
                for (int[] o : open) {
                    if (o[0] == r[0] && o[2] == r[2] && o[4] == r[4] && o[1] + o[3] == y) { match = o; break; }
                }
                if (match != null) { match[3]++; open.remove(match); next.add(match); }
                else next.add(r);
            }
            done.addAll(open); // rectangles that did not continue into this row
            open = next;
        }
        done.addAll(open);

        for (int[] r : done) {
            sb.append("  <rect x=\"").append(r[0]).append("\" y=\"").append(r[1])
                .append("\" width=\"").append(r[2]).append("\" height=\"").append(r[3])
                .append("\" fill=\"").append(r[4] == WHITE ? "#ffffff" : "#000000").append("\"/>\n");
        }
        sb.append("</svg>\n");
        return sb.toString();
    }

    /** Reads any SVG the addon understands and paints it onto the 33 x 33 grid (nearest cell). */
    public static PixelGrid fromSvg(String svg) {
        PixelGrid g = new PixelGrid();
        byte[] raster = SvgIcon.parse(svg).raster(SIZE);
        System.arraycopy(raster, 0, g.cells, 0, raster.length);
        return g;
    }
}
