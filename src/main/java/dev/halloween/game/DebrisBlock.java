package dev.halloween.game;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Something a survivor pulled down behind them in a chase (see Obstacles): a trash can, a bookcase, a barrel or a
 * hay bale lying across the way. Low enough to step over; whoever of Michael's side runs into it stumbles.
 */
public class DebrisBlock extends HorizontalDirectionalBlock {
	public enum Kind implements StringRepresentable {
		BIN, SHELF, BARREL, HAY;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
	public static final MapCodec<DebrisBlock> CODEC = simpleCodec(DebrisBlock::new);
	private static final VoxelShape LOW = Block.box(0, 0, 0, 16, 8, 16);

	public DebrisBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(KIND, Kind.BIN));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, KIND);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return LOW;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		// Stepped over, not climbed: what it costs is the stumble (Obstacles), not a wall.
		return Shapes.empty();
	}
}
