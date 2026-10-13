package dev.halloween.client;

import dev.halloween.HwConfig;
import dev.halloween.game.Guns;
import dev.halloween.game.HwItems;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * The shooter's side of firearms: right mouse raises the gun to the eye (aimed over its iron sights, a slight
 * zoom, steadier), left mouse fires along the current view, R reloads. The server decides what is hit. A shot
 * kicks: the view is thrown up and a little aside at once, then the hands bring some of it back down over the next
 * few ticks (HwConfig.recoilReturn); the camera jolts for a moment (to look at only).
 */
public final class ClientGuns {
	private static boolean sentAim;
	private static long nextShotAt;
	/** Recent recoil for the reticle: 1 right after a shot, fading back to 0. */
	private static float bloom;
	/** Degrees of the last kicks still to come back down, and the camera's jolt (its size, when it began). */
	private static float recoverLeft;
	private static float jolt;
	private static float joltAt = -100;
	private static long lastFrame;

	private ClientGuns() {
	}

	public static HwItems.Kind heldGun(Minecraft mc) {
		// A survivor in a match, or anyone at the school's range between matches.
		boolean range = !ClientState.inMatch() && ClientState.lobby.inLobby();
		if (mc.player == null || !(ClientState.myRole().survivor() || range) || mc.player.isSpectator()) {
			return null;
		}
		HwItems.Kind kind = HwItems.kindOf(mc.player.getMainHandItem());
		return kind != null && kind.gun() ? kind : null;
	}

	public static boolean aiming() {
		return sentAim;
	}

	public static float bloom() {
		return bloom;
	}

	/** Left click with a gun in hand. True when it was a gun (the vanilla attack must not run). */
	public static boolean trigger(Minecraft mc) {
		HwItems.Kind kind = heldGun(mc);
		if (kind == null) {
			return false;
		}
		long now = mc.level.getGameTime();
		if (now < nextShotAt) {
			return true;
		}
		HwConfig cfg = HwConfig.get();
		ItemStack gun = mc.player.getMainHandItem();
		ClientPlayNetworking.send(new HwNet.GunFire(mc.player.getYRot(), mc.player.getXRot()));
		ViewArms.trigger(ViewArms.Act.RECOIL);
		nextShotAt = now + Guns.shotTicks(kind);
		if (Guns.loaded(gun) > 0 && ClientState.progress.label().isEmpty()) {
			// The muzzle climbs; aimed, the hands hold it down a little better.
			float kick = (kind == HwItems.Kind.RIFLE ? cfg.rifleRecoil : kind == HwItems.Kind.SHOTGUN ? cfg.shotgunRecoil : cfg.revolverRecoil) * (sentAim ? 0.7f : 1f);
			mc.player.setXRot(Mth.clamp(mc.player.getXRot() - kick, -90f, 90f));
			mc.player.setYRot(mc.player.getYRot() + (mc.level.getRandom().nextFloat() - 0.5f) * kick * 0.35f);
			recoverLeft += kick * cfg.recoilReturn;
			jolt = kick;
			joltAt = mc.level.getGameTime();
			bloom = 1f;
		}
		return true;
	}

	public static void tick(Minecraft mc) {
		HwItems.Kind kind = heldGun(mc);
		boolean want = kind != null && mc.gui.screen() == null && mc.options.keyUse.isDown() && !ClientInteractions.aimsAtUsable(mc)
			&& !(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && ClientState.match.npcs().contains(eh.getEntity().getId()));
		if (want != sentAim) {
			sentAim = want;
			ClientPlayNetworking.send(new HwNet.GunAim(want));
		}
		while (HalloweenClient.KEY_SENSE.consumeClick()) {
			if (kind != null) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.RELOAD));
				ViewArms.trigger(ViewArms.Act.RELOAD);
			}
		}
		bloom = Math.max(0f, bloom - 0.08f);
	}

	/** Field of view while the gun is raised. */
	public static float fovMultiplier() {
		Minecraft mc = Minecraft.getInstance();
		HwItems.Kind kind = heldGun(mc);
		if (!sentAim || kind == null) {
			return 1f;
		}
		HwConfig cfg = HwConfig.get();
		return kind == HwItems.Kind.RIFLE ? cfg.rifleAimZoom : kind == HwItems.Kind.SHOTGUN ? cfg.shotgunAimZoom : cfg.revolverAimZoom;
	}

	/** The cone of fire right now as the server will see it (for the reticle), in degrees. */
	public static float spread(Minecraft mc) {
		HwItems.Kind kind = heldGun(mc);
		return kind == null ? 0f : Guns.spread(mc.player, kind, sentAim);
	}

	/**
	 * Every frame: some of the kick coming back down (by the hands, not the mouse), and the camera's jolt as
	 * turns to add to the view (yaw, pitch; null when none).
	 */
	public static float[] frame(Minecraft mc, float partial) {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1.0E9f);
		lastFrame = now;
		if (mc.player == null || mc.level == null) {
			recoverLeft = 0;
			return null;
		}
		if (recoverLeft > 0.01f && !mc.isPaused()) {
			float back = recoverLeft * Math.min(1f, dt * 7f);
			recoverLeft -= back;
			mc.player.setXRot(Mth.clamp(mc.player.getXRot() + back, -90f, 90f));
			mc.player.xRotO = Mth.clamp(mc.player.xRotO + back, -90f, 90f);
		}
		float e = mc.level.getGameTime() + partial - joltAt;
		if (e < 0 || e > 6f) {
			return null;
		}
		float k = (1f - e / 6f) * (1f - e / 6f) * jolt * 0.25f;
		return new float[]{Mth.sin(e * 5.1f) * k * 0.6f, -Mth.abs(Mth.cos(e * 4.3f)) * k};
	}

	public static void reset() {
		sentAim = false;
		bloom = 0;
		nextShotAt = 0;
		recoverLeft = 0;
		joltAt = -100;
	}
}
