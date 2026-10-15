package dev.halloween.game;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Buildings and their rooms as one finds them on the spot, on any map (the town's houses, the school, a hand-built
 * one): the floor under a roof one can walk to from a point, cut into rooms at doors and doorways (a gap one block
 * wide in a full-height wall). Michael's raids go through them room by room. A scan holds a minute (doors get
 * broken, the quake knocks things about).
 */
public final class Houses {
	private static final int KEEP_TICKS = 20 * 60;
	private static final int MAX_CELLS = 3000;
	/** How far from where a scan starts a building may reach (blocks, each way). */
	private static final int REACH = 36;
	/** Fewer cells than this between doorways is a passage (a hall one block wide, a stair), not a room. */
	private static final int MIN_ROOM = 6;
	private static final List<Building> CACHE = new ArrayList<>();

	private Houses() {
	}

	/** A room: the floor cells (all on one level), where to stand to see it, the wardrobes along its walls. */
	public static final class Room {
		public final int index;
		public final List<BlockPos> cells;
		public final BlockPos center;
		/** Where to stand to see it all: the middle, and in a big room its far end too. */
		public final List<BlockPos> spots = new ArrayList<>();
		/** Wardrobes (their lower halves) one opens from this room. */
		public final List<BlockPos> lockers = new ArrayList<>();

		Room(ServerLevel level, int index, List<BlockPos> cells) {
			this.index = index;
			this.cells = cells;
			double cx = 0;
			double cz = 0;
			for (BlockPos p : cells) {
				cx += p.getX() + 0.5;
				cz += p.getZ() + 0.5;
			}
			cx /= cells.size();
			cz /= cells.size();
			BlockPos best = cells.getFirst();
			double bestD = Double.MAX_VALUE;
			for (BlockPos p : cells) {
				double d = sq(p.getX() + 0.5 - cx) + sq(p.getZ() + 0.5 - cz);
				if (d < bestD) {
					bestD = d;
					best = p;
				}
			}
			this.center = best;
			this.spots.add(best);
			if (cells.size() > 48) {
				BlockPos far = best;
				for (BlockPos p : cells) {
					if (p.distSqr(best) > far.distSqr(best)) {
						far = p;
					}
				}
				if (far.distSqr(best) > 25) {
					this.spots.add(far);
				}
			}
			Set<BlockPos> found = new LinkedHashSet<>();
			for (BlockPos p : cells) {
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos q = p.relative(d);
					if (LockerBlock.is(level.getBlockState(q))) {
						found.add(LockerBlock.base(level, q).immutable());
					}
				}
			}
			this.lockers.addAll(found);
		}

