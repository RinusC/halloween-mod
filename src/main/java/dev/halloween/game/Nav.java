package dev.halloween.game;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Grid path finding for bots and townsfolk. Nodes are the blocks a body stands in (feet). Walking steps
 * go to the eight neighbours, up one block (a jump) or down up to three. Doors are passed unless
 * locked; windows (glass with a wall below) are crossed as a single climb when allowed.
 */
public final class Nav {
	public enum Step {
		WALK, DOOR, WINDOW
	}

	/** A point of the path and how it is reached from the previous one. For WINDOW, {@code glass} is the pane to climb. */
	public record Node(BlockPos pos, Step step, BlockPos glass) {
	}

	public static final class Options {
		boolean windows;
		int maxNodes = 5000;
		double windowCost = 5;

		public static Options walker() {
			return new Options();
		}

		public static Options climber() {
			Options o = new Options();
			o.windows = true;
			return o;
		}
	}

	/** Scores a reachable node during an open search: higher is better. */
	@FunctionalInterface
	public interface Scorer {
		double score(BlockPos pos, double cost);
	}

	private Nav() {
	}

	// ---------------------------------------------------------------- cells

	private static boolean isDoor(BlockState state) {
		return state.getBlock() instanceof DoorBlock;
	}

	/** 0 blocked, 1 free, 2 door. */
	static int passable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (isDoor(state)) {
			return DoorLocks.isLocked(level, pos) ? 0 : 2;
		}
		if (state.getBlock() instanceof FenceGateBlock) {
			return state.getValue(BlockStateProperties.OPEN) ? 1 : 0;
		}
		if (Windows.isWindow(state)) {
			return 0;
		}
		if (!state.getFluidState().isEmpty()) {
			return 0;
		}
		return state.getCollisionShape(level, pos).isEmpty() ? 1 : 0;
	}

	static boolean floor(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (isDoor(state) || state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock
			|| state.getBlock() instanceof FenceGateBlock) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5 && shape.max(Direction.Axis.Y) <= 1.0;
	}

	/** Can a body stand with its feet in {@code feet}? */
	public static boolean standable(Level level, BlockPos feet) {
		return floor(level, feet.below()) && passable(level, feet) != 0 && passable(level, feet.above()) != 0;
	}

	/** Snaps a position to a standable node nearby (an entity half inside a slab, on a ledge...). */
	public static BlockPos snap(Level level, BlockPos pos) {
		if (standable(level, pos)) {
			return pos;
		}
		for (int dy : new int[]{1, -1, 2}) {
			if (standable(level, pos.above(dy))) {
				return pos.above(dy);
			}
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (standable(level, pos.relative(d))) {
				return pos.relative(d);
			}
		}
		return pos;
	}

	private static Step stepInto(Level level, BlockPos feet) {
		return passable(level, feet) == 2 || passable(level, feet.above()) == 2 ? Step.DOOR : Step.WALK;
	}

	// ---------------------------------------------------------------- neighbours

	private interface Sink {
		void accept(BlockPos pos, Step step, BlockPos glass, double cost);
	}

	private static void neighbours(Level level, BlockPos cur, Options opt, Sink sink) {
		boolean headroom = passable(level, cur.above(2)) != 0;
		boolean[] open = new boolean[4];
		int i = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = cur.relative(d);
			if (standable(level, n)) {
				sink.accept(n, stepInto(level, n), null, 1);
				open[i] = passable(level, n) == 1 && passable(level, n.above()) == 1;
			} else if (headroom && passable(level, n) == 0 && standable(level, n.above()) && passable(level, n.above()) == 1) {
				sink.accept(n.above(), Step.WALK, null, 1.6);
			} else if (passable(level, n) != 0 && passable(level, n.above()) != 0) {
				for (int k = 1; k <= 3; k++) {
					BlockPos down = n.below(k);
					if (standable(level, down)) {
						sink.accept(down, Step.WALK, null, 1 + k * 0.4);
						break;
					}
					if (passable(level, down) == 0) {
						break;
					}
				}
			} else if (opt.windows) {
				// A window: wall at feet level with glass above it, standable ground right behind.
				BlockPos glass = n.above();
				if (Windows.isWindow(level.getBlockState(glass)) && !standable(level, n)) {
					BlockPos beyond = n.relative(d);
					if (standable(level, beyond)) {
						sink.accept(beyond, Step.WINDOW, glass.immutable(), opt.windowCost);
					}
				}
			}
			i++;
		}
		// Diagonals only across open floor on both sides, never past doors.
		Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
		for (int a = 0; a < 4; a++) {
			int b = (a + 1) % 4;
			if (!open[indexOf(dirs[a])] || !open[indexOf(dirs[b])]) {
				continue;
			}
			BlockPos n = cur.relative(dirs[a]).relative(dirs[b]);
			if (standable(level, n) && passable(level, n) == 1 && passable(level, n.above()) == 1) {
				sink.accept(n, Step.WALK, null, 1.414);
			}
		}
	}

	private static int indexOf(Direction d) {
		int i = 0;
		for (Direction h : Direction.Plane.HORIZONTAL) {
			if (h == d) {
				return i;
			}
			i++;
		}
		return 0;
	}

	// ---------------------------------------------------------------- searches

	private static final class Entry implements Comparable<Entry> {
		final long pos;
		final double f;

		Entry(long pos, double f) {
			this.pos = pos;
			this.f = f;
		}

		@Override
		public int compareTo(Entry o) {
			return Double.compare(this.f, o.f);
		}
	}

	private static double octile(BlockPos a, BlockPos b) {
		int dx = Math.abs(a.getX() - b.getX());
		int dz = Math.abs(a.getZ() - b.getZ());
		return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(a.getY() - b.getY());
	}

	/**
	 * A* from {@code start} to any node that satisfies {@code goal}, guided towards {@code target}.
	 * Returns the path (without the start), the path to the closest node found if the goal is
	 * unreachable within the node budget, or an empty list if nothing better than standing still exists.
	 */
	public static List<Node> find(Level level, BlockPos start, BlockPos target, Predicate<BlockPos> goal, Options opt) {
		start = snap(level, start);
		Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
		g.defaultReturnValue(Double.MAX_VALUE);
		Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
		Long2ObjectOpenHashMap<Node> via = new Long2ObjectOpenHashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>();
		long s = start.asLong();
		g.put(s, 0);
		open.add(new Entry(s, octile(start, target)));
		long best = s;
		double bestH = octile(start, target);
		int expanded = 0;
		while (!open.isEmpty() && expanded < opt.maxNodes) {
			Entry e = open.poll();
			BlockPos cur = BlockPos.of(e.pos);
			double gc = g.get(e.pos);
			if (e.f > gc + octile(cur, target) + 1.0E-6) {
				continue;
			}
			expanded++;
			if (goal.test(cur)) {
				return build(e.pos, s, parent, via);
			}
			double h = octile(cur, target);
			if (h < bestH) {
				bestH = h;
				best = e.pos;
			}
			neighbours(level, cur, opt, (n, step, glass, cost) -> {
				long key = n.asLong();
				double ng = gc + cost;
				if (ng < g.get(key)) {
					g.put(key, ng);
					parent.put(key, e.pos);
					via.put(key, new Node(n.immutable(), step, glass));
					open.add(new Entry(key, ng + octile(n, target)));
				}
			});
		}
		return best == s ? List.of() : build(best, s, parent, via);
	}

	public static List<Node> to(Level level, BlockPos start, BlockPos target, double within, Options opt) {
		double sq = within * within;
		return find(level, start, target, p -> p.distToCenterSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) <= sq, opt);
	}

	/**
	 * Dijkstra out to {@code maxCost}; returns the path to the node with the best score (empty if the
	 * start itself is best).
	 */
	public static List<Node> search(Level level, BlockPos start, double maxCost, Options opt, Scorer scorer) {
		start = snap(level, start);
		Long2DoubleOpenHashMap g = new Long2DoubleOpenHashMap();
		g.defaultReturnValue(Double.MAX_VALUE);
		Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
		Long2ObjectOpenHashMap<Node> via = new Long2ObjectOpenHashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>();
		long s = start.asLong();
		g.put(s, 0);
		open.add(new Entry(s, 0));
		long best = s;
		double bestScore = scorer.score(start, 0);
		int expanded = 0;
		while (!open.isEmpty() && expanded < opt.maxNodes) {
			Entry e = open.poll();
			double gc = g.get(e.pos);
			if (e.f > gc + 1.0E-6) {
				continue;
			}
			expanded++;
			BlockPos cur = BlockPos.of(e.pos);
			double score = scorer.score(cur, gc);
			if (score > bestScore) {
				bestScore = score;
				best = e.pos;
			}
			neighbours(level, cur, opt, (n, step, glass, cost) -> {
				double ng = gc + cost;
				long key = n.asLong();
				if (ng <= maxCost && ng < g.get(key)) {
					g.put(key, ng);
					parent.put(key, e.pos);
					via.put(key, new Node(n.immutable(), step, glass));
					open.add(new Entry(key, ng));
				}
			});
		}
		return best == s ? List.of() : build(best, s, parent, via);
	}

	private static List<Node> build(long end, long start, Long2LongOpenHashMap parent, Long2ObjectOpenHashMap<Node> via) {
		List<Node> path = new ArrayList<>();
		long cur = end;
		while (cur != start) {
			path.add(via.get(cur));
			cur = parent.get(cur);
		}
		Collections.reverse(path);
		return path;
	}
}
