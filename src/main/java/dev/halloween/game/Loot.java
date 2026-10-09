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
 * Furniture to search ({@link SearchBlock}: dressers, nightstands, kitchen cabinets, boxes, crates, tool chests,
 * filing cabinets), and chests and barrels on other maps, around the map centre are containers. At the start the items the exits need are
 * hidden in random ones, every match (fuse, gate key, gas can, sedan keys, padlock key, bolt cutters, two
 * repair kits); then ammunition, the relics that turned up this match, and for a share of the rest a find
 * drawn by how common it is in the original (see {@link HwConfig#lootWeights}). Survivors search them by
 * holding use.
 */
public final class Loot {
	private static final List<BlockPos> CONTAINERS = new ArrayList<>();
	private static final Set<BlockPos> SEARCHED = new HashSet<>();
	private static final Map<BlockPos, HwItems.Kind> HIDDEN = new HashMap<>();
	/** How far around the centre this match's containers (and breakers) are looked for. */
	private static int radius = 96;

	public static int radius() {
		return radius;
	}

	private Loot() {
	}

	public static void setup(ServerLevel level, BlockPos center, RandomSource random) {
		clear();
		// Containers count within the usual radius of the centre, and anywhere in the square the map points
		// span (a big town reaches into its corners).
		int circle = HwConfig.get().lootRadius;
		int square = 0;
		for (List<BlockPos> list : MapPoints.get(level).all().values()) {
			for (BlockPos p : list) {
				square = Math.max(square, Math.max(Math.abs(p.getX() - center.getX()), Math.abs(p.getZ() - center.getZ())) + 8);
			}
		}
		int r = Math.max(circle, square);
		radius = r;
		int sq = square;
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		int cr = (r >> 4) + 1;
		for (int x = cx - cr; x <= cx + cr; x++) {
			for (int z = cz - cr; z <= cz + cr; z++) {
				LevelChunk chunk = level.getChunk(x, z);
				// Furniture: closed again for the new match.
				for (int i = 0; i < chunk.getSections().length; i++) {
					net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSections()[i];
					if (section.hasOnlyAir() || !section.maybeHas(SearchBlock::is)) {
						continue;
					}
					int y0 = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					for (int ly = 0; ly < 16; ly++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								net.minecraft.world.level.block.state.BlockState state = section.getBlockState(lx, ly, lz);
								if (!SearchBlock.is(state)) {
									continue;
								}
								BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + lx, y0 + ly, chunk.getPos().getMinBlockZ() + lz);
								if (pos.closerThan(center, circle) || Math.abs(pos.getX() - center.getX()) <= sq && Math.abs(pos.getZ() - center.getZ()) <= sq) {
									CONTAINERS.add(pos);
									if (state.getValue(SearchBlock.OPEN)) {
										level.setBlock(pos, state.setValue(SearchBlock.OPEN, false), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
									}
								}
							}
						}
					}
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && (be.getBlockPos().closerThan(center, circle)
						|| Math.abs(be.getBlockPos().getX() - center.getX()) <= sq && Math.abs(be.getBlockPos().getZ() - center.getZ()) <= sq)
						&& !GunCase.isCase(be.getBlockPos())) {
						CONTAINERS.add(be.getBlockPos().immutable());
					}
				}
			}
		}
		// What the exits need comes first: if a map has few containers, the extras are what gets left out.
		List<HwItems.Kind> items = new ArrayList<>(List.of(
			HwItems.Kind.BOLT_CUTTERS, HwItems.Kind.PADLOCK_KEY, HwItems.Kind.FUSE, HwItems.Kind.GATE_KEY,
			HwItems.Kind.SEDAN_KEYS, HwItems.Kind.GAS_CAN, HwItems.Kind.REPAIR_KIT, HwItems.Kind.REPAIR_KIT));
		HwConfig cfg = HwConfig.get();
		java.util.Random rng = new java.util.Random(random.nextLong());
		// And one more of them, any, to make getting out a little easier.
		List<HwItems.Kind> escape = List.of(HwItems.Kind.BOLT_CUTTERS, HwItems.Kind.PADLOCK_KEY, HwItems.Kind.FUSE, HwItems.Kind.GATE_KEY,
			HwItems.Kind.SEDAN_KEYS, HwItems.Kind.GAS_CAN, HwItems.Kind.REPAIR_KIT);
		for (int i = 0; i < cfg.extraEscapeItems; i++) {
			items.add(escape.get(rng.nextInt(escape.size())));
		}
		if (GunCase.present()) {
			items.add(HwItems.Kind.SAFE_CODE);
			for (int i = 0; i < cfg.rifleAmmoFinds; i++) {
				items.add(HwItems.Kind.RIFLE_AMMO);
			}
		}
		for (int i = 0; i < cfg.revolverAmmoFinds; i++) {
			items.add(HwItems.Kind.REVOLVER_AMMO);
		}
		// Relics don't turn up every match.
		for (HwItems.Kind relic : new HwItems.Kind[]{HwItems.Kind.AMULET, HwItems.Kind.HOURGLASS, HwItems.Kind.BRASS_LANTERN}) {
			if (rng.nextFloat() < cfg.relicChance) {
				items.add(relic);
			}
		}
		// The rest: a share of the containers left hide something, drawn by how common it is. Weapons for
		// hand-to-hand aren't kept in drawers: they lie about outside (StreetWeapons); firearms still are.
		Map<String, Integer> indoors = new java.util.LinkedHashMap<>();
		cfg.lootWeights.forEach((key, w) -> {
			HwItems.Kind kind = kindByKey(key);
			if (kind != null && !kind.melee()) {
				indoors.put(key, w);
			}
		});
		int extra = Math.round(Math.max(0, CONTAINERS.size() - items.size()) * cfg.lootFillShare);
		for (int i = 0; i < extra; i++) {
			HwItems.Kind find = draw(indoors, rng);
			if (find != null) {
				items.add(find);
			}
		}
		List<BlockPos> shuffled = new ArrayList<>(CONTAINERS);
		Collections.shuffle(shuffled, rng);
		for (int i = 0; i < Math.min(items.size(), shuffled.size()); i++) {
			HIDDEN.put(shuffled.get(i), items.get(i));
		}
	}

	/** One find, drawn by weight from {@code weights} (keys are item names). */
	static HwItems.Kind draw(Map<String, Integer> weights, java.util.Random rng) {
		int total = 0;
		for (Map.Entry<String, Integer> e : weights.entrySet()) {
			if (kindByKey(e.getKey()) != null) {
				total += Math.max(0, e.getValue());
			}
		}
		if (total <= 0) {
			return null;
		}
		int roll = rng.nextInt(total);
		for (Map.Entry<String, Integer> e : weights.entrySet()) {
			HwItems.Kind kind = kindByKey(e.getKey());
			if (kind == null) {
				continue;
			}
			roll -= Math.max(0, e.getValue());
			if (roll < 0) {
				return kind;
			}
		}
		return null;
	}

	private static HwItems.Kind kindByKey(String key) {
		for (HwItems.Kind kind : HwItems.Kind.values()) {
			if (kind.key().equals(key)) {
				return kind;
			}
		}
		return null;
	}

	/** What this match hides where (tests, debugging). */
	public static Map<BlockPos, HwItems.Kind> hidden() {
		return new HashMap<>(HIDDEN);
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
		int ticks = Perks.has(player, Perks.Perk.QUICK_SEARCH) ? Math.round(HwConfig.get().searchTicks * HwConfig.get().perkQuickSearchFactor)
			: HwConfig.get().searchTicks;
		return new Holds.Hold(at, "halloween.hud.progress.search", false, ticks, p -> !SEARCHED.contains(at), p -> {
			SEARCHED.add(at);
			HwItems.Kind kind = HIDDEN.remove(at);
			// Now and then somebody kept an old revolver in there.
			if (p.level().getRandom().nextFloat() < HwConfig.get().revolverFindChance) {
				Component gun = Component.translatable("halloween.item.revolver");
				Inventory.give(p, HwItems.create(HwItems.Kind.REVOLVER));
				Notices.send(p, Component.translatable("halloween.hint.found", gun).withStyle(ChatFormatting.GOLD));
				AiStats.add(p, "found.revolver");
			}
			// The drawer stays pulled out, the door open, the flaps up.
			net.minecraft.world.level.block.state.BlockState state = p.level().getBlockState(at);
			if (state.getBlock() instanceof SearchBlock furniture) {
				p.level().playSound(null, at, furniture.stuff().sound, SoundSource.BLOCKS, 0.8f, furniture.stuff().pitch);
				p.level().setBlock(at, state.setValue(SearchBlock.OPEN, true), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
			} else {
				p.level().playSound(null, at, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.8f, 1.1f);
			}
			if (kind == null) {
				p.sendOverlayMessage(Component.translatable("halloween.hint.empty").withStyle(ChatFormatting.GRAY));
				return;
			}
			Component name = Component.translatable("halloween.item." + kind.key());
			Inventory.give(p, found(kind));
			p.sendOverlayMessage(Component.translatable("halloween.hint.found", name));
			Match.score(p, HwConfig.get().scoreItemFound, Component.translatable("halloween.score.item_found", name));
		});
	}

	/** What a find turns into: rounds come by the handful, the note has the gun case's code on it. */
	public static net.minecraft.world.item.ItemStack found(HwItems.Kind kind) {
		HwConfig cfg = HwConfig.get();
		net.minecraft.world.item.ItemStack stack = HwItems.create(kind);
		if (kind == HwItems.Kind.REVOLVER_AMMO) {
			stack.setCount(cfg.revolverAmmoPerFind);
		} else if (kind == HwItems.Kind.RIFLE_AMMO) {
			stack.setCount(cfg.rifleAmmoPerFind);
		} else if (kind == HwItems.Kind.SHOTGUN_SHELLS) {
			stack.setCount(cfg.shotgunShellsPerFind);
		} else if (kind == HwItems.Kind.SAFE_CODE) {
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("halloween.item.safe_code_with", GunCase.code()));
		}
		return stack;
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
