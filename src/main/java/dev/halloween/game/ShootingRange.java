package dev.halloween.game;

import dev.halloween.Role;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * The school's shooting range (part of the Lobby): a firing line of five booths, the guns on the wall (a button
 * each: revolver, rifle, shotgun, loaded and never running dry here) and a button that starts a round. A round is
 * thirty seconds of Michael's silhouette (dark body, white mask) popping up at four distances and gliding across on
 * two tracks, up for less and less time; a body hit scores, the mask more, farther and moving more still. The best
 * scores are kept with the lobby. One round at a time; guns only in here (taken off you at the door).
 */
public final class ShootingRange {
	/** The room, relative to the lobby's corner. */
	static final int X0 = 60;
	static final int X1 = 98;
	static final int Z0 = 33;
	static final int Z1 = 78;
	static final int HEIGHT = 9;
	/** The firing line: the counter's z; behind it the walkway, the guns on the wall. */
	static final int COUNTER = 37;
	private static final int[] ROWS = {46, 54, 62, 70};
	private static final int[] COLUMNS = {63, 68, 73, 78, 83, 88, 93};
	private static final int[] TRACKS = {50, 66};
	private static final int COUNTDOWN = 60;
	private static final int ROUND = 600;

	private static final class Target {
		final BlockPos base;
		final int row;
		final boolean moving;
		final int born;
		final int life;
		int dx;
		int nextMove;

		Target(BlockPos base, int row, boolean moving, int born, int life) {
			this.base = base;
			this.row = row;
			this.moving = moving;
			this.born = born;
			this.life = life;
		}

		BlockPos current;
	}

	private static UUID owner;
	private static int startTick;
	private static int score;
	private static int hits;
	private static int shots;
	private static int nextSpawn;
	private static final List<Target> TARGETS = new ArrayList<>();

	private ShootingRange() {
	}

	// ---------------------------------------------------------------- where things are

	static BlockPos gunButton(BlockPos o, HwItems.Kind kind) {
		int x = kind == HwItems.Kind.REVOLVER ? 62 : kind == HwItems.Kind.RIFLE ? 64 : 66;
		return o.offset(x, 1, Z0);
	}

	static BlockPos startButton(BlockPos o) {
		return o.offset(70, 1, Z0);
	}

	/** Is this spot in the range room? */
	static boolean inside(BlockPos o, double x, double y, double z) {
		double rx = x - o.getX();
		double ry = y - o.getY();
		double rz = z - o.getZ();
		return rx >= X0 && rx < X1 + 1 && rz >= Z0 && rz < Z1 + 1 && ry >= -1 && ry <= HEIGHT;
	}

	/** May this player fire here (in the range, between matches)? */
	public static boolean allowed(ServerPlayer p) {
		BlockPos o = Lobby.origin(p.level().getServer());
		return o != null && !Match.running() && !Match.frozen() && MatchManager.role(p) == Role.NONE && !p.isSpectator()
			&& inside(o, p.getX(), p.getY(), p.getZ());
	}

	// ---------------------------------------------------------------- building

