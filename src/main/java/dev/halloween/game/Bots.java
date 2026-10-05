package dev.halloween.game;

import com.mojang.authlib.GameProfile;
import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Bot civilians that fill the match up to {@link HwConfig#botsFillTo} when there aren't enough people. */
public final class Bots {
	private static final Map<UUID, BotPlayer> BOTS = new LinkedHashMap<>();
	private static final List<BotPlayer> LEAVING = new ArrayList<>();

	private Bots() {
	}

	public static boolean isBot(Player player) {
		return player instanceof BotPlayer;
	}

	public static List<BotPlayer> all() {
		return new ArrayList<>(BOTS.values());
	}

	/** Joins a bot to the server as if a player logged in, standing at {@code pos}. */
	public static BotPlayer spawn(MinecraftServer server, String name, BlockPos pos) {
		ServerLevel level = server.overworld();
		UUID id = UUID.nameUUIDFromBytes(("halloween-bot:" + name).getBytes(StandardCharsets.UTF_8));
		ServerPlayer existing = server.getPlayerList().getPlayer(id);
		if (existing instanceof BotPlayer bot) {
			return bot;
		}
		GameProfile profile = new GameProfile(id, name);
		BotPlayer bot = new BotPlayer(server, level, profile);
		bot.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
		server.getPlayerList().placeNewPlayer(new BotPlayer.NowhereConnection(), bot, CommonListenerCookie.createInitial(profile, false));
		bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		bot.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
		bot.brain = new BotBrain(bot);
		BOTS.put(id, bot);
		Halloween.LOGGER.info("Bot {} joined", name);
		return bot;
	}

	/** Takes a bot off the server at the end of the tick (it escaped or died and won't come back). */
	public static void leaveLater(ServerPlayer player) {
		if (player instanceof BotPlayer bot && !LEAVING.contains(bot)) {
			LEAVING.add(bot);
		}
	}

	public static void removeAll(MinecraftServer server) {
		for (BotPlayer bot : new ArrayList<>(BOTS.values())) {
			remove(server, bot);
		}
		LEAVING.clear();
	}

	private static void remove(MinecraftServer server, BotPlayer bot) {
		BOTS.remove(bot.getUUID());
		if (server.getPlayerList().getPlayer(bot.getUUID()) == bot) {
			server.getPlayerList().remove(bot);
		}
	}

	public static void tick(MinecraftServer server) {
		for (BotPlayer bot : LEAVING) {
			remove(server, bot);
		}
		LEAVING.clear();
		int now = server.getTickCount();
		for (BotPlayer bot : BOTS.values()) {
			if (bot.brain != null && !bot.isRemoved()) {
				bot.brain.tick(now);
			}
		}
	}

	/** Something alarming happened at {@code pos} (a townsperson screamed). */
	public static void alert(Vec3 pos, int now) {
		for (BotPlayer bot : BOTS.values()) {
			if (bot.brain != null) {
				bot.brain.hear(pos, now);
			}
		}
	}

	static boolean claimedByOther(BlockPos target, BotPlayer me) {
		for (BotPlayer bot : BOTS.values()) {
			if (bot != me && bot.brain != null && target.equals(bot.brain.claimed())) {
				return true;
			}
		}
		return false;
	}

	static boolean othersOn(String key, BotPlayer me) {
		for (BotPlayer bot : BOTS.values()) {
			if (bot != me && bot.brain != null && bot.brain.isTask(key)) {
				return true;
			}
		}
		return false;
	}
}
