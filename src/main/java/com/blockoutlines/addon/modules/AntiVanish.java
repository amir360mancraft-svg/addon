package com.blockoutlines.addon.modules;

import com.blockoutlines.addon.BlockOutlinesAddon;
import com.mojang.brigadier.suggestion.Suggestion;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Entry;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Finds players that went into vanish (staff hiding from you). It looks for the small things a vanish leaves behind:
 * a name that leaves the player list without a leave message, a name that is still in the command suggestions,
 * an invisible player body nearby, and sounds / particles with nothing visible to cause them.
 * Ported from the AntiVanishModule of another client; the helper classes it needed are written here.
 * Everything is read-only except one tab-complete request every few seconds (see "command-probe").
 */
public class AntiVanish extends Module {
    private static final String PROBE_COMMAND = "minecraft:msg";
    private static final int CRITICAL_SCORE = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSensors = settings.createGroup("Sensors");

    private final Setting<Boolean> onlyStaff = sgGeneral.add(new BoolSetting.Builder()
        .name("only-listed-names").description("Only report the names in the list below (e.g. staff). Off = report any player that seems to vanish.")
        .defaultValue(false).build());
    private final Setting<List<String>> staffNames = sgGeneral.add(new StringListSetting.Builder()
        .name("names").description("Staff / player names to watch (not case sensitive).")
        .defaultValue(new ArrayList<>()).visible(onlyStaff::get).build());
    private final Setting<Boolean> probe = sgGeneral.add(new BoolSetting.Builder()
        .name("command-probe").description("Every 5 seconds asks the server for the /msg name suggestions (the same thing that happens when you type /msg in chat). Hidden players often still show up there. The server can see these requests.")
        .defaultValue(true).build());
    private final Setting<Boolean> alertSound = sgGeneral.add(new BoolSetting.Builder()
        .name("alert-sound").description("Play a warning sound when a hidden player seems to be close to you.").defaultValue(true).build());

    private final Setting<Boolean> sensors = sgSensors.add(new BoolSetting.Builder()
        .name("sensors").description("Use invisible bodies, sounds and particles near you as clues.").defaultValue(true).build());
    private final Setting<Integer> sensorRange = sgSensors.add(new IntSetting.Builder()
        .name("range").description("How far away clues count (blocks).").defaultValue(64).range(16, 128).sliderRange(16, 96)
        .visible(sensors::get).build());

    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final Map<UUID, KnownPlayer> knownPlayers = new HashMap<>();
    private final Map<String, Detection> detections = new LinkedHashMap<>();
    private final Map<String, Long> signalCooldowns = new HashMap<>();
    private final Map<String, Long> announceCooldowns = new HashMap<>();
    private final Deque<Long> announceTimes = new ArrayDeque<>();
    private final Map<Long, Long> selfPlacedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> confirmedDepartures = new HashMap<>();
    private final Map<Long, Long> automatedMechanisms = new HashMap<>();
    private final Map<String, Deque<Long>> weakParticleBursts = new HashMap<>();
    private final Deque<Signal> signals = new ArrayDeque<>();
    private final Deque<ExplosionEvent> recentExplosions = new ArrayDeque<>();
    private final Deque<PendingVanish> pendingVanishes = new ArrayDeque<>();
    private final Map<String, HiddenSpell> hiddenSpells = new LinkedHashMap<>();
    private final Deque<RecentMessage> recentMessages = new ArrayDeque<>();
    private final Set<Integer> completionRequestIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> listedSinceMs = new HashMap<>();
    private final Set<UUID> pendingChimes = new HashSet<>();

    private volatile List<String> pendingCompletionNames;
    private volatile long lastLocalActionMs;
    private boolean serverSendsLeaveMessages;
    private int nextCompletionId = 30000;
    private int tickCounter;
    private long lastContainerActivityMs;
    private long lastCriticalMs;
    private Object lastWorld;
    private Vec3d lastPos;

    public AntiVanish() {
        super(BlockOutlinesAddon.CATEGORY, "anti-vanish", "Detects players that went into vanish and tells you in chat.");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        if (mc.player != null && mc.world != null) trackListedPlayers();
    }

    @Override
    public void onDeactivate() {
        resetRuntime();
    }

    // ------------------------------------------------------------------ small helpers (were separate classes)

    private static final Pattern USERNAME = Pattern.compile("^\\.?[A-Za-z0-9_]{3,16}$");

    private static boolean isUsername(String name) {
        return name != null && USERNAME.matcher(name).matches();
    }

