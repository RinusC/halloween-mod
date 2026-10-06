package dev.halloween.game;

import java.util.Random;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Haddonfield, Illinois, Halloween night 1978, after the film: three streets of suburban houses (Orange
 * Grove Avenue, Lampkin Lane, Main Street) crossed by Elm Street and Hackberry Avenue, about 300 blocks
 * square. The abandoned Myers house on Lampkin Lane (where the Shape starts), the Doyle and Wallace houses
 * facing each other across Orange Grove, the Strode house, Haddonfield Elementary, Nichols Hardware, a
 * diner and a gas station on Main Street, the cemetery, the park, a church in the fields and woods all
 * round inside the town fence. Every house can be entered: doors that lock, windows to climb, a phone in
 * the kitchen, a breaker box outside, a wardrobe to hide in, things to search; half of them have an upstairs.
 */
public final class Haddonfield extends MapPlan {
	/** The town fence (barriers above it); the ground is laid out a little further for the woods outside. */
	static final int FENCE = 148;
	static final int RIM = 156;

	// Roads: E-W at these z, N-S at these x. Each is 7 wide with 2-block sidewalks on both sides.
	static final int ORANGE_GROVE = -64;
	static final int LAMPKIN = 0;
	static final int MAIN = 64;
	static final int ELM = -64;
	static final int HACKBERRY = 64;

	/** Lots along each side of a street: x ranges, west to east (0-2 west of Elm, 3-6 between, 7-9 east of Hackberry). */
	static final int[][] LOTS = {{-147, -122}, {-121, -96}, {-95, -70}, {-58, -30}, {-29, -1}, {0, 28}, {29, 57}, {70, 95}, {96, 121}, {122, 147}};

	private record Style(String wall, String corner, String roof, String roofSlab, String floor, String rug) {
	}

	private static final Style[] STYLES = {
		new Style("birch_planks", "stripped_dark_oak_log", "dark_oak_stairs", "dark_oak_slab", "oak_planks", "red_wool"),
		new Style("white_terracotta", "stripped_spruce_log", "deepslate_tile_stairs", "deepslate_tile_slab", "spruce_planks", "blue_wool"),
		new Style("bricks", "bricks", "spruce_stairs", "spruce_slab", "oak_planks", "green_wool"),
		new Style("light_blue_terracotta", "white_concrete", "stone_brick_stairs", "stone_brick_slab", "birch_planks", "brown_wool"),
		new Style("yellow_terracotta", "stripped_oak_log", "brick_stairs", "brick_slab", "dark_oak_planks", "orange_wool"),
		new Style("spruce_planks", "stripped_birch_log", "dark_oak_stairs", "dark_oak_slab", "oak_planks", "gray_wool"),
		new Style("white_concrete", "stripped_spruce_log", "spruce_stairs", "spruce_slab", "spruce_planks", "cyan_wool"),
	};
	private static final Style MYERS = new Style("light_gray_terracotta", "stripped_dark_oak_log", "deepslate_tile_stairs", "deepslate_tile_slab",
		"spruce_planks", "brown_wool");

	private final Random random = new Random(1978);

	public Haddonfield() {
		super(RIM, -4, 40);
		this.draw();
	}

	// ---------------------------------------------------------------- layout

	private void draw() {
		this.cemetery();
		this.school();
		this.park();
		this.church();
		this.roads();
		this.lots();
		this.commerce();
		this.woodsAndFields();
		this.backHedges();
		this.lamps();
		this.boundary();
		this.points();
		this.autumn();
	}

	/** One lot: x range, the street edge and which way the house faces (true: south, the street is at the lot's south side). */
	private record Lot(int x0, int x1, int front, boolean south) {
		int width() {
			return this.x1 - this.x0 + 1;
		}

		/** z of the house's front wall, 7 blocks back from the sidewalk. */
		int frontWall() {
			return this.south ? this.front - 7 : this.front + 7;
		}

		/** Further from the street by {@code v} blocks. */
		int z(int v) {
			return this.south ? this.front - v : this.front + v;
		}

		int cx() {
			return (this.x0 + this.x1) / 2;
		}
	}

	/** The six rows of lots: north/south side of Orange Grove, Lampkin Lane and Main Street. */
	private static Lot lot(char row, int index) {
		int[] xs = LOTS[index];
		return switch (row) {
			case 'A' -> new Lot(xs[0], xs[1], ORANGE_GROVE - 6, true);
			case 'B' -> new Lot(xs[0], xs[1], ORANGE_GROVE + 6, false);
			case 'C' -> new Lot(xs[0], xs[1], LAMPKIN - 6, true);
			case 'D' -> new Lot(xs[0], xs[1], LAMPKIN + 6, false);
			case 'E' -> new Lot(xs[0], xs[1], MAIN - 6, true);
			default -> new Lot(xs[0], xs[1], MAIN + 6, false);
		};
	}

	private void lots() {
		// Row A, north side of Orange Grove (cemetery and school take the west and middle).
		this.house(lot('A', 7), 2, true, STYLES[1], "Wallace", false);
		this.randomHouse(lot('A', 8));
		this.randomHouse(lot('A', 9));
		// Row B, south side of Orange Grove.
		this.randomHouse(lot('B', 0));
		this.yardTrees(lot('B', 1));
		this.randomHouse(lot('B', 2));
		this.randomHouse(lot('B', 3));
		this.randomHouse(lot('B', 4));
		this.yardTrees(lot('B', 5));
		this.randomHouse(lot('B', 6));
		this.house(lot('B', 7), 2, true, STYLES[0], "Doyle", false);
		this.randomHouse(lot('B', 8));
		this.randomHouse(lot('B', 9));
		// Row C, north side of Lampkin Lane: the Myers house.
		this.randomHouse(lot('C', 0));
		this.house(lot('C', 1), 2, true, MYERS, "Myers", true);
		this.yardTrees(lot('C', 2));
		this.randomHouse(lot('C', 3));
		this.randomHouse(lot('C', 4));
		this.house(lot('C', 5), 2, true, STYLES[3], "Strode", false);
		this.randomHouse(lot('C', 6));
		this.yardTrees(lot('C', 7));
		this.randomHouse(lot('C', 8));
		this.randomHouse(lot('C', 9));
		// Row D, south side of Lampkin Lane.
		this.randomHouse(lot('D', 0));
		this.yardTrees(lot('D', 1));
		this.randomHouse(lot('D', 2));
		this.yardTrees(lot('D', 3));
		this.randomHouse(lot('D', 4));
		this.randomHouse(lot('D', 5));
		this.randomHouse(lot('D', 6));
		this.yardTrees(lot('D', 7));
		this.randomHouse(lot('D', 8));
		this.randomHouse(lot('D', 9));
		// Row E, north side of Main Street (shops in the middle, see commerce()).
		this.randomHouse(lot('E', 0));
		this.randomHouse(lot('E', 1));
		this.yardTrees(lot('E', 2));
		this.randomHouse(lot('E', 7));
		this.randomHouse(lot('E', 8));
		this.yardTrees(lot('E', 9));
		// Row F, south side of Main Street (the park takes the west).
		this.randomHouse(lot('F', 3));
		this.yardTrees(lot('F', 4));
		this.randomHouse(lot('F', 5));
		this.randomHouse(lot('F', 6));
		this.randomHouse(lot('F', 7));
		this.randomHouse(lot('F', 8));
		this.yardTrees(lot('F', 9));
	}

