package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Survivors' (and bots') panic, 0..1. It rises while they keep their eyes on Michael (faster close up), when
 * he strikes or grabs them, when they find a body. It ebbs away whenever he is far off and no body is near,
 * wherever they are. Panic brings on the heartbeat, a shaking view and heavy breathing; the breath comes back
 * slower, and a gun shakes in the hand.
 */
public final class Panic {
	private static final Map<UUID, Float> VALUES = new HashMap<>();

	private Panic() {
	}

	public static float value(Entity who) {
		return VALUES.getOrDefault(who.getUUID(), 0f);
	}

	/** A shock: a blow, a grab, a body found. */
	public static void add(LivingEntity who, float amount) {
		if (who instanceof ServerPlayer p && MatchManager.role(p).survivor() && Match.running()) {
			float shock = amount * (Perks.has(p, Perks.Perk.UNPHASED) ? HwConfig.get().perkUnphasedFactor : 1f);
			VALUES.merge(p.getUUID(), shock, (a, b) -> Math.min(1f, a + b));
			AiStats.add(p, "panic.shock");
		}
	}

	/** For tests. */
	public static void set(ServerPlayer who, float value) {
		VALUES.put(who.getUUID(), value);
	}

	public static void clear() {
		VALUES.clear();
	}

	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			VALUES.clear();
			return;
		}
		HwConfig cfg = HwConfig.get();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!MatchManager.role(p).survivor() || p.isSpectator()) {
				VALUES.remove(p.getUUID());
				continue;
			}
			float v = VALUES.getOrDefault(p.getUUID(), 0f);
			ServerPlayer seen = Exits.isSeated(p) || Lockers.isInside(p) ? null
				: Senses.seenShape(p, cfg.panicSeeRange, cfg.panicSeeDarkRange, cfg.panicSeeFovDeg);
			double nearest = nearestShape((ServerLevel) p.level(), p);
			if (seen != null) {
				float rate = 1f / Math.max(1, cfg.panicSeeSeconds * 20f) * (Perks.has(p, Perks.Perk.UNPHASED) ? cfg.perkUnphasedFactor : 1f);
				v += p.distanceTo(seen) < cfg.panicNearDistance ? rate * 2 : rate;
			} else if (nearest > cfg.panicCalmDistance && !Police.bodyNear(p.position(), cfg.panicBodyRange) && !MatchManager.isGrabbed(p)) {
				v -= 1f / Math.max(1, cfg.panicCalmSeconds * 20f);
			}
			v = Math.max(0f, Math.min(1f, v));
			if (v > 0f) {
				VALUES.put(p.getUUID(), v);
			} else {
				VALUES.remove(p.getUUID());
			}
		}
	}

	private static double nearestShape(ServerLevel level, ServerPlayer p) {
		double best = Double.MAX_VALUE;
		for (ServerPlayer s : level.players()) {
			if (MatchManager.role(s) == Role.SHAPE && !s.isSpectator()) {
				best = Math.min(best, s.distanceTo(p));
			}
		}
		return best;
	}
}
