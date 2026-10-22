package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * The first-launch welcome wizard for Better Render.
 * Allows the user to pick a starting module collection (Casual, Balanced, Competitive),
 * customize the dark theme style and accent color, and explains the Right Shift hub shortcut.
 */
public class LunarWizardScreen extends Screen {
    private static final String[] STEP_TITLES = {
        "CHOOSE A MODULE COLLECTION",
        "CUSTOMIZE APPEARANCE",
        "YOU'RE ALL SET!"
    };

    private static final String[] STEP_SUBTITLES = {
        "Select a starting preset tailored to your style. You can toggle any module anytime.",
        "Choose your preferred dark theme style and accent highlight color.",
        "Better Render has been configured. Press Right Shift in-game to open your hub."
    };

    private static final String[] PRESET_NAMES = {"Midnight", "OLED", "Graphite", "Glass"};
    private static final int[][] PRESET_BG = {{14, 17, 24}, {0, 0, 0}, {28, 28, 33}, {30, 42, 66}};
    private static final int[][] PRESET_CARD = {{30, 36, 50}, {16, 16, 18}, {46, 46, 52}, {52, 66, 96}};

    private static final int[][] PRESET_PANEL_COLORS = {
        {14, 17, 24, 228}, {0, 0, 0, 245}, {26, 26, 30, 236}, {14, 17, 24, 150}
    };
    private static final int[][] PRESET_CARD_COLORS = {
        {22, 26, 36, 215}, {14, 14, 16, 238}, {40, 40, 46, 228}, {34, 42, 60, 120}
    };

    private static final Object[][] ACCENTS = LunarGuiTheme.PRESETS;

    private final Screen parent;
    private int step = 0; // 0 = collection, 1 = theme, 2 = finish

    // User choices
    private String selectedCollection = "default"; // "casual", "default", "competitive"
    private int selectedThemePreset = 0; // 0..3
    private int selectedAccent = 0;      // 0..7

    // Layout
    private int panelX, panelY, panelW, panelH;