	private void randomHouse(Lot lot) {
		int w = lot.width() >= 28 ? 13 + 2 * this.random.nextInt(2) : 11 + 2 * this.random.nextInt(2);
		this.house(lot, w == 15 ? 2 : this.random.nextInt(2) + 1, false, STYLES[this.random.nextInt(STYLES.length)], null, false, w);
	}

	private void house(Lot lot, int storeys, boolean big, Style style, String name, boolean abandoned) {
		this.house(lot, storeys, big, style, name, abandoned, 13);
	}

	// ---------------------------------------------------------------- houses

	/** A house's own frame: u along +x from its west wall, v from the front wall to the back; y 0 = ground floor. */
	private final class Frame {
		final int x0;
		final Lot lot;

		Frame(int x0, Lot lot) {
			this.x0 = x0;
			this.lot = lot;
		}

		int x(int u) {
			return this.x0 + u;
		}

		int z(int v) {
			return this.lot.south ? this.lot.frontWall() - v : this.lot.frontWall() + v;
		}

		Direction front() {
			return this.lot.south ? Direction.SOUTH : Direction.NORTH;
		}

		Direction back() {
			return this.front().getOpposite();
		}

		void set(int u, int y, int v, BlockState s) {
			Haddonfield.this.set(this.x(u), y, this.z(v), s);
		}

