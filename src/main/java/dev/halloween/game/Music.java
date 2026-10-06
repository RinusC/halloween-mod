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
 * <li>Survivors: Ambience when all is quiet, Being Stalked when he is close but not after them, Proximity
 * when he comes for them and they have seen him.</li>
 * <li>A kill silences everything for a moment.</li>
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

	private static final Map<UUID, Integer> SENT = new HashMap<>();
	private static final Map<UUID, Integer> SAW_SHAPE = new HashMap<>();
	private static final Map<UUID, Double> LAST_DISTANCE = new HashMap<>();
	private static final Map<UUID, Integer> CHASED_UNTIL = new HashMap<>();
	private static int silentUntil;

	private Music() {
	}

	public static void clear() {
		SAW_SHAPE.clear();
		LAST_DISTANCE.clear();
		CHASED_UNTIL.clear();
		silentUntil = 0;
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
				if (held || state.shadowTicks == 0 && noticed && dist <= cfg.musicChaseRange && coming) {
					CHASED_UNTIL.put(player.getUUID(), now + cfg.musicChaseLingerTicks);
				}
				chased = state.shadowTicks == 0 && now < CHASED_UNTIL.getOrDefault(player.getUUID(), 0) && dist <= cfg.musicChaseRange + 8 || held;
				near = state.shadowTicks == 0 && dist <= cfg.musicStalkedRange;
				shapeChasing |= chased;
			}
			if (!Bots.isBot(player)) {
				moods.put(player.getUUID(), chased ? PROXIMITY * 10 + stunVariant(state) : near ? BEING_STALKED * 10 : AMBIENCE * 10);
			}
		}
		if (shape != null && !Bots.isBot(shape)) {
			moods.put(shape.getUUID(), shapeChasing ? PROXIMITY * 10 + stunVariant(state) : STALKING * 10 + (state == null ? 1 : state.level()));
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(player)) {
				continue;
			}
			int mood = MatchManager.role(player) == Role.NONE ? OFF * 10 : moods.getOrDefault(player.getUUID(), AMBIENCE * 10);
			if (mood >= 0 && now < silentUntil) {
				mood = SILENT * 10;
			}
			Integer sent = SENT.get(player.getUUID());
			if (sent == null || sent != mood) {
				SENT.put(player.getUUID(), mood);
				ServerPlayNetworking.send(player, new HwNet.MusicMood(Math.floorDiv(mood, 10), Math.floorMod(mood, 10)));
			}
		}
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

	/** A player left or joined: send their music afresh. */
	public static void forget(ServerPlayer player) {
		SENT.remove(player.getUUID());
	}
}
