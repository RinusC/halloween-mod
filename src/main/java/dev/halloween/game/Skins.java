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

	/** Tests: the survivor the first person gets (null: as picked or at random). */
	public static String forced;

	/**
	 * A new match: the people (not the bots) each get the survivor they picked in the character select (HeroPicks),
	 * if it is theirs and nobody got it first; the others one at random of those they have (open or bought). The bots
	 * are named after the rest. Returns the ones taken.
	 */
	public static java.util.Set<String> deal(List<ServerPlayer> people, java.util.Random random) {
		List<String> free = new java.util.ArrayList<>(SURVIVORS);
		java.util.Collections.shuffle(free, random);
		java.util.Set<String> taken = new java.util.HashSet<>();
		List<ServerPlayer> left = new ArrayList<>();
		for (ServerPlayer p : people) {
			String want = p == people.getFirst() && forced != null ? forced : HeroPicks.survivor(p.getUUID());
			if (want != null && free.contains(want) && (want.equals(forced) || Perks.ownsHero(p.level().getServer(), p.getUUID(), want))) {
				free.remove(want);
				CHOSEN.put(p.getUUID(), want);
				taken.add(want);
			} else {
				left.add(p);
			}
		}
		for (ServerPlayer p : left) {
			if (free.isEmpty()) {
				break;
			}
			String name = null;
			for (String s : free) {
				if (Perks.ownsHero(p.level().getServer(), p.getUUID(), s)) {
					name = s;
					break;
				}
			}
			// None of theirs left (more people than open survivors): whoever is.
			name = name != null ? name : free.getFirst();
			free.remove(name);
			CHOSEN.put(p.getUUID(), name);
			taken.add(name);
			if (HeroPicks.RANDOM.equals(HeroPicks.survivor(p.getUUID()))) {
				HeroPicks.dealtByChance(p.getUUID());
			}
		}
		return taken;
	}

	/** The survivor this person was dealt this match, or null. */
	public static String dealt(UUID id) {
		return CHOSEN.get(id);
	}

	/** Which survivor this player plays (see Heroes), or null when not one. */
	public static String heroOf(ServerPlayer player) {
		return MatchManager.role(player) == Role.CIVILIAN ? survivor(player) : null;
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
