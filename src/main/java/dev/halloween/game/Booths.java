package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What is left of the telephone booths that used to lift Vecna's curse (gone: the curse is fought another way now,
 * see {@link VecnaCurse}): a world that still has booths standing from before gets what stood there back.
 */
public final class Booths {
	private Booths() {
	}

	/** Everything the booths stood on put back as it was. */
	public static void clear(ServerLevel level) {
		Saved saved = Saved.get(level);
		if (saved.cells.isEmpty()) {
			return;
		}
		for (Saved.Cell c : saved.cells) {
			level.setBlock(c.pos(), c.state(), Block.UPDATE_CLIENTS);
		}
		saved.cells.clear();
		saved.setDirty();
	}

	/** What stood where the booths were, kept with the world until it is put back. */
	public static final class Saved extends SavedData {
		record Cell(BlockPos pos, BlockState state) {
			static final Codec<Cell> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Cell::pos),
				BlockState.CODEC.fieldOf("state").forGetter(Cell::state)
			).apply(i, Cell::new));
		}

		private static final Codec<Saved> CODEC = Cell.CODEC.listOf().xmap(Saved::new, s -> List.copyOf(s.cells));
		public static final SavedDataType<Saved> TYPE = new SavedDataType<>(Halloween.id("vecna_booths"), Saved::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

		final List<Cell> cells = new ArrayList<>();

		public Saved() {
		}

		private Saved(List<Cell> cells) {
			this.cells.addAll(cells);
		}

		static Saved get(ServerLevel level) {
			return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
		}
	}
}
