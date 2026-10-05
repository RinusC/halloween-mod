package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.FocusPoints;
import dev.halloween.game.GrabSession;
import dev.halloween.game.Loot;
import dev.halloween.game.LockerBlock;
import dev.halloween.game.Lockers;
import dev.halloween.game.MapPoints;
import dev.halloween.game.Match;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Nav;
import dev.halloween.game.ShapeState;
import dev.halloween.game.Sight;
import dev.halloween.game.Walker;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A plain but fair Michael for the AI simulation: sees only what is in front of him (any light), walks
 * (no Shape Jump), chases what he sees, searches where he lost it and throws open lockers there, breaks
 * locked doors in his way, otherwise patrols the places civilians need. Grabs land with {@link #SKILL}.
 * Also samples what the bots are doing.
 */
final class SimShape {
	static final float SKILL = 0.8f;

	private enum Mode {
		PATROL, CHASE, SEARCH
	}

	static BotPlayer shape;
	private static Walker walker;
	private static Mode mode = Mode.PATROL;
	private static LivingEntity target;
	private static Vec3 lastSeen;
	private static int lastSeenTick;
	private static int repathAt;
	private static BlockPos goal;
	private static final Random RNG = new Random(7);
	private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();
	static final Map<String, Integer> MODE_SECONDS = new HashMap<>();

	private SimShape() {
	}

	static void attach(BotPlayer bot) {
		shape = bot;
		walker = new Walker(bot);
		walker.closeDoors = false;
		mode = Mode.PATROL;
		target = null;
		goal = null;
		LAST_POS.clear();
	}

	static void detach() {
		shape = null;
	}

	static void tick(MinecraftServer server) {
		if (shape == null || shape.isRemoved() || !Match.running()) {
			return;
		}
		ShapeState state = MatchManager.shapeState(shape);
		if (state == null) {
			return;
		}
		int now = server.getTickCount();
		if (now % 20 == 0) {
			sample(server);
		}
		ServerLevel level = shape.level();
		if (state.grab != null) {
			walker.halt();
			GrabSession grab = state.grab;
			aim(grab.victim().position().add(0, 1, 0));
			if (grab.shapeWouldHit() && RNG.nextFloat() < SKILL / 3) {
				MatchManager.onQtePress(shape);
			}
			return;
		}
		if (state.busy()) {
			walker.halt();
			return;
		}

		LivingEntity seen = look(level);
		if (seen != null) {
			if (target != seen || mode != Mode.CHASE) {
				AiStats.add("~shape", "spotted");
				if (seen instanceof ServerPlayer p) {
					AiStats.add("~shape", "spotted.while." + Bots.modeOf(p));
					// Did the bot already know he was there?
					AiStats.add("~shape", "spotted.bot_" + (Bots.modeOf(p).startsWith("flee") ? "aware" : "unaware"));
				}
			}
			target = seen;
			lastSeen = seen.position();
			lastSeenTick = now;
			mode = Mode.CHASE;
		}

		switch (mode) {
			case CHASE -> chase(level, state, now);
			case SEARCH -> search(level, now);
			case PATROL -> patrol(level, now);
		}
	}

	private static LivingEntity look(ServerLevel level) {
		Vec3 eye = shape.getEyePosition();
		Vec3 facing = shape.getViewVector(1f).multiply(1, 0, 1).normalize();
		LivingEntity best = null;
		double bestDist = 28;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, shape.getBoundingBox().inflate(28), MatchManager::exposed)) {
			Vec3 body = FocusPoints.bodyCenter(e);
			double d = eye.distanceTo(body);
			if (d >= bestDist || (d > 3 && Sight.angleTo(eye, facing, new Vec3(body.x, eye.y, body.z)) > 70)) {
				continue;
			}
			if (Sight.canSee(level, eye, body, shape)) {
				best = e;
				bestDist = d;
			}
		}
		return best;
	}

	private static void aim(Vec3 at) {
		Vec3 eye = shape.getEyePosition();
		Vec3 dir = at.subtract(eye).normalize();
		MatchManager.onAim(shape, eye, dir);
		walker.face(at);
	}

	private static Walker.Status walk(ServerLevel level, int now) {
		Walker.Status status = walker.tick();
		if (status == Walker.Status.LOCKED) {
			// A locked door: smash it.
			Nav.Node node = walker.current();
			aim(Vec3.atCenterOf(node.pos().above()));
			MatchManager.onShapeAttack(shape, -1, true);
		}
		return status;
	}

	private static void chase(ServerLevel level, ShapeState state, int now) {
		HwConfig cfg = HwConfig.get();
		if (target == null || target.isRemoved() || !MatchManager.isVictim(target) || now - lastSeenTick > 30) {
			mode = Mode.SEARCH;
			walker.follow(Nav.to(level, shape.blockPosition(), BlockPos.containing(lastSeen), 1.2, Nav.Options.walker().lockedDoors(4)));
			return;
		}
		double dist = shape.distanceTo(target);
		boolean visible = now == lastSeenTick;
		if (visible && dist <= cfg.grabRange - 0.3 && state.grabCooldown == 0) {
			aim(FocusPoints.bodyCenter(target));
			MatchManager.onGrab(shape, target.getId());
			if (state.grab != null) {
				AiStats.add("~shape", "grab");
				return;
			}
		}
		if (visible && dist <= cfg.attackReach && state.attackCooldown == 0 && RNG.nextFloat() < 0.15f) {
			aim(FocusPoints.bodyCenter(target));
			MatchManager.onShapeAttack(shape, target.getId(), RNG.nextBoolean());
			AiStats.add("~shape", "strike");
		}
		if (visible && dist > 4 && dist < 9 && state.dashCooldown == 0 && RNG.nextFloat() < 0.05f) {
			aim(FocusPoints.bodyCenter(target));
			MatchManager.onDash(shape);
		}
		if (now >= repathAt || walker.idle()) {
			repathAt = now + 8;
			walker.follow(Nav.to(level, shape.blockPosition(), target.blockPosition(), 1.0, Nav.Options.walker().lockedDoors(4).nodes(3000)));
		}
		walk(level, now);
		if (visible) {
			MatchManager.onAim(shape, shape.getEyePosition(), FocusPoints.bodyCenter(target).subtract(shape.getEyePosition()).normalize());
		}
	}

	private static void search(ServerLevel level, int now) {
		Walker.Status status = walk(level, now);
		if (status == Walker.Status.MOVING || status == Walker.Status.LOCKED) {
			return;
		}
		// Lost them here: throw open every locker around.
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(lastSeen).offset(-5, -1, -5), BlockPos.containing(lastSeen).offset(5, 2, 5))) {
			var state = level.getBlockState(pos);
			if (state.getBlock() instanceof LockerBlock && state.getValue(LockerBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
				BlockPos base = pos.immutable();
				BlockPos front = Lockers.front(level, base);
				if (shape.position().distanceTo(Vec3.atBottomCenterOf(front)) > 6) {
					continue;
				}
				shape.teleportTo(level, front.getX() + 0.5, front.getY(), front.getZ() + 0.5, java.util.Set.of(), shape.getYRot(), 0f, false);
				aim(Vec3.atCenterOf(base));
				MatchManager.shapeState(shape).attackCooldown = 0;
				boolean someone = Lockers.occupied(base);
				Lockers.shapeOpen(shape, base);
				AiStats.add("~shape", someone ? "locker.found" : "locker.empty");
				if (someone) {
					break;
				}
			}
		}
		mode = Mode.PATROL;
		goal = null;
	}

	private static void patrol(ServerLevel level, int now) {
		if (goal == null || walker.idle()) {
			List<BlockPos> spots = new ArrayList<>(Loot.containers());
			for (HwNet.ExitInfo e : dev.halloween.game.Exits.infos()) {
				spots.add(e.pos());
				spots.add(e.pos());
			}
			spots.addAll(MapPoints.get(level).list(MapPoints.Type.PHONE));
			if (spots.isEmpty()) {
				return;
			}
			goal = spots.get(RNG.nextInt(spots.size()));
			walker.follow(Nav.to(level, shape.blockPosition(), goal, 2.0, Nav.Options.walker().lockedDoors(4)));
			if (walker.idle()) {
				goal = null;
				return;
			}
		}
		Walker.Status status = walk(level, now);
		MatchManager.onAim(shape, shape.getEyePosition(), shape.getViewVector(1f));
		if (now % 40 == 0) {
			// Michael kills the lights where he goes: darkness is his.
			for (BlockPos breaker : dev.halloween.game.LightSystem.breakers(level, shape.blockPosition(), 4)) {
				if (dev.halloween.game.LightSystem.isOn(level, breaker)) {
					dev.halloween.game.LightSystem.flip(level, breaker, false);
					AiStats.add("~shape", "power.off");
				}
			}
		}
		if (status == Walker.Status.STUCK || status == Walker.Status.ARRIVED) {
			goal = null;
		}
	}

	/** Once a second: what each bot is doing, and whether it stands around with nothing to show for it. */
	private static void sample(MinecraftServer server) {
		for (BotPlayer bot : Bots.all()) {
			if (bot == shape || bot.isSpectator() || !MatchManager.role(bot).survivor()) {
				continue;
			}
			String m = Bots.modeOf(bot);
			MODE_SECONDS.merge(m, 1, Integer::sum);
			Vec3 before = LAST_POS.put(bot.getUUID(), bot.position());
			if (before != null && before.distanceTo(bot.position()) < 0.2 && m.startsWith("roam")) {
				AiStats.add(bot, "idle.second");
				AiStats.add("~idle", m);
			}
		}
	}

	static AABB area() {
		return shape.getBoundingBox();
	}
}
