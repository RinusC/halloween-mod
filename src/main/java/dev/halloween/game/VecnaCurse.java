package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * Vecna's curse: on one survivor only, chosen at random when the match starts and kept secret (they know, and he
 * does; nobody else). It deepens by itself, whatever he does: a stage every few minutes ({@code vecnaCurseStageSeconds}),
 * and each stage keeps what the ones before brought:
 * <ol>
 * <li>a grandfather clock striking, for them alone, every thirty seconds;</li>
 * <li>Vecna seen where he is not (client.VecnaClient's phantoms);</li>
 * <li>panic that never ebbs;</li>
 * <li>broken: he sees them through any wall; their world swims and their hands shake (client), the reaction checks are
 * narrower ({@link #checkFactor}).</li>
 * </ol>
 * It never kills, and none of the ways out will take them (see Exits). They are the last of his ritual (see
 * {@link VecnaRitual}): if he kills them before the others, the curse passes to another survivor a stage lighter.
 */
public final class VecnaCurse {
	private static UUID cursed;
	/** Chosen this match already (so nobody new is cursed once there is nobody left to pass it to). */
	private static boolean chosen;
	/** The rift is open: the curse is spent for the rest of the match. */
	private static boolean spent;
	private static int stage;
	/** Towards the next stage, 0..1. */
	private static float progress;
	private static int nextChime;

	private VecnaCurse() {
	}

	/** Is this the cursed survivor? */
	public static boolean cursed(Entity e) {
		return e != null && cursed != null && Vecna.active() && cursed.equals(e.getUUID());
	}

	/** The cursed one gets out after all: the last minutes, with no other survivor left alive. */
	public static boolean mayEscape(Entity e) {
		if (!cursed(e) || !(e instanceof ServerPlayer self) || Match.ticksLeft() > HwConfig.get().curseEscapeLastSeconds * 20) {
			return false;
		}
		for (ServerPlayer p : self.level().getServer().getPlayerList().getPlayers()) {
			if (p != self && MatchManager.role(p).survivor() && !p.isSpectator()) {
				return false;
			}
		}
		return true;
	}

	public static int stage(ServerPlayer p) {
		return cursed(p) ? stage : 0;
	}

	/** At the last stage: broken. */
	public static boolean broken(Entity e) {
		return cursed(e) && stage >= 4;
	}

	/** The reaction checks' zones for them times this (narrower when broken). */
	public static float checkFactor(Entity e) {
		return broken(e) ? HwConfig.get().vecnaCurseCheckFactor : 1f;
	}

	/** The cursed survivor, if there is one in the match now. */
	public static ServerPlayer cursedPlayer(MinecraftServer server) {
		ServerPlayer p = cursed == null ? null : server.getPlayerList().getPlayer(cursed);
		return p != null && alive(p) ? p : null;
	}

	private static boolean alive(ServerPlayer p) {
		return MatchManager.role(p).survivor() && !p.isSpectator() && p.isAlive() && !Exits.isSeated(p);
	}

	public static void tick(MinecraftServer server) {
		if (!Vecna.active() || !Match.running()) {
			if (cursed != null || chosen) {
				clear(server);
			}
			return;
		}
		if (spent) {
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		ServerPlayer p = cursed == null ? null : server.getPlayerList().getPlayer(cursed);
		if (p == null || !alive(p)) {
			// Not yet chosen, or gone some other way (left the game, shot dead): on to someone else.
			pass(server, p, chosen ? Math.max(1, stage - 1) : 1, now);
			return;
		}
		HwConfig cfg = HwConfig.get();
		if (stage < 4 && !Match.frozen()) {
			progress += 1f / (cfg.vecnaCurseStageSeconds * 20f);
			if (progress >= 1) {
				stageUp(p, now);
			}
		}
		ServerPlayer vecna = Senses.shape(level);
		ShapeState st = vecna == null ? null : MatchManager.shapeState(vecna);
		if (vecna != null && st != null && !Match.frozen()) {
			MatchManager.addStalk(vecna, st, p, cfg.vecnaCurseStalkPerStage * stage / 20f);
		}
		effects(p, now);
		if (now % 10 == 0) {
			send(p);
		}
	}

	/**
	 * The curse on someone new (the old bearer {@code from} gone: killed, left), at {@code atStage}; nobody left to
	 * take it: it is over.
	 */
	private static void pass(MinecraftServer server, ServerPlayer from, int atStage, int now) {
		if (from != null) {
			ServerPlayNetworking.send(from, HwNet.CurseHud.NONE);
		}
		List<ServerPlayer> left = new ArrayList<>();
		for (ServerPlayer s : server.getPlayerList().getPlayers()) {
			if (alive(s) && (from == null || !s.getUUID().equals(from.getUUID()))) {
				left.add(s);
			}
		}
		cursed = null;
		if (left.isEmpty()) {
			return;
		}
		ServerPlayer next = left.get(server.overworld().getRandom().nextInt(left.size()));
		boolean first = !chosen;
		chosen = true;
		cursed = next.getUUID();
		stage = Math.max(1, Math.min(4, atStage));
		progress = 0;
		nextChime = now;
		AiStats.add(next, first ? "vecna.cursed" : "vecna.cursed.passed");
		next.sendOverlayMessage(Component.translatable(first ? "halloween.msg.curse.chosen" : "halloween.msg.curse.passed").withStyle(ChatFormatting.DARK_RED));
		private_(next, HwSounds.VECNA_CURSE_LAST, 0.8f, 1f);
		ServerPlayer vecna = Senses.shape(server.overworld());
		if (vecna != null) {
			vecna.sendOverlayMessage(Component.translatable(first ? "halloween.msg.curse.vecna_chosen" : "halloween.msg.curse.vecna_passed",
				next.getDisplayName()).withStyle(ChatFormatting.DARK_RED));
		}
		if (stage >= 4) {
			next.sendOverlayMessage(Component.translatable("halloween.msg.curse.last").withStyle(ChatFormatting.DARK_RED));
		}
		send(next);
	}

	/** Vecna killed them: the curse goes on to another survivor, a stage lighter (see VecnaRitual). */
	static void killed(ServerPlayer p) {
		if (!cursed(p)) {
			return;
		}
		pass(p.level().getServer(), p, stage - 1, p.level().getServer().getTickCount());
	}

	/** The curse is spent (the rift is open): nobody bears it any more. */
	static void end(MinecraftServer server) {
		ServerPlayer p = cursed == null ? null : server.getPlayerList().getPlayer(cursed);
		if (p != null) {
			ServerPlayNetworking.send(p, HwNet.CurseHud.NONE);
		}
		cursed = null;
		chosen = true;
		spent = true;
	}

	private static void stageUp(ServerPlayer p, int now) {
		stage++;
		progress = 0;
		AiStats.add(p, "vecna.curse." + stage);
		if (stage == 4) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.curse.last").withStyle(ChatFormatting.DARK_RED));
			private_(p, HwSounds.VECNA_CURSE_LAST, 1f, 1f);
			ServerPlayer vecna = Senses.shape(p.level());
			if (vecna != null) {
				vecna.sendOverlayMessage(Component.translatable("halloween.msg.curse.vecna_broken", p.getDisplayName()).withStyle(ChatFormatting.DARK_RED));
			}
		} else {
			p.sendOverlayMessage(Component.translatable("halloween.msg.curse.stage." + stage).withStyle(ChatFormatting.RED));
		}
		send(p);
	}

	/** What each stage brings (and keeps bringing). */
	private static void effects(ServerPlayer p, int now) {
		if (now >= nextChime) {
			// The grandfather clock: a deep bell and its hum, for them alone.
			nextChime = now + HwConfig.get().vecnaChimeTicks;
			private_(p, HwSounds.VECNA_CLOCK_CHIME, 1f, 1f);
		}
		if (stage >= 3) {
			Panic.set(p, 1f);
		}
		if (stage >= 4 && now % 22 == 0) {
			// Broken: the clock ticking on in their head, at its own pace (a stroke a second or so).
			private_(p, now / 22 % 2 == 0 ? HwSounds.VECNA_CLOCK_TICK : HwSounds.VECNA_CLOCK_TOCK, 0.6f, 1f);
		}
	}

	private static void private_(ServerPlayer p, SoundEvent s, float volume, float pitch) {
		p.connection.send(new ClientboundSoundPacket(Holder.direct(s), SoundSource.MASTER, p.getX(), p.getEyeY(), p.getZ(), volume, pitch, p.getRandom().nextLong()));
	}

	/** Their screen: the stage and how far to the next. */
	private static void send(ServerPlayer p) {
		ServerPlayNetworking.send(p, new HwNet.CurseHud(stage, progress));
	}

	public static void clear(MinecraftServer server) {
		ServerPlayer p = cursed == null ? null : server.getPlayerList().getPlayer(cursed);
		if (p != null) {
			ServerPlayNetworking.send(p, HwNet.CurseHud.NONE);
		}
		cursed = null;
		chosen = false;
		spent = false;
		stage = 0;
		progress = 0;
	}

	/** For tests: this survivor cursed, at this stage now. */
	public static void testSet(ServerPlayer p, int at) {
		if (cursed != null && !cursed.equals(p.getUUID())) {
			ServerPlayer old = p.level().getServer().getPlayerList().getPlayer(cursed);
			if (old != null) {
				ServerPlayNetworking.send(old, HwNet.CurseHud.NONE);
			}
		}
		cursed = p.getUUID();
		chosen = true;
		stage = Math.max(1, at - 1);
		progress = 0;
		if (at > 1) {
			stageUp(p, p.level().getServer().getTickCount());
		} else {
			stage = 1;
			send(p);
		}
	}
}
