package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna's ritual: kills. First {@code vecnaRitualKills} of anyone at all by his own hand (survivors, bots,
 * townsfolk, officers), then the cursed survivor ({@link VecnaCurse}): where they die the rift tears open and the
 * whole town is the Upside Down until the match ends: a red murk with lightning in it for all (client.VecnaClient),
 * burning cracks torn out from there along the streets ({@link VecnaCracks}), their body risen over the burning pit.
 * Killing the cursed one sooner counts as one of the others, and the curse passes on (a stage lighter).
 * The rift makes him stronger (a tenth faster, a longer, wider, harder Grip, more and quicker Eyes, his Anchor to
 * anyone on a short rest).
 */
public final class VecnaRitual {
	private static int kills;
	private static Vec3 pit;
	private static boolean rift;
	private static int riftAt;
	private static Mannequin risen;
	/** How high over the pit the body was when the rift opened (it goes on up from there). */
	private static double risenFrom;

	private VecnaRitual() {
	}

	/** The rift is open: the town is the Upside Down. */
	public static boolean rift() {
		return Vecna.active() && rift;
	}

	/** Where the rift opened (null: not yet). */
	public static Vec3 pit() {
		return pit;
	}

	/** Kills so far towards the ritual. */
	public static int kills() {
		return kills;
	}

	/** Vecna killed someone (from MatchManager.kill, their body already lying at {@code at}). */
	static void killed(ServerPlayer v, LivingEntity victim, Vec3 at) {
		if (!Vecna.active() || rift) {
			return;
		}
		ServerLevel level = v.level();
		int needed = HwConfig.get().vecnaRitualKills;
		boolean wasCursed = VecnaCurse.cursed(victim);
		if (wasCursed) {
			open(level, v, VecnaDeath.floor(level, at));
			return;
		}
		if (wasCursed && victim instanceof ServerPlayer p) {
			VecnaCurse.killed(p);
		}
		kills++;
		AiStats.add(v, "vecna.ritual.kill");
		if (kills < needed) {
			v.sendOverlayMessage(Component.translatable("halloween.msg.ritual.count", kills, needed).withStyle(ChatFormatting.DARK_RED));
		} else if (kills == needed) {
			// Enough: now the cursed one.
			v.sendOverlayMessage(Component.translatable("halloween.msg.ritual.ready").withStyle(ChatFormatting.DARK_RED));
			v.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(HwSounds.VECNA_ALTAR_KINDLE),
				SoundSource.MASTER, v.getX(), v.getEyeY(), v.getZ(), 1f, 1f, v.getRandom().nextLong()));
		}
		sync(level.getServer());
	}

	// ---------------------------------------------------------------- the rift

	/** The cursed one killed last: the rift tears open where they fell, and the town goes under. */
	private static void open(ServerLevel level, ServerPlayer v, Vec3 at) {
		rift = true;
		pit = at;
		int now = level.getServer().getTickCount();
		riftAt = now;
		VecnaCurse.end(level.getServer());
		// Their body rises over the pit, upright, hanging slack, as his victims do.
		risen = null;
		for (Mannequin m : level.getEntitiesOfClass(Mannequin.class, new AABB(at, at).inflate(1.5, 4, 1.5),
			m -> m.entityTags().contains(MatchManager.CORPSE_TAG))) {
			if (risen == null || m.distanceToSqr(at) < risen.distanceToSqr(at)) {
				risen = m;
			}
		}
		if (risen != null) {
			risenFrom = risen.getY() - at.y;
			risen.setNoGravity(true);
			risen.setPose(net.minecraft.world.entity.Pose.STANDING);
			GrabSession.anim(risen, HwNet.Anim.LIMP);
		}
		MinecraftServer server = level.getServer();
		// The ground tears open: burning cracks out from there along the streets, over the whole town.
		VecnaCracks.open(level, at, Loot.radius() + 40, HwConfig.get().vecnaCrackSpreadTicks);
		level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_RIFT_OPEN, SoundSource.HOSTILE, 4f, 1f);
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) != Role.NONE) {
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(HwSounds.VECNA_THUNDER), SoundSource.MASTER,
					p.getX(), p.getEyeY(), p.getZ(), 1f, 0.9f, p.getRandom().nextLong()));
			}
		}
		Flicker.start(level, at, 200);
		Match.announce(server, Component.translatable("halloween.match.rift").withStyle(ChatFormatting.DARK_RED));
		AiStats.add(v, "vecna.rift");
		Match.markDirty();
		sync(server);
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!Vecna.active() || !Match.running()) {
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		Flicker.tick(level, now);
		VecnaCracks.tick(level, now);
		if (rift) {
			if (risen != null && !risen.isRemoved()) {
				// Their body risen over the rift, turning slowly.
				double up = Mth.lerp(Math.min(1, (now - riftAt) / 60.0), risenFrom, 2.2) + Math.sin(now * 0.05) * 0.15;
				risen.teleportTo(pit.x, pit.y + up, pit.z);
				risen.setYRot((now - riftAt) * 0.6f);
				risen.setYBodyRot((now - riftAt) * 0.6f);
			}
			if (now % 2 == 0) {
				// The burning pit: embers licking up out of it, smoke rising high.
				level.sendParticles(HwParticles.VECNA_EMBER, pit.x, pit.y + 0.3, pit.z, 5, 1.8, 0.1, 1.8, 0.03);
				level.sendParticles(HwParticles.VECNA_SMOKE, pit.x, pit.y + 0.6, pit.z, 3, 1.5, 0.2, 1.5, 0.04);
				level.sendParticles(HwParticles.VECNA_MIST, pit.x, pit.y + 1.5, pit.z, 1, 2, 1.2, 2, 0.005);
			}
			if (now % 3 == 0) {
				level.sendParticles(HwParticles.VECNA_ASH, pit.x, pit.y + 2, pit.z, 4, 2.5, 1.5, 2.5, 0.02);
			}
		}
		if (now % 20 == 0) {
			sync(server);
		}
	}

	/** Everyone learns whether the rift is open; only he how far the ritual has come. */
	private static void sync(MinecraftServer server) {
		int needed = HwConfig.get().vecnaRitualKills;
		HwNet.VecnaRitual all = new HwNet.VecnaRitual(0, needed, rift);
		HwNet.VecnaRitual his = new HwNet.VecnaRitual(Math.min(kills, needed), needed, rift);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, MatchManager.role(p) == Role.SHAPE ? his : all);
		}
	}

	/** For tests: Vecna kills this one now (as with his claw, a body left and all). */
	public static void testKill(ServerPlayer v, LivingEntity victim) {
		MatchManager.kill(v, MatchManager.shapeState(v), victim, MatchManager.KillKind.STRIKE, false);
	}

	public static void clear(MinecraftServer server) {
		Flicker.clear(server.overworld());
		VecnaCracks.clear(server.overworld());
		kills = 0;
		pit = null;
		rift = false;
		risen = null;
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=hw_ritual_rift]");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, HwNet.VecnaRitual.NONE);
		}
	}
}
