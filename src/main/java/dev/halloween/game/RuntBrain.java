package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A bot playing the Runt: it picks someone to go after (whoever Michael isn't already on, nearest first),
 * creeps up and frightens them, then scurries off while it gets its breath back and goes for the next. With
 * the gifts it has, it unlocks doors in its way, cuts phones and throws breakers it passes, and trades places
 * with Michael when it has somebody pinned far from him.
 */
final class RuntBrain {
	private final BotPlayer body;
	private final Walker walker;
	private UUID target;
	private int retargetAt;
	private int repathAt;
	private int fleeUntil;
	private int lookAroundAt;
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

	void tick(int now, Runt.State s) {
		HwConfig cfg = HwConfig.get();
		if (s.outTicks > 0 || s.stunTicks > 0 || s.trappedTicks > 0) {
			this.walker.halt();
			return;
		}
		ServerPlayer michael = this.michael();
		if (s.holding != null) {
			this.walker.halt();
			// Pinned somebody far from Michael: fetch him (trade places, he is on them at once).
			if (michael != null && s.has("swap") && s.specialCooldown == 0 && michael.distanceTo(this.body) > 30) {
				Runt.special(this.body);
			}
			return;
		}
		if (now % 20 == 0 && s.has("mischief") && s.trickCooldown == 0) {
			this.tryMischief();
		}
		if (now < this.fleeUntil) {
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
		double d = this.body.distanceTo(t);
		if (d < cfg.runtScareReach - 0.3 && s.scareCooldown == 0) {
			this.walker.halt();
			this.walker.face(t.getEyePosition());
			if (Runt.scare(this.body, t)) {
				this.fleeUntil = now + 60 + this.body.getRandom().nextInt(60);
				this.repathAt = now;
				if (s.has("swap") && s.specialCooldown == 0 && michael != null && michael.distanceTo(this.body) > 40) {
					Runt.special(this.body);
				}
				return;
			}
		}
		if (s.scareCooldown > 0 && d < 6) {
			// Still getting its breath back: keep just out of reach, watching.
			this.walker.halt();
			this.walker.face(t.getEyePosition());
			if (d < 3) {
				this.fleeUntil = now + 30;
			}
			return;
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + 15;
			var path = Nav.to(this.level(), this.body.blockPosition(), t.blockPosition(), 1.2,
				Nav.Options.walker().lockedDoors(s.has("mischief") ? 4 : -1));
			if (path.isEmpty() && d > 3) {
				this.skip.put(t.getUUID(), now + 200);
				this.target = null;
				return;
			}
			this.walker.follow(path);
		}
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
		return p == null || p.isSpectator() || !MatchManager.role(p).survivor() ? null : p;
	}

	private ServerPlayer michael() {
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p) == Role.SHAPE) {
				return p;
			}
		}
		return null;
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
	private void tryMischief() {
		BlockPos at = this.body.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 2, 3))) {
			var st = this.level().getBlockState(p);
			boolean worth = DoorLocks.isDoor(st) && DoorLocks.isLocked(this.level(), p)
				|| st.is(dev.halloween.HwBlocks.PHONE) && Exits.phoneWorks(p.immutable())
				|| LightSystem.isOn(this.level(), p);
			if (worth && Sight.canSee(this.level(), this.body.getEyePosition(), Vec3.atCenterOf(p), this.body)) {
				this.walker.face(Vec3.atCenterOf(p));
				if (Runt.mischief(this.body, p.immutable())) {
					return;
				}
			}
		}
	}
}
