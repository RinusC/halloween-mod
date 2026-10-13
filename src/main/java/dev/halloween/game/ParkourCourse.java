package dev.halloween.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The gym's parkour (see Lobby): one lap round the walls from a pad in the floor up to the rafters, about sixty
 * jumps in four parts, the first three ending on a checkpoint (a fall to the floor puts you back on the last one, the clock
 * running on). It gets harder as it climbs: plain blocks and steps up, then fence and wall posts, ladders, sprint
 * gaps, a run over ice into a long jump, posts two apart. Laid out by a turtle that walks a lane a few blocks in from
 * the walls, turning at the corners; everything is relative to the lobby's corner.
 */
final class ParkourCourse {
	enum Kind {
		START, BLOCK, POST, ICE, LADDER, CHECKPOINT, FINISH
	}

	/** A place to stand on (its block), and what it is. */
	record Pad(BlockPos pos, Kind kind) {
	}

	/** The lane the course keeps to (the gym is x 1..58, z 33..78; the bleachers start at x 55). */
	private static final int X_MIN = 4;
	private static final int X_MAX = 51;
	private static final int Z_MIN = 37;
	private static final int Z_MAX = 75;

	final List<Pad> pads = new ArrayList<>();
	/** Indices into pads of the checkpoints, in order (the finish is the last pad). */
	final List<Integer> checkpoints = new ArrayList<>();
	/** Every block, relative to the lobby's corner, in the order laid. */
	final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

	private BlockPos at;
	private Direction dir = Direction.SOUTH;
	/** How far the pad stood on reaches forwards from its centre (1 on a 3x3 platform). */
	private int reach;

	private static ParkourCourse built;

	static ParkourCourse get() {
		if (built == null) {
			built = make();
		}
		return built;
	}

	private static ParkourCourse make() {
		ParkourCourse c = new ParkourCourse();
		c.at = new BlockPos(6, -1, 37);
		c.add(c.at, Kind.START, MapPlan.b("lime_concrete"));

		// I. Warming up along the west wall: blocks, steps up, small sideways hops, a ladder.
		c.jump(1, 1, 0);
		c.jump(1, 0, 0);
		c.jump(2, 0, 0);
		c.jump(1, 1, 1);
		c.jump(2, 0, 0);
		c.jump(1, 1, -1);
		c.jump(2, 0, -1);
		c.jump(3, 0, 0);
		c.jump(1, 1, 1);
		c.jump(2, -1, 1);
		c.jump(2, 1, 0);
		c.jump(1, 1, -1);
		c.ladder(3);
		c.jump(2, 0, 0);
		c.jump(2, 0, 1);
		c.checkpoint(1, 0);

		// II. Along the north wall: fence posts, wall posts, a sprint gap, another ladder.
		c.post(1, 0, 0, true);
		c.post(1, 0, 0, true);
		c.post(1, 0, 1, true);
		c.post(1, 0, 0, true);
		c.jump(2, 0, 0);
		c.post(1, 1, 0, false);
		c.post(1, 0, -1, false);
		c.post(2, 0, 0, false);
		c.jump(2, 0, 0);
		c.jump(3, 0, 0);
		c.jump(1, 1, 1);
		c.jump(2, 0, -1);
		c.jump(2, 1, 0);
		c.jump(2, 0, 0);
		c.jump(3, 0, 0);
		c.jump(2, 1, 1);
		c.checkpoint(2, 0);

		// III. The east wall, high up: a run over ice into a long jump, posts two apart, steps up with a twist.
		c.ice(5);
		c.jump(3, 0, 0);
		c.jump(2, 1, 0);
		c.post(2, 0, 0, true);
		c.post(2, 0, 1, true);
		c.jump(2, -1, 0);
		c.jump(1, 0, -1);
		c.post(2, 0, 0, false);
		c.jump(3, 0, 1);
		c.jump(1, 1, 0);
		c.jump(2, 0, -1);
		c.jump(2, 0, 0);
		c.post(1, 0, 1, true);
		c.jump(3, -1, 0);
		c.checkpoint(1, 1);

		// IV. Back along the south wall under the roof: everything at once, and up to the finish.
		c.post(1, 0, 0, true);
		c.post(2, 0, -1, true);
		c.post(1, 0, 0, true);
		c.jump(3, 0, 0);
		c.jump(2, 0, 1);
		c.jump(2, 0, -1);
		c.ice(3);
		c.jump(3, 0, 0);
		c.jump(3, -1, 0);
		c.jump(1, 1, 1);
		c.post(2, 0, 0, false);
		c.jump(2, 0, -1);
		c.ladder(2);
		c.jump(2, 1, 0);
		c.finish(2, 0);
		return c;
	}

