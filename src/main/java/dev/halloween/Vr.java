package dev.halloween;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.vivecraft.api.VRAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;
import org.vivecraft.api.server.VRServerAPI;

/**
 * Vivecraft bridge for both sides. Vivecraft is optional: without it every call answers "not in VR" and
 * no Vivecraft class is ever loaded (they are only touched behind {@link #LOADED}).
 */
public final class Vr {
	public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("vivecraft");

	private Vr() {
	}

	/** Whether this player plays in a headset right now (works on the server and for remote players). */
	public static boolean isVr(Player player) {
		return LOADED && Api.isVr(player);
	}

	/** Where the hand is and where it points, or null for a flat-screen player. */
	public static @Nullable Ray hand(Player player, InteractionHand hand) {
		return LOADED ? Api.part(player, hand == InteractionHand.MAIN_HAND ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND) : null;
	}

	/** The headset: eye position and the way the face looks, or null for a flat-screen player. */
	public static @Nullable Ray head(Player player) {
		return LOADED ? Api.part(player, VRBodyPart.HEAD) : null;
	}

	/** A short buzz in a VR player's controller; does nothing for everyone else. */
	public static void buzz(ServerPlayer player, boolean mainHand, float seconds) {
		if (LOADED) {
			Api.buzz(player, mainHand, seconds);
		}
	}

	/** A point in the world and a unit direction. */
	public record Ray(Vec3 pos, Vec3 dir) {
	}

	/** Only loaded when Vivecraft is there. */
	private static final class Api {
		static boolean isVr(Player player) {
			return VRAPI.instance().isVRPlayer(player);
		}

		static @Nullable Ray part(Player player, VRBodyPart part) {
			VRPose pose = VRAPI.instance().getVRPose(player);
			if (pose == null) {
				return null;
			}
			VRBodyPartData data = pose.getBodyPartData(part);
			return data == null ? null : new Ray(data.getPos(), data.getDir());
		}

		static void buzz(ServerPlayer player, boolean mainHand, float seconds) {
			if (VRServerAPI.instance().hasVivecraft(player)) {
				VRServerAPI.instance().sendHapticPulse(player, mainHand ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, seconds);
			}
		}
	}
}
