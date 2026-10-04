package dev.halloween.game;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A desk telephone. Survivors call the police from it; the Shape cuts its line. */
public class PhoneBlock extends Block {
	public static final MapCodec<PhoneBlock> CODEC = simpleCodec(PhoneBlock::new);
	private static final VoxelShape SHAPE = Block.box(4, 0, 5, 12, 5, 11);

	public PhoneBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}
}
