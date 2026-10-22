package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.util.HudHook;
import com.blockoutlines.addon.util.RoundRect;
import de.labystudio.spotifyapi.SpotifyAPI;
import de.labystudio.spotifyapi.SpotifyAPIFactory;
import de.labystudio.spotifyapi.SpotifyListener;
import de.labystudio.spotifyapi.model.Track;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the track playing in the desktop Spotify app: cover, title, artist, a bouncing equalizer and a seek bar.
 * The colors follow the cover, it can be made small, it hides itself when nothing plays and it can be moved from the
 * Right Shift hub (or with the chat open).
 */
public class BetterSpotifyHud extends Module {
    private static final Identifier ART_ID = Identifier.of("blockoutlines", "spotify_art");

    private static final int PAD = 8;
    private static final int ART = 44;
    private static final int ART_SMALL = 30;
    private static final int EQ_H = 22;
    private static final int EQ_MAX = 128;
    private static final int RADIUS = 10;

    private static final int GREEN = 0xFF1ED760;
    private static final int TITLE = 0xFFF4F6FA;
    private static final int ARTIST = 0xFF9AA4B6;
    private static final int TIME = 0xFF8791A4;
    private static final int TRACK = 0x26FFFFFF;
    private static final int ART_PLACEHOLDER = 0x33FFFFFF;

    public enum ColorMode { Album, Spotify, Custom }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHud = settings.createGroup("HUD");

