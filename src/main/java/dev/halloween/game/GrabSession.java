package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Shape holding a victim. Both sides play reaction checks at once:
 * the victim scores with its own hits and with the Shape's misses and escapes at
 * {@link HwConfig#victimPointsToEscape}; the Shape scores only with his own hits and executes at
 * {@link HwConfig#shapePointsToKillByStalk} (fewer for a well-stalked victim). Executions go through a window when the victim is fully stalked
 * and glass is in front, and turn bloody at a high Bloodthirst level.
 */
public final class GrabSession {
	private final ServerPlayer shape;
	private final ShapeState state;
	final LivingEntity victim;
	private final VictimState victimState;
	private final RandomSource random;

	private int victimPoints;
	private int shapePoints;
	private final Track victimTrack = new Track();
	private final Track shapeTrack = new Track();

	private boolean finishing;
	private int finisherTick;
	private BlockPos windowPos;
	private boolean bloody;
	private boolean done;

	/** One side's current reaction check. */
	private static final class Track {
		long roundStart;
		float zoneStart;
		float zoneWidth;
		boolean open;
		int simPressTick;
		boolean simSuccess;
	}

	GrabSession(ServerPlayer shape, ShapeState state, LivingEntity victim, VictimState victimState) {
		this.shape = shape;
		this.state = state;
		this.victim = victim;
		this.victimState = victimState;
		this.random = shape.getRandom();
		victimState.grabbed = true;
		this.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), HwSounds.GRAB, SoundSource.HOSTILE, 1.2f, 1f);
		this.startVictimRound();
		this.startShapeRound();
		this.sendState();
	}

	private ServerLevel level() {
		return this.shape.level();
	}

	private HwConfig cfg() {
		return HwConfig.get();
	}

	/** Hits the Shape still needs in total: the more the victim was stalked, the fewer. */
	public int shapeNeeded() {
		return HwConfig.at(this.cfg().shapePointsToKillByStalk, this.victimState.level());
	}

	boolean isDone() {
		return this.done;
	}

	// ---------------------------------------------------------------- reaction checks

	private void startVictimRound() {
		HwConfig cfg = this.cfg();
		Track t = this.victimTrack;
		t.roundStart = this.level().getGameTime();
		t.zoneWidth = Math.max(0.08f, cfg.victimZoneWidth
			- HwConfig.at(cfg.victimZoneShrinkByTier, this.state.tier())
			- HwConfig.at(cfg.victimZoneShrinkByStalk, this.victimState.level()));
		t.zoneStart = 0.1f + this.random.nextFloat() * (0.8f - t.zoneWidth);
		t.open = true;
		t.simSuccess = this.random.nextFloat() < (this.victim instanceof BotPlayer ? cfg.botQteSuccessChance : cfg.mannequinQteSuccessChance);
		t.simPressTick = 8 + this.random.nextInt(Math.max(1, cfg.victimRoundTicks - 12));
	}

	private void startShapeRound() {
		HwConfig cfg = this.cfg();
		Track t = this.shapeTrack;
		t.roundStart = this.level().getGameTime();
		t.zoneWidth = Math.min(0.5f, cfg.shapeZoneWidth + HwConfig.at(cfg.shapeZoneGrowByStalk, this.victimState.level()));
		t.zoneStart = 0.2f + this.random.nextFloat() * (0.7f - t.zoneWidth);
		t.open = true;
	}

	private void sendState() {
		HwConfig cfg = this.cfg();
		boolean windowReady = this.windowAvailable();
		this.bloody = this.state.level() >= cfg.bloodyExecutionLevel;
		if (this.victim instanceof ServerPlayer player) {
			Track t = this.victimTrack;
			ServerPlayNetworking.send(player, new HwNet.Qte(true, false, t.zoneStart, t.zoneWidth, cfg.victimCursorPeriodTicks,
				t.roundStart, cfg.victimRoundTicks, this.victimPoints, cfg.victimPointsToEscape, this.shapePoints, this.shapeNeeded(),
				false, false));
		}
		Track s = this.shapeTrack;
		ServerPlayNetworking.send(this.shape, new HwNet.Qte(true, true, s.zoneStart, s.zoneWidth, cfg.shapeNeedlePeriodTicks,
			s.roundStart, cfg.shapeRoundTicks, this.shapePoints, this.shapeNeeded(), this.victimPoints, cfg.victimPointsToEscape,
			windowReady, this.bloody));
	}

	/** Someone in the grab pressed their check key. */
	void press(ServerPlayer presser) {
		if (this.finishing) {
			return;
		}
		long now = this.level().getGameTime();
		HwConfig cfg = this.cfg();
		if (presser == this.shape && this.shapeTrack.open) {
			long elapsed = now - this.shapeTrack.roundStart;
			if (elapsed < 3) {
				return;
			}
			float needle = HwNet.needle(elapsed, cfg.shapeNeedlePeriodTicks);
			this.resolveShape(needle >= this.shapeTrack.zoneStart && needle <= this.shapeTrack.zoneStart + this.shapeTrack.zoneWidth);
		} else if (presser == this.victim && this.victimTrack.open) {
			long elapsed = now - this.victimTrack.roundStart;
			if (elapsed < 3) {
				return;
			}
			float cursor = HwNet.qteCursor(elapsed, cfg.victimCursorPeriodTicks);
			this.resolveVictim(cursor >= this.victimTrack.zoneStart && cursor <= this.victimTrack.zoneStart + this.victimTrack.zoneWidth);
		}
	}

	private void resolveVictim(boolean hit) {
		this.victimTrack.open = false;
		if (hit) {
			this.victimPoints++;
		} else if (this.cfg().victimMissGivesShapePoint) {
			this.shapePoints++;
		}
		this.feedback(this.victim, hit);
		if (!this.checkEnd()) {
			this.startVictimRound();
			this.sendState();
		}
	}

	private void resolveShape(boolean hit) {
		this.shapeTrack.open = false;
		if (hit) {
			this.shapePoints++;
		} else {
			this.victimPoints++;
		}
		this.feedback(this.shape, hit);
		if (!this.checkEnd()) {
			this.startShapeRound();
			this.sendState();
		}
	}

	private void feedback(LivingEntity who, boolean hit) {
		this.level().playSound(null, who.getX(), who.getY(), who.getZ(),
			hit ? HwSounds.QTE_HIT : HwSounds.QTE_MISS, SoundSource.PLAYERS, 1f, hit ? 1.2f : 0.7f);
	}

	/** Returns true once the grab is decided. */
	private boolean checkEnd() {
		HwConfig cfg = this.cfg();
		if (this.victimPoints >= cfg.victimPointsToEscape) {
			this.escape();
			return true;
		}
		if (this.shapePoints >= this.shapeNeeded()) {
			this.startFinisher();
			return true;
		}
		return false;
	}

	private void escape() {
		HwConfig cfg = this.cfg();
		this.state.recoverTicks = cfg.escapeShapeRecoverTicks;
		this.state.grabCooldown = HwConfig.at(cfg.grabCooldownByTier, this.state.tier());
		Vec3 away = this.victim.position().subtract(this.shape.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0E-4 ? this.shape.getLookAngle().multiply(1, 0, 1) : away;
		away = away.normalize();
		this.victim.setDeltaMovement(away.x * 0.9, 0.35, away.z * 0.9);
		this.victim.hurtMarked = true;
		this.level().playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.ESCAPE, SoundSource.PLAYERS, 1f, 1f);
		this.shape.sendOverlayMessage(Component.translatable("halloween.msg.escaped"));
		if (this.victim instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.you_escaped"));
		}
		this.end();
	}

	// ---------------------------------------------------------------- finisher

	private boolean windowAvailable() {
		return this.victimState.level() >= this.cfg().windowFinisherStalkLevel && this.findWindow() != null;
	}

	private void startFinisher() {
		this.finishing = true;
		this.finisherTick = 0;
		this.bloody = this.state.level() >= this.cfg().bloodyExecutionLevel;
		if (this.victimState.level() >= this.cfg().windowFinisherStalkLevel) {
			this.windowPos = this.findWindow();
		}
		HwNet.Qte off = HwNet.Qte.INACTIVE;
		if (this.victim instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, off);
		}
		ServerPlayNetworking.send(this.shape, off);
	}

	private BlockPos findWindow() {
		int r = this.cfg().windowSearchRadius;
		BlockPos center = this.shape.blockPosition();
		Vec3 look = this.shape.getLookAngle().multiply(1, 0, 1).normalize();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, 0, -r), center.offset(r, 2, r))) {
			if (Sight.isGlass(this.level().getBlockState(pos))) {
				// Only windows roughly in front of the Shape: he throws the victim forward.
				Vec3 dir = Vec3.atCenterOf(pos).subtract(this.shape.position()).multiply(1, 0, 1);
				if (dir.lengthSqr() < 1.0E-4 || look.dot(dir.normalize()) < 0.5) {
					continue;
				}
				double d = pos.distToCenterSqr(this.shape.position());
				if (d < bestDist) {
					bestDist = d;
					best = pos.immutable();
				}
			}
		}
		return best;
	}

	private void tickFinisher() {
		this.finisherTick++;
		if (this.windowPos != null) {
			if (this.finisherTick == 10) {
				this.stabEffects();
			} else if (this.finisherTick == 18) {
				this.smashWindow();
			} else if (this.finisherTick >= 26) {
				this.done = true;
				MatchManager.kill(this.shape, this.state, this.victim, MatchManager.KillKind.WINDOW, this.bloody);
			}
			return;
		}
		int[] stabs = this.bloody ? new int[]{6, 12, 18, 24, 30, 36} : new int[]{10, 20, 30};
		for (int t : stabs) {
			if (this.finisherTick == t) {
				this.stabEffects();
			}
		}
		if (this.finisherTick >= this.cfg().finisherTicks) {
			this.done = true;
			MatchManager.kill(this.shape, this.state, this.victim, MatchManager.KillKind.EXECUTION, this.bloody);
		}
	}

	private void stabEffects() {
		ServerLevel level = this.level();
		level.playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 1.1f, 0.9f + this.random.nextFloat() * 0.2f);
		MatchManager.bleed(level, this.victim, this.bloody ? 30 : 18);
	}

	private void smashWindow() {
		// The glass shatters but shards stay in the frame (see Windows).
		Windows.breakColumn(this.level(), this.windowPos);
		Vec3 window = Vec3.atBottomCenterOf(this.windowPos);
		Vec3 dir = window.subtract(this.shape.position()).multiply(1, 0, 1);
		dir = dir.lengthSqr() < 1.0E-4 ? this.shape.getLookAngle().multiply(1, 0, 1) : dir;
		Vec3 target = window.add(dir.normalize().scale(1.3));
		double y = Math.min(window.y, this.shape.getY());
		this.placeVictim(target.x, y, target.z, this.victim.getYRot());
		this.victim.setDeltaMovement(dir.normalize().scale(0.4).add(0, 0.2, 0));
		this.victim.hurtMarked = true;
	}

	// ---------------------------------------------------------------- tick

	void tick() {
		if (this.done) {
			return;
		}
		if (this.victim.isRemoved() || !this.victim.isAlive() || this.shape.isRemoved() || !MatchManager.isVictim(this.victim)) {
			this.end();
			return;
		}
		if (!(this.finishing && this.windowPos != null && this.finisherTick >= 18)) {
			this.pinVictim();
		}
		if (this.finishing) {
			this.tickFinisher();
			return;
		}
		HwConfig cfg = this.cfg();
		long now = this.level().getGameTime();
		long victimElapsed = now - this.victimTrack.roundStart;
		if (this.victimTrack.open) {
			if ((!(this.victim instanceof ServerPlayer) || this.victim instanceof BotPlayer) && victimElapsed >= this.victimTrack.simPressTick) {
				this.resolveVictim(this.victimTrack.simSuccess);
			} else if (victimElapsed >= cfg.victimRoundTicks) {
				this.resolveVictim(false);
			}
		}
		if (!this.finishing && !this.done && this.shapeTrack.open && now - this.shapeTrack.roundStart >= cfg.shapeRoundTicks) {
			this.resolveShape(false);
		}
	}

	private void pinVictim() {
		float yaw = this.shape.getYRot() * Mth.DEG_TO_RAD;
		double x = this.shape.getX() - Mth.sin(yaw) * 0.9;
		double z = this.shape.getZ() + Mth.cos(yaw) * 0.9;
		// Face the Shape.
		float victimYaw = this.shape.getYRot() + 180f;
		if (this.victim.distanceToSqr(x, this.shape.getY(), z) > 0.04) {
			this.placeVictim(x, this.shape.getY(), z, victimYaw);
		}
		this.victim.setDeltaMovement(Vec3.ZERO);
	}

	private void placeVictim(double x, double y, double z, float yaw) {
		if (this.victim instanceof ServerPlayer player) {
			player.teleportTo(this.level(), x, y, z, Set.of(), yaw, 0f, false);
		} else {
			this.victim.teleportTo(x, y, z);
			this.victim.setYRot(yaw);
			this.victim.setYBodyRot(yaw);
			this.victim.setYHeadRot(yaw);
		}
	}

	/** Releases everything without a kill (escape, abort, reset). */
	void end() {
		if (this.state.grab == this) {
			this.state.grab = null;
		}
		this.done = true;
		this.victimState.grabbed = false;
		if (this.victim instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		}
		ServerPlayNetworking.send(this.shape, HwNet.Qte.INACTIVE);
	}
}
