package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Practice (/hw tutorial): a quiet match on the current map, alone, with no Shape about. Step by step, with a
 * hint above the hotbar and sparks over what to use: as a survivor, search furniture, hide in a locker and
 * get out, climb through a window, lock a door, open the storm cellar and get away through it; then as
 * the Shape, go into Shape Jump and out, dash, grab a townsperson and finish them.
 */
public final class Tutorial {
	public enum Step {
		SEARCH, HIDE, LEAVE, WINDOW, LOCK, CELLAR, ESCAPE,
		JUMP, UNJUMP, DASH, GRAB, KILL, DONE;

		String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		boolean shape() {
			return ordinal() >= JUMP.ordinal();
		}
	}

	/** Ticks between the survivor part ending (the summary screen) and the Shape part starting. */
	private static final int BREAK_TICKS = 160;
	private static UUID who;
	private static Step step;
	private static int wait = -1;
	private static int kills;
	private static int timer;

	private Tutorial() {
	}

	public static boolean active() {
		return who != null;
	}

	public static Step step() {
		return step;
	}

	/** Starts the practice for {@code player}: the survivor part first. */
	public static Component start(MinecraftServer server, ServerPlayer player) {
		who = player.getUUID();
		Component result = begin(server, null);
		if (!Match.running() && !Match.frozen()) {
			who = null;
			return result;
		}
		go(player, Step.SEARCH);
		return Component.translatable("halloween.tutorial.started");
	}

	/** A practice match: alone (no bots), a clear night, a long clock; {@code shape} plays the Shape. */
	private static Component begin(MinecraftServer server, ServerPlayer shape) {
		HwConfig cfg = HwConfig.get();
		int bots = cfg.botsFillTo;
		int seconds = cfg.matchSeconds;
		int countdown = cfg.countdownSeconds;
		cfg.botsFillTo = 0;
		cfg.matchSeconds = 60 * 60;
		cfg.countdownSeconds = 3;
		Weather.forced = Weather.CLEAR;
		try {
			return Match.start(server, shape);
		} finally {
			cfg.botsFillTo = bots;
			cfg.matchSeconds = seconds;
			cfg.countdownSeconds = countdown;
			Weather.forced = -1;
		}
	}

	public static void stop() {
		who = null;
		step = null;
		wait = -1;
	}

	/** A hold-use finished (search, lock, open the cellar...). */
	public static void holdDone(ServerPlayer player, String label) {
		if (!player.getUUID().equals(who) || step == null) {
			return;
		}
		if (step == Step.SEARCH && label.equals("halloween.hud.progress.search")
			|| step == Step.LOCK && label.equals("halloween.hud.progress.lock")
			|| step == Step.CELLAR && label.equals("halloween.hud.progress.open_cellar")) {
			next(player);
		}
	}

	/** The match ended: after the survivor part the Shape part follows, after the Shape part we are done. */
	public static void matchEnded(MinecraftServer server) {
		if (who != null && step != null && !step.shape()) {
			wait = BREAK_TICKS;
		} else if (who != null) {
			stop();
		}
	}

	/** Should the match keep going even with nobody on the civilians' side (the Shape practises alone)? */
	public static boolean keepsMatch() {
		return who != null && step != null && step.shape() && step != Step.DONE;
	}

