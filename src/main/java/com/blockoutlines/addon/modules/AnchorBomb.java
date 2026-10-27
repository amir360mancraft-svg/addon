package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
    public enum Priority { Closest, LowestHealth }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgSmart = settings.createGroup("Smart");
    private final SettingGroup sgAuto = settings.createGroup("Auto");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Keybind> key = sg.add(new KeybindSetting.Builder()
        .name("key").description("Press this key (while the module is on) to start the bomb.")
        .defaultValue(Keybind.none()).action(this::start).build());
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder()
        .name("target-range").description("The nearest player within this distance is the target. If nobody is there, the block you look at is used.")
        .defaultValue(12.0).range(2.0, 40.0).sliderRange(4.0, 30.0).build());
    private final Setting<Double> placeRange = sg.add(new DoubleSetting.Builder()
        .name("place-range").description("How far you can reach to place and click.")
        .defaultValue(4.5).range(2.0, 6.0).sliderRange(3.0, 5.5).build());
    private final Setting<Boolean> shield = sg.add(new BoolSetting.Builder()
        .name("glowstone-shield").description("Place a Glowstone block between you and the anchor so you take less damage.")
        .defaultValue(true).build());
    private final Setting<Boolean> blastFallback = sg.add(new BoolSetting.Builder()
        .name("blast-fallback").description("When there is no good place at all, just place the anchor (no shield) and explode it so space opens up for the next try.")
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

    private final Setting<Boolean> predict = sgSmart.add(new BoolSetting.Builder()
        .name("predict-target").description("Plan for where the target will be in 3 ticks, not where it is now.")
        .defaultValue(true).build());
    private final Setting<Priority> priority = sgSmart.add(new EnumSetting.Builder<Priority>()
        .name("target-priority").description("Closest = nearest player. Lowest health = the weakest player in range.")
        .defaultValue(Priority.Closest).build());
    private final Setting<Boolean> humanize = sgSmart.add(new BoolSetting.Builder()
        .name("humanize").description("Click a slightly different point of the face every time (still a valid click).")
        .defaultValue(true).build());
    private final Setting<Integer> bombs = sgAuto.add(new IntSetting.Builder()
        .name("bombs-per-press").description("How many bombs one key press does, one after the other (each on a new target spot).")
        .defaultValue(1).range(1, 6).sliderRange(1, 6).build());
    private final Setting<Integer> repeatDelay = sgAuto.add(new IntSetting.Builder()
        .name("ticks-between-bombs").description("Wait between two bombs.").defaultValue(6).range(2, 40).sliderRange(2, 20).visible(() -> bombs.get() > 1).build());
    private final Setting<Boolean> autoRefill = sgAuto.add(new BoolSetting.Builder()
        .name("auto-refill").description("When the hotbar has no anchor / not enough Glowstone, really open your inventory and move them to the hotbar with normal clicks, with small random pauses.")
        .defaultValue(true).build());
    private final Setting<Integer> refillDelay = sgAuto.add(new IntSetting.Builder()
        .name("refill-delay").description("Ticks between the inventory steps (a random 0-2 is added).").defaultValue(4).range(2, 12).sliderRange(2, 10).visible(autoRefill::get).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Show the anchor and shield spot.").defaultValue(true).build());
    private final Setting<SettingColor> anchorColor = sgRender.add(new ColorSetting.Builder()
        .name("anchor-color").description("Color of the anchor spot.").defaultValue(new SettingColor(180, 60, 255, 60)).visible(render::get).build());
    private final Setting<SettingColor> shieldColor = sgRender.add(new ColorSetting.Builder()
        .name("shield-color").description("Color of the shield spot.").defaultValue(new SettingColor(255, 210, 80, 60)).visible(render::get).build());

    private int stage = -1;       // -2 waiting for the next bomb, -1 idle, 0 place, 1 shield, 2 charge, 3 explode
    private int bombsLeft, gapWait, refillState = -1, refillWait;
    private boolean justRefilled;
    private final java.util.ArrayDeque<net.minecraft.item.Item> refillQueue = new java.util.ArrayDeque<>();
    private final java.util.Random rnd = new java.util.Random();
    private int wait;
    private BlockPos anchorPos, shieldPos;
    private int startSlot = -1;
    private final java.util.Map<java.util.UUID, Vec3d> lastPos = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Vec3d> speed = new java.util.HashMap<>();

    public AnchorBomb() {
        super(BlockOutlinesAddon.COMBAT, "anchor-bomb", "One key: anchor on the lowest good spot near the target, Glowstone shield, charge and explode in 4 ticks.");
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void trackPlayers() {
        for (PlayerEntity p : mc.world.getPlayers()) {
            Vec3d now = p.getEntityPos();
            Vec3d old = lastPos.put(p.getUuid(), now);
            if (old != null) speed.put(p.getUuid(), now.subtract(old));
        }
    }

    private void abort(String why) {
        warning(why);
        if (swapBack.get() && startSlot >= 0) InvUtils.swap(startSlot, false);
        reset();
    }

    private void reset() {
        stage = -1;
        refillState = -1;
        refillQueue.clear();
        bombsLeft = 0;
        justRefilled = false;
        anchorPos = shieldPos = null;
        wait = 0;
    }

    // ------------------------------------------------------------------ start / plan

    private int hotbarCount(net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            net.minecraft.item.ItemStack st = mc.player.getInventory().getStack(i);
            if (st.isOf(item)) n += st.getCount();
        }
        return n;
    }

    private boolean inInventory(net.minecraft.item.Item item) {
        for (int i = 9; i < 36; i++) if (mc.player.getInventory().getStack(i).isOf(item)) return true;
        return false;
    }

    private void start() {
        if (mc.player == null || mc.world == null || stage != -1 || refillState >= 0) return;
        bombsLeft = bombs.get();
        justRefilled = false;
        begin(true);
    }

    private void begin(boolean first) {
        if (mc.world.getRegistryKey() == World.NETHER) {
            warning("Anchors do not explode in the Nether.");
            bombsLeft = 0;
            return;
        }
        boolean needAnchor = hotbarCount(Items.RESPAWN_ANCHOR) < 1;
        boolean needGlow = hotbarCount(Items.GLOWSTONE) < (shield.get() ? 2 : 1);
        if (needAnchor || needGlow) {
            if (autoRefill.get() && !justRefilled
                && ((needAnchor && inInventory(Items.RESPAWN_ANCHOR)) || (needGlow && inInventory(Items.GLOWSTONE)))) {
                refillQueue.clear();
                if (needAnchor) refillQueue.add(Items.RESPAWN_ANCHOR);
                if (needGlow) refillQueue.add(Items.GLOWSTONE);
                refillState = 0;
                refillWait = 0;
                return;
            }
            warning(needAnchor ? "No Respawn Anchor in your hotbar." : (shield.get() ? "You need at least 2 Glowstone in the hotbar (charge + shield)." : "No Glowstone in your hotbar."));
            bombsLeft = 0;
            return;
        }
        justRefilled = false;
        PlayerEntity target = findTarget();
        Vec3d aimAt;
        BlockPos base;
        if (target != null) {
            aimAt = target.getEntityPos();
            base = target.getBlockPos();
        } else if (mc.crosshairTarget instanceof BlockHitResult hr && hr.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            aimAt = hr.getPos();
            base = hr.getBlockPos().offset(hr.getSide());
        } else {
            if (first) warning("No player in range and you are not looking at a block.");
            bombsLeft = 0;
            return;
        }
        if (target != null && predict.get()) {
            Vec3d v = speed.getOrDefault(target.getUuid(), Vec3d.ZERO);
            aimAt = target.getEntityPos().add(v.x * 3.0, 0, v.z * 3.0); // 3 ticks ahead, sideways only
            base = BlockPos.ofFloored(aimAt);
        }
        boolean noShield = false;
        BlockPos spot = findAnchorSpot(base, aimAt, 1, -2, 2, false, true, 1);
        if (spot == null) {
            // 2: the "floor" layout, anchor and Glowstone side by side on the ground a bit further away
            spot = findAnchorSpot(base, aimAt, 3, -3, 1, true, false, 2);
            if (spot != null) info("Using the floor layout.");
        }
        if (spot == null && blastFallback.get()) {
            // 3: no good place at all: put just the anchor anywhere legit and blow it, to open up a place
            spot = findAnchorSpot(base, aimAt, 4, -3, 3, false, false, 3);
            if (spot != null) { noShield = true; info("No good place: only the anchor is placed and blown to open up space."); }
        }
        if (spot == null) { warning("No good place for the anchor."); bombsLeft = 0; return; }
        anchorPos = spot;
        shieldPos = shield.get() && !noShield ? findShieldSpot(spot) : null; // no room = no shield, nothing is said
        startSlot = mc.player.getInventory().getSelectedSlot();
        stage = 0;
        wait = 0;
    }

    private PlayerEntity findTarget() {
        PlayerEntity best = null;
        double bestKey = Double.MAX_VALUE;
        double r2 = targetRange.get() * targetRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isDead() || p.isSpectator()) continue;
            if (noFriends.get() && Friends.get().isFriend(p)) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d > r2) continue;
            double key = priority.get() == Priority.LowestHealth ? p.getHealth() + p.getAbsorptionAmount() + d * 0.001 : d;
            if (key < bestKey) { bestKey = key; best = p; }
        }
        return best;
    }

    /** Where the server thinks your eyes are (last tick's position) and where they are now; both must be fine while you move. */
    private Vec3d[] eyes() {
        Vec3d now = mc.player.getEyePos();
        Vec3d before = now.subtract(mc.player.getVelocity());
        return new Vec3d[]{now, before};
    }

    private boolean air(BlockPos p) {
        return mc.world.getBlockState(p).isReplaceable();
    }

    private boolean free(BlockPos p) {
        return air(p) && mc.world.getOtherEntities(null, new Box(p), e -> !e.isSpectator()).isEmpty();
    }

    private boolean reachable(BlockPos p) {
        for (Vec3d e : eyes()) if (e.squaredDistanceTo(Vec3d.ofCenter(p)) > placeRange.get() * placeRange.get()) return false;
        return true;
    }

    /** Do not pick a spot that has bedrock right above it (roof / floor of the world). */
    private boolean underBedrock(BlockPos p) {
        for (int i = 1; i <= 2; i++) {
            if (mc.world.getBlockState(p.up(i)).isOf(Blocks.BEDROCK)) return true;
        }
        return p.getY() <= mc.world.getBottomY() + 1;
    }

    /** True when you can click the block this spot would be placed against, with nothing in the way (not through a wall). */
    private boolean canSee(BlockPos p) { return findFace(p, null, null) != null; }

    private boolean canSee(BlockPos p, BlockPos exclude) { return findFace(p, null, exclude) != null; }

    /** The face we click and the exact point on it. */
    private record Face(BlockPos neighbour, Direction side, Vec3d hit) {}

    /**
     * A legit way to place a block at p: click a face of a solid neighbour that you can reach and that you see directly
     * (the ray from your eyes hits exactly that face first, nothing in between). Null when there is none.
     */
    private Face findFace(BlockPos p, BlockPos assumeSolid, BlockPos exclude) {
        Vec3d[] es = eyes();
        Face best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = p.offset(d);
            if (n.equals(exclude)) continue; // clicking the anchor with Glowstone would charge it instead of placing a block
            boolean assumed = n.equals(assumeSolid);
            if (!assumed && mc.world.getBlockState(n).isReplaceable()) continue;
            Direction side = d.getOpposite(); // the face of the neighbour we click
            Vec3d face = Vec3d.ofCenter(p).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
            boolean ok = true;
            double dist = 0;
            for (Vec3d eye : es) {
                double dd = eye.squaredDistanceTo(face);
                dist = Math.max(dist, dd);
                if (dd > placeRange.get() * placeRange.get()) { ok = false; break; }
                Vec3d toEye = eye.subtract(face);
                if (side.getOffsetX() * toEye.x + side.getOffsetY() * toEye.y + side.getOffsetZ() * toEye.z <= 0) { ok = false; break; }
                Vec3d end = face.add(d.getOffsetX() * 0.02, d.getOffsetY() * 0.02, d.getOffsetZ() * 0.02); // a little into the neighbour
                net.minecraft.world.RaycastContext ctx = new net.minecraft.world.RaycastContext(eye, end,
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player);
                net.minecraft.util.hit.BlockHitResult r = mc.world.raycast(ctx);
                boolean hit = assumed ? r.getType() == net.minecraft.util.hit.HitResult.Type.MISS
                    : r.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && r.getBlockPos().equals(n) && r.getSide() == side;
                if (!hit) { ok = false; break; }
            }
            if (!ok) continue;
            if (dist < bestD) { bestD = dist; best = new Face(n, side, face); }
        }
        return best;
    }

    /** The lowest free block next to / under / above the target that has something to stand against, close enough for you. */
    private BlockPos findAnchorSpot(BlockPos feet, Vec3d aimAt, int radius, int dyMin, int dyMax, boolean floorOnly, boolean lowestFirst, int tier) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = dyMin; dy <= dyMax; dy++) {
                    BlockPos p = feet.add(dx, dy, dz);
                    if (!free(p) || !reachable(p) || !canSee(p) || underBedrock(p)) continue;
                    // lowest first, then closest to the target, then closest to you
                    if (floorOnly && mc.world.getBlockState(p.down()).isReplaceable()) continue;
                    // never pick the block you are standing in
                    if (p.equals(mc.player.getBlockPos()) || p.equals(mc.player.getBlockPos().up())) continue;
                    if (!clickable(p, null)) continue; // you must still be able to click the anchor itself
                    double score = (lowestFirst ? p.getY() * 100.0 : p.getY() * 2.0) + Vec3d.ofCenter(p).squaredDistanceTo(aimAt) * 5.0
                        + mc.player.squaredDistanceTo(Vec3d.ofCenter(p)) * 0.1;
                    if (score < bestScore) { bestScore = score; best = p; }
                }
            }
        }
        return best;
    }

    /**
     * The shield: the free block next to the anchor that sits between the anchor and you. It must be placeable the
     * legit way (not against the anchor), and after it is there you must still see a face of the anchor to charge and
     * explode it. If no spot is good, there is no shield.
     */
    private BlockPos findShieldSpot(BlockPos anchor) {
        Vec3d to = mc.player.getEntityPos().add(0, 1.0, 0).subtract(Vec3d.ofCenter(anchor));
        double len = to.length();
        if (len < 0.5) return null;
        Vec3d dir = to.multiply(1.0 / len);
        BlockPos best = null;
        double bestDot = 0.35; // it has to face you
        for (Direction d : Direction.values()) {
            double dot = d.getOffsetX() * dir.x + d.getOffsetY() * dir.y + d.getOffsetZ() * dir.z;
            if (dot <= bestDot) continue;
            BlockPos p = anchor.offset(d);
            if (!free(p) || !reachable(p) || !canSee(p, anchor)) continue;
            if (p.equals(mc.player.getBlockPos()) || p.equals(mc.player.getBlockPos().up())) continue;
            if (!clickable(anchor, p)) continue;
            bestDot = dot;
            best = p;
        }
        return best;
    }

    /** True when, with an optional shield block in place, a face of the anchor is visible and in reach from both eye positions. */
    private boolean clickable(BlockPos anchor, BlockPos shield) {
        Vec3d[] es = eyes();
        for (Direction d : Direction.values()) {
            Vec3d face = Vec3d.ofCenter(anchor).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
            Vec3d end = face.subtract(d.getOffsetX() * 0.02, d.getOffsetY() * 0.02, d.getOffsetZ() * 0.02);
            boolean ok = true;
            for (Vec3d eye : es) {
                if (eye.squaredDistanceTo(face) > placeRange.get() * placeRange.get()) { ok = false; break; }
                Vec3d toEye = eye.subtract(face);
                if (d.getOffsetX() * toEye.x + d.getOffsetY() * toEye.y + d.getOffsetZ() * toEye.z <= 0) { ok = false; break; }
                net.minecraft.util.hit.BlockHitResult r = net.minecraft.world.BlockView.raycast(eye, end, Boolean.TRUE, (ctx, pos) -> {
                    net.minecraft.util.shape.VoxelShape shape = (pos.equals(anchor) || pos.equals(shield))
                        ? net.minecraft.util.shape.VoxelShapes.fullCube()
                        : mc.world.getBlockState(pos).getCollisionShape(mc.world, pos);
                    return shape.raycast(eye, end, pos);
                }, ctx -> null);
                if (r == null || !r.getBlockPos().equals(anchor) || r.getSide() != d) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the four ticks

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { if (stage != -1 || refillState >= 0) reset(); return; }
        trackPlayers();
        if (refillState >= 0) { tickRefill(); return; }
        if (stage == -2) {
            if (--gapWait <= 0) { stage = -1; begin(false); }
            return;
        }
        if (stage < 0) return;
        if (wait > 0) { wait--; return; }
        // the anchor must really stand there (the server may have refused the click): otherwise stop
        if (stage >= 1 && (anchorPos == null || mc.world.getBlockState(anchorPos).getBlock() != Blocks.RESPAWN_ANCHOR)) {
            abort("The anchor is not there (the server did not accept it).");
            return;
        }

        switch (stage) {
            case 0 -> placeBlock(anchorPos, Items.RESPAWN_ANCHOR, true);
            case 1 -> {
                if (shieldPos != null) placeBlock(shieldPos, Items.GLOWSTONE, false);
            }
            case 2 -> clickAnchor(true);
            case 3 -> clickAnchor(false);
            default -> { }
        }
        if (stage < 0) return; // aborted inside the step
        stage++;
        wait = gap.get() - 1;
        if (stage > 3) {
            if (swapBack.get() && startSlot >= 0) InvUtils.swap(startSlot, false);
            int left = bombsLeft - 1;
            reset();
            if (left > 0) { bombsLeft = left; stage = -2; gapWait = repeatDelay.get(); }
        }
    }

    // ------------------------------------------------------------------ refill (a real inventory screen, normal clicks)

    private int pause() {
        return refillDelay.get() + rnd.nextInt(3);
    }

    private void tickRefill() {
        if (refillWait > 0) { refillWait--; return; }
        switch (refillState) {
            case 0 -> {
                mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(mc.player));
                refillState = 1;
                refillWait = pause();
            }
            case 1 -> {
                net.minecraft.item.Item item = refillQueue.poll();
                if (item == null) { refillState = 2; refillWait = pause(); return; }
                int src = -1;
                for (int i = 9; i < 36; i++) {
                    if (mc.player.getInventory().getStack(i).isOf(item)) { src = i; break; }
                }
                if (src >= 0) {
                    int empty = -1;
                    for (int i = 0; i < 9; i++) {
                        if (mc.player.getInventory().getStack(i).isEmpty()) { empty = i; break; }
                    }
                    int sync = mc.player.currentScreenHandler.syncId;
                    if (empty >= 0) {
                        // like hovering the item and pressing the number key of an empty hotbar slot
                        mc.interactionManager.clickSlot(sync, src, empty, net.minecraft.screen.slot.SlotActionType.SWAP, mc.player);
                    } else {
                        // like shift-clicking it: it goes to the hotbar
                        mc.interactionManager.clickSlot(sync, src, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player);
                    }
                }
                refillWait = pause();
            }
            case 2 -> {
                mc.player.closeHandledScreen();
                refillState = -1;
                justRefilled = true;
                stage = -2;
                gapWait = 2 + rnd.nextInt(3);
            }
            default -> refillState = -1;
        }
    }

    /** A random point on the same face, not far from its middle. */
    private Vec3d jitter(Vec3d hit, Direction side) {
        double a = (rnd.nextDouble() - 0.5) * 0.5, b = (rnd.nextDouble() - 0.5) * 0.5;
        return switch (side.getAxis()) {
            case X -> hit.add(0, a, b);
            case Y -> hit.add(a, 0, b);
            default -> hit.add(a, b, 0);
        };
    }

    private void placeBlock(BlockPos pos, net.minecraft.item.Item item, boolean isAnchor) {
        FindItemResult it = InvUtils.findInHotbar(item);
        if (!it.found()) return;
        if (!free(pos)) return;
        // check again right now: the face must still be a legit one (for the shield the anchor is in the world by now)
        Face f = findFace(pos, null, isAnchor ? null : anchorPos);
        if (f == null) return;
        Vec3d hitAt = humanize.get() ? jitter(f.hit(), f.side()) : f.hit();
        BlockHitResult bhr = new BlockHitResult(hitAt, f.side(), f.neighbour(), false);
        final int slot = it.slot();
        rotateThen(hitAt, () -> {
            InvUtils.swap(slot, false);
            BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
        });
    }

    /** charge = right click with Glowstone; otherwise right click with something else to make it explode. */
    private void clickAnchor(boolean charge) {
        if (anchorPos == null) return;
        if (charge && mc.world.getBlockState(anchorPos).contains(net.minecraft.block.RespawnAnchorBlock.CHARGES)
            && mc.world.getBlockState(anchorPos).get(net.minecraft.block.RespawnAnchorBlock.CHARGES) > 0) return; // already charged
        int slot;
        if (charge) {
            FindItemResult g = InvUtils.findInHotbar(Items.GLOWSTONE);
            if (!g.found()) return;
            slot = g.slot();
        } else {
            slot = nonGlowSlot();
        }
        if (slot < 0) return;
        // click a face of the anchor that you really see and can reach
        Vec3d[] es = eyes();
        BlockHitResult bhr = null;
        double bd = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            Vec3d face = Vec3d.ofCenter(anchorPos).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
            boolean ok = true;
            double dist = 0;
            for (Vec3d eye : es) {
                double dd = eye.squaredDistanceTo(face);
                dist = Math.max(dist, dd);
                if (dd > placeRange.get() * placeRange.get()) { ok = false; break; }
                Vec3d toEye = eye.subtract(face);
                if (d.getOffsetX() * toEye.x + d.getOffsetY() * toEye.y + d.getOffsetZ() * toEye.z <= 0) { ok = false; break; }
                Vec3d end = face.subtract(d.getOffsetX() * 0.02, d.getOffsetY() * 0.02, d.getOffsetZ() * 0.02); // a little into the anchor
                net.minecraft.world.RaycastContext ctx = new net.minecraft.world.RaycastContext(eye, end,
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player);
                net.minecraft.util.hit.BlockHitResult r = mc.world.raycast(ctx);
                if (!(r.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && r.getBlockPos().equals(anchorPos) && r.getSide() == d)) { ok = false; break; }
            }
            if (ok && dist < bd) { bd = dist; bhr = new BlockHitResult(face, d, anchorPos, false); }
        }
        if (bhr == null) return;
        final Vec3d c = bhr.getPos();
        final BlockHitResult click = bhr;
        final int s = slot;
        rotateThen(c, () -> {
            InvUtils.swap(s, false);
            BlockUtils.interact(click, Hand.MAIN_HAND, true);
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
            Vec3d eye = eyes()[1];
            Vec3d d = at.subtract(eye);
            double yaw = Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0;
            double pitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            Rotations.rotate(yaw, pitch, 100, action);
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
