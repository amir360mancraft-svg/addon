package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Song lyrics written on the sky. The current line sits up in the sky, the next one rises from below and the
 * old one drifts up and fades. The text is fixed in the world (it turns with your head like a sky texture).
 * Lyrics come from LRCLIB (free, synced) using the track playing in the desktop Spotify app.
 */
public class SkyLyrics extends Module {
    private record Line(long ms, String text) {
    }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgLook = settings.createGroup("Look");

    private final Setting<Boolean> inWorld = sg.add(new BoolSetting.Builder().name("in-world-3d")
        .description("ON: real 3D text hanging in the world where the song started (walk around it, it gets bigger when you come close). OFF: flat sky style that follows you.").defaultValue(true).build());
    private final Setting<Boolean> recenter = sg.add(new BoolSetting.Builder().name("recenter-on-new-song")
        .description("Puts the text in front of you every time a new song starts.").defaultValue(true).build());
    private final Setting<Integer> offsetMs = sg.add(new IntSetting.Builder().name("sync-offset-ms")
        .description("Positive = the words come earlier, negative = later.").defaultValue(0).sliderRange(-2000, 2000).build());
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
    private final Setting<SettingColor> color = sgLook.add(new ColorSetting.Builder().name("color")
        .defaultValue(new SettingColor(255, 255, 255, 255)).build());
    private final Setting<Boolean> karaoke = sgLook.add(new BoolSetting.Builder().name("karaoke")
        .description("Words light up one by one as they are sung.").defaultValue(true).build());
    private final Setting<Double> unsung = sgLook.add(new DoubleSetting.Builder().name("unsung-brightness")
        .description("How bright the words not yet sung are.").defaultValue(0.42).range(0.1, 1).sliderRange(0.2, 0.8).build());
    private final Setting<Boolean> glow = sgLook.add(new BoolSetting.Builder().name("glow")
        .description("A soft colored glow around the current line.").defaultValue(true).build());
    private final Setting<Boolean> albumGlow = sgLook.add(new BoolSetting.Builder().name("glow-from-album")
        .description("The glow takes the color of the album cover.").defaultValue(true).build());
    private final Setting<SettingColor> glowColor = sgLook.add(new ColorSetting.Builder().name("glow-color")
        .defaultValue(new SettingColor(120, 170, 255, 255)).visible(() -> !albumGlow.get()).build());
    private final Setting<Boolean> sway = sgLook.add(new BoolSetting.Builder().name("float")
        .description("The text slowly floats like it hangs in the air.").defaultValue(true).build());
    private final Setting<Boolean> shadow = sgLook.add(new BoolSetting.Builder().name("shadow").defaultValue(true).build());

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Vector3d tmp = new Vector3d();

    private volatile List<Line> lines = List.of();
    private volatile String loadedFor = "";
    private volatile String status = "";
    private String wantedKey = "";
    private boolean fetching;

    private double cx, cy, cz;
    private double ox, oy, oz;
    private final Vector3d v1 = new Vector3d();
    private final Vector3d v2 = new Vector3d();
    private double anchorYaw;
    private boolean haveAnchor;
    private int lastIndex = -2;
    private long lastChange;
    private String lastShownKey = "";

    public SkyLyrics() {
        super(BlockOutlinesAddon.COSMETICS, "sky-lyrics", "Lyrics of the song playing in Spotify, written on the sky.");
    }

    @Override
    public void onActivate() {
        haveAnchor = false;
        lastIndex = -2;
        lastShownKey = "";
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
                            for (JsonElement e : arr) {
                                synced = syncedOf(e);
                                if (synced != null) break;
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

    // ------------------------------------------------------------------ drawing

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;
        Vec3d p = mc.player.getLerpedPos(event.tickDelta);
        cx = p.x;
        cy = p.y + mc.player.getEyeHeight(mc.player.getPose());
        cz = p.z;
    }

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return 1 - Math.pow(1 - x, 3);
    }

