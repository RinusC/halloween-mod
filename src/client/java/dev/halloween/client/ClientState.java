package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.halloween.game.FocusPoints;
import dev.halloween.game.Sight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** What the client knows about the match, filled from server packets. */
public final class ClientState {
	public static final Map<UUID, Role> ROLES = new HashMap<>();
	public static int shapeEntityId = -1;
	public static boolean shapeShadow;
	public static HwNet.ShapeHud hud;
	/** Playing the Runt: his meter, gifts, cooldowns (see game.Runt). */
	public static HwNet.RuntHud runtHud;
	/** Playing Michael: where his Runt is and whom it smells; when that last came. */
	public static HwNet.RuntInfo runtInfo;
	public static long runtInfoAt;
	public static final Map<Integer, HwNet.VictimInfo> VICTIMS = new HashMap<>();
	public static HwNet.Qte qte = HwNet.Qte.INACTIVE;
	/** Current match, as last synced. */
	public static HwNet.MatchSync match = HwNet.MatchSync.IDLE;
	/** In the lobby: its status line and the stand at hand (see game.Lobby). */
	public static HwNet.LobbyInfo lobby = HwNet.LobbyInfo.NONE;
	/** This match's survivors and how each is doing (see HwNet.Roster). */
	public static HwNet.Roster roster = HwNet.Roster.EMPTY;
	/** Final scoreboard and when it arrived (ms). */
	public static HwNet.MatchEnd matchEnd;
	public static long matchEndAt;
	/** This survivor's stamina (see game.Stamina). */
	public static float stamina = 1f;
	public static boolean staminaExhausted;
	/** This survivor's panic (see game.Panic). */
	public static float panic;
	/** The purse, the perks owned, the build and how many it holds (see game.Perks). */
	public static int perkPoints;
	public static java.util.List<String> perkOwned = java.util.List.of();
	public static java.util.List<String> perkEquipped = java.util.List.of();
	public static int perkSlots = 3;
	/** Footsteps Michael heard (see game.Noise), newest last. */
	public static final java.util.List<NoiseMark> NOISES = new java.util.ArrayList<>();

	public record NoiseMark(Vec3 pos, boolean loud, long at) {
	}

	/** Locked doors and doors with a broken lock (lower halves). */
	public static final Set<net.minecraft.core.BlockPos> LOCKED_DOORS = new HashSet<>();
	public static final Set<net.minecraft.core.BlockPos> BROKEN_LOCKS = new HashSet<>();
	/** This player's timed action (lock, search, climb...), see HwNet.Progress. */
	public static HwNet.Progress progress = HwNet.Progress.NONE;

	/** Victims the Shape can actually see from his camera right now (no outlines through walls). */
	public static final Set<Integer> VISIBLE = new HashSet<>();

	/** Detection Impulse: outdoor victims outlined, indoor ones only reveal a fuzzy spot. */
	public static final Set<Integer> IMPULSE_IDS = new HashSet<>();
	public static List<Vec3> impulseSpots = List.of();
	public static long impulseUntil;
	public static long impulseStart;
	/** Reaction check during a long action (a call, a talk). */
	public static HwNet.SkillCheck skill = HwNet.SkillCheck.INACTIVE;
	/** This match's killer: 0 Michael, 1 the Demogorgon, 2 Vecna (WorldSync); and the Demogorgon's own screen. */
	public static int killer;
	public static HwNet.DemoHud demo = HwNet.DemoHud.NONE;
	/** Vecna's own screen (the Grip). */
	public static HwNet.VecnaHud vecna = HwNet.VecnaHud.NONE;
	/** Down in the Upside Down: the houses with a survivor in them (box corners in pairs). */
	public static List<net.minecraft.core.BlockPos> demoHouses = List.of();

	/** Talking a townsperson round right now (the server shows it as a progress bar). */
	public static boolean talking() {
		return progress.label().startsWith("halloween.hud.progress.talk");
	}

	/** When this survivor can shove again (client estimate for the HUD). */
	public static long pushReadyAt;

	/** Where townsfolk screams pointed at, for survivors: position and the game time it fades. */
	public record AlertMark(Vec3 pos, long until) {
	}

	public static final java.util.List<AlertMark> ALERTS = new java.util.ArrayList<>();

	/** XP feed entries, newest first. */
	public static final Deque<FeedEntry> FEED = new ArrayDeque<>();

	/** Corner notices, newest first. */
	public record Notice(Component text, long time) {
	}

	public static final java.util.List<Notice> NOTICES = new java.util.ArrayList<>();

	public static void notice(Component text) {
		NOTICES.add(0, new Notice(text, System.currentTimeMillis()));
		while (NOTICES.size() > 6) {
			NOTICES.remove(NOTICES.size() - 1);
		}
	}

	public record FeedEntry(int points, List<Component> lines, long time) {
	}

	private ClientState() {
	}

