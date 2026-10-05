package dev.halloween.game;

import dev.halloween.HwConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chests and barrels around the map centre are containers. At the start the items the exits need,
 * spare repair kits and weapons are hidden in random ones; survivors search them by holding use.
 */
public final class Loot {
	private static final List<BlockPos> CONTAINERS = new ArrayList<>();
	private static final Set<BlockPos> SEARCHED = new HashSet<>();
	private static final Map<BlockPos, HwItems.Kind> HIDDEN = new HashMap<>();

	private Loot() {
	}

	public static void setup(ServerLevel level, BlockPos center, RandomSource random) {
		clear();
		int r = HwConfig.get().lootRadius;
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		int cr = (r >> 4) + 1;
		for (int x = cx - cr; x <= cx + cr; x++) {
			for (int z = cz - cr; z <= cz + cr; z++) {
				LevelChunk chunk = level.getChunk(x, z);
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && be.getBlockPos().closerThan(center, r)) {
						CONTAINERS.add(be.getBlockPos().immutable());
					}
				}
			}
		}
		List<HwItems.Kind> items = new ArrayList<>(List.of(
			HwItems.Kind.BOLT_CUTTERS, HwItems.Kind.PADLOCK_KEY,
			HwItems.Kind.CAR_KEYS, HwItems.Kind.CAR_PART, HwItems.Kind.REPAIR_KIT, HwItems.Kind.REPAIR_KIT,
			HwItems.Kind.BAT, HwItems.Kind.BAT, HwItems.Kind.KNIFE,
			HwItems.Kind.DART, HwItems.Kind.DART, HwItems.Kind.DART,
			HwItems.Kind.BATTERY, HwItems.Kind.BATTERY));
		List<BlockPos> shuffled = new ArrayList<>(CONTAINERS);
		Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
		for (int i = 0; i < Math.min(items.size(), shuffled.size()); i++) {
			HIDDEN.put(shuffled.get(i), items.get(i));
		}
	}

	public static void clear() {
		CONTAINERS.clear();
		SEARCHED.clear();
		HIDDEN.clear();
	}

	public static boolean isContainer(BlockPos pos) {
		return CONTAINERS.contains(pos);
	}

	public static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		if (!CONTAINERS.contains(pos) || !MatchManager.role(player).survivor()) {
			return null;
		}
		if (SEARCHED.contains(pos)) {
			player.sendOverlayMessage(Component.translatable("halloween.hint.searched").withStyle(ChatFormatting.GRAY));
			return null;
		}
		BlockPos at = pos.immutable();
		return new Holds.Hold(at, "halloween.hud.progress.search", false, HwConfig.get().searchTicks, p -> !SEARCHED.contains(at), p -> {
			SEARCHED.add(at);
			HwItems.Kind kind = HIDDEN.remove(at);
			p.level().playSound(null, at, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.8f, 1.1f);
			if (kind == null) {
				p.sendOverlayMessage(Component.translatable("halloween.hint.empty").withStyle(ChatFormatting.GRAY));
				return;
			}
			Component name = Component.translatable("halloween.item." + kind.key());
			Inventory.give(p, HwItems.create(kind));
			p.sendOverlayMessage(Component.translatable("halloween.hint.found", name));
			Match.score(p, HwConfig.get().scoreItemFound, Component.translatable("halloween.score.item_found", name));
		});
	}

	/** Puts a specific item into a specific container (tests, debugging). */
	public static void hide(BlockPos pos, HwItems.Kind kind) {
		HIDDEN.put(pos, kind);
		SEARCHED.remove(pos);
	}

	public static List<BlockPos> containers() {
		return CONTAINERS;
	}

	public static List<BlockPos> searched() {
		return new ArrayList<>(SEARCHED);
	}
}