	private void add(BlockPos pos, Kind kind, BlockState state) {
		this.blocks.put(pos, state);
		this.pads.add(new Pad(pos, kind));
	}

	private Direction right() {
		return this.dir.getClockWise();
	}

	/**
	 * Where a pad lands {@code forward} ahead, {@code side} to the right, {@code dy} up; at the lane's end turning
	 * left (round the gym: down the west side, along the far wall, back up the east side, along the near wall).
	 */
	private BlockPos ahead(int forward, int dy, int side) {
		BlockPos p = this.at.relative(this.dir, forward + this.reach).relative(this.right(), side).above(dy);
		for (int turns = 0; turns < 3 && !inLane(p); turns++) {
			this.dir = this.dir.getCounterClockWise();
			p = this.at.relative(this.dir, forward + this.reach).relative(this.right(), side).above(dy);
		}
		if (!inLane(p)) {
			throw new IllegalStateException("The parkour runs out of the gym at " + p);
		}
		return p;
	}

	private static boolean inLane(BlockPos p) {
		return p.getX() >= X_MIN && p.getX() <= X_MAX && p.getZ() >= Z_MIN && p.getZ() <= Z_MAX;
	}

	/** A block {@code gap} blocks of air ahead. */
	private void jump(int gap, int dy, int side) {
		BlockPos p = this.ahead(gap + 1, dy, side);
		String[] kinds = {"spruce_planks", "hay_block", "stripped_oak_log", "dark_oak_planks", "bone_block", "orange_terracotta"};
		BlockState s = MapPlan.b(kinds[Math.floorMod(p.getX() * 7 + p.getZ() * 3 + p.getY(), kinds.length)]);
		if (this.pads.size() % 9 == 4) {
			s = MapPlan.b("jack_o_lantern");
		}
		this.add(p, Kind.BLOCK, s);
		this.at = p;
		this.reach = 0;
	}

	/** A post (fence: thin; wall: thicker) half a block taller than a block. */
	private void post(int gap, int dy, int side, boolean fence) {
		BlockPos p = this.ahead(gap + 1, dy, side);
		this.add(p, Kind.POST, fence ? MapPlan.b("dark_oak_fence") : MapPlan.b("mossy_cobblestone_wall"));
		this.at = p;
		this.reach = 0;
	}

