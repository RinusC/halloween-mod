package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Survivor perks. What a player scores as a survivor goes into their purse (kept with the world, match after
 * match); perks are bought with it and stay theirs for good. Between matches each player puts up to
 * {@link HwConfig#perkSlots} of their perks into the build (the perk screen, key P); the build holds for the
 * whole next match.
 */
public final class Perks extends SavedData {
	public enum Perk {
		/** Searching furniture takes less time. */
		QUICK_SEARCH(1200),
		/** Runs longer before running out of breath. */
		STAMINA(1800),
		/** Footsteps carry half as far. */
		QUIET(2250),
		/** A little more health. */
		TOUGH(3000),
		/** The breath comes back faster. */
		ADRENALINE(1800),
		/** Doors you lock take more blows to break. */
		BARRICADE(1500),
		/** Less cut by glass climbing through windows. */
		GLASS_WALKER(1200),
		/** Firecrackers blind Michael longer. */
		BRIMSTONE(1200),
		/** Melee blows stun him more. */
		CONTENDER(2250),
		/** Thrown things stun him more. */
		LETHAL_PITCH(1800),
		/** He stalks you more slowly. */
		ELUSORY(3000),
		/** Med kits work faster, the spray heals more. */
		FIELD_TRIAGE(1200),
		/** In his grab, your zone is wider. */
		SLIPPERY(3750),
		/** Panic builds up more slowly. */
		UNPHASED(1500),
		/** Wounded, you move faster. */
		STUBBORN(1800),
		/** Fixing something may leave the repair kit whole. */
		REPAIR_EXPERT(1500),
		/** Unlocking doors takes half the time. */
		TRESPASSER(1200),
		/** Talking townsfolk round and calling the police go faster. */
		SMOOTH_TALKER(1500),
		/** Other survivors and townsfolk close by show through walls. */
		WITNESS(2250);

		private final int defaultCost;

		Perk(int defaultCost) {
			this.defaultCost = defaultCost;
		}

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public int cost() {
			return HwConfig.get().perkCosts.getOrDefault(this.key(), this.defaultCost);
		}

		public static Perk byKey(String key) {
			for (Perk p : values()) {
				if (p.key().equals(key)) {
					return p;
				}
			}
			return null;
		}
	}

	/** Buy, put into the build, take out of the build. */
	public static final int BUY = 0;
	public static final int EQUIP = 1;
	public static final int UNEQUIP = 2;

	/**
	 * A purse and what was bought with it: perks, and heroes (see HeroPicks); and each hero's record (matches played,
	 * escapes, best score).
	 */
	private record Profile(int points, List<String> owned, List<String> equipped, List<String> heroes, Map<String, List<Integer>> records) {
		static final Codec<Profile> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("points").forGetter(Profile::points),
			Codec.STRING.listOf().optionalFieldOf("owned", List.of()).forGetter(Profile::owned),
			Codec.STRING.listOf().optionalFieldOf("equipped", List.of()).forGetter(Profile::equipped),
			Codec.STRING.listOf().optionalFieldOf("heroes", List.of()).forGetter(Profile::heroes),
			Codec.unboundedMap(Codec.STRING, Codec.INT.listOf()).optionalFieldOf("records", Map.of()).forGetter(Profile::records)).apply(i, Profile::new));

		Profile(int points, List<String> owned, List<String> equipped) {
			this(points, owned, equipped, List.of(), Map.of());
		}

		Profile withPerks(int points, List<String> owned, List<String> equipped) {
			return new Profile(points, owned, equipped, this.heroes, this.records);
		}
	}

	private static final Codec<Perks> CODEC = Codec.unboundedMap(Codec.STRING, Profile.CODEC).xmap(Perks::fromMap, p -> Map.copyOf(p.profiles));
	public static final SavedDataType<Perks> TYPE = new SavedDataType<>(Halloween.id("perks"), Perks::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	/** Who plays the current match with which perks. */
	private static final Map<UUID, Set<Perk>> ACTIVE = new HashMap<>();

	private final Map<String, Profile> profiles = new HashMap<>();

	public Perks() {
	}

	private static Perks fromMap(Map<String, Profile> map) {
		Perks p = new Perks();
		p.profiles.putAll(map);
		return p;
	}

	private static Perks get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	private Profile profile(UUID id) {
		return this.profiles.getOrDefault(id.toString(), new Profile(0, List.of(), List.of()));
	}

	private void put(UUID id, Profile profile) {
		this.profiles.put(id.toString(), profile);
		this.setDirty();
	}

	public static int points(MinecraftServer server, UUID id) {
		return get(server).profile(id).points();
	}

	/** The perks this player has put into their build (bought ones only). */
	public static Set<Perk> equipped(MinecraftServer server, UUID id) {
		Profile profile = get(server).profile(id);
		Set<Perk> out = EnumSet.noneOf(Perk.class);
		for (String key : profile.equipped()) {
			Perk perk = Perk.byKey(key);
			if (perk != null && profile.owned().contains(key)) {
				out.add(perk);
			}
		}
		return out;
	}

	public static boolean owns(MinecraftServer server, UUID id, Perk perk) {
		return get(server).profile(id).owned().contains(perk.key());
	}

	/** Does this survivor play the current match with this perk? */
	public static boolean has(net.minecraft.world.entity.Entity who, Perk perk) {
		Set<Perk> set = ACTIVE.get(who.getUUID());
		// Or by who they are (Heroes).
		return set != null && set.contains(perk) || who instanceof ServerPlayer sp && Heroes.innate(sp) == perk;
	}

	public static boolean has(UUID id, Perk perk) {
		Set<Perk> set = id == null ? null : ACTIVE.get(id);
		return set != null && set.contains(perk);
	}

	/** Extra health the perk gives (0 without it). */
	public static float extraHealth(ServerPlayer player) {
		return has(player, Perk.TOUGH) ? HwConfig.get().perkToughHealth : 0f;
	}

	/** At the start of a match: everybody's build holds until it ends (bots have none). */
	public static void begin(MinecraftServer server, List<ServerPlayer> players) {
		ACTIVE.clear();
		for (ServerPlayer p : players) {
			if (Bots.isBot(p)) {
				continue;
			}
			Set<Perk> build = equipped(server, p.getUUID());
			if (!build.isEmpty()) {
				ACTIVE.put(p.getUUID(), build);
				build.forEach(perk -> AiStats.add(p, "perk." + perk.key()));
			}
		}
	}

	public static void end() {
		ACTIVE.clear();
	}

	/** For tests: plays the current match with these perks. */
	public static void activate(ServerPlayer player, Perk... perks) {
		Set<Perk> set = EnumSet.noneOf(Perk.class);
		set.addAll(List.of(perks));
		ACTIVE.put(player.getUUID(), set);
	}

	/** Buys a perk, or puts one into the build or takes it out (the build only between matches). */
	public static void act(ServerPlayer player, String key, int action) {
		MinecraftServer server = player.level().getServer();
		Perk perk = Perk.byKey(key);
		if (perk == null) {
			sync(player);
			return;
		}
		Perks data = get(server);
		Profile p = data.profile(player.getUUID());
		List<String> owned = new ArrayList<>(p.owned());
		List<String> equipped = new ArrayList<>(p.equipped());
		int points = p.points();
		switch (action) {
			case BUY -> {
				if (owned.contains(perk.key()) || points < perk.cost()) {
					sync(player);
					return;
				}
				points -= perk.cost();
				owned.add(perk.key());
				Notices.send(player, Component.translatable("halloween.perk.bought", Component.translatable("halloween.perk." + perk.key()))
					.withStyle(ChatFormatting.GREEN));
				// A free slot: straight into the build (between matches).
				if (!Match.running() && !Match.frozen() && equipped.size() < HwConfig.get().perkSlots) {
					equipped.add(perk.key());
				}
			}
			case EQUIP, UNEQUIP -> {
				if (Match.running() || Match.frozen()) {
					Notices.send(player, Component.translatable("halloween.perk.not_now").withStyle(ChatFormatting.GRAY));
					sync(player);
					return;
				}
				if (action == EQUIP) {
					if (!owned.contains(perk.key()) || equipped.contains(perk.key()) || equipped.size() >= HwConfig.get().perkSlots) {
						sync(player);
						return;
					}
					equipped.add(perk.key());
				} else {
					equipped.remove(perk.key());
				}
			}
			default -> {
				sync(player);
				return;
			}
		}
		data.put(player.getUUID(), p.withPerks(points, owned, equipped));
		sync(player);
	}

	/** The match is over: each person's survivor score goes into their purse. */
	public static void award(MinecraftServer server, Map<UUID, Integer> scores) {
		Perks data = get(server);
		for (Map.Entry<UUID, Integer> e : scores.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null || Bots.isBot(player) || e.getValue() <= 0) {
				continue;
			}
			Profile before = data.profile(e.getKey());
			int total = before.points() + e.getValue();
			data.put(e.getKey(), before.withPerks(total, before.owned(), before.equipped()));
			Notices.send(player, Component.translatable("halloween.perk.points", total, e.getValue()).withStyle(ChatFormatting.GOLD));
			sync(player);
		}
	}

	/** Puts points into a purse (the lobby farm, tests). */
	public static void give(MinecraftServer server, UUID id, int points) {
		Perks data = get(server);
		Profile p = data.profile(id);
		data.put(id, p.withPerks(p.points() + points, p.owned(), p.equipped()));
	}

	/** Is this hero theirs to play (open to everyone, or bought)? */
	public static boolean ownsHero(MinecraftServer server, UUID id, String hero) {
		return HeroInfo.free(hero) || get(server).profile(id).heroes().contains(hero);
	}

	/** Buys a hero for {@link HwConfig#heroPrice}; false if they have it already or the purse is short. */
	public static boolean buyHero(ServerPlayer player, String hero) {
		MinecraftServer server = player.level().getServer();
		Perks data = get(server);
		Profile p = data.profile(player.getUUID());
		int price = HwConfig.get().heroPrice;
		if (!HeroInfo.known(hero) || ownsHero(server, player.getUUID(), hero) || p.points() < price) {
			sync(player);
			return false;
		}
		List<String> heroes = new ArrayList<>(p.heroes());
		heroes.add(hero);
		data.put(player.getUUID(), new Profile(p.points() - price, p.owned(), p.equipped(), heroes, p.records()));
		Notices.send(player, Component.translatable("halloween.select.bought", Component.translatable("halloween.hero.name." + hero))
			.withStyle(ChatFormatting.GREEN));
		sync(player);
		return true;
	}

	/** A match played as this hero: one more played, maybe an escape, maybe a new best. */
	public static void record(MinecraftServer server, UUID id, String hero, boolean escaped, int score) {
		Perks data = get(server);
		Profile p = data.profile(id);
		Map<String, List<Integer>> records = new HashMap<>(p.records());
		List<Integer> r = records.getOrDefault(hero, List.of(0, 0, 0));
		records.put(hero, List.of(r.get(0) + 1, r.get(1) + (escaped ? 1 : 0), Math.max(r.get(2), score)));
		data.put(id, new Profile(p.points(), p.owned(), p.equipped(), p.heroes(), records));
	}

	public static void sync(ServerPlayer player) {
		if (Bots.isBot(player)) {
			return;
		}
		Profile profile = get(player.level().getServer()).profile(player.getUUID());
		ServerPlayNetworking.send(player, new HwNet.PerkSync(profile.points(), List.copyOf(profile.owned()), List.copyOf(profile.equipped()),
			HwConfig.get().perkSlots, List.copyOf(profile.heroes()), HwConfig.get().heroPrice, Map.copyOf(profile.records())));
	}
}
