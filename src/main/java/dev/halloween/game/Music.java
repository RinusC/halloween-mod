package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Which music each player hears, worked out on the server where everything is known; the client fades
 * between tracks ({@code ClientMusic}).
 * <ul>
 * <li>The Shape: Stalking while he only watches (one composition per Bloodthirst level), Michael Myers
 * Proximity while he chases someone who has seen him or holds someone (one per stun level: the last while
 * he is down).</li>
 * <li>Survivors: Ambience until his chase starts (nothing warns of him), then Proximity.</li>
 * <li>Vecna: his own calm music; survivors hear their calm file, and near him the chase file by its marks (vecnaMood).</li>
 * <li>A kill silences everything for a moment.</li>
 * <li>Seen by him in a chase (Chase), it is Proximity even without having looked back; getting away from him
 * cuts the music off at once (a few seconds of silence, then on as before).</li>
 * </ul>
 */
public final class Music {
	/** No match music (vanilla music may play). */
	public static final int OFF = -1;
	public static final int SILENT = 0;
	public static final int AMBIENCE = 1;
	public static final int BEING_STALKED = 2;
	public static final int PROXIMITY = 3;
	public static final int STALKING = 4;
	/** Vecna: survivors' calm music, his own, and the chase file by its marks (variant 1..7, see chaseMark). */
	public static final int VECNA_SURVIVOR = 5;
	public static final int VECNA_CALM = 6;
	public static final int VECNA_CHASE = 7;

	private static final Map<UUID, Integer> SENT = new HashMap<>();
	private static final Map<UUID, Integer> SAW_SHAPE = new HashMap<>();
	private static final Map<UUID, Double> LAST_DISTANCE = new HashMap<>();
	private static final Map<UUID, Integer> CHASED_UNTIL = new HashMap<>();
	/** Just got away (Chase): silence, cut off at once, until then. */
	private static final Map<UUID, Integer> ESCAPED_UNTIL = new HashMap<>();
	/** Vecna: the chase part each survivor hears now (0: none), until when the post-chase part lingers, who has been exposed in this chase. */
	private static final Map<UUID, Integer> CHASE_MARK = new HashMap<>();
	private static final Map<UUID, Integer> POST_UNTIL = new HashMap<>();
	private static final java.util.Set<UUID> EXPOSED = new java.util.HashSet<>();
	private static int silentUntil;
	/** Vecna himself: until when the post-chase part lingers, and whether the chase has been exposed. */
	private static int killerPostUntil;
	private static int killerMark;
	private static boolean killerExposed;

	private Music() {
	}

	public static void clear() {
		SAW_SHAPE.clear();
		LAST_DISTANCE.clear();
		CHASED_UNTIL.clear();
		ESCAPED_UNTIL.clear();
		CHASE_MARK.clear();
		POST_UNTIL.clear();
		EXPOSED.clear();
		silentUntil = 0;
		killerPostUntil = 0;
		killerMark = 0;
		killerExposed = false;
	}

