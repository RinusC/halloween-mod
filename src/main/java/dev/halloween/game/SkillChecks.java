package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Reaction checks during long actions (calling the police, opening a cellar, the gate, fuel, a phone mended, a
 * townsperson talked round). Every few seconds one pops up, of the action's own kind (see {@link #kindFor}): a needle
 * round a ring, two arcs one after the other, a circle closing in on a band, a cursor along a bar. Pressing the
 * reaction key at the right moment takes {@link HwConfig#skillCheckBonusTicks} off the action. A miss costs nothing.
 */
public final class SkillChecks {
	private static final class Check {
		boolean open;
		long start;
		final CheckRound round = new CheckRound();
		int kind = HwNet.Check.RING;
		int nextAt;
		long simPress;
		boolean simHit;
		int bonus;
	}

	private static final Map<UUID, Check> CHECKS = new HashMap<>();

	private SkillChecks() {
	}

	/** The kind of check a long action has (by its progress label): dialling is a ring, cutting a chain a closing circle... */
	public static int kindFor(String label) {
		return switch (label) {
			case "halloween.hud.progress.open_cellar" -> HwNet.Check.PULSE;
			case "halloween.hud.progress.open_gate", "halloween.hud.progress.repair_phone" -> HwNet.Check.DOUBLE;
			case "halloween.hud.progress.refuel" -> HwNet.Check.BAR;
			case "halloween.hud.progress.seal_portal" -> HwNet.Check.PULSE;
			default -> HwNet.Check.RING;
		};
	}

	/** As below, a ring. */
	public static int tick(ServerPlayer player) {
		return tick(player, HwNet.Check.RING);
	}

	/** Runs the checks for one tick of a long action; returns the ticks earned (to add to its progress). */
	public static int tick(ServerPlayer player, int kind) {
		HwConfig cfg = HwConfig.get();
		int now = player.level().getServer().getTickCount();
		RandomSource random = player.getRandom();
		Check c = CHECKS.computeIfAbsent(player.getUUID(), id -> {
			Check fresh = new Check();
			fresh.nextAt = now + cfg.skillCheckFirstMinTicks + random.nextInt(Math.max(1, cfg.skillCheckFirstMaxTicks - cfg.skillCheckFirstMinTicks));
			return fresh;
		});
		int bonus = c.bonus;
		c.bonus = 0;
		c.kind = kind;
		if (!c.open && now >= c.nextAt) {
			open(player, c);
		} else if (c.open) {
			long elapsed = player.level().getGameTime() - c.start;
			int lost = c.round.lostAfter(cfg.skillCheckPeriodTicks);
			if (player instanceof BotPlayer && elapsed == c.simPress) {
				resolve(player, c, c.simHit);
			} else if (elapsed >= (lost > 0 ? lost : cfg.skillCheckPeriodTicks + 4)) {
				// The needle went all the way round: missed, no harm done.
				resolve(player, c, false);
			}
		}
		return bonus;
	}

	private static void open(ServerPlayer player, Check c) {
		HwConfig cfg = HwConfig.get();
		RandomSource random = player.getRandom();
		c.open = true;
		c.start = player.level().getGameTime();
		// (Narrower for Vecna's cursed one once they are broken: their hands shake.)
		float width = (c.kind == HwNet.Check.PULSE ? cfg.skillCheckZoneWidth * 1.2f : cfg.skillCheckZoneWidth) * VecnaCurse.checkFactor(player);
		c.round.roll(c.kind, width, 0, random);
		// A bot presses some time into it; whether it lands is a roll.
		c.simPress = 8 + random.nextInt(Math.max(1, cfg.skillCheckPeriodTicks - 10));
		c.simHit = random.nextFloat() < cfg.botSkillCheckChance;
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.QTE_HIT, SoundSource.PLAYERS, 0.5f, 1.8f);
		send(player, c);
	}

	private static void send(ServerPlayer player, Check c) {
		CheckRound r = c.round;
		ServerPlayNetworking.send(player, new HwNet.SkillCheck(true, r.zoneStart, r.zoneWidth, HwConfig.get().skillCheckPeriodTicks, c.start,
			r.kind, r.zone2, r.need, r.stage));
	}

	/** The reaction key was pressed. Returns true if a check was open (the press is used up here). */
	public static boolean press(ServerPlayer player) {
		Check c = CHECKS.get(player.getUUID());
		if (c == null || !c.open) {
			return false;
		}
		long elapsed = player.level().getGameTime() - c.start;
		if (elapsed < 2) {
			return true;
		}
		int result = c.round.press(elapsed, HwConfig.get().skillCheckPeriodTicks);
		if (result == CheckRound.PENDING) {
			// The first of two arcs: on to the second.
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.QTE_HIT, SoundSource.PLAYERS, 0.5f, 1.5f);
			send(player, c);
		} else {
			resolve(player, c, result == CheckRound.HIT);
		}
		return true;
	}

	private static void resolve(ServerPlayer player, Check c, boolean hit) {
		HwConfig cfg = HwConfig.get();
		RandomSource random = player.getRandom();
		c.open = false;
		c.nextAt = player.level().getServer().getTickCount() + cfg.skillCheckGapMinTicks
			+ random.nextInt(Math.max(1, cfg.skillCheckGapMaxTicks - cfg.skillCheckGapMinTicks));
		if (hit) {
			c.bonus += cfg.skillCheckBonusTicks;
			AiStats.add(player, "skillcheck.hit");
		}
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), hit ? HwSounds.QTE_HIT : HwSounds.QTE_MISS,
			SoundSource.PLAYERS, 0.8f, hit ? 1.3f : 0.8f);
		ServerPlayNetworking.send(player, HwNet.SkillCheck.INACTIVE);
	}

	/** The long action is over (done or abandoned). */
	public static void stop(ServerPlayer player) {
		Check c = CHECKS.remove(player.getUUID());
		if (c != null && c.open) {
			ServerPlayNetworking.send(player, HwNet.SkillCheck.INACTIVE);
		}
	}

	public static void clear() {
		CHECKS.clear();
	}
}
