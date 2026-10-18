package dev.halloween.game;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who looks like whom in a match: the Shape is Michael Myers, deputies wear the police uniform, and
 * survivors are the survivors of Halloween: The Game. A bot named after one of them looks like them;
 * people get the first one nobody else is this match. Outside a match everyone keeps their own skin.
 */
public final class Skins {
	/** The survivors (textures/entity/skin/{name}.png); the first seven have slim arms. */
	public static final List<String> SURVIVORS = List.of("laurie", "annie", "lynda", "rachel", "tanya", "jennifer", "alexis",
		"bob", "eric", "marcus", "thomas", "richard");
	public static final int SLIM_COUNT = 7;

	private static final Map<UUID, String> CHOSEN = new HashMap<>();

	private Skins() {
	}

	/** A new match: everyone picks afresh. */
	public static void clear() {
		CHOSEN.clear();
	}

	/**
	 * A new match: the people (not the bots) each get a survivor at random, any of them; the bots are named after the
	 * rest. Returns the ones taken.
	 */
	public static java.util.Set<String> deal(List<ServerPlayer> people, java.util.Random random) {
		List<String> free = new java.util.ArrayList<>(SURVIVORS);
		java.util.Collections.shuffle(free, random);
		java.util.Set<String> taken = new java.util.HashSet<>();
		for (ServerPlayer p : people) {
			if (free.isEmpty()) {
				break;
			}
			String name = free.removeFirst();
			CHOSEN.put(p.getUUID(), name);
			taken.add(name);
		}
		return taken;
	}

	/** The skin this player wears now, or null for their own. */
	static String skinOf(ServerPlayer player) {
		Role role = MatchManager.role(player);
		return switch (role) {
			case SHAPE -> "michael";
			case RUNT -> "runt";
			case DEPUTY -> "police";
			case CIVILIAN -> survivor(player);
			default -> null;
		};
	}

	private static String survivor(ServerPlayer player) {
		String chosen = CHOSEN.get(player.getUUID());
		if (chosen != null) {
			return chosen;
		}
		String own = player.getScoreboardName().toLowerCase(Locale.ROOT);
		if (!SURVIVORS.contains(own) || CHOSEN.containsValue(own)) {
			own = null;
			for (String name : SURVIVORS) {
				// Leave the bots' own names to them.
				if (!CHOSEN.containsValue(name) && !botNamed(player, name)) {
					own = name;
					break;
				}
			}
		}
		if (own == null) {
			own = SURVIVORS.get(Math.floorMod(player.getUUID().hashCode(), SURVIVORS.size()));
		}
		CHOSEN.put(player.getUUID(), own);
		return own;
	}

	private static boolean botNamed(ServerPlayer except, String name) {
		for (BotPlayer bot : Bots.all()) {
			if (bot != except && bot.getScoreboardName().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** Tells everyone who wears what. */
	public static void sync(MinecraftServer server) {
		HwNet.Skins skins = current(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) {
				ServerPlayNetworking.send(p, skins);
			}
		}
	}

	public static void syncTo(ServerPlayer player) {
		ServerPlayNetworking.send(player, current(player.level().getServer()));
	}

	private static HwNet.Skins current(MinecraftServer server) {
		List<Integer> ids = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String key = skinOf(p);
			if (key != null) {
				ids.add(p.getId());
				keys.add(key);
			}
		}
		return new HwNet.Skins(ids, keys);
	}
}
