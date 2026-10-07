package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwParticles;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Blood, decided by the server: drops flying from a wound (the clients lay splats where they land), a pool
 * spreading under the dead, and a trail of drips behind a wounded survivor until they patch themselves up.
 * Pools and drips go to everyone on the map, so Michael can follow a trail from afar.
 */
public final class Blood {
	private static final Map<UUID, Vec3> LAST_DRIP = new HashMap<>();
	private static final Map<UUID, Integer> SINCE_DRIP = new HashMap<>();

	private Blood() {
	}

	/** Drops burst from the wound. */
	public static void splash(ServerLevel level, LivingEntity victim, int count) {
		level.sendParticles(HwParticles.BLOOD, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), count, 0.15, 0.2, 0.15, 0.22);
		AiStats.add(victim, "blood.splash");
	}

	/** A pool spreading under a body. */
	public static void pool(ServerLevel level, Vec3 at) {
		double floor = floor(level, at);
		if (!Double.isNaN(floor)) {
			level.sendParticles(HwParticles.BLOOD_POOL, true, true, at.x, floor, at.z, 0, 0.75 + level.getRandom().nextFloat() * 0.2, 0, 0, 1);
			AiStats.add("*total", "blood.pool");
		}
	}

	/** Wounded survivors leave drips: often while moving, now and then standing still. */
	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			if (!LAST_DRIP.isEmpty()) {
				LAST_DRIP.clear();
				SINCE_DRIP.clear();
			}
			return;
		}
		HwConfig cfg = HwConfig.get();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!MatchManager.role(player).survivor() || player.isSpectator() || Lockers.isInside(player)
				|| player.getHealth() >= player.getMaxHealth() * cfg.bloodTrailBelowHealth) {
				LAST_DRIP.remove(player.getUUID());
				continue;
			}
			int since = SINCE_DRIP.merge(player.getUUID(), 1, Integer::sum);
			Vec3 last = LAST_DRIP.get(player.getUUID());
			boolean moved = last == null || last.distanceToSqr(player.position()) > 1.0;
			if (since < (moved ? cfg.bloodTrailMovingTicks : cfg.bloodTrailStillTicks)) {
				continue;
			}
			double floor = floor(player.level(), player.position());
			if (Double.isNaN(floor)) {
				continue;
			}
			SINCE_DRIP.put(player.getUUID(), 0);
			LAST_DRIP.put(player.getUUID(), player.position());
			ServerLevel level = player.level();
			double jx = (level.getRandom().nextDouble() - 0.5) * 0.4;
			double jz = (level.getRandom().nextDouble() - 0.5) * 0.4;
			level.sendParticles(HwParticles.BLOOD_DRIP, true, true, player.getX() + jx, floor, player.getZ() + jz, 0,
				0.07 + level.getRandom().nextFloat() * 0.07, cfg.bloodTrailSeconds, 0, 1);
			AiStats.add(player, "blood.drip");
		}
	}

	/** The top of whatever is underfoot at {@code at} (within two blocks down), or NaN. */
	static double floor(ServerLevel level, Vec3 at) {
		BlockPos pos = BlockPos.containing(at.x, at.y + 0.25, at.z);
		for (int i = 0; i < 3; i++) {
			BlockState state = level.getBlockState(pos);
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (!shape.isEmpty()) {
				double top = pos.getY() + shape.max(Direction.Axis.Y);
				if (top <= at.y + 0.3) {
					return top;
				}
			}
			pos = pos.below();
		}
		return Double.NaN;
	}
}
