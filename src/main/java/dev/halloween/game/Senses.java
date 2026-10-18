package dev.halloween.game;

import dev.halloween.Role;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/** What bots and townsfolk notice: the Shape in their field of view, harder to make out in the dark. */
public final class Senses {
	private Senses() {
	}

	/** The Shape this body can see right now, or null. Inside Shape Jump he can't be seen at all. */
	/** Is the killer (in his own form) within {@code range} of them: close enough to be heard coming, seen or not? */
	public static boolean killerNear(LivingEntity who, double range) {
		for (ServerPlayer p : ((ServerLevel) who.level()).players()) {
			if (MatchManager.role(p) == Role.SHAPE && !p.isSpectator() && p.distanceTo(who) < range) {
				ShapeState st = MatchManager.shapeState(p);
				return st == null || st.shadowTicks == 0;
			}
		}
		return false;
	}

	public static ServerPlayer seenShape(LivingEntity viewer, double range, double darkRange, double fovDeg) {
		// Fog (or rain) shortens everyone's sight.
		range *= Weather.sight();
		darkRange *= Weather.sight();
		ServerLevel level = (ServerLevel) viewer.level();
		Vec3 eye = viewer.getEyePosition();
		Vec3 look = viewer.getViewVector(1f);
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player) != Role.SHAPE || player.isSpectator()) {
				continue;
			}
			ShapeState state = MatchManager.shapeState(player);
			if (state != null && state.shadowTicks > 0) {
				continue;
			}
			Vec3 body = player.position().add(0, 1.2, 0);
			double dist = eye.distanceTo(body);
			double max = lit(level, player) ? range : darkRange;
			if (dist > max) {
				continue;
			}
			// Right next to him nobody misses him, whatever the direction.
			if (dist > 2.5 && Sight.angleTo(eye, look, body) > fovDeg / 2) {
				continue;
			}
			if (Sight.canSee(level, eye, body, viewer) || Sight.canSee(level, eye, player.getEyePosition(), viewer)) {
				return player;
			}
		}
		return null;
	}

	/** Is the Shape standing in some light (lamps, house lights, flashlights)? */
	public static boolean lit(ServerLevel level, ServerPlayer shape) {
		ShapeState state = MatchManager.shapeState(shape);
		if (state != null && state.litTicks > 0) {
			return true;
		}
		return level.getBrightness(LightLayer.BLOCK, shape.blockPosition().above()) >= 6
			|| level.getBrightness(LightLayer.BLOCK, shape.blockPosition()) >= 6;
	}

	/** The Shape player, if any is in the match. */
	public static ServerPlayer shape(ServerLevel level) {
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player) == Role.SHAPE && !player.isSpectator()) {
				return player;
			}
		}
		return null;
	}
}
