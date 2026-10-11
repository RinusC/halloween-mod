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
 * The shooter's side of firearms: right mouse raises the gun (zoom, steadier), left mouse fires along the
 * current view (the muzzle kicks the view up), R reloads. The server decides what is hit.
 */
public final class ClientGuns {
	private static boolean sentAim;
	private static long nextShotAt;
	/** Recent recoil for the reticle: 1 right after a shot, fading back to 0. */
	private static float bloom;

	private ClientGuns() {
	}

	public static HwItems.Kind heldGun(Minecraft mc) {
		if (mc.player == null || !ClientState.myRole().survivor() || mc.player.isSpectator()) {
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

	public static void reset() {
		sentAim = false;
		bloom = 0;
		nextShotAt = 0;
	}
}
