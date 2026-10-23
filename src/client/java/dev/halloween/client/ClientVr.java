package dev.halloween.client;

import dev.halloween.Vr;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;

/**
 * The local headset. Everything here answers "flat screen" when Vivecraft is missing or VR is switched off,
 * so callers can branch on {@link #active()} and keep the monitor path as it is.
 */
public final class ClientVr {
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

	/** Buzz the local controller; strength 0..1. */
	public static void buzz(boolean mainHand, float seconds, float strength) {
		if (active()) {
			Api.buzz(mainHand, seconds, strength);
		}
	}

	private static final class Api {
		static boolean active() {
			return VRClientAPI.instance().isVRActive();
		}

		static boolean seated() {
			return VRClientAPI.instance().isSeated();
		}

		static void buzz(boolean mainHand, float seconds, float strength) {
			VRClientAPI.instance().triggerHapticPulse(mainHand ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND,
				seconds, 160f, strength, 0f);
		}
	}
}