	public static Role myRole() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? Role.NONE : ROLES.getOrDefault(mc.player.getUUID(), Role.NONE);
	}

	public static boolean inMatch() {
		return myRole() != Role.NONE;
	}

	private static long gameTime() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime();
	}

	public static boolean senseActive() {
		return myRole() == Role.SHAPE && hud != null && hud.sense();
	}

	public static boolean focusActive() {
		return myRole() == Role.SHAPE && hud != null && hud.focus();
	}

	public static boolean shadowActive() {
		return myRole() == Role.SHAPE && hud != null && hud.shadowTicks() > 0;
	}

	public static boolean impulseActive() {
		return myRole() == Role.SHAPE && gameTime() < impulseUntil;
	}

	/**
	 * True for the Shape's entity while he is hidden in Shape Jump, and for anyone hiding in a locker
	 * (vanilla would still draw what an invisible body holds), as seen by anyone but themselves.
	 */
	public static boolean hideEntity(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player) {
			return false;
		}
		if (shapeShadow && entity.getId() == shapeEntityId) {
			return true;
		}
		boolean inMatch = myRole() != Role.NONE || match.state() == 2;
		return inMatch && entity instanceof LivingEntity && entity.isInvisible();
	}

	/** Outline colour the Shape sees for an entity, or -1 for none. */
	public static int outlineColor(Entity entity) {
		int event = ClientEvents.glow(entity);
		if (event != -1) {
			// Shown by a match event: Michael after Loomis's call, everyone to him at midnight.
			return event;
		}
		if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && myRole().survivor() && Minecraft.getInstance().player != null
			&& entity.distanceTo(Minecraft.getInstance().player) <= 32) {
			// A way-out item lying about: shows through walls to survivors within 32 blocks.
			dev.halloween.game.HwItems.Kind kind = dev.halloween.game.HwItems.kindOf(item.getItem());
			if (kind != null && kind.escape()) {
				return 0xF2C94C;
			}
		}
		if (myRole().survivor() && match.state() == 2 && perkEquipped.contains("witness") && entity != Minecraft.getInstance().player
			&& (ROLES.getOrDefault(entity.getUUID(), Role.NONE).survivor() || match.npcs().contains(entity.getId())) && !entity.isSpectator()
			&& entity.distanceTo(Minecraft.getInstance().player) <= dev.halloween.HwConfig.get().perkWitnessRange) {
			// Witness: the others close by show through walls.
			return 0x6FB7FF;
		}
		if (myRole() == Role.RUNT) {
			return sharedSight(entity);
		}
		if (myRole() != Role.SHAPE || hud == null) {
			return -1;
		}
		HwNet.VictimInfo info = VICTIMS.get(entity.getId());
		// The impulse is the one thing that reveals victims through walls.
		if (impulseActive() && IMPULSE_IDS.contains(entity.getId())) {
			return 0xFF9A2E;
		}
		// The Demogorgon has the scent of whoever he followed by their blood: they show through walls.
		if (info != null && info.marked() && MichaelRenderer.demogorgon()) {
			return 0xE0261F;
		}
		if (info == null || !VISIBLE.contains(entity.getId())) {
			return sharedSight(entity);
		}
		if (info.marked()) {
			return 0xE0261F;
		}
		if (hud.sense()) {
			return 0xF2F2F2;
		}
		return -1;
	}

	/** Shared sight with the Runt (his gift): the survivors the other one has in view right now, in purple. */
	private static int sharedSight(Entity entity) {
		List<Integer> ids = null;
		if (myRole() == Role.RUNT && runtHud != null) {
			ids = runtHud.scent();
		} else if (myRole() == Role.SHAPE && runtInfo != null && gameTime() - runtInfoAt <= 20) {
			ids = runtInfo.scent();
		}
		return ids != null && ids.contains(entity.getId()) ? 0xB98CFF : -1;
	}

	public static void updateVisibility(Minecraft mc) {
		VISIBLE.clear();
		if (mc.level == null || mc.player == null) {
			return;
		}
		Vec3 from = CameraAim.origin();
		for (HwNet.VictimInfo info : VICTIMS.values()) {
			if (mc.level.getEntity(info.entityId()) instanceof LivingEntity living
				&& (Sight.canSee(mc.level, from, FocusPoints.bodyCenter(living), mc.player)
				|| Sight.canSee(mc.level, from, living.getEyePosition(), mc.player))) {
				VISIBLE.add(info.entityId());
			}
		}
	}

	public static float shoulderOffset() {
		return myRole() == Role.SHAPE ? HwConfig.get().shapeShoulderOffset : 0f;
	}

	public static float fovMultiplier() {
		return focusActive() ? 0.6f : ClientGuns.fovMultiplier();
	}

	public static void addFeed(int points, List<Component> lines) {
		FEED.addFirst(new FeedEntry(points, lines, System.currentTimeMillis()));
		while (FEED.size() > 4) {
			FEED.removeLast();
		}
	}

	public static void reset() {
		ROLES.clear();
		runtHud = null;
		runtInfo = null;
		ClientEvents.reset();
		ClientSkins.clear();
		ClientMusic.reset();
		ClientMinimap.clear();
		BloodParticles.clear();
		ClientAnims.clear();
		DemogorgonModel.clear();
		GunPoses.clear();
		stamina = 1f;
		staminaExhausted = false;
		panic = 0f;
		NOISES.clear();
		ClientPanting.clear();
		Cutscene.clear();
		ClientWeather.kind = dev.halloween.game.Weather.CLEAR;
		shapeEntityId = -1;
		shapeShadow = false;
		hud = null;
		VICTIMS.clear();
		qte = HwNet.Qte.INACTIVE;
		IMPULSE_IDS.clear();
		impulseSpots = List.of();
		impulseUntil = 0;
		ALERTS.clear();
		skill = HwNet.SkillCheck.INACTIVE;
		demo = HwNet.DemoHud.NONE;
		vecna = HwNet.VecnaHud.NONE;
		demoHouses = List.of();
		NOTICES.clear();
		FEED.clear();
		LOCKED_DOORS.clear();
		match = HwNet.MatchSync.IDLE;
		matchEnd = null;
		BROKEN_LOCKS.clear();
		progress = HwNet.Progress.NONE;
	}
}
