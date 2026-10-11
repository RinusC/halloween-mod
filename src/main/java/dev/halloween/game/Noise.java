package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Survivors' footsteps give them away. Running is heard a little way off, running over a loud surface (gravel,
 * a pile of leaves, broken glass by a smashed window) much further, walking over one a little; crouching is
 * silent. Michael sees a burst on his screen where the noise came from. A loud surface also crunches out loud.
 */
public final class Noise {
	public enum Surface {
		GRAVEL(SoundEvents.GRAVEL_STEP), LEAVES(SoundEvents.LEAF_LITTER_STEP), GLASS(SoundEvents.GLASS_STEP);

		final SoundEvent sound;

		Surface(SoundEvent sound) {
			this.sound = sound;
		}
	}

	private static final class Track {
		double x;
		double z;
		int nextBurst;
		int nextCrunch;
	}

	private static final Map<UUID, Track> TRACKS = new HashMap<>();

	private Noise() {
	}

	public static void clear() {
		TRACKS.clear();
	}

	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			TRACKS.clear();
			return;
		}
		HwConfig cfg = HwConfig.get();
		int now = server.getTickCount();
		List<ServerPlayer> shapes = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (MatchManager.role(p) == Role.SHAPE && !p.isSpectator()) {
				shapes.add(p);
			}
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!MatchManager.role(p).survivor() || p.isSpectator() || Lockers.isInside(p) || Exits.isSeated(p)) {
				TRACKS.remove(p.getUUID());
				continue;
			}
			Track t = TRACKS.computeIfAbsent(p.getUUID(), k -> new Track());
			double moved = Math.hypot(p.getX() - t.x, p.getZ() - t.z);
			t.x = p.getX();
			t.z = p.getZ();
			if (moved < 0.05 || p.isCrouching()) {
				continue;
			}
			boolean running = p.isSprinting();
			Surface surface = surface((ServerLevel) p.level(), p.blockPosition());
			double quiet = Perks.has(p, Perks.Perk.QUIET) ? cfg.perkQuietNoiseFactor : 1.0;
			if (surface != null && now >= t.nextCrunch) {
				// Crunch: heard by anyone near, like any footstep but louder.
				t.nextCrunch = now + (running ? 6 : 10);
				p.level().playSound(null, p.getX(), p.getY(), p.getZ(), surface.sound, SoundSource.PLAYERS,
					(float) ((running ? 1.0 : 0.5) * quiet), 0.85f + p.getRandom().nextFloat() * 0.3f);
			}
			double range = running ? (surface != null ? cfg.noiseLoudRunRange : cfg.noiseRunRange) : (surface != null ? cfg.noiseLoudWalkRange : 0);
			range *= quiet;
			// The party's music drowns footsteps round it (see Events).
			if (range <= 0 || now < t.nextBurst || Events.muffled(p.position())) {
				continue;
			}
			t.nextBurst = now + cfg.noiseEveryTicks;
			HwNet.NoiseBurst burst = new HwNet.NoiseBurst(p.position(), surface != null);
			for (ServerPlayer shape : shapes) {
				if (shape.level() == p.level() && shape.distanceTo(p) <= range) {
					ServerPlayNetworking.send(shape, burst);
					AiStats.add(p, surface != null ? "noise.loud" : "noise.run");
				}
			}
		}
	}

	/** The loud surface under these feet, or null: gravel underfoot, a pile of leaves, broken glass at hand. */
	public static Surface surface(ServerLevel level, BlockPos feet) {
		BlockState at = level.getBlockState(feet);
		if (at.getBlock() instanceof LeafLitterBlock && at.getValue(BlockStateProperties.SEGMENT_AMOUNT) >= 2) {
			return Surface.LEAVES;
		}
		BlockState under = level.getBlockState(feet.below());
		if (under.is(Blocks.GRAVEL) || under.is(Blocks.SUSPICIOUS_GRAVEL)) {
			return Surface.GRAVEL;
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (Windows.isBrokenPane(level.getBlockState(feet.relative(d))) || Windows.isBrokenPane(level.getBlockState(feet.relative(d).above()))) {
				return Surface.GLASS;
			}
		}
		return Windows.isBrokenPane(at) ? Surface.GLASS : null;
	}
}
