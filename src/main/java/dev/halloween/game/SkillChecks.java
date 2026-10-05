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
 * Circular reaction checks during long actions (calling the police, talking a townsperson round). Every
 * few seconds a ring with a zone pops up; pressing the reaction key while the needle is in the zone
 * takes {@link HwConfig#skillCheckBonusTicks} off the action. A miss costs nothing.
 */
public final class SkillChecks {
	private static final class Check {
		boolean open;
		long start;
		float zoneStart;
		float zoneWidth;
		int nextAt;
		long simPress;
		boolean simHit;
		int bonus;
	}

	private static final Map<UUID, Check> CHECKS = new HashMap<>();

	private SkillChecks() {
	}

	/** Runs the checks for one tick of a long action; returns the ticks earned (to add to its progress). */
	public static int tick(ServerPlayer player) {
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
		if (!c.open && now >= c.nextAt) {
			open(player, c);
		} else if (c.open) {
			long elapsed = player.level().getGameTime() - c.start;
			if (player instanceof BotPlayer && elapsed == c.simPress) {
				resolve(player, c, c.simHit);
			} else if (elapsed >= cfg.skillCheckPeriodTicks + 4) {
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
		c.zoneWidth = cfg.skillCheckZoneWidth;
		c.zoneStart = 0.35f + random.nextFloat() * (0.55f - c.zoneWidth);
		// A bot presses around the zone; whether it lands is a roll.
		c.simPress = (long) ((c.zoneStart + c.zoneWidth / 2) * cfg.skillCheckPeriodTicks) + random.nextInt(3) - 1;
		c.simHit = random.nextFloat() < cfg.botSkillCheckChance;
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), HwSounds.QTE_HIT, SoundSource.PLAYERS, 0.5f, 1.8f);
		ServerPlayNetworking.send(player, new HwNet.SkillCheck(true, c.zoneStart, c.zoneWidth, cfg.skillCheckPeriodTicks, c.start));
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
		float needle = HwNet.needle(elapsed, HwConfig.get().skillCheckPeriodTicks);
		resolve(player, c, needle >= c.zoneStart && needle <= c.zoneStart + c.zoneWidth);
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
