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
	public static final Map<Integer, HwNet.VictimInfo> VICTIMS = new HashMap<>();
	public static HwNet.Qte qte = HwNet.Qte.INACTIVE;
	/** Current match, as last synced. */
	public static HwNet.MatchSync match = HwNet.MatchSync.IDLE;
	/** Final scoreboard and when it arrived (ms). */
	public static HwNet.MatchEnd matchEnd;
	public static long matchEndAt;

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
		if (myRole() != Role.SHAPE || hud == null) {
			return -1;
		}
		HwNet.VictimInfo info = VICTIMS.get(entity.getId());
		// The impulse is the one thing that reveals victims through walls.
		if (impulseActive() && IMPULSE_IDS.contains(entity.getId())) {
			return 0xFF9A2E;
		}
		if (info == null || !VISIBLE.contains(entity.getId())) {
			return -1;
		}
		if (info.marked()) {
			return 0xE0261F;
		}
		if (hud.sense()) {
			return 0xF2F2F2;
		}
		return -1;
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
		ClientSkins.clear();
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
		NOTICES.clear();
		FEED.clear();
		LOCKED_DOORS.clear();
		match = HwNet.MatchSync.IDLE;
		matchEnd = null;
		BROKEN_LOCKS.clear();
		progress = HwNet.Progress.NONE;
	}
}
