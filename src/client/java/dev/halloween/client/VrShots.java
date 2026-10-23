package dev.halloween.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The scenes in the headset. On a monitor they fly a camera about and turn it; in VR that is what makes people sick,
 * so each scene offers still shots instead: the eyes are set down somewhere with what to look at straight ahead of
 * where the player happens to be facing, the head stays free (walking about in the room moves the eyes as it should),
 * and between shots the world goes black through the fog (the one darkening that reaches both eyes; a black fill on
 * the HUD would only hang in front of the face). Nothing here runs on a monitor.
 */
public final class VrShots {
	/**
	 * One still shot: what to look at, how far back from it and how high above it the eyes are, how black the world is
	 * (0..1) and whether the player's own body is drawn. A null subject is their own eyes (only the blackness).
	 */
	public record Shot(int id, @Nullable Vec3 subject, double back, double up, float black, boolean body) {
		public static Shot own(float black) {
			return new Shot(-1, null, 0, 0, black, false);
		}
	}

	/** The shot the eyes are set down for, and how far they are moved from where the headset puts them. */
	private static int anchored = Integer.MIN_VALUE;
	private static Vec3 shift = Vec3.ZERO;
	private static Vec3 lastEye;

	private VrShots() {
	}

	private static @Nullable Shot current(float partial) {
		if (!ClientVr.active() || Minecraft.getInstance().player == null) {
			return null;
		}
		Shot shot = Cutscene.vrShot(partial);
		if (shot == null) {
			shot = VecnaDeathScene.vrShot(partial);
		}
		if (shot == null) {
			shot = KillCam.vrShot(partial);
		}
		return shot;
	}

	/** How black the world is this frame (0 when no scene plays). */
	public static float black(float partial) {
		Shot shot = current(partial);
		return shot == null ? 0f : Mth.clamp(shot.black(), 0f, 1f);
	}

	/** Whether this frame's shot shows the player's own body. */
	public static boolean showsBody(float partial) {
		Shot shot = current(partial);
		return shot != null && shot.subject() != null && shot.body();
	}

	/** Where the eyes go this frame, given where the headset puts them; null to leave them there. */
	public static @Nullable Vec3 eyes(Vec3 headset, float partial) {
		Shot shot = current(partial);
		if (shot == null || shot.subject() == null) {
			anchored = Integer.MIN_VALUE;
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.player.getEyePosition(partial);
		if (shot.id() != anchored) {
			anchored = shot.id();
			Vec3 fwd = ClientVr.headForward();
			Vec3 subject = shot.subject();
			Vec3 want = subject.add(-fwd.x * shot.back(), shot.up(), -fwd.z * shot.back());
			// Never inside a wall: nearer to them if something stands between.
			HitResult hit = mc.level.clip(new ClipContext(subject, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
			Vec3 at = hit.getType() == HitResult.Type.MISS ? want : subject.lerp(hit.getLocation(), 0.85);
			shift = at.subtract(headset);
			lastEye = eye;
		}
		// Moved by the server meanwhile (a scene places them): the shot stays where it is.
		if (eye.distanceToSqr(lastEye) > 2.25) {
			shift = shift.subtract(eye.subtract(lastEye));
		}
		lastEye = eye;
		return headset.add(shift);
	}

	/** Black at both ends of [from, to): out of black over {@code edge} after {@code from}, into it before {@code to}. */
	public static float cut(float f, float from, float to, float edge) {
		return 1f - Mth.clamp(Math.min((f - from) / edge, (to - f) / edge), 0f, 1f);
	}

	/** Out of black over {@code edge} after {@code from}. */
	public static float fadeIn(float f, float from, float edge) {
		return 1f - Mth.clamp((f - from) / edge, 0f, 1f);
	}
}
