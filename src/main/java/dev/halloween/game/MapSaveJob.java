package dev.halloween.game;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads the town around the CENTER map point into a file (see {@link SavedMap}), a few milliseconds a tick
 * so the server keeps running: every block of the square, the block entities (containers, signs) and the
 * map points. What is built by hand in the world is what ends up in the file.
 */
public final class MapSaveJob {
	private static final long BUDGET_NANOS = 35_000_000L;
	private static MapSaveJob current;

	private final ServerLevel level;
	private final BlockPos origin;
	private final CommandSourceStack source;
	private final String name;
	private final int radius;
	private final int yMin;
	private final int yMax;
	private final int[] indices;
	private final List<BlockState> palette = new ArrayList<>();
	private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
	private final Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
	private int column;
	private long lastReport;
	private final long started = System.currentTimeMillis();

	private MapSaveJob(ServerLevel level, BlockPos origin, CommandSourceStack source, String name, int radius, int yMin, int yMax) {
		this.level = level;
		this.origin = origin;
		this.source = source;
		this.name = name;
		this.radius = radius;
		this.yMin = yMin;
		this.yMax = yMax;
		this.indices = new int[(radius * 2 + 1) * (radius * 2 + 1) * (yMax - yMin + 1)];
	}

	public static boolean busy() {
		return current != null;
	}

	/** Starts saving the square of the given size around the centre point; false when the map has no centre. */
	public static boolean start(ServerLevel level, CommandSourceStack source, String name, int radius, int yMin, int yMax) {
		BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
		if (center == null) {
			return false;
		}
		current = new MapSaveJob(level, center, source, name, radius, yMin, yMax);
		return true;
	}

	public static void tick(MinecraftServer server) {
		if (current != null) {
			current.step(System.nanoTime() + BUDGET_NANOS);
		}
	}

	/** Runs the whole job at once (tests). */
	public static void finishNow() {
		while (current != null) {
			current.step(Long.MAX_VALUE);
		}
	}

	private void step(long until) {
		int side = this.radius * 2 + 1;
		int total = side * side;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (this.column < total && System.nanoTime() < until) {
			int x = this.column / side - this.radius;
			int z = this.column % side - this.radius;
			for (int y = this.yMin; y <= this.yMax; y++) {
				pos.set(this.origin.getX() + x, this.origin.getY() + y, this.origin.getZ() + z);
				BlockState state = this.level.getBlockState(pos);
				int id = this.paletteIndex.computeIfAbsent(state, s -> {
					this.palette.add(s);
					return this.palette.size() - 1;
				});
				this.indices[SavedMap.index(this.radius, this.yMin, this.yMax, x, y, z)] = id;
				if (state.hasBlockEntity()) {
					BlockEntity be = this.level.getBlockEntity(pos);
					if (be != null) {
						this.blockEntities.put(new BlockPos(x, y, z), be.saveWithFullMetadata(this.level.registryAccess()));
					}
				}
			}
			this.column++;
		}
		this.report(this.column * 100 / total);
		if (this.column >= total) {
			this.finish();
			current = null;
		}
	}

	private void report(int percent) {
		long now = System.currentTimeMillis();
		if (now - this.lastReport > 3000) {
			this.lastReport = now;
			this.source.sendSystemMessage(Component.translatable("halloween.cmd.map_progress", this.name, percent).withStyle(ChatFormatting.GRAY));
		}
	}

	private void finish() {
		Map<MapPoints.Type, List<BlockPos>> points = new HashMap<>();
		MapPoints.get(this.level).all().forEach((type, list) -> {
			for (BlockPos p : list) {
				BlockPos local = p.subtract(this.origin);
				if (Math.abs(local.getX()) <= this.radius && Math.abs(local.getZ()) <= this.radius) {
					points.computeIfAbsent(type, t -> new ArrayList<>()).add(local);
				}
			}
		});
		Path file = SavedMap.file(this.name);
		try {
			SavedMap.write(file, this.radius, this.yMin, this.yMax, this.palette, this.indices, this.blockEntities, points);
		} catch (IOException e) {
			this.source.sendFailure(Component.translatable("halloween.cmd.map_save_failed", this.name, e.toString()));
			return;
		}
		long seconds = (System.currentTimeMillis() - this.started) / 1000;
		this.source.sendSuccess(() -> Component.translatable("halloween.cmd.map_saved", this.name, seconds, file.toAbsolutePath().toString())
			.withStyle(ChatFormatting.GREEN), true);
	}
}
