package dev.halloween.game;

import com.mojang.math.Transformation;
import dev.halloween.HwBlocks;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Pulled down behind you in a chase. A tall bookcase or a street trash can (PropBlock; the town's bookshelves two
 * high and its bins become these when a match is set up) is pulled down by a survivor running past it with Michael
 * after them: the use key, no aiming (the client offers the nearest one beside or behind them, see
 * client.ClientObstacles). Only in a chase: seen by him lately or with him close.
 * <ul>
 * <li><b>The fall.</b> A bookcase goes over forward off the wall, face down, across two blocks of floor, books
 * sliding out from under it; a can goes over towards the one who pulled it, its lid flying off and rolling, the
 * rubbish spilling out. Drawn as block displays turned over a pivot, a tick at a time, with a bounce; loud (layered
 * Kenney impacts), the view jolts nearby, and Michael hears where it came down.</li>
 * <li><b>Lying across the way</b> (DebrisBlock cells under it): a wall to Michael's side (too high to step or jump
 * over), a low step for everyone else. He goes round, or kicks it apart: a strike at it (or bumping into it with the
 * strike) is a second of stamping it to pieces, the wreck and the spilled books and rubbish left lying.</li>
 * <li><b>On him.</b> Coming down on Michael stuns him for a moment (no striking, no moving), thrown out from under it;
 * the one who pulled it is told.</li>
 * </ul>
 * Bot survivors pull things down when he is close behind them; the bot Michael goes round when that is short and
 * kicks his way through otherwise. Everything is put back when the match ends.
 */
public final class Obstacles {
	/** How near a survivor must run past one to pull it down (to its middle, across). */
	public static final double REACH = 2.4;
	/** He is this close (even unseen): it is a chase. */
	private static final double CHASED_WITHIN = 14;
	private static final int CASE_FALL = 10;
	private static final int CAN_FALL = 7;
	private static final int KICK_TICKS = 20;
	private static final int KICK_HIT = 9;
	private static final int STUN_TICKS = 34;
	private static final String TAG = "halloween_obstacle";

	/** What stood where something was pulled down or now lies, to put back (the first state seen at each place). */
	private static final Map<BlockPos, BlockState> ORIGINAL = new LinkedHashMap<>();
	private static final List<Fall> FALLS = new ArrayList<>();
	private static final Map<BlockPos, Lying> LYING = new HashMap<>();
	private static final List<Kick> KICKS = new ArrayList<>();
	/** Displays left lying after their obstacle is gone (books, rubbish, the wreck), and the rest, to clear. */
	private static final List<Display.BlockDisplay> ALL = new ArrayList<>();
	private static final List<Cue> CUES = new ArrayList<>();
	private static final Map<UUID, Boolean> ALLOWED = new HashMap<>();

	/** How it goes over: its base block, the way it falls and the two blocks it comes to lie across. */
	public record Plan(BlockPos base, PropBlock.Kind kind, Direction dir, BlockPos[] cells) {
	}

	/** A sound a few ticks on. */
	private record Cue(int at, Vec3 pos, SoundEvent sound, float volume, float pitch) {
	}

	/** A display and the transformation it ends up with. */
	private static final class Part {
		final Display.BlockDisplay display;
		Matrix4f rest;

		Part(Display.BlockDisplay display, Matrix4f rest) {
			this.display = display;
			this.rest = rest;
		}
	}

	private static final class Fall {
		final Plan plan;
		final ServerPlayer by;
		final int start;
		final List<Part> parts = new ArrayList<>();
		/** The can's lid: where it lands (north frame, its bottom middle) and how its flight starts. */
		Vector3f lidTo;
		Vector3f lidFrom;
		float lidAngle;
		final List<Spill> spills = new ArrayList<>();
		Lying lying;

		Fall(Plan plan, ServerPlayer by, int start) {
			this.plan = plan;
			this.by = by;
			this.start = start;
		}

		int length() {
			return this.plan.kind == PropBlock.Kind.BOOKCASE ? CASE_FALL : CAN_FALL;
		}
	}

	/** Spilled out as it lands: from {@code from} to {@code to} (north frame, its middle on the floor), turned {@code yaw}. */
	private record Spill(Part part, Vector3f from, Vector3f to, float yaw, int delay) {
	}

	private static final class Lying {
		final Plan plan;
		final List<Part> body = new ArrayList<>();

		Lying(Plan plan) {
			this.plan = plan;
		}

		/** Its middle, at floor height. */
		Vec3 center() {
			Vec3 a = Vec3.atBottomCenterOf(this.plan.cells[0]);
			Vec3 b = Vec3.atBottomCenterOf(this.plan.cells[1]);
			return a.add(b).scale(0.5);
		}
	}

	private static final class Kick {
		final Lying lying;
		final ServerPlayer shape;
		final int start;
		Vec3 dir;
		/** How far the can rolls away. */
		double roll;

		Kick(Lying lying, ServerPlayer shape, int start) {
			this.lying = lying;
			this.shape = shape;
			this.start = start;
		}
	}

	private Obstacles() {
	}

	// ---------------------------------------------------------------- setting up

