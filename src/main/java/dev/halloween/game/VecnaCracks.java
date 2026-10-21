package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * The rift as the town sees it from above (Stranger Things, the end of season four): burning cracks torn through the
 * ground from the altar out across the whole town, following the streets, splitting as they go, a broken burning pit
 * where they meet. A thin glowing seam (shroomlight) in charred black ground (blackstone), red rot (crimson nylium, its
 * roots) only in patches along it; they spread from the middle over some seconds. Only the open ground: under a house a crack runs on unseen. All of it is put back after
 * the match, even after a crash (kept in {@link Saved}). The haze and the embers over them are client.VecnaClient's.
 */
public final class VecnaCracks {
	/** A cell to tear open: where, what it becomes, and when (its distance along the crack). */
	private record Cell(BlockPos pos, BlockState state, int order) {
	}

	private static final List<Cell> PENDING = new ArrayList<>();
	private static int placed;
	private static int startTick;
	private static int spreadTicks;

	private VecnaCracks() {
	}

	/** The cracks torn out from {@code center} as far as {@code radius}, spreading over {@code ticks}. */
	public static void open(ServerLevel level, Vec3 center, int radius, int ticks) {
		PENDING.clear();
		placed = 0;
		startTick = level.getServer().getTickCount();
		spreadTicks = Math.max(1, ticks);
		RandomSource r = level.getRandom();
		Set<Long> used = new HashSet<>();
		BlockState core = Blocks.SHROOMLIGHT.defaultBlockState();
		BlockState rot = Blocks.BLACKSTONE.defaultBlockState();
		BlockPos c = BlockPos.containing(center);
		// The pit where they meet: a broken burning disc.
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > 3.4 || d > 2.5 && r.nextFloat() < 0.5f) {
					continue;
				}
				boolean burning = d < 0.8 || d < 1.6 && r.nextFloat() < 0.25f;
				add(level, used, c.getX() + dx, c.getY(), c.getZ() + dz, burning ? core : edge(rot, r), (int) d, r);
			}
		}
		// The main cracks: out every which way, bending towards the streets, splitting.
		int arms = 5 + r.nextInt(3);
		float base = r.nextFloat() * Mth.TWO_PI;
		for (int i = 0; i < arms; i++) {
			float angle = base + i * Mth.TWO_PI / arms + (r.nextFloat() - 0.5f) * 0.5f;
			crack(level, used, center, angle, radius, 3, 1, r, core, rot);
		}
		PENDING.sort((a, b) -> Integer.compare(a.order, b.order));
		Halloween.LOGGER.info("Vecna: rift cracks of {} cells", PENDING.size());
	}

	/** One crack: a meandering walk over the ground, glowing at its heart, rotting at its edges; it splits now and then. */
	private static void crack(ServerLevel level, Set<Long> used, Vec3 from, float angle, int length, int order0, int width,
		RandomSource r, BlockState core, BlockState rot) {
		double x = from.x;
		double z = from.z;
		int y = BlockPos.containing(from).getY();
		for (int step = 0; step < length; step++) {
			// Bend a little, and towards a street when one is beside.
			float best = angle;
			double bestScore = -1;
			for (float turn : new float[]{-0.35f, 0f, 0.35f}) {
				float a = angle + turn + (r.nextFloat() - 0.5f) * 0.25f;
				int nx = Mth.floor(x - Mth.sin(a) * 2);
				int nz = Mth.floor(z + Mth.cos(a) * 2);
				int ny = surface(level, nx, y, nz);
				double score = r.nextDouble() * 0.6 + (turn == 0 ? 0.4 : 0) + (ny != Integer.MIN_VALUE && Minimap.road(level.getBlockState(new BlockPos(nx, ny, nz))) ? 1.2 : 0);
				if (score > bestScore) {
					bestScore = score;
					best = a;
				}
			}
			angle = best;
			x -= Mth.sin(angle);
			z += Mth.cos(angle);
			BlockPos p = BlockPos.containing(x, y, z);
			if (!Nav.inArea(p)) {
				return;
			}
			int sy = surface(level, p.getX(), y, p.getZ());
			if (sy != Integer.MIN_VALUE) {
				y = sy;
			}
			int order = order0 + step;
			// A burning seam in charred ground, rotting here and there (a little wider near the pit).
			int w = step < 6 ? width + 1 : width;
			add(level, used, p.getX(), y, p.getZ(), core, order, r);
			float side = angle + Mth.HALF_PI;
			for (int k = 1; k <= w; k++) {
				for (int s : new int[]{-1, 1}) {
					if (k == w && k > 1 && r.nextFloat() < 0.6f || r.nextFloat() < 0.12f) {
						continue;
					}
					int ex = Mth.floor(x - Mth.sin(side) * k * s);
					int ez = Mth.floor(z + Mth.cos(side) * k * s);
					add(level, used, ex, y, ez, edge(rot, r), order, r);
				}
			}
			if (step > 6 && r.nextFloat() < 0.035f && length - step > 12) {
				// A crack splits off.
				crack(level, used, new Vec3(x, y, z), angle + (r.nextBoolean() ? 1 : -1) * (0.6f + r.nextFloat() * 0.6f),
					Math.min(length - step, 12 + r.nextInt(30)), order, 1, r, core, rot);
			}
		}
	}

	/** The ground beside a seam: mostly charred black, red rot in patches. */
	private static BlockState edge(BlockState charred, RandomSource r) {
		return r.nextFloat() < 0.22f ? Blocks.CRIMSON_NYLIUM.defaultBlockState() : charred;
	}

	/** The ground's top block at this column near {@code y}, open to the sky (else MIN_VALUE: under a roof). */
	private static int surface(ServerLevel level, int x, int y, int z) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = 3; dy >= -4; dy--) {
			p.set(x, y + dy, z);
			BlockState s = level.getBlockState(p);
			if (s.isCollisionShapeFullBlock(level, p) && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
				return level.canSeeSky(p.above()) ? p.getY() : Integer.MIN_VALUE;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static void add(ServerLevel level, Set<Long> used, int x, int y, int z, BlockState state, int order, RandomSource r) {
		int sy = surface(level, x, y, z);
		if (sy == Integer.MIN_VALUE || Math.abs(sy - y) > 2) {
			return;
		}
		BlockPos p = new BlockPos(x, sy, z);
		if (!used.add(p.asLong())) {
			return;
		}
		BlockState there = level.getBlockState(p);
		if (there.is(Blocks.SHROOMLIGHT) || there.is(Blocks.CRIMSON_NYLIUM) || there.is(Blocks.BLACKSTONE) || there.hasBlockEntity() || there.is(Blocks.BARRIER)) {
			return;
		}
		PENDING.add(new Cell(p, state, order));
		// Red roots out of the rot here and there.
		if (state.is(Blocks.CRIMSON_NYLIUM) && r.nextFloat() < 0.3f && level.getBlockState(p.above()).isAir()) {
			PENDING.add(new Cell(p.above(), Blocks.CRIMSON_ROOTS.defaultBlockState(), order + 1));
		}
	}

	/** The cracks spreading out: as much as the time gone allows. */
	public static void tick(ServerLevel level, int now) {
		if (placed >= PENDING.size()) {
			return;
		}
		int maxOrder = PENDING.get(PENDING.size() - 1).order;
		float k = Math.min(1f, (now - startTick) / (float) spreadTicks);
		int upTo = Math.round(maxOrder * k);
		Saved saved = Saved.get(level);
		List<Saved.Entry> before = new ArrayList<>();
		while (placed < PENDING.size() && PENDING.get(placed).order <= upTo) {
			Cell c = PENDING.get(placed++);
			before.add(new Saved.Entry(c.pos, level.getBlockState(c.pos)));
			level.setBlock(c.pos, c.state, Block.UPDATE_CLIENTS);
		}
		if (!before.isEmpty()) {
			saved.add(before);
		}
	}

	/** Everything torn open put back as it was. */
	public static void clear(ServerLevel level) {
		PENDING.clear();
		placed = 0;
		Saved saved = Saved.get(level);
		if (saved.entries.isEmpty()) {
			return;
		}
		// Back to front: a root put back before the ground under it.
		for (int i = saved.entries.size() - 1; i >= 0; i--) {
			Saved.Entry e = saved.entries.get(i);
			level.setBlock(e.pos(), e.state(), Block.UPDATE_CLIENTS);
		}
		saved.entries.clear();
		saved.setDirty();
	}

	/** What the cracks tore through, kept with the world until it is put back. */
	public static final class Saved extends SavedData {
		record Entry(BlockPos pos, BlockState state) {
			static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
				BlockState.CODEC.fieldOf("state").forGetter(Entry::state)
			).apply(i, Entry::new));
		}

		private static final Codec<Saved> CODEC = Entry.CODEC.listOf().xmap(Saved::new, s -> List.copyOf(s.entries));
		public static final SavedDataType<Saved> TYPE = new SavedDataType<>(Halloween.id("vecna_cracks"), Saved::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

		final List<Entry> entries = new ArrayList<>();

		public Saved() {
		}

		private Saved(List<Entry> entries) {
			this.entries.addAll(entries);
		}

		static Saved get(ServerLevel level) {
			return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
		}

		void add(List<Entry> list) {
			this.entries.addAll(list);
			this.setDirty();
		}
	}
}