	/** Someone is being killed: all music falls silent for {@code ticks}. */
	public static void killing(MinecraftServer server, int ticks) {
		silentUntil = Math.max(silentUntil, server.getTickCount() + ticks);
	}

	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		if (now % 5 != 0) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = server.overworld();
		ServerPlayer shape = Senses.shape(level);
		ShapeState state = shape == null ? null : MatchManager.shapeState(shape);
		boolean shapeChasing = state != null && state.grab != null;
		boolean exposedNow = false;
		Map<UUID, Integer> moods = new HashMap<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Role role = MatchManager.role(player);
			if (!role.survivor()) {
				continue;
			}
			boolean chased = false;
			boolean near = false;
			if (state != null && !player.isSpectator() && !shape.isSpectator()) {
				double dist = player.distanceTo(shape);
				Vec3 body = FocusPoints.bodyCenter(shape);
				Vec3 eye = player.getEyePosition();
				boolean hidden = state.shadowTicks > 0 || Lockers.isInside(player);
				if (!hidden && dist <= cfg.musicSightRange && Sight.angleTo(eye, player.getViewVector(1f), body) <= 70 && Sight.canSee(level, eye, body, player)) {
					SAW_SHAPE.put(player.getUUID(), now);
				}
				boolean noticed = now - SAW_SHAPE.getOrDefault(player.getUUID(), -100000) <= cfg.musicNoticeTicks;
				double before = LAST_DISTANCE.getOrDefault(player.getUUID(), dist);
				LAST_DISTANCE.put(player.getUUID(), dist);
				boolean coming = before - dist >= 0.25 || dist <= 4;
				boolean held = MatchManager.isGrabbed(player);
				// Michael: no music until the chase itself starts, so his appearance stays a scare.
				boolean starts = Vecna.active() ? noticed && coming || Chase.hunted(player) : Chase.hunted(player);
				if (held || state.shadowTicks == 0 && starts && dist <= cfg.musicChaseRange) {
					CHASED_UNTIL.put(player.getUUID(), now + cfg.musicChaseLingerTicks);
				}
				chased = state.shadowTicks == 0 && now < CHASED_UNTIL.getOrDefault(player.getUUID(), 0) && dist <= cfg.musicChaseRange + 8 || held;
				near = Vecna.active() && state.shadowTicks == 0 && dist <= cfg.musicStalkedRange;
				shapeChasing |= chased;
				exposedNow |= chased && (MatchManager.isGrabbed(player) || VecnaCurse.cursed(player));
			}
			if (!Bots.isBot(player)) {
				if (Vecna.active()) {
					moods.put(player.getUUID(), vecnaMood(player, shape, state, now, chased));
					continue;
				}
				boolean escaped = !chased && now < ESCAPED_UNTIL.getOrDefault(player.getUUID(), 0);
				moods.put(player.getUUID(), chased ? PROXIMITY * 10 + stunVariant(state) : escaped ? SILENT * 10 + 1 : near ? BEING_STALKED * 10 : AMBIENCE * 10);
			}
		}
		if (shape != null && !Bots.isBot(shape)) {
			if (Vecna.active()) {
				moods.put(shape.getUUID(), vecnaKillerMood(now, shapeChasing, exposedNow));
			} else {
				moods.put(shape.getUUID(), shapeChasing ? PROXIMITY * 10 + stunVariant(state) : STALKING * 10 + (state == null ? 1 : state.level()));
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(player)) {
				continue;
			}
			int mood = MatchManager.role(player) == Role.NONE ? OFF * 10 : moods.getOrDefault(player.getUUID(), AMBIENCE * 10);
			// Michael's kills silence only him; the survivors' music plays on.
			if (mood >= 0 && now < silentUntil && (Vecna.active() || player == shape)) {
				mood = SILENT * 10;
			}
			Integer sent = SENT.get(player.getUUID());
			if (sent == null || sent != mood) {
				SENT.put(player.getUUID(), mood);
				ServerPlayNetworking.send(player, new HwNet.MusicMood(Math.floorDiv(mood, 10), Math.floorMod(mood, 10)));
			}
		}
	}

	/** Vecna hears the chase file's chase parts while he hunts (Chase, Exposed Chase, Chase again), then Post Chase Terror Radius; otherwise his own calm music. */
	private static int vecnaKillerMood(int now, boolean chasing, boolean exposed) {
		if (chasing) {
			killerExposed |= exposed;
			killerMark = exposed ? 5 : killerExposed ? 6 : 4;
			killerPostUntil = now + 400;
			return VECNA_CHASE * 10 + killerMark;
		}
		killerExposed = false;
		if (killerMark != 0 && now < killerPostUntil) {
			killerMark = 7;
			return VECNA_CHASE * 10 + 7;
		}
		killerMark = 0;
		return VECNA_CALM * 10;
	}

	/** Which of the four Proximity compositions: by how close he is to being knocked down, the last while he is. */
	private static int stunVariant(ShapeState state) {
		if (state == null) {
			return 1;
		}
		if (state.stunnedTicks > 0) {
			return 4;
		}
		return state.stun < 0.34f ? 1 : state.stun < 0.67f ? 2 : 3;
	}

	/**
	 * Vecna's chase file by its marks: Terror Radius (1), Approaching (2), Proximity & Close (3), Chase (4), Exposed
	 * Chase (5: held, or cursed), Chase again (6: after an exposed one), Post Chase Terror Radius (7: after the chase,
	 * while he is still near). Each part loops until the state changes. Beyond his reach: the survivors' calm music.
	 */
	private static int vecnaMood(ServerPlayer player, ServerPlayer shape, ShapeState state, int now, boolean chased) {
		UUID id = player.getUUID();
		if (state == null || player.isSpectator() || shape.isSpectator()) {
			return VECNA_SURVIVOR * 10;
		}
		HwConfig cfg = HwConfig.get();
		double dist = player.distanceTo(shape);
		int last = CHASE_MARK.getOrDefault(id, 0);
		if (chased) {
			if (MatchManager.isGrabbed(player) || VecnaCurse.cursed(player)) {
				EXPOSED.add(id);
				CHASE_MARK.put(id, 5);
			} else {
				CHASE_MARK.put(id, EXPOSED.contains(id) ? 6 : 4);
			}
			POST_UNTIL.put(id, now + 600);
			return VECNA_CHASE * 10 + CHASE_MARK.get(id);
		}
		EXPOSED.remove(id);
		boolean afterChase = last >= 4;
		if (afterChase && now < POST_UNTIL.getOrDefault(id, 0) && dist <= cfg.musicStalkedRange + 8) {
			CHASE_MARK.put(id, 7);
			return VECNA_CHASE * 10 + 7;
		}
		if (state.shadowTicks > 0 || dist > cfg.musicStalkedRange) {
			CHASE_MARK.remove(id);
			return VECNA_SURVIVOR * 10;
		}
		// Bands by distance; moving out of a closer band takes two blocks more, so the music does not flicker.
		double slack = 2;
		int mark = dist <= 12 + (last == 3 ? slack : 0) ? 3 : dist <= 18 + (last == 2 ? slack : 0) ? 2 : 1;
		CHASE_MARK.put(id, mark);
		return VECNA_CHASE * 10 + mark;
	}

	/** Got away from him (Chase): the chase music is cut off at once, and silence for a few seconds. */
	public static void escaped(ServerPlayer player, int now) {
		if (Vecna.active()) {
			CHASED_UNTIL.remove(player.getUUID());
			SAW_SHAPE.remove(player.getUUID());
			CHASE_MARK.put(player.getUUID(), 7);
			POST_UNTIL.put(player.getUUID(), now + 600);
			return;
		}
		CHASED_UNTIL.remove(player.getUUID());
		SAW_SHAPE.remove(player.getUUID());
		ESCAPED_UNTIL.put(player.getUUID(), now + 70);
	}

	/** A player left or joined: send their music afresh. */
	public static void forget(ServerPlayer player) {
		SENT.remove(player.getUUID());
	}
}
