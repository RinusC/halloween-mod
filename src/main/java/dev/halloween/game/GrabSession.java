package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
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
	/** Where the Shape last stood with room for the victim in front: he is held back there at walls. */
	private Vec3 lastGood;
	private int finisherTick;
	private BlockPos windowPos;
	private boolean bloody;
	private boolean done;
	/** Whether the Shape's HUD was last told a window is at hand (he may walk up to one mid-grab). */
	private boolean windowShown;
	/** When the victim next chokes and gasps for air. */
	private long nextChoke;
	/** The choke take playing now (stopped when the grab ends); -1: none. */
	private int choking = -1;
	/** The way out has been animated already (an escape plays its own moves). */
	private boolean animated;

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
		Voices.play(victim, Voices.Kind.SCREAM);
		this.nextChoke = this.level().getGameTime() + 14;
		anim(shape, HwNet.Anim.HOLD);
		anim(victim, HwNet.Anim.STRUGGLE);
		this.startVictimRound(0);
		this.startShapeRound(0);
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

	/** A new check for the victim, {@code delay} ticks from now (a short pause between checks). */
	private void startVictimRound(int delay) {
		HwConfig cfg = this.cfg();
		Track t = this.victimTrack;
		t.roundStart = this.level().getGameTime() + delay;
		t.zoneWidth = Math.max(0.08f, cfg.victimZoneWidth
			- HwConfig.at(cfg.victimZoneShrinkByTier, this.state.tier())
			- HwConfig.at(cfg.victimZoneShrinkByStalk, this.victimState.level())
			+ (Perks.has(this.victim, Perks.Perk.SLIPPERY) ? cfg.perkSlipperyZone : 0f));
		t.zoneStart = 0.1f + this.random.nextFloat() * (0.8f - t.zoneWidth);
		t.open = true;
		// Simulated victims (bots, townsfolk) find a narrower zone harder to hit, like a person does.
		float base = this.victim instanceof BotPlayer ? cfg.botQteSuccessChance : cfg.mannequinQteSuccessChance;
		float chance = base * Mth.clamp(t.zoneWidth / cfg.victimZoneWidth, 0.35f, 1f);
		if (Police.isOfficer(this.victim)) {
			// Officers keep their heads: they hit their checks this often however narrow the zone.
			chance = cfg.policeQteSuccessChance;
		}
		t.simSuccess = this.random.nextFloat() < chance;
		t.simPressTick = 8 + this.random.nextInt(Math.max(1, cfg.victimRoundTicks - 12));
	}

	private void startShapeRound(int delay) {
		HwConfig cfg = this.cfg();
		Track t = this.shapeTrack;
		t.roundStart = this.level().getGameTime() + delay;
		t.zoneWidth = Math.min(0.5f, cfg.shapeZoneWidth + HwConfig.at(cfg.shapeZoneGrowByStalk, this.victimState.level()));
		t.zoneStart = 0.2f + this.random.nextFloat() * (0.7f - t.zoneWidth);
		t.open = true;
	}

	private void sendState() {
		HwConfig cfg = this.cfg();
		boolean windowReady = this.windowAvailable();
		this.windowShown = windowReady;
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

	/** Would the Shape's press land right now? (For the AI simulation's Shape.) */
	public boolean shapeWouldHit() {
		if (this.finishing || !this.shapeTrack.open) {
			return false;
		}
		long elapsed = this.level().getGameTime() - this.shapeTrack.roundStart;
		if (elapsed < 3) {
			return false;
		}
		float needle = HwNet.needle(elapsed, this.cfg().shapeNeedlePeriodTicks);
		return needle >= this.shapeTrack.zoneStart && needle <= this.shapeTrack.zoneStart + this.shapeTrack.zoneWidth;
	}

	public LivingEntity victim() {
		return this.victim;
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
			this.startVictimRound(this.cfg().qteRoundGapTicks);
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
			this.startShapeRound(this.cfg().qteRoundGapTicks);
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

	/** Tells everyone around what this entity is doing (see client.ClientAnims). */
	public static void anim(net.minecraft.world.entity.Entity who, int kind) {
		if (!(who.level() instanceof ServerLevel level)) {
			return;
		}
		HwNet.Anim payload = new HwNet.Anim(who.getId(), kind);
		for (ServerPlayer p : level.players()) {
			if (!Bots.isBot(p) && p.distanceToSqr(who) < 160 * 160) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private void escape() {
		this.escape(false);
	}

	/** Out of his hands: torn free with a shove (he staggers), or with a knife in him (he clutches the wound). */
	private void escape(boolean stab) {
		this.animated = true;
		anim(this.victim, stab ? HwNet.Anim.STAB : HwNet.Anim.BREAK_FREE);
		anim(this.shape, stab ? HwNet.Anim.STABBED : HwNet.Anim.STAGGER);
		AiStats.add(this.victim, "grab.escaped");
		Match.chronicle(this.shape.level().getServer(), Component.translatable(stab ? "halloween.chronicle.stab_free" : "halloween.chronicle.break_free",
			this.victim.getDisplayName()).withStyle(ChatFormatting.YELLOW));
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

	/** The victim stabbed him (a knife, an ice pick, a lawn dart): he lets go at once. */
	void stabbed() {
		ServerLevel level = this.level();
		level.playSound(null, this.shape.getX(), this.shape.getEyeY(), this.shape.getZ(), HwSounds.STAB, SoundSource.PLAYERS, 1.2f, 1.3f);
		MatchManager.bleed(level, this.shape, 10);
		this.escape(true);
		this.shape.sendOverlayMessage(Component.translatable("halloween.msg.stabbed_shape").withStyle(net.minecraft.ChatFormatting.DARK_RED));
		if (this.victim instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.stabbed_free").withStyle(net.minecraft.ChatFormatting.GREEN));
		}
	}

	// ---------------------------------------------------------------- finisher

	private boolean windowAvailable() {
		return this.victimState.level() >= this.cfg().windowFinisherStalkLevel && this.findWindow() != null;
	}

	private void startFinisher() {
		this.finishing = true;
		Music.killing(this.level().getServer(), this.cfg().finisherTicks + 70);
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
				anim(this.shape, HwNet.Anim.NONE);
				anim(this.victim, HwNet.Anim.NONE);
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
			anim(this.shape, HwNet.Anim.NONE);
			anim(this.victim, HwNet.Anim.NONE);
			MatchManager.kill(this.shape, this.state, this.victim, MatchManager.KillKind.EXECUTION, this.bloody);
		}
	}

	private void stabEffects() {
		ServerLevel level = this.level();
		anim(this.shape, HwNet.Anim.KNIFE_DOWN);
		level.playSound(null, this.victim.getX(), this.victim.getY(), this.victim.getZ(), HwSounds.STAB, SoundSource.HOSTILE, 1.1f, 0.9f + this.random.nextFloat() * 0.2f);
		MatchManager.bleed(level, this.victim, this.bloody ? 30 : 18);
	}

	private void smashWindow() {
		// The glass shatters but shards stay in the frame (see Windows).
		Windows.breakColumn(this.level(), this.windowPos);
		anim(this.victim, HwNet.Anim.NONE);
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
		if (now >= this.nextChoke) {
			// Held by the throat: choking, gasping for air.
			// One whole take at a time; the next (a different one) only once it is over.
			int take = this.random.nextInt(HwSounds.VOICE_CHOKES.length);
			if (take == this.choking) {
				take = (take + 1) % HwSounds.VOICE_CHOKES.length;
			}
			this.choking = take;
			float pitch = (Voices.female(this.victim) ? 1.05f : 0.97f) + this.random.nextFloat() * 0.03f;
			this.nextChoke = now + (long) (HwSounds.VOICE_CHOKE_TICKS[take] / pitch) + 4;
			this.level().playSound(null, this.victim.getX(), this.victim.getEyeY(), this.victim.getZ(), HwSounds.VOICE_CHOKES[take], SoundSource.VOICE, 1.2f, pitch);
			AiStats.add(this.victim, "voice.choke");
		}
		if (now % 5 == 0 && this.windowAvailable() != this.windowShown) {
			// He shuffled up to a window (or away from one): the throw is on offer (or not) right away.
			this.sendState();
		}
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

	/** Is the Shape still carrying out the struggle (he may shuffle along), not finishing the kill? */
	boolean struggling() {
		return !this.finishing && !this.done;
	}

	private void pinVictim() {
		float yaw = this.shape.getYRot() * Mth.DEG_TO_RAD;
		double x = this.shape.getX() - Mth.sin(yaw) * 0.9;
		double z = this.shape.getZ() + Mth.cos(yaw) * 0.9;
		// Face the Shape.
		float victimYaw = this.shape.getYRot() + 180f;
		if (this.victim.distanceToSqr(x, this.shape.getY(), z) > 0.04) {
			// He drags the victim along in front of him, but not through walls: there he can't go on.
			Vec3 move = new Vec3(x, this.shape.getY(), z).subtract(this.victim.position());
			boolean room = this.level().noCollision(this.victim, this.victim.getBoundingBox().move(move));
			if (room) {
				this.placeVictim(x, this.shape.getY(), z, victimYaw);
				this.lastGood = this.shape.position();
			} else if (this.lastGood != null && this.shape.position().distanceTo(this.lastGood) > 0.05) {
				this.shape.teleportTo(this.level(), this.lastGood.x, this.lastGood.y, this.lastGood.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0f, 0f, false);
			}
		} else {
			this.lastGood = this.shape.position();
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
	/** Out of his hands (or dead): the choking breaks off. */
	private void stopChoking() {
		if (this.choking < 0) {
			return;
		}
		net.minecraft.network.protocol.game.ClientboundStopSoundPacket stop = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
			HwSounds.VOICE_CHOKES[this.choking].location(), SoundSource.VOICE);
		this.choking = -1;
		for (ServerPlayer p : this.level().getServer().getPlayerList().getPlayers()) {
			if (p.level() == this.level() && p.distanceToSqr(this.victim) < 64 * 64) {
				p.connection.send(stop);
			}
		}
	}

	void end() {
		if (!this.animated) {
			anim(this.shape, HwNet.Anim.NONE);
			anim(this.victim, HwNet.Anim.NONE);
			this.animated = true;
		}
		if (this.state.grab == this) {
			this.state.grab = null;
		}
		this.done = true;
		this.victimState.grabbed = false;
		this.stopChoking();
		if (this.victim instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, HwNet.Qte.INACTIVE);
		}
		ServerPlayNetworking.send(this.shape, HwNet.Qte.INACTIVE);
	}
}