		public Vec3 middle() {
			return Vec3.atBottomCenterOf(this.center);
		}
	}

	/** A building: its floor, rooms, doors, ways out (and the ground outside them), windows and breakers. */
	public static final class Building {
		/** Stays the same from scan to scan of the same building: the lowest cell. */
		public final long key;
		final ServerLevel level;
		final int made;
		/** A building at all (not the shade of a tree, a carport). */
		final boolean real;
		final LongOpenHashSet cells;
		final Long2IntOpenHashMap roomOf;
		public final List<Room> rooms;
		public final List<BlockPos> doors;
		/** Cells inside by a way out, and the ground just outside each (same order). */
		public final List<BlockPos> ways;
		public final List<BlockPos> outside;
		/** Panes one climbs out through. */
		public final List<BlockPos> windows;
		public final List<BlockPos> breakers;
		public final Vec3 center;

		Building(ServerLevel level, int made, boolean real, LongOpenHashSet cells, Long2IntOpenHashMap roomOf, List<Room> rooms, List<BlockPos> doors,
			List<BlockPos> ways, List<BlockPos> outside, List<BlockPos> windows, List<BlockPos> breakers) {
			this.level = level;
			this.made = made;
			this.real = real;
			this.cells = cells;
			this.roomOf = roomOf;
			this.rooms = rooms;
			this.doors = doors;
			this.ways = ways;
			this.outside = outside;
			this.windows = windows;
			this.breakers = breakers;
			long low = Long.MAX_VALUE;
			double x = 0;
			double z = 0;
			LongIterator it = cells.iterator();
			while (it.hasNext()) {
				long c = it.nextLong();
				low = Math.min(low, c);
				x += BlockPos.getX(c) + 0.5;
				z += BlockPos.getZ(c) + 0.5;
			}
			this.key = low;
			int n = Math.max(1, cells.size());
			this.center = new Vec3(x / n, BlockPos.getY(low), z / n);
		}

		/** Its box: min x, y, z and max x, y, z (the walls a block out, the ceiling three over the highest floor). */
		public int[] bounds() {
			int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
			LongIterator it = this.cells.iterator();
			while (it.hasNext()) {
				long c = it.nextLong();
				b[0] = Math.min(b[0], BlockPos.getX(c));
				b[1] = Math.min(b[1], BlockPos.getY(c));
				b[2] = Math.min(b[2], BlockPos.getZ(c));
				b[3] = Math.max(b[3], BlockPos.getX(c));
				b[4] = Math.max(b[4], BlockPos.getY(c));
				b[5] = Math.max(b[5], BlockPos.getZ(c));
			}
			return new int[]{b[0] - 1, b[1] - 1, b[2] - 1, b[3] + 2, b[4] + 3, b[5] + 2};
		}

		/** Is this point on its floor (feet, or a block above or below)? */
		public boolean contains(BlockPos p) {
			return this.cells.contains(p.asLong()) || this.cells.contains(p.below().asLong()) || this.cells.contains(p.above().asLong());
		}

		/** On its floor or right by it (a door in its wall, a pane, a wardrobe against a wall). */
		public boolean touches(BlockPos p) {
			if (this.contains(p)) {
				return true;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				if (this.contains(p.relative(d))) {
					return true;
				}
			}
			return false;
		}

		/** The room this point is in, or null (a doorway, the hall, outside). */
		public Room roomAt(BlockPos p) {
			for (BlockPos q : new BlockPos[]{p, p.below(), p.above()}) {
				int i = this.roomOf.get(q.asLong());
				if (i >= 0) {
					return this.rooms.get(i);
				}
			}
			return null;
		}

		/** The room this point is in, or else the nearest one (by its middle; a floor apart counts for a lot). */
		public Room nearestRoom(Vec3 p) {
			Room in = this.roomAt(BlockPos.containing(p));
			if (in != null) {
				return in;
			}
			Room best = null;
			double bestD = Double.MAX_VALUE;
			for (Room r : this.rooms) {
				double d = r.middle().distanceTo(p) + Math.abs(r.center.getY() - p.y) * 4;
				if (d < bestD) {
					bestD = d;
					best = r;
				}
			}
			return best;
		}
	}

	/** Forgets every scan (a new match, a new map). */
	public static void clear() {
		CACHE.clear();
	}

	/** The building whose floor this point is on, or null (outdoors, or under no real building). */
	public static Building at(ServerLevel level, BlockPos pos) {
		int now = level.getServer().getTickCount();
		CACHE.removeIf(b -> b.level != level || now - b.made > KEEP_TICKS || now < b.made);
		BlockPos start = Nav.snap(level, pos);
		for (Building b : CACHE) {
			if (b.contains(start)) {
				return b.real ? b : null;
			}
		}
		if (!Nav.standable(level, start) || level.canSeeSky(start)) {
			return null;
		}
		Building b = scan(level, start.immutable(), now);
		CACHE.add(b);
		if (CACHE.size() > 48) {
			CACHE.removeFirst();
		}
		return b.real ? b : null;
	}

	/**
	 * The building at this point, or else the one whose door is nearest it within {@code r} blocks (someone seen
	 * last in a yard, by a porch).
	 */
	public static Building near(ServerLevel level, Vec3 point, int r) {
		BlockPos at = BlockPos.containing(point);
		Building b = at(level, at);
		if (b != null) {
			return b;
		}
		BlockPos best = null;
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -2, -r), at.offset(r, 3, r))) {
			if (DoorLocks.isDoor(level.getBlockState(p)) && (best == null || p.distSqr(at) < best.distSqr(at))) {
				best = p.immutable();
			}
		}
		if (best == null) {
			return null;
		}
		BlockPos door = DoorLocks.base(level, best);
		b = at(level, door);
		if (b != null) {
			return b;
		}
		// The door's own cell may be out under the sky: a step either way through it.
		for (Direction d : Direction.Plane.HORIZONTAL) {
			b = at(level, door.relative(d));
			if (b != null) {
				return b;
			}
		}
		return null;
	}

	private static Building scan(ServerLevel level, BlockPos start, int now) {
		List<BlockPos> ways = new ArrayList<>();
		List<BlockPos> outside = new ArrayList<>();
		LongOpenHashSet wayCells = new LongOpenHashSet();
		Set<BlockPos> windows = new LinkedHashSet<>();
		Nav.Options opt = Nav.Options.climber().lockedDoors(0).nodes(MAX_CELLS);
		LongOpenHashSet cells = Nav.flood(level, start, opt,
			p -> Math.abs(p.getX() - start.getX()) <= REACH && Math.abs(p.getZ() - start.getZ()) <= REACH && Math.abs(p.getY() - start.getY()) <= 12
				&& !level.canSeeSky(p),
			(from, to, step, glass) -> {
				if (!level.canSeeSky(to)) {
					return;
				}
				if (step == Nav.Step.WINDOW) {
					windows.add(glass);
				} else if (wayCells.add(from.asLong())) {
					ways.add(from);
					outside.add(to);
				}
			});
		List<BlockPos> doors = new ArrayList<>();
		LongOpenHashSet gaps = new LongOpenHashSet();
		LongIterator it = cells.iterator();
		while (it.hasNext()) {
			long c = it.nextLong();
			BlockPos p = BlockPos.of(c);
			if (DoorLocks.isDoor(level.getBlockState(p))) {
				doors.add(p);
				gaps.add(c);
			} else if (gap(level, cells, p, Direction.EAST) || gap(level, cells, p, Direction.NORTH)) {
				gaps.add(c);
			}
		}
		// Rooms: what hangs together on one level between the doorways.
		Long2IntOpenHashMap roomOf = new Long2IntOpenHashMap();
		roomOf.defaultReturnValue(-1);
		List<Room> rooms = new ArrayList<>();
		LongOpenHashSet taken = new LongOpenHashSet();
		it = cells.iterator();
		while (it.hasNext()) {
			long c = it.nextLong();
			if (gaps.contains(c) || !taken.add(c)) {
				continue;
			}
			List<BlockPos> part = new ArrayList<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(BlockPos.of(c));
			while (!queue.isEmpty()) {
				BlockPos p = queue.poll();
				part.add(p);
				for (Direction d : Direction.Plane.HORIZONTAL) {
					long n = p.relative(d).asLong();
					if (cells.contains(n) && !gaps.contains(n) && taken.add(n)) {
						queue.add(BlockPos.of(n));
					}
				}
			}
			if (part.size() >= MIN_ROOM) {
				Room room = new Room(level, rooms.size(), part);
				for (BlockPos p : part) {
					roomOf.put(p.asLong(), room.index);
				}
				rooms.add(room);
			}
		}
		Set<BlockPos> breakers = new LinkedHashSet<>();
		it = cells.iterator();
		while (it.hasNext()) {
			BlockPos p = BlockPos.of(it.nextLong());
			for (int dy = 0; dy <= 2; dy++) {
				BlockPos q = p.above(dy);
				if (level.getBlockState(q).is(Blocks.LEVER) && LightSystem.isBreaker(level, q)) {
					breakers.add(q);
				}
			}
		}
		// The shade of a tree or a carport is no building: one has a door or a window.
		boolean real = cells.size() >= 12 && !rooms.isEmpty() && (!doors.isEmpty() || !windows.isEmpty());
		return new Building(level, now, real, cells, roomOf, rooms, doors, ways, outside, new ArrayList<>(windows), new ArrayList<>(breakers));
	}

	/** A doorway across this axis: full-height wall on both sides of the cell, floor on through it the other way. */
	private static boolean gap(ServerLevel level, LongOpenHashSet cells, BlockPos p, Direction side) {
		Direction through = side.getClockWise();
		return wall(level, cells, p.relative(side)) && wall(level, cells, p.relative(side.getOpposite()))
			&& cells.contains(p.relative(through).asLong()) && cells.contains(p.relative(through.getOpposite()).asLong());
	}

	private static boolean wall(ServerLevel level, LongOpenHashSet cells, BlockPos p) {
		return !cells.contains(p.asLong()) && Nav.passable(level, p) == 0 && Nav.passable(level, p.above()) == 0;
	}

	private static double sq(double v) {
		return v * v;
	}
}
