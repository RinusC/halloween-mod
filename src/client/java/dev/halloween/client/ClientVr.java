package dev.halloween.client;

import dev.halloween.Vr;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRPose;

/**
 * The local headset. Everything here answers "flat screen" when Vivecraft is missing or VR is switched off,
 * so callers can branch on {@link #active()} and keep the monitor path as it is.
 *
 * <p>In the headset nothing shakes or turns the view: Vivecraft draws the eyes where the head is, and the shakes a
 * monitor gets (panic, the midnight quake, a gun's kick) come as buzzes in the hands instead.
 */
public final class ClientVr {
	/** When the next heartbeat buzz is due (client ticks), and whether the second, softer half of it is still to come. */
	private static long beatAt;
	private static boolean dub;
	private static long ticks;

	private ClientVr() {
	}

	/** The game is being played in the headset this frame (Vivecraft can switch VR on and off live). */
	public static boolean active() {
		return Vr.LOADED && Api.active();
	}

	/** Seated play: no room to swing or crouch for real, so gestures fall back to buttons. */
	public static boolean seated() {
		return active() && Api.seated();
	}

	/** Where the face looks, flattened to the ground (a unit vector); the player's look when there is no headset pose. */
	public static Vec3 headForward() {
		Vec3 dir = active() ? Api.headDir() : null;
		if (dir == null) {
			dir = Minecraft.getInstance().player.getLookAngle();
		}
		Vec3 flat = new Vec3(dir.x, 0, dir.z);
		return flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
	}

	/** A hand in the world this tick: where it is and where it points; null without a headset. */
	public static Vr.@Nullable Ray hand(boolean main) {
		return active() ? Api.part(main ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, true) : null;
	}

	/** The headset in the world this tick; null without one. */
	public static Vr.@Nullable Ray head() {
		return active() ? Api.part(VRBodyPart.HEAD, true) : null;
	}

	/** A hand in the room (moving about with the stick does not move it): for how fast it swings. */
	static @Nullable Vec3 roomHand(boolean main) {
		Vr.Ray ray = active() ? Api.part(main ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, false) : null;
		return ray == null ? null : ray.pos();
	}

	/** The radial menu's buttons (Vivecraft's own setting, in memory only): ours while in a match role, the player's back after. */
	static void radial(String @Nullable [] items) {
		if (Vr.LOADED) {
			try {
				Api.radial(items);
			} catch (Throwable e) {
				// Another Vivecraft than the one this was made for: the radial menu stays as it was.
			}
		}
	}

	/** Lets go of a key the radial menu holds down (it holds what it presses until pressed again). */
	static void unhold(net.minecraft.client.KeyMapping key) {
		if (active()) {
			try {
				Api.unhold(key);
			} catch (Throwable e) {
				// As above.
			}
		}
	}

	/** Buzz the local controller; strength 0..1. */
	public static void buzz(boolean mainHand, float seconds, float strength) {
		if (active()) {
			Api.buzz(mainHand, seconds, strength);
		}
	}

	private static void both(float seconds, float strength) {
		buzz(true, seconds, strength);
		buzz(false, seconds, strength);
	}

	/** Every client tick: panic as a heartbeat in the hands, the quake as a rumble. */
	static void tick(Minecraft mc) {
		ticks++;
		if (!active() || mc.level == null) {
			return;
		}
		float panic = ClientPanic.level();
		if (panic < 0.3f) {
			beatAt = 0;
			dub = false;
		} else if (ticks >= beatAt) {
			// Lub (stronger), dub four ticks later; faster and harder the worse it gets.
			float strength = 0.2f + 0.5f * panic;
			both(0.05f, dub ? strength * 0.6f : strength);
			beatAt = ticks + (dub ? Math.round(Mth.lerp(panic, 20f, 6f)) : 4);
			dub = !dub;
		}
		float[] quake = ClientEvents.quakeShake(mc.level.getGameTime());
		if (quake != null) {
			both(0.06f, Mth.clamp((Math.abs(quake[0]) + Math.abs(quake[1])) * 0.5f, 0.1f, 1f));
		}
	}

	private static final class Api {
		static boolean active() {
			return VRClientAPI.instance().isVRActive();
		}

		static boolean seated() {
			return VRClientAPI.instance().isSeated();
		}

		static @Nullable Vec3 headDir() {
			VRPose pose = VRClientAPI.instance().getWorldRenderPose();
			return pose == null ? null : pose.getHead().getDir();
		}

		static void buzz(boolean mainHand, float seconds, float strength) {
			VRClientAPI.instance().triggerHapticPulse(mainHand ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND,
				seconds, 160f, strength, 0f);
		}

		static Vr.@Nullable Ray part(VRBodyPart part, boolean world) {
			VRPose pose = world ? VRClientAPI.instance().getPostTickWorldPose() : VRClientAPI.instance().getPostTickRoomPose();
			if (pose == null) {
				return null;
			}
			var data = pose.getBodyPartData(part);
			return data == null ? null : new Vr.Ray(data.getPos(), data.getDir());
		}

		/** The player's own radial buttons, kept while ours are shown. */
		private static String[] saved;

		static void radial(String @Nullable [] items) {
			var settings = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().vrSettings;
			if (items == null) {
				if (saved != null) {
					settings.vrRadialItems = saved;
					saved = null;
				}
				return;
			}
			if (saved == null) {
				saved = settings.vrRadialItems;
			}
			settings.vrRadialItems = items;
		}

		static void unhold(net.minecraft.client.KeyMapping key) {
			var action = org.vivecraft.client_vr.provider.MCVR.get().getInputAction(key);
			if (action != null && key.isDown()) {
				action.stopHoldingBinding(2);
			}
		}
	}
}
