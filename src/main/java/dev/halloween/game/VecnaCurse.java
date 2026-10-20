package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Vecna's curse: it creeps over every survivor at once, the faster the farther they are from him (the farthest
 * reaches the next stage in two minutes, the nearest in four, the rest in between by how far they are). Each stage
 * keeps what the ones before brought:
 * <ol>
 * <li>a grandfather clock striking, for them alone, every thirty seconds;</li>
 * <li>Vecna seen where he is not (client.VecnaClient's phantoms);</li>
 * <li>panic that never ebbs;</li>
 * <li>twenty seconds to get to a telephone booth, or he takes them: lifted off their feet, their bones breaking.</li>
 * </ol>
 * A call from a booth ({@link Booths}) lifts it all. Every stage also stalks them for him, slowly.
 */
public final class VecnaCurse {
	private static final class Curse {
		int stage;
		/** Towards the next stage, 0..1. */
		float progress;
		/** Stage 4: ticks left to reach a booth; then the death (ticks of it left, -1 not yet). */
		int deathLeft;
		int dying = -1;
		int nextChime;
	}

	private static final Map<UUID, Curse> CURSES = new HashMap<>();
	private static final int DYING_TICKS = 50;

	private VecnaCurse() {
	}

	public static int stage(ServerPlayer p) {
		Curse c = CURSES.get(p.getUUID());
		return c == null ? 0 : c.stage;
	}

	/** A booth's call: the curse gone, from the start again. */
	public static void lift(ServerPlayer p) {
		Curse c = CURSES.get(p.getUUID());
		if (c == null || c.dying >= 0) {
			return;
		}
		boolean wasCursed = c.stage > 0;
		CURSES.remove(p.getUUID());
		if (wasCursed) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.curse.lifted").withStyle(ChatFormatting.AQUA));
			private_(p, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 0.6f);
		}
		send(p, null);
	}

	/** One stage more at once (the rift opening). */
	public static void bump(ServerPlayer p, int now) {
		Curse c = CURSES.computeIfAbsent(p.getUUID(), id -> new Curse());
		if (c.stage < 4) {
			stageUp(p, c, now);
		}
	}

	public static void tick(MinecraftServer server) {
		if (!Vecna.active() || !Match.running()) {
			if (!CURSES.isEmpty()) {
				CURSES.clear();
			}
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		ServerPlayer vecna = Senses.shape(level);
		ShapeState st = vecna == null ? null : MatchManager.shapeState(vecna);
		HwConfig cfg = HwConfig.get();
		List<ServerPlayer> cursed = new ArrayList<>();
		double near = Double.MAX_VALUE;
		double far = 0;
		for (ServerPlayer s : level.players()) {
			if (MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive()) {
				cursed.add(s);
				if (vecna != null) {
					double d = s.distanceTo(vecna);
					near = Math.min(near, d);
					far = Math.max(far, d);
				}
			}
		}
		CURSES.keySet().removeIf(id -> cursed.stream().noneMatch(s -> s.getUUID().equals(id)));
		for (ServerPlayer s : cursed) {
			Curse c = CURSES.computeIfAbsent(s.getUUID(), id -> new Curse());
			if (c.dying >= 0) {
				tickDying(s, c, vecna, st);
				continue;
			}
			if (vecna != null && !Match.frozen()) {
				if (c.stage < 4) {
					// Two minutes a stage for the farthest, four for the nearest; alone (or all as far), in between.
					double k = far - near < 1 ? 0.5 : (s.distanceTo(vecna) - near) / (far - near);
					double seconds = cfg.vecnaCurseNearSeconds + (cfg.vecnaCurseFarSeconds - cfg.vecnaCurseNearSeconds) * k;
					c.progress += (float) (1 / (seconds * 20));
					if (c.progress >= 1) {
						stageUp(s, c, now);
					}
				} else if (--c.deathLeft <= 0) {
					startDying(s, c);
					continue;
				}
				if (c.stage > 0 && st != null) {
					MatchManager.addStalk(vecna, st, s, cfg.vecnaCurseStalkPerStage * c.stage / 20f);
				}
			}
			effects(s, c, now);
			if (now % 10 == 0) {
				send(s, c);
			}
		}
	}

	private static void stageUp(ServerPlayer p, Curse c, int now) {
		c.stage++;
		c.progress = 0;
		AiStats.add(p, "vecna.curse." + c.stage);
		if (c.stage == 1) {
			c.nextChime = now;
		}
		if (c.stage == 4) {
			c.deathLeft = HwConfig.get().vecnaCurseLastTicks;
			p.sendOverlayMessage(Component.translatable("halloween.msg.curse.last").withStyle(ChatFormatting.DARK_RED));
			private_(p, SoundEvents.ELDER_GUARDIAN_CURSE, 1f, 0.5f);
		} else {
			p.sendOverlayMessage(Component.translatable("halloween.msg.curse.stage." + c.stage).withStyle(ChatFormatting.RED));
		}
		send(p, c);
	}

	/** What each stage brings (and keeps bringing). */
	private static void effects(ServerPlayer p, Curse c, int now) {
		if (c.stage >= 1 && now >= c.nextChime) {
			// The grandfather clock: a deep bell and its hum, for them alone.
			c.nextChime = now + HwConfig.get().vecnaChimeTicks;
			private_(p, SoundEvents.BELL_BLOCK, 1.2f, 0.5f);
			private_(p, SoundEvents.BELL_RESONATE, 0.8f, 0.5f);
		}
		if (c.stage >= 3) {
			Panic.set(p, 1f);
		}
		if (c.stage == 4 && c.deathLeft % 20 == 0) {
			// Every second of the last twenty: a tick of the clock.
			private_(p, SoundEvents.NOTE_BLOCK_HAT.value(), 1f, 0.5f);
		}
	}

	/** Too late: lifted off their feet, arms flung out, their bones snapping one after another. */
	private static void startDying(ServerPlayer p, Curse c) {
		c.dying = DYING_TICKS;
		if (Lockers.isInside(p)) {
			Lockers.leave(p, false);
		}
		p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, DYING_TICKS, 0, true, false));
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.5f, 0.5f);
		AiStats.add(p, "vecna.curse.death");
	}

	private static void tickDying(ServerPlayer p, Curse c, ServerPlayer vecna, ShapeState st) {
		ServerLevel level = p.level();
		if (c.dying % 8 == 0) {
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SKELETON_HURT, SoundSource.HOSTILE, 1.4f, 0.5f + p.getRandom().nextFloat() * 0.3f);
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 0.5f, 1.6f);
		}
		if (--c.dying > 0) {
			return;
		}
		CURSES.remove(p.getUUID());
		p.removeEffect(MobEffects.LEVITATION);
		if (vecna != null && st != null) {
			MatchManager.kill(vecna, st, p, MatchManager.KillKind.STRIKE, false);
		} else {
			MatchManager.eliminate(p);
		}
	}

	private static void private_(ServerPlayer p, SoundEvent s, float volume, float pitch) {
		p.connection.send(new ClientboundSoundPacket(Holder.direct(s), SoundSource.MASTER, p.getX(), p.getEyeY(), p.getZ(), volume, pitch, p.getRandom().nextLong()));
	}

	private static void private_(ServerPlayer p, Holder<SoundEvent> s, float volume, float pitch) {
		p.connection.send(new ClientboundSoundPacket(s, SoundSource.MASTER, p.getX(), p.getEyeY(), p.getZ(), volume, pitch, p.getRandom().nextLong()));
	}

	/** Their screen: the stage, how far to the next, the time left at the last; the booths. */
	private static void send(ServerPlayer p, Curse c) {
		int now = p.level().getServer().getTickCount();
		List<BlockPos> booths = new ArrayList<>();
		int resting = 0;
		for (Booths.Booth b : Booths.all()) {
			if (b.resting(now)) {
				resting |= 1 << booths.size();
			}
			booths.add(b.phone);
		}
		ServerPlayNetworking.send(p, new HwNet.CurseHud(c == null ? 0 : c.stage, c == null ? 0 : c.progress,
			c == null || c.stage < 4 ? 0 : Math.max(0, c.deathLeft), booths, resting));
	}

	public static void clear(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (CURSES.containsKey(p.getUUID())) {
				ServerPlayNetworking.send(p, HwNet.CurseHud.NONE);
			}
		}
		CURSES.clear();
	}

	/** For tests: this survivor at this stage now. */
	public static void testSet(ServerPlayer p, int stage) {
		Curse c = CURSES.computeIfAbsent(p.getUUID(), id -> new Curse());
		c.stage = Math.max(0, stage - 1);
		c.progress = 0;
		stageUp(p, c, p.level().getServer().getTickCount());
	}
}
