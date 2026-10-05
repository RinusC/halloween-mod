package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The gun case: a chest with a combination lock somewhere on the map (one of the gun_case points), with
 * the rifle inside. A note with the code lies in one of the ordinary containers; the code is typed on the
 * lock's keypad (whoever remembers it needs no note). Bolt cutters cut the lock instead, and are spent.
 */
public final class GunCase {
	private static BlockPos pos;
	private static BlockState replaced;
	private static boolean open;
	private static String code;

	private GunCase() {
	}

	public static void setup(ServerLevel level, RandomSource random) {
		clear(level);
		List<BlockPos> spots = MapPoints.get(level).list(MapPoints.Type.GUN_CASE);
		if (spots.isEmpty()) {
			return;
		}
		pos = spots.get(random.nextInt(spots.size())).immutable();
		code = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
		Block chest = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("waxed_copper_chest"));
		replaced = level.getBlockState(pos);
		level.setBlock(pos, (chest == Blocks.AIR ? Blocks.CHEST : chest).defaultBlockState(), Block.UPDATE_ALL);
	}

	public static void clear(ServerLevel level) {
		if (pos != null && replaced != null) {
			level.removeBlockEntity(pos);
			level.setBlock(pos, replaced, Block.UPDATE_ALL);
		}
		pos = null;
		replaced = null;
		open = false;
		code = null;
	}

	public static boolean present() {
		return pos != null;
	}

	public static boolean isCase(BlockPos at) {
		return pos != null && pos.equals(at);
	}

	/** The case while it is still shut (for the client's prompts), or none. */
	public static List<BlockPos> closed() {
		return pos == null || open ? List.of() : List.of(pos);
	}

	public static String code() {
		return code == null ? "----" : code;
	}

	/**
	 * Holding use on the case: with the note in a pocket (or without anything) the keypad comes up; with bolt
	 * cutters and no note, the lock is cut.
	 */
	public static Holds.Hold resolve(ServerPlayer player, BlockPos clicked) {
		if (!isCase(clicked) || !MatchManager.role(player).survivor()) {
			return null;
		}
		if (open) {
			player.sendOverlayMessage(Component.translatable("halloween.hint.case_empty").withStyle(ChatFormatting.GRAY));
			return null;
		}
		if (Inventory.has(player, HwItems.Kind.BOLT_CUTTERS) && !Inventory.has(player, HwItems.Kind.SAFE_CODE)) {
			BlockPos at = pos;
			return new Holds.Hold(at, "halloween.hud.progress.cut_case", false, HwConfig.get().caseCutTicks,
				p -> !open && Inventory.has(p, HwItems.Kind.BOLT_CUTTERS), p -> {
					Inventory.consume(p, HwItems.Kind.BOLT_CUTTERS);
					p.level().playSound(null, at, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.5f, 0.9f);
					openCase(p);
				});
		}
		Minigames.open(player, dev.halloween.net.HwNet.Minigame.KEYPAD, pos);
		return null;
	}

	/** A code typed on the keypad. True if it opened the case. */
	public static boolean tryCode(ServerPlayer player, int entered) {
		if (pos == null || open) {
			return false;
		}
		if (String.format(Locale.ROOT, "%04d", entered).equals(code)) {
			openCase(player);
			return true;
		}
		player.level().playSound(null, pos, HwSounds.CASE_WRONG, SoundSource.BLOCKS, 0.8f, 1f);
		AiStats.add(player, "case.wrong_code");
		return false;
	}

	private static void openCase(ServerPlayer player) {
		open = true;
		ServerLevel level = player.level();
		level.playSound(null, pos, HwSounds.CASE_OPEN, SoundSource.BLOCKS, 1f, 1f);
		level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.8f, 0.8f);
		Inventory.give(player, HwItems.create(HwItems.Kind.RIFLE));
		Component name = Component.translatable("halloween.item.rifle");
		player.sendOverlayMessage(Component.translatable("halloween.hint.found", name));
		Match.score(player, HwConfig.get().scoreItemFound, Component.translatable("halloween.score.item_found", name));
		AiStats.add(player, "case.opened");
		Match.markDirty();
	}
}
