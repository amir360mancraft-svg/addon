package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.blockoutlines.addon.mixin.GameRendererInvoker;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Identifier;

/**
 * Changes the color saturation of the whole game world. It uses a vanilla post effect, which is applied before
 * the GUI is drawn, so menus, chat and the hotbar keep their normal colors.
 */
public class Saturation extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> amount = sgGeneral.add(new DoubleSetting.Builder()
        .name("amount")
        .description("Color saturation of the world. 1 is normal, 0 is black and white, higher is more vivid.")
        .defaultValue(1.5)
        .range(0.0, 3.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    public Saturation() {
        super(BlockOutlinesAddon.CATEGORY, "saturation", "Changes the color saturation of the game world without touching the GUI.");
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.gameRenderer == null) return;
        int level = (int) Math.round(Math.max(0.0, Math.min(3.0, amount.get())) * 10.0);
        // Set every tick: vanilla resets the post effect when the camera entity changes (joining a world, respawning...).
        ((GameRendererInvoker) mc.gameRenderer).blockoutlines$setPostProcessor(Identifier.of("blockoutlines", String.format("saturation_%02d", level)));
    }

    private void clear() {
        if (mc.gameRenderer != null) ((GameRendererInvoker) mc.gameRenderer).blockoutlines$clearPostProcessor();
    }
}
