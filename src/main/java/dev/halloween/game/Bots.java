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
		bot.wire = new BotPlayer.NowhereConnection();
		server.getPlayerList().placeNewPlayer(bot.wire, bot, CommonListenerCookie.createInitial(profile, false));
		bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		bot.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
		bot.brain = new BotBrain(bot);
		BOTS.put(id, bot);
		Halloween.LOGGER.info("Bot {} joined", name);
		return bot;
	}

	/**
	 * A bot body with no civilian's mind and not counted with the others (the Runt's: game.Runt drives it and
	 * takes it off the server again).
	 */
	public static BotPlayer spawnLoose(MinecraftServer server, String name, BlockPos pos) {
		ServerLevel level = server.overworld();
		UUID id = UUID.nameUUIDFromBytes(("halloween-bot:" + name).getBytes(StandardCharsets.UTF_8));
		ServerPlayer existing = server.getPlayerList().getPlayer(id);
		if (existing instanceof BotPlayer bot) {
			return bot;
		}
		GameProfile profile = new GameProfile(id, name);
		BotPlayer bot = new BotPlayer(server, level, profile);
		bot.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);
		bot.wire = new BotPlayer.NowhereConnection();
		server.getPlayerList().placeNewPlayer(bot.wire, bot, CommonListenerCookie.createInitial(profile, false));
		bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		bot.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, java.util.Set.of(), 0f, 0f, false);
		Halloween.LOGGER.info("Bot {} joined (loose)", name);
		return bot;
	}

	/** Takes a bot off the server at the end of the tick (it escaped or died and won't come back). */
	public static void leaveLater(ServerPlayer player) {
		if (player instanceof BotPlayer bot && !LEAVING.contains(bot)) {
			LEAVING.add(bot);
		}
	}

	public static void removeAll(MinecraftServer server) {
		LAST_CALLOUT.clear();
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
				if (now % 20 == 0 && !bot.isSpectator() && !Nav.inArea(bot.blockPosition())) {
					// Got out of the match area somehow (a low fence on a custom map...): back to a spawn.
					backInside(bot);
				}
				bot.brain.tick(now);
			}
		}
	}

	private static void backInside(BotPlayer bot) {
		BlockPos best = null;
		for (BlockPos p : MapPoints.get(bot.level()).list(MapPoints.Type.SPAWN_CIVILIAN)) {
			if (best == null || p.distSqr(bot.blockPosition()) < best.distSqr(bot.blockPosition())) {
				best = p;
			}
		}
		if (best != null) {
			bot.teleportTo(bot.level(), best.getX() + 0.5, best.getY(), best.getZ() + 0.5, java.util.Set.of(), bot.getYRot(), 0f, false);
			AiStats.add(bot, "out_of_area");
			Halloween.LOGGER.warn("Bot {} left the match area, put back at {}", bot.getPlainTextName(), best);
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

	/** Last chat callout per bot, so they don't spam. */
	private static final Map<UUID, Integer> LAST_CALLOUT = new java.util.HashMap<>();

	/**
	 * A bot spotted the Shape: the other bots hear it like over voice chat, and people on the civilians' side
	 * get a short callout in chat ("Laurie: Michael by the car!").
	 */
	static void callout(BotPlayer from, Vec3 shapePos, int now) {
		HwConfig cfg = HwConfig.get();
		for (BotPlayer bot : BOTS.values()) {
			if (bot != from && bot.brain != null && !bot.isSpectator()) {
				bot.brain.hear(shapePos, now);
			}
		}
		Integer last = LAST_CALLOUT.get(from.getUUID());
		if (last != null && now - last < cfg.botCalloutCooldownTicks) {
			return;
		}
		LAST_CALLOUT.put(from.getUUID(), now);
		net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.translatable("halloween.bot.callout",
			from.getDisplayName(), Landmarks.describe(from.level(), shapePos)).withStyle(net.minecraft.ChatFormatting.GOLD);
		for (ServerPlayer p : from.level().getServer().getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer) && MatchManager.role(p).survivor()) {
				Notices.send(p, line);
			}
		}
	}

	/** What a bot is up to (simulation, debugging): roam, flee, hide, fight, hold, lock, locker. */
	public static String modeOf(ServerPlayer player) {
		return player instanceof BotPlayer bot && bot.brain != null ? bot.brain.modeName() : "-";
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
