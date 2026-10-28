package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Song lyrics as real 3D text in the world (or on the sky). The text is rasterized once per line and drawn as colored
 * quads, so it has true perspective, hides behind blocks, and you can walk around it. The current line is up front,
 * the next one rises from below and the old one drifts up and fades. Lyrics come from LRCLIB (free, synced) using the
 * track playing in the desktop Spotify app.
 */
public class SkyLyrics extends Module {
    private record Line(long ms, String text) {
    }

    private static final int FONT_PX = 40;
    private static final int PAD = 10;
    private static final double[] LEVEL_ALPHA = {0, 0.40, 0.75, 1.0};

    /** One rasterized line: quads grouped per word, plus a soft blurred copy used as a glow. */
    private static final class Raster {
        int w, h;
        List<int[]>[] words;
        double[] from, to;
        List<int[]> glow;
        int glowScale = 2;
    }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Boolean> inWorld = sg.add(new BoolSetting.Builder().name("in-world-3d")
        .description("ON: the text hangs in the world where the song started (walk around it). OFF: it follows you like a sky texture.").defaultValue(true).build());
    private final Setting<Boolean> throughWalls = sg.add(new BoolSetting.Builder().name("through-walls")
        .description("Show the text even behind blocks.").defaultValue(false).build());
    private final Setting<Boolean> recenter = sg.add(new BoolSetting.Builder().name("recenter-on-new-song")
        .description("Puts the text in front of you every time a new song starts.").defaultValue(true).build());
    private final Setting<Integer> offsetMs = sg.add(new IntSetting.Builder().name("sync-offset-ms")
        .description("Positive = the words come earlier, negative = later. If the words are late, raise it.").defaultValue(0).range(-10000, 10000).sliderRange(-5000, 5000).build());
    private final Setting<Integer> riseMs = sg.add(new IntSetting.Builder().name("rise-time-ms")
        .description("How long a new line takes to rise.").defaultValue(450).sliderRange(100, 1500).build());
    private final Setting<Boolean> showNext = sg.add(new BoolSetting.Builder().name("show-next-line")
        .description("The faded next line waiting below.").defaultValue(true).build());
    private final Setting<Boolean> showPrevious = sg.add(new BoolSetting.Builder().name("show-previous-line")
        .description("The old line that drifts up and fades.").defaultValue(true).build());

    private final Setting<Double> size = sgLook.add(new DoubleSetting.Builder().name("size").defaultValue(1.4).range(0.3, 6).sliderRange(0.5, 4).build());
    private final Setting<Double> height = sgLook.add(new DoubleSetting.Builder().name("height-degrees")
        .description("How high above the horizon the current line is.").defaultValue(16).range(-10, 70).sliderRange(0, 50).build());
    private final Setting<Double> gap = sgLook.add(new DoubleSetting.Builder().name("line-gap-degrees")
        .description("Space between lines.").defaultValue(9).range(3, 30).sliderRange(4, 18).build());
    private final Setting<Double> distance = sgLook.add(new DoubleSetting.Builder().name("distance")
        .defaultValue(40).range(8, 120).sliderRange(15, 80).build());
    private final Setting<String> fontName = sgLook.add(new StringSetting.Builder().name("font")
        .description("A font installed on your computer.").defaultValue("Segoe UI").build());
    private final Setting<SettingColor> color = sgLook.add(new ColorSetting.Builder().name("color")
        .defaultValue(new SettingColor(255, 255, 255, 255)).build());
    private final Setting<Boolean> karaoke = sgLook.add(new BoolSetting.Builder().name("karaoke")
        .description("Words light up one by one as they are sung.").defaultValue(true).build());
    private final Setting<Double> unsung = sgLook.add(new DoubleSetting.Builder().name("unsung-brightness")
        .description("How bright the words not yet sung are.").defaultValue(0.42).range(0.1, 1).sliderRange(0.2, 0.8).build());
    private final Setting<Boolean> glow = sgLook.add(new BoolSetting.Builder().name("glow")
        .description("A soft colored haze around the current line.").defaultValue(true).build());
    private final Setting<Boolean> albumGlow = sgLook.add(new BoolSetting.Builder().name("glow-from-album")
        .description("The glow takes the color of the album cover.").defaultValue(true).build());
    private final Setting<SettingColor> glowColor = sgLook.add(new ColorSetting.Builder().name("glow-color")
        .defaultValue(new SettingColor(120, 170, 255, 255)).visible(() -> !albumGlow.get()).build());
    private final Setting<Boolean> sway = sgLook.add(new BoolSetting.Builder().name("float")
        .description("The text slowly floats like it hangs in the air.").defaultValue(true).build());

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private volatile List<Line> lines = List.of();
    private volatile String loadedFor = "";
    private volatile String status = "";
    private String wantedKey = "";
    private boolean fetching;

