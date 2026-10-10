package com.blockoutlines.addon;

import com.blockoutlines.addon.modules.BlockOutlines;
import com.blockoutlines.addon.modules.ColorGrading;
import com.blockoutlines.addon.modules.CustomCrosshair;
import com.blockoutlines.addon.modules.FakeElytra;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class BlockOutlinesAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Better Render");

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new BlockOutlines());
        Modules.get().add(new ColorGrading());
        Modules.get().add(new CustomCrosshair());
        Modules.get().add(new FakeElytra());
        LOG.info("Better Render loaded");
    }

    @Override
    public String getPackage() {
        return "com.blockoutlines.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return null; // set this if you publish the addon on GitHub
    }
}
