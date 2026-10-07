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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

/**
 * Town map boards in the street (on Main Street and on Lampkin Lane, see the map_board points): twelve maps in
 * glow frames, 4 across and 3 down. The town fills the left square, north up, drawn clean like a tourist map
 * (sand-coloured ground, white streets, grey houses, green where the trees are), with the street names and the
 * landmarks written on, this match's storm cellars and gate marked and a red "you are here" dot; the right
 * column is the legend, with a north arrow. Nothing else points the way: survivors have to read the map. The
 * exits move from match to match, so the maps are redrawn at every start (the frames and map ids stay).
 */
public final class MapBoards {
	public static final String TAG = "hw_map_board";
	/** Maps across and down (128 pixels each); the town takes the left {@code TY} by {@code TY} of them. */
	public static final int TX = 4;
	public static final int TY = 3;
	private static final int W = 128 * TX;
	private static final int H = 128 * TY;
	private static final int TOWN = H;

	private static final net.minecraft.world.level.block.Block GRAY_CONCRETE = MapPlan.b("gray_concrete").getBlock();
	private static final net.minecraft.world.level.block.Block YELLOW_TERRACOTTA = MapPlan.b("yellow_terracotta").getBlock();
	private static final byte GROUND = 0;
	private static final byte STREET = 1;
	private static final byte TREES = 2;
	private static final byte BUILDING = 3;

	private MapBoards() {
	}

	public static void setup(ServerLevel level, BoundingBox area, BlockPos center) {
		List<BlockPos> boards = MapPoints.get(level).list(MapPoints.Type.MAP_BOARD);
		if (boards.isEmpty()) {
			return;
		}
		int x0 = area.minX() - 6;
		int z0 = area.minZ() - 6;
		double span = Math.max(area.maxX() + 6 - x0, area.maxZ() + 6 - z0) + 1;
		double scale = span / TOWN;
		byte[] plan = plan(level, x0, z0, scale, center.getY());
		byte[] img = new byte[W * H];
		paintTown(plan, img);
		// The names, if this is Haddonfield (its streets lie at fixed places around the centre).
		if (level.getBlockState(center.offset(Haddonfield.ELM, -1, Haddonfield.LAMPKIN)).is(GRAY_CONCRETE)) {
			labels(img, x0, z0, scale, center);
		}
		for (Exits.Marker m : Exits.markers()) {
			int px = (int) ((m.pos().getX() + 0.5 - x0) / scale);
			int py = (int) ((m.pos().getZ() + 0.5 - z0) / scale);
			if (m.cellar()) {
				cellar(img, px, py);
			} else {
				gate(img, px, py);
			}
		}
		legend(img);
		for (BlockPos board : boards) {
			byte[] mine = img.clone();
			here(mine, (int) ((board.getX() + TX / 2.0 - x0) / scale), (int) ((board.getZ() + 1.5 - z0) / scale));
			List<MapId> ids = frames(level, board);
			for (int t = 0; t < ids.size(); t++) {
				int tx = t % TX;
				int ty = t / TX;
				MapItemSavedData data = level.getMapData(ids.get(t));
				if (data == null) {
					continue;
				}
				for (int y = 0; y < 128; y++) {
					for (int x = 0; x < 128; x++) {
						data.setColor(x, y, mine[(ty * 128 + y) * W + tx * 128 + x]);
					}
				}
				data.setDirty();
			}
			signs(level, board);
		}
		AiStats.add("*total", "map_board.drawn");
	}

	// ---------------------------------------------------------------- the plan

