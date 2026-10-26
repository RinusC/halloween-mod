package dev.halloween.game;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * A map drawn in memory before it is put into the world: blocks in local coordinates (the origin is the
 * map centre at ground level, y 0 = feet on the ground), map points, and sign texts. Anything not drawn is
 * plain ground: grass at y -1 over dirt, air above. {@link MapJob} lays it into the world a slice at a time.
 */
public class MapPlan {
	final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
	final List<BlockPos> shapeFix = new ArrayList<>();
	final List<Object[]> points = new ArrayList<>();
	final Map<BlockPos, String[]> signs = new LinkedHashMap<>();
	/** Saved block entities (containers, signs) of a map read from a file; a drawn plan has none. */
	final Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntities = new LinkedHashMap<>();
	/** The square laid out: |x|, |z| up to this. */
	final int radius;
	final int yMin;
	final int yMax;

	MapPlan(int radius, int yMin, int yMax) {
		this.radius = radius;
		this.yMin = yMin;
		this.yMax = yMax;
	}

	BlockState ground(int x, int y, int z) {
		if (y >= 0) {
			return AIR;
		}
		return y == -1 ? GRASS : DIRT;
	}

	BlockState at(int x, int y, int z) {
		BlockState s = this.blocks.get(BlockPos.asLong(x, y, z));
		return s != null ? s : this.ground(x, y, z);
	}

	// ---------------------------------------------------------------- drawing

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

	static BlockState b(String id) {
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id));
		if (block == Blocks.AIR && !id.equals("air")) {
			throw new IllegalArgumentException("No such block: " + id);
		}
		return block.defaultBlockState();
	}

	static BlockState stairs(String id, Direction facing) {
		return b(id).setValue(StairBlock.FACING, facing);
	}

	static BlockState stairs(String id, Direction facing, boolean upsideDown) {
		return stairs(id, facing).setValue(StairBlock.HALF, upsideDown ? net.minecraft.world.level.block.state.properties.Half.TOP
			: net.minecraft.world.level.block.state.properties.Half.BOTTOM);
	}

	static BlockState slab(String id, SlabType type) {
		return b(id).setValue(BlockStateProperties.SLAB_TYPE, type);
	}

	static BlockState facing(String id, Direction facing) {
		return b(id).setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
	}

	/** A piece of furniture to search, its front facing {@code facing}. */
	static BlockState search(Block block, Direction facing) {
		return block.defaultBlockState().setValue(SearchBlock.FACING, facing);
	}

	static BlockState leaves(String id) {
		return b(id).setValue(BlockStateProperties.PERSISTENT, true);
	}

	void set(int x, int y, int z, BlockState state) {
		if (Math.abs(x) > this.radius || Math.abs(z) > this.radius || y < this.yMin || y > this.yMax) {
			return;
		}
		this.blocks.put(BlockPos.asLong(x, y, z), state);
		if (state.hasProperty(BlockStateProperties.NORTH) || state.getBlock() instanceof WallBlock || state.getBlock() instanceof StairBlock) {
			this.shapeFix.add(new BlockPos(x, y, z));
		}
	}

	void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					this.set(x, y, z, state);
				}
			}
		}
	}

	/** Only where nothing is drawn yet (decoration that must not overwrite buildings or roads). */
	void setIfGround(int x, int y, int z, BlockState state) {
		if (!this.blocks.containsKey(BlockPos.asLong(x, y, z)) && !this.blocks.containsKey(BlockPos.asLong(x, -1, z))) {
			this.set(x, y, z, state);
		}
	}

	boolean free(int x, int z) {
		for (int y = -1; y <= 3; y++) {
			if (this.blocks.containsKey(BlockPos.asLong(x, y, z))) {
				return false;
			}
		}
		return true;
	}

	boolean freeArea(int x0, int z0, int x1, int z1) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
				if (!this.free(x, z)) {
					return false;
				}
			}
		}
		return true;
	}

	void door(int x, int y, int z, Direction facing, String id) {
		BlockState door = b(id).setValue(DoorBlock.FACING, facing);
		this.set(x, y, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		this.set(x, y + 1, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	/** A breaker box: an iron block in the wall and a lever on its outside face pointing {@code out}. */
	void breaker(int x, int y, int z, Direction out) {
		this.set(x, y, z, b("iron_block"));
		BlockPos lever = new BlockPos(x, y, z).relative(out);
		this.set(lever.getX(), y, lever.getZ(), b("lever").setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
			.setValue(BlockStateProperties.HORIZONTAL_FACING, out).setValue(BlockStateProperties.POWERED, true));
	}

	void bulb(int x, int y, int z) {
		this.set(x, y, z, b("waxed_copper_bulb").setValue(BlockStateProperties.LIT, true));
	}

	void locker(int x, int y, int z, Direction facing) {
		BlockState lower = dev.halloween.HwBlocks.LOCKER.defaultBlockState().setValue(LockerBlock.FACING, facing)
			.setValue(LockerBlock.HALF, DoubleBlockHalf.LOWER);
		this.set(x, y, z, lower);
		this.set(x, y + 1, z, lower.setValue(LockerBlock.HALF, DoubleBlockHalf.UPPER));
	}

	void point(MapPoints.Type type, int x, int y, int z) {
		this.points.add(new Object[]{type, new BlockPos(x, y, z)});
	}

	void sign(int x, int y, int z, int rotation, String... lines) {
		this.set(x, y, z, b("dark_oak_sign").setValue(BlockStateProperties.ROTATION_16, rotation));
		this.signs.put(new BlockPos(x, y, z), lines);
	}

	void wallSign(int x, int y, int z, Direction facing, String... lines) {
		this.set(x, y, z, b("dark_oak_wall_sign").setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
		this.signs.put(new BlockPos(x, y, z), lines);
	}

	int count(MapPoints.Type type) {
		int n = 0;
		for (Object[] p : this.points) {
			n += p[0] == type ? 1 : 0;
		}
		return n;
	}
}
