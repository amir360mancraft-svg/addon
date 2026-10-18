package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.systems.modules.Module;

/** Stops rendering water (also waterlogged blocks). Water stays in the world, you just can't see it. */
public class NoWater extends Module {
    public NoWater() {
        super(BlockOutlinesAddon.SKY, "no-water", "Stops rendering water so you can see through it.");
    }

    @Override
    public void onActivate() {
        reloadWorld();
    }

    @Override
    public void onDeactivate() {
        reloadWorld();
    }

    // Chunks are cached as meshes, so they must be rebuilt for the change to show up.
    private void reloadWorld() {
        if (mc.worldRenderer != null && mc.world != null) mc.worldRenderer.reload();
    }
}