		void fill(int u0, int y0, int v0, int u1, int y1, int v1, BlockState s) {
			for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++) {
				for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++) {
					for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
						this.set(u, y, v, s);
					}
				}
			}
		}

		void door(int u, int y, int v, Direction facing) {
			Haddonfield.this.door(this.x(u), y, this.z(v), facing, "oak_door");
		}

		void locker(int u, int v, Direction facing) {
			Haddonfield.this.locker(this.x(u), 0, this.z(v), facing);
		}

		void point(MapPoints.Type type, int u, int y, int v) {
			Haddonfield.this.point(type, this.x(u), y, this.z(v));
		}
	}

	/**
	 * A family house on {@code lot}: ground floor with the living room at the front and the kitchen at the
	 * back, an upstairs with two bedrooms if {@code storeys} is 2, a pitched roof, a porch with pumpkins.
	 */
	private void house(Lot lot, int storeys, boolean big, Style s, String name, boolean abandoned, int w) {
		int d = 12;
		Frame f = new Frame(lot.cx() - w / 2, lot);
		boolean two = storeys == 2;
		int wallTop = two ? 8 : 3;
		int top = two ? 9 : 4;
		int mid = d / 2;
		BlockState air = AIR;
		BlockState wall = b(s.wall());
		BlockState floor = b(s.floor());
		BlockState pane = b("glass_pane");
		BlockState broken = dev.halloween.HwBlocks.BROKEN_GLASS_PANE.defaultBlockState();

		// Foundation, floor, shell and corners.
		f.fill(-1, -1, -1, w, -1, d, b("stone_bricks"));
		f.fill(1, -1, 1, w - 2, -1, d - 2, floor);
		f.fill(0, 0, 0, w - 1, wallTop, d - 1, wall);
		f.fill(1, 0, 1, w - 2, wallTop, d - 2, air);
		for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
			f.fill(c[0], 0, c[1], c[0], wallTop, c[1], b(s.corner()));
		}
		// Ceiling (upstairs floor) and the attic floor under the roof.
		f.fill(1, 4, 1, w - 2, 4, d - 2, floor);
		f.fill(0, top, 0, w - 1, top, d - 1, wall);
		f.fill(1, top, 1, w - 2, top, d - 2, floor);
		// A rug in the living room (part of the floor: nothing to trip over).
		f.fill(w / 2 - 1, -1, 2, w / 2 + 1, -1, 4, b(s.rug()));

		// Wall between the living room and the kitchen, doorway on the east side.
		f.fill(1, 0, mid, w - 2, 3, mid, wall);
		f.fill(w - 4, 0, mid, w - 3, 2, mid, air);
		// Doors: front in the middle, back near the east corner.
		f.door(w / 2, 0, 0, f.back());
		f.door(w - 3, 0, d - 1, f.front());
		// Ground-floor windows: two each side of the front door, two on each side wall. Sills stay solid.
		for (int u : new int[]{2, 3, w - 4, w - 3}) {
			f.fill(u, 1, 0, u, 2, 0, abandoned && u % 2 == 0 ? broken : pane);
		}
		for (int v : new int[]{2, 3, d - 4, d - 3}) {
			f.fill(0, 1, v, 0, 2, v, abandoned && v == 3 ? broken : pane);
			f.fill(w - 1, 1, v, w - 1, 2, v, pane);
		}
		// Breaker box outside on the east wall, level with the dividing wall.
		if (!abandoned) {
			this.breaker(f.x(w - 1), 1, f.z(mid), Direction.EAST);
		}

		// Living room: a sofa against the dividing wall facing the front, a coffee table, a bookcase, a chest.
		for (int u = 4; u <= 6; u++) {
			f.set(u, 0, mid - 1, stairs("dark_oak_stairs", f.back()));
		}
		f.set(5, 0, mid - 3, slab("dark_oak_slab", SlabType.BOTTOM));
		f.set(w - 2, 0, mid - 1, b("bookshelf"));
		f.set(w - 2, 1, mid - 1, b("bookshelf"));
		f.set(w - 2, 0, 1, facing("chest", Direction.WEST));
		f.point(MapPoints.Type.NPC, w / 2, 0, 2);

		// Kitchen: counters along the back wall with the phone, a barrel, a table with two chairs, the wardrobe.
		for (int u = 2; u <= w - 6; u++) {
			f.set(u, 0, d - 2, b(abandoned ? "cobblestone" : "polished_andesite"));
		}
		f.set(1, 0, d - 2, b("barrel"));
		f.set(2, 1, d - 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 2, 1, d - 2);
		int tableU = w / 2 - 1;
		f.set(tableU, 0, mid + 3, b("dark_oak_fence"));
		f.set(tableU - 1, 0, mid + 3, stairs("oak_stairs", Direction.WEST));
		f.set(tableU + 1, 0, mid + 3, stairs("oak_stairs", Direction.EAST));
		f.locker(1, mid + 1, Direction.EAST);

		// Lights in the ceilings (none in the abandoned house).
		if (!abandoned) {
			this.bulb(f.x(w / 2), 4, f.z(mid / 2));
			this.bulb(f.x(w / 2), 4, f.z(mid + (d - 1 - mid) / 2));
		}

		if (two) {
			// Stairs along the west wall, climbing towards the back, up to the landing above the dividing wall.
			for (int k = 0; k <= 4; k++) {
				f.set(1, k, mid - 4 + k, stairs(s.floor().replace("_planks", "_stairs"), f.back()));
				if (k + 1 <= 3) {
					f.fill(1, k + 1, mid - 4 + k, 1, 3, mid - 4 + k, air);
				}
			}
			f.fill(1, 4, mid - 4, 1, 4, mid - 1, air);
			f.fill(2, 5, mid - 4, 2, 5, mid - 1, b("dark_oak_fence"));
			// Upstairs: a wall between the two bedrooms, one step behind the landing.
			f.fill(1, 5, mid + 1, w - 2, 8, mid + 1, wall);
			f.fill(w / 2, 5, mid + 1, w / 2 + 1, 6, mid + 1, air);
			for (int u : new int[]{2, 3, w - 4, w - 3}) {
				f.fill(u, 6, 0, u, 7, 0, abandoned ? broken : pane);
			}
			for (int v : new int[]{2, 3, d - 4, d - 3}) {
				f.fill(0, 6, v, 0, 7, v, pane);
				f.fill(w - 1, 6, v, w - 1, 7, v, pane);
			}
			// Beds (made of wool: no sleeping through the night), a chest and a bookcase.
			f.set(w - 2, 5, mid - 1, b("red_wool"));
			f.set(w - 2, 5, mid, b("white_wool"));
			f.set(w - 2, 5, d - 3, b("blue_wool"));
			f.set(w - 2, 5, d - 2, b("white_wool"));
			f.set(1, 5, d - 2, facing("chest", Direction.EAST));
			f.set(1, 5, mid + 2, b("bookshelf"));
			if (!abandoned) {
				this.bulb(f.x(w / 2), 9, f.z(3));
				this.bulb(f.x(w / 2), 9, f.z(d - 3));
			}
		}
		this.roof(f, w, d, top, s, wall);

		if (abandoned) {
			// Years of nobody: cobwebs up in the corners, dirt on the floor, the shutters hanging.
			for (int[] c : new int[][]{{1, 1}, {w - 2, 1}, {1, d - 2}, {w - 2, mid - 1}}) {
				f.set(c[0], 3, c[1], b("cobweb"));
				if (two) {
					f.set(c[0], 8, c[1], b("cobweb"));
				}
			}
			f.fill(3, -1, mid + 1, 5, -1, mid + 3, b("coarse_dirt"));
			f.fill(w / 2 - 1, -1, 2, w / 2 + 1, -1, 4, b("rooted_dirt"));
		}

		// Porch: pumpkins either side of the door, a path to the sidewalk.
		boolean lit = !abandoned && this.random.nextInt(3) == 0;
		f.set(w / 2 - 1, 0, -1, facing(lit ? "jack_o_lantern" : "carved_pumpkin", f.front()));
		f.set(w / 2 + 1, 0, -1, facing("carved_pumpkin", f.front()));
		for (int v = -1; v >= -7; v--) {
			f.set(w / 2, -1, v, b("dirt_path"));
		}
		if (name != null) {
			int rot = lot.south ? 0 : 8;
			this.sign(f.x(w / 2 + 2), 0, f.z(-5), rot, "", name, "");
		}
		// A tree in the front yard on one side.
		int tu = this.random.nextBoolean() ? -3 : w + 2;
		if (f.x(tu) > lot.x0 + 1 && f.x(tu) < lot.x1 - 1) {
			this.tree(f.x(tu), f.z(-4), this.random.nextInt(3));
		}
	}

	/** A gable roof over the house, ridge along x, overhanging one block; the gable ends are wall. */
	private void roof(Frame f, int w, int d, int top, Style s, BlockState wall) {
		for (int k = 0; ; k++) {
			int vf = -1 + k;
			int vb = d - k;
			int y = top + k;
			if (vf > vb) {
				break;
			}
			for (int u = -1; u <= w; u++) {
				if (vf == vb) {
					f.set(u, y, vf, slab(s.roofSlab(), SlabType.BOTTOM));
				} else {
					f.set(u, y, vf, stairs(s.roof(), f.back()));
					f.set(u, y, vb, stairs(s.roof(), f.front()));
				}
			}
			if (k >= 1) {
				for (int v = vf + 1; v <= vb - 1; v++) {
					f.set(0, y, v, wall);
					f.set(w - 1, y, v, wall);
				}
			}
		}
	}

	/** An empty lot: a couple of trees and long grass. */
	private void yardTrees(Lot lot) {
		for (int i = 0; i < 3; i++) {
			int x = lot.x0 + 3 + this.random.nextInt(Math.max(1, lot.width() - 6));
			int z = lot.z(6 + this.random.nextInt(16));
			if (this.freeArea(x - 2, z - 2, x + 2, z + 2)) {
				this.tree(x, z, this.random.nextInt(3));
			}
		}
	}

	// ---------------------------------------------------------------- shops on Main Street

	private void commerce() {
		this.hardware(lot('E', 3).x0 + 2, lot('E', 4).x1 - 2);
		this.diner(lot('E', 5));
		this.gasStation(lot('E', 6));
	}

	/** A plain flat-roofed building on the north side of Main Street; returns nothing, draws the shell only. */
	private void shopShell(int x0, int x1, int zFront, int zBack, String wallId, String floorId) {
		BlockState wall = b(wallId);
		this.fill(x0, -1, zBack, x1, -1, zFront, b("stone_bricks"));
		this.fill(x0 + 1, -1, zBack + 1, x1 - 1, -1, zFront - 1, b(floorId));
		this.fill(x0, 0, zBack, x1, 4, zFront, wall);
		this.fill(x0 + 1, 0, zBack + 1, x1 - 1, 3, zFront - 1, AIR);
		this.fill(x0, 5, zBack, x1, 5, zFront, slab("smooth_stone_slab", SlabType.BOTTOM));
		for (int x = x0; x <= x1; x++) {
			this.set(x, 5, zBack, b(wallId));
			this.set(x, 5, zFront, b(wallId));
		}
	}

	/** Nichols Hardware: shelves in aisles, the counter, a storeroom at the back with the gun case. */
	private void hardware(int x0, int x1) {
		int zFront = MAIN - 12;
		int zBack = zFront - 17;
		this.shopShell(x0, x1, zFront, zBack, "bricks", "smooth_stone");
		this.door(x0 + 6, 0, zFront, Direction.NORTH, "spruce_door");
		this.door(x1 - 6, 0, zFront, Direction.NORTH, "spruce_door");
		// Display windows along the front.
		for (int x = x0 + 2; x <= x1 - 2; x++) {
			if (Math.abs(x - (x0 + 6)) > 1 && Math.abs(x - (x1 - 6)) > 1) {
				this.fill(x, 1, zFront, x, 2, zFront, b("glass_pane"));
			}
		}
		// Shelves: rows of spruce shelving with barrels (things to search) at the ends.
		for (int row = 0; row < 3; row++) {
			int z = zFront - 5 - row * 3;
			for (int x = x0 + 4; x <= x1 - 4; x++) {
				if (Math.abs(x - (x0 + x1) / 2) <= 1) {
					continue;
				}
				this.set(x, 0, z, b("spruce_planks"));
				this.set(x, 1, z, slab("spruce_slab", SlabType.BOTTOM));
			}
			this.set(x0 + 3, 0, z, b("barrel"));
			this.set(x1 - 3, 0, z, b("barrel"));
		}
		// Counter by the door with the phone.
		this.fill(x0 + 2, 0, zFront - 3, x0 + 4, 0, zFront - 3, b("dark_oak_planks"));
		this.set(x0 + 3, 1, zFront - 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.point(MapPoints.Type.PHONE, x0 + 3, 1, zFront - 3);
		// Storeroom behind a wall, with its own door to the shop.
		int zStore = zBack + 5;
		this.fill(x0 + 1, 0, zStore, x1 - 1, 3, zStore, b("bricks"));
		this.fill((x0 + x1) / 2, 0, zStore, (x0 + x1) / 2 + 1, 2, zStore, AIR);
		this.set(x0 + 2, 0, zBack + 1, b("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		this.set(x1 - 2, 0, zBack + 1, b("barrel"));
		this.point(MapPoints.Type.GUN_CASE, x0 + 5, 0, zBack + 1);
		this.door(x1 - 3, 0, zBack, Direction.SOUTH, "spruce_door");
		this.locker(x0 + 1, 0, zBack + 3, Direction.EAST);
		// Lights and the breaker (outside, west wall): bulbs within its reach, a second breaker for the east half.
		int cx = (x0 + x1) / 2;
		for (int x = x0 + 6; x <= x1 - 6; x += 9) {
			this.bulb(x, 4, zFront - 8);
			this.bulb(x, 4, zBack + 3);
		}
		this.breaker(x0, 1, zFront - 8, Direction.WEST);
		this.breaker(x1, 1, zFront - 8, Direction.EAST);
		this.breaker(cx, 1, zBack, Direction.NORTH);
		this.sign(x0 + 3, 0, zFront + 3, 0, "", "NICHOLS", "HARDWARE", "");
		this.point(MapPoints.Type.NPC, cx, 0, zFront - 2);
	}

	/** The diner: booths by the windows, a counter with stools, a kitchen at the back. */
	private void diner(Lot lot) {
		int x0 = lot.x0 + 2;
		int x1 = lot.x1 - 2;
		int zFront = MAIN - 12;
		int zBack = zFront - 13;
		this.shopShell(x0, x1, zFront, zBack, "white_terracotta", "polished_diorite");
		this.fill(x0, 4, zFront, x1, 4, zFront, b("red_terracotta"));
		this.door((x0 + x1) / 2, 0, zFront, Direction.NORTH, "birch_door");
		for (int x = x0 + 2; x <= x1 - 2; x++) {
			if (Math.abs(x - (x0 + x1) / 2) > 1) {
				this.fill(x, 1, zFront, x, 2, zFront, b("glass_pane"));
			}
		}
		// Booths: seat, table, seat, along the front windows (one step in from the glass).
		for (int x = x0 + 2; x <= x1 - 4; x += 4) {
			if (Math.abs(x - (x0 + x1) / 2) <= 2 || Math.abs(x + 2 - (x0 + x1) / 2) <= 1) {
				continue;
			}
			this.set(x, 0, zFront - 2, stairs("red_nether_brick_stairs", Direction.WEST));
			this.set(x + 1, 0, zFront - 2, slab("polished_andesite_slab", SlabType.TOP));
			this.set(x + 2, 0, zFront - 2, stairs("red_nether_brick_stairs", Direction.EAST));
		}
		// Counter and stools.
		this.fill(x0 + 2, 0, zBack + 4, x1 - 4, 0, zBack + 4, b("quartz_block"));
		for (int x = x0 + 2; x <= x1 - 4; x += 2) {
			this.set(x, 0, zBack + 6, b("dark_oak_fence"));
		}
		this.set(x0 + 3, 1, zBack + 4, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.point(MapPoints.Type.PHONE, x0 + 3, 1, zBack + 4);
		this.set(x0 + 1, 0, zBack + 1, b("barrel"));
		this.set(x1 - 1, 0, zBack + 1, b("chest").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		this.door(x1 - 2, 0, zBack, Direction.SOUTH, "birch_door");
		this.bulb(x0 + 6, 4, zFront - 6);
		this.bulb(x1 - 6, 4, zFront - 6);
		this.breaker(x0, 1, zFront - 6, Direction.WEST);
		this.breaker(x1, 1, zFront - 6, Direction.EAST);
		this.sign(x0 + 2, 0, zFront + 3, 0, "", "DINER", "open late", "");
		// Parking on the street side, where a car can be fixed.
		this.point(MapPoints.Type.CAR, (x0 + x1) / 2, 0, zFront + 3);
	}

	/** The gas station: pumps under a canopy by the road, a little shop at the back. */
	private void gasStation(Lot lot) {
		int x0 = lot.x0 + 1;
		int x1 = lot.x1 - 1;
		int zRoad = MAIN - 6;
		this.fill(x0, -1, zRoad - 15, x1, -1, zRoad, b("gray_concrete"));
		// Canopy on four posts.
		for (int[] p : new int[][]{{x0 + 3, zRoad - 3}, {x1 - 3, zRoad - 3}, {x0 + 3, zRoad - 9}, {x1 - 3, zRoad - 9}}) {
			this.fill(p[0], 0, p[1], p[0], 3, p[1], b("white_concrete"));
		}
		this.fill(x0 + 2, 4, zRoad - 10, x1 - 2, 4, zRoad - 2, slab("smooth_quartz_slab", SlabType.BOTTOM));
		this.fill(x0 + 2, 4, zRoad - 10, x1 - 2, 4, zRoad - 10, b("red_concrete"));
		this.fill(x0 + 2, 4, zRoad - 2, x1 - 2, 4, zRoad - 2, b("red_concrete"));
		// Pump islands.
		for (int x : new int[]{x0 + 9, x1 - 9}) {
			this.set(x, 0, zRoad - 6, b("polished_andesite"));
			this.set(x, 1, zRoad - 6, b("red_concrete"));
		}
		// Lights under the canopy: the forecourt is bright.
		this.set((x0 + x1) / 2, 3, zRoad - 6, b("lantern").setValue(BlockStateProperties.HANGING, true));
		this.set((x0 + x1) / 2, 4, zRoad - 6, b("white_concrete"));
		// The shop.
		int sx0 = x0 + 4;
		int sx1 = x1 - 4;
		int zf = zRoad - 13;
		int zb = zRoad - 23;
		this.shopShell(sx0, sx1, zf, zb, "light_gray_concrete", "smooth_stone");
		this.door((sx0 + sx1) / 2, 0, zf, Direction.NORTH, "spruce_door");
		for (int x = sx0 + 2; x <= sx1 - 2; x++) {
			if (Math.abs(x - (sx0 + sx1) / 2) > 1) {
				this.fill(x, 1, zf, x, 2, zf, b("glass_pane"));
			}
		}
		this.fill(sx0 + 2, 0, zb + 3, sx0 + 5, 0, zb + 3, b("spruce_planks"));
		this.set(sx0 + 3, 1, zb + 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.point(MapPoints.Type.PHONE, sx0 + 3, 1, zb + 3);
		this.set(sx1 - 1, 0, zb + 1, b("barrel"));
		this.set(sx1 - 1, 0, zb + 2, b("barrel"));
		this.locker(sx0 + 1, 0, zb + 1, Direction.SOUTH);
		this.bulb((sx0 + sx1) / 2, 4, zb + 4);
		this.breaker(sx1, 1, zb + 4, Direction.EAST);
		this.sign(x0 + 1, 0, zRoad - 1, 0, "", "GAS", "29.9c", "");
		this.point(MapPoints.Type.CAR, x1 - 6, 0, zRoad - 12);
	}

	// ---------------------------------------------------------------- the big places

	/** Haddonfield Elementary: classrooms either side of a corridor lined with wardrobes, a playground, a car park. */
	private void school() {
		int x0 = -42;
		int x1 = 42;
		int zFront = ORANGE_GROVE - 22;
		int zBack = zFront - 34;
		int zc = (zFront + zBack) / 2;
		BlockState wall = b("bricks");
		this.fill(x0, -1, zBack, x1, -1, zFront, b("stone_bricks"));
		this.fill(x0 + 1, -1, zBack + 1, x1 - 1, -1, zFront - 1, b("polished_andesite"));
		this.fill(x0, 0, zBack, x1, 4, zFront, wall);
		this.fill(x0 + 1, 0, zBack + 1, x1 - 1, 3, zFront - 1, AIR);
		this.fill(x0, 5, zBack, x1, 5, zFront, b("stone_bricks"));
		// Corridor along the middle, classroom walls either side with doorways.
		this.fill(x0 + 1, 0, zc - 3, x1 - 1, 3, zc - 3, wall);
		this.fill(x0 + 1, 0, zc + 3, x1 - 1, 3, zc + 3, wall);
		this.fill(x0 + 1, -1, zc - 2, x1 - 1, -1, zc + 2, b("white_terracotta"));
		int rooms = 6;
		int roomW = (x1 - x0) / rooms;
		for (int r = 0; r <= rooms; r++) {
			int x = x0 + r * roomW;
			if (r > 0 && r < rooms) {
				this.fill(x, 0, zBack + 1, x, 3, zc - 3, wall);
				this.fill(x, 0, zc + 3, x, 3, zFront - 1, wall);
			}
			if (r < rooms) {
				int dx = x + roomW / 2;
				// Doorways from the corridor.
				this.fill(dx, 0, zc - 3, dx + 1, 2, zc - 3, AIR);
				this.fill(dx, 0, zc + 3, dx + 1, 2, zc + 3, AIR);
				// Desks in rows, the teacher's desk with a chest, a blackboard.
				for (int[] side : new int[][]{{zBack + 2, zc - 5}, {zc + 5, zFront - 2}}) {
					for (int z = side[0] + 2; z <= side[1] - 1; z += 3) {
						for (int xx = x + 2; xx <= x + roomW - 3; xx += 3) {
							this.set(xx, 0, z, slab("oak_slab", SlabType.TOP));
						}
					}
				}
				this.set(x + 2, 0, zBack + 2, facing("chest", Direction.SOUTH));
				this.set(x + 2, 0, zFront - 2, facing("chest", Direction.NORTH));
				// Blackboards on the corridor wall, clear of the doorway.
				this.fill(x + 2, 1, zc - 4, x + 5, 2, zc - 4, b("black_concrete"));
				this.fill(x + 2, 1, zc + 4, x + 5, 2, zc + 4, b("black_concrete"));
				// Windows in the outer walls.
				for (int xx = x + 3; xx <= x + roomW - 3; xx += 4) {
					this.fill(xx, 1, zBack, xx + 1, 2, zBack, b("glass_pane"));
					this.fill(xx, 1, zFront, xx + 1, 2, zFront, b("glass_pane"));
				}
				this.bulb(x + roomW / 2, 4, zBack + 6);
				this.bulb(x + roomW / 2, 4, zFront - 6);
				this.bulb(x + roomW / 2, 4, zc);
			}
		}
		// Wardrobes along the corridor walls.
		for (int x : new int[]{-38, -27, -13, 1, 15, 29}) {
			this.locker(x, 0, zc - 2, Direction.SOUTH);
		}
		for (int x : new int[]{-31, -17, -3, 11, 25, 39}) {
			this.locker(x, 0, zc + 2, Direction.NORTH);
		}
		// Breakers along the corridor (each reaches 14 blocks).
		for (int x = x0 + 14; x <= x1 - 10; x += 26) {
			this.breaker(x, 1, zc + 3, Direction.NORTH);
		}
		// Main entrance in the middle of the front, side doors at both ends of the corridor.
		this.fill(-2, 0, zc + 3, 1, 2, zFront, AIR);
		this.fill(-3, 0, zc + 4, -3, 3, zFront - 1, wall);
		this.fill(2, 0, zc + 4, 2, 3, zFront - 1, wall);
		this.fill(-2, -1, zc + 3, 1, -1, zFront, b("white_terracotta"));
		this.door(-1, 0, zFront, Direction.NORTH, "oak_door");
		this.door(0, 0, zFront, Direction.NORTH, "oak_door");
		this.fill(-2, 0, zFront, -2, 3, zFront, wall);
		this.fill(1, 0, zFront, 1, 3, zFront, wall);
		this.door(x0, 0, zc, Direction.EAST, "oak_door");
		this.door(x1, 0, zc, Direction.WEST, "oak_door");
		// The office by the entrance: phone; the janitor's room: the gun case.
		this.fill(4, 0, zc + 5, 6, 0, zc + 5, b("dark_oak_planks"));
		this.set(5, 1, zc + 5, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.point(MapPoints.Type.PHONE, 5, 1, zc + 5);
		this.point(MapPoints.Type.GUN_CASE, x1 - 3, 0, zBack + 3);
		this.set(x1 - 2, 0, zBack + 2, b("barrel"));
		this.set(x1 - 2, 0, zBack + 4, b("barrel"));
		this.point(MapPoints.Type.NPC, x1 - 6, 0, zBack + 3);
		this.sign(-5, 0, zFront + 4, 0, "HADDONFIELD", "ELEMENTARY", "SCHOOL", "");
		// Flagpole, playground (swings, a slide, a climbing frame) and the car park east of the school.
		this.fill(0, 0, zFront + 8, 0, 8, zFront + 8, b("iron_bars"));
		for (int x = -30; x <= -20; x += 5) {
			this.fill(x, 0, zFront + 8, x, 3, zFront + 8, b("dark_oak_fence"));
			this.fill(x + 3, 0, zFront + 8, x + 3, 3, zFront + 8, b("dark_oak_fence"));
			this.fill(x, 4, zFront + 8, x + 3, 4, zFront + 8, b("dark_oak_planks"));
			this.fill(x + 1, 2, zFront + 8, x + 1, 3, zFront + 8, b("iron_chain"));
		}
		for (int k = 0; k < 4; k++) {
			this.set(-12 + k, k, zFront + 10, stairs("smooth_sandstone_stairs", Direction.EAST));
		}
		this.fill(-8, 0, zFront + 10, -8, 3, zFront + 10, b("smooth_sandstone"));
		this.fill(x1 + 3, -1, zFront - 20, x1 + 15, -1, zFront + 6, b("gray_concrete"));
		for (int z = zFront - 18; z <= zFront + 4; z += 4) {
			this.fill(x1 + 3, -1, z, x1 + 7, -1, z, b("white_concrete"));
		}
		this.point(MapPoints.Type.CAR, x1 + 11, 0, zFront - 8);
		this.point(MapPoints.Type.SPAWN_CIVILIAN, -10, 0, zFront + 14);
	}

	/** Haddonfield Memorial Cemetery: iron railings, rows of headstones, dead trees and a stone crypt. */
	private void cemetery() {
		int x0 = -146;
		int x1 = -71;
		int z0 = -146;
		int z1 = ORANGE_GROVE - 7;
		for (int x = x0; x <= x1; x++) {
			this.set(x, 0, z0, b("iron_bars"));
			this.set(x, 0, z1, b("iron_bars"));
		}
		for (int z = z0; z <= z1; z++) {
			this.set(x0, 0, z, b("iron_bars"));
			this.set(x1, 0, z, b("iron_bars"));
		}
		// Gateway from Orange Grove, and a gap to Elm Street.
		this.fill(-112, 0, z1, -108, 0, z1, AIR);
		this.fill(x1, 0, -100, x1, 0, -96, AIR);
		for (int z = z1; z >= z1 - 30; z--) {
			this.set(-110, -1, z, b("gravel"));
			this.set(-111, -1, z, b("gravel"));
		}
		for (int x = x0 + 4; x <= x1 - 4; x += 3) {
			for (int z = z0 + 6; z <= z1 - 6; z += 4) {
				if (Math.abs(x + 110) <= 2 || this.random.nextInt(6) == 0) {
					continue;
				}
				this.set(x, 0, z, this.random.nextInt(3) == 0 ? b("mossy_stone_brick_wall") : b("andesite_wall"));
			}
		}
		// The crypt.
		int cx = -125;
		int cz = -125;
		this.fill(cx - 4, -1, cz - 5, cx + 4, -1, cz + 5, b("stone_bricks"));
		this.fill(cx - 4, 0, cz - 5, cx + 4, 4, cz + 5, b("mossy_stone_bricks"));
		this.fill(cx - 3, 0, cz - 4, cx + 3, 3, cz + 4, AIR);
		this.fill(cx - 4, 5, cz - 5, cx + 4, 5, cz + 5, slab("stone_brick_slab", SlabType.BOTTOM));
		this.door(cx, 0, cz + 5, Direction.NORTH, "spruce_door");
		this.set(cx - 1, 1, cz + 6, b("soul_lantern"));
		this.set(cx - 1, 0, cz + 6, b("stone_bricks"));
		this.set(cx + 1, 1, cz + 6, b("soul_lantern"));
		this.set(cx + 1, 0, cz + 6, b("stone_bricks"));
		this.set(cx, 0, cz - 3, facing("chest", Direction.SOUTH));
		this.fill(cx - 2, 0, cz - 1, cx + 2, 0, cz - 1, b("polished_andesite"));
		for (int i = 0; i < 9; i++) {
			int x = x0 + 6 + this.random.nextInt(x1 - x0 - 12);
			int z = z0 + 6 + this.random.nextInt(z1 - z0 - 12);
			if (this.freeArea(x - 1, z - 1, x + 1, z + 1)) {
				this.deadTree(x, z);
			}
		}
		this.sign(-114, 0, z1 + 2, 0, "HADDONFIELD", "MEMORIAL", "CEMETERY", "");
	}

	/** The park: lawns, paths, trees, benches and a gazebo, a tool shed. */
	private void park() {
		int x0 = -147;
		int x1 = -70;
		int z0 = MAIN + 6;
		int z1 = 147;
		int cx = (x0 + x1) / 2;
		int cz = (z0 + z1) / 2;
		for (int x = x0; x <= x1; x++) {
			this.set(x, -1, cz, b("dirt_path"));
			this.set(x, -1, cz + 1, b("dirt_path"));
		}
		for (int z = z0; z <= z1; z++) {
			this.set(cx, -1, z, b("dirt_path"));
			this.set(cx + 1, -1, z, b("dirt_path"));
		}
		// Gazebo in the middle.
		this.fill(cx - 4, -1, cz - 4, cx + 5, -1, cz + 5, b("spruce_planks"));
		for (int[] p : new int[][]{{cx - 4, cz - 4}, {cx + 5, cz - 4}, {cx - 4, cz + 5}, {cx + 5, cz + 5}}) {
			this.fill(p[0], 0, p[1], p[0], 3, p[1], b("stripped_spruce_log"));
		}
		this.fill(cx - 5, 4, cz - 5, cx + 6, 4, cz + 6, slab("spruce_slab", SlabType.BOTTOM));
		this.fill(cx - 3, 5, cz - 3, cx + 4, 5, cz + 4, slab("spruce_slab", SlabType.BOTTOM));
		this.set(cx, 3, cz, b("lantern").setValue(BlockStateProperties.HANGING, true));
		this.set(cx, 4, cz, b("spruce_planks"));
		for (int x = cx - 3; x <= cx + 4; x += 7) {
			this.set(x, 0, cz - 1, stairs("spruce_stairs", x < cx ? Direction.WEST : Direction.EAST));
			this.set(x, 0, cz + 2, stairs("spruce_stairs", x < cx ? Direction.WEST : Direction.EAST));
		}
		// Benches along the paths.
		for (int x = x0 + 8; x <= x1 - 8; x += 14) {
			if (Math.abs(x - cx) > 6) {
				this.set(x, 0, cz - 1, stairs("oak_stairs", Direction.SOUTH));
				this.set(x + 1, 0, cz - 1, stairs("oak_stairs", Direction.SOUTH));
			}
		}
		// The groundskeeper's shed with a chest; a storm cellar behind it.
		int sx = x0 + 10;
		int sz = z1 - 16;
		this.fill(sx, -1, sz, sx + 6, -1, sz + 5, b("spruce_planks"));
		this.fill(sx, 0, sz, sx + 6, 3, sz + 5, b("spruce_planks"));
		this.fill(sx + 1, 0, sz + 1, sx + 5, 2, sz + 4, AIR);
		this.fill(sx, 4, sz, sx + 6, 4, sz + 5, slab("spruce_slab", SlabType.BOTTOM));
		this.door(sx + 3, 0, sz, Direction.SOUTH, "spruce_door");
		this.set(sx + 1, 1, sz + 2, b("glass_pane"));
		this.set(sx + 1, 2, sz + 2, b("glass_pane"));
		this.set(sx + 5, 0, sz + 4, facing("chest", Direction.WEST));
		this.set(sx + 5, 0, sz + 1, b("barrel"));
		this.point(MapPoints.Type.CELLAR, sx + 3, 0, sz + 9);
		// Trees all over the lawns.
		for (int i = 0; i < 60; i++) {
			int x = x0 + 3 + this.random.nextInt(x1 - x0 - 6);
			int z = z0 + 3 + this.random.nextInt(z1 - z0 - 6);
			if (Math.abs(x - cx) > 3 && Math.abs(z - cz) > 3 && this.freeArea(x - 2, z - 2, x + 2, z + 2)) {
				this.tree(x, z, this.random.nextInt(3));
			}
		}
		this.sign(x1 - 2, 0, z0 + 2, 8, "", "LAMPKIN", "PARK", "");
		this.point(MapPoints.Type.SPAWN_CIVILIAN, cx + 3, 0, cz + 3);
	}

	/** A white wooden church out in the fields south of Main Street. */
	private void church() {
		int x0 = -10;
		int x1 = 10;
		int z0 = 108;
		int z1 = 136;
		this.fill(x0, -1, z0, x1, -1, z1, b("stone_bricks"));
		this.fill(x0 + 1, -1, z0 + 1, x1 - 1, -1, z1 - 1, b("spruce_planks"));
		this.fill(x0, 0, z0, x1, 6, z1, b("white_terracotta"));
		this.fill(x0 + 1, 0, z0 + 1, x1 - 1, 6, z1 - 1, AIR);
		for (int k = 0; k <= (x1 - x0) / 2 + 1; k++) {
			for (int z = z0 - 1; z <= z1 + 1; z++) {
				int xa = x0 - 1 + k;
				int xb = x1 + 1 - k;
				if (xa < xb) {
					this.set(xa, 7 + k, z, stairs("deepslate_tile_stairs", Direction.EAST));
					this.set(xb, 7 + k, z, stairs("deepslate_tile_stairs", Direction.WEST));
				} else if (xa == xb) {
					this.set(xa, 7 + k, z, slab("deepslate_tile_slab", SlabType.BOTTOM));
				}
			}
			if (k >= 1) {
				for (int x = x0 - 1 + k + 1; x <= x1 + 1 - k - 1; x++) {
					this.set(x, 7 + k, z0, b("white_terracotta"));
					this.set(x, 7 + k, z1, b("white_terracotta"));
				}
			}
		}
		this.fill(x0 + 1, 7, z0 + 1, x1 - 1, 7, z1 - 1, b("spruce_planks"));
		// Steeple over the door.
		this.fill(-2, 7, z0 - 3, 2, 14, z0 + 1, b("white_terracotta"));
		this.fill(-1, 15, z0 - 2, 1, 18, z0, b("deepslate_tiles"));
		this.door(0, 0, z0, Direction.SOUTH, "dark_oak_door");
		this.fill(-1, 0, z0 - 3, 1, 1, z0 - 1, AIR);
		this.fill(-1, -1, z0 - 3, 1, -1, z0 - 1, b("stone_bricks"));
		this.door(x1, 0, z1 - 4, Direction.WEST, "dark_oak_door");
		for (int z = z0 + 4; z <= z1 - 6; z += 4) {
			this.fill(x0, 1, z, x0, 3, z, b("glass_pane"));
			this.fill(x1, 1, z, x1, 3, z, b("glass_pane"));
		}
		// Pews either side of the aisle, the altar at the back.
		for (int z = z0 + 3; z <= z1 - 8; z += 2) {
			this.fill(x0 + 2, 0, z, -3, 0, z, stairs("dark_oak_stairs", Direction.NORTH));
			this.fill(3, 0, z, x1 - 2, 0, z, stairs("dark_oak_stairs", Direction.NORTH));
		}
		this.fill(-3, 0, z1 - 4, 3, 0, z1 - 4, b("polished_diorite"));
		this.set(0, 1, z1 - 4, b("candle"));
		this.set(-4, 0, z1 - 2, facing("chest", Direction.NORTH));
		this.set(x1 - 1, 1, z1 - 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.set(x1 - 1, 0, z1 - 2, b("dark_oak_planks"));
		this.point(MapPoints.Type.PHONE, x1 - 1, 1, z1 - 2);
		this.locker(x0 + 1, 0, z1 - 2, Direction.EAST);
		this.bulb(0, 6, z0 + 8);
		this.bulb(0, 6, z1 - 8);
		this.breaker(x0, 1, (z0 + z1) / 2, Direction.WEST);
		this.point(MapPoints.Type.NPC, 0, 0, z1 - 6);
		// A gravel path up from Main Street through the empty lot.
		for (int z = MAIN + 6; z < z0 - 3; z++) {
			this.set(0, -1, z, b("gravel"));
			this.set(1, -1, z, b("gravel"));
		}
	}

	/** Woods along the fence and in the corners, and long grass in the fields. */
	private void woodsAndFields() {
		for (int i = 0; i < 900; i++) {
			int x = -RIM + this.random.nextInt(2 * RIM + 1);
			int z = -RIM + this.random.nextInt(2 * RIM + 1);
			boolean edge = Math.abs(x) > FENCE - 6 || Math.abs(z) > FENCE - 6;
			boolean ne = x > 72 && z < -98;
			boolean se = x > 72 && z > 98;
			boolean sMiddle = x > -56 && x < 56 && z > 98 && (Math.abs(x) > 14 || z > 140);
			boolean nearRoad = Math.abs(z - ORANGE_GROVE) < 9 || Math.abs(z - LAMPKIN) < 9 || Math.abs(z - MAIN) < 9
				|| Math.abs(x - ELM) < 9 || Math.abs(x - HACKBERRY) < 9;
			if ((edge || ne || se || sMiddle) && !nearRoad && Math.abs(x) != FENCE && Math.abs(z) != FENCE
				&& this.freeArea(x - 2, z - 2, x + 2, z + 2)) {
				this.tree(x, z, edge && this.random.nextBoolean() ? 2 : this.random.nextInt(3));
			}
		}
		// An old shed in the north-east woods.
		int sx = 110;
		int sz = -125;
		this.fill(sx, -1, sz, sx + 6, -1, sz + 5, b("spruce_planks"));
		this.fill(sx, 0, sz, sx + 6, 3, sz + 5, b("stripped_spruce_wood"));
		this.fill(sx + 1, 0, sz + 1, sx + 5, 2, sz + 4, AIR);
		this.fill(sx, 4, sz, sx + 6, 4, sz + 5, slab("spruce_slab", SlabType.BOTTOM));
		this.door(sx + 3, 0, sz + 5, Direction.NORTH, "spruce_door");
		this.set(sx + 1, 0, sz + 1, facing("chest", Direction.SOUTH));
		this.set(sx + 5, 0, sz + 1, b("barrel"));
		this.set(sx + 1, 1, sz + 3, b("glass_pane"));
		this.set(sx + 1, 2, sz + 3, b("glass_pane"));
	}

	// ---------------------------------------------------------------- streets

	private void roads() {
		for (int z : new int[]{ORANGE_GROVE, LAMPKIN, MAIN}) {
			this.street(true, z);
		}
		for (int x : new int[]{ELM, HACKBERRY}) {
			this.street(false, x);
		}
		// Asphalt over any sidewalk at the crossings.
		for (int z : new int[]{ORANGE_GROVE, LAMPKIN, MAIN}) {
			for (int x : new int[]{ELM, HACKBERRY}) {
				this.fill(x - 5, -1, z - 5, x + 5, -1, z + 5, b("gray_concrete"));
			}
		}
	}

	/** A street 7 wide with a dashed centre line and sidewalks, running the whole way between the fences. */
	private void street(boolean eastWest, int c) {
		for (int t = -FENCE + 1; t <= FENCE - 1; t++) {
			for (int o = -5; o <= 5; o++) {
				BlockState s = Math.abs(o) >= 4 ? b("smooth_stone") : o == 0 && Math.floorMod(t, 6) < 3 ? b("yellow_terracotta") : b("gray_concrete");
				if (eastWest) {
					this.set(t, -1, c + o, s);
					for (int y = 0; y <= 3; y++) {
						this.set(t, y, c + o, AIR);
					}
				} else {
					this.set(c + o, -1, t, s);
					for (int y = 0; y <= 3; y++) {
						this.set(c + o, y, t, AIR);
					}
				}
			}
		}
	}

	/** Street lamps every 24 blocks, alternating sides; one in five has burnt out. */
	private void lamps() {
		for (int z : new int[]{ORANGE_GROVE, LAMPKIN, MAIN}) {
			for (int x = -FENCE + 10, i = 0; x <= FENCE - 10; x += 24, i++) {
				this.lamp(x, z + (i % 2 == 0 ? -5 : 5), i % 5 == 3);
			}
		}
		for (int x : new int[]{ELM, HACKBERRY}) {
			for (int z = -FENCE + 22, i = 0; z <= FENCE - 10; z += 24, i++) {
				this.lamp(x + (i % 2 == 0 ? -5 : 5), z, i % 5 == 1);
			}
		}
	}

	private void lamp(int x, int z, boolean broken) {
		BlockState under = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, -1, z));
		if (under == null || !under.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE)) {
			return;
		}
		this.fill(x, 0, z, x, 2, z, b("dark_oak_fence"));
		this.set(x, 3, z, broken ? b("dark_oak_fence") : b("lantern"));
	}

	/** Hedges where back yards meet, with a gap at every lot boundary to slip through. */
	private void backHedges() {
		for (int z : new int[]{(ORANGE_GROVE + LAMPKIN) / 2, (LAMPKIN + MAIN) / 2}) {
			for (int x = -147; x <= 147; x++) {
				boolean gap = false;
				for (int[] lot : LOTS) {
					gap |= Math.abs(x - lot[0]) <= 1;
				}
				if (!gap && this.free(x, z)) {
					this.fill(x, 0, z, x, 1, z, leaves("oak_leaves"));
				}
			}
		}
	}

	/** The town fence: a low fence with barriers over it; outside, woods to the horizon of the map. */
	private void boundary() {
		BlockState fence = b("spruce_fence");
		BlockState barrier = b("barrier");
		for (int i = -FENCE; i <= FENCE; i++) {
			for (int[] p : new int[][]{{i, -FENCE}, {i, FENCE}, {-FENCE, i}, {FENCE, i}}) {
				this.fill(p[0], 0, p[1], p[0], 3, p[1], AIR);
				this.set(p[0], 0, p[1], fence);
				this.fill(p[0], 1, p[1], p[0], 4, p[1], barrier);
			}
		}
		for (int i = 0; i < 260; i++) {
			int x = -RIM + this.random.nextInt(2 * RIM + 1);
			int z = -RIM + this.random.nextInt(2 * RIM + 1);
			if ((Math.abs(x) > FENCE + 2 || Math.abs(z) > FENCE + 2) && this.freeArea(x - 1, z - 1, x + 1, z + 1)) {
				this.tree(x, z, 2);
			}
		}
	}

	// ---------------------------------------------------------------- nature

	/** 0: oak, 1: dark oak, 2: spruce. */
	private void tree(int x, int z, int kind) {
		int h = 5 + this.random.nextInt(3);
		String log = kind == 2 ? "spruce_log" : kind == 1 ? "dark_oak_log" : "oak_log";
		BlockState leaves = leaves(kind == 2 ? "spruce_leaves" : kind == 1 ? "dark_oak_leaves" : "oak_leaves");
		if (kind == 2) {
			for (int y = 2; y <= h + 1; y++) {
				int r = Math.max(0, (h + 2 - y) / 2);
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.abs(dx) + Math.abs(dz) <= r + 1) {
							this.setLeaf(x + dx, y, z + dz, leaves);
						}
					}
				}
			}
			this.setLeaf(x, h + 2, z, leaves);
		} else {
			for (int y = h - 2; y <= h + 1; y++) {
				int r = y >= h ? 1 : 2;
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (r == 1 || Math.abs(dx) + Math.abs(dz) < 4) {
							this.setLeaf(x + dx, y, z + dz, leaves);
						}
					}
				}
			}
		}
		this.fill(x, 0, z, x, h, z, b(log));
	}

	private void setLeaf(int x, int y, int z, BlockState leaves) {
		if (!this.blocks.containsKey(net.minecraft.core.BlockPos.asLong(x, y, z))) {
			this.set(x, y, z, leaves);
		}
	}

	private void deadTree(int x, int z) {
		this.fill(x, 0, z, x, 5, z, b("dark_oak_log"));
		this.set(x + 1, 4, z, b("dark_oak_fence"));
		this.set(x - 1, 3, z, b("dark_oak_fence"));
		this.set(x, 5, z + 1, b("dark_oak_fence"));
	}

	/** Fallen leaves and the odd pumpkin on the lawns. */
	private void autumn() {
		for (int i = 0; i < 9000; i++) {
			int x = -FENCE + 1 + this.random.nextInt(2 * FENCE - 1);
			int z = -FENCE + 1 + this.random.nextInt(2 * FENCE - 1);
			if (this.free(x, z)) {
				this.set(x, 0, z, this.random.nextInt(40) == 0 ? b("pumpkin") : b("leaf_litter")
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.from2DDataValue(this.random.nextInt(4))));
			}
		}
	}

	// ---------------------------------------------------------------- match points

	private void points() {
		this.point(MapPoints.Type.CENTER, 0, 0, 0);
		// The Shape starts at the Myers house.
		Lot myers = lot('C', 1);
		this.point(MapPoints.Type.SPAWN_SHAPE, myers.cx(), 0, myers.z(3));
		// Civilians start spread over the town; deputies come in from the east end of Lampkin Lane.
		this.point(MapPoints.Type.SPAWN_CIVILIAN, -30, 0, LAMPKIN + 5);
		this.point(MapPoints.Type.SPAWN_CIVILIAN, 40, 0, LAMPKIN - 5);
		this.point(MapPoints.Type.SPAWN_CIVILIAN, 100, 0, ORANGE_GROVE + 5);
		this.point(MapPoints.Type.SPAWN_CIVILIAN, 110, 0, MAIN + 5);
		this.point(MapPoints.Type.SPAWN_CIVILIAN, -100, 0, MAIN - 5);
		this.point(MapPoints.Type.SPAWN_DEPUTY, FENCE - 8, 0, LAMPKIN);
		// Storm cellars in back yards (plus the cemetery and the park, see there).
		Lot c3 = lot('C', 3);
		this.point(MapPoints.Type.CELLAR, c3.cx() - 1, 0, c3.z(23));
		Lot b2 = lot('B', 2);
		this.point(MapPoints.Type.CELLAR, b2.cx() - 1, 0, b2.z(22));
		Lot d8 = lot('D', 8);
		this.point(MapPoints.Type.CELLAR, d8.cx() - 1, 0, d8.z(22));
		// Police vans pull up on the cross streets.
		this.point(MapPoints.Type.POLICE, ELM, 0, (ORANGE_GROVE + LAMPKIN) / 2);
		this.point(MapPoints.Type.POLICE, HACKBERRY, 0, (LAMPKIN + MAIN) / 2);
		this.point(MapPoints.Type.POLICE, HACKBERRY, 0, ORANGE_GROVE - 30);
		// Gates out of town where the streets meet the fence.
		this.point(MapPoints.Type.GATE, -FENCE, 0, LAMPKIN);
		this.point(MapPoints.Type.GATE, FENCE, 0, ORANGE_GROVE);
		this.point(MapPoints.Type.GATE, ELM, 0, FENCE);
		this.point(MapPoints.Type.GATE, HACKBERRY, 0, -FENCE);
		// A gun case at the Strode house too (besides the hardware store and the school).
		Lot strode = lot('C', 5);
		this.point(MapPoints.Type.GUN_CASE, strode.cx() + 3, 0, strode.z(9));
	}
}
