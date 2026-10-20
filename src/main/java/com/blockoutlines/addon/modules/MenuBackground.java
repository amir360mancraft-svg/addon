package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Your own pictures as the menu background (title screen and the other menus outside a world), swapping by itself with a
 * soft cross-fade and a slow zoom. Put pictures (png / jpg) in the folder  .minecraft/better-render/backgrounds  or use
 * the "Add pictures" button.
 */
public class MenuBackground extends Module {
    public enum Order { Cycle, Random, Single }

    private static final String DIR = "better-render/backgrounds";
    private static final int MAX_SIDE = 2560;

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Order> order = sg.add(new EnumSetting.Builder<Order>()
        .name("order").description("Cycle = one after the other, Random = any picture, Single = always the picture chosen below.")
        .defaultValue(Order.Cycle).build());
    private final Setting<Integer> single = sg.add(new IntSetting.Builder()
        .name("picture").description("Which picture to show (1 = first in the folder, sorted by name).")
        .defaultValue(1).range(1, 200).sliderRange(1, 20).visible(() -> order.get() == Order.Single).build());
    private final Setting<Integer> interval = sg.add(new IntSetting.Builder()
        .name("change-every").description("Seconds before the next picture.")
        .defaultValue(20).range(3, 600).sliderRange(5, 120).visible(() -> order.get() != Order.Single).build());
    private final Setting<Integer> fadeMs = sg.add(new IntSetting.Builder()
        .name("fade").description("Cross-fade time between two pictures (milliseconds).")
        .defaultValue(1200).range(0, 5000).sliderRange(0, 3000).visible(() -> order.get() != Order.Single).build());
    private final Setting<Boolean> zoom = sg.add(new BoolSetting.Builder()
        .name("slow-zoom").description("A very slow zoom and drift over the picture.").defaultValue(true).build());
    private final Setting<Integer> dim = sg.add(new IntSetting.Builder()
        .name("darken").description("Darkens the picture so buttons stay readable (percent).")
        .defaultValue(25).range(0, 90).sliderRange(0, 80).build());
    private final Setting<Boolean> titleOnly = sg.add(new BoolSetting.Builder()
        .name("title-screen-only").description("Only the main menu. Off = also options, server list and the other menus outside a world.")
        .defaultValue(false).build());

    private static final class Tex {
        Identifier id;
        NativeImageBackedTexture texture;
        int w, h;
        File file;
    }

    private static final class Pending {
        int[] argb;
        int w, h;
        File file;
    }

    private final List<File> files = new ArrayList<>();
    private final Random rng = new Random();
    private Tex cur, prev;
    private CompletableFuture<Pending> loading;
    private int index = -1;
    private int serial;
    private long shownAt, fadeStart;
    private long lastScan;
    private boolean warned;

    public MenuBackground() {
        super(BlockOutlinesAddon.CATEGORY, "menu-background",
            "Your own pictures as the menu background, changing by themselves with a soft fade.");
    }

