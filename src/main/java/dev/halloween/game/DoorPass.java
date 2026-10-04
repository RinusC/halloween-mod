package dev.halloween.game;

import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Who walks through doors: the Shape while in Shape Jump. Checked on both sides so movement stays in sync. */
public final class DoorPass {
	/** Set by the client mod: whether the local player is the Shape in Shape Jump. */
	public static Predicate<Entity> clientCheck = entity -> false;

	private DoorPass() {
	}

	public static boolean passes(Entity entity) {
		if (entity.level().isClientSide()) {
			return clientCheck.test(entity);
		}
		if (entity instanceof ServerPlayer player) {
			ShapeState state = MatchManager.shapeState(player);
			return state != null && state.shadowTicks > 0;
		}
		return false;
	}
}
