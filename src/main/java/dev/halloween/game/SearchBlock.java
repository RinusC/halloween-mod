package dev.halloween.game;

import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Furniture survivors search (a dresser, a nightstand, a kitchen cabinet, a cardboard box, a crate, a tool
 * chest, a filing cabinet). Its front faces {@link #FACING}; once searched it stays {@link #OPEN}: a drawer
 * pulled out, a door swung open, the flaps up.
 */
public class SearchBlock extends HorizontalDirectionalBlock {
	public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

	/** What it is made of: how it sounds when someone goes through it. */
	public enum Stuff {
		WOOD(SoundEvents.BARREL_OPEN, 1.1f),
		METAL(SoundEvents.IRON_TRAPDOOR_OPEN, 1.4f),
		CARDBOARD(SoundEvents.BOOK_PAGE_TURN, 0.8f);

		public final SoundEvent sound;
		public final float pitch;

		Stuff(SoundEvent sound, float pitch) {
			this.sound = sound;
			this.pitch = pitch;
		}
	}

	private final Stuff stuff;
	private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
	private final MapCodec<SearchBlock> codec;

	/** {@code box}: the body for a piece facing north (front at z=0), in pixels. */
	public SearchBlock(Stuff stuff, double[] box, Properties properties) {
		super(properties);
		this.stuff = stuff;
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			this.shapes.put(facing, LockerBlock.box(facing, box[0], box[1], box[2], box[3], box[4], box[5]));
		}
		this.codec = simpleCodec(p -> new SearchBlock(stuff, box, p));
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPEN, false));
	}

	public Stuff stuff() {
		return this.stuff;
	}

	public static boolean is(BlockState state) {
		return state.getBlock() instanceof SearchBlock;
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return this.codec;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, OPEN);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.shapes.get(state.getValue(FACING));
	}
}
