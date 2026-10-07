package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Survivors' (and bots') stamina: running burns it ({@link HwConfig#staminaRunTicks} from full to empty),
 * it comes back a second after the run stops, faster standing still or hiding than walking. Run dry and you
 * are out of breath: no running until a third of it is back, and you pant (heard by anyone near, Michael too; see client.ClientPanting).
 */
public final class Stamina {
	private static final class State {
		float value = 1f;
		boolean exhausted;
		int restAt;
		boolean panting;
		double lastX;
		double lastZ;
		float sent = -1f;
		boolean sentExhausted;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private Stamina() {
	}

	public static boolean exhausted(Entity who) {
		State s = STATES.get(who.getUUID());
		return s != null && s.exhausted;
	}

	public static float value(Entity who) {
		State s = STATES.get(who.getUUID());
		return s == null ? 1f : s.value;
	}

	/** For tests: sets someone's stamina. */
	public static void set(ServerPlayer player, float value) {
		State s = STATES.computeIfAbsent(player.getUUID(), k -> new State());
		s.value = value;
		s.exhausted = value <= 0f;
	}

	public static void clear() {
		STATES.clear();
	}

	public static void tick(MinecraftServer server) {
		HwConfig cfg = HwConfig.get();
		int now = server.getTickCount();
		boolean on = Match.running() || Match.frozen();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!on || !MatchManager.role(p).survivor() || p.isSpectator()) {
				State gone = STATES.remove(p.getUUID());
				if (gone != null) {
					if (gone.panting) {
						pant(p, false);
					}
					sync(p, new State());
				}
				continue;
			}
			State s = STATES.computeIfAbsent(p.getUUID(), k -> new State());
			double dx = p.getX() - s.lastX;
			double dz = p.getZ() - s.lastZ;
			double moved = Math.sqrt(dx * dx + dz * dz);
			s.lastX = p.getX();
			s.lastZ = p.getZ();
			if (p.isSprinting() && moved > 0.08) {
				s.value -= 1f / Math.max(1, cfg.staminaRunTicks);
				s.restAt = now + cfg.staminaRegenDelayTicks;
				if (s.value <= 0f) {
					s.value = 0f;
					if (!s.exhausted) {
						s.exhausted = true;
						AiStats.add(p, "stamina.out");
					}
					p.setSprinting(false);
				}
			} else if (now >= s.restAt && s.value < 1f) {
				boolean still = moved < 0.02 || Lockers.isInside(p);
				s.value = Math.min(1f, s.value + 1f / Math.max(1, still ? cfg.staminaRestRegenTicks : cfg.staminaWalkRegenTicks));
			}
			if (s.exhausted && s.value >= cfg.staminaRecoverFraction) {
				s.exhausted = false;
			}
			if (s.exhausted != s.panting) {
				// Out of breath: everyone near hears the panting until it is caught (then it fades, a deep breath out).
				s.panting = s.exhausted;
				pant(p, s.panting);
				if (s.panting) {
					AiStats.add(p, "stamina.pant");
				}
			}
			sync(p, s);
		}
	}

	private static void pant(ServerPlayer who, boolean on) {
		HwNet.Pant payload = new HwNet.Pant(who.getId(), on, Voices.female(who), HwConfig.get().staminaPantVolume);
		for (ServerPlayer p : who.level().getServer().getPlayerList().getPlayers()) {
			if (!Bots.isBot(p) && p.level() == who.level() && p.distanceToSqr(who) < 48 * 48) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private static void sync(ServerPlayer p, State s) {
		if (Bots.isBot(p)) {
			return;
		}
		boolean full = s.value >= 1f && s.sent < 1f;
		if (Math.abs(s.value - s.sent) < 0.02f && s.exhausted == s.sentExhausted && !full) {
			return;
		}
		s.sent = s.value;
		s.sentExhausted = s.exhausted;
		ServerPlayNetworking.send(p, new HwNet.StaminaSync(s.value, s.exhausted));
	}
}
