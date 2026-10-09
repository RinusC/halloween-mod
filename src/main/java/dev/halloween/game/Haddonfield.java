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
	/** The varied town (/hw map town): the same streets, the lots built up differently. */
	private final boolean varied;

	public Haddonfield() {
		this(false);
	}

	public Haddonfield(boolean varied) {
		super(RIM, -4, 40);
		this.varied = varied;
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
		this.clearCarSpots();
	}

	/** Parking is kept clear (no pumpkins or leaves where the car is built at match start: 7x3, three high). */
	private void clearCarSpots() {
		for (Object[] p : this.points) {
			if (p[0] == MapPoints.Type.CAR) {
				net.minecraft.core.BlockPos at = (net.minecraft.core.BlockPos) p[1];
				this.fill(at.getX() - 3, at.getY(), at.getZ() - 1, at.getX() + 3, at.getY() + 2, at.getZ() + 1, AIR);
			}
		}
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
		if (this.varied) {
			this.variedLots();
			return;
		}
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

		void locker(int u, int y, int v, Direction facing) {
			Haddonfield.this.locker(this.x(u), y, this.z(v), this.mirror ? net.minecraft.world.level.block.Mirror.FRONT_BACK.mirror(facing) : facing);
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
		// In the corner past the fridge: it opens towards the front of the house, not into the fridge.
		f.locker(w - 2, d - 2, f.front());
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
		if (this.varied) {
			// The second town has no flat roofs: the hardware store and the diner get pitched ones.
			this.gable(lot('E', 3).x0 + 2, lot('E', 4).x1 - 2, MAIN - 12 - 17, MAIN - 12, 5, "dark_oak_stairs", "dark_oak_slab", b("bricks"));
			this.gable(lot('E', 5).x0 + 2, lot('E', 5).x1 - 2, MAIN - 12 - 13, MAIN - 12, 5, "brick_stairs", "brick_slab", b("white_terracotta"));
		}
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
		// The car stands at the east pump, under the canopy (it doesn't fit between the canopy and the shop).
		this.point(MapPoints.Type.CAR, x1 - 5, 0, zRoad - 6);
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

	// ================================================================ the varied town (/hw map town)
	//
	// The same streets, park, cemetery, school, church and fence, but the lots are built up differently: houses of
	// several sizes (cottages, bungalows, big two-storey houses with a garage, pairs of townhouses) whose rooms are
	// laid out afresh every time, and a downtown with shops and flats above them, a cinema, police and fire
	// stations, a post office, a clinic, and an industrial end with a warehouse, a garage, a scrapyard and a water
	// tower. Haddonfield itself (/hw map haddonfield) is drawn exactly as before.

	private void variedLots() {
		// Row A, north side of Orange Grove (cemetery and school take the west and middle): the industrial end.
		this.variedHouse(lot('A', 7));
		this.waterTower(lot('A', 8));
		this.junkyard(lot('A', 9));
		// Row B, south side of Orange Grove.
		this.variedHouse(lot('B', 0));
		this.yardTrees(lot('B', 1));
		this.cottage(lot('B', 2));
		this.mansion(lot('B', 3));
		this.bungalow(lot('B', 4));
		this.yardTrees(lot('B', 5));
		this.townhouses(lot('B', 6));
		this.warehouse(lot('B', 7));
		this.autoShop(lot('B', 8));
		this.variedHouse(lot('B', 9));
		// Row C, north side of Lampkin Lane: the Myers house and the Strode house stay.
		this.variedHouse(lot('C', 0));
		this.house(lot('C', 1), 2, true, MYERS, "Myers", true);
		this.yardTrees(lot('C', 2));
		this.cottage(lot('C', 3));
		this.townhouses(lot('C', 4));
		this.house(lot('C', 5), 2, true, STYLES[3], "Strode", false);
		this.mansion(lot('C', 6));
		this.yardTrees(lot('C', 7));
		this.bungalow(lot('C', 8));
		this.cottage(lot('C', 9));
		// Row D, south side of Lampkin Lane: the town's services in the middle.
		this.mansion(lot('D', 0));
		this.yardTrees(lot('D', 1));
		this.cottage(lot('D', 2));
		this.yardTrees(lot('D', 3));
		this.fireStation(lot('D', 4));
		this.postOffice(lot('D', 5));
		this.clinic(lot('D', 6));
		this.yardTrees(lot('D', 7));
		this.cottage(lot('D', 8));
		this.variedHouse(lot('D', 9));
		// Row E, north side of Main Street (the hardware store, the diner and the gas station in the middle).
		this.storefront(lot('E', 0), Shop.BAR);
		this.storefront(lot('E', 1), Shop.PHARMACY);
		this.yardTrees(lot('E', 2));
		this.cinema(lot('E', 7));
		this.storefront(lot('E', 8), Shop.BARBER);
		this.yardTrees(lot('E', 9));
		// Row F, south side of Main Street (the park takes the west).
		this.policeStation(lot('F', 3));
		this.yardTrees(lot('F', 4));
		this.storefront(lot('F', 5), Shop.BOOKS);
		this.storefront(lot('F', 6), Shop.GROCERY);
		this.townhouses(lot('F', 7));
		this.variedHouse(lot('F', 8));
		this.yardTrees(lot('F', 9));
	}

	private void variedHouse(Lot lot) {
		switch (this.random.nextInt(5)) {
			case 0 -> this.cottage(lot);
			case 1 -> this.bungalow(lot);
			case 2 -> this.mansion(lot);
			default -> this.randomHouse(lot);
		}
	}

	/** The direction of +u in the world (east, or west for a house built the other way round): doors don't turn by themselves. */
	private static Direction plusU(Frame f) {
		return f.mirror ? Direction.WEST : Direction.EAST;
	}

	// ---------------------------------------------------------------- rooms laid out afresh

	private enum RoomKind {
		LIVING, KITCHEN, BEDROOM, BATH, OFFICE, STORE
	}

	private static final class Room {
		final int u0;
		final int v0;
		final int u1;
		final int v1;
		RoomKind kind;

		Room(int u0, int v0, int u1, int v1) {
			this.u0 = u0;
			this.v0 = v0;
			this.u1 = u1;
			this.v1 = v1;
		}

		int area() {
			return (this.u1 - this.u0 + 1) * (this.v1 - this.v0 + 1);
		}

		boolean contains(int u, int v) {
			return u >= this.u0 && u <= this.u1 && v >= this.v0 && v <= this.v1;
		}
	}

	private record Split(boolean wallAlongV, int pos, int from, int to) {
	}

	/** A floor's layout: rooms, which cells are partition walls, which must stay clear (doorways and in front of them). */
	private static final class Layout {
		final java.util.List<Room> rooms = new java.util.ArrayList<>();
		final java.util.Set<Long> walls = new java.util.HashSet<>();
		final java.util.Set<Long> clear = new java.util.HashSet<>();
		final java.util.Set<Long> used = new java.util.HashSet<>();
		/** Inside a window: nothing goes there, so it can be climbed through. */
		final java.util.Set<Long> open = new java.util.HashSet<>();

		static long key(int u, int v) {
			return ((long) u << 32) ^ (v & 0xffffffffL);
		}

		boolean free(int u, int v) {
			long k = key(u, v);
			return !this.walls.contains(k) && !this.clear.contains(k) && !this.used.contains(k) && !this.open.contains(k);
		}

		/** Next to the way through a doorway or a door: furniture there would block it. */
		boolean nearClear(int u, int v) {
			return this.clear.contains(key(u + 1, v)) || this.clear.contains(key(u - 1, v)) || this.clear.contains(key(u, v + 1))
				|| this.clear.contains(key(u, v - 1));
		}
	}

	/**
	 * Splits the floor u0..u1 x v0..v1 into rooms with partition walls (at least four blocks each way, room to walk past the furniture), joins
	 * every two halves of a split by a doorway (now and then a door in it), and builds the walls (y .. y+h-1).
	 * {@code keep}: cells no wall may take (stairs, the way in).
	 */
	private Layout partition(Frame f, int u0, int v0, int u1, int v1, int y, int h, BlockState wall, java.util.Set<Long> keep,
		java.util.Set<Integer> noU, java.util.Set<Integer> noV) {
		Layout lay = new Layout();
		java.util.List<Split> splits = new java.util.ArrayList<>();
		this.split(lay, splits, new Room(u0, v0, u1, v1), keep, noU, noV, 0);
		for (Split s : splits) {
			java.util.List<Integer> spots = new java.util.ArrayList<>();
			for (int p = s.from(); p <= s.to(); p++) {
				int u = s.wallAlongV() ? s.pos() : p;
				int v = s.wallAlongV() ? p : s.pos();
				int au = s.wallAlongV() ? u - 1 : u;
				int bu = s.wallAlongV() ? u + 1 : u;
				int av = s.wallAlongV() ? v : v - 1;
				int bv = s.wallAlongV() ? v : v + 1;
				if (lay.walls.contains(Layout.key(u, v)) && !lay.walls.contains(Layout.key(au, av)) && !lay.walls.contains(Layout.key(bu, bv))
					&& !keep.contains(Layout.key(au, av)) && !keep.contains(Layout.key(bu, bv))) {
					spots.add(p);
				}
			}
			if (spots.isEmpty()) {
				continue;
			}
			int p = spots.get(this.random.nextInt(spots.size()));
			int u = s.wallAlongV() ? s.pos() : p;
			int v = s.wallAlongV() ? p : s.pos();
			lay.walls.remove(Layout.key(u, v));
			lay.clear.add(Layout.key(u, v));
			lay.clear.add(Layout.key(s.wallAlongV() ? u - 1 : u, s.wallAlongV() ? v : v - 1));
			lay.clear.add(Layout.key(s.wallAlongV() ? u + 1 : u, s.wallAlongV() ? v : v + 1));
			if (this.random.nextInt(3) == 0) {
				lay.used.add(Layout.key(u, v));
				f.door(u, y, v, s.wallAlongV() ? plusU(f) : f.back());
			}
		}
		for (long k : lay.walls) {
			int u = (int) (k >> 32);
			int v = (int) k;
			f.fill(u, y, v, u, y + h - 1, v, wall);
		}
		// Above each doorway the wall goes on.
		for (Split s : splits) {
			for (int p = s.from(); p <= s.to(); p++) {
				int u = s.wallAlongV() ? s.pos() : p;
				int v = s.wallAlongV() ? p : s.pos();
				if (!lay.walls.contains(Layout.key(u, v)) && h > 2 && !keep.contains(Layout.key(u, v))) {
					f.fill(u, y + 2, v, u, y + h - 1, v, wall);
				}
			}
		}
		return lay;
	}

	/** {@code noU}, {@code noV}: where no wall may run (by a door in the outer wall, by the stairs). */
	private void split(Layout lay, java.util.List<Split> splits, Room r, java.util.Set<Long> keep, java.util.Set<Integer> noU, java.util.Set<Integer> noV,
		int depth) {
		int w = r.u1 - r.u0 + 1;
		int d = r.v1 - r.v0 + 1;
		java.util.List<Integer> us = new java.util.ArrayList<>();
		for (int p = r.u0 + 4; p <= r.u1 - 4; p++) {
			if (!noU.contains(p)) {
				us.add(p);
			}
		}
		java.util.List<Integer> vs = new java.util.ArrayList<>();
		for (int p = r.v0 + 4; p <= r.v1 - 4; p++) {
			if (!noV.contains(p)) {
				vs.add(p);
			}
		}
		boolean canU = !us.isEmpty();
		boolean canV = !vs.isEmpty();
		boolean big = w * d > 42;
		if (!canU && !canV || !big && (depth >= 2 || this.random.nextInt(3) == 0)) {
			lay.rooms.add(r);
			return;
		}
		boolean alongV = canU && (!canV || w > d || w == d && this.random.nextBoolean());
		if (alongV) {
			int pos = us.get(this.random.nextInt(us.size()));
			for (int v = r.v0; v <= r.v1; v++) {
				if (!keep.contains(Layout.key(pos, v))) {
					lay.walls.add(Layout.key(pos, v));
				}
			}
			splits.add(new Split(true, pos, r.v0, r.v1));
			this.split(lay, splits, new Room(r.u0, r.v0, pos - 1, r.v1), keep, noU, noV, depth + 1);
			this.split(lay, splits, new Room(pos + 1, r.v0, r.u1, r.v1), keep, noU, noV, depth + 1);
		} else {
			int pos = vs.get(this.random.nextInt(vs.size()));
			for (int u = r.u0; u <= r.u1; u++) {
				if (!keep.contains(Layout.key(u, pos))) {
					lay.walls.add(Layout.key(u, pos));
				}
			}
			splits.add(new Split(false, pos, r.u0, r.u1));
			this.split(lay, splits, new Room(r.u0, r.v0, r.u1, pos - 1), keep, noU, noV, depth + 1);
			this.split(lay, splits, new Room(r.u0, pos + 1, r.u1, r.v1), keep, noU, noV, depth + 1);
		}
	}

	/** Which room is what: the way in is the living room, the biggest other one the kitchen, a small one the bathroom, the rest bedrooms. */
	private void assignHome(Layout lay, int doorU, int doorV, boolean upstairs) {
		java.util.List<Room> rooms = new java.util.ArrayList<>(lay.rooms);
		rooms.sort((a, b) -> Integer.compare(b.area(), a.area()));
		Room entry = null;
		if (!upstairs) {
			for (Room r : rooms) {
				if (r.contains(doorU, doorV)) {
					entry = r;
				}
			}
			if (entry == null) {
				entry = rooms.getFirst();
			}
			entry.kind = RoomKind.LIVING;
		}
		boolean kitchen = upstairs;
		Room smallest = rooms.getLast();
		for (Room r : rooms) {
			if (r.kind != null) {
				continue;
			}
			if (!kitchen) {
				r.kind = RoomKind.KITCHEN;
				kitchen = true;
			} else if (r == smallest && r.area() <= 16 && rooms.size() > 2) {
				r.kind = RoomKind.BATH;
			} else {
				r.kind = RoomKind.BEDROOM;
			}
		}
		if (!kitchen && entry != null) {
			// One room downstairs: it is kitchen and living room at once.
			entry.kind = RoomKind.KITCHEN;
		}
	}

	/** Free spots along a room's walls, corners first. */
	private java.util.List<int[]> wallSpots(Layout lay, Room r) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int[][] corners = {{r.u0, r.v0}, {r.u1, r.v0}, {r.u0, r.v1}, {r.u1, r.v1}};
		for (int[] c : corners) {
			out.add(c);
		}
		java.util.List<int[]> sides = new java.util.ArrayList<>();
		for (int u = r.u0 + 1; u < r.u1; u++) {
			sides.add(new int[]{u, r.v0});
			sides.add(new int[]{u, r.v1});
		}
		for (int v = r.v0 + 1; v < r.v1; v++) {
			sides.add(new int[]{r.u0, v});
			sides.add(new int[]{r.u1, v});
		}
		java.util.Collections.shuffle(sides, this.random);
		out.addAll(sides);
		out.removeIf(c -> !lay.free(c[0], c[1]) || lay.nearClear(c[0], c[1]));
		return out;
	}

	/** Which way something against this spot's wall faces (into the room). */
	private static Direction faceIn(Frame f, Room r, int u, int v) {
		if (u == r.u0) {
			return Direction.EAST;
		}
		if (u == r.u1) {
			return Direction.WEST;
		}
		return v == r.v0 ? f.back() : f.front();
	}

	/** Puts one block against a wall of the room; false if there was no room left. */
	private boolean against(Frame f, Layout lay, Room r, int y, java.util.function.Function<Direction, BlockState> what, boolean tall) {
		for (int[] c : this.wallSpots(lay, r)) {
			Direction in = faceIn(f, r, c[0], c[1]);
			lay.used.add(Layout.key(c[0], c[1]));
			f.set(c[0], y, c[1], what.apply(in));
			if (tall) {
				f.set(c[0], y + 1, c[1], what.apply(in));
			}
			return true;
		}
		return false;
	}

	private int lockers;

	/** A wardrobe to hide in against a wall of the room, with room in front of it to get in; false if none fits. */
	private boolean wardrobe(Frame f, Layout lay, Room r, int y) {
		for (int[] c : this.wallSpots(lay, r)) {
			Direction in = faceIn(f, r, c[0], c[1]);
			int fu = c[0] + (in == Direction.EAST ? 1 : in == Direction.WEST ? -1 : 0);
			int fv = c[1] + (in == f.back() ? 1 : in == f.front() ? -1 : 0);
			if (r.contains(fu, fv) && lay.free(fu, fv)) {
				lay.used.add(Layout.key(c[0], c[1]));
				lay.clear.add(Layout.key(fu, fv));
				f.locker(c[0], y, c[1], in);
				this.lockers++;
				return true;
			}
		}
		return false;
	}

	/** A rug or tiles let into the floor, not where something is kept (the stairs and the hole over them) nor under a wall. */
	private void carpet(Frame f, Layout lay, int u0, int y, int v0, int u1, int v1, BlockState s) {
		for (int u = u0; u <= u1; u++) {
			for (int v = v0; v <= v1; v++) {
				if (!lay.used.contains(Layout.key(u, v)) && !lay.walls.contains(Layout.key(u, v))) {
					f.floor(u, y, v, s);
				}
			}
		}
	}

	/** Fits out a room for what it is; the ceiling light over its middle. */
	private void furnish(Frame f, Layout lay, Room r, int y, int ceiling, Style s, boolean npc) {
		int cu = (r.u0 + r.u1) / 2;
		int cv = (r.v0 + r.v1) / 2;
		f.bulb(cu, ceiling, cv);
		if (npc && (r.kind == RoomKind.LIVING || r.kind == RoomKind.KITCHEN)) {
			// Where the one at home stands: kept clear of what goes in after.
			int[] at = null;
			if (cv + 1 < r.v1 && lay.free(cu, cv + 1)) {
				at = new int[]{cu, cv + 1};
			}
			for (int u = r.u0 + 1; u < r.u1 && at == null; u++) {
				for (int v = r.v0 + 1; v < r.v1 && at == null; v++) {
					if (lay.free(u, v)) {
						at = new int[]{u, v};
					}
				}
			}
			if (at != null) {
				lay.used.add(Layout.key(at[0], at[1]));
				f.point(MapPoints.Type.NPC, at[0], y, at[1]);
			}
		}
		switch (r.kind) {
			case LIVING -> {
				String sofa = this.pick("dark_oak_stairs", "spruce_stairs", "mangrove_stairs", "birch_stairs");
				this.against(f, lay, r, y, d -> stairs(sofa, d.getOpposite()), false);
				this.against(f, lay, r, y, d -> stairs(sofa, d.getOpposite()), false);
				this.against(f, lay, r, y, d -> search(HwBlocks.NIGHTSTAND, d), false);
				this.against(f, lay, r, y, d -> b("bookshelf"), true);
				if (this.random.nextBoolean()) {
					this.against(f, lay, r, y, d -> b("jukebox"), false);
				}
				this.carpet(f, lay, cu - 1, y - 1, cv - 1, cu, cv, b(s.rug()));
			}
			case KITCHEN -> {
				this.against(f, lay, r, y, d -> search(HwBlocks.KITCHEN_CABINET, d), false);
				this.against(f, lay, r, y, d -> facing("smoker", d), false);
				this.against(f, lay, r, y, d -> b("quartz_pillar"), true);
				this.against(f, lay, r, y, d -> b("cauldron"), false);
				for (int[] c : this.wallSpots(lay, r)) {
					lay.used.add(Layout.key(c[0], c[1]));
					f.set(c[0], y, c[1], b("polished_andesite"));
					if (y == 0) {
						f.set(c[0], y + 1, c[1], dev.halloween.HwBlocks.PHONE.defaultBlockState());
						f.point(MapPoints.Type.PHONE, c[0], y + 1, c[1]);
					}
					break;
				}
				if (r.u1 - r.u0 >= 4 && r.v1 - r.v0 >= 4 && lay.free(cu, cv) && !lay.nearClear(cu, cv)) {
					// A table, only where there is room to walk round it.
					lay.used.add(Layout.key(cu, cv));
					f.set(cu, y, cv, b("dark_oak_fence"));
					f.set(cu, y + 1, cv, b(this.pick("candle", "flower_pot", "white_carpet")));
				}

			}
			case BEDROOM -> {
				for (int[] c : this.wallSpots(lay, r)) {
					// A bed along a side wall: two cells, the head against it.
					int nu = c[0];
					int nv = c[1] == r.v0 ? c[1] + 1 : c[1] == r.v1 ? c[1] - 1 : c[1];
					if (nv == c[1] || !lay.free(nu, nv) || lay.nearClear(nu, nv) || nv < r.v0 || nv > r.v1) {
						continue;
					}
					Direction head = c[1] == r.v0 ? f.front() : f.back();
					lay.used.add(Layout.key(c[0], c[1]));
					lay.used.add(Layout.key(nu, nv));
					this.bed(f, nu, y, nv, head, this.pick("red", "blue", "white", "green", "yellow", "light_gray"));
					break;
				}
				this.against(f, lay, r, y, d -> search(this.random.nextBoolean() ? HwBlocks.DRESSER : HwBlocks.NIGHTSTAND, d), false);
				if (r.area() >= 12 && this.random.nextBoolean()) {
					// Often a wardrobe to hide in.
					this.wardrobe(f, lay, r, y);
				}
				this.carpet(f, lay, cu, y - 1, cv, cu, cv, b(s.rug()));
			}
			case BATH -> {
				this.carpet(f, lay, r.u0, y - 1, r.v0, r.u1, r.v1, b("white_terracotta"));
				this.against(f, lay, r, y, d -> b("cauldron"), false);
				this.against(f, lay, r, y, d -> b("quartz_slab"), false);
			}
			case OFFICE -> {
				this.against(f, lay, r, y, d -> search(HwBlocks.FILING_CABINET, d), false);
				this.against(f, lay, r, y, d -> stairs("spruce_stairs", d.getOpposite()), false);
				this.against(f, lay, r, y, d -> b("bookshelf"), true);
			}
			case STORE -> {
				this.against(f, lay, r, y, d -> search(HwBlocks.CARDBOARD_BOX, d), false);
				this.against(f, lay, r, y, d -> search(HwBlocks.CRATE, d), false);
				this.against(f, lay, r, y, d -> b("barrel"), false);
			}
		}
	}

	/**
	 * Whether a window may go into the outer wall in front of inside cell (u, v): no inside wall meets the outer one
	 * there or a block to either side, nothing kept (stairs, doors) stands there. {@code alongU}: the wall runs along u.
	 */
	private static boolean windowOk(Layout lay, int u, int v, boolean alongU) {
		if (lay == null) {
			return true;
		}
		for (int k = -1; k <= 1; k++) {
			if (lay.walls.contains(Layout.key(alongU ? u + k : u, alongU ? v : v + k))) {
				return false;
			}
		}
		return !lay.used.contains(Layout.key(u, v)) && !lay.clear.contains(Layout.key(u, v));
	}

	/** One window two high at (u, y, v) in the outer wall, its inside cell (iu, iv) kept free to climb through. */
	private void window(Frame f, Layout lay, int u, int y, int v, int iu, int iv) {
		f.fill(u, y, v, u, y + 1, v, b("glass_pane"));
		lay.open.add(Layout.key(iu, iv));
	}

	/**
	 * Windows round a home (upstairs too if there is one), every three blocks: none where an inside wall meets the
	 * outer one, none within two blocks of a door, none by the breaker, none in a wall shared with the next house
	 * or the garage ({@code solidEnd}: the u = w-1 wall). Returns the u of the front windows downstairs.
	 */
	private java.util.Set<Integer> homeWindows(Frame f, int w, int d, Layout ground, Layout upper, int doorU, int backDoorU, int sideDoorV, boolean solidEnd) {
		java.util.Set<Integer> front = new java.util.HashSet<>();
		for (int u = 2; u <= w - 3; u += 3) {
			if (Math.abs(u - doorU) > 2 && windowOk(ground, u, 1, true)) {
				this.window(f, ground, u, 1, 0, u, 1);
				front.add(u);
			}
			if (upper != null && windowOk(upper, u, 1, true)) {
				this.window(f, upper, u, 6, 0, u, 1);
			}
			if (Math.abs(u - backDoorU) > 2 && windowOk(ground, u, d - 2, true)) {
				this.window(f, ground, u, 1, d - 1, u, d - 2);
			}
			if (upper != null && windowOk(upper, u, d - 2, true)) {
				this.window(f, upper, u, 6, d - 1, u, d - 2);
			}
		}
		for (int v = 2; v <= d - 3; v += 3) {
			if (Math.abs(v - d / 2) <= 1) {
				continue;
			}
			if (windowOk(ground, 1, v, false)) {
				this.window(f, ground, 0, 1, v, 1, v);
			}
			if (!solidEnd && Math.abs(v - sideDoorV) > 2 && windowOk(ground, w - 2, v, false)) {
				this.window(f, ground, w - 1, 1, v, w - 2, v);
			}
			if (upper != null && windowOk(upper, 1, v, false)) {
				this.window(f, upper, 0, 6, v, 1, v);
			}
			if (upper != null && !solidEnd && windowOk(upper, w - 2, v, false)) {
				this.window(f, upper, w - 1, 6, v, w - 2, v);
			}
		}
		return front;
	}

	/**
	 * A home of any size: shell, pitched roof, rooms laid out afresh on each floor, stairs if it has two, the
	 * porch, the yard. {@code x0}: where it starts (u 0). {@code yard}: the front garden and the back yard too (the
	 * second of a pair of townhouses shares the first one's). {@code sideDoorV}: a door in the u = w-1 wall there (-1: none).
	 */
	private void home(Lot lot, int x0, int w, int d, boolean two, Style s, boolean mirror, boolean yard, boolean solidEnd, int sideDoorV) {
		Frame f = new Frame(x0, lot, w, mirror);
		BlockState wall = b(s.wall());
		BlockState floor = b(s.floor());
		int wallTop = two ? 8 : 3;
		int top = two ? 9 : 4;
		int stairV0 = 2;
		// The front door away from the stairs (they run up the u = 1 wall).
		int doorU = (two ? 4 : 2) + this.random.nextInt(Math.max(1, w - (two ? 6 : 4)));
		int backDoorU = 2 + this.random.nextInt(Math.max(1, w - 4));
		f.fill(-1, -1, -1, w, -1, d, b("stone_bricks"));
		f.fill(1, -1, 1, w - 2, -1, d - 2, floor);
		f.fill(0, 0, 0, w - 1, wallTop, d - 1, wall);
		f.fill(1, 0, 1, w - 2, wallTop, d - 2, AIR);
		for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) {
			f.fill(c[0], 0, c[1], c[0], wallTop, c[1], b(s.corner()));
		}
		f.fill(1, 4, 1, w - 2, 4, d - 2, floor);
		f.fill(0, top, 0, w - 1, top, d - 1, wall);
		f.fill(1, top, 1, w - 2, top, d - 2, floor);
		// Cells no inside wall may take: in front of the doors, the stairs and the way round them.
		java.util.Set<Long> doors = new java.util.HashSet<>();
		doors.add(Layout.key(doorU, 1));
		doors.add(Layout.key(backDoorU, d - 2));
		if (sideDoorV > 0) {
			doors.add(Layout.key(w - 2, sideDoorV));
		}
		java.util.Set<Long> stairCells = new java.util.HashSet<>();
		if (two) {
			for (int k = 0; k <= 4; k++) {
				stairCells.add(Layout.key(1, stairV0 + k));
				stairCells.add(Layout.key(2, stairV0 + k));
			}
			stairCells.add(Layout.key(1, stairV0 - 1));
		}
		java.util.Set<Long> keep = new java.util.HashSet<>(doors);
		keep.addAll(stairCells);
		// No inside wall runs into a door, nor straight down beside the stairs.
		java.util.Set<Integer> noU = new java.util.HashSet<>();
		for (int k = -1; k <= 1; k++) {
			noU.add(doorU + k);
			noU.add(backDoorU + k);
		}
		if (two) {
			noU.add(3);
		}
		java.util.Set<Integer> noV = new java.util.HashSet<>();
		if (sideDoorV > 0) {
			for (int k = -1; k <= 1; k++) {
				noV.add(sideDoorV + k);
			}
		}
		Layout ground = this.partition(f, 1, 1, w - 2, d - 2, 0, 4, wall, keep, noU, noV);
		Layout upper = null;
		java.util.Set<Long> keepUp = new java.util.HashSet<>();
		if (two) {
			for (int k = 0; k <= 5; k++) {
				keepUp.add(Layout.key(1, stairV0 + k));
				keepUp.add(Layout.key(2, stairV0 + k));
			}
			// And the corner in front of the hole: nothing in it, so it isn't shut off by the rail.
			keepUp.add(Layout.key(1, stairV0 - 1));
			keepUp.add(Layout.key(2, stairV0 - 1));
			upper = this.partition(f, 1, 1, w - 2, d - 2, 5, 4, wall, keepUp, java.util.Set.of(1, 2, 3), java.util.Set.of());
		}
		ground.clear.addAll(doors);
		ground.used.addAll(stairCells);
		if (upper != null) {
			upper.used.addAll(keepUp);
		}
		java.util.Set<Integer> windows = this.homeWindows(f, w, d, ground, upper, doorU, backDoorU, sideDoorV, solidEnd);
		int lockersBefore = this.lockers;
		this.assignHome(ground, doorU, 1, false);
		for (Room r : ground.rooms) {
			this.furnish(f, ground, r, 0, 4, s, r.kind == RoomKind.LIVING);
		}
		if (upper != null) {
			this.assignHome(upper, 1, 1, true);
			for (Room r : upper.rooms) {
				this.furnish(f, upper, r, 5, top, s, false);
			}
		}
		if (two) {
			// Stairs up the u = 1 wall, filled in underneath, a rail round the hole upstairs.
			for (int k = 0; k <= 4; k++) {
				f.set(1, k, stairV0 + k, stairs(s.floor().replace("_planks", "_stairs"), f.back()));
				if (k >= 1) {
					f.fill(1, 0, stairV0 + k, 1, k - 1, stairV0 + k, floor);
				}
				if (k + 1 <= 3) {
					f.fill(1, k + 1, stairV0 + k, 1, 3, stairV0 + k, AIR);
				}
			}
			f.fill(1, 4, stairV0, 1, 4, stairV0 + 3, AIR);
			f.fill(2, 5, stairV0, 2, 5, stairV0 + 3, b("dark_oak_fence"));
			// Light on the stairs: over the foot of them and over the landing.
			f.bulb(2, 4, stairV0);
			f.bulb(2, top, stairV0 + 5);
		}
		if (this.lockers == lockersBefore) {
			// Every home has somewhere to hide: a wardrobe in the living room if no bedroom got one.
			for (Room r : ground.rooms) {
				if (r.kind == RoomKind.LIVING || r.kind == RoomKind.KITCHEN) {
					if (this.wardrobe(f, ground, r, 0)) {
						break;
					}
				}
			}
		}
		f.door(doorU, 0, 0, f.back());
		f.door(backDoorU, 0, d - 1, f.front());
		if (sideDoorV > 0) {
			f.fill(w - 1, 0, sideDoorV, w - 1, 1, sideDoorV, AIR);
			f.door(w - 1, 0, sideDoorV, plusU(f));
		}
		if (solidEnd) {
			// The other side is the next house or the garage: the breaker goes on this end.
			this.breaker(f.x(0), 1, f.z(d / 2), plusU(f).getOpposite());
		} else {
			this.breaker(Math.max(f.x(0), f.x(w - 1)), 1, f.z(d / 2), Direction.EAST);
		}
		f.bulb(doorU + 1, 2, 0);
		this.roof(f, w, d, top, s, wall);
		this.porch(f, doorU, windows, false);
		String pathBlock = this.pick("dirt_path", "gravel", "stone_bricks", "bricks");
		for (int v = -3; v >= -7; v--) {
			f.set(doorU, -1, v, b(pathBlock));
		}
		if (!yard) {
			return;
		}
		this.flowerBed(f, w, doorU);
		boolean drive = this.driveway(f, w, lot, false);
		this.frontFence(f, w, lot, drive, doorU);
		this.mailbox(f, doorU);
		this.backyard(f, d);
		this.halloweenYard(f, w, lot, drive, doorU);
		this.curbside(f, w, lot, drive);
	}

	private Style anyStyle() {
		return STYLES[this.random.nextInt(STYLES.length)];
	}

	/** A small house: one storey, a few small rooms. */
	private void cottage(Lot lot) {
		int w = 11 + 2 * this.random.nextInt(2);
		this.home(lot, lot.cx() - w / 2, w, 10 + this.random.nextInt(2), false, this.anyStyle(), this.random.nextBoolean(), true, false, -1);
	}

	/** A wide one-storey house. */
	private void bungalow(Lot lot) {
		int w = 15 + 2 * this.random.nextInt(2);
		this.home(lot, lot.cx() - w / 2, w, 10 + this.random.nextInt(2), false, this.anyStyle(), this.random.nextBoolean(), true, false, -1);
	}

	/** A big two-storey house with a garage built on at the side. */
	private void mansion(Lot lot) {
		int w = lot.width() >= 28 ? 17 : 15;
		// Built the other way round, so the garage (u past the house) is on the west and the breaker stays on the east.
		boolean mirror = true;
		int x0 = lot.cx() - (w + 7) / 2 + 7;
		Style s = this.anyStyle();
		this.home(lot, x0, w, 14, true, s, mirror, true, true, 6);
		// The garage: where the drive leads, a wide opening at the front, a workbench and a tool cabinet; a door into the house.
		Frame g = new Frame(x0, lot, w, mirror);
		BlockState wall = b(s.wall());
		g.fill(w - 1, -1, 1, w + 6, -1, 10, b("smooth_stone"));
		g.fill(w, 0, 1, w + 6, 4, 10, wall);
		g.fill(w, 0, 2, w + 5, 3, 9, AIR);
		g.fill(w + 1, 0, 1, w + 5, 2, 1, AIR);
		g.fill(w + 1, 3, 1, w + 5, 3, 1, b("white_concrete"));
		this.gable(Math.min(g.x(w), g.x(w + 6)), Math.max(g.x(w), g.x(w + 6)), Math.min(g.z(1), g.z(10)), Math.max(g.z(1), g.z(10)), 5, s.roof(), s.roofSlab(), wall);
		g.set(w + 5, 0, 9, search(HwBlocks.TOOL_CABINET, Direction.WEST));
		g.set(w + 5, 0, 8, b("crafting_table"));
		g.set(w + 1, 0, 9, search(HwBlocks.CRATE, Direction.EAST));
		g.fill(w + 2, 0, 4, w + 4, 0, 7, b(this.pick("red_concrete", "light_blue_concrete", "black_concrete", "brown_concrete")));
		g.fill(w + 2, 1, 5, w + 4, 1, 6, b("tinted_glass"));
		g.bulb(w + 3, 4, 5);
	}

	/** Two narrow two-storey homes side by side, sharing a wall. */
	private void townhouses(Lot lot) {
		int w = 9;
		int x0 = lot.cx() - w + 1;
		this.home(lot, x0, w, 12, true, this.anyStyle(), false, true, true, -1);
		this.home(lot, x0 + w - 1, w, 12, true, this.anyStyle(), true, false, true, -1);
	}

	// ---------------------------------------------------------------- downtown, services, the industrial end

	private enum Shop {
		BAR, PHARMACY, BARBER, BOOKS, GROCERY
	}

	/** The lot moved up to the sidewalk: downtown buildings stand two blocks back from it, not seven. */
	private static Lot close(Lot lot) {
		return new Lot(lot.x0(), lot.x1(), lot.south() ? lot.front() + 5 : lot.front() - 5, lot.south());
	}

	private static final String[][] ROOFING = {
		{"dark_oak_stairs", "dark_oak_slab"}, {"deepslate_tile_stairs", "deepslate_tile_slab"}, {"brick_stairs", "brick_slab"},
		{"stone_brick_stairs", "stone_brick_slab"}, {"spruce_stairs", "spruce_slab"}, {"mud_brick_stairs", "mud_brick_slab"},
	};

	/** A building: walls {@code h} high, the ceiling at h (lights go in it), a pitched roof over it. */
	private Frame box(Lot lot, int x0, int w, int d, int h, String wallId, String floorId, boolean mirror) {
		Frame f = new Frame(x0, lot, w, mirror);
		BlockState wall = b(wallId);
		f.fill(-1, -1, -1, w, -1, d, b("stone_bricks"));
		f.fill(1, -1, 1, w - 2, -1, d - 2, b(floorId));
		f.fill(0, 0, 0, w - 1, h, d - 1, wall);
		f.fill(1, 0, 1, w - 2, h - 1, d - 2, AIR);
		f.fill(1, h, 1, w - 2, h, d - 2, b("smooth_stone"));
		String[] roofing = ROOFING[this.random.nextInt(ROOFING.length)];
		this.gable(Math.min(f.x(0), f.x(w - 1)), Math.max(f.x(0), f.x(w - 1)), Math.min(f.z(0), f.z(d - 1)), Math.max(f.z(0), f.z(d - 1)), h,
			roofing[0], roofing[1], wall);
		return f;
	}

	/**
	 * A pitched roof over the walls x0..x1, z0..z1 from height y: eaves a block out to the north and south, the
	 * ridge running east-west, the gable ends walled up.
	 */
	private void gable(int x0, int x1, int z0, int z1, int y, String stairsId, String slabId, BlockState wall) {
		for (int k = 0; ; k++) {
			int zn = z0 - 1 + k;
			int zs = z1 + 1 - k;
			if (zn > zs) {
				break;
			}
			for (int x = x0 - 1; x <= x1 + 1; x++) {
				if (zn == zs) {
					this.set(x, y + k, zn, slab(slabId, SlabType.BOTTOM));
				} else {
					this.set(x, y + k, zn, stairs(stairsId, Direction.SOUTH));
					this.set(x, y + k, zs, stairs(stairsId, Direction.NORTH));
				}
			}
			if (k >= 1) {
				for (int z = zn + 1; z <= zs - 1; z++) {
					this.set(x0, y + k, z, wall);
					this.set(x1, y + k, z, wall);
				}
			}
		}
	}

	/** Lights in the ceiling over a whole floor, every few blocks; breakers on both sides of a wide building. */
	private void lightsAndBreakers(Frame f, int w, int d, int y) {
		for (int u = 3; u <= w - 3; u += 5) {
			for (int v = 3; v <= d - 3; v += 5) {
				f.bulb(u, y, v);
			}
		}
		this.breaker(Math.max(f.x(0), f.x(w - 1)), 1, f.z(d / 2), Direction.EAST);
		if (w > 16) {
			this.breaker(Math.min(f.x(0), f.x(w - 1)), 1, f.z(d / 2), Direction.WEST);
		}
	}

	/** Windows along the sides of a box at one storey. */
	private void sideWindows(Frame f, int w, int d, int y) {
		for (int v = 3; v <= d - 3; v += 3) {
			if (Math.abs(v - d / 2) <= 1) {
				continue;
			}
			f.fill(0, y, v, 0, y + 1, v, b("glass_pane"));
			f.fill(w - 1, y, v, w - 1, y + 1, v, b("glass_pane"));
		}
	}

	private void frontSign(Frame f, int u, int y, String... lines) {
		this.wallSign(f.x(u), y, f.z(-1), f.front(), lines);
	}

	/** A shop with a flat over it: display windows and an awning at the front, a stockroom at the back. */
	private void storefront(Lot lot0, Shop shop) {
		Lot lot = close(lot0);
		int w = Math.min(lot.width() - 4, 19);
		int d = 13;
		boolean mirror = this.random.nextBoolean();
		String wallId = this.pick("bricks", "red_terracotta", "brown_terracotta", "light_gray_terracotta", "white_terracotta");
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 9, wallId, "spruce_planks", mirror);
		BlockState wall = b(wallId);
		int doorU = w / 2;
		f.fill(1, 4, 1, w - 2, 4, d - 2, b("spruce_planks"));
		// Display windows either side of the door, a pier of wall between.
		for (int u = 2; u <= w - 3; u++) {
			if (Math.abs(u - doorU) > 1) {
				f.fill(u, 1, 0, u, 2, 0, b("glass_pane"));
			}
		}
		f.door(doorU, 0, 0, f.back());
		String stripe = switch (shop) {
			case BAR -> "red_wool";
			case PHARMACY -> "green_wool";
			case BARBER -> "blue_wool";
			case BOOKS -> "brown_wool";
			case GROCERY -> "orange_wool";
		};
		for (int u = 0; u <= w - 1; u++) {
			f.set(u, 3, -1, b(u % 2 == 0 ? stripe : "white_wool"));
		}
		String[] name = switch (shop) {
			case BAR -> new String[]{"", "THE RUSTY", "NAIL", "BAR"};
			case PHARMACY -> new String[]{"", "PHARMACY", "DRUGS", ""};
			case BARBER -> new String[]{"", "BARBER", "SHOP", ""};
			case BOOKS -> new String[]{"", "BOOKS &", "RECORDS", ""};
			case GROCERY -> new String[]{"", "GROCERY", "& DELI", ""};
		};
		this.frontSign(f, doorU, 4, name);
		// The stockroom at the back, stairs up to the flat in it, the back door.
		int store = d - 5;
		f.fill(1, 0, store, w - 2, 3, store, wall);
		f.fill(doorU, 0, store, doorU, 1, store, AIR);
		f.set(1, 0, d - 2, search(HwBlocks.CARDBOARD_BOX, Direction.EAST));
		f.set(1, 0, d - 3, search(HwBlocks.CRATE, Direction.EAST));
		f.set(w - 2, 0, store + 1, b("barrel"));
		for (int k = 0; k <= 4; k++) {
			f.set(w - 7 + k, k, d - 2, stairs("spruce_stairs", Direction.EAST));
			if (k >= 1) {
				f.fill(w - 7 + k, 0, d - 2, w - 7 + k, k - 1, d - 2, b("spruce_planks"));
			}
			// Headroom over each step but the top one (that one is level with the floor upstairs and must stay).
			if (k < 4) {
				f.fill(w - 7 + k, k + 1, d - 2, w - 7 + k, 4, d - 2, AIR);
			}
		}
		f.fill(w - 7, 4, d - 2, w - 4, 4, d - 2, AIR);
		f.door(doorU + 2, 0, d - 1, f.front());
		// The stockroom and the stairs lit, the landing too.
		f.bulb(3, 4, d - 3);
		f.bulb(w - 8, 4, d - 3);
		f.bulb(w - 3, 9, d - 3);
		// The shop floor.
		int cv = store - 2;
		switch (shop) {
			case BAR -> {
				f.fill(2, 0, cv, w - 3, 0, cv, b("stripped_dark_oak_log"));
				f.set(doorU, 0, cv, AIR);
				for (int u = 2; u <= w - 3; u += 3) {
					if (Math.abs(u - doorU) > 1) {
						f.set(u, 0, cv - 1, stairs("dark_oak_stairs", f.back()));
					}
				}
				f.set(2, 0, cv + 1, search(HwBlocks.NIGHTSTAND, f.front()));
				f.set(w - 3, 0, cv + 1, b("barrel"));
				f.set(w - 2, 0, 2, b("jukebox"));
				f.set(2, 1, cv, dev.halloween.HwBlocks.PHONE.defaultBlockState());
				f.point(MapPoints.Type.PHONE, 2, 1, cv);
			}
			case PHARMACY -> {
				f.fill(2, 0, cv, w - 3, 0, cv, b("quartz_block"));
				f.set(doorU, 0, cv, AIR);
				for (int v = 2; v < cv - 1; v++) {
					f.set(1, 0, v, b("spruce_slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
					f.set(1, 1, v, b(this.pick("brewing_stand", "potted_fern", "flower_pot", "cauldron")));
				}
				f.set(w - 3, 0, cv + 1, search(HwBlocks.FILING_CABINET, f.front()));
				f.set(2, 0, cv + 1, search(HwBlocks.NIGHTSTAND, f.front()));
				f.set(3, 1, cv, dev.halloween.HwBlocks.PHONE.defaultBlockState());
				f.point(MapPoints.Type.PHONE, 3, 1, cv);
			}
			case BARBER -> {
				for (int u = 2; u <= w - 3; u += 3) {
					f.set(u, 0, cv, stairs("red_nether_brick_stairs", f.front()));
				}
				f.set(1, 0, cv - 2, search(HwBlocks.DRESSER, Direction.EAST));
				f.set(w - 2, 0, cv - 2, b("cauldron"));
				f.fill(-1, 0, -1, -1, 2, -1, b("red_wool"));
				f.set(-1, 1, -1, b("white_wool"));
			}
			case BOOKS -> {
				for (int u = 2; u <= w - 3; u += 3) {
					if (Math.abs(u - doorU) > 1) {
						f.fill(u, 0, 2, u, 2, cv - 1, b("bookshelf"));
					}
				}
				f.set(w - 2, 0, cv, search(HwBlocks.FILING_CABINET, Direction.WEST));
				f.set(1, 0, cv, b("jukebox"));
			}
			case GROCERY -> {
				for (int u = 2; u <= w - 3; u += 3) {
					if (Math.abs(u - doorU) > 1) {
						for (int v = 2; v < cv; v++) {
							f.set(u, 0, v, b(this.pick("hay_block", "melon", "pumpkin", "barrel", "spruce_planks")));
						}
					}
				}
				f.set(1, 0, cv, search(HwBlocks.KITCHEN_CABINET, Direction.EAST));
				f.set(w - 2, 0, cv, search(HwBlocks.CARDBOARD_BOX, Direction.WEST));
				f.set(w - 3, 1, 1, dev.halloween.HwBlocks.PHONE.defaultBlockState());
				f.set(w - 3, 0, 1, b("spruce_planks"));
				f.point(MapPoints.Type.PHONE, w - 3, 1, 1);
			}
		}
		// The flat upstairs, rooms laid out afresh, windows front and back.
		java.util.Set<Long> keepUp = new java.util.HashSet<>();
		for (int k = 0; k <= 5; k++) {
			keepUp.add(Layout.key(w - 7 + k, d - 2));
			keepUp.add(Layout.key(w - 7 + k, d - 3));
		}
		Layout up = this.partition(f, 1, 1, w - 2, d - 2, 5, 4, wall, keepUp, java.util.Set.of(), java.util.Set.of());
		up.used.addAll(keepUp);
		for (int u = 2; u <= w - 3; u += 3) {
			if (windowOk(up, u, 1, true)) {
				this.window(f, up, u, 6, 0, u, 1);
			}
			if (windowOk(up, u, d - 2, true)) {
				this.window(f, up, u, 6, d - 1, u, d - 2);
			}
		}
		this.assignHome(up, 1, 1, true);
		// Someone at home upstairs, in the middle of the first room (kept clear of the furniture).
		Room home = up.rooms.getFirst();
		int hu = (home.u0 + home.u1) / 2;
		int hv = (home.v0 + home.v1) / 2;
		up.used.add(Layout.key(hu, hv));
		f.point(MapPoints.Type.NPC, hu, 5, hv);
		for (Room r : up.rooms) {
			this.furnish(f, up, r, 5, 9, STYLES[0], false);
		}
		this.lightsAndBreakers(f, w, d, 4);
	}

	/** The picture house: a lobby with the ticket booth, the auditorium with rows of seats and the screen. */
	private void cinema(Lot lot0) {
		Lot lot = close(lot0);
		int w = Math.min(lot.width() - 4, 22);
		int d = 18;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 7, "red_terracotta", "red_wool", false);
		BlockState wall = b("red_terracotta");
		int doorU = w / 2;
		f.door(doorU - 1, 0, 0, f.back());
		f.door(doorU + 1, 0, 0, f.back());
		f.fill(2, 1, 0, 4, 2, 0, b("glass_pane"));
		f.fill(w - 5, 1, 0, w - 3, 2, 0, b("glass_pane"));
		// The marquee: lights and a sign over the doors.
		f.fill(1, 4, -1, w - 2, 4, -1, b("redstone_lamp"));
		f.fill(1, 5, -1, w - 2, 6, -1, b("black_concrete"));
		this.frontSign(f, doorU, 5, "", "THE FOG", "TONIGHT 9PM", "");
		// Lobby (ticket booth, candy counter, an office), then the auditorium behind a wall.
		int lobby = 6;
		f.fill(1, 0, lobby, w - 2, 6, lobby, wall);
		f.fill(doorU - 1, 0, lobby, doorU + 1, 2, lobby, AIR);
		f.fill(2, 0, 2, 4, 0, 4, b("quartz_block"));
		f.set(3, 1, 3, b("glass_pane"));
		f.set(2, 1, 2, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 2, 1, 2);
		f.set(w - 3, 0, 4, search(HwBlocks.CARDBOARD_BOX, Direction.WEST));
		f.fill(w - 6, 0, 2, w - 6, 3, 5, wall);
		f.fill(w - 6, 0, 3, w - 6, 1, 3, AIR);
		f.set(w - 3, 0, 2, search(HwBlocks.FILING_CABINET, Direction.WEST));
		f.locker(1, lobby - 1, f.front());
		for (int v = lobby + 2; v <= d - 5; v += 2) {
			for (int u = 2; u <= w - 3; u++) {
				if (Math.abs(u - doorU) > 1) {
					f.set(u, 0, v, stairs("red_nether_brick_stairs", f.front()));
				}
			}
		}
		f.fill(1, 1, d - 2, w - 2, 5, d - 2, b("white_wool"));
		f.door(1, 0, d - 1, f.front());
		f.fill(1, 1, d - 2, 1, 2, d - 2, AIR);
		this.lightsAndBreakers(f, w, d, 7);
		this.sideWindows(f, w, d, 1);
	}

	/** The police station: front desk, offices, two cells, the locker room, the armoury with the gun case. */
	private void policeStation(Lot lot0) {
		Lot lot = close(lot0);
		int w = 23;
		int d = 16;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 4, "light_blue_terracotta", "polished_andesite", false);
		BlockState wall = b("white_terracotta");
		int doorU = w / 2;
		f.door(doorU, 0, 0, f.back());
		for (int u = 2; u <= w - 3; u += 3) {
			if (Math.abs(u - doorU) > 2) {
				f.fill(u, 1, 0, u, 2, 0, b("glass_pane"));
			}
		}
		this.frontSign(f, doorU, 3, "", "POLICE", "DEPARTMENT", "");
		f.set(doorU - 2, 3, -1, b("sea_lantern"));
		f.set(doorU + 2, 3, -1, b("sea_lantern"));
		// A corridor across the middle; the desk in the lobby at the front.
		int hall = 6;
		f.fill(1, 0, hall - 1, w - 2, 3, hall - 1, wall);
		f.fill(1, 0, hall + 2, w - 2, 3, hall + 2, wall);
		f.fill(doorU - 1, 0, hall - 1, doorU + 1, 1, hall - 1, AIR);
		f.fill(doorU - 3, 0, 3, doorU + 3, 0, 3, b("spruce_planks"));
		f.set(doorU + 2, 1, 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, doorU + 2, 1, 3);
		f.set(doorU - 3, 0, 4, search(HwBlocks.FILING_CABINET, f.front()));
		f.point(MapPoints.Type.NPC, doorU, 0, 4);
		// Rooms off the back of the corridor: office, two cells, locker room, armoury.
		int[] walls = {5, 9, 13, 17};
		for (int u : walls) {
			f.fill(u, 0, hall + 2, u, 3, d - 2, wall);
		}
		f.fill(3, 0, hall + 2, 3, 1, hall + 2, AIR);
		f.set(1, 0, d - 2, search(HwBlocks.FILING_CABINET, Direction.EAST));
		f.set(4, 0, d - 2, stairs("spruce_stairs", f.front()));
		for (int c = 0; c < 2; c++) {
			int u0 = walls[c] + 1;
			int u1 = walls[c + 1] - 1;
			f.fill(u0, 0, hall + 2, u1, 2, hall + 2, b("iron_bars"));
			f.set(u0, 0, d - 2, b("cauldron"));
			f.set(u1, 0, d - 2, b("white_wool"));
		}
		f.fill(walls[2] + 2, 0, hall + 2, walls[2] + 2, 1, hall + 2, AIR);
		f.locker(walls[2] + 1, d - 2, f.front());
		f.locker(walls[3] - 1, d - 2, f.front());
		f.fill(walls[3] + 2, 0, hall + 2, walls[3] + 2, 1, hall + 2, AIR);
		f.door(walls[3] + 2, 0, hall + 2, f.back());
		f.point(MapPoints.Type.GUN_CASE, w - 3, 0, d - 3);
		f.set(w - 2, 0, d - 2, search(HwBlocks.TOOL_CABINET, Direction.WEST));
		f.door(2, 0, d - 1, f.front());
		this.lightsAndBreakers(f, w, d, 4);
		this.sideWindows(f, w, d, 1);
	}

	/** The fire station: a bay with the engine, the crew's kitchen and bunk room. */
	private void fireStation(Lot lot) {
		int w = 23;
		int d = 16;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 6, "bricks", "smooth_stone", this.random.nextBoolean());
		BlockState wall = b("bricks");
		// The engine bay: a big opening at the front, the red engine inside.
		f.fill(2, 0, 0, 8, 4, 0, AIR);
		f.fill(1, 5, -1, 9, 5, -1, b("red_concrete"));
		this.frontSign(f, 5, 4, "", "FIRE", "STATION 1", "");
		f.fill(3, 0, 3, 7, 1, 11, b("red_concrete"));
		f.fill(3, 2, 3, 7, 2, 5, b("red_concrete"));
		f.fill(4, 2, 4, 6, 2, 4, b("glass"));
		f.fill(3, 0, 12, 7, 0, 12, b("white_concrete"));
		f.fill(1, 0, 2, 1, 0, 3, search(HwBlocks.TOOL_CABINET, Direction.EAST));
		f.set(1, 0, d - 2, search(HwBlocks.CRATE, Direction.EAST));
		// A wall, then the crew quarters.
		f.fill(10, 0, 1, 10, 5, d - 2, wall);
		f.fill(10, 0, 4, 10, 1, 4, AIR);
		f.door(10, 0, 4, plusU(f));
		f.door(15, 0, 0, f.back());
		f.fill(11, 0, 8, w - 2, 5, 8, wall);
		f.fill(16, 0, 8, 16, 1, 8, AIR);
		f.set(w - 2, 0, 1, search(HwBlocks.KITCHEN_CABINET, Direction.WEST));
		f.set(w - 2, 0, 2, facing("smoker", Direction.WEST));
		f.set(w - 2, 0, 3, b("polished_andesite"));
		f.set(w - 2, 1, 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, w - 2, 1, 3);
		f.set(14, 0, 4, b("dark_oak_fence"));
		f.set(14, 1, 4, b("candle"));
		for (int u = 12; u <= w - 3; u += 3) {
			this.bed(f, u, 0, d - 3, f.back(), "red");
		}
		f.locker(11, 9, Direction.EAST);
		f.set(w - 2, 0, 9, search(HwBlocks.DRESSER, Direction.WEST));
		f.point(MapPoints.Type.NPC, 16, 0, 5);
		this.lightsAndBreakers(f, w, d, 6);
		this.sideWindows(f, w, d, 1);
	}

	/** The post office: the counter, the sorting room full of parcels. */
	private void postOffice(Lot lot) {
		int w = 19;
		int d = 13;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 4, "white_terracotta", "polished_andesite", this.random.nextBoolean());
		BlockState wall = b("light_gray_terracotta");
		int doorU = w / 2;
		f.door(doorU, 0, 0, f.back());
		for (int u = 2; u <= w - 3; u += 3) {
			if (Math.abs(u - doorU) > 2) {
				f.fill(u, 1, 0, u, 2, 0, b("glass_pane"));
			}
		}
		this.frontSign(f, doorU, 3, "", "U.S. POST", "OFFICE", "");
		f.set(doorU + 3, 0, -2, b("blue_concrete"));
		f.set(doorU + 3, 1, -2, b("blue_concrete"));
		f.fill(2, 0, 5, w - 3, 0, 5, b("spruce_planks"));
		f.set(doorU, 0, 5, AIR);
		f.set(3, 1, 5, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 3, 1, 5);
		f.fill(1, 0, 7, w - 2, 3, 7, wall);
		f.fill(doorU, 0, 7, doorU, 1, 7, AIR);
		f.door(doorU, 0, 7, f.back());
		f.set(1, 0, d - 2, search(HwBlocks.CARDBOARD_BOX, Direction.EAST));
		f.set(w - 2, 0, d - 2, search(HwBlocks.CARDBOARD_BOX, Direction.WEST));
		f.set(w - 2, 0, 8, search(HwBlocks.FILING_CABINET, Direction.WEST));
		for (int u = 3; u <= w - 4; u += 4) {
			f.set(u, 0, d - 3, b("barrel"));
			f.set(u + 1, 0, d - 3, b("bookshelf"));
		}
		f.door(2, 0, d - 1, f.front());
		f.point(MapPoints.Type.NPC, doorU, 0, 3);
		this.lightsAndBreakers(f, w, d, 4);
		this.sideWindows(f, w, d, 1);
	}

	/** The clinic: a waiting room, reception, two examination rooms. */
	private void clinic(Lot lot) {
		int w = 21;
		int d = 14;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 4, "white_concrete", "white_terracotta", this.random.nextBoolean());
		BlockState wall = b("white_concrete");
		int doorU = w / 2;
		f.door(doorU, 0, 0, f.back());
		for (int u = 2; u <= w - 3; u += 3) {
			if (Math.abs(u - doorU) > 2) {
				f.fill(u, 1, 0, u, 2, 0, b("glass_pane"));
			}
		}
		this.frontSign(f, doorU, 3, "", "HADDONFIELD", "CLINIC", "");
		for (int v = 2; v <= 4; v++) {
			f.set(1, 0, v, stairs("birch_stairs", Direction.WEST));
		}
		f.fill(w - 6, 0, 3, w - 3, 0, 3, b("quartz_block"));
		f.set(w - 4, 1, 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, w - 4, 1, 3);
		f.set(w - 2, 0, 2, search(HwBlocks.FILING_CABINET, Direction.WEST));
		f.fill(1, 0, 6, w - 2, 3, 6, wall);
		f.fill(doorU, 0, 6, doorU, 1, 6, AIR);
		f.fill(doorU, 0, 7, doorU, 3, d - 2, wall);
		f.fill(doorU - 2, 0, 6, doorU - 2, 1, 6, AIR);
		f.fill(doorU + 2, 0, 6, doorU + 2, 1, 6, AIR);
		f.set(doorU, 0, 6, wall);
		f.set(doorU, 1, 6, wall);
		for (int side : new int[]{1, w - 2}) {
			Direction in = side == 1 ? Direction.EAST : Direction.WEST;
			f.set(side, 0, d - 3, b("white_wool"));
			f.set(side, 0, d - 2, b("white_wool"));
			f.set(side, 0, 8, search(HwBlocks.NIGHTSTAND, in));
		}
		f.locker(doorU - 1, d - 2, f.front());
		f.point(MapPoints.Type.NPC, 3, 0, 4);
		f.door(1, 0, d - 1, f.front());
		this.lightsAndBreakers(f, w, d, 4);
		this.sideWindows(f, w, d, 1);
	}

	/** The warehouse: a tall hall of stacked crates in aisles, an office in a corner, a loading door. */
	private void warehouse(Lot lot) {
		int w = 22;
		int d = 18;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 7, "gray_concrete", "smooth_stone", this.random.nextBoolean());
		BlockState wall = b("gray_concrete");
		f.fill(w - 8, 0, 0, w - 4, 4, 0, AIR);
		f.fill(w - 8, 5, 0, w - 4, 5, 0, b("yellow_concrete"));
		f.door(3, 0, 0, f.back());
		this.frontSign(f, 3, 3, "", "SMITH'S", "GROVE", "STORAGE");
		for (int u = 3; u <= w - 4; u += 4) {
			for (int v = 5; v <= d - 3; v++) {
				if (v == d / 2 + 1) {
					continue;
				}
				int hgt = 1 + this.random.nextInt(3);
				for (int y = 0; y < hgt; y++) {
					f.set(u, y, v, b(this.pick("spruce_planks", "barrel", "hay_block", "stripped_spruce_log", "oak_planks")));
				}
			}
		}
		f.set(3, 0, d / 2 + 1, search(HwBlocks.CRATE, Direction.NORTH));
		f.set(w - 5, 0, d / 2 + 1, search(HwBlocks.CRATE, Direction.SOUTH));
		f.set(1, 0, d - 2, search(HwBlocks.CARDBOARD_BOX, Direction.EAST));
		// The office in the front corner.
		f.fill(1, 0, 4, 6, 3, 4, wall);
		f.fill(6, 0, 1, 6, 3, 4, wall);
		f.fill(6, 0, 2, 6, 1, 2, AIR);
		f.door(6, 0, 2, plusU(f));
		f.fill(1, 1, 4, 3, 2, 4, b("glass_pane"));
		f.set(1, 0, 1, search(HwBlocks.FILING_CABINET, Direction.EAST));
		f.set(5, 0, 1, b("spruce_planks"));
		f.set(5, 1, 1, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 5, 1, 1);
		f.locker(w - 2, 2, Direction.WEST);
		this.lightsAndBreakers(f, w, d, 7);
		this.sideWindows(f, w, d, 4);
	}

	/** The garage: two bays (a car up on the lift in one), tools, tyres, oil drums, the office. */
	private void autoShop(Lot lot) {
		int w = 19;
		int d = 14;
		Frame f = this.box(lot, lot.cx() - w / 2, w, d, 5, "white_terracotta", "smooth_stone", this.random.nextBoolean());
		BlockState wall = b("white_terracotta");
		f.fill(1, 0, 0, 5, 3, 0, AIR);
		f.fill(7, 0, 0, 11, 3, 0, AIR);
		f.fill(0, 4, -1, w - 1, 4, -1, b("red_concrete"));
		this.frontSign(f, 15, 3, "", "JOE'S AUTO", "REPAIR", "");
		f.fill(2, 0, 4, 2, 1, 4, b("iron_block"));
		f.fill(4, 0, 4, 4, 1, 4, b("iron_block"));
		f.fill(2, 2, 3, 4, 2, 9, b(this.pick("light_blue_concrete", "brown_concrete", "green_concrete")));
		f.fill(2, 3, 5, 4, 3, 7, b("tinted_glass"));
		f.fill(8, 0, 4, 10, 0, 9, b("black_concrete"));
		f.fill(8, 1, 5, 10, 1, 8, b("orange_concrete"));
		f.set(1, 0, d - 2, search(HwBlocks.TOOL_CABINET, Direction.EAST));
		f.set(6, 0, d - 2, search(HwBlocks.TOOL_CABINET, f.front()));
		f.set(11, 0, d - 2, search(HwBlocks.CRATE, f.front()));
		for (int v = 2; v <= 4; v++) {
			f.set(12, 0, v, b("black_wool"));
			f.set(12, 1, v, b("black_wool"));
		}
		f.set(6, 0, 1, b("cauldron"));
		f.set(6, 0, 2, b("cauldron"));
		// The office.
		f.fill(13, 0, 1, 13, 3, d - 2, wall);
		f.fill(13, 0, 6, 13, 1, 6, AIR);
		f.fill(17, 1, 0, 17, 2, 0, b("glass_pane"));
		f.door(15, 0, 0, f.back());
		f.set(w - 2, 0, 3, b("spruce_planks"));
		f.set(w - 2, 1, 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, w - 2, 1, 3);
		f.set(w - 2, 0, d - 2, search(HwBlocks.FILING_CABINET, Direction.WEST));
		f.locker(14, d - 2, f.front());
		f.point(MapPoints.Type.NPC, 16, 0, 5);
		this.lightsAndBreakers(f, w, d, 5);
		this.sideWindows(f, w, d, 1);
	}

	/** The scrapyard: a fenced yard of wrecks and tyres, a little shack. */
	private void junkyard(Lot lot) {
		int w = Math.min(lot.width() - 4, 22);
		int d = 18;
		Frame f = new Frame(lot.cx() - w / 2, lot, w, this.random.nextBoolean());
		for (int u = 0; u < w; u++) {
			for (int v : new int[]{0, d - 1}) {
				f.fill(u, 0, v, u, 1, v, b("spruce_fence"));
			}
		}
		for (int v = 0; v < d; v++) {
			f.fill(0, 0, v, 0, 1, v, b("spruce_fence"));
			f.fill(w - 1, 0, v, w - 1, 1, v, b("spruce_fence"));
		}
		f.fill(w / 2 - 2, 0, 0, w / 2 + 2, 1, 0, AIR);
		f.fill(1, -1, 1, w - 2, -1, d - 2, b("coarse_dirt"));
		this.frontSign(f, w / 2 + 3, 1, "", "SCRAP", "& SALVAGE", "");
		// Wrecks: rusty bodies, one on top of another, tyres piled up.
		String[] rust = {"brown_concrete", "rooted_dirt", "light_gray_terracotta", "red_terracotta", "black_concrete"};
		for (int[] c : new int[][]{{2, 3}, {2, 8}, {8, 12}, {13, 4}, {13, 11}}) {
			String body = rust[this.random.nextInt(rust.length)];
			f.fill(c[0], 0, c[1], c[0] + 4, 0, c[1] + 2, b(body));
			f.fill(c[0] + 1, 1, c[1], c[0] + 3, 1, c[1] + 2, b(this.random.nextBoolean() ? body : "tinted_glass"));
		}
		f.fill(2, 1, 8, 6, 1, 10, b("brown_concrete"));
		for (int v = 6; v <= 8; v++) {
			f.fill(w - 3, 0, v, w - 3, this.random.nextInt(3), v, b("black_wool"));
		}
		f.set(8, 0, 5, search(HwBlocks.CRATE, f.front()));
		f.set(w - 2, 0, d - 2, search(HwBlocks.TOOL_CABINET, Direction.WEST));
		// The shack in the back corner.
		f.fill(1, 0, d - 6, 5, 3, d - 2, b("spruce_planks"));
		f.fill(2, 0, d - 5, 4, 2, d - 3, AIR);
		f.door(3, 0, d - 6, f.back());
		f.fill(1, 4, d - 6, 5, 4, d - 2, slab("spruce_slab", SlabType.BOTTOM));
		f.set(2, 0, d - 3, search(HwBlocks.CARDBOARD_BOX, Direction.EAST));
		f.set(4, 0, d - 3, b("spruce_planks"));
		f.set(4, 1, d - 3, dev.halloween.HwBlocks.PHONE.defaultBlockState());
		f.point(MapPoints.Type.PHONE, 4, 1, d - 3);
		f.bulb(3, 3, d - 4);
		this.breaker(Math.max(f.x(1), f.x(5)), 1, f.z(d - 4), Direction.EAST);
		f.set(w / 2, 2, 1, b("lantern"));
	}

	/** The water tower: a tank on four legs high over the end of town, a ladder up, the pump house under it. */
	private void waterTower(Lot lot) {
		int cx = lot.cx();
		int cz = lot.z(14);
		for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
			this.fill(cx + c[0], 0, cz + c[1], cx + c[0], 15, cz + c[1], b("stripped_spruce_log"));
		}
		for (int y = 4; y <= 12; y += 4) {
			for (int k = -3; k <= 3; k++) {
				this.set(cx + k, y, cz - 3, b("spruce_fence"));
				this.set(cx + k, y, cz + 3, b("spruce_fence"));
				this.set(cx - 3, y, cz + k, b("spruce_fence"));
				this.set(cx + 3, y, cz + k, b("spruce_fence"));
			}
		}
		for (int y = 16; y <= 22; y++) {
			for (int dx = -5; dx <= 5; dx++) {
				for (int dz = -5; dz <= 5; dz++) {
					double r = Math.sqrt(dx * dx + dz * dz);
					if (y == 16 && r <= 5.2 || r > 4.3 && r <= 5.2) {
						this.set(cx + dx, y, cz + dz, b(y == 19 ? "spruce_planks" : "stripped_spruce_wood"));
					}
				}
			}
		}
		for (int k = 0; k <= 5; k++) {
			for (int dx = -5 + k; dx <= 5 - k; dx++) {
				for (int dz = -5 + k; dz <= 5 - k; dz++) {
					if (Math.sqrt(dx * dx + dz * dz) <= 5.2 - k && Math.sqrt(dx * dx + dz * dz) > 4.0 - k) {
						this.set(cx + dx, 23 + k, cz + dz, b("dark_oak_planks"));
					}
				}
			}
		}
		for (int y = 0; y <= 16; y++) {
			this.set(cx + 4, y, cz + 3, b("ladder").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		}
		this.set(cx + 3, 23, cz, b("lantern"));
		// The pump house under it.
		this.fill(cx - 2, -1, cz - 2, cx + 2, -1, cz + 2, b("stone_bricks"));
		this.fill(cx - 2, 0, cz - 2, cx + 2, 3, cz + 2, b("bricks"));
		this.fill(cx - 1, 0, cz - 1, cx + 1, 2, cz + 1, AIR);
		Direction toStreet = lot.south() ? Direction.SOUTH : Direction.NORTH;
		int dz = lot.south() ? 2 : -2;
		this.door(cx, 0, cz + dz, toStreet.getOpposite(), "spruce_door");
		this.set(cx - 1, 0, cz - dz / 2, search(HwBlocks.TOOL_CABINET, toStreet));
		this.set(cx + 1, 0, cz - dz / 2, b("cauldron"));
		this.bulb(cx, 3, cz);
		this.breaker(cx + 2, 1, cz, Direction.EAST);
		this.sign(cx - 2, 0, lot.z(6), lot.south() ? 0 : 8, "", "WATER", "DEPT.", "");
	}

}