    private final Setting<Boolean> showArt = sgGeneral.add(new BoolSetting.Builder()
        .name("album-art").description("Show the cover of the track.").defaultValue(true).build());
    private final Setting<Boolean> showProgress = sgGeneral.add(new BoolSetting.Builder()
        .name("progress-bar").description("Show the time and the seek bar.").defaultValue(true).build());
    private final Setting<Boolean> showEq = sgGeneral.add(new BoolSetting.Builder()
        .name("equalizer").description("Show the bouncing bars.").defaultValue(true).build());
    private final Setting<Double> eqSensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("equalizer-sensitivity").description("How hard the bars react.")
        .defaultValue(0.5).range(0.0, 1.0).sliderRange(0.0, 1.0).visible(showEq::get).build());
    private final Setting<Boolean> compact = sgGeneral.add(new BoolSetting.Builder()
        .name("compact").description("A small card: cover, title, artist and a thin bar. No equalizer.").defaultValue(false).build());
    private final Setting<Boolean> hideIdle = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-idle").description("Hide the panel when Spotify is closed or nothing was played yet.").defaultValue(false).build());
    private final Setting<ColorMode> colorMode = sgGeneral.add(new EnumSetting.Builder<ColorMode>()
        .name("color").description("Album: the color follows the cover. Spotify: always green. Custom: your color.")
        .defaultValue(ColorMode.Album).build());
    private final Setting<SettingColor> customColor = sgGeneral.add(new ColorSetting.Builder()
        .name("custom-color").description("Used when Color is Custom.")
        .defaultValue(new SettingColor(30, 215, 96, 255)).visible(() -> colorMode.get() == ColorMode.Custom).build());

    private final Setting<Integer> panelWidth = sgHud.add(new IntSetting.Builder()
        .name("width").description("Panel width.").defaultValue(280).min(200).max(500).sliderRange(200, 500).build());
    private final Setting<Integer> background = sgHud.add(new IntSetting.Builder()
        .name("background").description("How solid the panel is (percent).").defaultValue(94).range(20, 100).sliderRange(20, 100).build());
    private final Setting<Integer> scale = sgHud.add(new IntSetting.Builder()
        .name("scale").description("Size of the panel and text.").defaultValue(100).min(70).max(150).sliderRange(70, 150).build());
    private final Setting<Integer> hudX = sgHud.add(new IntSetting.Builder()
        .name("hud-x").description("Panel X. -1 parks it at the top right. Drag it in the Right Shift hub.")
        .defaultValue(-1).min(-1).sliderRange(-1, 4000).build());
    private final Setting<Integer> hudY = sgHud.add(new IntSetting.Builder()
        .name("hud-y").description("Panel Y. -1 parks it at the top.")
        .defaultValue(-1).min(-1).sliderRange(-1, 4000).build());

    // what Spotify told us (written by the library's thread, read on the render thread)
    private final Object lock = new Object();
    private Track track;
    private int position;
    private boolean playing;
    private long lastUpdate;

    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile boolean connected;
    private long lastTry;
    private SpotifyAPI api;

    private volatile BufferedImage pendingArt;
    private boolean hasArt;
    private int artW, artH;
    private int albumColor = GREEN;

    // drawing state
    private final float[] barHeights = new float[EQ_MAX];
    private final float[] barPhase = new float[EQ_MAX];
    private final float[] barJitter = new float[EQ_MAX];
    private boolean dragging;
    private double grabX, grabY;
    private float scrollX;
    private long scrollNanos;
    private String lastTitle = "";

    public BetterSpotifyHud() {
        super(BlockOutlinesAddon.CATEGORY, "better-spotify-hud", "The track playing in the desktop Spotify app, with the cover, an equalizer and a seek bar.");
        Random random = new Random();
        for (int i = 0; i < EQ_MAX; i++) {
            barHeights[i] = 2f;
            barPhase[i] = random.nextFloat() * 6.2831855f;
            barJitter[i] = 0.7f + random.nextFloat() * 0.6f;
        }
    }

    @Override
    public void onActivate() {
        dragging = false;
        scrollX = 0;
        lastTitle = "";
        tryConnect();
    }

    @Override
    public void onDeactivate() {
        dragging = false;
    }

    // ------------------------------------------------------------------ Spotify connection

    private void tryConnect() {
        if (connected || !connecting.compareAndSet(false, true)) return;
        lastTry = System.currentTimeMillis();
        Thread thread = new Thread(() -> {
            try {
                connect();
            } finally {
                connecting.set(false);
            }
        }, "better-spotify");
        thread.setDaemon(true);
        thread.start();
    }

    private void connect() {
        try {
            SpotifyAPI created = SpotifyAPIFactory.create();
            created.registerListener(new SpotifyListener() {
                @Override
                public void onConnect() {
                    connected = true;
                }

                @Override
                public void onTrackChanged(Track changed) {
                    synchronized (lock) {
                        track = changed;
                        lastUpdate = System.currentTimeMillis();
                    }
                    if (changed != null && changed.getCoverArt() != null) pendingArt = changed.getCoverArt();
                }

                @Override
                public void onPositionChanged(int changed) {
                    synchronized (lock) {
                        position = changed;
                        lastUpdate = System.currentTimeMillis();
                    }
                }

                @Override
                public void onPlayBackChanged(boolean changed) {
                    synchronized (lock) {
                        playing = changed;
                        lastUpdate = System.currentTimeMillis();
                    }
                }

                @Override
                public void onSync() {
                }

                @Override
                public void onDisconnect(Exception exception) {
                    connected = false;
                }
            });
            created.initialize();
            api = created;
            connected = true;
        } catch (Throwable ignored) {
            // Spotify is not open (or not supported here): we try again in a few seconds
            connected = false;
        }
    }

    private record Snap(String title, String artist, int seconds, int duration, boolean playing, long musicMs, boolean has) {
    }

    private Snap snapshot() {
        synchronized (lock) {
            if (track == null) return new Snap("Not playing", connected ? "Press play in Spotify" : "Open the Spotify app", 0, 0, false, 0L, false);
            int duration = track.getLength() / 1000;
            long elapsed = playing ? System.currentTimeMillis() - lastUpdate : 0L;
            int seconds = position / 1000;
            if (playing) seconds = Math.min(seconds + (int) (elapsed / 1000), duration);
            String title = track.getName() == null ? "" : track.getName();
            String artist = track.getArtist() == null ? "" : track.getArtist();
            return new Snap(title, artist, seconds, duration, playing, position + elapsed, true);
        }
    }

    // ------------------------------------------------------------------ the cover

    private void uploadArt() {
        BufferedImage image = pendingArt;
        if (image == null) return;
        pendingArt = null;
        try {
            int w = image.getWidth(), h = image.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            long sr = 0, sg = 0, sb = 0, sw = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = image.getRGB(x, y);
                    img.setColorArgb(x, y, 0xFF000000 | argb);
                    if (((x | y) & 3) == 0) {
                        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                        int weight = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) + 12;
                        sr += (long) r * weight;
                        sg += (long) g * weight;
                        sb += (long) b * weight;
                        sw += weight;
                    }
                }
            }
            if (sw > 0) {
                int r = (int) (sr / sw), g = (int) (sg / sw), b = (int) (sb / sw);
                int max = Math.max(r, Math.max(g, b));
                if (max < 170 && max > 0) {
                    float k = 170f / max;
                    r = Math.min(255, (int) (r * k));
                    g = Math.min(255, (int) (g * k));
                    b = Math.min(255, (int) (b * k));
                }
                albumColor = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            try {
                mc.getTextureManager().destroyTexture(ART_ID);
            } catch (Throwable ignored) {
            }
            mc.getTextureManager().registerTexture(ART_ID, new NativeImageBackedTexture(() -> "blockoutlines-spotify-art", img));
            artW = w;
            artH = h;
            hasArt = true;
        } catch (Throwable t) {
            hasArt = false;
        }
    }

    private int accent() {
        return switch (colorMode.get()) {
            case Spotify -> GREEN;
            case Custom -> argb(customColor.get());
            default -> hasArt ? albumColor : GREEN;
        };
    }

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.getWindow() == null || mc.options.hudHidden) return;
        if (!connected && !connecting.get() && System.currentTimeMillis() - lastTry > 8000) tryConnect();
        uploadArt();

        Snap snap = snapshot();
        boolean editing = HudHook.editing();
        if (hideIdle.get() && !snap.has() && !editing) return;

        boolean small = compact.get();
        boolean art = showArt.get();
        boolean eq = showEq.get() && !small;
        boolean bar = showProgress.get();

        float s = scale.get() / 100f;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int artUnit = small ? ART_SMALL : ART;
        int hUnits = PAD + artUnit + (eq ? 8 + EQ_H : 0) + (bar ? (small ? 8 : 17) : 0) + PAD;
        int w = px(panelWidth.get(), s), h = px(hUnits, s);

        int x = resolveX(sw, w), y = resolveY(sh, h);
        HudHook.mark("Spotify HUD", x, y, w, h);
        handleDrag(x, y, w, h, sw, sh);
        x = resolveX(sw, w);
        y = resolveY(sh, h);

        int accent = accent();
        int pad = px(PAD, s);
        int artSize = px(artUnit, s);
        int radius = Math.max(3, px(RADIUS, s));
        int bgAlpha = Math.round(255 * background.get() / 100f);

        DrawContext ctx = event.drawContext;
        RoundRect.outlined(ctx, x, y, w, h, radius, (bgAlpha << 24) | 0x0A0A0E, (0x26 << 24) | 0xFFFFFF);

        int artX = x + pad, artY = y + pad;
        if (art) {
            RoundRect.draw(ctx, artX, artY, artSize, artSize, Math.max(2, px(6, s)), ART_PLACEHOLDER);
            if (hasArt) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ART_ID, artX, artY, 0f, 0f, artSize, artSize, artW, artH, artW, artH, 0xFFFFFFFF);
            }
        }

        int infoX = art ? artX + artSize + px(10, s) : artX;
        int infoW = Math.max(1, x + w - pad - infoX);

        String status = !snap.has() ? "SPOTIFY" : snap.playing() ? "NOW PLAYING" : "PAUSED";
        text(ctx, status, infoX, artY + px(1, s), accent, s * 0.85f);

        if (!snap.title().equals(lastTitle)) {
            lastTitle = snap.title();
            scrollX = 0;
        }
        drawTitle(ctx, snap.title(), infoX, artY + px(small ? 11 : 17, s), infoW, s);
        if (!snap.artist().isEmpty()) {
            text(ctx, clip(snap.artist(), infoW, s * 0.9f), infoX, artY + px(small ? 22 : 30, s), ARTIST, s * 0.9f);
        }

        int cursor = y + pad + artSize;
        if (eq) {
            int eqTop = cursor + px(8, s);
            int eqH = px(EQ_H, s);
            drawEqualizer(ctx, x + pad, eqTop + eqH, w - pad * 2, eqH, snap, s, accent);
            cursor = eqTop + eqH;
        }
        if (bar) {
            if (small) drawThinBar(ctx, x + pad, cursor + px(5, s), w - pad * 2, snap, s, accent);
            else drawProgress(ctx, x, cursor + px(11, s), w, pad, snap, s, accent);
        }
    }

    private void drawTitle(DrawContext ctx, String title, int x, int y, int maxWidth, float s) {
        if (title.isEmpty()) return;
        int titleWidth = (int) (mc.textRenderer.getWidth(title) * s);
        if (titleWidth <= maxWidth) {
            scrollX = 0;
            text(ctx, title, x, y, TITLE, s);
            return;
        }
        long now = System.nanoTime();
        float delta = scrollNanos == 0L ? 0f : Math.min(0.1f, (now - scrollNanos) / 1.0e9f);
        scrollNanos = now;
        scrollX += 28f * delta * s;
        if (scrollX > titleWidth + px(24, s)) scrollX = 0;

        ctx.enableScissor(x, y - px(2, s), x + maxWidth, y + px(12, s));
        text(ctx, title, x - (int) scrollX, y, TITLE, s);
        text(ctx, title, x - (int) scrollX + titleWidth + px(24, s), y, TITLE, s);
        ctx.disableScissor();
    }

    private void drawEqualizer(DrawContext ctx, int x, int bottom, int innerWidth, int maxH, Snap snap, float s, int accent) {
        int gap = Math.max(1, px(2, s));
        int barW = Math.max(1, px(3, s));
        int unit = barW + gap;
        int count = Math.max(1, Math.min(EQ_MAX, innerWidth / unit));
        int used = count * unit - gap;
        int startX = x + (innerWidth - used) / 2;

        tickBars(snap.playing(), count, snap.musicMs(), maxH, eqSensitivity.get());

        int cap = lighten(accent, 0.45f);
        for (int i = 0; i < count; i++) {
            int bh = Math.max(1, Math.min(maxH, (int) barHeights[i]));
            int bx = startX + i * unit;
            ctx.fill(bx, bottom - bh, bx + barW, bottom, accent);
            ctx.fill(bx, bottom - bh, bx + barW, bottom - bh + 1, cap);
        }
    }

    private void tickBars(boolean isPlaying, int count, long musicMs, int maxPixels, double sensitivity) {
        if (!isPlaying) {
            for (int i = 0; i < count; i++) barHeights[i] += (2f - barHeights[i]) * 0.2f;
            return;
        }
        double beatMs = 60000.0 / 125.0;
        double halfMs = beatMs / 2.0;
        double beatPhase = (musicMs % (long) beatMs) / beatMs;
        double halfPhase = (musicMs % (long) halfMs) / halfMs;
        float kick = (float) Math.exp(-3.4 * beatPhase);
        float hat = (float) (Math.exp(-6.0 * halfPhase) * 0.55);
        float seconds = musicMs / 1000f;
        int denom = Math.max(1, count - 1);
        for (int i = 0; i < count; i++) {
            float centre = 1f - Math.abs((i / (float) denom) * 2f - 1f);
            float freq = (6f + (1f - centre) * 16f) * barJitter[i];
            float osc = (float) (Math.sin(seconds * freq + barPhase[i]) * 0.5 + 0.5);
            float energy = centre * kick + (1f - centre) * hat + osc * (0.30f + 0.45f * (1f - centre));
            float target = 2f + Math.min(1.4f, energy) * (float) sensitivity * (maxPixels - 2);
            barHeights[i] += (target - barHeights[i]) * 0.35f;
        }
    }

    private void drawProgress(DrawContext ctx, int x, int rowY, int width, int pad, Snap snap, float s, int accent) {
        String elapsed = fmt(snap.seconds());
        String total = snap.duration() > 0 ? fmt(snap.duration()) : "0:00";
        int elapsedW = (int) (mc.textRenderer.getWidth(elapsed) * s * 0.85f);
        int totalW = (int) (mc.textRenderer.getWidth(total) * s * 0.85f);
        text(ctx, elapsed, x + pad, rowY, TIME, s * 0.85f);
        text(ctx, total, x + width - pad - totalW, rowY, TIME, s * 0.85f);

        int trackX = x + pad + elapsedW + px(8, s);
        int trackEnd = x + width - pad - totalW - px(8, s);
        int barY = rowY - px(2, s);
        drawBar(ctx, trackX, barY, trackEnd - trackX, snap, s, accent);
    }

    private void drawThinBar(DrawContext ctx, int x, int y, int width, Snap snap, float s, int accent) {
        drawBar(ctx, x, y, width, snap, s, accent);
    }

    private void drawBar(DrawContext ctx, int x, int y, int width, Snap snap, float s, int accent) {
        int bh = Math.max(1, px(3, s));
        if (width <= px(10, s)) return;
        RoundRect.draw(ctx, x, y, width, bh, bh / 2, TRACK);
        if (snap.duration() <= 0) return;
        float pct = Math.min(1f, (float) snap.seconds() / snap.duration());
        int filled = Math.max(bh, (int) (width * pct));
        RoundRect.draw(ctx, x, y, filled, bh, bh / 2, accent);
        int knob = bh + Math.max(1, px(2, s));
        int kx = Math.min(x + width - knob, x + filled - knob / 2);
        RoundRect.draw(ctx, kx, y + bh / 2 - knob / 2, knob, knob, knob / 2, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ dragging (hub or chat open)

    private void handleDrag(int x, int y, int w, int h, int sw, int sh) {
        if (!HudHook.editing()) {
            dragging = false;
            return;
        }
        double mx = mc.mouse.getX() * sw / (double) mc.getWindow().getWidth();
        double my = mc.mouse.getY() * sh / (double) mc.getWindow().getHeight();
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!down) {
            dragging = false;
            return;
        }
        if (!dragging) {
            if (mx < x || mx > x + w || my < y || my > y + h) return;
            dragging = true;
            grabX = mx - x;
            grabY = my - y;
        }
        hudX.set(Math.max(0, Math.min(sw - w, (int) (mx - grabX))));
        hudY.set(Math.max(0, Math.min(sh - h, (int) (my - grabY))));
    }

    // ------------------------------------------------------------------ helpers

    private int resolveX(int sw, int w) {
        int v = hudX.get();
        if (v < 0) return Math.max(0, sw - w - 4);
        return Math.max(0, Math.min(sw - w, v));
    }

    private int resolveY(int sh, int h) {
        int v = hudY.get();
        if (v < 0) return 4;
        return Math.max(0, Math.min(sh - h, v));
    }

    private String clip(String value, int maxWidth, float s) {
        if (mc.textRenderer.getWidth(value) * s <= maxWidth) return value;
        String out = value;
        while (!out.isEmpty() && mc.textRenderer.getWidth(out + "...") * s > maxWidth) out = out.substring(0, out.length() - 1);
        return out + "...";
    }

    private void text(DrawContext ctx, String value, int x, int y, int argb, float s) {
        if (value == null || value.isEmpty()) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);
        ctx.drawText(mc.textRenderer, value, 0, 0, argb, true);
        ctx.getMatrices().popMatrix();
    }

    private static String fmt(int seconds) {
        if (seconds < 0) seconds = 0;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static int px(int value, float s) {
        return Math.max(1, Math.round(value * s));
    }

    private static int argb(SettingColor c) {
        return 0xFF000000 | (c.r << 16) | (c.g << 8) | c.b;
    }

    private static int lighten(int argb, float k) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * k);
        g += (int) ((255 - g) * k);
        b += (int) ((255 - b) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
