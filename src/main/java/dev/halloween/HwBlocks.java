package dev.halloween;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class HwBlocks {
	/** A window pane after someone went through it: shards left in the frame, still blocks walking. */
	public static final Block BROKEN_GLASS_PANE = register("broken_glass_pane");
	/** Desk phone used to call the police. */
	public static final Block PHONE = Registry.register(BuiltInRegistries.BLOCK, key("phone"),
		new dev.halloween.game.PhoneBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).strength(-1f, 3600000f).noOcclusion().setId(key("phone"))));

	/** A wardrobe to hide in (two blocks tall). */
	public static final Block LOCKER = Registry.register(BuiltInRegistries.BLOCK, key("locker"),
		new dev.halloween.game.LockerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_PLANKS).strength(-1f, 3600000f).noOcclusion()
			.isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false).setId(key("locker"))));

	private HwBlocks() {
	}

	private static ResourceKey<Block> key(String path) {
		return ResourceKey.create(Registries.BLOCK, Halloween.id(path));
	}

	private static Block register(String path) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Halloween.id(path));
		return Registry.register(BuiltInRegistries.BLOCK, key, new IronBarsBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS_PANE).setId(key)));
	}

	public static void init() {
	}
}