	public static void tick(MinecraftServer server) {
		if (who == null) {
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(who);
		if (player == null) {
			stop();
			return;
		}
		if (wait > 0) {
			if (--wait == 0) {
				wait = -1;
				begin(server, player);
				kills = 0;
				go(player, Step.JUMP);
			}
			return;
		}
		if (step == null || !Match.running() && !Match.frozen()) {
			return;
		}
		timer++;
		ShapeState shape = MatchManager.shapeState(player);
		boolean done = switch (step) {
			case HIDE -> Lockers.isInside(player);
			case LEAVE -> !Lockers.isInside(player);
			case WINDOW -> Windows.isVaulting(player);
			case JUMP -> shape != null && shape.shadowTicks > 0;
			case UNJUMP -> shape != null && shape.shadowTicks == 0;
			case DASH -> shape != null && shape.dashTicks > 0;
			case GRAB -> shape != null && shape.grab != null;
			case KILL -> kills > 0;
			default -> false;
		};
		if (done) {
			next(player);
			return;
		}
		if (step == Step.DONE) {
			if (timer > 100) {
				stop();
				Match.stop(server);
			}
			return;
		}
		if (step == Step.CELLAR && !Inventory.has(player, HwItems.Kind.BOLT_CUTTERS) && timer % 20 == 1) {
			Inventory.give(player, HwItems.create(HwItems.Kind.BOLT_CUTTERS));
		}
		if (timer % 20 == 1) {
			player.connection.send(new ClientboundSetActionBarTextPacket(hint(step).copy().withStyle(ChatFormatting.YELLOW)));
		}
		if (timer % 5 == 0) {
			BlockPos target = target(player);
			if (target != null) {
				((ServerLevel) player.level()).sendParticles(player, ParticleTypes.END_ROD, true, false,
					target.getX() + 0.5, target.getY() + 1.6, target.getZ() + 0.5, 3, 0.15, 0.25, 0.15, 0.01);
			}
		}
	}

	/** The Shape killed someone (the last step of his part). */
	public static void killed(ServerPlayer shape) {
		if (shape.getUUID().equals(who)) {
			kills++;
		}
	}

	private static void next(ServerPlayer player) {
		player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
		// After ESCAPE nothing comes here: the match ends with the escape (see matchEnded).
		go(player, Step.values()[step.ordinal() + 1]);
	}

	private static void go(ServerPlayer player, Step s) {
		step = s;
		timer = 0;
		if (s == Step.SEARCH || s == Step.JUMP || s == Step.DONE) {
			player.connection.send(new ClientboundSetTitleTextPacket(Component.translatable(s == Step.DONE ? "halloween.tutorial.done" : "halloween.tutorial.title." + (s.shape() ? "shape" : "survivor"))
				.withStyle(s.shape() ? ChatFormatting.DARK_RED : ChatFormatting.GREEN)));
		}
		// The hint itself stays above the hotbar (renewed every second in tick).
		player.connection.send(new ClientboundSetActionBarTextPacket(hint(s).copy().withStyle(ChatFormatting.YELLOW)));
	}

	private static Component hint(Step s) {
		return switch (s) {
			case JUMP -> Component.translatable("halloween.tutorial.jump", Component.keybind("key.halloween.shape_jump"));
			case UNJUMP -> Component.translatable("halloween.tutorial.unjump", Component.keybind("key.halloween.shape_jump"));
			case DASH -> Component.translatable("halloween.tutorial.dash", Component.keybind("key.sprint"));
			default -> Component.translatable("halloween.tutorial." + s.key());
		};
	}

	/** What the sparks hang over for this step: the nearest thing to use. */
	private static BlockPos target(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		BlockPos at = player.blockPosition();
		return switch (step) {
			case SEARCH -> nearest(at, Loot.containers().stream().filter(p -> !Loot.isSearched(p)).toList());
			case HIDE -> Lockers.nearestFree(level, at, 24);
			case WINDOW -> scan(level, at, 14, st -> Windows.isWindow(st));
			case LOCK -> scan(level, at, 14, st -> st.getBlock() instanceof DoorBlock && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !st.getValue(DoorBlock.OPEN));
			case CELLAR, ESCAPE -> nearest(at, Exits.markers().stream().filter(Exits.Marker::cellar).map(m -> m.pos().below()).toList());
			default -> null;
		};
	}

	private static BlockPos nearest(BlockPos at, java.util.List<BlockPos> list) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : list) {
			double d = p.distSqr(at);
			if (d < bestDist) {
				bestDist = d;
				best = p;
			}
		}
		return best;
	}

	private static BlockPos scan(ServerLevel level, BlockPos at, int radius, java.util.function.Predicate<BlockState> what) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-radius, -2, -radius), at.offset(radius, 3, radius))) {
			double d = p.distSqr(at);
			if (d < bestDist && what.test(level.getBlockState(p))) {
				bestDist = d;
				best = p.immutable();
			}
		}
		return best == null ? null : best.below();
	}

	/** For the Shape part: whose role it is now. */
	static boolean isShapePart(ServerPlayer player) {
		return player.getUUID().equals(who) && MatchManager.role(player) == Role.SHAPE;
	}
}
