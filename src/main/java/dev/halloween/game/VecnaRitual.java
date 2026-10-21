package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwParticles;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vecna's ritual: any bodies will do. He carries them one at a time over his shoulder (the grab key on a body; again
 * to lay it down; a stun drops it). Where he lays the first is the altar, a ring of red glow everyone sees; with four
 * bodies in the ring the rift opens and the whole town is the Upside Down until the match ends: a red murk with
 * lightning in it for all (client.VecnaClient), burning cracks torn out from the altar along the streets ({@link VecnaCracks}), the bodies risen over the burning pit.
 * The rift makes him stronger (a tenth faster, a longer, wider, harder Grip, more and quicker Eyes, his Anchor to
 * anyone on a short rest) and puts every survivor's curse a stage on at once.
 */
public final class VecnaRitual {
	private static Mannequin carried;
	private static Vec3 altar;
	private static boolean rift;
	private static int riftAt;
	private static Entity riftDisplay;
	private static final List<Mannequin> RISEN = new ArrayList<>();

	private VecnaRitual() {
	}

	public static boolean carrying() {
		return Vecna.active() && carried != null;
	}

	/** The rift is open: the town is the Upside Down. */
	public static boolean rift() {
		return Vecna.active() && rift;
	}

	private static boolean isCorpse(Entity e) {
		return e instanceof Mannequin m && !m.isRemoved() && m.entityTags().contains(MatchManager.CORPSE_TAG) && !RISEN.contains(m);
	}

	/**
	 * The grab key: lay down the body he carries, or take up the one aimed at (or lying at his feet in front of him).
	 * False: nothing to do with bodies, the grab goes on as usual.
	 */
	static boolean useKey(ServerPlayer v, ShapeState st, int targetId) {
		if (carried != null) {
			lay(v);
			return true;
		}
		if (st.busy() || Vecna.rooted()) {
			return false;
		}
		Entity target = targetId >= 0 ? v.level().getEntity(targetId) : null;
		if (target != null && !isCorpse(target) && MatchManager.exposed(target)) {
			return false;
		}
		Mannequin body = target != null && isCorpse(target) && target.distanceTo(v) <= 3.5 ? (Mannequin) target : nearBody(v);
		if (body == null) {
			return false;
		}
		carried = body;
		v.level().playSound(null, body.getX(), body.getY(), body.getZ(), HwSounds.VECNA_BODY_LIFT, SoundSource.HOSTILE, 1f, 1f);
		AiStats.add(v, "vecna.carry");
		return true;
	}

	/** A body at his feet, in front of him. */
	private static Mannequin nearBody(ServerPlayer v) {
		Vec3 look = v.getViewVector(1f);
		Mannequin best = null;
		double bestD = 3.0;
		for (Mannequin m : v.level().getEntitiesOfClass(Mannequin.class, v.getBoundingBox().inflate(3), VecnaRitual::isCorpse)) {
			Vec3 to = m.position().subtract(v.position());
			double d = to.length();
			if (d < bestD && (d < 1.2 || new Vec3(to.x, 0, to.z).normalize().dot(new Vec3(look.x, 0, look.z).normalize()) > 0.3)) {
				best = m;
				bestD = d;
			}
		}
		return best;
	}