	/** A ladder up the near face of a pillar {@code h} high, its top the next pad. */
	private void ladder(int h) {
		BlockPos pillar = this.ahead(2, 0, 0);
		Direction toPillar = this.dir;
		BlockPos ladder = pillar.relative(toPillar.getOpposite());
		for (int y = 1; y <= h; y++) {
			this.blocks.put(pillar.above(y), MapPlan.b("stripped_dark_oak_log"));
			this.blocks.put(ladder.above(y), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toPillar.getOpposite()));
		}
		this.add(pillar.above(h), Kind.LADDER, MapPlan.b("stripped_dark_oak_log"));
		this.at = pillar.above(h);
		this.reach = 0;
	}

	/** A strip of ice straight ahead to run over (into a long jump). */
	private void ice(int n) {
		BlockPos last = this.at;
		BlockPos first = this.ahead(1, 0, 0);
		Direction d = this.dir;
		for (int k = 0; k < n; k++) {
			last = first.relative(d, k);
			this.add(last, Kind.ICE, MapPlan.b("packed_ice"));
		}
		this.at = last;
		this.reach = 0;
	}

	/** A 3x3 platform ({@code gap} blocks of air to its near edge): a checkpoint. */
	private void checkpoint(int gap, int dy) {
		this.platform(gap, dy, Kind.CHECKPOINT, MapPlan.b("light_blue_concrete"), MapPlan.b("spruce_planks"));
		this.checkpoints.add(this.pads.size() - 1);
	}

	private void finish(int gap, int dy) {
		this.platform(gap, dy, Kind.FINISH, MapPlan.b("gold_block"), MapPlan.b("dark_oak_planks"));
	}

	private void platform(int gap, int dy, Kind kind, BlockState middle, BlockState rim) {
		BlockPos c = this.ahead(gap + 2, dy, 0);
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if (a != 0 || b != 0) {
					this.blocks.put(c.offset(a, 0, b), rim);
				}
			}
		}
		this.add(c, kind, middle);
		this.at = c;
		this.reach = 1;
	}

	/** The checkpoint (or start) this block belongs to: its index into checkpoints, -1 the start, or null. */
	Integer checkpointOf(BlockPos rel) {
		if (rel.equals(this.pads.getFirst().pos())) {
			return -1;
		}
		for (int i = 0; i < this.checkpoints.size(); i++) {
			BlockPos c = this.pads.get(this.checkpoints.get(i)).pos();
			if (Math.abs(rel.getX() - c.getX()) <= 1 && Math.abs(rel.getZ() - c.getZ()) <= 1 && rel.getY() == c.getY()) {
				return i;
			}
		}
		return null;
	}

	boolean isFinish(BlockPos rel) {
		BlockPos f = this.pads.getLast().pos();
		return Math.abs(rel.getX() - f.getX()) <= 1 && Math.abs(rel.getZ() - f.getZ()) <= 1 && rel.getY() == f.getY();
	}

	/**
	 * What would make the course unplayable: a jump too long or too high, something of the course over a pad or in
	 * the way of a jump, the top too near the rafters. Empty when it is all right.
	 */
	List<String> problems(int ceiling) {
		List<String> out = new ArrayList<>();
		java.util.Set<BlockPos> ladders = new java.util.HashSet<>();
		for (Map.Entry<BlockPos, BlockState> e : this.blocks.entrySet()) {
			if (e.getValue().getBlock() instanceof LadderBlock) {
				ladders.add(e.getKey());
			}
		}
		for (int i = 0; i < this.pads.size(); i++) {
			Pad p = this.pads.get(i);
			for (int y = 1; y <= 2; y++) {
				if (this.blocks.containsKey(p.pos().above(y))) {
					out.add("something over pad " + i + " at " + p.pos());
				}
			}
			if (p.pos().getY() + 4 > ceiling) {
				out.add("pad " + i + " too near the roof");
			}
			if (i == 0 || p.kind() == Kind.ICE && this.pads.get(i - 1).kind() == Kind.ICE) {
				continue;
			}
			Pad q = this.pads.get(i - 1);
			int dy = p.pos().getY() - q.pos().getY();
			// Edge to edge (platforms reach a block out from their centre).
			int ext = (p.kind() == Kind.CHECKPOINT || p.kind() == Kind.FINISH ? 1 : 0) + (q.kind() == Kind.CHECKPOINT ? 1 : 0);
			double dx = Math.max(0, Math.abs(p.pos().getX() - q.pos().getX()) - 1 - ext);
			double dz = Math.max(0, Math.abs(p.pos().getZ() - q.pos().getZ()) - 1 - ext);
			double gap = Math.sqrt(dx * dx + dz * dz);
			if (p.kind() != Kind.LADDER && (dy > 1 || gap > (dy > 0 ? 2.9 : 3.9))) {
				out.add(String.format(java.util.Locale.ROOT, "jump %d too far: gap %.1f, up %d", i, gap, dy));
			}
			// Nothing in the way: the cells over the line between them, up to three blocks over the higher.
			int steps = 12;
			for (int s = 1; s < steps; s++) {
				double t = s / (double) steps;
				int x = (int) Math.floor(q.pos().getX() + 0.5 + (p.pos().getX() - q.pos().getX()) * t);
				int z = (int) Math.floor(q.pos().getZ() + 0.5 + (p.pos().getZ() - q.pos().getZ()) * t);
				int y0 = Math.max(q.pos().getY(), p.pos().getY()) + 1;
				for (int y = y0; y <= y0 + 2; y++) {
					BlockPos c = new BlockPos(x, y, z);
					if (this.blocks.containsKey(c) && !ladders.contains(c) && p.kind() != Kind.LADDER && !c.equals(p.pos())) {
						out.add("in the way of jump " + i + " at " + c);
					}
				}
			}
		}
		return out;
	}

	/** The highest pad. */
	int top() {
		int y = Integer.MIN_VALUE;
		for (Pad p : this.pads) {
			y = Math.max(y, p.pos().getY());
		}
		return y;
	}

	/** Jumps from pad to pad. */
	int jumps() {
		int n = 0;
		for (Pad p : this.pads) {
			if (p.kind() != Kind.ICE && p.kind() != Kind.START) {
				n++;
			}
		}
		return n;
	}
}
