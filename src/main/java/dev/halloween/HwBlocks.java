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

	/** Furniture to search (see game.SearchBlock); boxes are in pixels for the piece facing north. */
	public static final Block DRESSER = search("dresser", Blocks.DARK_OAK_PLANKS, dev.halloween.game.SearchBlock.Stuff.WOOD, 0, 0, 2, 16, 16, 16);
	public static final Block NIGHTSTAND = search("nightstand", Blocks.OAK_PLANKS, dev.halloween.game.SearchBlock.Stuff.WOOD, 2, 0, 3, 14, 12, 15);
	public static final Block KITCHEN_CABINET = search("kitchen_cabinet", Blocks.SPRUCE_PLANKS, dev.halloween.game.SearchBlock.Stuff.WOOD, 0, 0, 0, 16, 16, 16);
	public static final Block CARDBOARD_BOX = search("cardboard_box", Blocks.OAK_PLANKS, dev.halloween.game.SearchBlock.Stuff.CARDBOARD, 2, 0, 2, 14, 10, 14);
	public static final Block CRATE = search("crate", Blocks.OAK_PLANKS, dev.halloween.game.SearchBlock.Stuff.WOOD, 1, 0, 1, 15, 14, 15);
	public static final Block TOOL_CABINET = search("tool_cabinet", Blocks.IRON_BLOCK, dev.halloween.game.SearchBlock.Stuff.METAL, 1, 0, 2, 15, 16, 15);
	public static final Block FILING_CABINET = search("filing_cabinet", Blocks.IRON_BLOCK, dev.halloween.game.SearchBlock.Stuff.METAL, 2, 0, 1, 14, 16, 15);

	/** The gun safe with the rifle (see game.GunCase). */
	public static final Block GUN_SAFE = Registry.register(BuiltInRegistries.BLOCK, key("gun_safe"),
		new dev.halloween.game.SafeBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(-1f, 3600000f).noOcclusion().setId(key("gun_safe"))));

	private HwBlocks() {
	}

	private static ResourceKey<Block> key(String path) {
		return ResourceKey.create(Registries.BLOCK, Halloween.id(path));
	}

	private static Block register(String path) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Halloween.id(path));
		return Registry.register(BuiltInRegistries.BLOCK, key, new IronBarsBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS_PANE).setId(key)));
	}

	private static Block search(String path, Block like, dev.halloween.game.SearchBlock.Stuff stuff, double... box) {
		ResourceKey<Block> key = key(path);
		return Registry.register(BuiltInRegistries.BLOCK, key, new dev.halloween.game.SearchBlock(stuff, box,
			BlockBehaviour.Properties.ofFullCopy(like).strength(-1f, 3600000f).noOcclusion().setId(key)));
	}

	public static void init() {
	}
}
