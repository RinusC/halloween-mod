package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A bot playing the Runt. With a person playing Michael it is his creature: it keeps at his heels until he gives
 * it an order (search there, go for that one, play tricks there, come back), carries it out and comes back. With
 * no person playing Michael it hunts on its own: whoever Michael isn't already on, nearest first. Either way it
 * pounces from a crouch, winding up as long as the distance needs and leading a runner, frightens people at
 * close quarters, giggles when they can't see it, and with the gifts it has unlocks doors, cuts phones, throws
 * breakers and trades places with Michael.
 */
final class RuntBrain {
	private final BotPlayer body;
	private final Walker walker;
	/** Michael's order (-1: none, at his heels), its target and place, how long it stands. */
	private int order = -1;
	private UUID orderTarget;
	private BlockPos orderPos;
	private int orderUntil;
	private int searchUntil;
	private UUID target;
	private int retargetAt;
	private int repathAt;
	private int fleeUntil;
	private int lookAroundAt;
	/** Winding up a pounce at {@link #target}. */
	private boolean charging;
	/** Survivors it couldn't get to, and until when it leaves them be. */
	private final Map<UUID, Integer> skip = new HashMap<>();

	RuntBrain(BotPlayer body) {
		this.body = body;
		this.walker = new Walker(body);
		this.walker.closeDoors = false;
	}

	private ServerLevel level() {
		return this.body.level();
	}

	/** For Michael's screen: 0 at his heels, 1 searching, 2 going for someone, 3 tricks, 4 hunting on its own. */
	int status() {
		if (this.order == HwNet.RuntOrder.SEARCH) {
			return 1;
		}
		if (this.order == HwNet.RuntOrder.ATTACK) {
			return 2;
		}
		if (this.order == HwNet.RuntOrder.TRICKS) {
			return 3;
		}
		return this.humanMichael() != null ? 0 : 4;
	}

	void order(int kind, ServerPlayer survivor, BlockPos pos, int now) {
		this.walker.stop();
		this.stopCharging();
		this.repathAt = now;
		this.fleeUntil = 0;
		if (kind == HwNet.RuntOrder.HEEL) {
			this.clearOrder();
			return;
		}
		this.order = kind;
		this.orderPos = pos;
		this.orderTarget = survivor == null ? null : survivor.getUUID();
		this.orderUntil = now + 20 * 40;
		this.searchUntil = 0;
		if (kind == HwNet.RuntOrder.ATTACK && survivor == null) {
			// Pointed at a spot, not at anyone: whoever is nearest it, else a search there.
			ServerPlayer near = this.nearestSurvivor(Vec3.atCenterOf(pos), 10);
			if (near != null) {
				this.orderTarget = near.getUUID();
			} else {
				this.order = HwNet.RuntOrder.SEARCH;
			}
		}
		this.target = this.orderTarget;
	}

	private void clearOrder() {
		this.order = -1;
		this.orderTarget = null;
		this.orderPos = null;
		this.target = null;
	}

	void tick(int now, Runt.State s) {
		if (s.leapTicks > 0) {
			// In the air: nothing to steer.
			return;
		}
		if (s.outTicks > 0 || s.stunTicks > 0 || s.trappedTicks > 0) {
			this.walker.halt();
			this.stopCharging();
			return;
		}
		ServerPlayer michael = this.michael();
		if (s.holding != null) {
			this.walker.halt();
			this.stopCharging();
			// Pinned somebody far from Michael: fetch him (trade places, he is on them at once).
			if (michael != null && s.has("swap") && s.specialCooldown == 0 && michael.distanceTo(this.body) > 30) {
				Runt.special(this.body);
			}
			if (this.order == HwNet.RuntOrder.ATTACK) {
				this.clearOrder();
			}
			return;
		}
		if (this.charging) {
			this.tickCharge(s);
			return;
		}
		// A giggle where people are who can't see it.
		if (s.laughCooldown == 0 && now % 20 == 0 && this.unseenBy(16) > 0) {
			Runt.laugh(this.body);
		}
		if (this.humanMichael() != null) {
			this.tickCommanded(now, s, michael);
		} else {
			this.tickAlone(now, s, michael);
		}
	}

	// ---------------------------------------------------------------- at Michael's command