	static void build(ServerLevel level, BlockPos o) {
		// Floor: concrete on the firing line, packed earth downrange; the backstop of hay.
		Lobby.fill(level, o, X0, -1, Z0, X1, -1, COUNTER, MapPlan.b("polished_andesite"));
		for (int x = X0; x <= X1; x++) {
			for (int z = COUNTER + 1; z <= Z1; z++) {
				Lobby.set(level, o, x, -1, z, MapPlan.b((x * 31 + z * 17) % 5 == 0 ? "gravel" : "coarse_dirt"));
			}
		}
		Lobby.fill(level, o, X0, 0, Z1, X1, 5, Z1, MapPlan.b("hay_block"));
		Lobby.fill(level, o, X0, 6, Z1, X1, HEIGHT - 1, Z1, MapPlan.b("dark_oak_planks"));
		// Distance markers on the floor at each row.
		for (int i = 0; i < ROWS.length; i++) {
			for (int x = X0 + 1; x < X1; x += 2) {
				Lobby.set(level, o, x, -1, ROWS[i] - 2, MapPlan.b("white_concrete"));
			}
		}
		for (int z : TRACKS) {
			Lobby.fill(level, o, X0 + 1, -1, z, X1 - 1, -1, z, MapPlan.b("smooth_stone"));
		}
		// The counter, booths between it and the walkway.
		Lobby.fill(level, o, X0, 0, COUNTER, X1, 0, COUNTER, MapPlan.b("stripped_spruce_log").setValue(
			net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X));
		for (int x = X0 + 6; x < X1; x += 6) {
			Lobby.fill(level, o, x, 0, Z0 + 1, x, 1, COUNTER - 1, MapPlan.b("dark_oak_fence"));
			Lobby.set(level, o, x, 0, COUNTER, MapPlan.b("dark_oak_planks"));
		}
		// Lamps over the field, the line lit too.
		for (int x = X0 + 3; x < X1; x += 8) {
			for (int z = COUNTER + 6; z < Z1; z += 8) {
				Lobby.set(level, o, x, HEIGHT - 1, z, MapPlan.b("lantern").setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
			}
			Lobby.set(level, o, x, HEIGHT - 1, Z0 + 2, MapPlan.b("lantern").setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
		}
		// The guns on the wall, the round's button, signs over them.
		for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.REVOLVER, HwItems.Kind.RIFLE, HwItems.Kind.SHOTGUN}) {
			BlockPos b = gunButton(o, kind);
			level.setBlock(b, MapPlan.b("stone_button").setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.SOUTH),
				Block.UPDATE_CLIENTS);
			Lobby.wallSign(level, b.above(), Direction.SOUTH, Component.translatable("halloween.item." + kind.key()).withStyle(ChatFormatting.BOLD),
				Component.translatable("halloween.lobby.range_take"));
		}
		BlockPos start = startButton(o);
		level.setBlock(start, MapPlan.b("polished_blackstone_button").setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.SOUTH),
			Block.UPDATE_CLIENTS);
		Lobby.wallSign(level, start.above(), Direction.SOUTH, Component.translatable("halloween.lobby.range_sign").withStyle(ChatFormatting.BOLD),
			Component.translatable("halloween.lobby.range_sign2"));
		// Old targets (a round cut short when the lobby was rebuilt) are gone with the rebuild.
		TARGETS.clear();
		owner = null;
	}

	// ---------------------------------------------------------------- use

	/** A button in the range: a gun, or the round. True if it was one of them. */
	static boolean onUse(ServerPlayer p, BlockPos o, BlockPos pos) {
		for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.REVOLVER, HwItems.Kind.RIFLE, HwItems.Kind.SHOTGUN}) {
			if (pos.equals(gunButton(o, kind))) {
				ItemStack gun = HwItems.create(kind);
				Guns.setLoaded(gun, Guns.capacity(kind));
				stripGuns(p);
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, gun);
				p.level().playSound(null, p.blockPosition(), dev.halloween.HwSounds.GUN_BOLT, SoundSource.PLAYERS, 0.8f, 1.1f);
				return true;
			}
		}
		if (pos.equals(startButton(o))) {
			MinecraftServer server = p.level().getServer();
			if (owner != null && !owner.equals(p.getUUID()) && server.getPlayerList().getPlayer(owner) != null) {
				Notices.send(p, Component.translatable("halloween.lobby.range_busy").withStyle(ChatFormatting.GRAY));
				return true;
			}
			if (!(HwItems.kindOf(p.getMainHandItem()) instanceof HwItems.Kind k && k.gun())) {
				Notices.send(p, Component.translatable("halloween.lobby.range_need_gun").withStyle(ChatFormatting.GRAY));
				return true;
			}
			clearTargets((ServerLevel) p.level());
			owner = p.getUUID();
			startTick = server.getTickCount();
			score = 0;
			hits = 0;
			shots = 0;
			nextSpawn = startTick + COUNTDOWN;
			p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 0.8f);
			return true;
		}
		return false;
	}

	/** Guns stay in the range. */
	static void stripGuns(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			HwItems.Kind k = HwItems.kindOf(inv.getItem(i));
			if (k != null && k.gun()) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	/** A shot fired in here (for the round's accuracy). */
	static void shot(ServerPlayer p) {
		if (p.getUUID().equals(owner)) {
			shots++;
		}
	}

	// ---------------------------------------------------------------- the round

	/** The round's line for its shooter, or null. */
	static Component status(ServerPlayer p, int now) {
		if (owner == null || !owner.equals(p.getUUID())) {
			return null;
		}
		int e = now - startTick;
		if (e < COUNTDOWN) {
			return Component.translatable("halloween.lobby.range_get_ready", (COUNTDOWN - e + 19) / 20).withStyle(ChatFormatting.GOLD);
		}
		return Component.translatable("halloween.lobby.range_round", score, (ROUND - (e - COUNTDOWN) + 19) / 20, hits, shots).withStyle(ChatFormatting.AQUA);
	}

	static void tick(MinecraftServer server, BlockPos o, Lobby data) {
		if (owner == null) {
			return;
		}
		ServerLevel level = server.overworld();
		ServerPlayer shooter = server.getPlayerList().getPlayer(owner);
		int now = server.getTickCount();
		if (shooter == null || !allowed(shooter)) {
			// Walked out (or a match began): the round is off.
			clearTargets(level);
			owner = null;
			return;
		}
		int e = now - startTick - COUNTDOWN;
		if (e < 0) {
			if ((now - startTick) % 20 == 0) {
				level.playSound(null, shooter.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1f, 1f);
			}
			return;
		}
		if (e >= ROUND) {
			finish(level, shooter, data);
			return;
		}
		float progress = e / (float) ROUND;
		RandomSource random = level.getRandom();
		// Up for less and less long; more at once later on.
		if (now >= nextSpawn) {
			int up = TARGETS.size();
			int most = progress < 0.3f ? 2 : progress < 0.7f ? 3 : 4;
			if (up < most) {
				spawn(level, o, random, now, progress);
			}
			nextSpawn = now + (int) (24 - 12 * progress) + random.nextInt(8);
		}
		Iterator<Target> it = TARGETS.iterator();
		while (it.hasNext()) {
			Target t = it.next();
			if (now - t.born >= t.life) {
				// Gone again, missed.
				remove(level, t);
				level.playSound(null, t.current, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.5f, 1.4f);
				it.remove();
				continue;
			}
			if (t.moving && now >= t.nextMove) {
				BlockPos next = t.current.offset(t.dx, 0, 0);
				int rx = next.getX() - o.getX();
				if (rx <= X0 + 1 || rx >= X1 - 1) {
					t.dx = -t.dx;
					next = t.current.offset(t.dx, 0, 0);
				}
				remove(level, t);
				t.current = next;
				place(level, t, 3);
				t.nextMove = now + 5;
			} else if (!t.moving && now - t.born < 3) {
				// Popping up: a block a tick.
				place(level, t, now - t.born + 1);
			}
		}
	}

	private static void spawn(ServerLevel level, BlockPos o, RandomSource random, int now, float progress) {
		boolean moving = random.nextFloat() < 0.15f + 0.25f * progress;
		int life = (int) (60 - 30 * progress) + random.nextInt(15);
		for (int tries = 0; tries < 12; tries++) {
			BlockPos base;
			int row;
			if (moving) {
				int track = random.nextInt(TRACKS.length);
				row = track == 0 ? 1 : 3;
				base = o.offset(X0 + 2 + random.nextInt(X1 - X0 - 4), 0, TRACKS[track]);
			} else {
				row = random.nextInt(ROWS.length);
				base = o.offset(COLUMNS[random.nextInt(COLUMNS.length)], 0, ROWS[row]);
			}
			if (!free(base)) {
				continue;
			}
			Target t = new Target(base, row, moving, now, moving ? life + 30 : life);
			t.current = base;
			t.dx = random.nextBoolean() ? 1 : -1;
			t.nextMove = now + 5;
			TARGETS.add(t);
			place(level, t, moving ? 3 : 1);
			level.playSound(null, base, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.6f, 1.2f);
			return;
		}
	}

	private static boolean free(BlockPos base) {
		for (Target t : TARGETS) {
			if (Math.abs(t.current.getX() - base.getX()) <= 2 && t.current.getZ() == base.getZ()) {
				return false;
			}
		}
		return true;
	}

	/** Michael: a dark body two blocks high, the white mask on top ({@code n} of the three blocks up so far). */
	private static void place(ServerLevel level, Target t, int n) {
		for (int y = 0; y < Math.min(3, n); y++) {
			level.setBlock(t.current.above(y), y < 2 ? MapPlan.b("black_wool") : MapPlan.b("white_concrete"), Block.UPDATE_CLIENTS);
		}
	}

	private static void remove(ServerLevel level, Target t) {
		for (int y = 0; y < 3; y++) {
			level.setBlock(t.current.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private static void clearTargets(ServerLevel level) {
		for (Target t : TARGETS) {
			remove(level, t);
		}
		TARGETS.clear();
	}

	/**
	 * A bullet stopped by this block: if it is one of the round's targets, it is down and scores (the round's
	 * shooter's shots only). True if it was a target.
	 */
	public static boolean hit(ServerLevel level, net.minecraft.world.entity.LivingEntity shooter, BlockPos pos) {
		for (Iterator<Target> it = TARGETS.iterator(); it.hasNext();) {
			Target t = it.next();
			int dy = pos.getY() - t.current.getY();
			if (pos.getX() != t.current.getX() || pos.getZ() != t.current.getZ() || dy < 0 || dy > 2) {
				continue;
			}
			if (!shooter.getUUID().equals(owner)) {
				return true;
			}
			boolean head = dy == 2;
			int points = (head ? 25 : 10) + 5 * t.row;
			if (t.moving) {
				points *= 2;
			}
			score += points;
			hits++;
			BlockState was = level.getBlockState(pos);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, was), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 20, 0.25, 0.25, 0.25, 0.1);
			remove(level, t);
			it.remove();
			if (shooter instanceof ServerPlayer p) {
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(head ? SoundEvents.NOTE_BLOCK_BELL : SoundEvents.NOTE_BLOCK_PLING,
					SoundSource.PLAYERS, p.getX(), p.getEyeY(), p.getZ(), 1f, head ? 1.6f : 1.2f, p.getRandom().nextLong()));
				p.sendOverlayMessage(Component.translatable(head ? "halloween.lobby.range_head" : "halloween.lobby.range_body", points)
					.withStyle(head ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
			}
			return true;
		}
		return false;
	}

	private static void finish(ServerLevel level, ServerPlayer shooter, Lobby data) {
		clearTargets(level);
		owner = null;
		String name = shooter.getScoreboardName();
		Integer best = data.rangeRecord(name);
		int accuracy = shots == 0 ? 0 : Math.round(100f * hits / shots);
		if (best == null || score > best) {
			data.setRangeRecord(name, score);
			Notices.send(shooter, Component.translatable("halloween.lobby.range_record", score, hits, accuracy).withStyle(ChatFormatting.GOLD));
			level.playSound(null, shooter.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.1f);
		} else {
			Notices.send(shooter, Component.translatable("halloween.lobby.range_done", score, hits, accuracy, best).withStyle(ChatFormatting.GREEN));
			level.playSound(null, shooter.blockPosition(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1f, 1f);
		}
	}

	/** A match starts, the lobby is rebuilt: no round on. */
	static void stop(ServerLevel level) {
		clearTargets(level);
		owner = null;
	}

	/** For tests: the round's score so far, -1 without one. */
	public static int score() {
		return owner == null ? -1 : score;
	}

	/** For tests: the targets up now (each its lowest block). */
	public static List<BlockPos> targets() {
		List<BlockPos> out = new ArrayList<>();
		for (Target t : TARGETS) {
			out.add(t.current);
		}
		return out;
	}
}