    // ------------------------------------------------------------------ module

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();
        WButton add = row.add(theme.button("Add pictures")).expandX().widget();
        WButton open = row.add(theme.button("Open folder")).expandX().widget();
        WButton next = list.add(theme.button("Next picture now")).expandX().widget();
        add.action = this::addPictures;
        open.action = () -> {
            try {
                Util.getOperatingSystem().open(dir());
            } catch (Throwable t) {
                error("Could not open the folder: " + t.getMessage());
            }
        };
        next.action = () -> {
            scan(true);
            requestNext();
        };
        return list;
    }

    @Override
    public void onActivate() {
        warned = false;
        scan(true);
        if (files.isEmpty()) {
            info("No pictures yet. Click \"Add pictures\" in the module settings, or copy png/jpg files into: " + dir().getPath());
        }
    }

    @Override
    public void onDeactivate() {
        loading = null;
        free(prev);
        free(cur);
        prev = null;
        cur = null;
        index = -1;
    }

    private File dir() {
        File d = new File(mc.runDirectory, DIR);
        d.mkdirs();
        return d;
    }

    private void scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 5000) return;
        lastScan = now;
        files.clear();
        File[] all = dir().listFiles((d, n) -> {
            String s = n.toLowerCase();
            return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".bmp") || s.endsWith(".gif");
        });
        if (all != null) {
            Arrays.sort(all, Comparator.comparing(f -> f.getName().toLowerCase()));
            files.addAll(Arrays.asList(all));
        }
    }

    private void addPictures() {
        try {
            PointerBuffer filters = BufferUtils.createPointerBuffer(3);
            filters.put(MemoryUtil.memASCII("*.png"));
            filters.put(MemoryUtil.memASCII("*.jpg"));
            filters.put(MemoryUtil.memASCII("*.jpeg"));
            filters.rewind();
            String paths = TinyFileDialogs.tinyfd_openFileDialog("Select background pictures (you can select many)", null, filters, "Pictures", true);
            if (paths == null) return;
            File d = dir();
            int ok = 0;
            for (String path : paths.split("\\|")) {
                if (path.isBlank()) continue;
                File src = new File(path);
                String name = src.getName().replaceAll("[^A-Za-z0-9 _()+.'-]", "_");
                Files.copy(src.toPath(), new File(d, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                ok++;
            }
            scan(true);
            info(ok + " picture(s) added.");
        } catch (Exception e) {
            error("Could not add the pictures: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ choosing + loading

    private int pickIndex() {
        int n = files.size();
        if (n == 0) return -1;
        switch (order.get()) {
            case Single:
                return Math.min(n, Math.max(1, single.get())) - 1;
            case Random:
                if (n == 1) return 0;
                int i;
                do { i = rng.nextInt(n); } while (i == index);
                return i;
            default:
                return (index + 1) % n;
        }
    }

    private void requestNext() {
        if (loading != null || files.isEmpty()) return;
        int i = pickIndex();
        if (i < 0) return;
        final File f = files.get(i);
        index = i;
        loading = CompletableFuture.supplyAsync(() -> decode(f));
    }

    /** Reads a picture on a worker thread and shrinks it when it is huge. Returns null when it cannot be read. */
    private static Pending decode(File f) {
        try {
            BufferedImage src = ImageIO.read(f);
            if (src == null) return null;
            int w = src.getWidth(), h = src.getHeight();
            int big = Math.max(w, h);
            if (big > MAX_SIDE) {
                double k = MAX_SIDE / (double) big;
                int nw = Math.max(1, (int) (w * k)), nh = Math.max(1, (int) (h * k));
                BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = small.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, nw, nh, null);
                g.dispose();
                src = small;
                w = nw;
                h = nh;
            }
            Pending p = new Pending();
            p.w = w;
            p.h = h;
            p.file = f;
            p.argb = src.getRGB(0, 0, w, h, null, 0, w);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Makes a GPU texture from the decoded pixels (must run on the render thread). */
    private Tex upload(Pending p) {
        NativeImage img = new NativeImage(p.w, p.h, false);
        for (int y = 0; y < p.h; y++) {
            for (int x = 0; x < p.w; x++) img.setColorArgb(x, y, 0xFF000000 | p.argb[y * p.w + x]);
        }
        Tex t = new Tex();
        t.w = p.w;
        t.h = p.h;
        t.file = p.file;
        t.id = Identifier.of("blockoutlines", "menu_background_" + (serial++));
        t.texture = new NativeImageBackedTexture(() -> "blockoutlines-menu-background", img);
        mc.getTextureManager().registerTexture(t.id, t.texture);
        return t;
    }

    private void free(Tex t) {
        if (t == null) return;
        try {
            mc.getTextureManager().destroyTexture(t.id);
        } catch (Throwable ignored) {
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        scan(false);

        if (loading != null && loading.isDone()) {
            Pending p = null;
            try {
                p = loading.getNow(null);
            } catch (Throwable ignored) {
            }
            loading = null;
            if (p != null) {
                try {
                    Tex fresh = upload(p);
                    free(prev);
                    prev = cur;
                    cur = fresh;
                    shownAt = now;
                    fadeStart = now;
                    if (prev == null) fadeStart = now - fadeMs.get(); // first picture appears at once
                } catch (Throwable t) {
                    if (!warned) { warned = true; error("Could not use a picture: " + t.getMessage()); }
                }
            } else if (!files.isEmpty()) {
                // unreadable picture: skip it next time
                shownAt = now - interval.get() * 1000L + 500;
            }
        }

        if (prev != null && now - fadeStart >= fadeMs.get()) {
            free(prev);
            prev = null;
        }

        if (loading == null && !files.isEmpty()) {
            boolean want = cur == null
                || (order.get() != Order.Single && now - shownAt >= interval.get() * 1000L)
                || (order.get() == Order.Single && (cur.file != files.get(Math.min(files.size(), Math.max(1, single.get())) - 1)));
            if (want) requestNext();
        }
    }

    // ------------------------------------------------------------------ drawing (called by ScreenBackgroundMixin)

    /** Draws the picture. Returns false when nothing is ready, so the normal panorama is drawn instead. */
    public boolean draw(DrawContext ctx, Screen screen) {
        if (mc.world != null) return false;
        if (titleOnly.get() && !(screen instanceof TitleScreen)) return false;

        try {
            tick();
            if (cur == null) return false;

            int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
            long now = System.currentTimeMillis();
            double f = prev == null ? 1.0 : Math.max(0.0, Math.min(1.0, (now - fadeStart) / (double) Math.max(1, fadeMs.get())));
            f = f * f * (3 - 2 * f);

            if (prev != null && f < 1.0) cover(ctx, prev, w, h, 255, now);
            cover(ctx, cur, w, h, (int) (255 * f), now);

            if (dim.get() > 0) ctx.fill(0, 0, w, h, ((int) (255 * dim.get() / 100.0) << 24));
            return true;
        } catch (Throwable t) {
            if (!warned) { warned = true; error("Menu background problem: " + t.getMessage()); }
            return false;
        }
    }

    /** Fills the whole screen with the picture without stretching it (the extra part is cropped). */
    private void cover(DrawContext ctx, Tex t, int w, int h, int alpha, long now) {
        if (alpha <= 0) return;
        double scale = Math.max(w / (double) t.w, h / (double) t.h);
        double z = 1.0, dx = 0, dy = 0;
        if (zoom.get()) {
            double life = Math.max(1, interval.get() * 1000.0 + fadeMs.get());
            double u = Math.max(0, Math.min(1, (now - shownAt + (t == prev ? interval.get() * 1000.0 : 0)) / life));
            z = 1.0 + 0.10 * u;
            dx = (u - 0.5) * 0.6;
            dy = Math.sin(u * Math.PI) * 0.15;
        }
        double rw = w / scale / z, rh = h / scale / z;
        double u0 = (t.w - rw) / 2.0 * (1.0 + dx);
        double v0 = (t.h - rh) / 2.0 * (1.0 + dy);
        u0 = Math.max(0, Math.min(t.w - rw, u0));
        v0 = Math.max(0, Math.min(t.h - rh, v0));
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id, 0, 0, (float) u0, (float) v0, w, h,
            Math.max(1, (int) rw), Math.max(1, (int) rh), t.w, t.h, (alpha << 24) | 0xFFFFFF);
    }
}