    private double cx, cy, cz;
    private double ox, oy, oz;
    private double anchorYaw;
    private boolean haveAnchor;
    private int lastIndex = -2;
    private long lastChange;
    private String lastShownKey = "";

    private final Map<String, Raster> cache = new HashMap<>();
    private Font font;
    private String fontKey = "";

    public SkyLyrics() {
        super(BlockOutlinesAddon.COSMETICS, "sky-lyrics", "Lyrics of the song playing in Spotify, as 3D text in the world.");
    }

    @Override
    public void onActivate() {
        haveAnchor = false;
        lastIndex = -2;
        lastShownKey = "";
    }

    @Override
    public void onDeactivate() {
        cache.clear();
    }

    // ------------------------------------------------------------------ lyrics download

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "BetterRender/1.0 (private Meteor addon)").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return res.statusCode() == 200 ? res.body() : null;
    }

    private void fetch(String key, String title, String artist, int durationSec) {
        fetching = true;
        Thread t = new Thread(() -> {
            List<Line> result = List.of();
            String st = "no lyrics found";
            try {
                String synced = null;
                String body = get("https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                    + (durationSec > 0 ? "&duration=" + durationSec : ""));
                if (body != null) synced = syncedOf(JsonParser.parseString(body));
                if (synced == null) {
                    body = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
                    if (body != null) {
                        JsonElement root = JsonParser.parseString(body);
                        if (root.isJsonArray()) {
                            JsonArray arr = root.getAsJsonArray();
                            double bestDiff = Double.MAX_VALUE;
                            for (JsonElement e : arr) {
                                String s = syncedOf(e);
                                if (s == null) continue;
                                double diff = 0;
                                JsonElement d = e.getAsJsonObject().get("duration");
                                if (durationSec > 0 && d != null && !d.isJsonNull()) diff = Math.abs(d.getAsDouble() - durationSec);
                                // the version whose length is closest to the song is the one with the right timing
                                if (diff < bestDiff) {
                                    bestDiff = diff;
                                    synced = s;
                                }
                            }
                        }
                    }
                }
                if (synced != null) {
                    result = parse(synced);
                    st = result.isEmpty() ? "no synced lyrics" : "ok";
                }
            } catch (Throwable e) {
                st = "download failed";
            }
            lines = result;
            status = st;
            loadedFor = key;
            fetching = false;
        }, "sky-lyrics");
        t.setDaemon(true);
        t.start();
    }

    private static String syncedOf(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement s = o.get("syncedLyrics");
        if (s == null || s.isJsonNull()) return null;
        String text = s.getAsString();
        return text == null || text.isBlank() ? null : text;
    }

    private static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            String l = raw.trim();
            if (l.length() < 7 || l.charAt(0) != '[') continue;
            int close = l.indexOf(']');
            if (close < 0) continue;
            try {
                String stamp = l.substring(1, close);
                int colon = stamp.indexOf(':');
                int min = Integer.parseInt(stamp.substring(0, colon));
                double sec = Double.parseDouble(stamp.substring(colon + 1));
                long ms = (long) (min * 60000L + sec * 1000.0);
                out.add(new Line(ms, l.substring(close + 1).trim()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ text -> quads

    private Font currentFont() {
        String name = fontName.get();
        if (font == null || !name.equals(fontKey)) {
            fontKey = name;
            Font f = new Font(name, Font.PLAIN, FONT_PX);
            if (!f.getFamily().equalsIgnoreCase(name)) f = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_PX);
            font = f;
            cache.clear();
        }
        return font;
    }

    private static int level(int alpha) {
        if (alpha < 40) return 0;
        if (alpha < 100) return 1;
        if (alpha < 180) return 2;
        return 3;
    }

    /** Merges equal-level pixels into rectangles (first across, then down). */
    @SuppressWarnings("unchecked")
    private static List<int[]>[] runs(int[] levels, int w, int h, int[] owner, int groups) {
        List<int[]>[] out = new List[groups];
        Map<Long, int[]>[] open = new Map[groups];
        for (int i = 0; i < groups; i++) {
            out[i] = new ArrayList<>();
            open[i] = new HashMap<>();
        }
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                int lvl = levels[y * w + x];
                if (lvl == 0) {
                    x++;
                    continue;
                }
                int o = owner[x];
                int x2 = x + 1;
                while (x2 < w && owner[x2] == o && levels[y * w + x2] == lvl) x2++;
                long key = ((long) x << 40) | ((long) (x2 - x) << 20) | lvl;
                int[] prev = open[o].get(key);
                if (prev != null && prev[1] + prev[3] == y) {
                    prev[3]++;
                } else {
                    int[] r = {x, y, x2 - x, 1, lvl};
                    out[o].add(r);
                    open[o].put(key, r);
                }
                x = x2;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Raster raster(String text) {
        Raster cached = cache.get(text);
        if (cached != null) return cached;
        if (cache.size() > 90) cache.clear();

        Font f = currentFont();
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        pg.setFont(f);
        FontMetrics fm = pg.getFontMetrics();
        int tw = Math.max(1, fm.stringWidth(text)), th = fm.getHeight(), asc = fm.getAscent();

        String[] words = text.split(" ");
        int wc = Math.max(1, words.length);
        int[] startX = new int[wc + 1];
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            startX[i] = PAD + fm.stringWidth(prefix.toString());
            prefix.append(words[i]).append(' ');
        }
        pg.dispose();
        int w = tw + PAD * 2, h = th + PAD * 2;
        startX[wc] = w;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(f);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, PAD, PAD + asc);
        g.dispose();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        int[] owner = new int[w];
        for (int x = 0; x < w; x++) {
            int o = 0;
            for (int i = 0; i < wc; i++) if (x >= startX[i]) o = i;
            owner[x] = o;
        }
        int[] levels = new int[w * h];
        for (int i = 0; i < px.length; i++) levels[i] = level(px[i] >>> 24);

        Raster r = new Raster();
        r.w = w;
        r.h = h;
        r.words = runs(levels, w, h, owner, wc);
        r.from = new double[wc];
        r.to = new double[wc];
        int chars = 0;
        for (String s : words) chars += s.length() + 1;
        int acc = 0;
        for (int i = 0; i < words.length; i++) {
            r.from[i] = acc / (double) Math.max(1, chars);
            acc += words[i].length() + 1;
            r.to[i] = acc / (double) Math.max(1, chars);
        }

        // glow: half resolution, blurred
        int gw = w / 2 + 1, gh = h / 2 + 1;
        float[] a = new float[gw * gh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) a[(y / 2) * gw + (x / 2)] += (px[y * w + x] >>> 24) / 1020f;
        }
        a = blur(a, gw, gh, 4);
        a = blur(a, gw, gh, 4);
        int[] gl = new int[gw * gh];
        for (int i = 0; i < gl.length; i++) {
            float v = Math.min(1f, a[i] * 2.4f);
            gl[i] = v < 0.08f ? 0 : 1 + Math.min(4, (int) (v * 5f));
        }
        r.glow = runs(gl, gw, gh, new int[gw], 1)[0];

        cache.put(text, r);
        return r;
    }

    private static float[] blur(float[] src, int w, int h, int rad) {
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        float inv = 1f / (rad * 2 + 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int k = -rad; k <= rad; k++) {
                    int xx = x + k;
                    if (xx >= 0 && xx < w) s += src[y * w + xx];
                }
                tmp[y * w + x] = s * inv;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int k = -rad; k <= rad; k++) {
                    int yy = y + k;
                    if (yy >= 0 && yy < h) s += tmp[yy * w + x];
                }
                out[y * w + x] = s * inv;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - x, 3);
    }

    private static double alphaFor(double pos, boolean next, boolean prev) {
        if (pos >= 0) return prev ? Math.max(0, 1 - pos) : (pos < 0.001 ? 1 : 0);
        if (!next) return Math.max(0, 1 + pos * 3.0);
        if (pos >= -1) return 1 + pos * 0.7;
        return Math.max(0, 0.3 * (2 + pos));
    }

    /** Draws rectangles of a raster. (px,py,pz) is the world position of the image's top-left corner. */
    private static void drawRuns(Renderer3D r, List<int[]> list, double px, double py, double pz, double rx, double rz, double k, double dy, Color[] cols) {
        for (int[] q : list) {
            Color c = cols[q[4]];
            if (c == null || c.a <= 2) continue;
            double x0 = q[0] * k, x1 = (q[0] + q[2]) * k;
            double y0 = py + dy - q[1] * k, y1 = py + dy - (q[1] + q[3]) * k;
            r.quad(px + rx * x0, y0, pz + rz * x0,
                px + rx * x1, y0, pz + rz * x1,
                px + rx * x1, y1, pz + rz * x1,
                px + rx * x0, y1, pz + rz * x0, c);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        Vec3d p = mc.player.getLerpedPos(event.tickDelta);
        cx = p.x;
        cy = p.y + mc.player.getEyeHeight(mc.player.getPose());
        cz = p.z;

        BetterSpotifyHud.Now now = BetterSpotifyHud.now();
        if (!now.has() || now.title().isEmpty()) return;
        String key = now.title() + "|" + now.artist();

        if (!key.equals(wantedKey)) {
            wantedKey = key;
            lines = List.of();
            status = "loading";
            loadedFor = "";
            lastIndex = -2;
            cache.clear();
        }
        if (!key.equals(loadedFor) && !fetching) fetch(key, now.title(), now.artist(), now.durationMs() / 1000);

        if (!haveAnchor || (recenter.get() && !key.equals(lastShownKey))) {
            anchorYaw = Math.toRadians(mc.player.getYaw());
            ox = cx;
            oy = cy;
            oz = cz;
            haveAnchor = true;
        }
        lastShownKey = key;

        List<Line> list = lines;
        if (list.isEmpty() || !key.equals(loadedFor)) return;

        long pos = now.positionMs() + offsetMs.get();
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).ms() <= pos) idx = i; else break;
        }
        long nowMs = System.currentTimeMillis();
        if (idx != lastIndex) {
            // a seek (jump of many lines) should not animate, only a normal step to the next line
            boolean step = idx == lastIndex + 1;
            lastIndex = idx;
            lastChange = step ? nowMs : nowMs - 100000L;
        }
        double s = ease((nowMs - lastChange) / (double) Math.max(50, riseMs.get()));
        if (!now.playing()) s = 1;

        Renderer3D r = throughWalls.get() ? event.renderer : event.depthRenderer;
        boolean w3 = inWorld.get();
        SettingColor base = color.get();
        double d = distance.get();
        int accent = albumGlow.get() ? now.albumColor() : glowColor.get().getPacked();
        int gr = (accent >> 16) & 0xFF, gg = (accent >> 8) & 0xFF, gb = accent & 0xFF;
        double time = nowMs / 1000.0;
        long firstMs = list.get(0).ms();
        double ryaw = anchorYaw;
        double rx = -Math.cos(ryaw), rz = -Math.sin(ryaw);   // to the viewer's right
        double nx = Math.sin(ryaw), nz = -Math.cos(ryaw);    // out of the text, toward the viewer

        for (int j = Math.max(-1, idx - 2); j <= idx + 2; j++) {
            if (j >= list.size()) continue;
            String str;
            if (j < 0) {
                if (firstMs < 2500) continue;
                str = "♪ ♪ ♪";
            } else {
                str = list.get(j).text();
                if (str.isEmpty()) {
                    if (j != idx) continue;
                    str = "♪ ♪ ♪";
                }
            }
            boolean current = j == idx || (j < 0 && idx < 0);
            double rel = (idx - j) - 1 + s;
            double a = alphaFor(rel, showNext.get(), showPrevious.get());
            if (a <= 0.02) continue;
            double aClamped = Math.max(0, Math.min(1, a));

            // how much of the line is already sung
            double prog;
            if (j < idx) prog = 1;
            else if (j > idx) prog = 0;
            else if (j < 0) prog = 0;
            else {
                long t0 = list.get(j).ms();
                long t1 = j + 1 < list.size() ? list.get(j + 1).ms() : t0 + 4000;
                double dur = Math.max(600, Math.min(t1 - t0, 9000)) * 0.85;
                prog = Math.max(0, Math.min(1, (pos - t0) / dur));
            }
            if (j < 0) prog = idx < 0 ? 0.0 : 1.0;
            if (!karaoke.get()) prog = current || j < idx ? 1 : 0;

            double elev = Math.toRadians(height.get() - rel * gap.get());
            double yawOff = 0;
            if (sway.get() && Math.abs(rel) < 1.6) {
                yawOff = Math.sin(time * 0.7 + j * 0.9) * Math.toRadians(0.9);
                elev += Math.sin(time * 0.9 + j * 1.3) * Math.toRadians(0.5);
            }
            double yawA = anchorYaw + yawOff;
            double bx = w3 ? ox : cx, by = w3 ? oy : cy, bz = w3 ? oz : cz;
            double wx = bx - Math.sin(yawA) * Math.cos(elev) * d;
            double wy = by + Math.sin(elev) * d;
            double wz = bz + Math.cos(yawA) * Math.cos(elev) * d;

            // only from the front
            if ((cx - wx) * nx + (cz - wz) * nz <= 0) continue;

            boolean notes = str.startsWith("♪");
            double ls = 1.0 - 0.22 * Math.min(1.0, Math.abs(rel));
            if (notes) ls *= 1.0 + 0.06 * Math.sin(time * 4.0);

            Raster ras = raster(str);
            double lineBlocks = size.get() * 0.045 * d * ls;
            double k = lineBlocks / Math.max(1, ras.h - PAD * 2);
            // top-left corner of the image in the world
            double px = wx - rx * (ras.w / 2.0) * k;
            double py = wy + (ras.h / 2.0) * k;
            double pz = wz - rz * (ras.w / 2.0) * k;

            if (glow.get() && current && aClamped > 0.3) {
                Color[] gc = new Color[6];
                for (int i = 1; i <= 5; i++) gc[i] = new Color(gr, gg, gb, (int) (255 * 0.5 * (i / 5.0) * aClamped));
                // a little behind the text so the text always wins
                double bxo = -Math.sin(ryaw) * 0.04, bzo = Math.cos(ryaw) * 0.04;
                drawRuns(r, ras.glow, px + bxo, py, pz + bzo, rx, rz, k * ras.glowScale, 0, gc);
            }

            double lineH = ras.h * k;
            for (int wi = 0; wi < ras.words.length; wi++) {
                double f;
                if (notes) f = 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(time * 4.0));
                else if (prog >= 0.999) f = 1;
                else if (prog <= 0) f = 0;
                else f = ease((prog - ras.from[wi]) / Math.max(1e-6, ras.to[wi] - ras.from[wi]));
                double bright = unsung.get() + (1 - unsung.get()) * f;
                if (notes) bright = f;
                int al = (int) (base.a * aClamped * bright);
                Color[] cols = new Color[4];
                for (int lv = 1; lv <= 3; lv++) cols[lv] = new Color(base.r, base.g, base.b, (int) (al * LEVEL_ALPHA[lv]));
                // words not sung yet sit a little lower and rise as they light up
                double lift = notes ? 0 : -(1 - f) * lineH * 0.10;
                drawRuns(r, ras.words[wi], px, py, pz, rx, rz, k, lift, cols);
            }
        }
    }

    @Override
    public String getInfoString() {
        return status.isEmpty() || status.equals("ok") ? null : status;
    }
}