	/** What each pixel of the town square is: ground, street (road or pavement), tree tops, a building. */
	private static byte[] plan(ServerLevel level, int x0, int z0, double scale, int base) {
		byte[] plan = new byte[TOWN * TOWN];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int py = 0; py < TOWN; py++) {
			for (int px = 0; px < TOWN; px++) {
				int x = (int) Math.floor(x0 + (px + 0.5) * scale);
				int z = (int) Math.floor(z0 + (py + 0.5) * scale);
				BlockState ground = level.getBlockState(pos.set(x, base - 1, z));
				byte kind = ground.is(GRAY_CONCRETE) || ground.is(YELLOW_TERRACOTTA) || ground.is(Blocks.SMOOTH_STONE) ? STREET : GROUND;
				boolean crown = false;
				for (int dy = 0; dy <= 10; dy++) {
					BlockState s = level.getBlockState(pos.set(x, base + dy, z));
					if (s.isAir()) {
						continue;
					}
					if (s.is(BlockTags.LEAVES)) {
						// Tree tops only, not hedges and bushes.
						crown |= dy >= 3;
					} else if (dy >= 1 && s.isSolidRender() && !s.is(BlockTags.LOGS)) {
						kind = BUILDING;
						break;
					}
				}
				plan[py * TOWN + px] = kind != BUILDING && crown ? TREES : kind;
			}
		}
		// Tidy up: a lone pixel of something takes on what is around it.
		byte[] tidy = plan.clone();
		for (int py = 1; py < TOWN - 1; py++) {
			for (int px = 1; px < TOWN - 1; px++) {
				byte k = plan[py * TOWN + px];
				int same = 0;
				int[] count = new int[4];
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						if (dx != 0 || dy != 0) {
							byte n = plan[(py + dy) * TOWN + px + dx];
							count[n]++;
							same += n == k ? 1 : 0;
						}
					}
				}
				if (same <= 1) {
					int best = 0;
					for (int i = 1; i < 4; i++) {
						best = count[i] > count[best] ? i : best;
					}
					tidy[py * TOWN + px] = (byte) best;
				}
			}
		}
		return tidy;
	}

	private static byte c(MapColor color, MapColor.Brightness b) {
		// One shade darker throughout: an old paper map on a street board at night, not a lit screen.
		MapColor.Brightness dim = switch (b) {
			case HIGH -> MapColor.Brightness.NORMAL;
			case NORMAL -> MapColor.Brightness.LOW;
			default -> MapColor.Brightness.LOWEST;
		};
		return color.getPackedId(dim);
	}

	private static void put(byte[] img, int x, int y, byte color) {
		if (x >= 0 && y >= 0 && x < W && y < H) {
			img[y * W + x] = color;
		}
	}

	/** Colours the town: each kind its colour, a darker edge where it meets something else. */
	private static void paintTown(byte[] plan, byte[] img) {
		for (int py = 0; py < TOWN; py++) {
			for (int px = 0; px < TOWN; px++) {
				byte k = plan[py * TOWN + px];
				boolean edge = false;
				for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int nx = px + d[0];
					int ny = py + d[1];
					if (nx >= 0 && ny >= 0 && nx < TOWN && ny < TOWN && plan[ny * TOWN + nx] != k && (k == BUILDING || k == STREET && plan[ny * TOWN + nx] != BUILDING)) {
						edge = true;
					}
				}
				byte color = switch (k) {
					case STREET -> edge ? c(MapColor.COLOR_LIGHT_GRAY, MapColor.Brightness.NORMAL) : c(MapColor.QUARTZ, MapColor.Brightness.HIGH);
					case TREES -> c(MapColor.PLANT, MapColor.Brightness.NORMAL);
					case BUILDING -> edge ? c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL) : c(MapColor.COLOR_GRAY, MapColor.Brightness.NORMAL);
					default -> c(MapColor.SAND, MapColor.Brightness.HIGH);
				};
				img[py * W + px] = color;
			}
		}
		byte border = c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
		for (int i = 0; i < TOWN; i++) {
			for (int k : new int[]{0, 1, TOWN - 2, TOWN - 1}) {
				put(img, i, k, border);
				put(img, k, i, border);
			}
		}
	}

	// ---------------------------------------------------------------- writing

	/** A small capital font, 3 wide and 5 high ('#' is ink). */
	private static final String[] FONT = {
		" ................",
		"'..#..#..........",
		"...............#.",
		"A..#.#.#####.##.#",
		"B.##.#.###.#.###.",
		"C..###..#..#...##",
		"D.##.#.##.##.###.",
		"E.####..##.#..###",
		"F.####..##.#..#..",
		"G..###..#.##.#.##",
		"H.#.##.#####.##.#",
		"I.###.#..#..#.###",
		"J...#..#..##.#.#.",
		"K.#.##.###.#.##.#",
		"L.#..#..#..#..###",
		"M.#.########.##.#",
		"N.##.#.##.##.##.#",
		"O..#.#.##.##.#.#.",
		"P.##.#.###.#..#..",
		"Q..#.#.##.###..##",
		"R.##.#.###.#.##.#",
		"S..###...#...###.",
		"T.###.#..#..#..#.",
		"U.#.##.##.##.####",
		"V.#.##.##.##.#.#.",
		"W.#.##.########.#",
		"X.#.##.#.#.#.##.#",
		"Y.#.##.#.#..#..#.",
		"Z.###..#.#.#..###"};

	private static String glyph(char ch) {
		for (String g : FONT) {
			if (g.charAt(0) == ch) {
				String bits = g.substring(2);
				return (bits + "...............").substring(0, 15);
			}
		}
		return "...............";
	}

	/** Writes {@code text} with its middle at (cx, cy), across (or down, letter under letter), with a halo for clarity. */
	private static void write(byte[] img, String text, int cx, int cy, boolean down, byte ink, byte halo) {
		int n = text.length();
		int x = down ? cx - 1 : cx - (n * 4 - 1) / 2;
		int y = down ? cy - (n * 6 - 1) / 2 : cy - 2;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < n; i++) {
				String g = glyph(text.charAt(i));
				int gx = down ? x : x + i * 4;
				int gy = down ? y + i * 6 : y;
				for (int r = 0; r < 5; r++) {
					for (int col = 0; col < 3; col++) {
						if (g.charAt(r * 3 + col) != '#') {
							continue;
						}
						if (pass == 0) {
							if (halo != 0) {
								for (int dy = -1; dy <= 1; dy++) {
									for (int dx = -1; dx <= 1; dx++) {
										put(img, gx + col + dx, gy + r + dy, halo);
									}
								}
							}
						} else {
							put(img, gx + col, gy + r, ink);
						}
					}
				}
			}
		}
	}

	/** Haddonfield's street names and landmarks (positions from the town plan, around its centre). */
	private static void labels(byte[] img, int x0, int z0, double scale, BlockPos center) {
		byte ink = c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
		byte paper = c(MapColor.SNOW, MapColor.Brightness.NORMAL);
		Object[][] streets = {
			{"ORANGE GROVE AVE", -108, Haddonfield.ORANGE_GROVE, false}, {"LAMPKIN LANE", 30, Haddonfield.LAMPKIN, false},
			{"MAIN STREET", 108, Haddonfield.MAIN, false}, {"ELM ST", Haddonfield.ELM, -30, true}, {"HACKBERRY AVE", Haddonfield.HACKBERRY, 30, true}};
		for (Object[] s : streets) {
			int px = (int) ((center.getX() + (int) s[1] - x0) / scale);
			int py = (int) ((center.getZ() + (int) s[2] - z0) / scale);
			write(img, (String) s[0], px, py, (boolean) s[3], ink, (byte) 0);
		}
		Object[][] places = {{"SCHOOL", 0, -100}, {"CEMETERY", -108, -108}, {"PARK", -108, 104}, {"CHURCH", 0, 126}, {"MYERS", -108, -26}};
		for (Object[] p : places) {
			int px = (int) ((center.getX() + (int) p[1] - x0) / scale);
			int py = (int) ((center.getZ() + (int) p[2] - z0) / scale);
			write(img, (String) p[0], px, py, false, ink, paper);
		}
	}

	// ---------------------------------------------------------------- marks

	/** A storm cellar: a brown hatch of planks in a black outline (13 by 13). */
	private static void cellar(byte[] img, int cx, int cy) {
		for (int dy = -6; dy <= 6; dy++) {
			for (int dx = -6; dx <= 6; dx++) {
				boolean edge = Math.abs(dx) == 6 || Math.abs(dy) == 6;
				boolean seam = !edge && Math.floorMod(dy, 3) == 0;
				boolean ring = Math.abs(dx) == 1 && dy == 0;
				put(img, cx + dx, cy + dy, edge || seam ? c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL)
					: ring ? c(MapColor.GOLD, MapColor.Brightness.HIGH) : c(MapColor.COLOR_BROWN, MapColor.Brightness.HIGH));
			}
		}
	}

	/** The gate out of town: an arch of black bars on white in a black outline (13 by 13). */
	private static void gate(byte[] img, int cx, int cy) {
		for (int dy = -6; dy <= 6; dy++) {
			for (int dx = -6; dx <= 6; dx++) {
				boolean edge = Math.abs(dx) == 6 || Math.abs(dy) == 6;
				boolean bar = !edge && (Math.floorMod(dx, 2) == 1 && dy > -4 || dy == -4 || dy == -3);
				put(img, cx + dx, cy + dy, edge || bar ? c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL) : c(MapColor.QUARTZ, MapColor.Brightness.HIGH));
			}
		}
	}

	/** You are here: a red dot ringed in white and black (13 across). */
	private static void here(byte[] img, int cx, int cy) {
		for (int dy = -6; dy <= 6; dy++) {
			for (int dx = -6; dx <= 6; dx++) {
				double d = Math.sqrt(dx * dx + dy * dy);
				if (d <= 3.8) {
					put(img, cx + dx, cy + dy, c(MapColor.COLOR_RED, d <= 1.6 ? MapColor.Brightness.LOW : MapColor.Brightness.HIGH));
				} else if (d <= 6.2) {
					put(img, cx + dx, cy + dy, d <= 5.2 ? c(MapColor.SNOW, MapColor.Brightness.NORMAL) : c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL));
				}
			}
		}
	}

	/** The right column: the town's name, a north arrow, what the marks mean. */
	private static void legend(byte[] img) {
		byte paper = c(MapColor.SNOW, MapColor.Brightness.NORMAL);
		byte ink = c(MapColor.COLOR_BLACK, MapColor.Brightness.NORMAL);
		for (int y = 0; y < H; y++) {
			for (int x = TOWN; x < W; x++) {
				boolean edge = x < TOWN + 2 || x >= W - 2 || y < 2 || y >= H - 2;
				img[y * W + x] = edge ? ink : paper;
			}
		}
		int mid = TOWN + (W - TOWN) / 2;
		write(img, "HADDONFIELD", mid, 20, false, ink, (byte) 0);
		write(img, "ILLINOIS", mid, 30, false, c(MapColor.COLOR_GRAY, MapColor.Brightness.NORMAL), (byte) 0);
		// North arrow.
		for (int r = 0; r < 16; r++) {
			for (int dx = -r / 2; dx <= r / 2; dx++) {
				put(img, mid + dx, 52 + r, r < 9 ? ink : c(MapColor.COLOR_GRAY, MapColor.Brightness.NORMAL));
			}
		}
		write(img, "N", mid, 74, false, ink, (byte) 0);
		cellar(img, TOWN + 18, 120);
		write(img, "STORM CELLAR", TOWN + 18 + 12 + 24, 120, false, ink, (byte) 0);
		gate(img, TOWN + 18, 150);
		write(img, "GATE", TOWN + 18 + 12 + 8, 150, false, ink, (byte) 0);
		here(img, TOWN + 18, 180);
		write(img, "YOU ARE HERE", TOWN + 18 + 12 + 24, 180, false, ink, (byte) 0);
		cellarKey(img, ink, mid);
	}

	private static void cellarKey(byte[] img, byte ink, int mid) {
		write(img, "STREETS", mid, 214, false, ink, (byte) 0);
		for (int x = TOWN + 20; x < W - 20; x++) {
			for (int y = 222; y < 230; y++) {
				put(img, x, y, y == 222 || y == 229 ? c(MapColor.COLOR_LIGHT_GRAY, MapColor.Brightness.NORMAL) : c(MapColor.QUARTZ, MapColor.Brightness.HIGH));
			}
		}
		write(img, "HOUSES", mid, 244, false, ink, (byte) 0);
		for (int x = mid - 10; x <= mid + 10; x++) {
			for (int y = 252; y <= 264; y++) {
				boolean edge = x == mid - 10 || x == mid + 10 || y == 252 || y == 264;
				put(img, x, y, edge ? ink : c(MapColor.COLOR_GRAY, MapColor.Brightness.NORMAL));
			}
		}
		write(img, "TREES", mid, 280, false, ink, (byte) 0);
		for (int x = mid - 10; x <= mid + 10; x++) {
			for (int y = 288; y <= 300; y++) {
				put(img, x, y, c(MapColor.PLANT, MapColor.Brightness.NORMAL));
			}
		}
	}

	// ---------------------------------------------------------------- the board

	/**
	 * The board's twelve frames (top row first, west to east), each with its own map; made the first time,
	 * found again after. Plain frames, not glowing ones: the map is lit like the street around it (by an unseen
	 * light in front of the board), not shining in the dark (a dim light in front). Boards from before (glow frames) are made anew.
	 * {@code board}: the lower west square of the board, the frames hang on its south face.
	 */
	private static List<MapId> frames(ServerLevel level, BlockPos board) {
		List<ItemFrame> found = level.getEntitiesOfClass(ItemFrame.class, new AABB(board).inflate(5), f -> f.entityTags().contains(TAG));
		List<MapId> ids = new ArrayList<>();
		lanterns(level, board);
		if (found.size() == TX * TY && found.stream().noneMatch(f -> f instanceof GlowItemFrame)) {
			found.sort((a, b) -> a.getBlockY() != b.getBlockY() ? Integer.compare(b.getBlockY(), a.getBlockY()) : Integer.compare(a.getBlockX(), b.getBlockX()));
			for (ItemFrame f : found) {
				MapId id = f.getItem().get(DataComponents.MAP_ID);
				if (id == null || level.getMapData(id) == null) {
					ids.clear();
					break;
				}
				ids.add(id);
			}
			if (ids.size() == TX * TY) {
				return ids;
			}
		}
		found.forEach(f -> f.discard());
		ids.clear();
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		for (int ty = 0; ty < TY; ty++) {
			for (int tx = 0; tx < TX; tx++) {
				MapId id = level.getFreeMapId();
				level.setMapData(id, MapItemSavedData.createFresh(board.getX(), board.getZ(), (byte) 0, false, false, level.dimension()).locked());
				ids.add(id);
				BlockPos at = board.offset(tx, TY - 1 - ty, 1);
				level.getServer().getCommands().performPrefixedCommand(console, String.format(Locale.ROOT,
					"summon minecraft:item_frame %d %d %d {Facing:3b,Fixed:1b,Invisible:1b,Tags:[\"%s\"],Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":%d}}}",
					at.getX(), at.getY(), at.getZ(), TAG, id.id()));
			}
		}
		return ids;
	}

	/** A dim, unseen light in front of the board (two light blocks): the map can just be read at night. */
	private static void lanterns(ServerLevel level, BlockPos board) {
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		for (BlockPos at : new BlockPos[]{board.offset(1, 1, 2), board.offset(TX - 2, 1, 2)}) {
			if (level.getBlockState(at).isAir()) {
				level.getServer().getCommands().performPrefixedCommand(console, String.format(Locale.ROOT,
					"setblock %d %d %d minecraft:light[level=10]", at.getX(), at.getY(), at.getZ()));
			}
		}
	}

	/** The legend sign on the west post (in the player's language). */
	private static void signs(ServerLevel level, BlockPos board) {
		BlockPos at = board.offset(-1, 0, 1);
		if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) {
			return;
		}
		SignText text = new SignText()
			.setMessage(0, Component.translatable("halloween.board.title").withStyle(ChatFormatting.BOLD))
			.setMessage(1, Component.translatable("halloween.board.cellar").withStyle(s -> s.withColor(TextColor.fromRgb(0x8B5A2B))))
			.setMessage(2, Component.translatable("halloween.board.gate").withStyle(ChatFormatting.DARK_GRAY))
			.setMessage(3, Component.translatable("halloween.board.here").withStyle(ChatFormatting.DARK_RED))
			.setHasGlowingText(false);
		sign.setText(text, true);
		sign.setChanged();
		level.sendBlockUpdated(at, level.getBlockState(at), level.getBlockState(at), 3);
	}
}
