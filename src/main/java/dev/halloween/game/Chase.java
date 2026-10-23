package dev.halloween.game;

import dev.halloween.Role;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Getting away. Once Michael (his player or the bot) has had a survivor in sight close by, they are in a chase, and
 * every moment out of his sight counts towards losing him: about four seconds, sooner crouched or standing still
 * (quiet), later running (footsteps), at once far off. In sight again it drains fast. When it fills, they are
 * clear: nothing is said, but their music is cut off at once (Music) and for a few seconds their footsteps reach
 * nobody (Noise).
 */
public final class Chase {
	/** Seen this close, they are in a chase. */
	private static final double RANGE = 26;
	private static final float OUT_OF_SIGHT_SECONDS = 4f;
	/** After getting away: this long their footsteps give nothing away. */
	private static final int QUIET_TICKS = 100;

	private static final class Run {
		boolean on;
		float lost;
		int seenAt = -1000;
		Vec3 last;
	}

	private static final Map<UUID, Run> RUNS = new HashMap<>();
	private static final Map<UUID, Integer> QUIET_UNTIL = new HashMap<>();

	private Chase() {
	}

	public static void clear() {
		RUNS.clear();
		QUIET_UNTIL.clear();
	}

	/** Chased and seen within the last two seconds. */
	public static boolean hunted(ServerPlayer player) {
		Run r = RUNS.get(player.getUUID());
		return r != null && r.on && player.level().getServer().getTickCount() - r.seenAt <= 40;
	}

	/** Just got away: their footsteps are not heard for a while. */
	public static boolean quiet(ServerPlayer player) {
		return player.level().getServer().getTickCount() < QUIET_UNTIL.getOrDefault(player.getUUID(), 0);
	}

	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		if (now % 5 != 0) {
			return;
		}
		if (!Match.running()) {
			clear();
			return;
		}
		ServerLevel level = server.overworld();
		ServerPlayer shape = Senses.shape(level);
		ShapeState st = shape == null ? null : MatchManager.shapeState(shape);
		if (st == null || shape.isSpectator()) {
			RUNS.clear();
			return;
		}
		for (ServerPlayer p : level.players()) {
			Role role = MatchManager.role(p);
			if (!role.survivor() || p.isSpectator() || Exits.isSeated(p) || MatchManager.isGrabbed(p)) {
				continue;
			}
			Run r = RUNS.computeIfAbsent(p.getUUID(), k -> new Run());
			Vec3 pos = p.position();
			boolean still = r.last != null && r.last.distanceToSqr(pos) < 0.01;
			r.last = pos;
			double dist = p.distanceTo(shape);
			boolean sees = st.shadowTicks == 0 && !Lockers.isInside(p) && dist <= 48
				&& Sight.canSee(level, shape.getEyePosition(), FocusPoints.bodyCenter(p), shape);
			if (sees) {
				if (dist <= RANGE) {
					r.on = true;
					r.seenAt = now;
				}
				r.lost = Math.max(0f, r.lost - 0.5f);
				continue;
			}
			if (!r.on) {
				continue;
			}
			float rate = 0.25f / OUT_OF_SIGHT_SECONDS;
			rate *= p.isCrouching() || still || Lockers.isInside(p) ? 1.75f : p.isSprinting() ? 0.6f : 1f;
			if (dist > 40) {
				rate *= 3f;
			}
			r.lost += rate;
			if (r.lost >= 1f) {
				escape(p, r, now);
			}
		}
	}

	private static void escape(ServerPlayer p, Run r, int now) {
		r.on = false;
		r.lost = 0f;
		QUIET_UNTIL.put(p.getUUID(), now + QUIET_TICKS);
		Music.escaped(p, now);
		AiStats.add(p, "chase.escaped");
	}
}
