package dev.halloween.client;

import dev.halloween.Role;
import dev.halloween.game.Obstacles;
import dev.halloween.game.PropBlock;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Pulling things down behind you, the client's side (game.Obstacles). Running with Michael after them, a survivor is
 * offered the nearest bookcase or can beside or just behind them that has room to go over (the HUD says so); the use
 * key pulls it, no aiming, unless the crosshair is on a door, a window or the like. The view jolts when something
 * comes down close by.
 */
public final class ClientObstacles {
	/** The one the use key would pull down now (its base block), or null. */
	private static BlockPos candidate;
	private static boolean useWasDown;
	private static long lastSent = -100;
	/** Whether the server says we may now (Michael after us). */
	public static boolean allowed;
	/** The jolt of something coming down near: when, how hard. */
	private static float joltStart = -100;
	private static float joltStrength;

	private ClientObstacles() {
	}

	public static BlockPos candidate() {
		return candidate;
	}

	public static void reset() {
		candidate = null;
		allowed = false;
	}

	public static void tick(Minecraft mc) {
		boolean useDown = mc.options.keyUse.isDown();
		candidate = find(mc);
		if (useDown && !useWasDown && candidate != null && mc.level.getGameTime() - lastSent > 8 && !ClientInteractions.aimsAtObject(mc)) {
			lastSent = mc.level.getGameTime();
			Vec3 motion = mc.player.getDeltaMovement();
			ClientPlayNetworking.send(new HwNet.KnockRequest(candidate, new Vec3(motion.x, 0, motion.z)));
			// The arm on its side flung out to it.
			Vec3 to = Vec3.atBottomCenterOf(candidate).subtract(mc.player.position());
			Vec3 look = mc.player.getLookAngle();
			ViewArms.pullRight = look.x * to.z - look.z * to.x < 0;
			ViewArms.trigger(ViewArms.Act.PULL);
		}
		useWasDown = useDown;
	}

	private static BlockPos find(Minecraft mc) {
		if (mc.player == null || mc.level == null || !allowed || !ClientState.myRole().survivor() || ClientState.match.state() != 2
			|| mc.player.isSpectator() || mc.player.isInvisible() || mc.gui.screen() != null) {
			return null;
		}
		Vec3 motion = mc.player.getDeltaMovement();
		Vec3 flat = new Vec3(motion.x, 0, motion.z);
		if (!mc.player.isSprinting() || flat.length() < 0.08) {
			return null;
		}
		Vec3 heading = flat.normalize();
		Vec3 at = mc.player.position();
		BlockPos feet = mc.player.blockPosition();
		BlockPos best = null;
		double bestScore = 1e9;
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 1, 3))) {
			BlockState s = mc.level.getBlockState(p);
			if (!PropBlock.is(s) || s.getValue(PropBlock.HALF) != DoubleBlockHalf.LOWER) {
				continue;
			}
			Vec3 c = Vec3.atBottomCenterOf(p);
			Vec3 to = new Vec3(c.x - at.x, 0, c.z - at.z);
			double d = to.length();
			if (d > Obstacles.REACH || Math.abs(c.y - at.y) > 1.2) {
				continue;
			}
			// Beside or behind: not one well ahead (it would come down in front of us).
			double ahead = d < 0.3 ? 0 : to.normalize().dot(heading);
			if (ahead > 0.6) {
				continue;
			}
			double score = d + ahead * 0.8;
			if (score < bestScore && Obstacles.plan(mc.level, p, at, motion) != null) {
				bestScore = score;
				best = p.immutable();
			}
		}
		return best;
	}

	/** Something came down near us (or on him, by our doing). */
	public static void effect(HwNet.ObstacleFx fx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		joltStart = mc.level.getGameTime();
		joltStrength = Math.min(2f, fx.strength());
	}

	/** The view's jolt now (degrees of yaw, pitch), or null: a hard knock down and back, gone in a few ticks. */
	public static float[] jolt(float time) {
		float t = time - joltStart;
		if (t < 0 || t > 9) {
			return null;
		}
		float amp = joltStrength * 2.2f * (float) Math.exp(-t / 2.6f);
		return new float[]{amp * 0.35f * Mth.sin(t * 5.1f), amp * Mth.cos(t * 3.3f) * (t < 1 ? t : 1)};
	}

	/** Is the local player stopped by what lies across the way (of Michael's side, in his own form)? */
	public static boolean blocked() {
		return ClientState.myRole() == Role.SHAPE && !ClientState.shadowActive();
	}
}
