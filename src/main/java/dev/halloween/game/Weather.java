package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * Each match's night: clear, foggy or with a light rain (picked by {@link HwConfig#weatherWeights}). Fog closes
 * in on everyone's sight (the clients draw it thick) and shortens how far townsfolk, bots and police notice the
 * Shape; rain shortens it a little.
 */
public final class Weather {
	public static final int CLEAR = 0;
	public static final int FOG = 1;
	public static final int RAIN = 2;
	private static int current = CLEAR;
	/** Tests (and the tutorial) can ask for a night instead of rolling one. */
	public static int forced = -1;

	private Weather() {
	}

	public static void setup(ServerLevel level, RandomSource random) {
		current = forced >= 0 ? forced : roll(random);
		if (current == RAIN) {
			command(level, "weather rain");
		} else {
			command(level, "weather clear");
		}
		sync(level.getServer());
		Match.announce(level.getServer(), Component.translatable("halloween.weather." + key()).withStyle(ChatFormatting.GRAY));
		AiStats.add("*total", "weather." + key());
	}

	private static int roll(RandomSource random) {
		int[] w = HwConfig.get().weatherWeights;
		int total = 0;
		for (int i = 0; i < w.length && i < 3; i++) {
			total += Math.max(0, w[i]);
		}
		if (total <= 0) {
			return CLEAR;
		}
		int roll = random.nextInt(total);
		for (int i = 0; i < w.length && i < 3; i++) {
			roll -= Math.max(0, w[i]);
			if (roll < 0) {
				return i;
			}
		}
		return CLEAR;
	}

	public static void clear(MinecraftServer server) {
		if (current != CLEAR) {
			current = CLEAR;
			command(server.overworld(), "weather clear");
		}
		sync(server);
	}

	private static void command(ServerLevel level, String command) {
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
	}

	public static int current() {
		return current;
	}

	public static String key() {
		return current == FOG ? "fog" : current == RAIN ? "rain" : "clear";
	}

	/** How far anyone notices the Shape, against a clear night. */
	public static double sight() {
		return current == FOG ? HwConfig.get().fogSightFactor : current == RAIN ? HwConfig.get().rainSightFactor : 1.0;
	}

	public static void sync(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			syncTo(p);
		}
	}

	public static void syncTo(ServerPlayer player) {
		if (!Bots.isBot(player)) {
			ServerPlayNetworking.send(player, new HwNet.MatchWeather(current));
		}
	}
}
