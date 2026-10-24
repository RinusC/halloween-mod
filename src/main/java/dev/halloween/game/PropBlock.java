package dev.halloween.game;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Something a survivor can pull down behind them in a chase (see Obstacles): a tall bookcase (two blocks, its back
 * to the wall, {@link #FACING} its front) or a street trash can. Drawn by models of its own (tools/prop_models.pl);
 * the town's bookshelves two high and its cauldron and composter bins become these when a match is set up.
 */
public class PropBlock extends HorizontalDirectionalBlock {
	public enum Kind implements StringRepresentable {
		BOOKCASE, BIN;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
	public static final MapCodec<PropBlock> CODEC = simpleCodec(PropBlock::new);
	private static final Map<Direction, VoxelShape> CASE = new EnumMap<>(Direction.class);
	private static final VoxelShape CAN = Block.box(2, 0, 2, 14, 16, 14);

	static {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			CASE.put(d, LockerBlock.box(d, 0, 0, 6, 16, 16, 16));
		}
	}

	public PropBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(KIND, Kind.BOOKCASE).setValue(HALF, DoubleBlockHalf.LOWER));
	}

	public static boolean is(BlockState state) {
		return state.getBlock() instanceof PropBlock;
	}

	/** The block it stands on the floor with (a bookcase's lower half). */
	public static BlockPos base(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return is(state) && state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, KIND, HALF);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(KIND) == Kind.BIN ? CAN : CASE.get(state.getValue(FACING));
	}
}
