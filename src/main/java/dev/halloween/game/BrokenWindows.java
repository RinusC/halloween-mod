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
 * The windows smashed this match and what glass they had, kept with the world: put back at the next match even
 * if the game was closed in between (the abandoned house's broken panes are built that way and never listed).
 */
public final class BrokenWindows extends SavedData {
	private record Pane(BlockPos pos, BlockState glass) {
		static final Codec<Pane> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Pane::pos),
			BlockState.CODEC.fieldOf("glass").forGetter(Pane::glass)).apply(i, Pane::new));
	}

	private static final Codec<BrokenWindows> CODEC = Pane.CODEC.listOf().xmap(BrokenWindows::fromList,
		w -> w.panes.entrySet().stream().map(e -> new Pane(e.getKey(), e.getValue())).toList());
	public static final SavedDataType<BrokenWindows> TYPE = new SavedDataType<>(Halloween.id("broken_windows"), BrokenWindows::new, CODEC,
		DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<BlockPos, BlockState> panes = new LinkedHashMap<>();

	public BrokenWindows() {
	}

	private static BrokenWindows fromList(List<Pane> list) {
		BrokenWindows w = new BrokenWindows();
		list.forEach(p -> w.panes.put(p.pos(), p.glass()));
		return w;
	}

	static BrokenWindows get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	void add(BlockPos pos, BlockState glass) {
		if (this.panes.putIfAbsent(pos.immutable(), glass) == null) {
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
