package dev.halloween.game;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.halloween.Halloween;
import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * Telephone booths, put up round the town for a match against Vecna: a call from one lifts his curse off the caller
 * (see {@link VecnaCurse}). Each is a glass box open on one side with the phone on a shelf at the back, a lamp in
 * its roof. A call takes holding use with reaction checks; the booth is dead for a minute afterwards, and Vecna hears
 * where it rang. What stood there before comes back after the match, even after a crash (kept in {@link Saved}).
 */
public final class Booths {
	/** A booth: its phone, the cell a caller stands in, and the tick until which it is dead (after a call). */
	public static final class Booth {
		public final BlockPos phone;
		public final BlockPos inside;
		int restUntil;

		Booth(BlockPos phone, BlockPos inside) {
			this.phone = phone;
			this.inside = inside;
		}

		public boolean resting(int now) {
			return now < this.restUntil;
		}
	}

	private static final List<Booth> BOOTHS = new ArrayList<>();

	private Booths() {
	}

	public static List<Booth> all() {
		return BOOTHS;
	}

	public static Booth at(BlockPos phone) {
		for (Booth b : BOOTHS) {
			if (b.phone.equals(phone)) {
				return b;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- putting them up

	/** Four booths far apart in the open, inside the match area, away from where anybody starts. */
	public static void setup(ServerLevel level, BlockPos center, int radius, RandomSource random) {
		clear(level);
		HwConfig cfg = HwConfig.get();
		List<BlockPos> starts = new ArrayList<>();
		starts.add(center);
		for (MapPoints.Type t : new MapPoints.Type[]{MapPoints.Type.SPAWN_CIVILIAN, MapPoints.Type.SPAWN_SHAPE, MapPoints.Type.SPAWN_DEPUTY}) {
			starts.addAll(MapPoints.get(level).list(t));
		}
		List<BlockPos> found = new ArrayList<>();
		List<BlockPos> paved = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = center.getX() - radius; x <= center.getX() + radius; x += 2) {
			for (int z = center.getZ() - radius; z <= center.getZ() + radius; z += 2) {
				for (int dy = -1; dy <= 1; dy++) {
					p.set(x, center.getY() + dy, z);
					if (!level.hasChunkAt(p) || !Nav.inArea(p.offset(-3, 0, -3)) || !Nav.inArea(p.offset(3, 0, 3)) || !fits(level, p)) {
						continue;
					}
					boolean near = false;
					for (BlockPos s : starts) {
						near |= s.distSqr(p) < 12 * 12;
					}
					if (near) {
						continue;
					}
					found.add(p.immutable());
					// On a pavement or a road rather than in a yard: where a booth stands.
					if (!level.getBlockState(p.below()).is(Blocks.GRASS_BLOCK) && !level.getBlockState(p.below()).is(Blocks.DIRT)) {
						paved.add(p.immutable());
					}
					break;
				}
			}
		}
		List<BlockPos> pool = paved.size() >= cfg.vecnaBooths * 4 ? paved : found;
		List<BlockPos> chosen = new ArrayList<>();
		if (!pool.isEmpty()) {
			// Spread out: each next one the farthest from those already up.
			chosen.add(pool.get(random.nextInt(pool.size())));
			while (chosen.size() < cfg.vecnaBooths) {
				BlockPos best = null;
				double bestD = -1;
				for (BlockPos c : pool) {
					double d = Double.MAX_VALUE;
					for (BlockPos o : chosen) {
						d = Math.min(d, c.distSqr(o));
					}
					if (d > bestD) {
						bestD = d;
						best = c;
					}
				}
				if (best == null || bestD < 16 * 16) {
					break;
				}
				chosen.add(best);
			}
		}
		Saved saved = Saved.get(level);
		for (BlockPos at : chosen) {
			build(level, at, saved);
		}
		Halloween.LOGGER.info("Vecna: {} telephone booths put up ({} spots, {} paved)", BOOTHS.size(), found.size(), paved.size());
	}

	/** Room for a booth with its floor at {@code at} (its middle): three by three, four high, open sky, solid ground. */
	private static boolean fits(ServerLevel level, BlockPos at) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos under = at.offset(dx, -1, dz);
				if (!level.getBlockState(under).isCollisionShapeFullBlock(level, under)) {
					return false;
				}
				for (int dy = 0; dy <= 3; dy++) {
					BlockPos c = at.offset(dx, dy, dz);
					BlockState s = level.getBlockState(c);
					if (!s.isAir() && !s.canBeReplaced()) {
						return false;
					}
				}
			}
		}
		// Somewhere to walk in from: the cell in front of the doorway is free too.
		return level.canSeeSky(at.above(3)) && open(level, at) != null;
	}

	/** The side the doorway faces: one with standing room in front of it. */
	private static Direction open(ServerLevel level, BlockPos at) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos front = at.relative(d, 2);
			if (Nav.standable(level, front) && level.getBlockState(front.below()).isCollisionShapeFullBlock(level, front.below())) {
				return d;
			}
		}
		return null;
	}

	private static void build(ServerLevel level, BlockPos at, Saved saved) {
		Direction door = open(level, at);
		if (door == null) {
			return;
		}
		Direction back = door.getOpposite();
		List<Saved.Cell> before = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 0; dy <= 3; dy++) {
					BlockPos c = at.offset(dx, dy, dz);
					before.add(new Saved.Cell(c, level.getBlockState(c)));
				}
			}
		}
		saved.add(before);
		// Thin metal posts at the corners, glass between them, a blue band round the top (the sign), a flat roof.
		BlockState post = Blocks.IRON_BARS.defaultBlockState();
		BlockState glass = Blocks.GLASS_PANE.defaultBlockState();
		BlockState band = MapPlan.b("blue_concrete");
		BlockState shelf = MapPlan.b("smooth_stone");
		BlockState roof = MapPlan.b("smooth_stone_slab");
		BlockPos phone = at.relative(back).above();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos col = at.offset(dx, 0, dz);
				boolean corner = dx != 0 && dz != 0;
				boolean middle = dx == 0 && dz == 0;
				for (int dy = 0; dy <= 2; dy++) {
					BlockPos c = col.above(dy);
					BlockState s;
					if (middle) {
						s = Blocks.AIR.defaultBlockState();
					} else if (dy == 2) {
						// The band, over the doorway too (two blocks high: room to walk in).
						s = band;
					} else if (col.equals(at.relative(door))) {
						s = Blocks.AIR.defaultBlockState();
					} else if (corner) {
						s = post;
					} else if (col.equals(at.relative(back)) && dy == 0) {
						// The shelf the phone stands on.
						s = shelf;
					} else if (c.equals(phone)) {
						s = HwBlocks.PHONE.defaultBlockState();
					} else {
						s = glass;
					}
					level.setBlock(c, s, Block.UPDATE_ALL);
				}
				// A lamp in the middle of the roof: a booth glows at night.
				level.setBlock(col.above(3), middle ? Blocks.SEA_LANTERN.defaultBlockState() : roof, Block.UPDATE_ALL);
			}
		}
		// The panes and posts join up with each other now that all of it stands.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = 0; dy <= 2; dy++) {
					BlockPos c = at.offset(dx, dy, dz);
					BlockState s = level.getBlockState(c);
					if (s.is(Blocks.GLASS_PANE) || s.is(Blocks.IRON_BARS)) {
						level.setBlock(c, Block.updateFromNeighbourShapes(s, level, c), Block.UPDATE_CLIENTS);
					}
				}
			}
		}
		BOOTHS.add(new Booth(phone, at));
	}

	/** Everything the booths stood on put back as it was. */
	public static void clear(ServerLevel level) {
		BOOTHS.clear();
		Saved saved = Saved.get(level);
		if (saved.cells.isEmpty()) {
			return;
		}
		for (Saved.Cell c : saved.cells) {
			level.setBlock(c.pos(), c.state(), Block.UPDATE_CLIENTS);
		}
		saved.cells.clear();
		saved.setDirty();
	}

	// ---------------------------------------------------------------- a call

	/** Holding use on a booth's phone (null: not a booth's; then nothing else, not a police call, is made of it). */
	static boolean isBooth(BlockPos pos) {
		return at(pos) != null;
	}

	static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		Booth b = at(pos);
		HwConfig cfg = HwConfig.get();
		int now = player.level().getServer().getTickCount();
		if (b == null || !MatchManager.role(player).survivor() || !Vecna.active()) {
			return null;
		}
		if (b.resting(now)) {
			player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("halloween.msg.booth.dead", (b.restUntil - now + 19) / 20)
				.withStyle(net.minecraft.ChatFormatting.GRAY));
			return null;
		}
		if (VecnaCurse.stage(player) == 0) {
			player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("halloween.msg.booth.no_curse").withStyle(net.minecraft.ChatFormatting.GRAY));
			return null;
		}
		return new Holds.Hold(b.phone, "halloween.hud.progress.booth", false, cfg.vecnaBoothTicks,
			p -> !b.resting(p.level().getServer().getTickCount()) && VecnaCurse.stage(p) > 0, p -> called(p, b), true);
	}

	private static void called(ServerPlayer player, Booth b) {
		ServerLevel level = player.level();
		HwConfig cfg = HwConfig.get();
		int now = level.getServer().getTickCount();
		b.restUntil = now + cfg.vecnaBoothRestTicks;
		VecnaCurse.lift(player);
		Vec3 c = Vec3.atCenterOf(b.phone);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.5f, 1.6f);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.5f, 0.8f);
		AiStats.add(player, "vecna.booth");
		// Vecna hears where it rang.
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE) {
				ServerPlayNetworking.send(p, new HwNet.Impulse(List.of(), List.of(c), cfg.minigameNoiseRevealTicks));
			}
		}
	}

	// ---------------------------------------------------------------- what to put back

	/** What stood where the booths are, kept with the world until it is put back. */
	public static final class Saved extends SavedData {
		record Cell(BlockPos pos, BlockState state) {
			static final Codec<Cell> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Cell::pos),
				BlockState.CODEC.fieldOf("state").forGetter(Cell::state)
			).apply(i, Cell::new));
		}

		private static final Codec<Saved> CODEC = Cell.CODEC.listOf().xmap(Saved::new, s -> List.copyOf(s.cells));
		public static final SavedDataType<Saved> TYPE = new SavedDataType<>(Halloween.id("vecna_booths"), Saved::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

		final List<Cell> cells = new ArrayList<>();

		public Saved() {
		}

		private Saved(List<Cell> cells) {
			this.cells.addAll(cells);
		}

		static Saved get(ServerLevel level) {
			return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
		}

		void add(List<Cell> list) {
			this.cells.addAll(list);
			this.setDirty();
		}
	}
}
