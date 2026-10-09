package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * How the Runt feeds on fear by really frightening people, not just by pressing a button at them:
 * <ul>
 * <li>Ambush: unseen by someone for a good while, then suddenly right there in front of them: a stinger only they
 * hear, a jolt of panic, a lot of fear.</li>
 * <li>The corner of the eye: while he flits about at the edge of someone's view, fear trickles in; looked at
 * straight, it stops (he has to slip away).</li>
 * <li>A face at the window: staring in through the glass from outside at someone in the house who sees him: fear
 * comes quickly, and they hear a soft tapping on the pane.</li>
 * <li>A giggle, a whisper: heard by everyone near as if from everywhere at once; fear for each one it reaches.</li>
 * <li>Knocking: on the door or a window of a house with people inside: all of them hear it.</li>
 * </ul>
 */
final class RuntFear {
	private record Pair(UUID runt, UUID survivor) {
	}

	/** When each survivor last had the Runt in view, and when the next ambush on them may come. */
	private static final Map<Pair, Integer> LAST_SEEN = new HashMap<>();
	private static final Map<Pair, Integer> AMBUSH_READY = new HashMap<>();
	private static final Map<Pair, Integer> TAP_AT = new HashMap<>();

	private RuntFear() {
	}

	static void clear() {
		LAST_SEEN.clear();
		AMBUSH_READY.clear();
		TAP_AT.clear();
	}

	/** A sound only {@code who} hears, from right where they are: no telling where it came from. */
	static void hear(ServerPlayer who, Holder<SoundEvent> sound, float volume, float pitch) {
		who.connection.send(new ClientboundSoundPacket(sound, SoundSource.HOSTILE, who.getX(), who.getEyeY(), who.getZ(), volume, pitch, who.getRandom().nextLong()));
	}

