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
 * it an order (search there, go for that one, play tricks there, come back), carries it out and comes back; at
 * his heels it still leaps at anyone (survivor or townsperson) who comes close enough, then comes back. With no
 * person playing Michael it hunts on its own: survivors and townsfolk alike, whoever Michael isn't already on,
 * nearest first. Either way it pounces from a crouch (when the pause between pounces is over), winding up as long
 * as the distance needs and leading a runner, frightens people at close quarters while it can't pounce, and with
 * the gifts it has unlocks doors, cuts phones, throws breakers and trades places with Michael.
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
	/** At Michael's heels it leapt at someone who came close (an order of its own): back to him after the leap. */
	private boolean snap;
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

	void order(int kind, net.minecraft.world.entity.LivingEntity survivor, BlockPos pos, int now) {
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
		this.snap = false;
		if (kind == HwNet.RuntOrder.ATTACK && survivor == null) {
			// Pointed at a spot, not at anyone: whoever is nearest it (a survivor or a townsperson), else a search there.
			net.minecraft.world.entity.LivingEntity near = this.nearestPrey(Vec3.atCenterOf(pos), 10);
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
		this.snap = false;
		this.orderTarget = null;
		this.orderPos = null;
		this.target = null;
	}

	void tick(int now, Runt.State s) {
		if (s.leapTicks > 0) {
			// In the air: nothing to steer.
			return;
		}
		if (s.stunTicks > 0 || s.trappedTicks > 0) {
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
		if (this.humanMichael() != null || this.order != -1) {
			// A person playing Michael, or an order a bot Michael has given it: carry it out.
			this.tickCommanded(now, s, michael);
		} else {
			this.tickAlone(now, s, michael);
		}
	}

	// ---------------------------------------------------------------- at Michael's command

	private void tickCommanded(int now, Runt.State s, ServerPlayer michael) {
		if (this.order != -1 && now > this.orderUntil) {
			this.clearOrder();
			if (this.humanMichael() == null) {
				// A bot Michael's order run out: back to hunting alone (no heel to a bot).
				return;
			}
		}
		switch (this.order) {
			case HwNet.RuntOrder.SEARCH -> this.tickSearch(now, s);
			case HwNet.RuntOrder.ATTACK -> {
				net.minecraft.world.entity.LivingEntity t = this.targetEntity();
				if (t == null || this.snap && !pounceReady(s)) {
					// Gone, or (leapt at someone from his heels) the leap is spent: back to Michael.
					this.clearOrder();
					return;
				}
				this.hunt(now, s, t);
			}
			case HwNet.RuntOrder.TRICKS -> this.tickTricks(now, s);
			default -> {
				if (!this.snapAt(now, s)) {
					this.heel(now, michael);
				}
			}
		}
	}

	/** At his heels: someone (a survivor or a townsperson) close and in the open, a leap ready: go for them, then back. */
	private boolean snapAt(int now, Runt.State s) {
		if (!pounceReady(s) || now % 5 != 0) {
			return false;
		}
		net.minecraft.world.entity.LivingEntity prey = this.preyInView(HwConfig.get().runtPounceMaxDistance, now);
		if (prey == null || !this.clearLeap(prey)) {
			return false;
		}
		this.order = HwNet.RuntOrder.ATTACK;
		this.orderTarget = prey.getUUID();
		this.target = this.orderTarget;
		this.orderUntil = now + 20 * 8;
		this.snap = true;
		this.hunt(now, s, prey);
		return true;
	}

	/** The pause between pounces is over (or the clinger's second go is waiting). */
	static boolean pounceReady(Runt.State s) {
		return s.pounceCooldown == 0 || s.secondChance;
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

	/** Off to the place he showed, looking about on the way and there; whoever it sees (survivor or townsperson), it goes for. */
	private void tickSearch(int now, Runt.State s) {
		net.minecraft.world.entity.LivingEntity seen = this.preyInView(12, now);
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
		if (now >= this.retargetAt || this.targetEntity() == null) {
			this.retargetAt = now + 40;
			this.target = this.pickTarget(michael, now);
		}
		net.minecraft.world.entity.LivingEntity t = this.targetEntity();
		if (t == null) {
			this.wander(now);
			return;
		}
		this.hunt(now, s, t);
	}

	/**
	 * After someone (a survivor or a townsperson): close in, pounce when the way is clear, the distance right and
	 * the pause between pounces over (a crouch as long as the leap needs, aimed where they will be); while it
	 * can't pounce, frighten them when right on them, else keep a few steps off and wait.
	 */
	private void hunt(int now, Runt.State s, net.minecraft.world.entity.LivingEntity t) {
		HwConfig cfg = HwConfig.get();
		double d = this.body.distanceTo(t);
		boolean see = Sight.canSee(this.level(), this.body.getEyePosition(), t.getEyePosition(), this.body);
		boolean ready = pounceReady(s);
		if (ready && see && d >= 1.2 && d <= cfg.runtPounceMaxDistance - 0.5 && this.clearLeap(t)) {
			this.walker.halt();
			this.charging = true;
			this.body.setShiftKeyDown(true);
			this.walker.face(this.lead(t));
			return;
		}
		if (d < cfg.runtScareReach - 0.3 && s.scareCooldown == 0 && see) {
			this.walker.halt();
			this.walker.face(t.getEyePosition());
			if (Runt.scare(this.body, t)) {
				this.fleeUntil = now + 40 + this.body.getRandom().nextInt(40);
				this.repathAt = now;
				return;
			}
		}
		if (!ready && see && d < 5) {
			// No leap in it yet: close enough to go the moment it is, not so close they knock it down.
			this.walker.halt();
			this.walker.face(t.getEyePosition());
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
		net.minecraft.world.entity.LivingEntity t = this.targetEntity();
		if (t == null || !pounceReady(s)) {
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
	private Vec3 lead(net.minecraft.world.entity.LivingEntity t) {
		Vec3 v = t.getDeltaMovement().multiply(1, 0, 1);
		return t.getBoundingBox().getCenter().add(v.scale(10));
	}

	/** Nothing in the way of a leap at them, low down. */
	private boolean clearLeap(net.minecraft.world.entity.LivingEntity t) {
		Vec3 from = this.body.position().add(0, 0.4, 0);
		Vec3 to = t.position().add(0, 0.4, 0);
		return Sight.canSee(this.level(), from, to, this.body);
	}

	private void scurryOff(int now) {
		if (this.walker.idle() || now >= this.repathAt) {
			net.minecraft.world.entity.LivingEntity from = this.targetEntity();
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

	/**
	 * Whoever is worth going for: a survivor or a townsperson alike, nearest first (a survivor a little before a
	 * townsperson, the one it is already after before a new one), not in Michael's hands or by him already.
	 */
	private UUID pickTarget(ServerPlayer michael, int now) {
		net.minecraft.world.entity.LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (ServerPlayer p : this.level().players()) {
			if (!MatchManager.role(p).survivor() || p.isSpectator() || Lockers.isInside(p) || MatchManager.isGrabbed(p) || Runt.heldBy(p) != null
				|| this.skip.getOrDefault(p.getUUID(), 0) > now || p.distanceTo(this.body) > 120) {
				continue;
			}
			double score = this.preyScore(p, michael);
			if (score < bestScore) {
				bestScore = score;
				best = p;
			}
		}
		for (net.minecraft.world.entity.decoration.Mannequin m : Townsfolk.bodies()) {
			if (Lockers.isInside(m) || MatchManager.isHeld(m) || Runt.heldBy(m) != null || this.skip.getOrDefault(m.getUUID(), 0) > now
				|| m.distanceTo(this.body) > 60) {
				continue;
			}
			double score = this.preyScore(m, michael) + 6;
			if (score < bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best == null ? null : best.getUUID();
	}

	private double preyScore(net.minecraft.world.entity.LivingEntity e, ServerPlayer michael) {
		double score = e.distanceTo(this.body);
		if (michael != null && e.distanceTo(michael) < 20) {
			// Michael is on them already.
			score += 40;
		}
		if (e.getUUID().equals(this.target)) {
			score -= 5;
		}
		return score;
	}

	/** Who it is after: a survivor, or a townsperson (out in the open, not in Michael's hands). */
	private net.minecraft.world.entity.LivingEntity targetEntity() {
		if (this.target == null) {
			return null;
		}
		ServerPlayer p = this.level().getServer().getPlayerList().getPlayer(this.target);
		if (p != null) {
			return p.isSpectator() || !MatchManager.role(p).survivor() || Lockers.isInside(p) ? null : p;
		}
		var e = this.level().getEntity(this.target);
		return e instanceof net.minecraft.world.entity.LivingEntity l && Townsfolk.isNpc(l) && l.isAlive() && !l.isRemoved() && !Lockers.isInside(l)
			&& !MatchManager.isHeld(l) ? l : null;
	}

	/** The survivor or townsperson nearest a spot (survivors first when both are there). */
	private net.minecraft.world.entity.LivingEntity nearestPrey(Vec3 at, double within) {
		net.minecraft.world.entity.LivingEntity best = null;
		double bestD = within;
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator() && p.position().distanceTo(at) < bestD) {
				best = p;
				bestD = p.position().distanceTo(at);
			}
		}
		if (best != null) {
			return best;
		}
		for (net.minecraft.world.entity.decoration.Mannequin m : Townsfolk.bodies()) {
			if (!Lockers.isInside(m) && !MatchManager.isHeld(m) && m.position().distanceTo(at) < bestD) {
				best = m;
				bestD = m.position().distanceTo(at);
			}
		}
		return best;
	}

	/** Someone in view near it to go for: a survivor or a townsperson out in the open, nearest first (survivors a little before). */
	private net.minecraft.world.entity.LivingEntity preyInView(double within, int now) {
		net.minecraft.world.entity.LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (ServerPlayer p : this.level().players()) {
			double d = p.distanceTo(this.body);
			if (MatchManager.role(p).survivor() && !p.isSpectator() && !Lockers.isInside(p) && !MatchManager.isGrabbed(p) && Runt.heldBy(p) == null
				&& this.skip.getOrDefault(p.getUUID(), 0) <= now && d < within && d < bestScore
				&& Sight.canSee(this.level(), this.body.getEyePosition(), p.getEyePosition(), this.body)) {
				best = p;
				bestScore = d;
			}
		}
		for (net.minecraft.world.entity.decoration.Mannequin m : Townsfolk.bodies()) {
			double d = m.distanceTo(this.body);
			if (!Lockers.isInside(m) && !MatchManager.isHeld(m) && Runt.heldBy(m) == null && this.skip.getOrDefault(m.getUUID(), 0) <= now
				&& d < within && d + 1.5 < bestScore && Sight.canSee(this.level(), this.body.getEyePosition(), m.getEyePosition(), this.body)) {
				best = m;
				bestScore = d + 1.5;
			}
		}
		return best;
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
