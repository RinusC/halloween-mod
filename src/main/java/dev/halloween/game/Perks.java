package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Survivor perks. Everything a player scores as a survivor adds up from match to match (kept with the world);
 * at {@link HwConfig#perkUnlockPoints} points each perk opens. Between matches each player picks one (the perk
 * screen, key P); it holds for the whole next match.
 */
public final class Perks extends SavedData {
	public enum Perk {
		/** Searching furniture takes less time. */
		QUICK_SEARCH,
		/** More breath for running. */
		STAMINA,
		/** Footsteps carry half as far. */
		QUIET,
		/** A little more health. */
		TOUGH;

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public int cost() {
			return HwConfig.at(HwConfig.get().perkUnlockPoints, this.ordinal());
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

	private record Profile(int points, String perk) {
		static final Codec<Profile> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("points").forGetter(Profile::points),
			Codec.STRING.optionalFieldOf("perk", "").forGetter(Profile::perk)).apply(i, Profile::new));
	}

	private static final Codec<Perks> CODEC = Codec.unboundedMap(Codec.STRING, Profile.CODEC).xmap(Perks::fromMap, p -> Map.copyOf(p.profiles));
	public static final SavedDataType<Perks> TYPE = new SavedDataType<>(Halloween.id("perks"), Perks::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	/** Who plays the current match with which perk. */
	private static final Map<UUID, Perk> ACTIVE = new HashMap<>();

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
		return this.profiles.getOrDefault(id.toString(), new Profile(0, ""));
	}

	public static int points(MinecraftServer server, UUID id) {
		return get(server).profile(id).points();
	}

	public static Perk chosen(MinecraftServer server, UUID id) {
		Profile profile = get(server).profile(id);
		Perk perk = Perk.byKey(profile.perk());
		return perk != null && profile.points() >= perk.cost() ? perk : null;
	}

	/** Does this survivor play the current match with this perk? */
	public static boolean has(ServerPlayer player, Perk perk) {
		return ACTIVE.get(player.getUUID()) == perk;
	}

	/** Extra health the perk gives (0 without it). */
	public static float extraHealth(ServerPlayer player) {
		return has(player, Perk.TOUGH) ? HwConfig.get().perkToughHealth : 0f;
	}

	/** At the start of a match: everybody's chosen perk holds until it ends (bots have none). */
	public static void begin(MinecraftServer server, List<ServerPlayer> players) {
		ACTIVE.clear();
		for (ServerPlayer p : players) {
			Perk perk = Bots.isBot(p) ? null : chosen(server, p.getUUID());
			if (perk != null) {
				ACTIVE.put(p.getUUID(), perk);
				AiStats.add(p, "perk." + perk.key());
			}
		}
	}

	public static void end() {
		ACTIVE.clear();
	}

	/** Picks a perk between matches; null takes it off. */
	public static void choose(ServerPlayer player, String key) {
		MinecraftServer server = player.level().getServer();
		if (Match.running() || Match.frozen()) {
			Notices.send(player, Component.translatable("halloween.perk.not_now").withStyle(ChatFormatting.GRAY));
			sync(player);
			return;
		}
		Perk perk = Perk.byKey(key);
		Perks data = get(server);
		Profile profile = data.profile(player.getUUID());
		if (perk != null && profile.points() < perk.cost()) {
			sync(player);
			return;
		}
		data.profiles.put(player.getUUID().toString(), new Profile(profile.points(), perk == null ? "" : perk.key()));
		data.setDirty();
		sync(player);
	}

	/** The match is over: each person's survivor score adds to their total; newly opened perks are announced. */
	public static void award(MinecraftServer server, Map<UUID, Integer> scores) {
		Perks data = get(server);
		for (Map.Entry<UUID, Integer> e : scores.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null || Bots.isBot(player) || e.getValue() <= 0) {
				continue;
			}
			Profile before = data.profile(e.getKey());
			int total = before.points() + e.getValue();
			data.profiles.put(e.getKey().toString(), new Profile(total, before.perk()));
			Notices.send(player, Component.translatable("halloween.perk.points", total, e.getValue()).withStyle(ChatFormatting.GOLD));
			List<Component> opened = new ArrayList<>();
			for (Perk perk : Perk.values()) {
				if (before.points() < perk.cost() && total >= perk.cost()) {
					opened.add(Component.translatable("halloween.perk." + perk.key()));
				}
			}
			for (Component name : opened) {
				Notices.send(player, Component.translatable("halloween.perk.opened", name).withStyle(ChatFormatting.GREEN));
			}
			sync(player);
		}
		data.setDirty();
	}

	public static void sync(ServerPlayer player) {
		if (Bots.isBot(player)) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		Perks data = get(server);
		Profile profile = data.profile(player.getUUID());
		ServerPlayNetworking.send(player, new HwNet.PerkSync(profile.points(), profile.perk()));
	}
}