	static void hear(ServerPlayer who, SoundEvent sound, float volume, float pitch) {
		hear(who, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
	}

	/** Every five ticks: who sees him, and how. */
	static void tick(ServerPlayer runt, Runt.State s, int now) {
		if (s.outTicks > 0 || runt.isInvisible()) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = runt.level();
		Vec3 body = runt.getBoundingBox().getCenter();
		boolean outdoors = level.canSeeSky(runt.blockPosition().above());
		for (ServerPlayer v : level.players()) {
			if (!MatchManager.role(v).survivor() || v.isSpectator() || Lockers.isInside(v) || v.getUUID().equals(s.holding)) {
				continue;
			}
			Vec3 eye = v.getEyePosition();
			double dist = eye.distanceTo(body);
			if (dist > 24) {
				continue;
			}
			Pair key = new Pair(runt.getUUID(), v.getUUID());
			double angle = Sight.angleTo(eye, v.getViewVector(1f), body);
			boolean los = Sight.canSee(level, eye, body, v);
			if (los && angle <= 55) {
				int lastSeen = LAST_SEEN.getOrDefault(key, -100000);
				if (dist <= cfg.runtAmbushRange && now - lastSeen >= cfg.runtAmbushHiddenTicks && now >= AMBUSH_READY.getOrDefault(key, 0)) {
					AMBUSH_READY.put(key, now + cfg.runtAmbushCooldownTicks);
					ambush(runt, s, v);
				}
				LAST_SEEN.put(key, now);
			}
			// At the edge of their eye: a flicker they can't quite catch.
			if (los && dist <= 16 && angle >= 35 && angle <= 80) {
				Runt.gain(runt, s, cfg.runtEdgeFearPerSecond / 4f);
				Panic.add(v, 0.005f);
			}
			// A face at the window: him outside, them in the house, the glass between.
			if (los && outdoors && dist <= 12 && angle <= 60 && !level.canSeeSky(v.blockPosition().above())) {
				BlockPos glass = glassBetween(level, eye, runt.getEyePosition());
				if (glass != null) {
					Runt.gain(runt, s, cfg.runtWindowFearPerSecond / 4f);
					Panic.add(v, 0.012f);
					if (now >= TAP_AT.getOrDefault(key, 0)) {
						TAP_AT.put(key, now + 40);
						Vec3 g = Vec3.atCenterOf(glass);
						v.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.GLASS_HIT), SoundSource.HOSTILE,
							g.x, g.y, g.z, 0.6f, 1.6f, v.getRandom().nextLong()));
						AiStats.add(runt, "runt.fear.window");
					}
				}
			}
		}
	}

	/** The first window glass on the line from {@code a} to {@code b}, if any. */
	private static BlockPos glassBetween(ServerLevel level, Vec3 a, Vec3 b) {
		Vec3 step = b.subtract(a);
		int n = (int) Math.ceil(step.length() / 0.25);
		BlockPos last = null;
		for (int i = 1; i < n; i++) {
			BlockPos p = BlockPos.containing(a.add(step.scale(i / (double) n)));
			if (p.equals(last)) {
				continue;
			}
			last = p;
			if (Windows.isWindow(level.getBlockState(p))) {
				return p.immutable();
			}
		}
		return null;
	}

	private static void ambush(ServerPlayer runt, Runt.State s, ServerPlayer v) {
		HwConfig cfg = HwConfig.get();
		hear(v, HwSounds.STINGER, 1.2f, 1.1f);
		Panic.add(v, cfg.runtAmbushPanic);
		Runt.gain(runt, s, cfg.runtFearAmbush);
		Notices.send(runt, Component.translatable("halloween.runt.fear.ambush", v.getDisplayName()).withStyle(ChatFormatting.DARK_PURPLE));
		AiStats.add(runt, "runt.fear.ambush");
	}

	/** A giggle or a whisper, for everyone near to hear as if from inside their own head. True if anyone heard. */
	static boolean laugh(ServerPlayer runt, Runt.State s) {
		HwConfig cfg = HwConfig.get();
		if (s.laughCooldown > 0 || s.outTicks > 0) {
			return false;
		}
		s.laughCooldown = s.laughCooldownMax = cfg.runtLaughCooldownTicks;
		boolean giggle = runt.getRandom().nextBoolean();
		int heard = 0;
		for (ServerPlayer v : runt.level().players()) {
			if (MatchManager.role(v).survivor() && !v.isSpectator() && v.distanceTo(runt) <= cfg.runtLaughRange) {
				if (giggle) {
					hear(v, SoundEvents.WITCH_CELEBRATE, 0.9f, 1.85f);
				} else {
					hear(v, SoundEvents.AMBIENT_CAVE, 1f, 1.3f);
				}
				Panic.add(v, cfg.runtLaughPanic);
				heard++;
			}
		}
		if (giggle) {
			hear(runt, SoundEvents.WITCH_CELEBRATE, 0.6f, 1.85f);
		} else {
			hear(runt, SoundEvents.AMBIENT_CAVE, 0.6f, 1.3f);
		}
		if (heard > 0) {
			Runt.gain(runt, s, cfg.runtFearLaugh * Math.min(3, heard));
		}
		AiStats.add(runt, "runt.fear.laugh");
		return heard > 0;
	}

	/** Knocking on a door or a window: the people in the house hear it. True if anyone did. */
	static boolean knock(ServerPlayer runt, Runt.State s, BlockPos pos) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = runt.level();
		BlockState st = level.getBlockState(pos);
		boolean door = st.getBlock() instanceof DoorBlock;
		if (s.knockCooldown > 0 || s.busy() || !door && !Windows.isWindow(st) || Vec3.atCenterOf(pos).distanceTo(runt.getEyePosition()) > 3.5) {
			return false;
		}
		s.knockCooldown = cfg.runtKnockCooldownTicks;
		Vec3 c = Vec3.atCenterOf(pos);
		for (int i = 0; i < 3; i++) {
			level.playSound(null, c.x, c.y, c.z, door ? HwSounds.DOOR_HIT : SoundEvents.GLASS_HIT, SoundSource.HOSTILE, door ? 0.9f : 1.2f, door ? 1.25f : 1.4f);
		}
		int heard = 0;
		for (ServerPlayer v : level.players()) {
			if (MatchManager.role(v).survivor() && !v.isSpectator() && v.position().distanceTo(c) <= cfg.runtKnockRange) {
				Panic.add(v, cfg.runtKnockPanic);
				heard++;
			}
		}
		if (heard > 0) {
			Runt.gain(runt, s, cfg.runtFearKnock * Math.min(3, heard));
		}
		AiStats.add(runt, "runt.fear.knock");
		return heard > 0;
	}
}
