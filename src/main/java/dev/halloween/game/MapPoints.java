package dev.halloween.game;

import com.mojang.serialization.Codec;
import dev.halloween.Halloween;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Named spots of the map, saved with the world: spawns, possible exit locations, phones and the centre
 * (containers are searched for around it). Placed with /hw point add, or by /hw arena.
 */
public final class MapPoints extends SavedData {
	public enum Type {
		CENTER, SPAWN_SHAPE, SPAWN_CIVILIAN, SPAWN_DEPUTY, CELLAR, CAR, POLICE, GATE, PHONE, NPC;

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static Type byKey(String key) {
			for (Type t : values()) {
				if (t.key().equals(key)) {
					return t;
				}
			}
			return null;
		}
	}

	private static final Codec<MapPoints> CODEC = Codec.unboundedMap(Codec.STRING, BlockPos.CODEC.listOf())
		.xmap(MapPoints::fromMap, MapPoints::toMap);

	public static final SavedDataType<MapPoints> TYPE = new SavedDataType<>(Halloween.id("map_points"), MapPoints::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<Type, List<BlockPos>> points = new EnumMap<>(Type.class);

	public MapPoints() {
	}

	private static MapPoints fromMap(Map<String, List<BlockPos>> map) {
		MapPoints mp = new MapPoints();
		map.forEach((key, list) -> {
			Type type = Type.byKey(key);
			if (type != null) {
				mp.points.put(type, new ArrayList<>(list));
			}
		});
		return mp;
	}

	private Map<String, List<BlockPos>> toMap() {
		Map<String, List<BlockPos>> map = new HashMap<>();
		this.points.forEach((type, list) -> map.put(type.key(), List.copyOf(list)));
		return map;
	}

	public static MapPoints get(ServerLevel level) {
		return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public List<BlockPos> list(Type type) {
		return this.points.getOrDefault(type, List.of());
	}

	public BlockPos first(Type type) {
		List<BlockPos> list = this.list(type);
		return list.isEmpty() ? null : list.get(0);
	}

	public void add(Type type, BlockPos pos) {
		if (type == Type.CENTER) {
			this.points.put(type, new ArrayList<>(List.of(pos.immutable())));
		} else {
			this.points.computeIfAbsent(type, t -> new ArrayList<>()).add(pos.immutable());
		}
		this.setDirty();
	}

	public void clear(Type type) {
		this.points.remove(type);
		this.setDirty();
	}

	public void clearAll() {
		this.points.clear();
		this.setDirty();
	}

	/** Removes the point of any type closest to {@code pos} within {@code radius}; returns its type or null. */
	public Type removeNear(BlockPos pos, double radius) {
		Type bestType = null;
		BlockPos best = null;
		double bestDist = radius * radius;
		for (Map.Entry<Type, List<BlockPos>> e : this.points.entrySet()) {
			for (BlockPos p : e.getValue()) {
				double d = p.distSqr(pos);
				if (d <= bestDist) {
					bestDist = d;
					best = p;
					bestType = e.getKey();
				}
			}
		}
		if (best != null) {
			this.points.get(bestType).remove(best);
			this.setDirty();
		}
		return bestType;
	}

	public Map<Type, List<BlockPos>> all() {
		return this.points;
	}
}
