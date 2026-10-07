package dev.halloween.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

/**
 * Town map boards in the street (on Main Street and on Lampkin Lane, see the map_board points): the plan of the
 * town on four maps in glow frames, 2 by 2, north up, with this match's storm cellars and gate drawn on and a red
 * "you are here" dot; a legend sign on the post. Nothing else points the way: survivors have to read the map. The
 * exits move from match to match, so the maps are redrawn at every start (the frames and map ids stay).
 */
public final class MapBoards {
	public static final String TAG = "hw_map_board";
	/** Maps across and down (128 pixels each). */
	private static final int TILES = 2;
	private static final int SIZE = 128 * TILES;

	private MapBoards() {
	}

	public static void setup(ServerLevel level, BoundingBox area, BlockPos center) {
		List<BlockPos> boards = MapPoints.get(level).list(MapPoints.Type.MAP_BOARD);
		if (boards.isEmpty()) {
			return;
		}
		// The plan once (it is the same for every board), then each board gets its own "you are here".
		int x0 = area.minX() - 8;
		int z0 = area.minZ() - 8;
		double span = Math.max(area.maxX() + 8 - x0, area.maxZ() + 8 - z0) + 1;
		double scale = span / SIZE;
		byte[] plan = new byte[SIZE * SIZE];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				int x = (int) Math.floor(x0 + (px + 0.5) * scale);
				int z = (int) Math.floor(z0 + (py + 0.5) * scale);
				plan[py * SIZE + px] = color(Minimap.kindAt(level, x, z, center.getY(), pos));
			}
		}
		frame(plan);
		for (Exits.Marker m : Exits.markers()) {
			int px = (int) ((m.pos().getX() + 0.5 - x0) / scale);
			int py = (int) ((m.pos().getZ() + 0.5 - z0) / scale);
			if (m.cellar()) {
				cellar(plan, px, py);
			} else {
				gate(plan, px, py);
			}
		}
		for (BlockPos board : boards) {
			byte[] mine = plan.clone();
			here(mine, (int) ((board.getX() + 1 - x0) / scale), (int) ((board.getZ() + 1.5 - z0) / scale));
			List<MapId> ids = frames(level, board);
			for (int t = 0; t < ids.size(); t++) {
				int tx = t % TILES;
				int ty = t / TILES;
				MapItemSavedData data = level.getMapData(ids.get(t));
				if (data == null) {
					continue;
				}
				for (int y = 0; y < 128; y++) {
					for (int x = 0; x < 128; x++) {
						data.setColor(x, y, mine[(ty * 128 + y) * SIZE + tx * 128 + x]);
					}
				}
				data.setDirty();
			}
			legend(level, board);
		}
		AiStats.add("*total", "map_board.drawn");
	}

	private static byte color(byte kind) {
		return switch (kind) {
			case Minimap.ROAD -> MapColor.COLOR_LIGHT_GRAY.getPackedId(MapColor.Brightness.NORMAL);
			case Minimap.TREES -> MapColor.PLANT.getPackedId(MapColor.Brightness.LOW);
			case Minimap.BUILDING -> MapColor.TERRACOTTA_BROWN.getPackedId(MapColor.Brightness.NORMAL);
			default -> MapColor.SAND.getPackedId(MapColor.Brightness.NORMAL);
		};
	}

	private static void put(byte[] plan, int x, int y, MapColor color, MapColor.Brightness b) {
		if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
			plan[y * SIZE + x] = color.getPackedId(b);
		}
	}

	private static void frame(byte[] plan) {
		for (int i = 0; i < SIZE; i++) {
			for (int k : new int[]{0, 1, SIZE - 2, SIZE - 1}) {
				put(plan, i, k, MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
				put(plan, k, i, MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
			}
		}
	}

	/** A storm cellar: a brown hatch of planks in a black outline (9 by 9). */
	private static void cellar(byte[] plan, int cx, int cy) {
		for (int dy = -4; dy <= 4; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				boolean edge = Math.abs(dx) == 4 || Math.abs(dy) == 4;
				boolean seam = !edge && Math.floorMod(dy, 3) == 1;
				put(plan, cx + dx, cy + dy, edge || seam ? MapColor.COLOR_BLACK : MapColor.COLOR_BROWN,
					edge || seam ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH);
			}
		}
	}

	/** The gate out of town: grey bars in a black outline (9 by 9). */
	private static void gate(byte[] plan, int cx, int cy) {
		for (int dy = -4; dy <= 4; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				boolean edge = Math.abs(dx) == 4 || Math.abs(dy) == 4;
				boolean bar = !edge && (Math.floorMod(dx, 2) == 1 || dy == -2);
				put(plan, cx + dx, cy + dy, edge || bar ? MapColor.COLOR_BLACK : MapColor.QUARTZ, MapColor.Brightness.NORMAL);
			}
		}
	}

	/** You are here: a red dot ringed in white (9 across). */
	private static void here(byte[] plan, int cx, int cy) {
		for (int dy = -4; dy <= 4; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d <= 2.6) {
					put(plan, cx + dx, cy + dy, MapColor.COLOR_RED, d <= 1.2 ? MapColor.Brightness.LOW : MapColor.Brightness.HIGH);
				} else if (d <= 4.1) {
					put(plan, cx + dx, cy + dy, d <= 3.4 ? MapColor.SNOW : MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
				}
			}
		}
	}

	/**
	 * The board's four glow frames (top row first, west to east), each with its own map; made the first time,
	 * found again after. {@code board}: the lower west square of the board, the frames hang on its south face.
	 */
	private static List<MapId> frames(ServerLevel level, BlockPos board) {
		List<GlowItemFrame> found = level.getEntities(EntityTypes.GLOW_ITEM_FRAME, new AABB(board).inflate(3), f -> f.entityTags().contains(TAG));
		List<MapId> ids = new ArrayList<>();
		if (found.size() == TILES * TILES) {
			found.sort((a, b) -> a.getBlockY() != b.getBlockY() ? Integer.compare(b.getBlockY(), a.getBlockY()) : Integer.compare(a.getBlockX(), b.getBlockX()));
			for (GlowItemFrame f : found) {
				MapId id = f.getItem().get(DataComponents.MAP_ID);
				if (id == null || level.getMapData(id) == null) {
					ids.clear();
					break;
				}
				ids.add(id);
			}
			if (ids.size() == TILES * TILES) {
				return ids;
			}
		}
		found.forEach(f -> f.discard());
		ids.clear();
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		for (int ty = 0; ty < TILES; ty++) {
			for (int tx = 0; tx < TILES; tx++) {
				MapId id = level.getFreeMapId();
				level.setMapData(id, MapItemSavedData.createFresh(board.getX(), board.getZ(), (byte) 0, false, false, level.dimension()).locked());
				ids.add(id);
				BlockPos at = board.offset(tx, TILES - 1 - ty, 1);
				level.getServer().getCommands().performPrefixedCommand(console, String.format(Locale.ROOT,
					"summon minecraft:glow_item_frame %d %d %d {Facing:3b,Fixed:1b,Invisible:1b,Tags:[\"%s\"],Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":%d}}}",
					at.getX(), at.getY(), at.getZ(), TAG, id.id()));
			}
		}
		return ids;
	}

	/** The legend on the west post: what the marks mean. */
	private static void legend(ServerLevel level, BlockPos board) {
		BlockPos at = board.offset(-1, 0, 1);
		if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) {
			return;
		}
		SignText text = new SignText()
			.setMessage(0, Component.translatable("halloween.board.title").withStyle(ChatFormatting.BOLD))
			.setMessage(1, Component.translatable("halloween.board.cellar").withStyle(s -> s.withColor(TextColor.fromRgb(0x8B5A2B))))
			.setMessage(2, Component.translatable("halloween.board.gate").withStyle(ChatFormatting.DARK_GRAY))
			.setMessage(3, Component.translatable("halloween.board.here").withStyle(ChatFormatting.DARK_RED))
			.setHasGlowingText(true);
		sign.setText(text, true);
		sign.setChanged();
		level.sendBlockUpdated(at, level.getBlockState(at), level.getBlockState(at), 3);
	}
}
