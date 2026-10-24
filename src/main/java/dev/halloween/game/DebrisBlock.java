package dev.halloween.game;

import com.mojang.serialization.MapCodec;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where something a survivor pulled down lies across the way (see Obstacles). The {@link Piece#CELL} is what stands
 * in the world: drawn by nothing (the bookcase or can lying there is the block displays it fell as), it is a wall
 * to Michael's side, too high to step or jump over, until he kicks it apart, and low enough for anyone else to step
 * over. The other pieces are never placed: they are models for those displays (the can and its lid apart, books,
 * rubbish, the wreck of a bookcase).
 */
public class DebrisBlock extends Block {
	public enum Piece implements StringRepresentable {
		CELL, BIN_BODY, BIN_LID, BOOK_A, BOOK_B, BOOK_OPEN, PAPERS, BAG, TRASH, PLANKS;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	public static final EnumProperty<Piece> PIECE = EnumProperty.create("piece", Piece.class);
	public static final MapCodec<DebrisBlock> CODEC = simpleCodec(DebrisBlock::new);
	/** What Michael aims his kick at, and what everyone else steps up onto. */
	private static final VoxelShape LOW = Block.box(0, 0, 0, 16, 9, 16);
	/** For Michael's side: no stepping over, no jumping over. (Also with no one in particular asking, so the game knows it reaches above its block.) */
	private static final VoxelShape WALL = Block.box(0, 0, 0, 16, 24, 16);
	/** Set by the client mod: whether this is the local player, of Michael's side and in his own form. */
	public static Predicate<Entity> clientBlocked = entity -> false;

	public DebrisBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(PIECE, Piece.CELL));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(PIECE);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return state.getValue(PIECE) == Piece.CELL ? RenderShape.INVISIBLE : RenderShape.MODEL;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(PIECE) == Piece.CELL ? LOW : Shapes.block();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (state.getValue(PIECE) != Piece.CELL) {
			return Shapes.empty();
		}
		if (context instanceof EntityCollisionContext ec && ec.getEntity() != null) {
			return blocks(ec.getEntity()) ? WALL : LOW;
		}
		return WALL;
	}

	/** Is this one stopped by it (Michael, the Demogorgon or Vecna, not in Shape Jump)? */
	public static boolean blocks(Entity entity) {
		if (entity.level().isClientSide()) {
			return clientBlocked.test(entity);
		}
		if (entity instanceof ServerPlayer player && MatchManager.role(player) == dev.halloween.Role.SHAPE) {
			ShapeState state = MatchManager.shapeState(player);
			return state != null && state.shadowTicks == 0;
		}
		return false;
	}
}
