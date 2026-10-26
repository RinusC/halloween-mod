package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who wants to play whom next match, picked in the character select (client.CharacterSelectScreen) between
 * matches. A survivor can be picked by one person only, and only if it is theirs (open from the start, or bought,
 * see Perks); the match deals the picked ones (Skins.deal) and fills the rest by chance from what each has. Wanting
 * to be the killer is a pick of its own on the other tab: of everyone who wants it one is drawn, and whoever was the
 * killer last match only if nobody else wants it; that one plays the killer they picked, the others the survivor
 * they picked. Nobody wants it: the killer is decided as before (the bot, or someone at random, with the killer of
 * /hw killer). Leaving one's survivor to chance earns a few points more at the end.
 */
public final class HeroPicks {
	public static final int SURVIVOR = 0;
	public static final int KILLER = 1;
	public static final int BUY = 2;
	public static final int READY = 3;
	public static final int PRACTICE = 4;
	public static final String RANDOM = "random";

	private static final Map<UUID, String> SURVIVORS = new HashMap<>();
	private static final Map<UUID, String> KILLERS = new HashMap<>();
	/** Who left it to chance this match (their bonus at the end). */
	private static final Set<UUID> BY_CHANCE = new HashSet<>();
	private static UUID lastKiller;
	/** The killer drawn for this match ("michael", "demogorgon", "vecna"), or null when the picks didn't decide it. */
	private static String matchKiller;
	private static HwNet.HeroLobby sent = HwNet.HeroLobby.EMPTY;

	private HeroPicks() {
	}

	private static boolean between() {
		return !Match.running() && !Match.frozen();
	}

	/** The survivor this person picked ("random", or null for none). */
	public static String survivor(UUID id) {
		return SURVIVORS.get(id);
	}

	public static String killer(UUID id) {
		return KILLERS.get(id);
	}

	/** This match's killer by the picks, or null (then /hw killer decides). */
	public static String matchKiller() {
		return matchKiller;
	}

	/** From the screen. */
	public static void act(ServerPlayer player, int action, String hero) {
		MinecraftServer server = player.level().getServer();
		UUID id = player.getUUID();
		switch (action) {
			case SURVIVOR -> {
				if (!between()) {
					return;
				}
				if (hero.isEmpty()) {
					SURVIVORS.remove(id);
				} else if (hero.equals(RANDOM)) {
					SURVIVORS.put(id, RANDOM);
				} else if (Skins.SURVIVORS.contains(hero) && Perks.ownsHero(server, id, hero)) {
					if (takenByOther(id, hero)) {
						Notices.send(player, Component.translatable("halloween.select.taken", Component.translatable("halloween.hero.name." + hero))
							.withStyle(ChatFormatting.GRAY));
						return;
					}
					SURVIVORS.put(id, hero);
				}
			}
			case KILLER -> {
				if (!between()) {
					return;
				}
				if (hero.isEmpty()) {
					KILLERS.remove(id);
				} else if (HeroInfo.killer(hero) && Perks.ownsHero(server, id, hero)) {
					KILLERS.put(id, hero);
				}
			}
			case BUY -> {
				if (Perks.buyHero(player, hero) && between()) {
					// Bought: picked straight away, if nobody has it.
					act(player, HeroInfo.killer(hero) ? KILLER : SURVIVOR, hero);
				}
			}
			case READY -> Lobby.toggleReady(player);
			case PRACTICE -> {
				if (between()) {
					Notices.send(player, Tutorial.start(server, player));
				}
			}
			default -> {
			}
		}
		broadcast(server);
	}

	private static boolean takenByOther(UUID id, String hero) {
		for (Map.Entry<UUID, String> e : SURVIVORS.entrySet()) {
			if (!e.getKey().equals(id) && e.getValue().equals(hero)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * At the start of a match: one of those who want the killer, drawn (the last match's killer only if nobody else
	 * wants it). Null if nobody does, or nobody of them is here.
	 */
	public static ServerPlayer drawKiller(List<ServerPlayer> players, java.util.Random random) {
		matchKiller = null;
		List<ServerPlayer> keen = new ArrayList<>();
		for (ServerPlayer p : players) {
			String k = KILLERS.get(p.getUUID());
			if (k != null && !Bots.isBot(p) && Perks.ownsHero(p.level().getServer(), p.getUUID(), k)) {
				keen.add(p);
			}
		}
		if (keen.isEmpty()) {
			return null;
		}
		List<ServerPlayer> fresh = new ArrayList<>(keen);
		fresh.removeIf(p -> p.getUUID().equals(lastKiller));
		List<ServerPlayer> from = fresh.isEmpty() ? keen : fresh;
		ServerPlayer chosen = from.get(random.nextInt(from.size()));
		matchKiller = KILLERS.get(chosen.getUUID());
		lastKiller = chosen.getUUID();
		return chosen;
	}

	/** A killer chosen some other way (the bot, at random, /hw start): the next draw doesn't hold it against them. */
	public static void noteKiller(ServerPlayer shape) {
		if (matchKiller == null) {
			lastKiller = shape == null || Bots.isBot(shape) ? null : shape.getUUID();
		}
	}

	/** Skins.deal: this person was dealt someone by chance though they asked for it. */
	static void dealtByChance(UUID id) {
		BY_CHANCE.add(id);
	}

	/** The match is over: the bonus for those who left it to chance. */
	public static void bonus(Map<UUID, Integer> scores) {
		int bonus = HwConfig.get().heroRandomBonus;
		for (UUID id : BY_CHANCE) {
			if (scores.containsKey(id)) {
				scores.merge(id, bonus, Integer::sum);
			}
		}
		BY_CHANCE.clear();
	}

	/** Every second or so, and whenever something changes: everyone sees the lobby as it stands. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 == 0) {
			broadcast(server);
		}
	}

	private static void broadcast(MinecraftServer server) {
		List<String> names = new ArrayList<>();
		List<String> survivors = new ArrayList<>();
		List<String> killers = new ArrayList<>();
		List<Boolean> ready = new ArrayList<>();
		Set<UUID> here = new HashSet<>();
		List<ServerPlayer> humans = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(p)) {
				continue;
			}
			humans.add(p);
			here.add(p.getUUID());
			names.add(p.getScoreboardName());
			survivors.add(SURVIVORS.getOrDefault(p.getUUID(), ""));
			killers.add(KILLERS.getOrDefault(p.getUUID(), ""));
			ready.add(Lobby.ready(p));
		}
		SURVIVORS.keySet().retainAll(here);
		KILLERS.keySet().retainAll(here);
		HwNet.HeroLobby state = new HwNet.HeroLobby(names, survivors, killers, ready, between() ? Lobby.countdownSeconds() : -1);
		if (state.equals(sent)) {
			return;
		}
		sent = state;
		for (ServerPlayer p : humans) {
			ServerPlayNetworking.send(p, state);
		}
	}

	/** Someone new: the lobby as it stands. */
	public static void syncTo(ServerPlayer player) {
		ServerPlayNetworking.send(player, sent);
	}

	/** For tests: forget every pick. */
	public static void clear() {
		SURVIVORS.clear();
		KILLERS.clear();
		BY_CHANCE.clear();
		lastKiller = null;
		matchKiller = null;
		sent = HwNet.HeroLobby.EMPTY;
	}
}