	private void tickCommanded(int now, Runt.State s, ServerPlayer michael) {
		if (this.order != -1 && now > this.orderUntil) {
			this.clearOrder();
		}
		switch (this.order) {
			case HwNet.RuntOrder.SEARCH -> this.tickSearch(now, s);
			case HwNet.RuntOrder.ATTACK -> {
				ServerPlayer t = this.targetPlayer();
				if (t == null) {
					this.clearOrder();
					return;
				}
				this.hunt(now, s, t);
			}
			case HwNet.RuntOrder.TRICKS -> this.tickTricks(now, s);
			default -> this.heel(now, michael);
		}
	}

	/** At his heels: a few steps off, never under his feet. */
	private void heel(int now, ServerPlayer michael) {
		if (michael == null) {
			this.walker.halt();
			return;
		}
		double d = this.body.distanceTo(michael);
		if (d > 4.5) {
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 20;
				this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), michael.blockPosition(), 2.5, Nav.Options.walker()));
			}
			this.walker.speed = d > 10 ? 1f : 0.8f;
			this.walker.tick();
		} else {
			this.walker.halt();
			if (now % 40 == 0) {
				this.walker.face(michael.getEyePosition());
			}
		}
	}

	/** Off to the place he showed, looking about on the way and there; whoever it sees, it goes for. */
	private void tickSearch(int now, Runt.State s) {
		ServerPlayer seen = this.visibleSurvivor(12);
		if (seen != null) {
			this.order = HwNet.RuntOrder.ATTACK;
			this.orderTarget = seen.getUUID();
			this.target = this.orderTarget;
			this.orderUntil = now + 20 * 30;
			return;
		}
		if (this.orderPos == null) {
			this.clearOrder();
			return;
		}
		boolean there = this.body.position().distanceTo(Vec3.atBottomCenterOf(this.orderPos)) < 3.5;
		if (there && this.searchUntil == 0) {
			this.searchUntil = now + 160;
		}
		if (this.searchUntil > 0 && now > this.searchUntil) {
			// Nobody there: back to Michael.
			this.clearOrder();
			return;
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + 40;
			BlockPos to = this.searchUntil > 0
				? this.orderPos.offset(this.body.getRandom().nextInt(13) - 6, 0, this.body.getRandom().nextInt(13) - 6) : this.orderPos;
			this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), to, 2, Nav.Options.walker()));
		}
		this.walker.speed = 1f;
		this.walker.tick();
	}

	/** Off to the place he showed to play tricks there: its mischief if it has the gift, else knocking. */
	private void tickTricks(int now, Runt.State s) {
		if (this.orderPos == null) {
			this.clearOrder();
			return;
		}
		if (this.body.position().distanceTo(Vec3.atBottomCenterOf(this.orderPos)) > 4) {
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 30;
				this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), this.orderPos, 2.5, Nav.Options.walker()));
			}
			this.walker.speed = 1f;
			this.walker.tick();
			return;
		}
		this.walker.halt();
		if (!(s.has("mischief") && s.trickCooldown == 0 && this.tryMischief(5)) && s.knockCooldown == 0) {
			this.tryKnock(5);
		}
		this.clearOrder();
	}

	// ---------------------------------------------------------------- on its own

	private void tickAlone(int now, Runt.State s, ServerPlayer michael) {
		if (now % 20 == 0 && s.has("mischief") && s.trickCooldown == 0) {
			this.tryMischief(3);
		}
		if (now < this.fleeUntil) {
			this.scurryOff(now);
			return;
		}
		if (now >= this.retargetAt || this.targetPlayer() == null) {
			this.retargetAt = now + 40;
			this.target = this.pickTarget(michael, now);
		}
		ServerPlayer t = this.targetPlayer();
		if (t == null) {
			this.wander(now);
			return;
		}
		this.hunt(now, s, t);
	}

	/**
	 * After someone: close in, pounce when the way is clear and the distance right (a crouch as long as the leap
	 * needs, aimed where they will be), frighten them when right on them.
	 */
	private void hunt(int now, Runt.State s, ServerPlayer t) {
		HwConfig cfg = HwConfig.get();
		double d = this.body.distanceTo(t);
		boolean see = Sight.canSee(this.level(), this.body.getEyePosition(), t.getEyePosition(), this.body);
		if (d < cfg.runtScareReach - 0.3 && s.scareCooldown == 0 && see) {
			this.walker.halt();
			this.walker.face(t.getEyePosition());
			if (Runt.scare(this.body, t)) {
				this.fleeUntil = now + 40 + this.body.getRandom().nextInt(40);
				this.repathAt = now;
				return;
			}
		}
		if (see && d >= 2.5 && d <= cfg.runtPounceMaxDistance - 0.5 && this.clearLeap(t)) {
			this.walker.halt();
			this.charging = true;
			this.body.setShiftKeyDown(true);
			this.walker.face(this.lead(t));
			return;
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + 15;
			var path = Nav.to(this.level(), this.body.blockPosition(), t.blockPosition(), 1.2, Nav.Options.walker().lockedDoors(s.has("mischief") ? 4 : -1));
			if (path.isEmpty() && d > 3) {
				this.skip.put(t.getUUID(), now + 200);
				this.target = null;
				if (this.order == HwNet.RuntOrder.ATTACK) {
					this.clearOrder();
				}
				return;
			}
			this.walker.follow(path);
		}
		this.walker.speed = 1f;
		Walker.Status status = this.walker.tick();
		if (status == Walker.Status.LOCKED) {
			BlockPos door = this.walker.current() == null ? null : this.walker.current().pos();
			if (door == null || !Runt.mischief(this.body, door) && !Runt.mischief(this.body, door.above())) {
				this.skip.put(t.getUUID(), now + 200);
				this.target = null;
				this.walker.stop();
			}
		} else if (status == Walker.Status.STUCK || status == Walker.Status.WINDOW) {
			this.skip.put(t.getUUID(), now + 100);
			this.target = null;
			this.walker.stop();
		}
	}

	/** Crouched: aim where they will be, let go once wound up enough for the distance to it. */
	private void tickCharge(Runt.State s) {
		HwConfig cfg = HwConfig.get();
		ServerPlayer t = this.targetPlayer();
		if (t == null) {
			this.stopCharging();
			return;
		}
		Vec3 aim = this.lead(t);
		this.walker.face(aim);
		double d = this.body.position().distanceTo(aim.multiply(1, 0, 1).add(0, this.body.getY(), 0));
		if (d > cfg.runtPounceMaxDistance + 2 || !Sight.canSee(this.level(), this.body.getEyePosition(), t.getEyePosition(), this.body)) {
			this.stopCharging();
			return;
		}
		double span = Math.max(0.1, cfg.runtPounceMaxDistance - cfg.runtPounceMinDistance);
		int needed = (int) Math.ceil(Math.max(cfg.runtPounceMinChargeTicks, (d - cfg.runtPounceMinDistance) / span * cfg.runtPounceChargeTicks));
		if (s.crouch >= Math.min(needed, cfg.runtPounceChargeTicks)) {
			// Let go: the server sees the crouch end and the leap goes the way it faces.
			this.body.setShiftKeyDown(false);
			this.charging = false;
		}
	}

	private void stopCharging() {
		if (this.charging) {
			this.charging = false;
		}
		this.body.setShiftKeyDown(false);
	}

	/** Where they will be when it lands, about ten ticks on. */
	private Vec3 lead(ServerPlayer t) {
		Vec3 v = t.getDeltaMovement().multiply(1, 0, 1);
		return t.getBoundingBox().getCenter().add(v.scale(10));
	}

	/** Nothing in the way of a leap at them, low down. */
	private boolean clearLeap(ServerPlayer t) {
		Vec3 from = this.body.position().add(0, 0.4, 0);
		Vec3 to = t.position().add(0, 0.4, 0);
		return Sight.canSee(this.level(), from, to, this.body);
	}

	private void scurryOff(int now) {
		if (this.walker.idle() || now >= this.repathAt) {
			ServerPlayer from = this.targetPlayer();
			Vec3 away = from == null ? Vec3.ZERO : this.body.position().subtract(from.position()).multiply(1, 0, 1);
			away = away.lengthSqr() < 1.0E-4 ? new Vec3(this.body.getRandom().nextGaussian(), 0, this.body.getRandom().nextGaussian()) : away;
			away = away.normalize().scale(14);
			BlockPos to = BlockPos.containing(this.body.position().add(away));
			this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), to, 3, Nav.Options.walker()));
			this.repathAt = now + 30;
		}
		this.walker.speed = 1f;
		this.walker.tick();
	}

	/** Whoever is worth frightening: near, alone, and not already in Michael's hands or by him. */
	private UUID pickTarget(ServerPlayer michael, int now) {
		ServerPlayer best = null;
		double bestScore = Double.MAX_VALUE;
		for (ServerPlayer p : this.level().players()) {
			if (!MatchManager.role(p).survivor() || p.isSpectator() || Lockers.isInside(p) || MatchManager.isGrabbed(p)
				|| this.skip.getOrDefault(p.getUUID(), 0) > now) {
				continue;
			}
			double score = p.distanceTo(this.body);
			if (michael != null && p.distanceTo(michael) < 20) {
				score += 40;
			}
			if (score < bestScore && p.distanceTo(this.body) < 120) {
				bestScore = score;
				best = p;
			}
		}
		return best == null ? null : best.getUUID();
	}

	private ServerPlayer targetPlayer() {
		ServerPlayer p = this.target == null ? null : this.level().getServer().getPlayerList().getPlayer(this.target);
		return p == null || p.isSpectator() || !MatchManager.role(p).survivor() || Lockers.isInside(p) ? null : p;
	}

	private ServerPlayer nearestSurvivor(Vec3 at, double within) {
		ServerPlayer best = null;
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator() && p.position().distanceTo(at) < within
				&& (best == null || p.position().distanceTo(at) < best.position().distanceTo(at))) {
				best = p;
			}
		}
		return best;
	}

	private ServerPlayer visibleSurvivor(double within) {
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator() && !Lockers.isInside(p) && p.distanceTo(this.body) < within
				&& Sight.canSee(this.level(), this.body.getEyePosition(), p.getEyePosition(), this.body)) {
				return p;
			}
		}
		return null;
	}

	/** How many survivors near it don't have it in view. */
	private int unseenBy(double within) {
		int n = 0;
		Vec3 me = this.body.getBoundingBox().getCenter();
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator() && p.distanceTo(this.body) < within
				&& (Sight.angleTo(p.getEyePosition(), p.getViewVector(1f), me) > 70 || !Sight.canSee(this.level(), p.getEyePosition(), me, p))) {
				n++;
			}
		}
		return n;
	}

	private ServerPlayer michael() {
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p) == Role.SHAPE) {
				return p;
			}
		}
		return null;
	}

	/** Michael played by a person: then the Runt waits on his orders. */
	private ServerPlayer humanMichael() {
		ServerPlayer m = this.michael();
		return m == null || m instanceof BotPlayer ? null : m;
	}

	private void wander(int now) {
		if (now >= this.lookAroundAt || this.walker.idle()) {
			this.lookAroundAt = now + 100;
			BlockPos center = MapPoints.get(this.level()).first(MapPoints.Type.CENTER);
			BlockPos to = center == null ? this.body.blockPosition()
				: center.offset(this.body.getRandom().nextInt(81) - 40, 0, this.body.getRandom().nextInt(81) - 40);
			this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), to, 3, Nav.Options.walker()));
		}
		this.walker.tick();
	}

	/** Tricks within reach: a locked door, a phone that works, a breaker that's on. */
	private boolean tryMischief(int r) {
		BlockPos at = this.body.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 2, r))) {
			var st = this.level().getBlockState(p);
			boolean worth = DoorLocks.isDoor(st) && DoorLocks.isLocked(this.level(), p)
				|| st.is(dev.halloween.HwBlocks.PHONE) && Exits.phoneWorks(p.immutable())
				|| LightSystem.isOn(this.level(), p);
			if (worth && Vec3.atCenterOf(p).distanceTo(this.body.getEyePosition()) <= 4.5
				&& Sight.canSee(this.level(), this.body.getEyePosition(), Vec3.atCenterOf(p), this.body)) {
				this.walker.face(Vec3.atCenterOf(p));
				if (Runt.mischief(this.body, p.immutable())) {
					return true;
				}
			}
		}
		return false;
	}

	/** Knocks on the nearest door or window within reach. */
	private boolean tryKnock(int r) {
		BlockPos at = this.body.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, 0, -r), at.offset(r, 2, r))) {
			var st = this.level().getBlockState(p);
			if ((DoorLocks.isDoor(st) || Windows.isWindow(st)) && Vec3.atCenterOf(p).distanceTo(this.body.getEyePosition()) <= 3.4) {
				this.walker.face(Vec3.atCenterOf(p));
				if (Runt.knock(this.body, p.immutable())) {
					return true;
				}
			}
		}
		return false;
	}
}
