package dev.halloween.game;

import dev.halloween.HwBlocks;
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
		// Points first: yard decorations keep clear of where the cellars go.
		this.points();
		this.cemetery();
		this.school();
		this.park();
		this.church();
		this.roads();
		// The town map boards go up before the lots, so fences and trees keep clear of them.
		this.mapBoard(-31, MAIN - 6);
		this.mapBoard(-1, LAMPKIN - 6);
		this.lots();
		this.commerce();
		this.woodsAndFields();
		this.backHedges();
		this.streetTrees();
		this.lamps();
		this.streetDecor();
		this.welcome(-FENCE + 9, LAMPKIN - 8, Direction.EAST);
		this.welcome(FENCE - 9, ORANGE_GROVE + 8, Direction.WEST);
		this.boundary();
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

	/**
	 * A house's own frame: u along +x from its west wall (or, built the other way round, from its east wall), v
	 * from the front wall to the back; y 0 = ground floor. Blocks set through it are turned to match.
	 */
	private final class Frame {
		final int x0;
		final Lot lot;
		final int w;
		/** Left for right: everything inside on the other side. */
		final boolean mirror;

		Frame(int x0, Lot lot, int w, boolean mirror) {
			this.x0 = x0;
			this.lot = lot;
			this.w = w;
			this.mirror = mirror;
		}

		int x(int u) {
			return this.x0 + (this.mirror ? this.w - 1 - u : u);
		}

		/** The u of a world x. */
		int u(int x) {
			return this.mirror ? this.w - 1 - (x - this.x0) : x - this.x0;
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
			Haddonfield.this.set(this.x(u), y, this.z(v), this.mirror ? s.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK) : s);
		}

		/** A floor block that leaves a ceiling light of the room below where it is. */
		void floor(int u, int y, int v, BlockState s) {
			BlockState there = Haddonfield.this.blocks.get(net.minecraft.core.BlockPos.asLong(this.x(u), y, this.z(v)));
			if (there == null || !(there.getBlock() instanceof net.minecraft.world.level.block.CopperBulbBlock)) {
				this.set(u, y, v, s);
			}
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

		void floorFill(int u0, int y, int v0, int u1, int v1, BlockState s) {
			for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++) {
				for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++) {
					this.floor(u, y, v, s);
				}
			}
		}

		void door(int u, int y, int v, Direction facing) {
			Haddonfield.this.door(this.x(u), y, this.z(v), facing, "oak_door");
		}

		void locker(int u, int v, Direction facing) {
			Haddonfield.this.locker(this.x(u), 0, this.z(v), this.mirror ? net.minecraft.world.level.block.Mirror.FRONT_BACK.mirror(facing) : facing);
		}

		void bulb(int u, int y, int v) {
			Haddonfield.this.bulb(this.x(u), y, this.z(v));
		}

		void point(MapPoints.Type type, int u, int y, int v) {
			Haddonfield.this.point(type, this.x(u), y, this.z(v));
		}
	}

	/** How a house's ground floor is laid out. */
	private enum Plan {
		/** The living room at the front, the kitchen behind it (the film's houses are like this). */
		CLASSIC,
		/** The kitchen at the front by the door, the living room at the back. */
		KITCHEN_FRONT,
		/** A long hall from the front door straight through to the back door along one side; the rooms open off it. */
		HALL
	}

	/** How the upstairs is divided. */
	private enum Upstairs {
		/** A bedroom at the front, another at the back. */
		TWO_BEDROOMS,
		/** One big bedroom and a bathroom in the back corner. */
		MASTER_AND_BATH,
		/** The parents at the front, a child's room at the back. */
		KIDS_ROOM
	}

	/**
	 * A family house on {@code lot}. Houses differ: the kitchen at the back or by the front door, or a long hall
	 * down one side with the rooms off it, any of them built left for right; two bedrooms, or one big one and a
	 * bathroom, or a child's room upstairs; the things to search stand in different places. The film's houses
	 * (with a name) keep the plan they have always had: the living room at the front, the kitchen behind it.
	 * Every house has a pitched roof, a porch with pumpkins, a phone in the kitchen, a breaker box outside on
	 * the east wall and a wardrobe to hide in.
	 */
	private void house(Lot lot, int storeys, boolean big, Style s, String name, boolean abandoned, int w) {
		boolean film = name != null;
		Plan plan = film ? Plan.CLASSIC : Plan.values()[this.random.nextInt(Plan.values().length)];
		Upstairs up = film ? Upstairs.TWO_BEDROOMS : Upstairs.values()[this.random.nextInt(Upstairs.values().length)];
		boolean mirror = !film && this.random.nextBoolean();
		int d = 12;
		Frame f = new Frame(lot.cx() - w / 2, lot, w, mirror);
		boolean two = storeys == 2;
		int wallTop = two ? 8 : 3;
		int top = two ? 9 : 4;
		int mid = d / 2;
		BlockState wall = b(s.wall());
		BlockState floor = b(s.floor());
		BlockState pane = b("glass_pane");
		BlockState broken = dev.halloween.HwBlocks.BROKEN_GLASS_PANE.defaultBlockState();
		// The front door: in the middle, or at the end of the hall.
		int doorU = plan == Plan.HALL ? 2 : w / 2;

		// Foundation, floor, shell and corners.
		f.fill(-1, -1, -1, w, -1, d, b("stone_bricks"));
		f.fill(1, -1, 1, w - 2, -1, d - 2, floor);
		f.fill(0, 0, 0, w - 1, wallTop, d - 1, wall);
		f.fill(1, 0, 1, w - 2, wallTop, d - 2, AIR);
		for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
			f.fill(c[0], 0, c[1], c[0], wallTop, c[1], b(s.corner()));
		}
		// Ceiling (upstairs floor) and the attic floor under the roof.
		f.fill(1, 4, 1, w - 2, 4, d - 2, floor);
		f.fill(0, top, 0, w - 1, top, d - 1, wall);
		f.fill(1, top, 1, w - 2, top, d - 2, floor);

		// Doors, and the ground-floor windows (two pairs at the front clear of the door, two on each side). Sills stay solid.
		f.door(doorU, 0, 0, f.back());
		f.door(plan == Plan.HALL ? 2 : w - 3, 0, d - 1, f.front());
		int[] frontWindows = plan == Plan.HALL ? new int[]{5, 6, w - 4, w - 3} : new int[]{2, 3, w - 4, w - 3};
		java.util.Set<Integer> windowU = new java.util.HashSet<>();
		for (int u : frontWindows) {
			if (Math.abs(u - doorU) > 1) {
				f.fill(u, 1, 0, u, 2, 0, abandoned && u % 2 == 0 ? broken : pane);
				windowU.add(u);
			}
		}
		for (int v : new int[]{2, 3, d - 4, d - 3}) {
			f.fill(0, 1, v, 0, 2, v, abandoned && v == 3 ? broken : pane);
			f.fill(w - 1, 1, v, w - 1, 2, v, pane);
		}
		// The breaker box outside, always on the east wall (each house's lights stay nearest its own box).
		if (!abandoned) {
			this.breaker(Math.max(f.x(0), f.x(w - 1)), 1, f.z(mid), Direction.EAST);
		}

		boolean fireplace = this.random.nextBoolean();
		int chimneyV = switch (plan) {
			case CLASSIC -> this.classicFloor(f, w, d, mid, s, wall, abandoned, two, fireplace);
			case KITCHEN_FRONT -> this.kitchenFrontFloor(f, w, d, mid, s, wall, two, fireplace);
			case HALL -> this.hallFloor(f, w, d, mid, s, wall, two, fireplace);
		};
		// The porch light by the front door, in the wall.
		if (!abandoned) {
			f.bulb(doorU + 1, 2, 0);
		}

		if (two) {
			// Stairs along the west wall, climbing towards the back, up to the landing above the middle.
			for (int k = 0; k <= 4; k++) {
				f.set(1, k, mid - 4 + k, stairs(s.floor().replace("_planks", "_stairs"), f.back()));
				if (k + 1 <= 3) {
					f.fill(1, k + 1, mid - 4 + k, 1, 3, mid - 4 + k, AIR);
				}
			}
			f.fill(1, 4, mid - 4, 1, 4, mid - 1, AIR);
			f.fill(2, 5, mid - 4, 2, 5, mid - 1, b("dark_oak_fence"));
			for (int u : new int[]{2, 3, w - 4, w - 3}) {
				f.fill(u, 6, 0, u, 7, 0, abandoned ? broken : pane);
			}
			for (int v : new int[]{2, 3, d - 4, d - 3}) {
				f.fill(0, 6, v, 0, 7, v, pane);
				f.fill(w - 1, 6, v, w - 1, 7, v, pane);
			}
			this.upstairs(f, w, d, mid, s, wall, abandoned, up);
		}
		this.roof(f, w, d, top, s, wall);
		if (chimneyV >= 0) {
			f.fill(w, 0, chimneyV, w, top + 6, chimneyV, b("bricks"));
		}

		if (abandoned) {
			// Years of nobody: cobwebs up in the corners, dirt on the floor, the furniture under dust sheets.
			for (int[] c : new int[][]{{1, 1}, {w - 2, 1}, {1, d - 2}, {w - 2, mid - 1}, {w / 2, mid + 2}}) {
				f.set(c[0], 3, c[1], b("cobweb"));
				if (two) {
					f.set(c[0], 8, c[1], b("cobweb"));
				}
			}
			f.fill(w / 2 - 1, -1, 2, w / 2 + 1, -1, 4, b("rooted_dirt"));
			f.set(w - 3, 0, 4, b("skeleton_skull").setValue(BlockStateProperties.ROTATION_16, 6));
		}

		this.porch(f, doorU, windowU, abandoned);
		String pathBlock = abandoned ? "dirt_path" : this.pick("dirt_path", "dirt_path", "gravel", "stone_bricks", "bricks", "mossy_stone_bricks");
		for (int v = -3; v >= -7; v--) {
			f.set(doorU, -1, v, b(pathBlock));
		}
		if (name != null) {
			int rot = lot.south ? 0 : 8;
			this.sign(f.x(doorU + 2), 0, f.z(-5), rot, "", name, "");
		}
		this.flowerBed(f, w, doorU);
		boolean drive = this.driveway(f, w, lot, abandoned);
		if (!abandoned) {
			this.frontFence(f, w, lot, drive, doorU);
			this.mailbox(f, doorU);
			this.backyard(f, d);
			this.halloweenYard(f, w, lot, drive, doorU);
			this.curbside(f, w, lot, drive);
		} else {
			// The Myers yard: overgrown, a dead tree, nothing on the porch but the pumpkins.
			this.deadTree(f.x(w + 3), f.z(-4));
		}
		// A tree in the front yard on the side without the drive.
		int tu = drive || this.random.nextBoolean() ? -3 : w + 2;
		if (f.x(tu) > lot.x0 + 1 && f.x(tu) < lot.x1 - 1 && this.freeArea(f.x(tu) - 1, f.z(-4) - 1, f.x(tu) + 1, f.z(-4) + 1)) {
			this.tree(f.x(tu), f.z(-4), this.yardTreeKind());
		}
	}

	/**
	 * The film's plan: the living room at the front (sofa against the dividing wall, a coffee table, a bookcase,
	 * a television or a fireplace), the kitchen behind it (counters along the back wall with the stove and the
	 * phone, a fridge, a table and two chairs, the wardrobe). The dresser and the kitchen cabinet stand in one
	 * of a few places; now and then a nightstand by the front door. Returns where the chimney goes (v) or -1.
	 */
	private int classicFloor(Frame f, int w, int d, int mid, Style s, BlockState wall, boolean abandoned, boolean two, boolean fireplace) {
		// A rug in the living room (part of the floor: nothing to trip over).
		f.fill(w / 2 - 1, -1, 2, w / 2 + 1, -1, 4, b(s.rug()));
		// Wall between the living room and the kitchen, doorway on the east side.
		f.fill(1, 0, mid, w - 2, 3, mid, wall);
		f.fill(w - 4, 0, mid, w - 3, 2, mid, AIR);

		String sofa = this.pick("dark_oak_stairs", "spruce_stairs", "mangrove_stairs");
		for (int u = 4; u <= 6; u++) {
			f.set(u, 0, mid - 1, abandoned ? b("white_wool") : stairs(sofa, f.back()));
		}
		f.set(5, 0, mid - 3, slab("dark_oak_slab", SlabType.BOTTOM));
		f.set(w - 2, 0, mid - 1, b("bookshelf"));
		f.set(w - 2, 1, mid - 1, b("bookshelf"));
		// The dresser: in the east corner by the front wall, or on the west side by the windows (not in the way of the stairs).
		switch (abandoned ? 0 : this.random.nextInt(two ? 2 : 3)) {
			case 0 -> f.set(w - 2, 0, 1, search(HwBlocks.DRESSER, Direction.WEST));
			case 1 -> f.set(2, 0, 1, search(HwBlocks.DRESSER, f.back()));
			default -> f.set(1, 0, 2, search(HwBlocks.DRESSER, Direction.EAST));
		}
		if (!abandoned && this.random.nextInt(5) < 2) {
			// A nightstand by the front door, for the keys and the post.
			f.set(w / 2 - 2, 0, 1, search(HwBlocks.NIGHTSTAND, f.back()));
		} else {
			f.set(w / 2 - 2, 0, 1, b(this.pick("potted_fern", "potted_red_tulip", "potted_orange_tulip", "potted_dead_bush")));
		}
		if (fireplace) {
			// A brick hearth on the east wall, its chimney up the outside.
			f.set(w - 2, 0, 4, b("campfire").setValue(BlockStateProperties.LIT, false).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
			f.fill(w - 2, 1, 4, w - 2, 3, 4, b("bricks"));
		} else {
			// The family television, rabbit ears and all.
			f.set(w / 2 + 1, 0, 1, b("dark_oak_planks"));
			f.set(w / 2 + 1, 1, 1, b("black_concrete"));
			f.set(w / 2 + 1, 2, 1, b("iron_bars"));
		}
		// An armchair by the side windows, a floor lamp by the sofa, a side table on the other end.
		f.set(w - 3, 0, 3, stairs(abandoned ? "cobblestone_stairs" : "spruce_stairs", Direction.EAST));
		f.set(3, 0, mid - 1, b("dark_oak_fence"));
		if (abandoned) {
			f.set(3, 1, mid - 1, b("cobweb"));
		} else {
			f.bulb(3, 1, mid - 1);
		}
		if (w >= 13) {
			f.set(7, 0, mid - 1, b("dark_oak_planks"));
			f.set(7, 1, mid - 1, b(abandoned ? "skeleton_skull" : this.pick("flower_pot", "candle", "potted_fern")));
		}
		f.set(1, 2, 1, b(this.pick("red", "brown", "green", "blue") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.back()));
		f.set(w - 2, 2, 1, b(this.pick("red", "brown", "green", "blue") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.back()));
		f.set(5, 2, mid - 1, b(this.pick("orange", "yellow", "light_blue", "lime", "purple") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.front()));
		f.point(MapPoints.Type.NPC, w / 2, 0, 2);

		// Kitchen: a checked floor, counters along the back wall (sink, stove, the phone), cupboards over them, a fridge,
		// the cabinet at the west end of the counters or by the doorway, a table and two chairs, the wardrobe to hide in.
		for (int u = 1; u <= w - 2; u++) {
			for (int v = mid + 1; v <= d - 2; v++) {
				f.set(u, -1, v, b((u + v) % 2 == 0 ? "white_terracotta" : (abandoned ? "coarse_dirt" : "gray_terracotta")));
			}
		}
		for (int u = 2; u <= w - 6; u++) {
			f.set(u, 0, d - 2, b(abandoned ? "cobblestone" : "polished_andesite"));
			f.set(u, 2, d - 2, b("spruce_planks"));
		}
		f.set(3, 0, d - 2, b("cauldron"));
		f.set(4, 0, d - 2, facing("smoker", f.front()));
		f.set(w - 6, 1, d - 2, b(this.pick("potted_fern", "potted_spruce_sapling", "potted_red_tulip")));
		f.fill(w - 5, 0, d - 2, w - 5, 1, d - 2, b("quartz_pillar"));
		if (abandoned || this.random.nextBoolean()) {
			f.set(1, 0, d - 2, search(HwBlocks.KITCHEN_CABINET, f.front()));
		} else {
			f.set(1, 0, d - 2, b("polished_andesite"));
			f.set(w - 2, 0, mid + 1, search(HwBlocks.KITCHEN_CABINET, Direction.WEST));
		}
		f.set(2, 1, d - 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 2, 1, d - 2);
		int tableU = w / 2 - 1;
		f.set(tableU, 0, mid + 3, b("dark_oak_fence"));
		if (!abandoned) {
			f.set(tableU, 1, mid + 3, b("candle"));
		}
		f.set(tableU - 1, 0, mid + 3, stairs("oak_stairs", Direction.WEST));
		f.set(tableU + 1, 0, mid + 3, stairs("oak_stairs", Direction.EAST));
		f.locker(1, mid + 1, Direction.EAST);
		f.set(3, 2, mid + 1, b("white_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.back()));

		if (!abandoned) {
			// Two ceiling lights in each room and one under the hood over the stove: all on the house's wiring.
			f.bulb(w / 2 - 2, 4, 3);
			f.bulb(w / 2 + 2, 4, 3);
			f.bulb(w / 2 - 2, 4, d - 3);
			f.bulb(w / 2 + 2, 4, mid + 3);
			f.bulb(4, 2, d - 2);
		}
		return fireplace ? 4 : -1;
	}

	/**
	 * The kitchen at the front: in by the door, counters down the east wall (the cabinet, the phone, the stove,
	 * the sink, the fridge), a table by the windows; the stairs go up from the kitchen. The living room behind
	 * it: a sofa against the back wall facing a television or a fireplace, an armchair, a bookcase, a dresser,
	 * the wardrobe in the far corner. Returns where the chimney goes (v) or -1.
	 */
	private int kitchenFrontFloor(Frame f, int w, int d, int mid, Style s, BlockState wall, boolean two, boolean fireplace) {
		for (int u = 1; u <= w - 2; u++) {
			for (int v = 1; v <= mid - 1; v++) {
				f.set(u, -1, v, b((u + v) % 2 == 0 ? "white_terracotta" : "gray_terracotta"));
			}
		}
		f.fill(1, 0, mid, w - 2, 3, mid, wall);
		f.fill(w - 4, 0, mid, w - 3, 2, mid, AIR);
		// Counters down the east wall.
		f.set(w - 2, 0, 1, search(HwBlocks.KITCHEN_CABINET, Direction.WEST));
		f.set(w - 2, 2, 1, b("spruce_planks"));
		f.set(w - 2, 0, 2, b("polished_andesite"));
		f.set(w - 2, 1, 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, w - 2, 1, 2);
		f.set(w - 2, 0, 3, facing("smoker", Direction.WEST));
		f.set(w - 2, 0, 4, b("cauldron"));
		f.set(w - 2, 2, 4, b("spruce_planks"));
		f.fill(w - 2, 0, mid - 1, w - 2, 1, mid - 1, b("quartz_pillar"));
		// The table by the front windows.
		int tableU = w / 2 - 2;
		f.set(tableU, 0, 3, b("dark_oak_fence"));
		f.set(tableU, 1, 3, b("candle"));
		f.set(tableU - 1, 0, 3, stairs("oak_stairs", Direction.WEST));
		f.set(tableU + 1, 0, 3, stairs("oak_stairs", Direction.EAST));
		f.set(w - 2, 2, mid - 1, b("white_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));

		// The living room at the back.
		f.fill(w / 2 - 1, -1, mid + 2, w / 2 + 1, -1, mid + 4, b(s.rug()));
		String sofa = this.pick("dark_oak_stairs", "spruce_stairs", "mangrove_stairs");
		for (int u = 4; u <= 6; u++) {
			f.set(u, 0, d - 2, stairs(sofa, f.back()));
		}
		f.set(5, 0, d - 4, slab("dark_oak_slab", SlabType.BOTTOM));
		if (fireplace) {
			f.set(w - 2, 0, mid + 1, b("campfire").setValue(BlockStateProperties.LIT, false).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
			f.fill(w - 2, 1, mid + 1, w - 2, 3, mid + 1, b("bricks"));
		} else {
			f.set(w / 2 + 1, 0, mid + 1, b("dark_oak_planks"));
			f.set(w / 2 + 1, 1, mid + 1, b("black_concrete"));
			f.set(w / 2 + 1, 2, mid + 1, b("iron_bars"));
		}
		f.set(w - 2, 0, d - 4, stairs("spruce_stairs", Direction.EAST));
		f.fill(w - 2, 0, d - 2, w - 2, 1, d - 2, b("bookshelf"));
		f.set(3, 0, d - 2, b("dark_oak_fence"));
		f.bulb(3, 1, d - 2);
		f.set(1, 0, mid + 1 + this.random.nextInt(2), search(HwBlocks.DRESSER, Direction.EAST));
		f.locker(1, d - 2, Direction.EAST);
		f.set(5, 2, d - 2, b(this.pick("orange", "yellow", "light_blue", "lime", "purple") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.front()));
		f.point(MapPoints.Type.NPC, w / 2, 0, mid + 3);

		f.bulb(w / 2 - 2, 4, 3);
		f.bulb(w / 2 + 2, 4, 3);
		f.bulb(w / 2 - 2, 4, mid + 3);
		f.bulb(w / 2 + 2, 4, mid + 3);
		f.bulb(w - 2, 2, 3);
		return fireplace ? mid + 1 : -1;
	}

	/**
	 * A long hall down the west side from the front door to the back door (the stairs go up in it), its wall
	 * opening into the living room at the front and the kitchen at the back; the two rooms open into each other
	 * too. Something to search stands in the hall. Returns where the chimney goes (v) or -1.
	 */
	private int hallFloor(Frame f, int w, int d, int mid, Style s, BlockState wall, boolean two, boolean fireplace) {
		f.fill(3, 0, 1, 3, 3, d - 2, wall);
		f.fill(3, 0, 3, 3, 2, 4, AIR);
		f.fill(3, 0, mid + 2, 3, 2, mid + 3, AIR);
		f.fill(4, 0, mid, w - 2, 3, mid, wall);
		f.fill(w - 4, 0, mid, w - 3, 2, mid, AIR);
		// A runner down the hall.
		f.fill(2, -1, 1, 2, -1, d - 2, b(this.pick("red_wool", "brown_wool", "green_wool")));
		if (two) {
			f.set(1, 0, d - 2 - this.random.nextInt(3), search(this.random.nextBoolean() ? HwBlocks.NIGHTSTAND : HwBlocks.DRESSER, Direction.EAST));
		} else {
			f.set(1, 0, mid - 1 + this.random.nextInt(3), search(HwBlocks.DRESSER, Direction.EAST));
		}
		f.set(1, 2, d - 3, b(this.pick("red", "brown", "green", "blue") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));

		int lc = (4 + w - 2) / 2;
		// Living room.
		f.fill(lc - 1, -1, 2, lc + 1, -1, 4, b(s.rug()));
		// (Short enough to leave the way through to the kitchen clear in a narrow house.)
		String sofa = this.pick("dark_oak_stairs", "spruce_stairs", "mangrove_stairs");
		for (int u = lc - 1; u <= Math.min(lc + 1, w - 5); u++) {
			f.set(u, 0, mid - 1, stairs(sofa, f.back()));
		}
		f.set(lc, 0, mid - 3, slab("dark_oak_slab", SlabType.BOTTOM));
		if (fireplace) {
			f.set(w - 2, 0, 4, b("campfire").setValue(BlockStateProperties.LIT, false).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
			f.fill(w - 2, 1, 4, w - 2, 3, 4, b("bricks"));
		} else {
			f.set(w - 2, 0, 4, b("dark_oak_planks"));
			f.set(w - 2, 1, 4, b("black_concrete"));
			f.set(w - 2, 2, 4, b("iron_bars"));
		}
		f.set(this.random.nextBoolean() ? w - 2 : 4, 0, 1, search(HwBlocks.DRESSER, f.back()));
		f.set(w - 3, 0, 2, stairs("spruce_stairs", Direction.EAST));
		f.set(4, 0, mid - 1, b("dark_oak_fence"));
		f.bulb(4, 1, mid - 1);
		f.set(lc, 2, mid - 1, b(this.pick("orange", "yellow", "light_blue", "lime", "purple") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.front()));
		f.point(MapPoints.Type.NPC, lc, 0, 2);

		// Kitchen.
		for (int u = 4; u <= w - 2; u++) {
			for (int v = mid + 1; v <= d - 2; v++) {
				f.set(u, -1, v, b((u + v) % 2 == 0 ? "white_terracotta" : "gray_terracotta"));
			}
		}
		f.set(4, 0, d - 2, search(HwBlocks.KITCHEN_CABINET, f.front()));
		for (int u = 5; u <= w - 4; u++) {
			f.set(u, 0, d - 2, b("polished_andesite"));
			f.set(u, 2, d - 2, b("spruce_planks"));
		}
		f.set(5, 1, d - 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 5, 1, d - 2);
		f.set(6, 0, d - 2, facing("smoker", f.front()));
		f.set(6, 2, d - 2, AIR);
		f.bulb(6, 2, d - 2);
		if (w - 4 >= 7) {
			f.set(7, 0, d - 2, b("cauldron"));
		}
		f.fill(w - 3, 0, d - 2, w - 3, 1, d - 2, b("quartz_pillar"));
		f.locker(w - 2, d - 2, Direction.WEST);
		f.set(lc, 0, mid + 2, b("dark_oak_fence"));
		f.set(lc, 1, mid + 2, b("candle"));
		f.set(lc - 1, 0, mid + 2, stairs("oak_stairs", Direction.WEST));
		f.set(lc + 1, 0, mid + 2, stairs("oak_stairs", Direction.EAST));

		f.bulb(2, 4, mid + 2);
		f.bulb(lc, 4, 3);
		f.bulb(lc, 4, d - 3);
		return fireplace ? 4 : -1;
	}

	/**
	 * Upstairs (the stairs come up at the west wall, landing at the middle): two bedrooms; or one big bedroom
	 * with a double bed and a bathroom in the back corner; or the parents at the front and a child's room at the
	 * back. Nightstands, dressers and a toy chest to search.
	 */
	private void upstairs(Frame f, int w, int d, int mid, Style s, BlockState wall, boolean abandoned, Upstairs up) {
		String rug = abandoned ? "white" : this.pick("red", "blue", "green", "yellow", "light_blue", "brown");
		if (up != Upstairs.MASTER_AND_BATH) {
			// A wall between the two rooms, one step behind the landing.
			f.fill(1, 5, mid + 1, w - 2, 8, mid + 1, wall);
			f.fill(w / 2, 5, mid + 1, w / 2 + 1, 6, mid + 1, AIR);
			// Front bedroom: a bed under the window, a bedside table (sometimes a nightstand to search), a desk and chair,
			// a bookcase, a rug.
			this.bed(f, w - 2, 5, 3, f.front(), rug);
			if (!abandoned && this.random.nextBoolean()) {
				f.set(w - 2, 5, 1, search(HwBlocks.NIGHTSTAND, f.back()));
			} else {
				f.set(w - 2, 5, 1, b("spruce_planks"));
			}
			f.set(w / 2, 5, 1, slab("spruce_slab", SlabType.TOP));
			f.set(w / 2, 5, 2, stairs("spruce_stairs", f.back()));
			f.set(3, 5, 1, b("chiseled_bookshelf").setValue(BlockStateProperties.HORIZONTAL_FACING, f.back()));
			f.floorFill(w / 2 - 1, 4, 3, w / 2 + 1, 4, b(s.rug()));
			f.set(w / 2, 7, 1, b(this.pick("pink", "cyan", "lime", "orange") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.back()));
			if (!abandoned) {
				f.bulb(w / 2, 9, 3);
				f.bulb(w - 2, 6, 1);
			}
		}
		switch (up) {
			case TWO_BEDROOMS -> {
				// Back bedroom: a bed against the back wall, a nightstand or a dresser, a bookcase, a desk.
				this.bed(f, w - 2, 5, d - 3, f.back(), abandoned ? "white" : this.pick("red", "blue", "green", "purple", "orange"));
				f.set(1, 5, d - 2, search(this.random.nextBoolean() ? HwBlocks.NIGHTSTAND : HwBlocks.DRESSER, Direction.EAST));
				f.set(1, 5, mid + 2, b("bookshelf"));
				f.set(1, 6, mid + 2, b("bookshelf"));
				f.set(w / 2, 5, d - 2, slab("spruce_slab", SlabType.TOP));
				f.set(w / 2, 5, d - 3, stairs("spruce_stairs", f.front()));
				f.floorFill(w / 2 - 1, 4, mid + 2, w / 2 + 1, mid + 3, b(this.pick("light_blue_wool", "lime_wool", "pink_wool")));
				if (!abandoned) {
					f.bulb(w / 2, 9, d - 3);
					f.bulb(w / 2, 6, d - 2);
				}
			}
			case KIDS_ROOM -> {
				// A child's room: a bed along the west side, a toy chest (a crate), a little desk, a bright rug, toys about.
				String kid = this.pick("lime", "pink", "light_blue", "yellow");
				this.bed(f, 2, 5, d - 3, f.back(), kid);
				f.set(w - 2, 5, d - 2, search(HwBlocks.CRATE, Direction.WEST));
				f.set(w - 2, 5, mid + 2, slab("birch_slab", SlabType.TOP));
				f.set(w - 3, 5, mid + 2, stairs("birch_stairs", Direction.EAST));
				f.floorFill(w / 2 - 1, 4, mid + 3, w / 2 + 1, d - 2, b(kid + "_wool"));
				f.set(w / 2 + 1, 5, d - 2, b(this.pick("red_candle", "lime_candle", "blue_candle", "yellow_candle")));
				f.set(1, 5, mid + 2, b("bookshelf"));
				f.set(w / 2, 7, d - 2, b(kid + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.front()));
				f.bulb(w / 2, 9, d - 3);
				f.bulb(w - 2, 6, mid + 2);
			}
			case MASTER_AND_BATH -> {
				// The bathroom in the back east corner: tiles, a bathtub, a toilet, a basin.
				int bu = w - 5;
				f.fill(bu, 5, mid + 1, w - 2, 8, mid + 1, wall);
				f.fill(bu, 5, mid + 2, bu, 8, d - 2, wall);
				f.fill(bu, 5, mid + 3, bu, 6, mid + 3, AIR);
				f.floorFill(w - 4, 4, mid + 2, w - 2, d - 2, b("white_terracotta"));
				BlockState bath = b("water_cauldron").setValue(BlockStateProperties.LEVEL_CAULDRON, 3);
				f.set(w - 3, 5, d - 2, bath);
				f.set(w - 2, 5, d - 2, bath);
				f.set(w - 4, 5, d - 2, stairs("quartz_stairs", f.back()));
				f.set(w - 2, 5, mid + 2, b("cauldron"));
				f.set(w - 2, 6, mid + 2, b("tripwire_hook").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
				f.bulb(w - 3, 9, mid + 3);
				// The bedroom: a double bed against the east wall, a nightstand beside it, a dresser in the far corner,
				// a desk, a bookcase, a big rug.
				this.bed(f, w - 2, 5, 3, f.front(), rug);
				this.bed(f, w - 3, 5, 3, f.front(), rug);
				f.set(w - 4, 5, 2, search(HwBlocks.NIGHTSTAND, f.back()));
				f.set(1, 5, d - 2, search(HwBlocks.DRESSER, Direction.EAST));
				f.set(w / 2 - 1, 5, 1, slab("spruce_slab", SlabType.TOP));
				f.set(w / 2 - 1, 5, 2, stairs("spruce_stairs", f.back()));
				f.set(1, 5, mid + 2, b("bookshelf"));
				f.set(1, 6, mid + 2, b("bookshelf"));
				f.floorFill(w / 2 - 2, 4, mid, w / 2, mid + 2, b(s.rug()));
				f.set(w / 2, 7, 1, b(this.pick("pink", "cyan", "lime", "orange") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, f.back()));
				if (!abandoned) {
					f.bulb(w / 2, 9, 3);
					f.bulb(w / 2 - 1, 9, d - 3);
					f.bulb(w - 4, 6, 2);
				}
			}
		}
	}

	private String pick(String... options) {
		return options[this.random.nextInt(options.length)];
	}

	/** A real bed, foot at (u, y, v), head one step towards {@code headDir}. Using it does nothing in a match. */
	private void bed(Frame f, int u, int y, int v, Direction headDir, String color) {
		BlockState bed = b(color + "_bed").setValue(BlockStateProperties.HORIZONTAL_FACING, headDir);
		int headV = headDir == f.front() ? v - 1 : v + 1;
		f.set(u, y, v, bed.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
		f.set(u, y, headV, bed.setValue(BlockStateProperties.BED_PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
	}

	/** A deck and a little roof over the front door (at u = {@code c}) on two posts, pumpkins, a rocking chair; a lantern on some. */
	private void porch(Frame f, int c, java.util.Set<Integer> windows, boolean abandoned) {
		f.fill(c - 2, -1, -1, c + 2, -1, -2, b("spruce_planks"));
		f.fill(c - 2, 0, -2, c - 2, 2, -2, b("spruce_fence"));
		f.fill(c + 2, 0, -2, c + 2, 2, -2, b("spruce_fence"));
		f.fill(c - 2, 3, -1, c + 2, 3, -2, slab(abandoned ? "dark_oak_slab" : "spruce_slab", SlabType.BOTTOM));
		boolean lit = !abandoned && this.random.nextInt(3) == 0;
		f.set(c - 1, 0, -1, facing(lit ? "jack_o_lantern" : "carved_pumpkin", f.front()));
		f.set(c + 1, 0, -1, facing("carved_pumpkin", f.front()));
		if (!abandoned) {
			f.set(c + 1, 0, -2, stairs("dark_oak_stairs", f.back()));
			if (this.random.nextInt(3) == 0) {
				f.set(c, 2, -2, b("lantern").setValue(BlockStateProperties.HANGING, true));
			}
		}
		// Cobwebs in the top corners: Halloween decorations, up out of anyone's way.
		// (Not over a window: a climb through it must not end in a web.)
		if (!windows.contains(c - 2)) {
			f.set(c - 2, 2, -1, b("cobweb"));
		}
		if ((abandoned || this.random.nextBoolean()) && !windows.contains(c + 2)) {
			f.set(c + 2, 2, -1, b("cobweb"));
		}
	}

	/** Autumn flowers along the front wall, clear of the steps (no collision: windows and the door stay usable). */
	private void flowerBed(Frame f, int w, int doorU) {
		for (int u = 0; u < w; u++) {
			if (Math.abs(u - doorU) <= 2 || this.random.nextInt(3) == 0) {
				continue;
			}
			f.set(u, 0, -1, b(this.pick("poppy", "orange_tulip", "red_tulip", "dandelion", "oxeye_daisy", "short_grass")));
		}
	}

	/** A drive along the house: concrete, gravel or stone (no parked cars: the only car in town is the one that drives). */
	private boolean driveway(Frame f, int w, Lot lot, boolean abandoned) {
		int xa = Math.min(f.x(w + 1), f.x(w + 3));
		int xb = Math.max(f.x(w + 1), f.x(w + 3));
		if (xb > lot.x1 - 1 || xa < lot.x0 + 1) {
			return false;
		}
		BlockState surface = b(abandoned ? "coarse_dirt" : this.pick("light_gray_concrete", "light_gray_concrete", "gravel", "andesite", "smooth_stone"));
		for (int v = -7; v <= 4; v++) {
			for (int u = w + 1; u <= w + 3; u++) {
				f.set(u, -1, v, surface);
			}
		}
		return true;
	}

	/**
	 * Along the sidewalk, one of: nothing, a white picket fence, a low hedge, a low stone wall, a wrought-iron
	 * railing; open at the path and the drive.
	 */
	private void frontFence(Frame f, int w, Lot lot, boolean drive, int doorU) {
		int kind = this.random.nextInt(6);
		if (kind < 2) {
			return;
		}
		BlockState post = switch (kind) {
			case 2 -> b("birch_fence");
			case 3 -> leaves(this.pick("oak_leaves", "azalea_leaves", "dark_oak_leaves"));
			case 4 -> b(this.pick("cobblestone_wall", "mossy_cobblestone_wall", "stone_brick_wall", "brick_wall"));
			default -> b("iron_bars");
		};
		int z = f.z(-7);
		for (int x = lot.x0 + 1; x <= lot.x1 - 1; x++) {
			int u = f.u(x);
			if (u == doorU || drive && u >= w + 1 && u <= w + 3) {
				continue;
			}
			if (this.free(x, z)) {
				this.set(x, 0, z, post);
			}
		}
	}

	/** A mailbox by the path, at the sidewalk. */
	private void mailbox(Frame f, int doorU) {
		int x = f.x(doorU - 2);
		int z = f.z(-6);
		if (this.free(x, z)) {
			this.set(x, 0, z, b(this.pick("spruce_fence", "dark_oak_fence", "cobblestone_wall")));
			this.set(x, 1, z, b(this.pick("red_terracotta", "light_gray_terracotta", "blue_terracotta", "black_terracotta", "white_terracotta")));
		}
	}

	/**
	 * Halloween in the front yard, one or two of: a little graveyard of headstones, a scarecrow with a pumpkin
	 * head, a pile of pumpkins, hay bales with pumpkins, skulls on stakes. Clear of the path and the drive.
	 */
	private void halloweenYard(Frame f, int w, Lot lot, boolean drive, int doorU) {
		// The side of the front yard away from the path.
		int u0 = doorU <= w / 2 ? doorU + 3 : 1;
		int u1 = doorU <= w / 2 ? w - 2 : doorU - 3;
		if (u1 - u0 < 2) {
			return;
		}
		int count = 1 + this.random.nextInt(2);
		java.util.List<Integer> kinds = new java.util.ArrayList<>(java.util.List.of(0, 1, 2, 3, 4));
		java.util.Collections.shuffle(kinds, this.random);
		int u = u0;
		for (int i = 0; i < count && u <= u1; i++) {
			switch (kinds.get(i)) {
				case 0 -> {
					// Headstones in a row, a skull on one.
					String stone = this.pick("stone_brick_wall", "cobblestone_wall", "andesite_wall", "mossy_stone_brick_wall");
					for (int k = 0; k < 3 && u <= u1; k++, u += 2) {
						if (this.yardFree(f, u, -5)) {
							f.set(u, 0, -5, b(stone));
							f.set(u, -1, -4, b("coarse_dirt"));
							if (this.random.nextInt(4) == 0) {
								f.set(u, 1, -5, b("skeleton_skull").setValue(BlockStateProperties.ROTATION_16, lot.south ? 0 : 8));
							}
						}
					}
				}
				case 1 -> {
					// A scarecrow: a post, a hay body with arms, a carved pumpkin for a head.
					if (u + 2 <= u1 && this.yardFree(f, u, -5) && this.yardFree(f, u + 1, -5) && this.yardFree(f, u + 2, -5)) {
						f.set(u + 1, 0, -5, b("dark_oak_fence"));
						f.set(u + 1, 1, -5, b("hay_block"));
						f.set(u + 1, 2, -5, facing("carved_pumpkin", f.front()));
						f.set(u, 1, -5, b("dark_oak_fence"));
						f.set(u + 2, 1, -5, b("dark_oak_fence"));
					}
					u += 4;
				}
				case 2 -> {
					// Pumpkins in a heap.
					for (int[] p : new int[][]{{0, -4}, {1, -4}, {0, -5}, {1, -5}, {2, -4}}) {
						if (u + p[0] <= u1 && this.yardFree(f, u + p[0], p[1]) && this.random.nextInt(4) > 0) {
							f.set(u + p[0], 0, p[1], this.random.nextInt(3) == 0 ? facing("carved_pumpkin", f.front()) : b("pumpkin"));
						}
					}
					u += 4;
				}
				case 3 -> {
					// Hay bales, pumpkins on top.
					for (int k = 0; k < 2 && u + k <= u1; k++) {
						if (this.yardFree(f, u + k, -5)) {
							f.set(u + k, 0, -5, b("hay_block"));
							f.set(u + k, 1, -5, k == 0 ? b("pumpkin") : facing("carved_pumpkin", f.front()));
						}
					}
					u += 3;
				}
				default -> {
					// Skulls on stakes.
					for (int k = 0; k < 2 && u <= u1; k++, u += 2) {
						if (this.yardFree(f, u, -4)) {
							f.set(u, 0, -4, b("dark_oak_fence"));
							f.set(u, 1, -4, b("skeleton_skull").setValue(BlockStateProperties.ROTATION_16, lot.south ? 0 : 8));
						}
					}
				}
			}
			u += 1;
		}
	}

	private boolean yardFree(Frame f, int u, int v) {
		return this.free(f.x(u), f.z(v));
	}

	/** By the curb: a trash can or two on some days, a pile of raked leaves, now and then a For Sale sign. */
	private void curbside(Frame f, int w, Lot lot, boolean drive) {
		int u = drive ? w + 4 : w - 1;
		if (this.random.nextBoolean() && f.x(u) > lot.x0 && f.x(u) < lot.x1 && this.yardFree(f, u, -6)) {
			f.set(u, 0, -6, b(this.pick("cauldron", "cauldron", "composter")));
			if (this.random.nextBoolean() && this.yardFree(f, u + 1, -6) && f.x(u + 1) > lot.x0 && f.x(u + 1) < lot.x1) {
				f.set(u + 1, 0, -6, b("cauldron"));
			}
		}
		// Raked leaves in a heap on the lawn.
		int lu = drive ? -2 : w + 1;
		if (this.random.nextInt(3) > 0) {
			for (int du = 0; du < 3; du++) {
				for (int dv = 0; dv < 2; dv++) {
					if (f.x(lu + du) > lot.x0 && f.x(lu + du) < lot.x1 && this.yardFree(f, lu + du, -5 + dv)) {
						f.set(lu + du, 0, -5 + dv, this.leafLitter(du == 1 ? 4 : 2 + this.random.nextInt(2)));
					}
				}
			}
		}
		if (this.random.nextInt(12) == 0 && this.yardFree(f, w - 1, -5)) {
			int rot = lot.south ? 0 : 8;
			this.sign(f.x(w - 1), 0, f.z(-5), rot, "FOR SALE", "Nichols", "Realty", "555-0178");
		}
	}

	/** Fallen leaves, {@code amount} of 4 layers. */
	private BlockState leafLitter(int amount) {
		BlockState s = b("leaf_litter").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.from2DDataValue(this.random.nextInt(4)));
		net.minecraft.world.level.block.state.properties.Property<?> p = s.getBlock().getStateDefinition().getProperty("segment_amount");
		if (p instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip) {
			s = s.setValue(ip, Math.max(1, Math.min(4, amount)));
		}
		return s;
	}

	/** Something in the back yard: a shed, a swing set, washing on the line, a dog kennel, a pumpkin patch, a picnic table. */
	private void backyard(Frame f, int d) {
		int v0 = d + 3;
		int u0 = 1;
		int za = Math.min(f.z(v0), f.z(v0 + 5));
		int zb = Math.max(f.z(v0), f.z(v0 + 5));
		for (Object[] p : this.points) {
			net.minecraft.core.BlockPos at = (net.minecraft.core.BlockPos) p[1];
			if (p[0] == MapPoints.Type.CELLAR && at.getX() >= Math.min(f.x(u0), f.x(u0 + 6)) - 4 && at.getX() <= Math.max(f.x(u0), f.x(u0 + 6)) + 4
				&& at.getZ() >= za - 4 && at.getZ() <= zb + 4) {
				return;
			}
		}
		if (!this.freeArea(f.x(u0) - 1, za, f.x(u0 + 6), zb)) {
			return;
		}
		switch (this.random.nextInt(6)) {
			case 0 -> {
				// Garden shed with a door (it locks like any door).
				f.fill(u0, -1, v0, u0 + 4, -1, v0 + 3, b("spruce_planks"));
				f.fill(u0, 0, v0, u0 + 4, 2, v0 + 3, b("stripped_spruce_wood"));
				f.fill(u0 + 1, 0, v0 + 1, u0 + 3, 1, v0 + 2, AIR);
				f.fill(u0 - 1, 3, v0 - 1, u0 + 5, 3, v0 + 4, slab("dark_oak_slab", SlabType.BOTTOM));
				f.door(u0 + 2, 0, v0, f.back());
				f.set(u0 + 1, 0, v0 + 2, b("hay_block"));
				f.set(u0 + 3, 0, v0 + 2, b("cauldron"));
			}
			case 1 -> {
				// Swing set.
				f.fill(u0, 0, v0 + 1, u0, 2, v0 + 1, b("dark_oak_fence"));
				f.fill(u0 + 4, 0, v0 + 1, u0 + 4, 2, v0 + 1, b("dark_oak_fence"));
				f.fill(u0, 3, v0 + 1, u0 + 4, 3, v0 + 1, b("dark_oak_planks"));
				for (int u : new int[]{u0 + 1, u0 + 3}) {
					f.set(u, 2, v0 + 1, b("iron_chain"));
					f.set(u, 1, v0 + 1, slab("oak_slab", SlabType.TOP));
				}
			}
			case 2 -> {
				// Washing on the line, up out of the way.
				f.fill(u0, 0, v0 + 2, u0, 2, v0 + 2, b("spruce_fence"));
				f.fill(u0 + 6, 0, v0 + 2, u0 + 6, 2, v0 + 2, b("spruce_fence"));
				for (int u = u0 + 1; u <= u0 + 5; u++) {
					f.set(u, 2, v0 + 2, u % 2 == 0 ? b(this.pick("white_wool", "light_blue_wool", "pink_wool"))
						: b("iron_chain").setValue(BlockStateProperties.AXIS, Direction.Axis.X));
				}
			}
			case 3 -> {
				// Dog kennel and a water bowl.
				f.fill(u0, 0, v0, u0 + 2, 1, v0 + 2, b("oak_planks"));
				f.fill(u0 + 1, 0, v0, u0 + 1, 1, v0 + 1, AIR);
				f.fill(u0, 2, v0, u0 + 2, 2, v0 + 2, slab("red_nether_brick_slab", SlabType.BOTTOM));
				f.set(u0 + 4, 0, v0, b("cauldron"));
			}
			case 4 -> {
				// Pumpkin patch with a scarecrow.
				f.fill(u0, -1, v0, u0 + 5, -1, v0 + 3, b("coarse_dirt"));
				for (int u = u0; u <= u0 + 5; u += 2) {
					f.set(u, 0, v0 + (u % 4 == 1 ? 0 : 3), b("pumpkin"));
				}
				int su = u0 + 3;
				f.set(su, 0, v0 + 1, b("dark_oak_fence"));
				f.set(su, 1, v0 + 1, b("hay_block"));
				f.set(su, 2, v0 + 1, facing("carved_pumpkin", f.back()));
				f.set(su - 1, 1, v0 + 1, b("dark_oak_fence"));
				f.set(su + 1, 1, v0 + 1, b("dark_oak_fence"));
			}
			default -> {
				// Picnic table with benches.
				f.fill(u0 + 1, 0, v0 + 1, u0 + 3, 0, v0 + 1, slab("spruce_slab", SlabType.TOP));
				f.fill(u0 + 1, 0, v0, u0 + 3, 0, v0, stairs("spruce_stairs", f.front()));
				f.fill(u0 + 1, 0, v0 + 2, u0 + 3, 0, v0 + 2, stairs("spruce_stairs", f.back()));
			}
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
				this.tree(x, z, this.yardTreeKind());
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
		// Shelves: rows of spruce shelving with cardboard boxes (things to search) at the ends.
		for (int row = 0; row < 3; row++) {
			int z = zFront - 5 - row * 3;
			for (int x = x0 + 4; x <= x1 - 4; x++) {
				if (Math.abs(x - (x0 + x1) / 2) <= 1) {
					continue;
				}
				this.set(x, 0, z, b("spruce_planks"));
				this.set(x, 1, z, this.goods());
				this.set(x, 2, z, slab("spruce_slab", SlabType.BOTTOM));
			}
			this.set(x0 + 3, 0, z, search(HwBlocks.CARDBOARD_BOX, Direction.WEST));
			this.set(x1 - 3, 0, z, search(HwBlocks.CARDBOARD_BOX, Direction.EAST));
		}
		// Counter by the door with the phone.
		this.fill(x0 + 2, 0, zFront - 3, x0 + 4, 0, zFront - 3, b("dark_oak_planks"));
		this.set(x0 + 3, 1, zFront - 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.point(MapPoints.Type.PHONE, x0 + 3, 1, zFront - 3);
		// Storeroom behind a wall, with its own door to the shop.
		int zStore = zBack + 5;
		this.fill(x0 + 1, 0, zStore, x1 - 1, 3, zStore, b("bricks"));
		this.fill((x0 + x1) / 2, 0, zStore, (x0 + x1) / 2 + 1, 2, zStore, AIR);
		this.set(x0 + 2, 0, zBack + 1, search(HwBlocks.TOOL_CABINET, Direction.SOUTH));
		this.set(x1 - 2, 0, zBack + 1, search(HwBlocks.CRATE, Direction.SOUTH));
		this.point(MapPoints.Type.GUN_CASE, x0 + 5, 0, zBack + 1);
		this.door(x1 - 3, 0, zBack, Direction.SOUTH, "spruce_door");
		this.locker(x0 + 1, 0, zBack + 3, Direction.EAST);
		// Lights and the breaker (outside, west wall): bulbs within its reach, a second breaker for the east half.
		int cx = (x0 + x1) / 2;
		for (int x = x0 + 6; x <= x1 - 6; x += 9) {
			this.bulb(x, 4, zFront - 8);
			this.bulb(x, 4, zBack + 3);
			this.bulb(x, 4, zFront - 4);
		}
		this.breaker(x0, 1, zFront - 8, Direction.WEST);
		this.breaker(x1, 1, zFront - 8, Direction.EAST);
		this.breaker(cx, 1, zBack, Direction.NORTH);
		this.sign(x0 + 3, 0, zFront + 3, 0, "", "NICHOLS", "HARDWARE", "");
		this.point(MapPoints.Type.NPC, cx, 0, zFront - 2);
		// The till on the counter, crates in the storeroom, an awning and the name over the windows.
		this.set(x0 + 2, 1, zFront - 3, b("observer").setValue(BlockStateProperties.FACING, Direction.UP));
		this.set(x0 + 4, 1, zFront - 3, b("candle"));
		for (int x = x0 + 8; x <= x1 - 8; x += 3) {
			this.set(x, 0, zBack + 3, b(this.pick("hay_block", "composter", "spruce_planks", "fletching_table")));
			this.set(x, 1, zBack + 3, b(this.pick("spruce_planks", "hay_block")));
		}
		this.awning(x0, x1, 3, zFront + 1, "red_wool");
		this.wallSign(cx, 4, zFront + 1, Direction.SOUTH, "", "NICHOLS", "HARDWARE", "since 1946");
		this.fill(x0 + 1, -1, zFront - 4, x1 - 1, -1, zFront - 1, b("polished_andesite"));
	}

	/** Something on a hardware store shelf. */
	private BlockState goods() {
		return b(this.pick("flower_pot", "iron_chain", "iron_bars", "cauldron", "lightning_rod", "candle", "anvil", "grindstone",
			"stonecutter", "loom", "fletching_table", "composter", "brewing_stand", "decorated_pot", "bookshelf", "hay_block"));
	}

	/** A striped awning along a shop front, at height {@code y}, one block out from the wall. */
	private void awning(int x0, int x1, int y, int z, String stripe) {
		for (int x = x0; x <= x1; x++) {
			this.set(x, y, z, b(x % 2 == 0 ? stripe : "white_wool"));
		}
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
		this.set(x0 + 1, 0, zBack + 1, search(HwBlocks.KITCHEN_CABINET, Direction.SOUTH));
		this.set(x1 - 1, 0, zBack + 1, search(HwBlocks.CARDBOARD_BOX, Direction.SOUTH));
		this.door(x1 - 2, 0, zBack, Direction.SOUTH, "birch_door");
		this.bulb(x0 + 6, 4, zFront - 6);
		this.bulb(x1 - 6, 4, zFront - 6);
		this.bulb((x0 + x1) / 2, 4, zFront - 6);
		this.bulb(x0 + 6, 4, zFront - 2);
		this.bulb(x1 - 6, 4, zFront - 2);
		this.bulb(x0 + 6, 4, zBack + 2);
		this.bulb(x1 - 6, 4, zBack + 2);
		this.breaker(x0, 1, zFront - 6, Direction.WEST);
		this.breaker(x1, 1, zFront - 6, Direction.EAST);
		this.sign(x0 + 2, 0, zFront + 3, 0, "", "DINER", "open late", "");
		for (int x = x0 + 1; x <= x1 - 1; x++) {
			for (int z = zBack + 5; z <= zFront - 1; z++) {
				this.set(x, -1, z, b((x + z) % 2 == 0 ? "black_concrete" : "white_concrete"));
			}
		}
		for (int x = x0 + 3; x <= x1 - 3; x += 4) {
			BlockState table = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, 0, zFront - 2));
			if (table != null && table.is(net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE_SLAB)) {
				this.set(x, 1, zFront - 2, b("candle"));
			}
		}
		this.set(x0 + 5, 1, zBack + 4, b("cake"));
		this.set(x0 + 7, 1, zBack + 4, b("brewing_stand"));
		this.set(x1 - 5, 1, zBack + 4, b("observer").setValue(BlockStateProperties.FACING, Direction.UP));
		this.set(x0 + 4, 0, zBack + 1, facing("smoker", Direction.SOUTH));
		this.set(x0 + 5, 0, zBack + 1, facing("furnace", Direction.SOUTH));
		this.set(x0 + 6, 0, zBack + 1, b("cauldron"));
		this.fill(x0 + 4, 2, zBack + 1, x0 + 6, 2, zBack + 1, b("iron_bars"));
		this.set(x1 - 1, 0, zBack + 8, b("jukebox"));
		// A red band round the walls and the menu board over the kitchen hatch.
		for (int x = x0; x <= x1; x++) {
			this.set(x, 3, zBack, b("red_terracotta"));
		}
		for (int z = zBack; z <= zFront; z++) {
			this.set(x0, 3, z, b("red_terracotta"));
			this.set(x1, 3, z, b("red_terracotta"));
		}
		this.fill(x0 + 8, 2, zBack, x1 - 8, 3, zBack, b("black_concrete"));
		this.wallSign((x0 + x1) / 2, 2, zBack + 1, Direction.SOUTH, "TODAY", "pie 25c", "coffee 15c", "burger 85c");
		for (int x = x0 + 2; x <= x1 - 2; x += 5) {
			this.set(x, 2, zFront - 1, b(this.pick("red", "white", "black") + "_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
		}
		this.awning(x0, x1, 3, zFront + 1, "red_wool");
		this.wallSign((x0 + x1) / 2, 4, zFront + 1, Direction.SOUTH, "", "HADDONFIELD", "DINER", "");
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
		this.set(sx1 - 1, 0, zb + 1, search(HwBlocks.TOOL_CABINET, Direction.WEST));
		this.set(sx1 - 1, 0, zb + 2, search(HwBlocks.CARDBOARD_BOX, Direction.WEST));
		this.locker(sx0 + 1, 0, zb + 1, Direction.SOUTH);
		this.bulb((sx0 + sx1) / 2, 4, zb + 4);
		this.bulb(sx0 + 4, 4, zf - 2);
		this.bulb(sx1 - 4, 4, zf - 2);
		this.breaker(sx1, 1, zb + 4, Direction.EAST);
		this.sign(x0 + 1, 0, zRoad - 1, 0, "", "GAS", "29.9c", "");
		int shopMid = (sx0 + sx1) / 2;
		for (int x = sx0 + 7; x <= sx1 - 4; x++) {
			if (Math.abs(x - shopMid) <= 1) {
				continue;
			}
			this.set(x, 0, zb + 6, b("spruce_planks"));
			this.set(x, 1, zb + 6, b(this.pick("flower_pot", "cauldron", "candle", "decorated_pot", "hay_block")));
		}
		this.fill(sx1 - 1, 0, zf - 3, sx1 - 1, 1, zf - 2, b("white_concrete"));
		this.set(sx0 + 2, 1, zb + 3, b("observer").setValue(BlockStateProperties.FACING, Direction.UP));
		for (int i = 0; i < 3; i++) {
			this.fill(x0 + 1 + i, 0, zRoad - 13, x0 + 1 + i, 1 - (i % 2), zRoad - 13, b("black_concrete"));
			this.set(x1 - 1, 0, zRoad - 13 + i, b("cauldron"));
		}
		for (int x : new int[]{x0 + 9, x1 - 9}) {
			this.set(x, 2, zRoad - 6, b("iron_chain"));
		}
		this.wallSign(shopMid, 3, zf + 1, Direction.SOUTH, "", "GAS & SNACKS", "", "");
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
				// Desks in rows, the teacher's desk with a filing cabinet, a blackboard.
				for (int[] side : new int[][]{{zBack + 2, zc - 5}, {zc + 5, zFront - 2}}) {
					for (int z = side[0] + 2; z <= side[1] - 1; z += 3) {
						for (int xx = x + 2; xx <= x + roomW - 3; xx += 3) {
							this.set(xx, 0, z, slab("oak_slab", SlabType.TOP));
						}
					}
				}
				// Chairs behind the desks, facing the blackboard on the corridor wall.
				for (int z = zBack + 4; z <= zc - 6; z += 3) {
					for (int xx = x + 2; xx <= x + roomW - 3; xx += 3) {
						this.set(xx, 0, z - 1, stairs("oak_stairs", Direction.NORTH));
					}
				}
				for (int z = zc + 7; z <= zFront - 3; z += 3) {
					for (int xx = x + 2; xx <= x + roomW - 3; xx += 3) {
						this.set(xx, 0, z + 1, stairs("oak_stairs", Direction.SOUTH));
					}
				}
				// The teacher's desk by the board, bookshelves on a side wall, a picture.
				this.fill(x + 10, 0, zc - 5, x + 11, 0, zc - 5, b("dark_oak_planks"));
				this.set(x + 10, 1, zc - 5, b("flower_pot"));
				this.fill(x + 10, 0, zc + 5, x + 11, 0, zc + 5, b("dark_oak_planks"));
				this.set(x + 11, 1, zc + 5, b("candle"));
				if (r > 0) {
					this.fill(x + 1, 0, zBack + 2, x + 1, 1, zBack + 3, b("bookshelf"));
				}
				this.set(x + 6, 2, zBack + 1, b(this.pick("orange", "yellow", "lime", "light_blue") + "_wall_banner")
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
				this.set(x + 2, 0, zBack + 2, search(HwBlocks.FILING_CABINET, Direction.SOUTH));
				this.set(x + 2, 0, zFront - 2, search(HwBlocks.FILING_CABINET, Direction.NORTH));
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
		// The library (first room on the north side): rows of shelves instead of desks.
		for (int z = zBack + 3; z <= zc - 6; z += 3) {
			this.fill(x0 + 2, 0, z, x0 + roomW - 3, 1, z, b("bookshelf"));
			this.fill(x0 + 2, 0, z + 1, x0 + roomW - 3, 1, z + 1, AIR);
		}
		this.fill(x0 + 6, 0, zBack + 3, x0 + 7, 1, zc - 5, AIR);
		this.fill(x0 + 7, 0, zc - 4, x0 + 8, 1, zc - 4, AIR);
		// The cafeteria (last room on the south side): long tables with benches.
		for (int z = zc + 6; z <= zFront - 4; z += 4) {
			this.fill(x1 - roomW + 2, 0, z, x1 - 3, 0, z, AIR);
			this.fill(x1 - roomW + 2, 0, z + 1, x1 - 3, 0, z + 1, slab("spruce_slab", SlabType.TOP));
			this.fill(x1 - roomW + 2, 0, z + 2, x1 - 3, 0, z + 2, stairs("spruce_stairs", Direction.SOUTH));
			this.fill(x1 - roomW + 2, 0, z, x1 - 3, 0, z, stairs("spruce_stairs", Direction.NORTH));
			this.fill(x1 - roomW + 2, 0, z + 3, x1 - 3, 0, z + 3, AIR);
		}
		// School colours along the corridor, a drinking fountain by the entrance.
		// The janitor's room (last on the north side) has no desks: brooms, buckets and the gun case.
		this.fill(x1 - roomW + 1, 0, zBack + 1, x1 - 1, 1, zc - 5, AIR);
		this.set(x1 - 4, 0, zBack + 2, b("cauldron"));
		this.set(x1 - 6, 0, zBack + 1, b("iron_chain"));
		for (int x = x0 + 3; x <= x1 - 3; x += 6) {
			if (Math.floorMod(x - x0, roomW) == 7 || Math.floorMod(x - x0, roomW) == 8) {
				continue;
			}
			this.set(x, 2, zc - 2, b(x % 4 == 1 ? "orange_wall_banner" : "black_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
		}
		this.set(3, 0, zc + 2, b("cauldron"));
		// Breakers along the corridor (each reaches 14 blocks).
		// (The middle one beside the entrance hall, which takes the corridor wall at -2..1.)
		for (int x : new int[]{x0 + 14, -5, x1 - 18}) {
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
		this.set(x1 - 2, 0, zBack + 2, search(HwBlocks.TOOL_CABINET, Direction.WEST));
		this.set(x1 - 2, 0, zBack + 4, search(HwBlocks.CARDBOARD_BOX, Direction.WEST));
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
		this.set(cx, 0, cz - 3, search(HwBlocks.CRATE, Direction.SOUTH));
		this.fill(cx - 2, 0, cz - 1, cx + 2, 0, cz - 1, b("polished_andesite"));
		for (int i = 0; i < 9; i++) {
			int x = x0 + 6 + this.random.nextInt(x1 - x0 - 12);
			int z = z0 + 6 + this.random.nextInt(z1 - z0 - 12);
			if (this.freeArea(x - 1, z - 1, x + 1, z + 1)) {
				this.deadTree(x, z);
			}
		}
		this.sign(-114, 0, z1 + 2, 0, "HADDONFIELD", "MEMORIAL", "CEMETERY", "");
		for (int x = x0 + 4; x <= x1 - 4; x += 3) {
			for (int z = z0 + 6; z <= z1 - 6; z += 4) {
				BlockState stone = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, 0, z));
				if (stone != null && stone.getBlock() instanceof net.minecraft.world.level.block.WallBlock) {
					if (this.random.nextInt(4) == 0) {
						this.set(x, 0, z + 1, b(this.pick("poppy", "dandelion", "oxeye_daisy", "wither_rose")));
					} else if (this.random.nextInt(8) == 0) {
						this.set(x, -1, z + 1, b("coarse_dirt"));
						this.set(x, -1, z + 2, b("rooted_dirt"));
					}
				}
			}
		}
		// Judith Myers, 1963.
		this.fill(-104, 0, -82, -104, 1, -82, b("chiseled_stone_bricks"));
		this.wallSign(-104, 1, -81, Direction.SOUTH, "JUDITH", "MARGARET", "MYERS", "1947-1963");
		this.set(-104, 0, -81, b("poppy"));
		// Inside the crypt: two stone coffins, candles, cobwebs.
		this.fill(cx - 2, 0, cz + 1, cx - 2, 0, cz + 2, slab("polished_andesite_slab", SlabType.TOP));
		this.fill(cx + 2, 0, cz + 1, cx + 2, 0, cz + 2, slab("polished_andesite_slab", SlabType.TOP));
		this.set(cx - 2, 1, cz - 1, b("candle"));
		this.set(cx + 2, 1, cz - 1, b("candle"));
		this.set(cx - 3, 3, cz - 4, b("cobweb"));
		this.set(cx + 3, 3, cz + 4, b("cobweb"));
		this.set(cx, 3, cz, b("soul_lantern").setValue(BlockStateProperties.HANGING, true));
		this.set(cx, 4, cz, b("mossy_stone_bricks"));
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
		// The groundskeeper's shed with a tool chest and a crate; a storm cellar behind it.
		int sx = x0 + 10;
		int sz = z1 - 16;
		this.fill(sx, -1, sz, sx + 6, -1, sz + 5, b("spruce_planks"));
		this.fill(sx, 0, sz, sx + 6, 3, sz + 5, b("spruce_planks"));
		this.fill(sx + 1, 0, sz + 1, sx + 5, 2, sz + 4, AIR);
		this.fill(sx, 4, sz, sx + 6, 4, sz + 5, slab("spruce_slab", SlabType.BOTTOM));
		this.door(sx + 3, 0, sz, Direction.SOUTH, "spruce_door");
		this.set(sx + 1, 1, sz + 2, b("glass_pane"));
		this.set(sx + 1, 2, sz + 2, b("glass_pane"));
		this.set(sx + 5, 0, sz + 4, search(HwBlocks.TOOL_CABINET, Direction.WEST));
		this.set(sx + 5, 0, sz + 1, search(HwBlocks.CRATE, Direction.WEST));
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
		// Halloween fair corner: hay bales, pumpkins, scarecrows, a stage.
		int fx = cx + 12;
		int fz = cz - 18;
		for (int i = 0; i < 14; i++) {
			int x = fx + this.random.nextInt(14);
			int z = fz + this.random.nextInt(12);
			if (this.free(x, z)) {
				this.set(x, 0, z, b(this.pick("hay_block", "pumpkin", "carved_pumpkin", "hay_block")));
			}
		}
		for (int k = 0; k < 2; k++) {
			int sx2 = fx + 3 + k * 8;
			int sz2 = fz + 6;
			if (this.freeArea(sx2 - 1, sz2, sx2 + 1, sz2)) {
				this.set(sx2, 0, sz2, b("dark_oak_fence"));
				this.set(sx2, 1, sz2, b("hay_block"));
				this.set(sx2, 2, sz2, facing("carved_pumpkin", Direction.SOUTH));
				this.set(sx2 - 1, 1, sz2, b("dark_oak_fence"));
				this.set(sx2 + 1, 1, sz2, b("dark_oak_fence"));
			}
		}
		this.fill(fx, -1, fz + 9, fx + 8, -1, fz + 12, b("spruce_planks"));
		this.fill(fx, 0, fz + 9, fx + 8, 0, fz + 12, slab("spruce_slab", SlabType.BOTTOM));
		this.fill(fx, 0, fz + 13, fx + 8, 2, fz + 13, b("orange_wool"));
		this.fill(fx + 1, 3, fz + 13, fx + 7, 3, fz + 13, b("black_wool"));
		// Picnic tables and flower beds along the paths.
		for (int i = 0; i < 6; i++) {
			int x = x0 + 8 + this.random.nextInt(x1 - x0 - 16);
			int z = z0 + 8 + this.random.nextInt(z1 - z0 - 16);
			if (this.freeArea(x - 1, z - 2, x + 3, z + 2)) {
				this.fill(x, 0, z, x + 2, 0, z, slab("spruce_slab", SlabType.TOP));
				this.fill(x, 0, z - 1, x + 2, 0, z - 1, stairs("spruce_stairs", Direction.NORTH));
				this.fill(x, 0, z + 1, x + 2, 0, z + 1, stairs("spruce_stairs", Direction.SOUTH));
			}
		}
		for (int x = cx - 8; x <= cx + 9; x++) {
			for (int z : new int[]{cz - 6, cz + 7}) {
				if (this.free(x, z) && Math.abs(x - cx) > 1) {
					this.set(x, 0, z, b(this.pick("poppy", "orange_tulip", "red_tulip", "dandelion", "allium")));
				}
			}
		}
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
			String glass = this.pick("red", "blue", "yellow", "purple", "green") + "_stained_glass_pane";
			this.fill(x0, 1, z, x0, 3, z, b(glass));
			this.fill(x1, 1, z, x1, 3, z, b(glass));
		}
		this.fill(-1, -1, z0 + 1, 1, -1, z1 - 5, b("red_wool"));
		// The organ in the back corner: a console and its pipes.
		this.set(x0 + 2, 0, z1 - 5, b("dark_oak_planks"));
		this.set(x0 + 2, 1, z1 - 5, b("note_block"));
		this.fill(x0 + 1, 0, z1 - 4, x0 + 1, 4, z1 - 4, b("iron_bars"));
		this.fill(x0 + 1, 0, z1 - 6, x0 + 1, 3, z1 - 6, b("iron_bars"));
		this.fill(-1, 15, z0 - 2, 1, 16, z0, AIR);
		this.set(0, 15, z0 - 1, b("bell"));
		this.fill(-1, 2, z1 - 1, 1, 2, z1 - 1, b("purple_wall_banner").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
		this.set(0, 1, z1 - 4, b("candle").setValue(BlockStateProperties.CANDLES, 3));
		this.set(-2, 1, z1 - 4, b("candle"));
		this.set(2, 1, z1 - 4, b("candle"));
		// Pews either side of the aisle, the altar at the back.
		for (int z = z0 + 3; z <= z1 - 8; z += 2) {
			this.fill(x0 + 2, 0, z, -3, 0, z, stairs("dark_oak_stairs", Direction.NORTH));
			this.fill(3, 0, z, x1 - 2, 0, z, stairs("dark_oak_stairs", Direction.NORTH));
		}
		this.fill(-3, 0, z1 - 4, 3, 0, z1 - 4, b("polished_diorite"));

		this.set(-4, 0, z1 - 2, search(HwBlocks.DRESSER, Direction.NORTH));
		this.set(x1 - 1, 1, z1 - 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		this.set(x1 - 1, 0, z1 - 2, b("dark_oak_planks"));
		this.point(MapPoints.Type.PHONE, x1 - 1, 1, z1 - 2);
		this.locker(x0 + 1, 0, z1 - 2, Direction.EAST);
		this.bulb(0, 6, z0 + 8);
		this.bulb(0, 6, z1 - 8);
		for (int z = z0 + 5; z <= z1 - 5; z += 7) {
			this.bulb(-6, 6, z);
			this.bulb(6, 6, z);
		}
		this.breaker(x0, 1, (z0 + z1) / 2, Direction.WEST);
		this.breaker(x1, 1, (z0 + z1) / 2, Direction.EAST);
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
		this.set(sx + 1, 0, sz + 1, search(HwBlocks.CRATE, Direction.SOUTH));
		this.set(sx + 5, 0, sz + 1, search(HwBlocks.TOOL_CABINET, Direction.SOUTH));
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

	/** A street 7 wide with a dashed centre line, a manhole cover now and then, and sidewalks, running the whole way between the fences. */
	private void street(boolean eastWest, int c) {
		for (int t = -FENCE + 1; t <= FENCE - 1; t++) {
			for (int o = -5; o <= 5; o++) {
				BlockState s = Math.abs(o) >= 4 ? b("smooth_stone") : o == 0 && Math.floorMod(t, 6) < 3 ? b("yellow_terracotta")
					: Math.abs(o) == 2 && Math.floorMod(t + c + (o > 0 ? 19 : 0), 41) == 0 ? b("chiseled_stone_bricks") : b("gray_concrete");
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

	/**
	 * Telephone poles with wires along one side of every street, fire hydrants, stop signs at the
	 * crossings, benches and trash cans on Main Street.
	 */
	private void streetDecor() {
		for (int c : new int[]{ORANGE_GROVE, LAMPKIN, MAIN}) {
			this.poles(true, c, c + 5);
			for (int x = -FENCE + 18; x <= FENCE - 10; x += 40) {
				this.onSidewalk(x, c - 5, b("red_nether_brick_wall"));
			}
		}
		for (int c : new int[]{ELM, HACKBERRY}) {
			this.poles(false, c, c + 5);
			for (int z = -FENCE + 30; z <= FENCE - 10; z += 40) {
				this.onSidewalk(c - 5, z, b("red_nether_brick_wall"));
			}
		}
		for (int z : new int[]{ORANGE_GROVE, LAMPKIN, MAIN}) {
			for (int x : new int[]{ELM, HACKBERRY}) {
				int[][] corners = {{-6, -6, 8}, {6, -6, 8}, {-6, 6, 0}, {6, 6, 0}};
				for (int[] k : corners) {
					if (this.free(x + k[0], z + k[1])) {
						this.set(x + k[0], 0, z + k[1], b("crimson_sign").setValue(BlockStateProperties.ROTATION_16, k[2]));
						this.signs.put(new net.minecraft.core.BlockPos(x + k[0], 0, z + k[1]), new String[]{"", "STOP", "", ""});
					}
				}
			}
		}
		for (int x = -50; x <= 50; x += 25) {
			this.onSidewalk(x, MAIN - 5, stairs("dark_oak_stairs", Direction.NORTH));
			this.onSidewalk(x + 1, MAIN - 5, stairs("dark_oak_stairs", Direction.NORTH));
			this.onSidewalk(x + 3, MAIN - 5, b("cauldron"));
		}
	}

	/**
	 * A town map board at the street's edge, facing south over the sidewalk: two posts, a backing where the twelve maps hang (see MapBoards: they are drawn at every match), a little roof, a header and
	 * a legend sign on the west post. {@code x0}: the board's west square (it is MapBoards.TX wide, MapBoards.TY maps high).
	 */
	private void mapBoard(int x0, int z) {
		int w = MapBoards.TX;
		int h = MapBoards.TY;
		this.fill(x0 - 1, 0, z, x0 - 1, h + 1, z, b("dark_oak_log"));
		this.fill(x0 + w, 0, z, x0 + w, h + 1, z, b("dark_oak_log"));
		this.fill(x0, 0, z, x0 + w - 1, h + 1, z, b("dark_oak_planks"));
		this.fill(x0 - 1, h + 2, z - 1, x0 + w, h + 2, z + 1, slab("dark_oak_slab", SlabType.BOTTOM));
		this.fill(x0 - 1, 0, z + 1, x0 + w, h + 1, z + 1, AIR);
		this.wallSign(x0 - 1, 1, z + 1, Direction.SOUTH, "", "", "", "");
		this.wallSign(x0 + 1, h + 1, z + 1, Direction.SOUTH, "HADDONFIELD", "town map", "", "");
		this.point(MapPoints.Type.MAP_BOARD, x0, 1, z);
	}

	/** "Welcome to Haddonfield" board on two posts beside a road into town, facing {@code facing}. */
	private void welcome(int x, int z, Direction facing) {
		if (!this.freeArea(x - 1, z - 2, x + 1, z + 2)) {
			return;
		}
		boolean alongZ = facing.getAxis() == Direction.Axis.X;
		for (int k = -2; k <= 2; k++) {
			int bx = alongZ ? x : x + k;
			int bz = alongZ ? z + k : z;
			if (Math.abs(k) == 2) {
				this.fill(bx, 0, bz, bx, 3, bz, b("dark_oak_log"));
			} else {
				this.fill(bx, 2, bz, bx, 3, bz, b("white_concrete"));
			}
		}
		int sx = x + facing.getStepX();
		int sz = z + facing.getStepZ();
		this.wallSign(sx, 3, sz, facing, "WELCOME TO", "HADDONFIELD", "Illinois", "pop. 4,723");
		this.set(sx, 0, sz, b("carved_pumpkin").setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
	}

	/** Something standing on the outer edge of a sidewalk (only on bare sidewalk). */
	private void onSidewalk(int x, int z, BlockState state) {
		BlockState under = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, -1, z));
		BlockState here = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, 0, z));
		if (under != null && under.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE) && (here == null || here.isAir())) {
			this.set(x, 0, z, state);
		}
	}

	/** Wooden poles every 16 blocks on a sidewalk edge, chains strung between them at the top. */
	private void poles(boolean eastWest, int road, int edge) {
		int lastPole = Integer.MIN_VALUE;
		for (int t = -FENCE + 4; t <= FENCE - 4; t++) {
			int x = eastWest ? t : edge;
			int z = eastWest ? edge : t;
			if (Math.floorMod(t, 16) == 0) {
				BlockState under = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, -1, z));
				BlockState here = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, 0, z));
				if (under != null && under.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE) && (here == null || here.isAir())) {
					this.fill(x, 0, z, x, 6, z, b("spruce_fence"));
					this.set(x, 7, z, slab("spruce_slab", SlabType.BOTTOM));
					lastPole = t;
					continue;
				}
			}
			if (lastPole != Integer.MIN_VALUE && t - lastPole < 17) {
				this.set(x, 6, z, b("iron_chain").setValue(BlockStateProperties.AXIS, eastWest ? Direction.Axis.X : Direction.Axis.Z));
			}
		}
	}

	/**
	 * Street lamps every 24 blocks, alternating sides; one in five has burnt out. Each street has its own kind
	 * of post: wooden on Orange Grove, stone on Lampkin Lane, black iron on Main Street, cobblestone on the
	 * cross streets.
	 */
	private void lamps() {
		String[] posts = {"dark_oak_fence", "stone_brick_wall", "polished_deepslate_wall"};
		int[] streets = {ORANGE_GROVE, LAMPKIN, MAIN};
		for (int s = 0; s < streets.length; s++) {
			int z = streets[s];
			for (int x = -FENCE + 10, i = 0; x <= FENCE - 10; x += 24, i++) {
				this.lamp(x, z + (i % 2 == 0 ? -5 : 5), i % 5 == 3, posts[s]);
			}
		}
		for (int x : new int[]{ELM, HACKBERRY}) {
			for (int z = -FENCE + 22, i = 0; z <= FENCE - 10; z += 24, i++) {
				this.lamp(x + (i % 2 == 0 ? -5 : 5), z, i % 5 == 1, "cobblestone_wall");
			}
		}
	}

	private void lamp(int x, int z, boolean broken, String post) {
		BlockState under = this.blocks.get(net.minecraft.core.BlockPos.asLong(x, -1, z));
		if (under == null || !under.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE)) {
			return;
		}
		this.fill(x, 0, z, x, 2, z, b(post));
		this.set(x, 3, z, broken ? b(post) : b("lantern"));
	}

	/**
	 * Trees along the streets, just inside the yards behind the sidewalk: autumn oaks, dark oaks, birches,
	 * pale oaks hung with moss, a bare one here and there; fallen leaves under them. Kept clear of paths, drives,
	 * fences and each other.
	 */
	private void streetTrees() {
		for (int road : new int[]{ORANGE_GROVE, LAMPKIN, MAIN}) {
			for (int side : new int[]{-1, 1}) {
				int z = road + side * 8;
				for (int x = -FENCE + 6 + this.random.nextInt(5); x <= FENCE - 6; x += 9 + this.random.nextInt(6)) {
					this.streetTree(x, z);
				}
			}
		}
		for (int road : new int[]{ELM, HACKBERRY}) {
			for (int side : new int[]{-1, 1}) {
				int x = road + side * 8;
				for (int z = -FENCE + 6 + this.random.nextInt(5); z <= FENCE - 6; z += 9 + this.random.nextInt(6)) {
					this.streetTree(x, z);
				}
			}
		}
	}

	private void streetTree(int x, int z) {
		if (!this.freeArea(x - 2, z - 2, x + 2, z + 2)) {
			return;
		}
		// No spruce here: its lowest branches would hang over the sidewalk at head height.
		int kind = this.yardTreeKind();
		this.tree(x, z, kind == 2 ? 0 : kind);
		for (int i = 0; i < 6; i++) {
			int lx = x + this.random.nextInt(5) - 2;
			int lz = z + this.random.nextInt(5) - 2;
			if (this.free(lx, lz)) {
				this.set(lx, 0, lz, this.leafLitter(1 + this.random.nextInt(4)));
			}
		}
	}

	/** A tree for a yard: mostly autumn oaks, some dark oaks, birches and pale oaks, a spruce, a bare tree now and then. */
	private int yardTreeKind() {
		int r = this.random.nextInt(11);
		return r < 3 ? 0 : r < 5 ? 1 : r < 6 ? 2 : r < 8 ? 3 : r < 10 ? 4 : 5;
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
	/** Where a gate can stand: on the edge where Lampkin Lane, Orange Grove, Elm and Hackberry leave town. */
	private static final int[][] GATES = {{-FENCE, LAMPKIN}, {FENCE, ORANGE_GROVE}, {ELM, FENCE}, {HACKBERRY, -FENCE}};
	/** A gate lane runs this deep into the trees (and is this many blocks to either side of the gate). */
	private static final int LANE_DEPTH = 6;
	private static final int LANE_HALF = 3;

	/**
	 * The edge of town is a forest nobody gets through: right at the edge a hedge of undergrowth two blocks
	 * high (nobody jumps) with an invisible wall above it, behind that dense woods out to the rim. Where a
	 * gate can stand, a lane runs a few blocks into the trees, closed by a barricade unless the match builds
	 * its gate there; the other streets just end in the trees.
	 */
	private void boundary() {
		BlockState barrier = b("barrier");
		BlockState[] bushes = {leaves("oak_leaves"), leaves("dark_oak_leaves"), leaves("spruce_leaves"), leaves("azalea_leaves")};
		for (int x = -RIM; x <= RIM; x++) {
			for (int z = -RIM; z <= RIM; z++) {
				int d = Math.max(Math.abs(x), Math.abs(z)) - FENCE;
				if (d < 0) {
					continue;
				}
				if (inGateLane(x, z) && d <= LANE_DEPTH) {
					this.set(x, -1, z, b("coarse_dirt"));
					this.fill(x, 0, z, x, 6, z, AIR);
					if (d == 0) {
						// The barricade the match's gate replaces.
						this.set(x, 0, z, b("spruce_fence"));
						this.fill(x, 1, z, x, 4, z, barrier);
					}
					continue;
				}
				this.set(x, -1, z, this.random.nextInt(5) == 0 ? b("coarse_dirt") : b("podzol"));
				BlockState bush = bushes[this.random.nextInt(bushes.length)];
				if (d <= 1) {
					// The hedge: two high, no gaps; above it an invisible wall.
					this.set(x, 0, z, bush);
					this.set(x, 1, z, bush);
					if (d == 1) {
						this.fill(x, 2, z, x, 6, z, barrier);
					}
				} else if (this.random.nextInt(100) < 60) {
					this.set(x, 0, z, bush);
					if (this.random.nextBoolean()) {
						this.set(x, 1, z, bush);
					}
				} else if (this.random.nextInt(4) == 0) {
					this.set(x, 0, z, b(this.random.nextBoolean() ? "fern" : "dead_bush"));
				}
			}
		}
		// Trunks all through it, close together, crowns meeting overhead.
		for (int gx = -RIM; gx <= RIM; gx += 3) {
			for (int gz = -RIM; gz <= RIM; gz += 3) {
				int x = gx + this.random.nextInt(2);
				int z = gz + this.random.nextInt(2);
				int d = Math.max(Math.abs(x), Math.abs(z)) - FENCE;
				if (d < 1 || Math.abs(x) > RIM || Math.abs(z) > RIM || inGateLane(x, z) && d <= LANE_DEPTH + 1) {
					continue;
				}
				int kind = this.random.nextInt(3);
				this.tree(x, z, kind);
				this.set(x, -1, z, b("podzol"));
			}
		}
	}

	/** Is this column in one of the lanes leading to a gate spot (from the edge outwards)? */
	private static boolean inGateLane(int x, int z) {
		for (int[] g : GATES) {
			if (Math.abs(g[0]) == FENCE && Integer.signum(x) == Integer.signum(g[0]) && Math.abs(x) >= FENCE && Math.abs(z - g[1]) <= LANE_HALF) {
				return true;
			}
			if (Math.abs(g[1]) == FENCE && Integer.signum(z) == Integer.signum(g[1]) && Math.abs(z) >= FENCE && Math.abs(x - g[0]) <= LANE_HALF) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- nature

	/** 0: oak, 1: dark oak, 2: spruce, 3: birch, 4: pale oak hung with moss, 5: a bare tree. */
	private void tree(int x, int z, int kind) {
		if (kind == 5) {
			this.bareTree(x, z);
			return;
		}
		int h = 5 + this.random.nextInt(3);
		String log = switch (kind) {
			case 1 -> "dark_oak_log";
			case 2 -> "spruce_log";
			case 3 -> "birch_log";
			case 4 -> "pale_oak_log";
			default -> "oak_log";
		};
		BlockState leaves = leaves(switch (kind) {
			case 1 -> "dark_oak_leaves";
			case 2 -> "spruce_leaves";
			case 3 -> "birch_leaves";
			case 4 -> "pale_oak_leaves";
			default -> "oak_leaves";
		});
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
		if (kind == 4) {
			// Pale moss hanging from the crown.
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if ((dx != 0 || dz != 0) && this.random.nextInt(3) == 0) {
						BlockState above = this.blocks.get(net.minecraft.core.BlockPos.asLong(x + dx, h - 2, z + dz));
						if (above != null && above.is(net.minecraft.world.level.block.Blocks.PALE_OAK_LEAVES)
							&& !this.blocks.containsKey(net.minecraft.core.BlockPos.asLong(x + dx, h - 3, z + dz))) {
							this.set(x + dx, h - 3, z + dz, b("pale_hanging_moss"));
						}
					}
				}
			}
		}
	}

	/** A bare tree: a trunk and a few crooked branches, its leaves all down. */
	private void bareTree(int x, int z) {
		int h = 5 + this.random.nextInt(2);
		this.fill(x, 0, z, x, h, z, b("dark_oak_log"));
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (this.random.nextInt(4) > 0) {
				int y = h - 1 - this.random.nextInt(2);
				this.setLeaf(x + d.getStepX(), y, z + d.getStepZ(), b("dark_oak_fence"));
				this.setLeaf(x + 2 * d.getStepX(), y + 1, z + 2 * d.getStepZ(), b("dark_oak_fence"));
			}
		}
		this.setLeaf(x, h + 1, z, b("dark_oak_fence"));
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

	/** Fallen leaves and the odd pumpkin on the lawns; muddy patches where the rain stood; heaps of raked leaves. */
	private void autumn() {
		for (int i = 0; i < 60; i++) {
			int x = -FENCE + 4 + this.random.nextInt(2 * FENCE - 8);
			int z = -FENCE + 4 + this.random.nextInt(2 * FENCE - 8);
			int size = 1 + this.random.nextInt(2);
			if (this.freeArea(x - size, z - size, x + size, z + size)) {
				for (int dx = -size; dx <= size; dx++) {
					for (int dz = -size; dz <= size; dz++) {
						if (Math.abs(dx) + Math.abs(dz) <= size + this.random.nextInt(2)) {
							this.set(x + dx, -1, z + dz, b("mud"));
						}
					}
				}
			}
		}
		for (int i = 0; i < 70; i++) {
			int x = -FENCE + 4 + this.random.nextInt(2 * FENCE - 8);
			int z = -FENCE + 4 + this.random.nextInt(2 * FENCE - 8);
			if (this.freeArea(x - 1, z - 1, x + 1, z + 1)) {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						this.set(x + dx, 0, z + dz, this.leafLitter(dx == 0 && dz == 0 ? 4 : 2 + this.random.nextInt(3)));
					}
				}
			}
		}
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
