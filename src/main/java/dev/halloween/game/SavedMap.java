package dev.halloween.game;

import dev.halloween.Halloween;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A map read from a file: the same square a {@link MapPlan} lays, but with every block, block entity and
 * map point taken from a snapshot made by {@link MapSaveJob} (/hw map save). Looked for first in
 * {@code <game dir>/halloween_maps/<name>.nbt}, then in the mod itself ({@code data/halloween/maps/<name>.nbt}).
 */
public final class SavedMap extends MapPlan {
	static final int VERSION = 1;
	private static final String RESOURCE = "/data/" + Halloween.MOD_ID + "/maps/";

	private final BlockState[] palette;
	private final int[] indices;
	private final int side;
	private final int height;

	private SavedMap(int radius, int yMin, int yMax, BlockState[] palette, int[] indices) {
		super(radius, yMin, yMax);
		this.palette = palette;
		this.indices = indices;
		this.side = radius * 2 + 1;
		this.height = yMax - yMin + 1;
	}

	public static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve("halloween_maps");
	}

	public static Path file(String name) {
		return folder().resolve(name + ".nbt");
	}

	public static boolean validName(String name) {
		return name.matches("[a-z0-9_]{1,40}");
	}

	/** Index of a block in the flat array: x and z from the square's corner, y from the bottom. */
	static int index(int radius, int yMin, int yMax, int x, int y, int z) {
		int side = radius * 2 + 1;
		return ((x + radius) * side + (z + radius)) * (yMax - yMin + 1) + (y - yMin);
	}

	@Override
	BlockState at(int x, int y, int z) {
		if (Math.abs(x) > this.radius || Math.abs(z) > this.radius || y < this.yMin || y > this.yMax) {
			return this.ground(x, y, z);
		}
		return this.palette[this.indices[((x + this.radius) * this.side + (z + this.radius)) * this.height + (y - this.yMin)]];
	}

	/** The snapshot called {@code name}, or null when there is none (a file next to the game wins over the one in the mod). */
	public static SavedMap load(String name) throws IOException {
		CompoundTag root;
		Path file = file(name);
		if (Files.isRegularFile(file)) {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} else {
			try (InputStream in = SavedMap.class.getResourceAsStream(RESOURCE + name + ".nbt")) {
				if (in == null) {
					return null;
				}
				root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
			}
		}
		int radius = root.getIntOr("radius", 0);
		int yMin = root.getIntOr("yMin", 0);
		int yMax = root.getIntOr("yMax", 0);
		ListTag paletteTags = root.getListOrEmpty("palette");
		BlockState[] palette = new BlockState[paletteTags.size()];
		for (int i = 0; i < palette.length; i++) {
			palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteTags.getCompoundOrEmpty(i));
		}
		int[] indices = root.getIntArray("blocks").orElse(new int[0]);
		int side = radius * 2 + 1;
		if (radius <= 0 || yMax < yMin || indices.length != side * side * (yMax - yMin + 1)) {
			throw new IOException("Broken map file: " + name);
		}
		SavedMap map = new SavedMap(radius, yMin, yMax, palette, indices);
		ListTag entities = root.getListOrEmpty("entities");
		for (int i = 0; i < entities.size(); i++) {
			CompoundTag e = entities.getCompoundOrEmpty(i);
			map.blockEntities.put(new BlockPos(e.getIntOr("x", 0), e.getIntOr("y", 0), e.getIntOr("z", 0)), e.getCompoundOrEmpty("data"));
		}
		CompoundTag points = root.getCompoundOrEmpty("points");
		for (String key : points.keySet()) {
			MapPoints.Type type = MapPoints.Type.byKey(key);
			int[] xyz = points.getIntArray(key).orElse(new int[0]);
			for (int i = 0; type != null && i + 2 < xyz.length; i += 3) {
				map.point(type, xyz[i], xyz[i + 1], xyz[i + 2]);
			}
		}
		return map;
	}

	/** Writes a snapshot. Called by {@link MapSaveJob} once every column has been read. */
	static void write(Path file, int radius, int yMin, int yMax, List<BlockState> palette, int[] indices,
		Map<BlockPos, CompoundTag> blockEntities, Map<MapPoints.Type, List<BlockPos>> points) throws IOException {
		CompoundTag root = new CompoundTag();
		root.putInt("version", VERSION);
		root.putInt("radius", radius);
		root.putInt("yMin", yMin);
		root.putInt("yMax", yMax);
		ListTag paletteTags = new ListTag();
		for (BlockState state : palette) {
			paletteTags.add(NbtUtils.writeBlockState(state));
		}
		root.put("palette", paletteTags);
		root.putIntArray("blocks", indices);
		ListTag entities = new ListTag();
		blockEntities.forEach((pos, data) -> {
			CompoundTag e = new CompoundTag();
			e.putInt("x", pos.getX());
			e.putInt("y", pos.getY());
			e.putInt("z", pos.getZ());
			e.put("data", data);
			entities.add(e);
		});
		root.put("entities", entities);
		CompoundTag pointTags = new CompoundTag();
		points.forEach((type, list) -> {
			int[] xyz = new int[list.size() * 3];
			for (int i = 0; i < list.size(); i++) {
				xyz[i * 3] = list.get(i).getX();
				xyz[i * 3 + 1] = list.get(i).getY();
				xyz[i * 3 + 2] = list.get(i).getZ();
			}
			pointTags.putIntArray(type.key(), xyz);
		});
		root.put("points", pointTags);
		Files.createDirectories(file.getParent());
		NbtIo.writeCompressed(root, file);
	}

	/** Names of the snapshots that can be loaded (files next to the game, then those in the mod are not listed). */
	public static List<String> names() {
		List<String> names = new ArrayList<>();
		try (var stream = Files.list(folder())) {
			stream.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".nbt"))
				.forEach(n -> names.add(n.substring(0, n.length() - 4)));
		} catch (IOException ignored) {
			// No folder yet: nothing saved.
		}
		return names;
	}
}
