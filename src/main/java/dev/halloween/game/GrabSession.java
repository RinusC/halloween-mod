package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Shape holding a victim. The victim fights back with a timing-bar QTE; enough hits and they
 * break free, too many misses and the Shape performs a finisher (through a window if the victim
 * is fully stalked and there's glass nearby).
 */
public final class GrabSession {
	private final ServerPlayer shape;
	private final ShapeState state;
	final LivingEntity victim;
	private final VictimState victimState;
	private final RandomSource random;

	private int successes;
	private int misses;
	private long roundStart;
	private float zoneStart;
	private float zoneWidth;
	private boolean roundOpen;
	private int mannequinPressTick;
	private boolean mannequinSucceeds;

	private boolean finishing;
	private int finisherTick;
	private BlockPos windowPos;
	private boolean done;

	GrabSession(ServerPlayer shape, ShapeState state, LivingEntity victim, VictimState victimState) {
		this.shape = shape;
		this.state = state;
		this.victim = victim;
		this.victimState = victimState;
		this.random = shape.getRandom();
		victimState.grabbed = true;
		this.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), HwSounds.GRAB, SoundSource.HOSTILE, 1.2f, 1f);
		this.startRound();
	}

	private ServerLevel level() {
		return this.shape.level();
	}

	private HwConfig cfg() {
		return HwConfig.get();
	}

	boolean isDone() {
		return this.done;
	}

	// ---------------------------------------------------------------- QTE

	private void startRound() {
		HwConfig cfg = this.cfg();
		this.roundStart = this.level().getGameTime();
		this.zoneWidth = Math.max(0.08f, cfg.qteZoneWidth - HwConfig.tierValue(cfg.qteZoneShrinkPerTier, this.state.tier));
		this.zoneStart = 0.1f + this.random.nextFloat() * (0.8f - this.zoneWidth);
		this.roundOpen = true;
		this.mannequinSucceeds = this.random.nextFloat() < cfg.mannequinQteSuccessChance;
		this.mannequinPressTick = 8 + this.random.nextInt(Math.max(1, cfg.qteRoundTicks - 12));
		this.sendQte();
	}

	private void sendQte() {
		if (this.victim instanceof ServerPlayer player) {
			HwConfig cfg = this.cfg();
			ServerPlayNetworking.send(player, new HwNet.Qte(
				true, this.zoneStart, this.zoneWidth, cfg.qteCursorPeriodTicks, this.roundStart, cfg.qteRoundTicks,
				this.successes, cfg.qteSuccessesToEscape, this.misses, cfg.qteMaxMisses
			));
		}
	}

	/** Victim pressed the QTE key. */
	void press() {
		if (this.finishing || !this.roundOpen) {
			return;
		}
		long elapsed = this.level().getGameTime() - this.roundStart;
		if (elapsed < 3) {
			return;
		}
		float cursor = HwNet.qteCursor(elapsed, this.cfg().qteCursorPeriodTicks);
		this.resolveRound(cursor >= this.zoneStart && cursor <= this.zoneStart + this.zoneWidth);
	}

	private void resolveRound(boolean hit) {
		this.roundOpen = false;
		HwConfig cfg = this.cfg();
		if (hit) {
			this.successes++;
		} else {
			this.misses++;
		}
		this.level().playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(),
			hit ? HwSounds.QTE_HIT : HwSounds.QTE_MISS, SoundSource.PLAYERS, 1f, hit ? 1.2f : 0.7f);
		if (this.successes >= cfg.qteSuccessesToEscape) {
			this.escape();
		} else if (this.misses >= cfg.qteMaxMisses) {
			this.startFinisher();
		} else {
			this.startRound();
		}
	}

	private void escape() {
		HwConfig cfg = this.cfg();
		this.state.recoverTicks = cfg.escapeShapeRecoverTicks;
		this.state.grabCooldown = HwConfig.tierValue(cfg.grabCooldownTicks, this.state.tier);
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

	private void startFinisher() {
		this.finishing = true;
		this.finisherTick = 0;
		if (this.victimState.stalk >= this.cfg().windowFinisherStalkRequired) {
			this.windowPos = this.findWindow();
		}
		if (this.victim instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		}
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
				MatchManager.kill(this.shape, this.state, this.victim, true);
			}
			return;
		}
		if (this.finisherTick == 10 || this.finisherTick == 20 || this.finisherTick == 30) {
			this.stabEffects();
		}
		if (this.finisherTick >= this.cfg().finisherTicks) {
			this.done = true;
			MatchManager.kill(this.shape, this.state, this.victim, false);
		}
	}

	private void stabEffects() {
		ServerLevel level = this.level();
		level.playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 1.1f, 0.9f + this.random.nextFloat() * 0.2f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
			this.victim.getX(), this.victim.getY() + this.victim.getBbHeight() * 0.6, this.victim.getZ(), 18, 0.2, 0.25, 0.2, 0.1);
	}

	private void smashWindow() {
		ServerLevel level = this.level();
		for (int dy = -2; dy <= 2; dy++) {
			BlockPos pos = this.windowPos.above(dy);
			if (Sight.isGlass(level.getBlockState(pos))) {
				level.destroyBlock(pos, false);
			}
		}
		level.playSound(null, this.windowPos, HwSounds.WINDOW_SMASH, SoundSource.BLOCKS, 1.5f, 0.9f);
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
		long elapsed = this.level().getGameTime() - this.roundStart;
		if (!(this.victim instanceof ServerPlayer) && this.roundOpen && elapsed >= this.mannequinPressTick) {
			this.resolveRound(this.mannequinSucceeds);
		} else if (this.roundOpen && elapsed >= this.cfg().qteRoundTicks) {
			this.resolveRound(false);
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
	}
}
