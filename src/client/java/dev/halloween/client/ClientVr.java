package dev.halloween.client;

import dev.halloween.Vr;
import dev.halloween.net.HwNet;
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
	private static int lastHurt;

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

	/** A hand where it is drawn this frame (smoother than the tick's pose, for what hangs off it); null without a headset. */
	public static Vr.@Nullable Ray renderHand(boolean main) {
		return active() ? Api.renderPart(main ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND) : null;
	}

	/** The headset where it is drawn this frame; null without one. */
	public static Vr.@Nullable Ray renderHead() {
		return active() ? Api.renderPart(VRBodyPart.HEAD) : null;
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

	/**
	 * The HUD held before the eyes, as on a monitor, while in a match role (Vivecraft's own setting, in memory only,
	 * not by the hand or the wrist); the player's own choice back after.
	 */
	private static void hudOnHead(boolean on) {
		if (Vr.LOADED && on != hudHeld) {
			try {
				Api.hudHead(on);
				hudHeld = on;
			} catch (Throwable e) {
				// Another Vivecraft than the one this was made for: the HUD stays where it was.
				hudHeld = on;
			}
		}
	}

	private static boolean hudHeld;

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

	/**
	 * What the HUD lays over the whole screen on a monitor, as a tint of the world's fog in the headset: how much (0..1),
	 * the colour (RGB 0..1) and how near the fog closes in (blocks); null for nothing. The Shape blinded by a flashlight
	 * (white, close), knocked down (dimmer), a lightning flash in the Upside Down (red).
	 */
	public static float @Nullable [] fogTint(float partial) {
		if (!active()) {
			return null;
		}
		HwNet.ShapeHud hud = ClientState.myRole() == dev.halloween.Role.SHAPE ? ClientState.hud : null;
		if (hud != null && hud.blind()) {
			return new float[]{0.9f, 0.96f, 0.95f, 0.92f, 2.5f};
		}
		if (hud != null && hud.stunnedTicks() > 0) {
			return new float[]{0.35f, 0f, 0f, 0f, 24f};
		}
		float[] vecna = VecnaClient.vrTint(partial);
		if (vecna != null) {
			// Vecna going into someone's eyes: dark as he flies, a dark red blink as he gets there.
			return vecna;
		}
		float flash = MichaelRenderer.demogorgon() && ClientState.demo.below() ? ClientUpsideDown.flash(partial) : 0f;
		return flash > 0.01f ? new float[]{flash * 0.5f, 1f, 0.25f, 0.19f, 64f} : null;
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
		if (!active()) {
			described = false;
		} else if (!described) {
			described = true;
			describe();
		}
		hudOnHead(active() && ClientState.myRole() != dev.halloween.Role.NONE);
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
		// Hurt: a hard jolt in both hands.
		if (mc.player != null && mc.player.hurtTime > lastHurt) {
			both(0.15f, 1f);
		}
		lastHurt = mc.player == null ? 0 : mc.player.hurtTime;
		float[] quake = ClientEvents.quakeShake(mc.level.getGameTime());
		if (quake != null) {
			both(0.06f, Mth.clamp((Math.abs(quake[0]) + Math.abs(quake[1])) * 0.5f, 0.1f, 1f));
		}
	}

	private static boolean described;

	/**
	 * Into the log as VR starts: the headset and controllers SteamVR reports (the controller type is what picks
	 * Vivecraft's bindings: one it has none for leaves buttons unbound), so a report from a headset can be looked into.
	 */
	private static void describe() {
		try {
			Class<?> sys = Class.forName("org.lwjgl.openvr.VRSystem");
			java.lang.reflect.Method role = sys.getMethod("VRSystem_GetTrackedDeviceIndexForControllerRole", int.class);
			java.lang.reflect.Method prop = null;
			for (java.lang.reflect.Method m : sys.getMethods()) {
				if (m.getName().equals("VRSystem_GetStringTrackedDeviceProperty") && m.getParameterCount() == 3
					&& m.getParameterTypes()[2] == java.nio.IntBuffer.class) {
					prop = m;
				}
			}
			StringBuilder out = new StringBuilder("VR devices:");
			int[][] devices = {{0, -1}, {(int) role.invoke(null, 1), 1}, {(int) role.invoke(null, 2), 2}};
			String[] names = {"headset", "left", "right"};
			for (int i = 0; i < devices.length; i++) {
				int index = devices[i][0];
				if (index < 0 || index > 64 || prop == null) {
					out.append(' ').append(names[i]).append("=none");
					continue;
				}
				// Manufacturer, model, controller type.
				String maker = (String) prop.invoke(null, index, 1005, null);
				String model = (String) prop.invoke(null, index, 1001, null);
				String type = (String) prop.invoke(null, index, 7037, null);
				out.append(' ').append(names[i]).append("=[").append(maker).append(" / ").append(model).append(" / ").append(type).append(']');
			}
			dev.halloween.Halloween.LOGGER.info(out.toString());
		} catch (Throwable e) {
			dev.halloween.Halloween.LOGGER.info("VR devices: could not be read ({})", e.toString());
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

		static Vr.@Nullable Ray renderPart(VRBodyPart part) {
			VRPose pose = VRClientAPI.instance().getWorldRenderPose();
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

		/** The player's own HUD lock, kept while ours is on. */
		private static org.vivecraft.client_vr.settings.VRSettings.HUDLock savedLock;

		static void hudHead(boolean on) {
			var settings = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().vrSettings;
			if (on) {
				if (savedLock == null) {
					savedLock = settings.vrHudLockMode;
				}
				settings.vrHudLockMode = org.vivecraft.client_vr.settings.VRSettings.HUDLock.HEAD;
			} else if (savedLock != null) {
				settings.vrHudLockMode = savedLock;
				savedLock = null;
			}
		}

		static void unhold(net.minecraft.client.KeyMapping key) {
			var action = org.vivecraft.client_vr.provider.MCVR.get().getInputAction(key);
			if (action != null && key.isDown()) {
				action.stopHoldingBinding(2);
			}
		}
	}
}
