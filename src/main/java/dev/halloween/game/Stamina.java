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
		float sentPanic;
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
		// As if they had just stopped running.
		s.restAt = player.level().getServer().getTickCount() + HwConfig.get().staminaRegenDelayTicks;
	}

	public static void clear() {
		STATES.clear();
	}

	/**
	 * A shove or a blow costs breath ({@link HwConfig#staminaActionCost}): false (and a word to them) when there
	 * isn't enough. Outside a match it costs nothing.
	 */
	public static boolean spend(ServerPlayer player) {
		State s = STATES.get(player.getUUID());
		if (s == null) {
			return true;
		}
		HwConfig cfg = HwConfig.get();
		if (s.value < cfg.staminaActionCost - 1e-4f) {
			player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("halloween.msg.no_breath")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
			return false;
		}
		s.value = Math.max(0f, s.value - cfg.staminaActionCost);
		s.restAt = player.level().getServer().getTickCount() + cfg.staminaRegenDelayTicks;
		if (s.value <= 0f) {
			s.exhausted = true;
		}
		return true;
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
				double runTicks = cfg.staminaRunTicks * (Perks.has(p, Perks.Perk.STAMINA) ? cfg.perkStaminaFactor : 1.0);
				s.value -= (float) (1.0 / Math.max(1, runTicks));
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
				// Panic takes the breath away: it comes back slower.
				float calm = (1f - cfg.panicRegenPenalty * Panic.value(p)) * (Perks.has(p, Perks.Perk.ADRENALINE) ? cfg.perkAdrenalineRegen : 1f)
					* (Runt.hexed(p) ? cfg.runtHexRegen : 1f);
				s.value = Math.min(1f, s.value + calm / Math.max(1, still ? cfg.staminaRestRegenTicks : cfg.staminaWalkRegenTicks));
			}
			if (s.exhausted && s.value >= cfg.staminaRecoverFraction) {
				s.exhausted = false;
			}
			// Out of breath, or panicking: everyone near hears the heavy breathing until it is caught (then it fades).
			float panic = Panic.value(p);
			// In his grab the choking takes over: no panting on top of it (it comes back after, if still out of breath).
			boolean breathing = !MatchManager.isGrabbed(p) && (s.exhausted || panic >= cfg.panicBreathOn || s.panting && panic >= cfg.panicBreathOn - 0.15f);
			if (breathing != s.panting) {
				s.panting = breathing;
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
		float panic = Panic.value(p);
		if (Math.abs(s.value - s.sent) < 0.02f && s.exhausted == s.sentExhausted && Math.abs(panic - s.sentPanic) < 0.03f && !full
			&& !(panic == 0f && s.sentPanic > 0f)) {
			return;
		}
		s.sent = s.value;
		s.sentExhausted = s.exhausted;
		s.sentPanic = panic;
		ServerPlayNetworking.send(p, new HwNet.StaminaSync(s.value, s.exhausted, panic));
	}
}
