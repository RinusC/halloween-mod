package dev.halloween.game;

import com.mojang.serialization.Codec;
import dev.halloween.Halloween;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Things to wear, one for each level (see PlayerLevel), on two tracks: the survivors' (a head, a back and a pet on
 * the shoulder) and Michael's. A level reached opens its two things for good (the level is kept, so are they); one
 * thing per slot is worn, chosen in the character select, and saved with the world. Everyone is told who wears what
 * (drawn by client.CosmeticRenderer, in the lobby and in the match).
 */
public final class Cosmetics extends SavedData {
	public static final int SURVIVOR = 0;
	public static final int KILLER = 1;

	/** Where it goes; the survivors' three, then Michael's. */
	public enum Slot {
		HEAD, BACK, PET, HAND, BELT, CLOAK, AURA;

		public String key() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public record Item(String id, int track, Slot slot, int level) {
		public boolean legendary() {
			return this.level == PlayerLevel.MAX;
		}
	}

	public static final List<Item> ITEMS = List.of(
		new Item("bandages", SURVIVOR, Slot.HEAD, 1),
		new Item("radio", SURVIVOR, Slot.BACK, 2),
		new Item("spider", SURVIVOR, Slot.PET, 3),
		new Item("witch_hat", SURVIVOR, Slot.HEAD, 4),
		new Item("broom", SURVIVOR, Slot.BACK, 5),
		new Item("cat", SURVIVOR, Slot.PET, 6),
		new Item("antlers", SURVIVOR, Slot.HEAD, 7),
		new Item("bat_wings", SURVIVOR, Slot.BACK, 8),
		new Item("ghost", SURVIVOR, Slot.PET, 9),
		new Item("pumpkin_head", SURVIVOR, Slot.HEAD, 10),
		new Item("rusty_knife", KILLER, Slot.HAND, 1),
		new Item("garland", KILLER, Slot.BELT, 2),
		new Item("crow", KILLER, Slot.PET, 3),
		new Item("candy_sack", KILLER, Slot.BACK, 4),
		new Item("cracked_mask", KILLER, Slot.HEAD, 5),
		new Item("chain", KILLER, Slot.BACK, 6),
		new Item("cleaver", KILLER, Slot.HAND, 7),
		new Item("pumpkin_mask", KILLER, Slot.HEAD, 8),
		new Item("cloak", KILLER, Slot.CLOAK, 9),
		new Item("shadow", KILLER, Slot.AURA, 10));

	private static final Codec<Cosmetics> CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).xmap(Cosmetics::fromMap,
		c -> Map.copyOf(c.worn));
	public static final SavedDataType<Cosmetics> TYPE = new SavedDataType<>(Halloween.id("cosmetics"), Cosmetics::new, CODEC,
		DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** What each one wears (uuid: item ids). */
	private final Map<String, List<String>> worn = new HashMap<>();
	private static HwNet.CosmeticSync sent;

	public Cosmetics() {
	}

	private static Cosmetics fromMap(Map<String, List<String>> map) {
		Cosmetics c = new Cosmetics();
		c.worn.putAll(map);
		return c;
	}

	private static Cosmetics get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public static Item byId(String id) {
		for (Item i : ITEMS) {
			if (i.id.equals(id)) {
				return i;
			}
		}
		return null;
	}

	/** The thing a level opens on a track. */
	public static Item at(int track, int level) {
		for (Item i : ITEMS) {
			if (i.track == track && i.level == level) {
				return i;
			}
		}
		return null;
	}

	public static boolean unlocked(MinecraftServer server, UUID id, Item item) {
		return PlayerLevel.of(Perks.earned(server, id)) >= item.level;
	}

	public static List<String> worn(MinecraftServer server, UUID id) {
		return get(server).worn.getOrDefault(id.toString(), List.of());
	}

	/** From the character select: put it on (taking off what was in its slot) or take it off. */
	public static void act(ServerPlayer player, String itemId, boolean on) {
		MinecraftServer server = player.level().getServer();
		Item item = byId(itemId);
		if (item == null || Bots.isBot(player)) {
			return;
		}
		Cosmetics data = get(server);
		List<String> list = new ArrayList<>(data.worn.getOrDefault(player.getUUID().toString(), List.of()));
		if (on) {
			if (!unlocked(server, player.getUUID(), item)) {
				Notices.send(player, Component.translatable("halloween.cosmetic.locked_at", item.level).withStyle(ChatFormatting.GRAY));
				return;
			}
			list.removeIf(id -> {
				Item other = byId(id);
				return other == null || other.track == item.track && other.slot == item.slot;
			});
			list.add(item.id);
		} else {
			list.remove(item.id);
		}
		data.worn.put(player.getUUID().toString(), List.copyOf(list));
		data.setDirty();
		broadcast(server);
	}

	/** A new level reached: what it opens, said to them. */
	static void announce(ServerPlayer player, int level) {
		Item s = at(SURVIVOR, level);
		Item k = at(KILLER, level);
		if (s == null || k == null) {
			return;
		}
		Notices.send(player, Component.translatable("halloween.cosmetic.unlocked", Component.translatable("halloween.cosmetic." + s.id),
			Component.translatable("halloween.cosmetic." + k.id)).withStyle(ChatFormatting.LIGHT_PURPLE));
		player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
	}

	/** Everyone: who wears what. */
	public static void broadcast(MinecraftServer server) {
		Cosmetics data = get(server);
		List<UUID> ids = new ArrayList<>();
		List<String> items = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			List<String> list = data.worn.get(p.getUUID().toString());
			if (list != null && !list.isEmpty()) {
				ids.add(p.getUUID());
				items.add(String.join(",", list));
			}
		}
		sent = new HwNet.CosmeticSync(ids, items);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) {
				ServerPlayNetworking.send(p, sent);
			}
		}
	}

	/** For tests: nobody wears anything. */
	public static void clear(MinecraftServer server) {
		get(server).worn.clear();
		get(server).setDirty();
		broadcast(server);
	}
}