    public LunarWizardScreen(Screen parent) {
        super(Text.literal("Better Render Setup"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(width - 32, 540);
        panelH = Math.min(height - 32, 340);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW_KEY_ESCAPE) {
            finishSetup();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Dim the background
        context.fill(0, 0, width, height, 0xC00A0D14);

        // Window background card
        fillRounded(context, panelX, panelY, panelW, panelH, 10, 0xF212151E);
        drawBorder(context, panelX, panelY, panelW, panelH, getAccentColor(140));

        // Header
        renderHeader(context);

        // Step content
        switch (step) {
            case 0 -> renderStep0(context, mouseX, mouseY);
            case 1 -> renderStep1(context, mouseX, mouseY);
            case 2 -> renderStep2(context, mouseX, mouseY);
        }

        // Footer buttons and progress dots
        renderFooter(context, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ Header

    private void renderHeader(DrawContext context) {
        // Brand tag
        context.drawTextWithShadow(textRenderer, "BETTER RENDER", panelX + 22, panelY + 18, getAccentColor(255));
        String versionTag = "v0.1.0 • Lunar Setup Guide";
        context.drawTextWithShadow(textRenderer, versionTag, panelX + panelW - textRenderer.getWidth(versionTag) - 22, panelY + 18, 0x8898A8C0);

        // Separator line
        context.fill(panelX + 22, panelY + 34, panelX + panelW - 22, panelY + 35, 0x22FFFFFF);

        // Step title & subtitle
        context.drawTextWithShadow(textRenderer, STEP_TITLES[step], panelX + 22, panelY + 44, 0xFFF0F4FC);
        context.drawTextWithShadow(textRenderer, STEP_SUBTITLES[step], panelX + 22, panelY + 58, 0xFF8E9CB2);
    }

    // ------------------------------------------------------------------ Step 0: Collections

    private void renderStep0(DrawContext context, int mouseX, int mouseY) {
        int contentY = panelY + 76;
        int gap = 12;
        int cardW = (panelW - 44 - gap * 2) / 3;
        int cardH = panelH - 146;

        // Card 1: Casual
        renderCollectionCard(context, panelX + 22, contentY, cardW, cardH,
            "Casual", "Visual Polish", "casual",
            new String[]{"• Better Outline", "• Custom Crosshair", "• Item Drop Physics", "• Jump Circles", "• Player Trail", "• Color Grading"},
            mouseX, mouseY);

        // Card 2: Balanced (Recommended)
        renderCollectionCard(context, panelX + 22 + cardW + gap, contentY, cardW, cardH,
            "Balanced", "Recommended", "default",
            new String[]{"• Better Outline", "• Dynamic Island", "• Hit & Kill Effects", "• Better Spotify HUD", "• Target HUD", "• Death Echo"},
            mouseX, mouseY);

        // Card 3: Competitive
        renderCollectionCard(context, panelX + 22 + (cardW + gap) * 2, contentY, cardW, cardH,
            "Competitive", "Combat Focus", "competitive",
            new String[]{"• Target HUD & Marker", "• Hotbar Item Counter", "• Crosshair Editor", "• Hit Particles", "• Clean Outlines", "• Low Overhead"},
            mouseX, mouseY);
    }

    private void renderCollectionCard(DrawContext context, int x, int y, int w, int h,
                                      String name, String badge, String id, String[] features,
                                      int mouseX, int mouseY) {
        boolean selected = selectedCollection.equals(id);
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;

        int bg = selected ? 0xFF1E2433 : (hovered ? 0xFF181D2A : 0xFF151822);
        fillRounded(context, x, y, w, h, 8, bg);

        int border = selected ? getAccentColor(240) : (hovered ? 0x66FFFFFF : 0x22FFFFFF);
        drawBorder(context, x, y, w, h, border);

        // Title
        context.drawTextWithShadow(textRenderer, name, x + 12, y + 12, selected ? 0xFFFFFFFF : 0xFFD8E0F0);

        // Badge
        int bw = textRenderer.getWidth(badge) + 8;
        int bx = x + w - bw - 10;
        int badgeBg = selected ? getAccentColor(80) : 0x33445566;
        fillRounded(context, bx, y + 10, bw, 13, 3, badgeBg);
        context.drawTextWithShadow(textRenderer, badge, bx + 4, y + 13, selected ? getAccentColor(255) : 0xFFB0C0D4);

        // Features list
        int fy = y + 36;
        for (String feat : features) {
            context.drawTextWithShadow(textRenderer, feat, x + 12, fy, 0xFF8C9BAF);
            fy += 13;
        }

        // Selection pill at bottom
        int pillH = 18;
        int pillY = y + h - pillH - 10;
        int pillBg = selected ? getAccentColor(220) : 0x22FFFFFF;
        fillRounded(context, x + 12, pillY, w - 24, pillH, 4, pillBg);
        String label = selected ? "SELECTED ✓" : "CHOOSE";
        int tw = textRenderer.getWidth(label);
        context.drawTextWithShadow(textRenderer, label, x + (w - tw) / 2, pillY + 5, selected ? 0xFF0B101D : 0xFFC0CAD8);
    }

    // ------------------------------------------------------------------ Step 1: Appearance

    private void renderStep1(DrawContext context, int mouseX, int mouseY) {
        int contentY = panelY + 80;

        // Theme presets section
        context.drawTextWithShadow(textRenderer, "THEME PRESET", panelX + 24, contentY, 0xFFDCE4F2);
        int gap = 10;
        int presetW = (panelW - 48 - gap * 3) / 4;
        int presetH = 68;
        int ty = contentY + 16;

        for (int i = 0; i < 4; i++) {
            int tx = panelX + 24 + i * (presetW + gap);
            boolean sel = selectedThemePreset == i;
            boolean hov = mouseX >= tx && mouseX <= tx + presetW && mouseY >= ty && mouseY <= ty + presetH;

            int bg = sel ? 0xFF222838 : (hov ? 0xFF1C2130 : 0xFF161922);
            fillRounded(context, tx, ty, presetW, presetH, 6, bg);
            drawBorder(context, tx, ty, presetW, presetH, sel ? getAccentColor(255) : (hov ? 0x66FFFFFF : 0x22FFFFFF));

            // Miniature preview box
            int[] cBg = PRESET_BG[i], cCard = PRESET_CARD[i];
            int px = tx + 10, py = ty + 10, pw = presetW - 20, ph = 26;
            context.fill(px, py, px + pw, py + ph, 0xFF000000 | (cBg[0] << 16) | (cBg[1] << 8) | cBg[2]);
            context.fill(px + 4, py + 4, px + pw - 4, py + ph - 4, 0xFF000000 | (cCard[0] << 16) | (cCard[1] << 8) | cCard[2]);
            // Accent dash inside preview
            context.fill(px + 8, py + 8, px + 22, py + 12, getAccentColor(255));

            // Name
            int tw = textRenderer.getWidth(PRESET_NAMES[i]);
            context.drawTextWithShadow(textRenderer, PRESET_NAMES[i], tx + (presetW - tw) / 2, ty + 46, sel ? 0xFFFFFFFF : 0xFFA0B0C4);
        }

        // Accent colors section
        int accentY = contentY + 104;
        context.drawTextWithShadow(textRenderer, "ACCENT COLOR", panelX + 24, accentY, 0xFFDCE4F2);

        int ay = accentY + 20;
        int circleRadius = 14;
        int circleGap = 16;
        int totalW = 8 * (circleRadius * 2) + 7 * circleGap;
        int startX = panelX + (panelW - totalW) / 2;

        for (int i = 0; i < 8; i++) {
            int cx = startX + i * (circleRadius * 2 + circleGap) + circleRadius;
            int cy = ay + circleRadius;
            boolean sel = selectedAccent == i;
            boolean hov = Math.hypot(mouseX - cx, mouseY - cy) <= circleRadius;

            Object[] acc = ACCENTS[i];
            int r = (Integer) acc[1], g = (Integer) acc[2], b = (Integer) acc[3];
            int color = 0xFF000000 | (r << 16) | (g << 8) | b;

            // Outer ring if selected
            if (sel) {
                drawCircleOutline(context, cx, cy, circleRadius + 3, 0xFFFFFFFF);
            } else if (hov) {
                drawCircleOutline(context, cx, cy, circleRadius + 2, 0x88FFFFFF);
            }

            // Filled circle
            fillCircle(context, cx, cy, circleRadius, color);
        }

        // Accent name below
        Object[] cur = ACCENTS[selectedAccent];
        String accentName = "Current: " + cur[0];
        int anw = textRenderer.getWidth(accentName);
        context.drawTextWithShadow(textRenderer, accentName, panelX + (panelW - anw) / 2, ay + circleRadius * 2 + 10, 0xFF90A4BE);
    }

    // ------------------------------------------------------------------ Step 2: Ready

    private void renderStep2(DrawContext context, int mouseX, int mouseY) {
        int contentY = panelY + 84;

        // Big active badge
        int badgeW = panelW - 48;
        int badgeH = 74;
        fillRounded(context, panelX + 24, contentY, badgeW, badgeH, 8, 0xFF161B26);
        drawBorder(context, panelX + 24, contentY, badgeW, badgeH, getAccentColor(160));

        // Checkmark badge
        fillRounded(context, panelX + 42, contentY + 16, 42, 42, 6, getAccentColor(40));
        drawBorder(context, panelX + 42, contentY + 16, 42, 42, getAccentColor(220));
        context.drawTextWithShadow(textRenderer, "✓", panelX + 57, contentY + 28, getAccentColor(255));

        // Text beside checkmark
        context.drawTextWithShadow(textRenderer, "LUNAR THEME ACTIVE", panelX + 96, contentY + 20, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, "Preset: " + PRESET_NAMES[selectedThemePreset] + "  •  Collection: " + selectedCollection.toUpperCase(),
            panelX + 96, contentY + 34, 0xFF96A6BC);
        context.drawTextWithShadow(textRenderer, "All Meteor settings and modules are fully preserved and styled.",
            panelX + 96, contentY + 47, 0xFF6C7C92);

        // Shortcut guide cards
        int guideY = contentY + 88;
        int gw = (badgeW - 12) / 2;
        int gh = 56;

        // Hub guide card
        fillRounded(context, panelX + 24, guideY, gw, gh, 6, 0xFF141822);
        drawBorder(context, panelX + 24, guideY, gw, gh, 0x22FFFFFF);
        context.drawTextWithShadow(textRenderer, "[ RIGHT SHIFT ]", panelX + 36, guideY + 12, getAccentColor(255));
        context.drawTextWithShadow(textRenderer, "Opens the Lunar Hub & HUD editor.", panelX + 36, guideY + 28, 0xFF8CA0B8);

        // Customize guide card
        fillRounded(context, panelX + 24 + gw + 12, guideY, gw, gh, 6, 0xFF141822);
        drawBorder(context, panelX + 24 + gw + 12, guideY, gw, gh, 0x22FFFFFF);
        context.drawTextWithShadow(textRenderer, "SETTINGS & BEHAVIOR", panelX + 36 + gw + 12, guideY + 12, 0xFFE0E8F4);
        context.drawTextWithShadow(textRenderer, "Tweak colors or re-run setup anytime.", panelX + 36 + gw + 12, guideY + 28, 0xFF8CA0B8);
    }

    // ------------------------------------------------------------------ Footer

    private void renderFooter(DrawContext context, int mouseX, int mouseY) {
        int footY = panelY + panelH - 42;

        // Progress dots in center
        int dotSpacing = 16;
        int dotTotalW = 2 * dotSpacing + 24;
        int dotStartX = panelX + (panelW - dotTotalW) / 2;

        for (int i = 0; i < 3; i++) {
            boolean active = step == i;
            int dw = active ? 16 : 7;
            int dh = 7;
            int dx = dotStartX + i * dotSpacing;
            int dy = footY + 8;
            fillRounded(context, dx, dy, dw, dh, 3, active ? getAccentColor(255) : 0x44FFFFFF);
        }

        // Back button (if step > 0)
        if (step > 0) {
            int bx = panelX + 22, bw = 70, bh = 24;
            boolean hov = mouseX >= bx && mouseX <= bx + bw && mouseY >= footY && mouseY <= footY + bh;
            fillRounded(context, bx, footY, bw, bh, 4, hov ? 0x44FFFFFF : 0x22FFFFFF);
            context.drawTextWithShadow(textRenderer, "← Back", bx + 16, footY + 8, 0xFFD0DCEB);
        }

        // Skip Tutorial button (if step < 2)
        if (step < 2) {
            int sx = panelX + (step > 0 ? 102 : 22), sw = 84, sh = 24;
            boolean hov = mouseX >= sx && mouseX <= sx + sw && mouseY >= footY && mouseY <= footY + sh;
            fillRounded(context, sx, footY, sw, sh, 4, hov ? 0x33FFFFFF : 0x18FFFFFF);
            context.drawTextWithShadow(textRenderer, "Skip Setup", sx + 12, footY + 8, 0xFF8E9EB4);
        }

        // Next / Finish button (Right)
        String nextLabel = step == 2 ? "FINISH & PLAY ✓" : "Next →";
        int nw = textRenderer.getWidth(nextLabel) + 24;
        int nx = panelX + panelW - nw - 22;
        int nh = 26;
        int ny = footY - 1;
        boolean hov = mouseX >= nx && mouseX <= nx + nw && mouseY >= ny && mouseY <= ny + nh;

        int nextBg = hov ? getAccentColor(255) : getAccentColor(210);
        fillRounded(context, nx, ny, nw, nh, 5, nextBg);
        context.drawTextWithShadow(textRenderer, nextLabel, nx + 12, ny + 9, 0xFF0B101D);
    }

    // ------------------------------------------------------------------ Input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);

        int mx = (int) click.x(), my = (int) click.y();
        int footY = panelY + panelH - 42;

        // Step 0 clicks: Collection cards
        if (step == 0) {
            int contentY = panelY + 76;
            int gap = 12;
            int cardW = (panelW - 44 - gap * 2) / 3;
            int cardH = panelH - 146;

            if (my >= contentY && my <= contentY + cardH) {
                if (mx >= panelX + 22 && mx <= panelX + 22 + cardW) {
                    selectedCollection = "casual";
                    return true;
                }
                if (mx >= panelX + 22 + cardW + gap && mx <= panelX + 22 + cardW * 2 + gap) {
                    selectedCollection = "default";
                    return true;
                }
                if (mx >= panelX + 22 + (cardW + gap) * 2 && mx <= panelX + 22 + (cardW + gap) * 2 + cardW) {
                    selectedCollection = "competitive";
                    return true;
                }
            }
        }

        // Step 1 clicks: Themes & Accents
        if (step == 1) {
            int contentY = panelY + 80;
            int gap = 10;
            int presetW = (panelW - 48 - gap * 3) / 4;
            int presetH = 68;
            int ty = contentY + 16;

            // Presets
            if (my >= ty && my <= ty + presetH) {
                for (int i = 0; i < 4; i++) {
                    int tx = panelX + 24 + i * (presetW + gap);
                    if (mx >= tx && mx <= tx + presetW) {
                        selectedThemePreset = i;
                        return true;
                    }
                }
            }

            // Accents
            int ay = contentY + 124;
            int circleRadius = 14;
            int circleGap = 16;
            int totalW = 8 * (circleRadius * 2) + 7 * circleGap;
            int startX = panelX + (panelW - totalW) / 2;

            for (int i = 0; i < 8; i++) {
                int cx = startX + i * (circleRadius * 2 + circleGap) + circleRadius;
                int cy = ay + circleRadius;
                if (Math.hypot(mx - cx, my - cy) <= circleRadius + 4) {
                    selectedAccent = i;
                    return true;
                }
            }
        }

        // Footer buttons:
        // Back
        if (step > 0) {
            int bx = panelX + 22, bw = 70, bh = 24;
            if (mx >= bx && mx <= bx + bw && my >= footY && my <= footY + bh) {
                step--;
                return true;
            }
        }

        // Skip
        if (step < 2) {
            int sx = panelX + (step > 0 ? 102 : 22), sw = 84, sh = 24;
            if (mx >= sx && mx <= sx + sw && my >= footY && my <= footY + sh) {
                finishSetup();
                return true;
            }
        }

        // Next / Finish
        String nextLabel = step == 2 ? "FINISH & PLAY ✓" : "Next →";
        int nw = textRenderer.getWidth(nextLabel) + 24;
        int nx = panelX + panelW - nw - 22;
        int nh = 26;
        int ny = footY - 1;

        if (mx >= nx && mx <= nx + nw && my >= ny && my <= ny + nh) {
            if (step < 2) {
                step++;
            } else {
                finishSetup();
            }
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    private void finishSetup() {
        // Select the Lunar theme in Meteor
        GuiThemes.select("Lunar");

        // Apply theme preset and accent to Lunar theme
        LunarGuiTheme lt = LunarGuiTheme.INSTANCE;
        if (lt != null) {
            lt.themePreset.set(selectedThemePreset);

            int[] p = PRESET_PANEL_COLORS[selectedThemePreset];
            int[] c = PRESET_CARD_COLORS[selectedThemePreset];
            lt.panelColor.set(new SettingColor(p[0], p[1], p[2], p[3]));
            lt.cardColor.set(new SettingColor(c[0], c[1], c[2], c[3]));

            Object[] acc = ACCENTS[selectedAccent];
            lt.applyAccent((Integer) acc[1], (Integer) acc[2], (Integer) acc[3]);
        }

        // Save choices to better-render-setup.properties
        LunarSetup.markDone(selectedCollection, selectedThemePreset, selectedAccent);

        // Close to parent or title screen
        mc.setScreen(parent != null ? parent : new TitleScreen());
    }

    // ------------------------------------------------------------------ Drawing Helpers

    private int getAccentColor(int alpha) {
        Object[] acc = ACCENTS[selectedAccent];
        int r = (Integer) acc[1], g = (Integer) acc[2], b = (Integer) acc[3];
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (r << 16) | (g << 8) | b;
    }

    private static void fillRounded(DrawContext context, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w / 2, h / 2));
        if (r <= 1) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        context.fill(x + r, y, x + w - r, y + h, color);
        context.fill(x, y + r, x + r, y + h - r, color);
        context.fill(x + w - r, y + r, x + w, y + h - r, color);
    }

    private static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y + 1, x + 1, y + h - 1, color);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static void fillCircle(DrawContext context, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            context.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, color);
        }
    }

    private static void drawCircleOutline(DrawContext context, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            context.fill(cx - dx - 1, cy + dy, cx - dx, cy + dy + 1, color);
            context.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }
}