	/**
	 * The match is being set up: within {@code radius} of the centre, each bookshelf column two high with room in front
	 * becomes a bookcase facing the room, and each cauldron or composter with nothing on it a trash can.
	 */
	public static void setup(ServerLevel level, BlockPos center, int radius) {
		int made = 0;
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		int cr = (radius >> 4) + 1;
		for (int x = cx - cr; x <= cx + cr; x++) {
			for (int z = cz - cr; z <= cz + cr; z++) {
				LevelChunk chunk = level.getChunk(x, z);
				for (int i = 0; i < chunk.getSections().length; i++) {
					LevelChunkSection section = chunk.getSections()[i];
					if (section.hasOnlyAir() || !section.maybeHas(Obstacles::convertible)) {
						continue;
					}
					int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					List<BlockPos> found = new ArrayList<>();
					for (int ly = 0; ly < 16; ly++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								if (convertible(section.getBlockState(lx, ly, lz))) {
									found.add(new BlockPos(chunk.getPos().getMinBlockX() + lx, y0 + ly, chunk.getPos().getMinBlockZ() + lz));
								}
							}
						}
					}
					for (BlockPos pos : found) {
						made += convert(level, pos) ? 1 : 0;
					}
				}
			}
		}
		if (made > 0) {
			dev.halloween.Halloween.LOGGER.info("Obstacles: {} bookcases and bins set up", made);
		}
	}

	private static boolean convertible(BlockState s) {
		return s.is(Blocks.BOOKSHELF) || s.is(Blocks.CAULDRON) || s.is(Blocks.COMPOSTER);
	}

	private static boolean convert(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockPos below = pos.below();
		if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
			return false;
		}
		if (state.is(Blocks.BOOKSHELF)) {
			if (level.getBlockState(below).is(Blocks.BOOKSHELF) || !level.getBlockState(pos.above()).is(Blocks.BOOKSHELF)
				|| level.getBlockState(pos.above(2)).is(Blocks.BOOKSHELF)) {
				return false;
			}
			Direction facing = null;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				if (open(level, pos.relative(d)) && open(level, pos.above().relative(d))) {
					if (facing == null || !open(level, pos.relative(d.getOpposite()))) {
						facing = d;
					}
				}
			}
			if (facing == null) {
				return false;
			}
			BlockState prop = HwBlocks.PROP.defaultBlockState().setValue(PropBlock.KIND, PropBlock.Kind.BOOKCASE).setValue(PropBlock.FACING, facing);
			level.setBlock(pos, prop, Block.UPDATE_CLIENTS);
			level.setBlock(pos.above(), prop.setValue(PropBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
			return true;
		}
		if (!level.getBlockState(pos.above()).isAir()) {
			return false;
		}
		// On a floor, not goods on a shop's shelf (a row of boards has floor on two sides of it at most).
		int floor = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = below.relative(d);
			floor += level.getBlockState(n).isFaceSturdy(level, n, Direction.UP) ? 1 : 0;
		}
		if (floor < 3) {
			return false;
		}
		Direction facing = Direction.from2DDataValue(Math.floorMod(pos.getX() * 7 + pos.getZ() * 13, 4));
		level.setBlock(pos, HwBlocks.PROP.defaultBlockState().setValue(PropBlock.KIND, PropBlock.Kind.BIN).setValue(PropBlock.FACING, facing), Block.UPDATE_CLIENTS);
		return true;
	}

	/** Nothing to bump into there. */
	private static boolean open(Level level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
	}

	/** Room for it to come down on: nothing in the way, a floor under it. */
	private static boolean floorAt(Level level, BlockPos p) {
		return open(level, p) && !level.getBlockState(p).is(HwBlocks.DEBRIS) && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
	}

	// ---------------------------------------------------------------- which way

	/**
	 * How the one at {@code pos} would go over if pulled by someone at {@code from} running along {@code motion}, or
	 * null when it can't (the floor in front taken, something low over it). A bookcase only ever goes forward; a can
	 * goes towards whoever pulls it, else whichever way is open, the way they came first. Both sides use this.
	 */
	public static Plan plan(Level level, BlockPos pos, Vec3 from, Vec3 motion) {
		BlockPos base = PropBlock.base(level, pos);
		BlockState state = level.getBlockState(base);
		if (!PropBlock.is(state) || state.getValue(PropBlock.HALF) != DoubleBlockHalf.LOWER) {
			return null;
		}
		PropBlock.Kind kind = state.getValue(PropBlock.KIND);
		if (kind == PropBlock.Kind.BOOKCASE) {
			Direction d = state.getValue(PropBlock.FACING);
			BlockPos a = base.relative(d);
			BlockPos b = a.relative(d);
			if (!level.getBlockState(base.above()).is(HwBlocks.PROP) || !floorAt(level, a) || !floorAt(level, b) || !open(level, a.above())) {
				return null;
			}
			return new Plan(base, kind, d, new BlockPos[]{a, b});
		}
		Vec3 c = Vec3.atBottomCenterOf(base);
		Vec3 toward = new Vec3(from.x - c.x, 0, from.z - c.z);
		Vec3 back = new Vec3(-motion.x, 0, -motion.z);
		Direction best = null;
		double bestScore = -1e9;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos a = base.relative(d);
			if (!floorAt(level, a)) {
				continue;
			}
			Vec3 n = new Vec3(d.getStepX(), 0, d.getStepZ());
			double score = n.dot(toward.lengthSqr() > 1e-4 ? toward.normalize() : Vec3.ZERO) * 2 + n.dot(back.lengthSqr() > 1e-4 ? back.normalize() : Vec3.ZERO);
			if (score > bestScore) {
				bestScore = score;
				best = d;
			}
		}
		return best == null ? null : new Plan(base, kind, best, new BlockPos[]{base, base.relative(best)});
	}

	// ---------------------------------------------------------------- pulling it down

	/** For tests: anyone may, chase or no chase. */
	public static boolean testAllowed;

	/** May this survivor pull things down now: Michael after them (seen by him lately), or close? */
	public static boolean allowed(ServerPlayer p) {
		if (testAllowed || Chase.hunted(p)) {
			return true;
		}
		ServerPlayer shape = Senses.shape(p.level());
		return shape != null && !shape.isSpectator() && shape.distanceTo(p) <= CHASED_WITHIN;
	}

	/** A survivor (or a bot) running past asks to pull the one at {@code pos} down. */
	public static boolean request(ServerPlayer p, BlockPos pos, Vec3 motion) {
		if (!Match.running() || Match.frozen() || !MatchManager.role(p).survivor() || p.isSpectator() || Lockers.isInside(p)
			|| MatchManager.isGrabbed(p) || Windows.isVaulting(p) || !p.isSprinting() || !allowed(p)) {
			return false;
		}
		ServerLevel level = p.level();
		BlockPos base = PropBlock.base(level, pos);
		Vec3 c = Vec3.atBottomCenterOf(base);
		if (Math.hypot(p.getX() - c.x, p.getZ() - c.z) > REACH + 0.6 || Math.abs(p.getY() - c.y) > 1.5) {
			return false;
		}
		Plan plan = plan(level, base, p.position(), motion);
		if (plan == null) {
			return false;
		}
		for (Fall f : FALLS) {
			// (Not where another one is still coming down.)
			if (f.plan.base.equals(base) || java.util.Arrays.stream(f.plan.cells).anyMatch(q -> q.equals(plan.cells[0]) || q.equals(plan.cells[1]))) {
				return false;
			}
		}
		start(level, plan, p);
		return true;
	}

	private static void start(ServerLevel level, Plan plan, ServerPlayer by) {
		int now = level.getServer().getTickCount();
		BlockState state = level.getBlockState(plan.base);
		ORIGINAL.putIfAbsent(plan.base, state);
		if (plan.kind == PropBlock.Kind.BOOKCASE) {
			ORIGINAL.putIfAbsent(plan.base.above(), level.getBlockState(plan.base.above()));
		}
		for (BlockPos p : plan.cells) {
			ORIGINAL.putIfAbsent(p, level.getBlockState(p));
		}
		level.setBlock(plan.base, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		if (plan.kind == PropBlock.Kind.BOOKCASE) {
			level.setBlock(plan.base.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		Fall f = new Fall(plan, by, now);
		Matrix4f upright = frame(plan.dir);
		if (plan.kind == PropBlock.Kind.BOOKCASE) {
			BlockState prop = HwBlocks.PROP.defaultBlockState().setValue(PropBlock.KIND, PropBlock.Kind.BOOKCASE);
			f.parts.add(new Part(display(level, plan.base, prop, upright), null));
			f.parts.add(new Part(display(level, plan.base, prop.setValue(PropBlock.HALF, DoubleBlockHalf.UPPER), new Matrix4f(upright).translate(0, 1, 0)), null));
			cue(now, plan.base, HwSounds.OBSTACLE_CREAK, 1.0f, 0.5f);
			cue(now + 1, plan.base, HwSounds.OBSTACLE_PLANK, 0.5f, 0.55f);
			cue(now + 5, plan.base, HwSounds.OBSTACLE_BOOK, 0.6f, 1.25f);
		} else {
			f.parts.add(new Part(display(level, plan.base, piece(DebrisBlock.Piece.BIN_BODY), upright), null));
			f.parts.add(new Part(display(level, plan.base, piece(DebrisBlock.Piece.BIN_LID), upright), null));
			RandomSource r = level.getRandom();
			// The lid comes down beyond the can, flat; aside if there is no floor beyond.
			BlockPos beyond = plan.cells[1].relative(plan.dir);
			f.lidTo = floorAt(level, beyond) ? new Vector3f(0.5f + (r.nextFloat() - 0.5f) * 0.3f, 0, -0.85f - r.nextFloat() * 0.15f)
				: new Vector3f(r.nextBoolean() ? 1.2f : -0.2f, 0, -0.45f);
			cue(now, plan.base, HwSounds.OBSTACLE_CLINK, 0.6f, 0.8f);
			cue(now + 3, plan.base, HwSounds.OBSTACLE_RATTLE, 0.5f, 1.1f);
		}
		FALLS.add(f);
		GrabSession.anim(by, HwNet.Anim.PULL);
		AiStats.add(by, "obstacle.knock");
	}

	// ---------------------------------------------------------------- the fall

	private static void tickFall(ServerLevel level, Fall f, int now) {
		int t = now - f.start;
		int len = f.length();
		Plan plan = f.plan;
		boolean bookcase = plan.kind == PropBlock.Kind.BOOKCASE;
		// Over it goes, faster and faster; a bounce as it hits.
		float theta;
		if (t <= len) {
			float u = (float) t / len;
			theta = 90f * u * u;
		} else {
			theta = t == len + 1 ? 84f : 90f;
		}
		float u2 = Math.min(1f, theta / 90f);
		Matrix4f body = bookcase ? tip(theta, -6f / 16f * u2, 6f / 16f) : tip(theta, 6f / 16f * u2, 2f / 16f);
		Matrix4f world = frame(plan.dir).mul(body);
		if (t <= len + 2) {
			// (Settled after the bounce: left alone from then on, to be kicked apart.)
			update(f.parts.get(0), new Matrix4f(world));
			if (bookcase) {
				update(f.parts.get(1), new Matrix4f(world).translate(0, 1, 0));
			}
		}
		if (!bookcase) {
			tickLid(f, world, t, len);
		}
		if (t == len) {
			land(level, f, now);
		}
		for (Spill s : f.spills) {
			int k = t - len - s.delay;
			if (k < 0 || k > 4) {
				continue;
			}
			float e = 1f - (1f - k / 4f) * (1f - k / 4f);
			Vector3f at = new Vector3f(s.from).lerp(s.to, e);
			float scale = 0.35f + 0.65f * e;
			update(s.part, frame(plan.dir).translate(at.x, at.y, at.z).rotateY(s.yaw * (0.4f + 0.6f * e)).scale(scale).translate(-0.5f, 0, -0.5f));
		}
	}

	/** The can's lid: on the can as it starts over, then flying off, turning over once, landing flat and rattling down. */
	private static void tickLid(Fall f, Matrix4f canWorld, int t, int len) {
		Part lid = f.parts.get(1);
		int detach = Math.max(2, len - 3);
		int flight = 6;
		Vector3f lidPivot = new Vector3f(0.5f, 14f / 16f, 0.5f);
		if (t <= detach) {
			update(lid, new Matrix4f(canWorld));
			if (t == detach) {
				// Where the lid's bottom middle is now, in the frame the fall is drawn in.
				float u = (float) t / len;
				float theta = 90f * u * u;
				f.lidFrom = tip(theta, 6f / 16f * (theta / 90f), 2f / 16f).transformPosition(new Vector3f(lidPivot));
				f.lidAngle = theta;
			}
			return;
		}
		int k = t - detach;
		Matrix4f frame = frame(f.plan.dir);
		if (k <= flight) {
			float e = (float) k / flight;
			Vector3f at = new Vector3f(f.lidFrom).lerp(f.lidTo, e);
			at.y += 0.45f * 4 * e * (1 - e) * (1 - e * 0.5f);
			at.y = Math.max(at.y, f.lidTo.y + (1 - e) * 0.02f);
			float phi = Mth.lerp(e, f.lidAngle, 360f);
			update(lid, frame.translate(at.x, at.y, at.z).rotateX(-phi * Mth.DEG_TO_RAD).translate(-lidPivot.x, -lidPivot.y, -lidPivot.z));
			if (k == flight) {
				Vec3 w = local(f.plan, f.lidTo.x, f.lidTo.z);
				cue(f.start + t, BlockPos.containing(w), HwSounds.OBSTACLE_CLINK, 0.9f, 1.0f);
				cue(f.start + t + 2, BlockPos.containing(w), HwSounds.OBSTACLE_RATTLE, 0.6f, 1.25f);
				cue(f.start + t + 5, BlockPos.containing(w), HwSounds.OBSTACLE_CLINK, 0.4f, 1.4f);
			}
			return;
		}
		// Rattling down flat: a wobble that dies away.
		int w = k - flight;
		if (w > 9) {
			return;
		}
		float wob = w > 8 ? 0f : 9f * (float) Math.cos(w * 1.9f) * (1f - w / 8f);
		update(lid, frame.translate(f.lidTo.x, f.lidTo.y, f.lidTo.z).rotateZ(wob * Mth.DEG_TO_RAD).translate(-lidPivot.x, -lidPivot.y, -lidPivot.z));
	}

	private static void land(ServerLevel level, Fall f, int now) {
		Plan plan = f.plan;
		boolean bookcase = plan.kind == PropBlock.Kind.BOOKCASE;
		for (BlockPos p : plan.cells) {
			level.setBlock(p, HwBlocks.DEBRIS.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		Lying lying = new Lying(plan);
		lying.body.add(f.parts.get(0));
		Matrix4f restBody = frame(plan.dir).mul(bookcase ? tip(90f, -6f / 16f, 6f / 16f) : tip(90f, 6f / 16f, 2f / 16f));
		f.parts.get(0).rest = restBody;
		if (bookcase) {
			lying.body.add(f.parts.get(1));
			f.parts.get(1).rest = new Matrix4f(restBody).translate(0, 1, 0);
		}
		for (BlockPos p : plan.cells) {
			LYING.put(p, lying);
		}
		f.lying = lying;
		Vec3 mid = lying.center();
		RandomSource r = level.getRandom();
		// Dust and splinters along it.
		BlockState look = bookcase ? HwBlocks.PROP.defaultBlockState() : piece(DebrisBlock.Piece.BIN_BODY);
		for (BlockPos p : plan.cells) {
			Vec3 c = Vec3.atBottomCenterOf(p);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, look), c.x, c.y + 0.3, c.z, bookcase ? 28 : 14, 0.45, 0.2, 0.45, 0.12);
			// Dust off the floor it hit, the floor's own colour, and a soft cloud of it.
			BlockState floor = level.getBlockState(p.below());
			level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, floor), c.x, c.y + 0.05, c.z, bookcase ? 14 : 8, 0.55, 0.02, 0.55, 0.15);
			level.sendParticles(ParticleTypes.DUST_PLUME, c.x, c.y + 0.15, c.z, bookcase ? 3 : 2, 0.45, 0.05, 0.45, 0.01);
		}
		if (bookcase) {
			cue(now, plan.cells[0], HwSounds.OBSTACLE_CRASH_WOOD, 1.7f, 0.55f);
			cue(now, plan.cells[1], HwSounds.OBSTACLE_THUD, 1.3f, 0.6f);
			cue(now, plan.cells[0], HwSounds.OBSTACLE_PLANK, 1.0f, 0.75f);
			cue(now + 1, plan.cells[1], HwSounds.OBSTACLE_CRASH_WOOD, 1.1f, 0.8f);
			cue(now + 2, plan.cells[1], HwSounds.OBSTACLE_SPLINTER, 0.6f, 1.15f);
			for (int i = 0; i < 5; i++) {
				cue(now + 1 + i + r.nextInt(2), plan.cells[i % 2], HwSounds.OBSTACLE_BOOK, 0.55f + r.nextFloat() * 0.35f, 0.9f + r.nextFloat() * 0.5f);
			}
			spillBooks(level, f, r);
		} else {
			cue(now, plan.cells[1], HwSounds.OBSTACLE_CRASH_METAL, 1.6f, 0.7f);
			cue(now + 1, plan.cells[1], HwSounds.OBSTACLE_RATTLE, 1.0f, 0.9f);
			cue(now + 1, plan.cells[1], HwSounds.OBSTACLE_TRASH, 0.8f, 0.9f);
			cue(now + 3, plan.cells[1], HwSounds.OBSTACLE_TRASH, 0.6f, 1.2f);
			Vec3 c = Vec3.atBottomCenterOf(plan.cells[1]);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, piece(DebrisBlock.Piece.PAPERS)), c.x, c.y + 0.2, c.z, 12, 0.4, 0.15, 0.4, 0.1);
			spillTrash(level, f, r);
		}
		loud(level, mid);
		// Came down on him: stunned for a moment, thrown out from under it.
		boolean onHim = false;
		AABB under = new AABB(plan.cells[0]).minmax(new AABB(plan.cells[1])).setMaxY(plan.cells[0].getY() + 1.6).deflate(0.05, 0, 0.05);
		for (ServerPlayer p : level.players()) {
			ShapeState st = MatchManager.role(p) == Role.SHAPE && !p.isSpectator() ? MatchManager.shapeState(p) : null;
			if (st == null || st.shadowTicks > 0 || !p.getBoundingBox().intersects(under)) {
				continue;
			}
			onHim = true;
			stun(level, p, st, plan);
		}
		if (onHim && f.by != null && !f.by.isRemoved()) {
			f.by.sendOverlayMessage(Component.translatable(bookcase ? "halloween.obstacle.on_him_case" : "halloween.obstacle.on_him_bin").withStyle(ChatFormatting.GOLD));
			AiStats.add(f.by, "obstacle.on_him");
		}
		// The view jolts for whoever is near.
		for (ServerPlayer p : level.players()) {
			double d = p.position().distanceTo(mid);
			if (d < 10 && !(p instanceof BotPlayer)) {
				float strength = (float) (1 - d / 10) * (bookcase ? 1f : 0.6f) * (MatchManager.role(p) == Role.SHAPE && onHim ? 2f : 1f);
				ServerPlayNetworking.send(p, new HwNet.ObstacleFx(mid, strength, onHim && p == f.by));
			}
		}
	}

	/** Books slide out from under a bookcase that went over face down: out to its sides, past its top. */
	private static void spillBooks(ServerLevel level, Fall f, RandomSource r) {
		Plan plan = f.plan;
		DebrisBlock.Piece[] books = {DebrisBlock.Piece.BOOK_A, DebrisBlock.Piece.BOOK_B, DebrisBlock.Piece.BOOK_OPEN};
		// (x, z) in the fall's frame: x across (0..1 is under it), z along (0 to -2 is under it).
		float[][] spots = {{-0.3f, -0.6f}, {1.3f, -1.3f}, {-0.35f, -1.7f}, {1.35f, -0.4f}, {0.5f, -2.35f}, {0.4f, 0.35f}};
		int delay = 0;
		for (float[] s : spots) {
			if (r.nextFloat() < 0.25f || !open(level, BlockPos.containing(local(plan, s[0], s[1])))
				|| !level.getBlockState(BlockPos.containing(local(plan, s[0], s[1])).below()).isFaceSturdy(level, BlockPos.containing(local(plan, s[0], s[1])).below(), Direction.UP)) {
				continue;
			}
			Vector3f to = new Vector3f(s[0], 0, s[1]);
			Vector3f from = new Vector3f(Mth.clamp(s[0], 0.15f, 0.85f), 0, Mth.clamp(s[1], -1.85f, -0.15f));
			float yaw = (r.nextFloat() - 0.5f) * 2.4f;
			DebrisBlock.Piece what = books[r.nextInt(books.length)];
			Part part = new Part(display(level, plan.base, piece(what), frame(plan.dir).translate(from.x, 0, from.z).scale(0.35f).translate(-0.5f, 0, -0.5f)), null);
			f.spills.add(new Spill(part, from, to, yaw, delay));
			delay += r.nextInt(2);
		}
	}

	/** Rubbish out of a can: the bag, papers, the rest, spread out of its mouth. */
	private static void spillTrash(ServerLevel level, Fall f, RandomSource r) {
		Plan plan = f.plan;
		Vector3f mouth = new Vector3f(0.5f, 0, -0.3f);
		Object[][] spill = {{DebrisBlock.Piece.TRASH, -0.75f, 0.05f}, {DebrisBlock.Piece.PAPERS, -0.55f, 0.35f}, {DebrisBlock.Piece.BAG, -1.25f, -0.15f},
			{DebrisBlock.Piece.PAPERS, -1.05f, -0.4f}};
		int delay = 0;
		for (Object[] s : spill) {
			float z = (Float) s[1];
			float x = 0.5f + (Float) s[2];
			if (z < -1 && !floorAt(level, plan.cells[1].relative(plan.dir))) {
				z = -0.8f;
				x = 0.5f + (Float) s[2] * 2.2f;
			}
			Vector3f to = new Vector3f(x, 0, z);
			Part part = new Part(display(level, plan.base, piece((DebrisBlock.Piece) s[0]), frame(plan.dir).translate(mouth.x, 0, mouth.z).scale(0.35f).translate(-0.5f, 0, -0.5f)), null);
			f.spills.add(new Spill(part, new Vector3f(mouth), to, (r.nextFloat() - 0.5f) * 3f, delay));
			delay += 1;
		}
	}

	private static void stun(ServerLevel level, ServerPlayer shape, ShapeState st, Plan plan) {
		st.recoverTicks = Math.max(st.recoverTicks, STUN_TICKS);
		st.attackCooldown = Math.max(st.attackCooldown, STUN_TICKS);
		st.dashTicks = 0;
		GrabSession.anim(shape, HwNet.Anim.STAGGER);
		// Out from under it to the nearer side (or back the way he came, failing that).
		Vec3 mid = Vec3.atBottomCenterOf(plan.cells[0]).add(Vec3.atBottomCenterOf(plan.cells[1])).scale(0.5);
		Direction side = plan.dir.getClockWise();
		Vec3 across = new Vec3(side.getStepX(), 0, side.getStepZ());
		double s = shape.position().subtract(mid).dot(across) >= 0 ? 1 : -1;
		Vec3 along = new Vec3(plan.dir.getStepX(), 0, plan.dir.getStepZ());
		double t = Mth.clamp(shape.position().subtract(mid).dot(along), -0.5, 0.5);
		Vec3 to = null;
		for (Vec3 c : new Vec3[]{mid.add(along.scale(t)).add(across.scale(s * 1.0)), mid.add(along.scale(t)).add(across.scale(-s * 1.0)),
			mid.add(along.scale(-1.6)), mid.add(along.scale(1.6))}) {
			BlockPos feet = BlockPos.containing(c.x, shape.getY() + 0.1, c.z);
			if (open(level, feet) && open(level, feet.above()) && !level.getBlockState(feet).is(HwBlocks.DEBRIS)) {
				to = new Vec3(c.x, shape.getY(), c.z);
				break;
			}
		}
		if (to != null) {
			dev.halloween.Vr.place(shape, level, to.x, to.y, to.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f);
		}
		cue(level.getServer().getTickCount(), shape.blockPosition(), HwSounds.OBSTACLE_KICK, 1.5f, 0.55f);
		AiStats.add(shape, "obstacle.stunned");
	}

	// ---------------------------------------------------------------- kicked apart

	/** The cell of something lying across the way at {@code pos}, if that is one. */
	public static boolean isCell(Level level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.is(HwBlocks.DEBRIS) && s.getValue(DebrisBlock.PIECE) == DebrisBlock.Piece.CELL;
	}

	/** One lying right in front of him (bumping into it with a strike counts, when the aim misses it): its cell. */
	public static BlockPos inFront(ServerPlayer shape, Vec3 look) {
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() < 1e-4) {
			return null;
		}
		flat = flat.normalize();
		BlockPos best = null;
		double bestD = 1e9;
		for (BlockPos p : LYING.keySet()) {
			Vec3 c = Vec3.atBottomCenterOf(p);
			Vec3 to = new Vec3(c.x - shape.getX(), 0, c.z - shape.getZ());
			double d = to.length();
			if (d < 1.7 && Math.abs(c.y - shape.getY()) < 1.2 && (d < 0.6 || to.normalize().dot(flat) > 0.45) && d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** Michael's strike lands on something lying across the way: he kicks it apart (a second of nothing else). */
	public static boolean kick(ServerPlayer shape, ShapeState st, BlockPos cell) {
		ServerLevel level = shape.level();
		if (!isCell(level, cell)) {
			return false;
		}
		Lying lying = LYING.get(cell);
		int now = level.getServer().getTickCount();
		if (lying == null) {
			// (Lost track of it: a restart mid-match.) Just gone.
			level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			return true;
		}
		for (Kick k : KICKS) {
			if (k.lying == lying) {
				return true;
			}
		}
		Kick k = new Kick(lying, shape, now);
		Vec3 to = lying.center().subtract(shape.position());
		k.dir = new Vec3(to.x, 0, to.z).lengthSqr() < 1e-4 ? new Vec3(lying.plan.dir.getStepX(), 0, lying.plan.dir.getStepZ()) : new Vec3(to.x, 0, to.z).normalize();
		KICKS.add(k);
		st.recoverTicks = Math.max(st.recoverTicks, KICK_TICKS);
		st.attackCooldown = Math.max(st.attackCooldown, KICK_TICKS + 4);
		st.dashTicks = 0;
		GrabSession.anim(shape, HwNet.Anim.KICK);
		AiStats.add(shape, "obstacle.kick");
		return true;
	}

	private static void tickKick(ServerLevel level, Kick k, int now) {
		int t = now - k.start;
		Lying lying = k.lying;
		Plan plan = lying.plan;
		boolean bookcase = plan.kind == PropBlock.Kind.BOOKCASE;
		if (t == KICK_HIT) {
			for (BlockPos p : plan.cells) {
				if (isCell(level, p)) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
				LYING.remove(p);
			}
			Vec3 mid = lying.center();
			BlockPos at = BlockPos.containing(mid);
			cue(now, at, HwSounds.OBSTACLE_KICK, 1.4f, 0.7f);
			if (bookcase) {
				cue(now, at, HwSounds.OBSTACLE_SPLINTER, 1.5f, 0.6f);
				cue(now, at, HwSounds.OBSTACLE_CRASH_WOOD, 1.2f, 0.9f);
				cue(now + 1, at, HwSounds.OBSTACLE_PLANK, 1.0f, 1.1f);
				cue(now + 2, at, HwSounds.OBSTACLE_BOOK, 0.7f, 1.2f);
				cue(now + 4, at, HwSounds.OBSTACLE_PLANK, 0.5f, 1.4f);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, HwBlocks.PROP.defaultBlockState()), mid.x, mid.y + 0.3, mid.z, 60, 0.7, 0.25, 0.7, 0.18);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, piece(DebrisBlock.Piece.BOOK_A)), mid.x, mid.y + 0.3, mid.z, 16, 0.6, 0.2, 0.6, 0.12);
				level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, level.getBlockState(BlockPos.containing(mid).below())), mid.x, mid.y + 0.05, mid.z, 12, 0.6, 0.02, 0.6, 0.15);
				// The wreck left where it lay.
				float yaw = (float) Math.atan2(-plan.dir.getStepX(), -plan.dir.getStepZ()) + (level.getRandom().nextFloat() - 0.5f) * 0.6f;
				Vec3 rel = mid.subtract(Vec3.atLowerCornerOf(plan.base));
				display(level, plan.base, piece(DebrisBlock.Piece.PLANKS),
					new Matrix4f().translate((float) rel.x, 0.01f, (float) rel.z).rotateY(yaw).scale(1.3f, 1f, 1.3f).translate(-0.5f, 0, -0.5f));
			} else {
				cue(now, at, HwSounds.OBSTACLE_CRASH_METAL, 1.3f, 0.95f);
				cue(now + 1, at, HwSounds.OBSTACLE_RATTLE, 1.0f, 0.8f);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, piece(DebrisBlock.Piece.BIN_BODY)), mid.x, mid.y + 0.3, mid.z, 14, 0.4, 0.2, 0.4, 0.1);
				// How far it can roll before it hits something.
				double d = 0;
				Vec3 from = Vec3.atBottomCenterOf(plan.cells[0]).add(Vec3.atBottomCenterOf(plan.cells[1])).scale(0.5).add(0, 0.3, 0);
				while (d < 1.6 && open(level, BlockPos.containing(from.add(k.dir.scale(d + 0.45))))) {
					d += 0.1;
				}
				k.roll = d;
				for (int i = 2; i < 12 && d > 0.3; i += 3) {
					cue(now + i, at, HwSounds.OBSTACLE_RATTLE, 0.45f, 0.7f + i * 0.03f);
				}
			}
			for (ServerPlayer p : level.players()) {
				double dd = p.position().distanceTo(mid);
				if (dd < 8 && !(p instanceof BotPlayer)) {
					ServerPlayNetworking.send(p, new HwNet.ObstacleFx(mid, (float) (1 - dd / 8) * (p == k.shape ? 0.9f : 0.5f), false));
				}
			}
			loud(level, mid);
		}
		if (t < KICK_HIT) {
			return;
		}
		int s = t - KICK_HIT;
		Vec3 mid = lying.center().subtract(Vec3.atLowerCornerOf(plan.base));
		if (bookcase) {
			// Flung apart and gone to splinters.
			float e = Math.min(1f, s / 7f);
			float out = 1f - (1f - e) * (1f - e);
			float scale = s < 3 ? 1f : Math.max(0f, 1f - (s - 3) / 5f);
			for (int i = 0; i < lying.body.size(); i++) {
				Part part = lying.body.get(i);
				float twist = (i == 0 ? 0.5f : -0.6f) * out;
				Vector3f push = new Vector3f((float) k.dir.x, 0, (float) k.dir.z).mul(0.7f * out);
				update(part, new Matrix4f().translate(push).translate((float) mid.x, 0.2f * out, (float) mid.z).rotateY(twist).scale(scale)
					.translate((float) -mid.x, 0, (float) -mid.z).mul(part.rest));
			}
			if (s >= 9) {
				for (Part part : lying.body) {
					part.display.discard();
				}
			}
		} else {
			// The can rolls away and stays where it stops.
			float e = Math.min(1f, s / 10f);
			float d = (float) (k.roll * (1f - (1f - e) * (1f - e)));
			Vector3f axis = new Vector3f(0, 1, 0).cross((float) k.dir.x, 0, (float) k.dir.z);
			Vector3f along = new Vector3f(lying.plan.dir.getStepX(), 0, lying.plan.dir.getStepZ());
			boolean rolls = Math.abs(axis.dot(along)) > 0.7f;
			Vector3f c = new Vector3f((float) mid.x, 6f / 16f, (float) mid.z);
			Matrix4f m = new Matrix4f().translate((float) k.dir.x * d, 0, (float) k.dir.z * d).translate(c);
			if (rolls) {
				m.rotate(d / 0.375f, axis.normalize());
			} else {
				m.rotateY(d * 0.8f);
			}
			Part body = lying.body.get(0);
			update(body, m.translate(-c.x, -c.y, -c.z).mul(body.rest));
		}
	}

	// ---------------------------------------------------------------- for the bots

	/** The extra a step into this block costs the bot Michael (going round is better when it is short). */
	public static double shapeCost(BlockPos p) {
		return LYING.containsKey(p) ? 8 : 0;
	}

	/** Lying across the way in that block (for the path finding: walked through, for those who step over it). */
	public static boolean lies(BlockState state) {
		return state.is(HwBlocks.DEBRIS);
	}

	/** A bot survivor running from {@code threat}: pulls down whatever is beside or behind it, between it and him. */
	public static boolean botKnock(ServerPlayer bot, Vec3 threat) {
		Vec3 pos = bot.position();
		if (threat.distanceTo(pos) > 12 || threat.distanceTo(pos) < 2.5) {
			return false;
		}
		Vec3 motion = bot.getDeltaMovement();
		Vec3 away = new Vec3(pos.x - threat.x, 0, pos.z - threat.z).normalize();
		if (motion.horizontalDistanceSqr() < 1e-4) {
			motion = away;
		}
		Level level = bot.level();
		BlockPos at = bot.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 1, 2))) {
			BlockState s = level.getBlockState(p);
			if (!PropBlock.is(s) || s.getValue(PropBlock.HALF) != DoubleBlockHalf.LOWER) {
				continue;
			}
			Plan plan = plan(level, p.immutable(), pos, motion);
			if (plan == null) {
				continue;
			}
			Vec3 mid = Vec3.atBottomCenterOf(plan.cells[0]).add(Vec3.atBottomCenterOf(plan.cells[1])).scale(0.5);
			// Between it and him, and not on its own feet.
			Vec3 toMid = mid.subtract(pos);
			if (toMid.dot(threat.subtract(pos)) > 0 && new Vec3(toMid.x, 0, toMid.z).length() > 0.8 && request(bot, p.immutable(), motion)) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		for (Iterator<Fall> it = FALLS.iterator(); it.hasNext(); ) {
			Fall f = it.next();
			tickFall(level, f, now);
			if (now - f.start > f.length() + 16) {
				it.remove();
			}
		}
		for (Iterator<Kick> it = KICKS.iterator(); it.hasNext(); ) {
			Kick k = it.next();
			tickKick(level, k, now);
			if (now - k.start > KICK_HIT + 12) {
				it.remove();
			}
		}
		for (Iterator<Cue> it = CUES.iterator(); it.hasNext(); ) {
			Cue c = it.next();
			if (now >= c.at) {
				level.playSound(null, c.pos.x, c.pos.y, c.pos.z, c.sound, SoundSource.BLOCKS, c.volume, c.pitch);
				it.remove();
			}
		}
		if (now % 5 == 0) {
			// Whether each survivor may pull things down now (the client offers them only then).
			for (ServerPlayer p : level.players()) {
				if (p instanceof BotPlayer || !MatchManager.role(p).survivor()) {
					continue;
				}
				boolean ok = allowed(p);
				if (!Boolean.valueOf(ok).equals(ALLOWED.put(p.getUUID(), ok))) {
					ServerPlayNetworking.send(p, new HwNet.KnockAllowed(ok));
				}
			}
		}
	}

	/** The match is over: everything pulled down stands where it stood; the pieces and the wreck are gone. */
	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Display.BlockDisplay d : ALL) {
			d.discard();
		}
		ALL.clear();
		FALLS.clear();
		KICKS.clear();
		LYING.clear();
		CUES.clear();
		List<Map.Entry<BlockPos, BlockState>> back = new ArrayList<>(ORIGINAL.entrySet());
		for (int i = back.size() - 1; i >= 0; i--) {
			level.setBlock(back.get(i).getKey(), back.get(i).getValue(), Block.UPDATE_CLIENTS);
		}
		ORIGINAL.clear();
		for (ServerPlayer p : level.players()) {
			if (Boolean.TRUE.equals(ALLOWED.get(p.getUUID())) && !(p instanceof BotPlayer)) {
				ServerPlayNetworking.send(p, new HwNet.KnockAllowed(false));
			}
		}
		ALLOWED.clear();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=" + TAG + "]");
	}

	// ---------------------------------------------------------------- drawing

	/** Turning a model about a block's middle so its front (north, in the model) faces {@code dir}. */
	private static Matrix4f frame(Direction dir) {
		float yaw = switch (dir) {
			case EAST -> -90f;
			case SOUTH -> 180f;
			case WEST -> 90f;
			default -> 0f;
		};
		return new Matrix4f().translate(0.5f, 0, 0.5f).rotateY(yaw * Mth.DEG_TO_RAD).translate(-0.5f, 0, -0.5f);
	}

	/**
	 * Tipped forward (north, in the model) by {@code theta} degrees over the floor edge at {@code pivotZ}, slid
	 * {@code slide} along as it goes (its foot kicks out from under it, or back).
	 */
	private static Matrix4f tip(float theta, float slide, float pivotZ) {
		return new Matrix4f().translate(0, 0, slide + pivotZ).rotateX(-theta * Mth.DEG_TO_RAD).translate(0, 0, -pivotZ);
	}

	/** A point given in the fall's frame (x across, z along, 0..1 the base block), in the world. */
	private static Vec3 local(Plan plan, float x, float z) {
		Vector3f v = frame(plan.dir).transformPosition(new Vector3f(x, 0.2f, z));
		return Vec3.atLowerCornerOf(plan.base).add(v.x, v.y, v.z);
	}

	private static BlockState piece(DebrisBlock.Piece piece) {
		return HwBlocks.DEBRIS.defaultBlockState().setValue(DebrisBlock.PIECE, piece);
	}

	private static Display.BlockDisplay display(ServerLevel level, BlockPos at, BlockState state, Matrix4f m) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		d.setBlockState(state);
		d.setTransformation(new Transformation(m));
		d.setTransformationInterpolationDuration(1);
		d.setViewRange(1.5f);
		// Culled by a box round where it stands: wide enough for it lying two blocks off, or flying.
		d.setWidth(6f);
		d.setHeight(3f);
		d.setPos(at.getX(), at.getY(), at.getZ());
		d.addTag(TAG);
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		ALL.add(d);
		return d;
	}

	/** On to the next pose, eased over a tick on the client. */
	private static void update(Part part, Matrix4f m) {
		part.display.setTransformation(new Transformation(m));
		part.display.setTransformationInterpolationDelay(0);
	}

	private static void cue(int at, BlockPos pos, SoundEvent sound, float volume, float pitch) {
		CUES.add(new Cue(at, Vec3.atCenterOf(pos), sound, volume, pitch));
	}

	/** Loud: Michael hears where it came down. */
	private static void loud(ServerLevel level, Vec3 at) {
		HwNet.NoiseBurst burst = new HwNet.NoiseBurst(at, true);
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE && p.position().distanceTo(at) <= 40) {
				ServerPlayNetworking.send(p, burst);
			}
		}
	}
}
