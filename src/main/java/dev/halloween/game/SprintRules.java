package dev.halloween.game;

import dev.halloween.Halloween;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Survivors run at 6 m/s rather than vanilla's 5.6: a little extra on top of the sprint modifier, on both
 * sides (the client moves its own player, so it must know too), and no running while out of breath.
 */
public final class SprintRules {
	/** 0.1 x 1.3 x (1 + this) blocks a tick = 6 m/s. */
	public static final double BONUS = 6.0 / 5.612 - 1.0;
	public static final AttributeModifier MODIFIER = new AttributeModifier(Halloween.id("survivor_run"), BONUS,
		AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
	/** Set by the client: is this (the local player) a survivor? */
	public static Predicate<LivingEntity> clientSurvivor = e -> false;
	/** Set by the client: is the local player out of breath? */
	public static Predicate<LivingEntity> clientExhausted = e -> false;

	private SprintRules() {
	}

	public static boolean fast(LivingEntity who) {
		return who instanceof ServerPlayer sp ? MatchManager.role(sp).survivor() : clientSurvivor.test(who);
	}

	public static boolean exhausted(LivingEntity who) {
		return who instanceof ServerPlayer ? Stamina.exhausted(who) : clientExhausted.test(who);
	}
}