	/** Laid down before him on the ground; the first one makes the altar, the fourth in its ring opens the rift. */
	private static void lay(ServerPlayer v) {
		ServerLevel level = v.level();
		Mannequin body = carried;
		carried = null;
		if (body.isRemoved()) {
			return;
		}
		float yaw = v.getYRot();
		Vec3 front = v.position().add(-Mth.sin(yaw * Mth.DEG_TO_RAD) * 1.1, 0, Mth.cos(yaw * Mth.DEG_TO_RAD) * 1.1);
		Vec3 at = ground(level, front, v.position());
		Vec3 from = body.position();
		body.teleportTo(at.x, at.y, at.z);
		body.setYRot(yaw + 90);
		body.setYBodyRot(yaw + 90);
		Police.moveBody(from, at);
		level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_BODY_DROP, SoundSource.HOSTILE, 1f, 1f);
		HwConfig cfg = HwConfig.get();
		if (rift) {
			return;
		}
		if (altar == null) {
			altar = at;
			level.playSound(null, at.x, at.y, at.z, HwSounds.VECNA_ALTAR_KINDLE, SoundSource.HOSTILE, 1.5f, 1f);
			v.sendOverlayMessage(Component.translatable("halloween.msg.ritual.altar").withStyle(ChatFormatting.DARK_RED));
			AiStats.add(v, "vecna.altar");
		}
		int n = bodiesOnAltar(level).size();
		if (at.distanceTo(altar) <= cfg.vecnaAltarRadius) {
			v.sendOverlayMessage(Component.translatable("halloween.msg.ritual.count", n, cfg.vecnaRitualBodies).withStyle(ChatFormatting.DARK_RED));
		}
		if (n >= cfg.vecnaRitualBodies) {
			open(level, v);
		}
	}

	/** Where something laid at {@code want} comes to rest: the floor under it (not inside a wall: else at his own feet). */
	private static Vec3 ground(ServerLevel level, Vec3 want, Vec3 fallback) {
		BlockPos p = BlockPos.containing(want);
		for (int dy = 1; dy >= -3; dy--) {
			BlockPos c = p.above(dy);
			if (level.getBlockState(c).getCollisionShape(level, c).isEmpty() && !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty()) {
				return new Vec3(want.x, c.getY(), want.z);
			}
		}
		return fallback;
	}

	private static List<Mannequin> bodiesOnAltar(ServerLevel level) {
		if (altar == null) {
			return List.of();
		}
		double r = HwConfig.get().vecnaAltarRadius;
		return level.getEntitiesOfClass(Mannequin.class, new AABB(altar, altar).inflate(r, 3, r),
			m -> isCorpse(m) && m != carried && m.position().distanceTo(altar) <= r + 0.5);
	}

	// ---------------------------------------------------------------- the rift

	/** Four bodies in the ring: the rift tears open over the altar, and the town goes under. */
	private static void open(ServerLevel level, ServerPlayer v) {
		rift = true;
		int now = level.getServer().getTickCount();
		riftAt = now;
		RISEN.clear();
		RISEN.addAll(bodiesOnAltar(level).subList(0, HwConfig.get().vecnaRitualBodies));
		for (Mannequin m : RISEN) {
			m.setNoGravity(true);
			// Upright in the air, hanging slack, as his victims do.
			m.setPose(net.minecraft.world.entity.Pose.STANDING);
			GrabSession.anim(m, HwNet.Anim.LIMP);
		}
		MinecraftServer server = level.getServer();
		// The ground tears open: burning cracks out from the altar along the streets, over the whole town.
		VecnaCracks.open(level, altar, Loot.radius() + 40, HwConfig.get().vecnaCrackSpreadTicks);
		level.playSound(null, altar.x, altar.y, altar.z, HwSounds.VECNA_RIFT_OPEN, SoundSource.HOSTILE, 4f, 1f);
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) != Role.NONE) {
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(HwSounds.VECNA_THUNDER), SoundSource.MASTER,
					p.getX(), p.getEyeY(), p.getZ(), 1f, 0.9f, p.getRandom().nextLong()));
			}
			if (MatchManager.role(p).survivor() && !p.isSpectator() && p.isAlive()) {
				VecnaCurse.bump(p, now);
			}
		}
		Flicker.start(level, altar, 200);
		Match.announce(server, Component.translatable("halloween.match.rift").withStyle(ChatFormatting.DARK_RED));
		AiStats.add(v, "vecna.rift");
		Match.markDirty();
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!Vecna.active() || !Match.running()) {
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		ServerPlayer v = Senses.shape(level);
		ShapeState st = v == null ? null : MatchManager.shapeState(v);
		if (carried != null) {
			if (v == null || st == null || carried.isRemoved()) {
				carried = null;
			} else if (st.stunnedTicks > 0 || st.grab != null) {
				// Stunned (or busy with someone): the body falls.
				lay(v);
			} else {
				// Over his shoulder, across his back.
				float yaw = v.getYRot();
				Vec3 at = v.position().add(Mth.cos(yaw * Mth.DEG_TO_RAD) * 0.1, 1.45, Mth.sin(yaw * Mth.DEG_TO_RAD) * 0.1);
				carried.teleportTo(at.x, at.y, at.z);
				carried.setYRot(yaw + 90);
				carried.setYBodyRot(yaw + 90);
				carried.setYHeadRot(yaw + 90);
			}
		}
		Flicker.tick(level, now);
		VecnaCracks.tick(level, now);
		HwConfig cfg = HwConfig.get();
		if (altar != null && !rift && now % 4 == 0) {
			// The ring: red embers round it, a slow pulse at its heart.
			double r = cfg.vecnaAltarRadius;
			for (int i = 0; i < 12; i++) {
				double a = i / 12.0 * Math.PI * 2 + now * 0.02;
				level.sendParticles(HwParticles.VECNA_EMBER, altar.x + Math.cos(a) * r, altar.y + 0.15, altar.z + Math.sin(a) * r, 0, 0, 0.3, 0, 0.05);
			}
			level.sendParticles(HwParticles.VECNA_MIST, altar.x, altar.y + 0.5, altar.z, 1, 0.6, 0.2, 0.6, 0.005);
		}
		if (rift) {
			// The bodies risen round the rift, turning slowly.
			for (int i = 0; i < RISEN.size(); i++) {
				Mannequin m = RISEN.get(i);
				if (m.isRemoved()) {
					continue;
				}
				double a = i / (double) RISEN.size() * Math.PI * 2 + (now - riftAt) * 0.01;
				double up = Math.min(1, (now - riftAt) / 60.0) * 2.2 + Math.sin((now + i * 20) * 0.05) * 0.15;
				m.teleportTo(altar.x + Math.cos(a) * 2.6, altar.y + up, altar.z + Math.sin(a) * 2.6);
			}
			if (now % 2 == 0) {
				// The burning pit: flames licking up out of it, smoke and embers rising high.
				level.sendParticles(HwParticles.VECNA_EMBER, altar.x, altar.y + 0.3, altar.z, 5, 1.8, 0.1, 1.8, 0.03);
				level.sendParticles(HwParticles.VECNA_SMOKE, altar.x, altar.y + 0.6, altar.z, 3, 1.5, 0.2, 1.5, 0.04);
				level.sendParticles(HwParticles.VECNA_MIST, altar.x, altar.y + 1.5, altar.z, 1, 2, 1.2, 2, 0.005);
			}
			if (now % 3 == 0) {
				level.sendParticles(HwParticles.VECNA_ASH, altar.x, altar.y + 2, altar.z, 4, 2.5, 1.5, 2.5, 0.02);
			}
		}
		if (now % 10 == 0) {
			HwNet.VecnaRitual payload = new HwNet.VecnaRitual(altar != null, altar == null ? BlockPos.ZERO : BlockPos.containing(altar),
				altar == null ? 0 : rift ? cfg.vecnaRitualBodies : bodiesOnAltar(level).size(), cfg.vecnaRitualBodies, rift,
				carried != null && v != null ? v.getId() : -1);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	public static void clear(MinecraftServer server) {
		Flicker.clear(server.overworld());
		VecnaCracks.clear(server.overworld());
		carried = null;
		altar = null;
		rift = false;
		RISEN.clear();
		if (riftDisplay != null) {
			riftDisplay.discard();
			riftDisplay = null;
		}
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=hw_ritual_rift]");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, HwNet.VecnaRitual.NONE);
		}
	}

	/** For tests: the altar here with this many bodies already. */
	public static Vec3 altar() {
		return altar;
	}
}