    private static boolean containsPlayerName(String text, String name) {
        if (text == null || name == null || name.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = t.indexOf(n, from);
            if (i < 0) return false;
            boolean before = i == 0 || !isNameChar(t.charAt(i - 1));
            boolean after = i + n.length() >= t.length() || !isNameChar(t.charAt(i + n.length()));
            if (before && after) return true;
            from = i + 1;
        }
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean looksLikeLeaveMessage(String text, String name) {
        if (!containsPlayerName(text, name)) return false;
        String l = text.toLowerCase(Locale.ROOT);
        return l.contains("left") || l.contains("leave") || l.contains("disconnect") || l.contains("quit") || l.contains("logged out") || l.contains("offline");
    }

    private static String path(String id) {
        if (id == null) return "";
        int c = id.indexOf(':');
        return (c >= 0 ? id.substring(c + 1) : id).toLowerCase(Locale.ROOT);
    }

    private static boolean suspiciousSound(String id) {
        String p = path(id);
        if (p.isEmpty() || p.contains("explode") || p.contains("thunder") || p.contains("lightning")) return false;
        if (p.startsWith("block.") && (p.endsWith(".place") || p.endsWith(".break"))) return true;
        if (p.contains("chest.") || p.contains("barrel.") || p.contains("shulker_box.")) return p.endsWith(".open") || p.endsWith(".close");
        if (p.contains("door.") || p.contains("trapdoor.") || p.contains("fence_gate.")) return p.endsWith(".open") || p.endsWith(".close");
        if (p.startsWith("entity.player.attack") || p.equals("entity.player.burp") || p.equals("entity.generic.eat") || p.equals("entity.generic.drink")) return true;
        return false;
    }

    private static boolean suspiciousParticle(String id) {
        String p = path(id);
        return p.equals("block") || p.contains("smoke") || p.equals("crit") || p.equals("enchanted_hit")
            || p.equals("sweep_attack") || p.equals("damage_indicator") || p.equals("poof") || p.equals("cloud");
    }

    private boolean isStaff(String name) {
        if (name == null) return false;
        if (!onlyStaff.get()) return true;
        for (String s : staffNames.get()) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** True when you changed server / world or teleported far: all the old observations are then meaningless. */
    private boolean moved() {
        Vec3d now = mc.player.getEntityPos();
        boolean m = lastWorld != mc.world || (lastPos != null && lastPos.squaredDistanceTo(now) > 160.0 * 160.0);
        lastWorld = mc.world;
        lastPos = now;
        return m;
    }

    // ------------------------------------------------------------------ tick

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            resetRuntime();
            return;
        }
        if (moved()) resetRuntime();

        playPendingChime();
        tickCounter++;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) lastContainerActivityMs = System.currentTimeMillis();

