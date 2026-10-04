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

	private HwBlocks() {
	}

	private static Block register(String path) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Halloween.id(path));
		return Registry.register(BuiltInRegistries.BLOCK, key, new IronBarsBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS_PANE).setId(key)));
	}

	public static void init() {
	}
}
