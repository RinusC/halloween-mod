package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The blocks the exits (cellar hatches, the gate, the police car's barriers) took the place of, and what stood
 * there, kept with the world: put back at the next match even if the game was closed in the middle of one (or the
 * town would keep hatches and gates of matches gone, that no map shows).
 */
public final class ExitProps extends SavedData {
	private record Pane(BlockPos pos, BlockState glass) {
		static final Codec<Pane> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Pane::pos),
			BlockState.CODEC.fieldOf("glass").forGetter(Pane::glass)).apply(i, Pane::new));
	}

	private static final Codec<ExitProps> CODEC = Pane.CODEC.listOf().xmap(ExitProps::fromList,
		w -> w.panes.entrySet().stream().map(e -> new Pane(e.getKey(), e.getValue())).toList());
	public static final SavedDataType<ExitProps> TYPE = new SavedDataType<>(Halloween.id("exit_props"), ExitProps::new, CODEC,
		DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<BlockPos, BlockState> panes = new LinkedHashMap<>();

	public ExitProps() {
	}

	private static ExitProps fromList(List<Pane> list) {
		ExitProps w = new ExitProps();
		list.forEach(p -> w.panes.put(p.pos(), p.glass()));
		return w;
	}

	static ExitProps get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	void add(BlockPos pos, BlockState glass) {
		if (this.panes.putIfAbsent(pos.immutable(), glass) == null) {
			this.setDirty();
		}
	}

	void remove(BlockPos pos) {
		if (this.panes.remove(pos) != null) {
			this.setDirty();
		}
	}

	Map<BlockPos, BlockState> panes() {
		return this.panes;
	}

	void clear() {
		this.panes.clear();
		this.setDirty();
	}
}