        drainObservations();
        processPendingVanishes();
        processCompletionProbe();
        if (probe.get() && tickCounter % 100 == 0) sendCompletionProbe();
        if (tickCounter % 10 == 0) {
            trackListedPlayers();
            reviewSpells();
        }
        if (sensors.get() && tickCounter % 5 == 0) scanInvisiblePlayers();
        pruneState();
    }

    // ------------------------------------------------------------------ packets

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        handleOutbound(event.packet);
    }

    private void handleOutbound(Packet<?> packet) {
        if (packet == null) return;
        if (packet instanceof PlayerInteractBlockC2SPacket useOn) {
            lastLocalActionMs = System.currentTimeMillis();
            if (useOn.getBlockHitResult() != null && useOn.getBlockHitResult().getBlockPos() != null) {
                long until = System.currentTimeMillis() + 6000L;
                BlockPos hit = useOn.getBlockHitResult().getBlockPos();
                BlockPos adjacent = hit.offset(useOn.getBlockHitResult().getSide());
                selfPlacedBlocks.put(hit.asLong(), until);
                selfPlacedBlocks.put(adjacent.asLong(), until);
                String itemPath = "";
                if (mc.player != null && useOn.getHand() != null) {
                    Identifier itemId = Registries.ITEM.getId(mc.player.getStackInHand(useOn.getHand()).getItem());
                    itemPath = path(itemId == null ? "" : itemId.toString());
                }
                markSelfMultiBlockFootprint(hit, itemPath, selfPlacedBlocks, until);
                markSelfMultiBlockFootprint(adjacent, itemPath, selfPlacedBlocks, until);
            }
        } else if (packet instanceof PlayerActionC2SPacket || packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket || packet instanceof HandSwingC2SPacket) {
            lastLocalActionMs = System.currentTimeMillis();
        }
    }

    static void markSelfMultiBlockFootprint(BlockPos pos, String path, Map<Long, Long> targets, long until) {
        if (pos == null || targets == null) return;
        targets.put(pos.asLong(), until);
        String id = path == null ? "" : path;
        boolean vertical = id.endsWith("_door") && !id.endsWith("trapdoor") || id.contains("sunflower") || id.contains("lilac")
            || id.contains("rose_bush") || id.contains("peony") || id.contains("tall_grass") || id.contains("large_fern") || id.contains("pitcher_plant");
        if (vertical) {
            targets.put(pos.up().asLong(), until);
            targets.put(pos.down().asLong(), until);
        }
        if (id.endsWith("_bed")) {
            targets.put(pos.add(1, 0, 0).asLong(), until);
            targets.put(pos.add(-1, 0, 0).asLong(), until);
            targets.put(pos.add(0, 0, 1).asLong(), until);
            targets.put(pos.add(0, 0, -1).asLong(), until);
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (handleInbound(event.packet)) event.cancel();
    }

    /** Runs on the network thread: it only puts things in the queue, the tick reads them. Returns true to hide our own probe answer from the game. */
    private boolean handleInbound(Packet<?> packet) {
        if (packet instanceof PlayerRemoveS2CPacket remove) {
            if (remove.profileIds().size() < 4) {
                for (UUID id : remove.profileIds()) observations.offer(Observation.tabRemove(id));
            }
        } else if (packet instanceof PlayerListS2CPacket info && info.getActions().contains(Action.UPDATE_LISTED)) {
            int unlisted = 0;
            for (Entry entry : info.getEntries()) if (entry != null && !entry.listed()) unlisted++;
            if (unlisted > 0 && unlisted < 4) {
                for (Entry entry : info.getEntries()) if (entry != null && !entry.listed()) observations.offer(Observation.tabHide(entry.profileId()));
            }
        } else if (packet instanceof PlayerListS2CPacket info && info.getActions().contains(Action.UPDATE_GAME_MODE)) {
            queueGamemodeUpdates(info);
        } else if (packet instanceof GameMessageS2CPacket chat) {
            String text = chat.content() == null ? "" : chat.content().getString();
            if (!text.isBlank()) observations.offer(Observation.systemChat(text));
            String departedName = departedPlayerName(chat.content());
            if (!departedName.isBlank()) observations.offer(Observation.playerLeft(departedName));
        } else {
            if (packet instanceof CommandSuggestionsS2CPacket suggestions && completionRequestIds.remove(suggestions.id())) {
                List<String> names = new ArrayList<>();
                for (Suggestion suggestion : suggestions.getSuggestions().getList()) {
                    String t = suggestion.getText();
                    if (t != null && !t.isBlank()) names.add(t.trim());
                }
                pendingCompletionNames = names;
                return true;
            }
            if (packet instanceof EntityTrackerUpdateS2CPacket metadata) {
                observations.offer(Observation.entityMetadata(metadata.id()));
            } else if (packet instanceof ExplosionS2CPacket explosion) {
                observations.offer(Observation.explosion(explosion.center(), explosion.radius()));
            } else if (packet instanceof PlaySoundFromEntityS2CPacket sound) {
                observations.offer(Observation.entitySound(sound.getEntityId(), soundId(sound.getSound().value().id())));
            } else if (packet instanceof ParticleS2CPacket particles) {
                Identifier id = Registries.PARTICLE_TYPE.getId(particles.getParameters().getType());
                observations.offer(Observation.position(ObservationType.PARTICLE, particles.getX(), particles.getY(), particles.getZ(), id == null ? "" : id.toString()));
            } else if (packet instanceof PlaySoundS2CPacket sound) {
                if (sound.getSound() != null && sound.getSound().value() != null) {
                    String id = soundId(sound.getSound().value().id());
                    observations.offer(Observation.position(ObservationType.POSITIONAL_SOUND, sound.getX(), sound.getY(), sound.getZ(), id));
                }
            }
        }
        return false;
    }

    private void drainObservations() {
        for (int i = 0; i < 512; i++) {
            Observation observation = observations.poll();
            if (observation == null) break;
            processObservation(observation);
        }
        while (observations.size() > 4096) observations.poll();
    }

    private void processObservation(Observation o) {
        switch (o.type) {
            case TAB_REMOVE -> handleTabRemoval(o.profileId);
            case TAB_HIDE -> handleTabHidden(o.profileId);
            case PLAYER_LEFT -> {
                serverSendsLeaveMessages = true;
                confirmDeparture(o.detail);
            }
            case SYSTEM_CHAT -> cacheRecentMessage(o.detail);
            case ENTITY_METADATA -> { if (sensors.get()) inspectInvisibleEntity(o.entityId); }
            case POSITIONAL_SOUND -> { if (sensors.get()) inspectPositionalSound(o); }
            case ENTITY_SOUND -> { if (sensors.get()) inspectEntitySound(o); }
            case PARTICLE -> { if (sensors.get()) inspectParticle(o); }
            case EXPLOSION -> rememberExplosion(o);
            case GAMEMODE -> announceGamemode(o.profileId, o.detail);
        }
    }

    // ------------------------------------------------------------------ player list

    private void trackListedPlayers() {
        if (mc.getNetworkHandler() == null) return;
        long now = System.currentTimeMillis();
        for (PlayerListEntry info : mc.getNetworkHandler().getListedPlayerListEntries()) {
            if (info == null || info.getProfile() == null || info.getProfile().id() == null) continue;
            UUID uuid = info.getProfile().id();
            if (mc.player != null && mc.player.getUuid().equals(uuid)) continue;
            listedSinceMs.putIfAbsent(uuid, now);
            String name = info.getProfile().name();
            if (name != null && !knownPlayers.containsKey(uuid)) knownPlayers.put(uuid, new KnownPlayer(uuid, name));
            if (name != null && !hiddenSpells.isEmpty()) endSpell(name, false, 1000L);
        }
        if (listedSinceMs.size() > 1024 || knownPlayers.size() > 1024) {
            Set<UUID> connected = new HashSet<>();
            for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) if (e != null && e.getProfile() != null) connected.add(e.getProfile().id());
            listedSinceMs.keySet().retainAll(connected);
            knownPlayers.keySet().retainAll(connected);
        }
    }

    private boolean trustedListed(UUID uuid) {
        Long since = uuid == null ? null : listedSinceMs.get(uuid);
        return since != null && System.currentTimeMillis() - since >= 1500L;
    }

    private boolean credibleSubject(UUID uuid, String name) {
        return uuid != null && isUsername(name) && isStaff(name) && trustedListed(uuid);
    }

    private void handleTabRemoval(UUID uuid) {
        if (uuid == null || mc.player.getUuid().equals(uuid)) return;
        String name = knownName(uuid);
        if (credibleSubject(uuid, name)) {
            pendingVanishes.removeIf(p -> p.uuid.equals(uuid));
            pendingVanishes.addLast(new PendingVanish(uuid, name, tickCounter + 20));
        }
    }

    private void handleTabHidden(UUID uuid) {
        if (uuid == null || mc.player.getUuid().equals(uuid)) return;
        KnownPlayer known = knownPlayers.get(uuid);
        String name = known != null && known.name != null ? known.name : knownName(uuid);
        if (credibleSubject(uuid, name) && !recentMessageNames(name)) {
            reportHidden(uuid, name, false, "Vanish Event: hidden from TAB", "hidden from TAB", 100);
        }
    }

    private String knownName(UUID uuid) {
        KnownPlayer known = knownPlayers.get(uuid);
        if (known != null && known.name != null && !known.name.isBlank()) return known.name;
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null && info.getProfile() != null) return info.getProfile().name();
        }
        return null;
    }

    private UUID knownUuid(String name) {
        for (KnownPlayer k : knownPlayers.values()) if (k.name.equalsIgnoreCase(name)) return k.uuid;
        return null;
    }

    private void confirmDeparture(String displayedName) {
        long now = System.currentTimeMillis();
        for (KnownPlayer known : knownPlayers.values()) {
            if (!containsPlayerName(displayedName, known.name)) continue;
            confirmedDepartures.put(known.uuid, now + 5000L);
            pendingVanishes.removeIf(p -> p.uuid.equals(known.uuid));
            hiddenSpells.remove(spellKey(known.name));
            Detection d = detections.get(known.uuid.toString());
            if (d != null && tabDepartureReason(d.reason)) detections.remove(known.uuid.toString());
            signals.removeIf(s -> s.type == SignalType.VANISH && s.subject.equalsIgnoreCase(known.name) && tabDepartureReason(s.reason));
        }
    }

    private void processPendingVanishes() {
        while (!pendingVanishes.isEmpty() && pendingVanishes.peekFirst().dueTick <= tickCounter) {
            PendingVanish pending = pendingVanishes.removeFirst();
            if (mc.getNetworkHandler().getPlayerListEntry(pending.uuid) != null) continue;
            long now = System.currentTimeMillis();
            if (confirmedDepartures.getOrDefault(pending.uuid, 0L) > now) continue;
            if (recentMessageNames(pending.name)) {
                serverSendsLeaveMessages = true;
                continue;
            }
            PlayerEntity remaining = mc.world.getPlayerByUuid(pending.uuid);
            if (remaining != null && !remaining.isRemoved()) {
                reportHidden(pending.uuid, pending.name, false, "Vanish Event: entity remained", "off TAB, still nearby", 100);
            } else {
                String reason = "Vanish Event: silent TAB disappearance";
                int score = serverSendsLeaveMessages ? 100 : 30;
                if (serverSendsLeaveMessages) {
                    reportHidden(pending.uuid, pending.name, false, reason, "left TAB silently", score);
                } else {
                    // this server sends no leave messages, so a name leaving the list alone proves nothing: it only counts together with other clues
                    RegionProof proof = regionProof(pending.uuid);
                    if (proof != null) {
                        signals.addLast(new Signal(SignalType.VANISH, pending.name, proof, reason, score, now));
                        evaluateCritical();
                    }
                }
            }
        }
    }

    static boolean tabDepartureReason(String reason) {
        if (reason == null) return false;
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.startsWith("vanish event:") && (lower.contains("tab") || lower.contains("entity remained") || lower.contains("no leave packet"));
    }

    private void cacheRecentMessage(String text) {
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        recentMessages.addLast(new RecentMessage(text, now));
        while (!recentMessages.isEmpty() && (now - recentMessages.peekFirst().atMs > 8000L || recentMessages.size() > 64)) recentMessages.removeFirst();
    }

    private boolean recentMessageNames(String name) {
        if (name == null || name.isBlank()) return false;
        long now = System.currentTimeMillis();
        for (RecentMessage m : recentMessages) if (now - m.atMs <= 8000L && looksLikeLeaveMessage(m.text, name)) return true;
        return false;
    }

    // ------------------------------------------------------------------ command suggestion probe

    private void sendCompletionProbe() {
        if (mc.getNetworkHandler() == null) return;
        int id = nextCompletionId++;
        if (nextCompletionId > 40000) nextCompletionId = 30000;
        completionRequestIds.add(id);
        while (completionRequestIds.size() > 8) completionRequestIds.remove(completionRequestIds.iterator().next());
        try {
            mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(id, PROBE_COMMAND + " "));
        } catch (Throwable ignored) {
        }
    }

    private void processCompletionProbe() {
        List<String> current = pendingCompletionNames;
        if (current == null) return;
        pendingCompletionNames = null;
        if (mc.getNetworkHandler() == null || mc.player == null) return;
        Set<String> tabNames = new HashSet<>();
        for (PlayerListEntry info : mc.getNetworkHandler().getPlayerList()) {
            if (info != null && info.getProfile() != null && info.getProfile().name() != null) tabNames.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        }
        String self = mc.player.getName().getString();
        List<String> hidden = new ArrayList<>();
        for (String name : current) {
            if (isStaff(name) && isUsername(name) && !name.equalsIgnoreCase(self) && !tabNames.contains(name.toLowerCase(Locale.ROOT)) && !recentMessageNames(name)) {
                hidden.add(name);
            }
        }
        if (!hidden.isEmpty() && hidden.size() <= 3) {
            for (String n : hidden) reportHidden(knownUuid(n), n, false, "Vanish Event: hidden but targetable", "off TAB, still targetable", 90);
        }
    }

    private static String departedPlayerName(Text component) {
        if (component != null && component.getContent() instanceof TranslatableTextContent translated && "multiplayer.player.left".equals(translated.getKey())) {
            Object[] args = translated.getArgs();
            if (args.length != 0 && args[0] != null) return args[0] instanceof Text name ? name.getString() : String.valueOf(args[0]);
        }
        return "";
    }

    // ------------------------------------------------------------------ hidden players

    private void reportHidden(UUID uuid, String name, boolean spectator, String reason, String detail, int score) {
        upsertDetection(detectionKey(uuid, name), name, reason, score, System.currentTimeMillis() + 20000L);
        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell == null || spell.spectator != spectator) {
            hiddenSpells.put(spellKey(name), new HiddenSpell(uuid, name, spectator, detail, regionProof(uuid), tickCounter));
        }
    }

    private void reviewSpells() {
        for (HiddenSpell spell : hiddenSpells.values()) {
            if (spell.spectator && mc.getNetworkHandler().getPlayerListEntry(spell.uuid) == null) continue;
            if (spell.watched || spell.sinceTick >= tickCounter) continue;
            RegionProof proof = regionProof(spell.uuid);
            if (proof == null && !spell.told) proof = spell.proof;
            if (proof != null) {
                spell.told = true;
                spell.watched = true;
                watchingYou(spell.name, proof);
            } else if (!spell.told) {
                spell.told = true;
                news(spell.name, spell.how(), spell.detail);
            }
        }
        hiddenSpells.values().removeIf(s -> s.spectator && mc.getNetworkHandler().getPlayerListEntry(s.uuid) == null);
    }

    private boolean endSpell(String name, boolean spectator, long settleMs) {
        HiddenSpell spell = hiddenSpells.get(spellKey(name));
        if (spell != null && spell.spectator == spectator && System.currentTimeMillis() - spell.sinceMs >= settleMs) {
            hiddenSpells.remove(spellKey(name));
            detections.remove(detectionKey(spell.uuid, spell.name));
            if (spell.told) news(spell.name, "is visible again", "");
            return true;
        }
        return false;
    }

    /** A body of this player is loaded near you: proves they are really close. */
    private RegionProof regionProof(UUID uuid) {
        if (uuid == null) return null;
        PlayerEntity body = mc.world.getPlayerByUuid(uuid);
        if (body != null && body != mc.player) return new RegionProof(uuid, Math.round(body.distanceTo(mc.player)));
        return null;
    }

    private static String spellKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String detectionKey(UUID uuid, String name) {
        return uuid != null ? uuid.toString() : name;
    }

    // ------------------------------------------------------------------ sensors

    private double sensorRangeSq() {
        double r = sensorRange.get();
        return r * r;
    }

    private void scanInvisiblePlayers() {
        double rangeSq = sensorRangeSq();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != null && player != mc.player && player.isInvisible() && player.squaredDistanceTo(mc.player) <= rangeSq && realPlayer(player)) {
                triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
            }
        }
    }

    private void inspectInvisibleEntity(int entityId) {
        if (mc.world.getEntityById(entityId) instanceof PlayerEntity player && player != mc.player && player.isInvisible()
            && player.squaredDistanceTo(mc.player) <= sensorRangeSq() && realPlayer(player)) {
            triggerSensor(SignalType.INVISIBLE, player, "Invisible Entity: metadata flag", 35, 5000L);
        }
    }

    private boolean realPlayer(PlayerEntity player) {
        return credibleSubject(player.getUuid(), profileName(player));
    }

    private static String profileName(PlayerEntity player) {
        return player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().name();
    }

    private void inspectPositionalSound(Observation o) {
        if (!suspiciousSound(o.detail)) return;
        Vec3d source = o.position();
        long now = System.currentTimeMillis();
        if (nearPlayer(source) && !hasVisibleCause(source) && !isExplosionRelated(source) && !isPoweredMechanism(source, o.detail)
            && now - lastLocalActionMs >= 1000L && !recentlySelfPlaced(source) && !villagerToggledDoor(o.detail, source)
            && (!selfContainerActive() || !isContainerSignal(o.detail))) {
            triggerSensor(SignalType.SOUND, locatedSubject(source), "Suspicious Sound: " + path(o.detail), 14, 3000L);
        }
    }

    private void inspectEntitySound(Observation o) {
        if (!suspiciousSound(o.detail)) return;
        if (mc.world.getEntityById(o.entityId) instanceof PlayerEntity player && player != mc.player && player.isInvisible()
            && player.squaredDistanceTo(mc.player) <= sensorRangeSq() && realPlayer(player)) {
            triggerSensor(SignalType.SOUND, player, "Suspicious Sound: invisible source", 16, 3000L);
        }
    }

    private void inspectParticle(Observation o) {
        if (!suspiciousParticle(o.detail)) return;
        Vec3d source = o.position();
        long now = System.currentTimeMillis();
        if (nearPlayer(source) && !hasVisibleCause(source) && !isExplosionRelated(source) && now - lastLocalActionMs >= 900L
            && !hasAmbientParticleSource(source, o.detail)) {
            String particle = path(o.detail);
            boolean weak = particle.equals("block") || particle.contains("smoke");
            if (weak && nearSelf(source, 6.25)) return;
            if (weak && !particleBurstReady(particle, now)) return;
            triggerSensor(SignalType.PARTICLE, locatedSubject(source), "Ghost Particle: " + particle, 16, 3000L);
        }
    }

    private boolean villagerToggledDoor(String blockId, Vec3d source) {
        String p = path(blockId);
        boolean doorLike = p.contains("door") && !p.contains("trapdoor") || p.contains("fence_gate");
        if (!doorLike || source == null || mc.world == null) return false;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() == EntityType.VILLAGER && entity.getEntityPos().squaredDistanceTo(source) <= 9.0) return true;
        }
        return false;
    }

    private boolean recentlySelfPlaced(Vec3d source) {
        if (source == null) return false;
        Long until = selfPlacedBlocks.get(BlockPos.ofFloored(source).asLong());
        return until != null && until > System.currentTimeMillis();
    }

    private void rememberExplosion(Observation o) {
        long now = System.currentTimeMillis();
        recentExplosions.addLast(new ExplosionEvent(o.position(), Math.max(2.0, parseDouble(o.detail, 4.0) + 4.0), now));
        while (recentExplosions.size() > 8) recentExplosions.removeFirst();
    }

    private boolean particleBurstReady(String particle, long now) {
        Deque<Long> burst = weakParticleBursts.computeIfAbsent(particle, k -> new ArrayDeque<>());
        burst.addLast(now);
        while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
        if (burst.size() < 2) return false;
        burst.clear();
        return true;
    }

    private void triggerSensor(SignalType type, String where, String reason, int weight, long cooldownMs) {
        addSignal(type, where, null, reason, weight, cooldownMs);
    }

    private void triggerSensor(SignalType type, PlayerEntity player, String reason, int weight, long cooldownMs) {
        addSignal(type, profileName(player), regionProof(player.getUuid()), reason, weight, cooldownMs);
    }

    private void addSignal(SignalType type, String subject, RegionProof proof, String reason, int weight, long cooldownMs) {
        long now = System.currentTimeMillis();
        String cooldownKey = type.name() + "|" + subject.toLowerCase(Locale.ROOT);
        long last = signalCooldowns.getOrDefault(cooldownKey, 0L);
        if (now - last < cooldownMs) return;
        signalCooldowns.put(cooldownKey, now);
        signals.addLast(new Signal(type, subject, proof, reason, weight, now));
        upsertDetection("signal:" + cooldownKey, subject, reason, weight, now + 20000L);
        announceTrigger(cooldownKey, subject, proof != null, reason);
        evaluateCritical();
    }

    /** Two different kinds of clues together, and one of them has a real name: somebody hidden is watching you. */
    private void evaluateCritical() {
        long now = System.currentTimeMillis();
        pruneSignals(now);
        EnumMap<SignalType, Integer> strongest = new EnumMap<>(SignalType.class);
        Signal watcher = null;
        for (Signal s : signals) {
            strongest.merge(s.type, s.weight, Math::max);
            if (s.proof != null) watcher = s;
        }
        int score = strongest.values().stream().mapToInt(Integer::intValue).sum();
        if (watcher != null && strongest.size() >= 2 && score >= CRITICAL_SCORE && now - lastCriticalMs >= 12000L) {
            watchingYou(watcher.subject, watcher.proof);
        }
    }

    private void watchingYou(String name, RegionProof proof) {
        lastCriticalMs = System.currentTimeMillis();
        pendingChimes.add(proof.id());
        warning("%s may be watching you (vanished%s)", name, proof.distance() >= 0 ? ", about " + proof.distance() + " blocks away" : "");
    }

    private void playPendingChime() {
        if (pendingChimes.isEmpty()) return;
        pendingChimes.clear();
        if (alertSound.get() && mc.getSoundManager() != null) {
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.0F, 1.0F));
        }
    }

    // ------------------------------------------------------------------ chat output

    private void news(String name, String what, String detail) {
        info("%s %s%s", name, what, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    private void queueGamemodeUpdates(PlayerListS2CPacket info) {
        if (info.getActions().contains(Action.ADD_PLAYER)) return;
        for (Entry entry : info.getEntries()) {
            if (entry != null && entry.gameMode() != null && entry.profileId() != null) observations.offer(Observation.gamemode(entry.profileId(), entry.gameMode().getId()));
        }
    }

    private void announceGamemode(UUID uuid, String gameMode) {
        if (uuid == null || (mc.player != null && mc.player.getUuid().equals(uuid))) return;
        KnownPlayer known = knownPlayers.get(uuid);
        if (known == null || !onlyStaff.get() || !isStaff(known.name)) return; // gamemode changes are only told for the names in your list
        if (GameMode.SPECTATOR.getId().equals(gameMode)) {
            if (credibleSubject(uuid, known.name)) {
                reportHidden(uuid, known.name, true, "Vanish Event: switched to spectator", "", 100);
                return;
            }
        } else if (endSpell(known.name, true, 0L)) {
            return;
        }
        news(known.name, "switched to " + gameMode, "");
    }

    private void announceTrigger(String eventKey, String subject, boolean named, String reason) {
        long now = System.currentTimeMillis();
        Long last = announceCooldowns.get(eventKey);
        if (last != null && now - last < 1500L) return;
        while (!announceTimes.isEmpty() && now - announceTimes.peekFirst() > 4000L) announceTimes.removeFirst();
        if (announceTimes.size() >= 6) return;
        announceCooldowns.put(eventKey, now);
        announceTimes.addLast(now);
        boolean located = subject != null && !subject.isBlank() && !"Unknown".equalsIgnoreCase(subject);
        warning("%s%s", reason, located ? " (" + subject + ")" : "");
    }

    private void upsertDetection(String key, String name, String reason, int score, long expiresAt) {
        Detection existing = detections.get(key);
        if (existing == null) {
            detections.put(key, new Detection(name, reason, score, expiresAt));
        } else {
            existing.name = name;
            existing.expiresAt = Math.max(existing.expiresAt, expiresAt);
            if (score >= existing.score) {
                existing.reason = reason;
                existing.score = score;
            }
        }
    }

    private void pruneState() {
        long now = System.currentTimeMillis();
        detections.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        signalCooldowns.entrySet().removeIf(e -> now - e.getValue() > 60000L);
        announceCooldowns.entrySet().removeIf(e -> now - e.getValue() > 60000L);
        selfPlacedBlocks.entrySet().removeIf(e -> e.getValue() <= now);
        confirmedDepartures.entrySet().removeIf(e -> e.getValue() <= now);
        automatedMechanisms.entrySet().removeIf(e -> e.getValue() <= now);
        weakParticleBursts.values().removeIf(burst -> {
            while (!burst.isEmpty() && now - burst.peekFirst() > 2000L) burst.removeFirst();
            return burst.isEmpty();
        });
        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();
        pruneSignals(now);
    }

    private void pruneSignals(long now) {
        while (!signals.isEmpty() && now - signals.peekFirst().timeMs > 15000L) signals.removeFirst();
    }

    // ------------------------------------------------------------------ world checks

    private boolean nearPlayer(Vec3d source) {
        return source != null && source.squaredDistanceTo(mc.player.getEntityPos()) <= sensorRangeSq();
    }

    private boolean nearSelf(Vec3d source, double maxDistSq) {
        return source != null && mc.player != null && source.squaredDistanceTo(mc.player.getEntityPos()) < maxDistSq;
    }

    private boolean selfContainerActive() {
        return System.currentTimeMillis() - lastContainerActivityMs < 2500L;
    }

    private static boolean isContainerSignal(String id) {
        String p = path(id);
        return p.contains("chest") || p.contains("barrel") || p.contains("shulker");
    }

    private String locatedSubject(Vec3d source) {
        if (source == null || mc.player == null) return "nearby";
        Vec3d me = mc.player.getEntityPos();
        double dx = source.x - me.x, dy = source.y - me.y, dz = source.z - me.z;
        long dist = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        String dir = compass(dx, dz);
        return dir.isEmpty() ? dist + "m" : dist + "m " + dir;
    }

    private static String compass(double dx, double dz) {
        String ns = dz < -1.0 ? "N" : (dz > 1.0 ? "S" : "");
        String ew = dx > 1.0 ? "E" : (dx < -1.0 ? "W" : "");
        return ns + ew;
    }

    private boolean hasVisibleCause(Vec3d source) {
        if (source == null) return true;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player) continue;
            if (entity instanceof PlayerEntity player) {
                if (!player.isInvisible() && entity.getEntityPos().squaredDistanceTo(source) <= 16.0) return true;
            } else if (entity instanceof ProjectileEntity && entity.getEntityPos().squaredDistanceTo(source) <= 16.0) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplosionRelated(Vec3d source) {
        if (source == null) return false;
        long now = System.currentTimeMillis();
        while (!recentExplosions.isEmpty() && now - recentExplosions.peekFirst().timeMs > 3000L) recentExplosions.removeFirst();
        for (ExplosionEvent e : recentExplosions) if (e.center.squaredDistanceTo(source) <= e.radius * e.radius) return true;
        return false;
    }

    private boolean hasAmbientParticleSource(Vec3d source, String particleId) {
        if (source == null || !path(particleId).contains("smoke")) return false;
        BlockPos center = BlockPos.ofFloored(source);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Identifier id = Registries.BLOCK.getId(mc.world.getBlockState(center.add(dx, dy, dz)).getBlock());
                    String p = path(id == null ? "" : id.toString());
                    if (p.contains("campfire") || p.contains("furnace") || p.contains("smoker") || p.contains("torch") || p.contains("fire")
                        || p.contains("candle") || p.contains("respawn_anchor")) return true;
                }
            }
        }
        return false;
    }

    private boolean isPoweredMechanism(Vec3d source, String soundId) {
        String p = path(soundId);
        if (!p.contains("door") && !p.contains("trapdoor")) return false;
        BlockPos center = BlockPos.ofFloored(source);
        long now = System.currentTimeMillis();
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos pos = center.add(0, dy, 0);
            BlockState state = mc.world.getBlockState(pos);
            long key = pos.asLong();
            boolean powered = state.contains(Properties.POWERED) && state.get(Properties.POWERED);
            if (powered || mc.world.isReceivingRedstonePower(pos)) {
                automatedMechanisms.put(key, now + 5000L);
                return true;
            }
            if (automatedMechanisms.getOrDefault(key, 0L) > now) return true;
        }
        return false;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String soundId(Identifier id) {
        return id == null ? "" : id.toString();
    }

    private void resetRuntime() {
        observations.clear();
        knownPlayers.clear();
        detections.clear();
        signalCooldowns.clear();
        announceCooldowns.clear();
        announceTimes.clear();
        selfPlacedBlocks.clear();
        confirmedDepartures.clear();
        automatedMechanisms.clear();
        weakParticleBursts.clear();
        signals.clear();
        recentExplosions.clear();
        pendingVanishes.clear();
        hiddenSpells.clear();
        tickCounter = 0;
        lastLocalActionMs = 0L;
        lastContainerActivityMs = 0L;
        lastCriticalMs = 0L;
        pendingChimes.clear();
        recentMessages.clear();
        listedSinceMs.clear();
        serverSendsLeaveMessages = false;
        completionRequestIds.clear();
        pendingCompletionNames = null;
        nextCompletionId = 30000;
        lastWorld = null;
        lastPos = null;
    }

    // ------------------------------------------------------------------ data

    private static final class Detection {
        String name, reason;
        int score;
        long expiresAt;
        Detection(String name, String reason, int score, long expiresAt) {
            this.name = name; this.reason = reason; this.score = score; this.expiresAt = expiresAt;
        }
    }

    private record ExplosionEvent(Vec3d center, double radius, long timeMs) {}

    private static final class HiddenSpell {
        final UUID uuid;
        final String name;
        final boolean spectator;
        final String detail;
        final RegionProof proof;
        final int sinceTick;
        final long sinceMs = System.currentTimeMillis();
        boolean told, watched;
        HiddenSpell(UUID uuid, String name, boolean spectator, String detail, RegionProof proof, int sinceTick) {
            this.uuid = uuid; this.name = name; this.spectator = spectator; this.detail = detail; this.proof = proof; this.sinceTick = sinceTick;
        }
        String how() {
            return spectator ? "switched to spectator" : "vanished";
        }
    }

    private record KnownPlayer(UUID uuid, String name) {}

    private record Observation(ObservationType type, UUID profileId, int entityId, double x, double y, double z, String detail) {
        static Observation tabRemove(UUID id) { return new Observation(ObservationType.TAB_REMOVE, id, -1, 0, 0, 0, ""); }
        static Observation tabHide(UUID id) { return new Observation(ObservationType.TAB_HIDE, id, -1, 0, 0, 0, ""); }
        static Observation gamemode(UUID id, String mode) { return new Observation(ObservationType.GAMEMODE, id, -1, 0, 0, 0, mode); }
        static Observation playerLeft(String name) { return new Observation(ObservationType.PLAYER_LEFT, null, -1, 0, 0, 0, name); }
        static Observation systemChat(String text) { return new Observation(ObservationType.SYSTEM_CHAT, null, -1, 0, 0, 0, text); }
        static Observation entityMetadata(int id) { return new Observation(ObservationType.ENTITY_METADATA, null, id, 0, 0, 0, ""); }
        static Observation explosion(Vec3d c, float radius) { return new Observation(ObservationType.EXPLOSION, null, -1, c.x, c.y, c.z, Float.toString(radius)); }
        static Observation entitySound(int id, String sound) { return new Observation(ObservationType.ENTITY_SOUND, null, id, 0, 0, 0, sound); }
        static Observation position(ObservationType t, double x, double y, double z, String detail) { return new Observation(t, null, -1, x, y, z, detail); }
        Vec3d position() { return new Vec3d(x, y, z); }
    }

    private enum ObservationType { TAB_REMOVE, TAB_HIDE, PLAYER_LEFT, SYSTEM_CHAT, ENTITY_METADATA, POSITIONAL_SOUND, ENTITY_SOUND, PARTICLE, EXPLOSION, GAMEMODE }

    private record PendingVanish(UUID uuid, String name, int dueTick) {}

    private record RecentMessage(String text, long atMs) {}

    private record RegionProof(UUID id, int distance) {}

    private record Signal(SignalType type, String subject, RegionProof proof, String reason, int weight, long timeMs) {}

    private enum SignalType { VANISH, INVISIBLE, PARTICLE, SOUND }
}
