package dev.halloween.game;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A two-block wardrobe to hide in. Hollow: thin walls and a slatted door facing {@link #FACING}, so
 * whoever is inside stands in the empty middle and peeks out through the slits.
 */
public class LockerBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<LockerBlock> CODEC = simpleCodec(LockerBlock::new);
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
	private static final Map<Direction, VoxelShape> LOWER = new EnumMap<>(Direction.class);
	private static final Map<Direction, VoxelShape> UPPER = new EnumMap<>(Direction.class);

	static {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			VoxelShape walls = Shapes.or(
				box(facing, 0, 0, 0, 16, 16, 1),   // door (front)
				box(facing, 0, 0, 15, 16, 16, 16), // back
				box(facing, 0, 0, 0, 1, 16, 16),   // side
				box(facing, 15, 0, 0, 16, 16, 16)); // side
			LOWER.put(facing, walls);
			UPPER.put(facing, Shapes.or(walls, box(facing, 0, 15, 0, 16, 16, 16)));
		}
	}

	public LockerBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
	}

	/** A box given for a locker facing north (front at z=0), turned to face {@code facing}. */
	private static VoxelShape box(Direction facing, double x0, double y0, double z0, double x1, double y1, double z1) {
		return switch (facing) {
			case SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
			case EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
			case WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
			default -> Block.box(x0, y0, z0, x1, y1, z1);
		};
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, HALF);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return (state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER).get(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.block();
	}

	/** Lower half of the locker at {@code pos}. */
	public static BlockPos base(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof LockerBlock && state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	public static boolean is(BlockState state) {
		return state.getBlock() instanceof LockerBlock;
	}

	/** Places a whole locker with its door facing {@code facing}. */
	public static void place(Level level, BlockPos base, Direction facing, Block block) {
		BlockState lower = block.defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER);
		level.setBlock(base, lower, Block.UPDATE_ALL);
		level.setBlock(base.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
	}
}
