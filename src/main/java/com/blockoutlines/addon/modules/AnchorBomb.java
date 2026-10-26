package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * One key does a whole anchor bomb in four ticks: place a Respawn Anchor on the lowest good spot around the target,
 * put a Glowstone block between you and it as a shield, charge it, then set it off. Rotations are silent (only the
 * server sees your head turn). Works only outside the Nether. Hotbar needs: Respawn Anchor and Glowstone (2+ if the
 * shield is on). Do not hold Glowstone in your offhand (it would charge the anchor again instead of exploding it).
 */
public class AnchorBomb extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Keybind> key = sg.add(new KeybindSetting.Builder()
        .name("key").description("Press this key (while the module is on) to start the bomb.")
        .defaultValue(Keybind.none()).action(this::start).build());
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder()
        .name("target-range").description("Nearest player within this distance is the target.")
        .defaultValue(6.0).range(2.0, 12.0).sliderRange(3.0, 10.0).build());
    private final Setting<Double> placeRange = sg.add(new DoubleSetting.Builder()
        .name("place-range").description("How far you can reach to place and click.")
        .defaultValue(4.5).range(2.0, 6.0).sliderRange(3.0, 5.5).build());
    private final Setting<Boolean> shield = sg.add(new BoolSetting.Builder()
        .name("glowstone-shield").description("Place a Glowstone block between you and the anchor so you take less damage.")
        .defaultValue(true).build());
    private final Setting<Boolean> silent = sg.add(new BoolSetting.Builder()
        .name("silent-rotation").description("Look at each block on the server only; your own camera does not move.")
        .defaultValue(true).build());
    private final Setting<Integer> gap = sg.add(new IntSetting.Builder()
        .name("ticks-between-steps").description("1 = place, shield, charge and explode on 4 following ticks.")
        .defaultValue(1).range(1, 3).sliderRange(1, 3).build());
    private final Setting<Boolean> swapBack = sg.add(new BoolSetting.Builder()
        .name("swap-back").description("Go back to the slot you had before.").defaultValue(true).build());
    private final Setting<Boolean> noFriends = sg.add(new BoolSetting.Builder()
        .name("ignore-friends").description("Friends are never the target.").defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Show the anchor and shield spot.").defaultValue(true).build());
    private final Setting<SettingColor> anchorColor = sgRender.add(new ColorSetting.Builder()
        .name("anchor-color").description("Color of the anchor spot.").defaultValue(new SettingColor(180, 60, 255, 60)).visible(render::get).build());
    private final Setting<SettingColor> shieldColor = sgRender.add(new ColorSetting.Builder()
        .name("shield-color").description("Color of the shield spot.").defaultValue(new SettingColor(255, 210, 80, 60)).visible(render::get).build());

    private int stage = -1;       // -1 idle, 0 place, 1 shield, 2 charge, 3 explode
    private int wait;
    private BlockPos anchorPos, shieldPos;
    private int startSlot = -1;

    public AnchorBomb() {
        super(BlockOutlinesAddon.COMBAT, "anchor-bomb", "One key: anchor on the lowest good spot near the target, Glowstone shield, charge and explode in 4 ticks.");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        stage = -1;
        anchorPos = shieldPos = null;
        wait = 0;
    }

    // ------------------------------------------------------------------ start / plan

    private void start() {
        if (mc.player == null || mc.world == null || stage != -1) return;
        if (mc.world.getRegistryKey() == World.NETHER) {
            warning("Anchors do not explode in the Nether.");
            return;
        }
        FindItemResult anchor = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        FindItemResult glow = InvUtils.findInHotbar(Items.GLOWSTONE);
        if (!anchor.found()) { warning("No Respawn Anchor in your hotbar."); return; }
        if (!glow.found() || (shield.get() && glow.count() < 2)) {
            warning(shield.get() ? "You need at least 2 Glowstone in the hotbar (charge + shield)." : "No Glowstone in your hotbar.");
            return;
        }
        PlayerEntity target = findTarget();
        if (target == null) { warning("No target in range."); return; }
        BlockPos spot = findAnchorSpot(target);
        if (spot == null) { warning("No good place for the anchor."); return; }
        anchorPos = spot;
        shieldPos = shield.get() ? findShieldSpot(spot) : null;
        if (shield.get() && shieldPos == null) info("No room for the shield, continuing without it.");
        startSlot = mc.player.getInventory().getSelectedSlot();
        stage = 0;
        wait = 0;
    }

    private PlayerEntity findTarget() {
        PlayerEntity best = null;
        double bd = targetRange.get() * targetRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isDead() || p.isSpectator()) continue;
            if (noFriends.get() && Friends.get().isFriend(p)) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private boolean air(BlockPos p) {
        return mc.world.getBlockState(p).isReplaceable();
    }

    private boolean free(BlockPos p) {
        return air(p) && mc.world.getOtherEntities(null, new Box(p), e -> !e.isSpectator()).isEmpty();
    }

    private boolean reachable(BlockPos p) {
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= placeRange.get() * placeRange.get();
    }

    /** The lowest free block next to / under / above the target that has something to stand against, close enough for you. */
    private BlockPos findAnchorSpot(PlayerEntity t) {
        BlockPos feet = t.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!free(p) || !reachable(p)) continue;
                    if (BlockUtils.getPlaceSide(p) == null) continue;
                    // lowest first, then closest to the target, then closest to you
                    double score = p.getY() * 100.0 + Vec3d.ofCenter(p).squaredDistanceTo(t.getEntityPos()) * 5.0
                        + mc.player.squaredDistanceTo(Vec3d.ofCenter(p)) * 0.1;
                    // never pick the block you are standing in
                    if (p.equals(mc.player.getBlockPos()) || p.equals(mc.player.getBlockPos().up())) continue;
                    if (score < bestScore) { bestScore = score; best = p; }
                }
            }
        }
        return best;
    }

    /** The block on the side of the anchor that faces you. */
    private BlockPos findShieldSpot(BlockPos anchor) {
        Vec3d me = mc.player.getEntityPos();
        double dx = me.x - (anchor.getX() + 0.5), dz = me.z - (anchor.getZ() + 0.5), dy = mc.player.getEyeY() - (anchor.getY() + 0.5);
        Direction[] order;
        if (Math.abs(dx) >= Math.abs(dz)) {
            order = new Direction[]{dx > 0 ? Direction.EAST : Direction.WEST, dz > 0 ? Direction.SOUTH : Direction.NORTH};
        } else {
            order = new Direction[]{dz > 0 ? Direction.SOUTH : Direction.NORTH, dx > 0 ? Direction.EAST : Direction.WEST};
        }
        for (Direction d : order) {
            BlockPos p = anchor.offset(d);
            if (free(p) && reachable(p)) return p;
        }
        if (dy > 0.8) {
            BlockPos p = anchor.up();
            if (free(p) && reachable(p)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the four ticks

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (stage < 0) return;
        if (mc.player == null || mc.world == null) { reset(); return; }
        if (wait > 0) { wait--; return; }

        switch (stage) {
            case 0 -> placeBlock(anchorPos, Items.RESPAWN_ANCHOR, true);
            case 1 -> {
                if (shieldPos != null) placeBlock(shieldPos, Items.GLOWSTONE, false);
            }
            case 2 -> clickAnchor(true);
            case 3 -> clickAnchor(false);
            default -> { }
        }
        stage++;
        wait = gap.get() - 1;
        if (stage > 3) {
            if (swapBack.get() && startSlot >= 0) InvUtils.swap(startSlot, false);
            reset();
        }
    }

    private void placeBlock(BlockPos pos, net.minecraft.item.Item item, boolean isAnchor) {
        FindItemResult it = InvUtils.findInHotbar(item);
        if (!it.found()) return;
        // click against a neighbour (the anchor itself counts for the shield)
        Direction side = BlockUtils.getPlaceSide(pos);
        BlockPos neighbour;
        Vec3d hit = Vec3d.ofCenter(pos);
        if (side == null) {
            side = Direction.UP;
            neighbour = pos;
        } else {
            neighbour = pos.offset(side);
            hit = hit.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        }
        BlockHitResult bhr = new BlockHitResult(hit, side.getOpposite(), neighbour, false);
        final Vec3d aim = hit;
        final int slot = it.slot();
        Runnable act = () -> {
            InvUtils.swap(slot, false);
            BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
        };
        rotateThen(aim, act);
    }

    /** charge = right click with Glowstone; otherwise right click with something else to make it explode. */
    private void clickAnchor(boolean charge) {
        if (anchorPos == null) return;
        if (mc.world.getBlockState(anchorPos).getBlock() != Blocks.RESPAWN_ANCHOR) return;
        int slot;
        if (charge) {
            FindItemResult g = InvUtils.findInHotbar(Items.GLOWSTONE);
            if (!g.found()) return;
            slot = g.slot();
        } else {
            slot = nonGlowSlot();
        }
        if (slot < 0) return;
        Vec3d c = Vec3d.ofCenter(anchorPos);
        BlockHitResult bhr = new BlockHitResult(c, Direction.UP, anchorPos, false);
        final int s = slot;
        rotateThen(c, () -> {
            InvUtils.swap(s, false);
            BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
        });
    }

    /** A hotbar slot that does not hold Glowstone (an empty slot or the item you already had in hand). */
    private int nonGlowSlot() {
        int cur = mc.player.getInventory().getSelectedSlot();
        if (!mc.player.getInventory().getStack(cur).isOf(Items.GLOWSTONE)) return cur;
        for (int i = 0; i < 9; i++) {
            if (!mc.player.getInventory().getStack(i).isOf(Items.GLOWSTONE)) return i;
        }
        return -1;
    }

    private void rotateThen(Vec3d at, Runnable action) {
        if (silent.get()) {
            Rotations.rotate(Rotations.getYaw(at), Rotations.getPitch(at), 100, action);
        } else {
            action.run();
        }
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || stage < 0) return;
        if (anchorPos != null) event.renderer.box(anchorPos, anchorColor.get(), anchorColor.get(), ShapeMode.Both, 0);
        if (shieldPos != null) event.renderer.box(shieldPos, shieldColor.get(), shieldColor.get(), ShapeMode.Both, 0);
    }
}