    private static double alphaFor(double pos, boolean next, boolean prev) {
        if (pos >= 0) return prev ? Math.max(0, 1 - pos) : (pos < 0.001 ? 1 : 0);
        if (!next) return Math.max(0, 1 + pos * 3.0) * 1.0;
        if (pos >= -1) return 1 + pos * 0.7;
        return Math.max(0, 0.3 * (2 + pos));
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        BetterSpotifyHud.Now now = BetterSpotifyHud.now();
        if (!now.has() || now.title().isEmpty()) return;
        String key = now.title() + "|" + now.artist();

        if (!key.equals(wantedKey)) {
            wantedKey = key;
            lines = List.of();
            status = "loading";
            loadedFor = "";
            lastIndex = -2;
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

        TextRenderer text = TextRenderer.get();
        boolean sh = shadow.get();
        SettingColor base = color.get();
        double d = distance.get();
        int accent = albumGlow.get() ? now.albumColor() : glowColor.get().getPacked();
        int gr = (accent >> 16) & 0xFF, gg = (accent >> 8) & 0xFF, gb = accent & 0xFF;
        double time = nowMs / 1000.0;
        long firstMs = list.get(0).ms();

        for (int j = Math.max(-1, idx - 2); j <= idx + 2; j++) {
            if (j >= list.size()) continue;
            String str;
            if (j < 0) {
                if (firstMs < 2500) continue;
                str = "\u266A \u266A \u266A";
            } else {
                str = list.get(j).text();
                if (str.isEmpty()) {
                    if (j != idx) continue;
                    str = "\u266A \u266A \u266A";
                }
            }
            boolean current = j == idx || (j < 0 && idx < 0);
            double rel = (idx - j) - 1 + s;
            double a = alphaFor(rel, showNext.get(), showPrevious.get());
            if (a <= 0.02) continue;

            // how much of the line is already sung
            double prog;
            if (j < idx) prog = 1;
            else if (j > idx) prog = 0;
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
            double dx = -Math.sin(yawA) * Math.cos(elev);
            double dy = Math.sin(elev);
            double dz = Math.cos(yawA) * Math.cos(elev);
            boolean w3 = inWorld.get();
            double bx = w3 ? ox : cx, by = w3 ? oy : cy, bz = w3 ? oz : cz;
            tmp.set(bx + dx * d, by + dy * d, bz + dz * d);

            double ls = 1.0 - 0.22 * Math.min(1.0, Math.abs(rel));
            // a note line pulses
            boolean notes = str.startsWith("\u266A");
            if (notes) ls *= 1.0 + 0.06 * Math.sin(time * 4.0);

            float m00 = 1, m01 = 0, m10 = 0, m11 = 1;
            if (w3) {
                // the text is a flat plane facing the point where the song started; we only draw it from the front
                double nx = Math.sin(anchorYaw), nz = -Math.cos(anchorYaw);
                if ((cx - tmp.x) * nx + (cz - tmp.z) * nz <= 0) continue;
                double fov = Math.toRadians(mc.options.getFov().getValue());
                double ws = mc.getWindow().calculateScaleFactor(1, false);
                double q = (mc.getWindow().getFramebufferHeight() / ws) / (2 * Math.tan(fov / 2) * d);
                double k = size.get() / q;           // world size of one text pixel
                double L = 64;
                double rx = -Math.cos(anchorYaw), rz = -Math.sin(anchorYaw);
                v1.set(tmp.x + rx * k * L, tmp.y, tmp.z + rz * k * L);
                v2.set(tmp.x, tmp.y - k * L, tmp.z);
                if (!NametagUtils.to2D(v1, size.get(), false) || !NametagUtils.to2D(v2, size.get(), false)) continue;
                if (!NametagUtils.to2D(tmp, size.get(), false)) continue;
                double mult = ls / NametagUtils.scale / L;
                m00 = (float) ((v1.x - tmp.x) * mult);
                m01 = (float) ((v1.y - tmp.y) * mult);
                m10 = (float) ((v2.x - tmp.x) * mult);
                m11 = (float) ((v2.y - tmp.y) * mult);
            } else {
                if (!NametagUtils.to2D(tmp, size.get() * ls)) continue;
            }

            NametagUtils.begin(tmp, event.drawContext);
            if (w3) {
                event.drawContext.getMatrices().mul(new Matrix3x2f(m00, m01, m10, m11, 0, 0));
                RenderSystem.getModelViewStack().mul(new Matrix4f(m00, m01, 0, 0, m10, m11, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1));
            }
            text.beginBig();
            double total = text.getWidth(str, sh);
            double lineH = text.getHeight(sh);
            double y0 = -lineH / 2.0;
            double aClamped = Math.max(0, Math.min(1, a));

            // glow: a few soft copies behind the current line
            if (glow.get() && current && aClamped > 0.3) {
                int ga = (int) (46 * aClamped);
                Color gc = new Color(gr, gg, gb, ga);
                double o = 2.6;
                text.render(str, -total / 2.0 - o, y0, gc, false);
                text.render(str, -total / 2.0 + o, y0, gc, false);
                text.render(str, -total / 2.0, y0 - o, gc, false);
                text.render(str, -total / 2.0, y0 + o, gc, false);
            }

            if (prog >= 0.999 || prog <= 0.0 || notes) {
                double k = notes ? 1.0 : prog;
                double bright = unsung.get() + (1 - unsung.get()) * k;
                if (notes) bright = 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(time * 4.0));
                Color c = new Color(base.r, base.g, base.b, (int) (base.a * aClamped * bright));
                text.render(str, -total / 2.0, y0, c, sh);
            } else {
                String[] words = str.split(" ");
                int chars = 0;
                for (String w : words) chars += w.length() + 1;
                int acc = 0;
                StringBuilder prefix = new StringBuilder();
                for (String w : words) {
                    double from = acc / (double) chars;
                    acc += w.length() + 1;
                    double to = acc / (double) chars;
                    double f = ease((prog - from) / Math.max(1e-6, to - from) );
                    double bright = unsung.get() + (1 - unsung.get()) * f;
                    double px = -total / 2.0 + text.getWidth(prefix.toString(), sh);
                    // words that are not sung yet sit a little lower and rise as they light up
                    double lift = (1 - f) * lineH * 0.12;
                    Color c = new Color(base.r, base.g, base.b, (int) (base.a * aClamped * bright));
                    text.render(w, px, y0 + lift, c, sh);
                    prefix.append(w).append(' ');
                }
            }
            text.end();
            NametagUtils.end(event.drawContext);
        }
    }

    @Override
    public String getInfoString() {
        return status.isEmpty() || status.equals("ok") ? null : status;
    }
}
