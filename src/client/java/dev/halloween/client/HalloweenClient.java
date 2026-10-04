package dev.halloween.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.client.mixin.GameRendererAccessor;
import dev.halloween.net.HwNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

public class HalloweenClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Halloween.id("main"));
	public static final KeyMapping KEY_SENSE = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.killer_sense", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
	public static final KeyMapping KEY_SHAPE_JUMP = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.shape_jump", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
	public static final KeyMapping KEY_PUSH = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.push", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
	public static final KeyMapping KEY_IMPULSE = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.impulse", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));

	private static final Identifier FX_SHAPE_JUMP = Halloween.id("shape_jump");
	private static final Identifier FX_SENSE = Halloween.id("killer_sense");
	private static final Identifier FX_FOCUS = Halloween.id("focus");

	private static boolean sentSense;
	private static boolean sentFocus;
	private static boolean attackWasDown;
	private static int chargeTicks;
	private static int lastLevel;
	private static boolean lastShadow;
	private static Identifier ourEffect;
	private static LoopSound breath;
	private static LoopSound theme;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(HwNet.WorldSync.TYPE, (payload, ctx) -> {
			ClientState.ROLES.clear();
			for (HwNet.RoleEntry entry : payload.roles()) {
				ClientState.ROLES.put(entry.player(), entry.role());
			}
			ClientState.shapeEntityId = payload.shapeEntityId();
			ClientState.shapeShadow = payload.shadow();
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.ShapeHud.TYPE, (payload, ctx) -> {
			ClientState.hud = payload;
			ClientState.VICTIMS.clear();
			for (HwNet.VictimInfo info : payload.victims()) {
				ClientState.VICTIMS.put(info.entityId(), info);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Qte.TYPE, (payload, ctx) -> ClientState.qte = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.ScoreEvent.TYPE, (payload, ctx) -> ClientState.addFeed(payload.points(), payload.lines()));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Impulse.TYPE, (payload, ctx) -> {
			ClientState.IMPULSE_IDS.clear();
			ClientState.IMPULSE_IDS.addAll(payload.outdoorIds());
			ClientState.impulseSpots = payload.indoorSpots();
			long now = ctx.client().level == null ? 0 : ctx.client().level.getGameTime();
			ClientState.impulseStart = now;
			ClientState.impulseUntil = now + payload.durationTicks();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientState.reset();
			sentSense = false;
			sentFocus = false;
		});

		// The Shape's melee goes through our own light/heavy logic instead of vanilla attacks.
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> ClientState.myRole() == Role.SHAPE);

		ClientTickEvents.END_CLIENT_TICK.register(HalloweenClient::tick);

		HudElementRegistry.addLast(Halloween.id("hud"), HwHud::render);
		// Vanilla bars are replaced by our own HUD while a match role is set.
		for (Identifier id : new Identifier[]{
			VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
			VanillaHudElements.ARMOR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.AIR_BAR, VanillaHudElements.CROSSHAIR
		}) {
			HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
				if (!ClientState.inMatch()) {
					original.extractRenderState(graphics, delta);
				}
			});
		}
	}

	/**
	 * Right-click for the Shape: focus while Killer Sense is held, otherwise grab the victim under the
	 * reticle. Returns true when vanilla use must not run (levers still work when nothing is grabbed).
	 */
	public static boolean handleShapeUse() {
		if (ClientState.myRole() != Role.SHAPE) {
			return false;
		}
		if (KEY_SENSE.isDown()) {
			return true;
		}
		LivingEntity picked = CameraAim.pickLiving(HwConfig.get().grabRange);
		if (picked != null) {
			ClientPlayNetworking.send(new HwNet.Grab(picked.getId()));
			return true;
		}
		return false;
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Role role = ClientState.myRole();
		CameraType wanted = role == Role.SHAPE ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
		if (role != Role.NONE && mc.options.getCameraType() != wanted) {
			mc.options.setCameraType(wanted);
		}

		if (role == Role.SHAPE) {
			tickShape(mc);
		} else {
			if (sentSense || sentFocus) {
				sentSense = false;
				sentFocus = false;
				ClientPlayNetworking.send(new HwNet.SenseInput(false, false));
			}
			for (KeyMapping key : new KeyMapping[]{KEY_SHAPE_JUMP, KEY_PUSH, KEY_IMPULSE}) {
				while (key.consumeClick()) {
					// Shape-only keys do nothing for other roles.
				}
			}
		}
		updatePostEffect(mc);

		if (role == Role.CIVILIAN && themeVolume(mc) > 0 && (theme == null || !mc.getSoundManager().isActive(theme))) {
			theme = new LoopSound(HwSounds.THEME, SoundSource.HOSTILE, 1f, () -> themeVolume(mc));
			mc.getSoundManager().play(theme);
		}

		if (ClientState.qte.active() && !ClientState.qte.forShape()) {
			while (mc.options.keyJump.consumeClick()) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.QTE_PRESS));
			}
		}
	}

	private static void tickShape(Minecraft mc) {
		HwConfig cfg = HwConfig.get();
		boolean screenOpen = mc.gui.screen() != null;
		ClientPlayNetworking.send(new HwNet.Aim(CameraAim.origin(), CameraAim.direction()));
		ClientState.updateVisibility(mc);

		// Plain sprinting is replaced by the dash.
		while (mc.options.keySprint.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.DASH));
		}
		mc.options.keySprint.setDown(false);
		mc.player.setSprinting(false);

		boolean sense = KEY_SENSE.isDown() && !screenOpen;
		boolean focus = sense && mc.options.keyUse.isDown();
		if (sense != sentSense || focus != sentFocus) {
			sentSense = sense;
			sentFocus = focus;
			ClientPlayNetworking.send(new HwNet.SenseInput(sense, focus));
		}
		while (KEY_SHAPE_JUMP.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.SHAPE_JUMP));
		}
		while (KEY_PUSH.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.PUSH));
		}
		while (KEY_IMPULSE.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.IMPULSE));
		}

		// Attack button: tap = light attack, hold = heavy attack, during a grab = reaction check.
		boolean attackDown = mc.options.keyAttack.isDown() && !screenOpen;
		if (ClientState.qte.active() && ClientState.qte.forShape()) {
			if (attackDown && !attackWasDown) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.QTE_PRESS));
			}
			chargeTicks = 0;
		} else if (attackDown) {
			chargeTicks++;
		} else if (attackWasDown && chargeTicks > 0) {
			boolean heavy = chargeTicks >= cfg.heavyChargeTicks;
			// Hit whatever is under the reticle (centre of the screen), not what the eyes point at.
			LivingEntity picked = CameraAim.pickLiving(cfg.attackReach);
			int target = picked == null ? -1 : picked.getId();
			mc.player.swing(InteractionHand.MAIN_HAND);
			ClientPlayNetworking.send(new HwNet.ShapeAttack(target, heavy));
			chargeTicks = 0;
		}
		attackWasDown = attackDown;

		HwNet.ShapeHud hud = ClientState.hud;
		if (hud == null) {
			return;
		}
		if (hud.sense() && (breath == null || !mc.getSoundManager().isActive(breath))) {
			breath = new LoopSound(HwSounds.SENSE_BREATH, SoundSource.PLAYERS, 0.9f,
				() -> ClientState.senseActive() ? 1 : 0);
			mc.getSoundManager().play(breath);
		}
		int level = Math.min(3, (int) (hud.bloodthirst() / 100f) + 1);
		if (level > lastLevel && lastLevel != 0) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(HwSounds.TIER_UP, 1f));
		}
		lastLevel = level;
		boolean shadow = hud.shadowTicks() > 0;
		if (shadow && !lastShadow) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(HwSounds.SHADOW_ENTER, 1f));
		}
		lastShadow = shadow;
	}

	/** Shape Jump and Killer Sense colour the whole world through post-processing shaders. */
	private static void updatePostEffect(Minecraft mc) {
		Identifier wanted = null;
		if (ClientState.shadowActive()) {
			wanted = FX_SHAPE_JUMP;
		} else if (ClientState.focusActive()) {
			wanted = FX_FOCUS;
		} else if (ClientState.senseActive()) {
			wanted = FX_SENSE;
		}
		Identifier current = mc.gameRenderer.currentPostEffect();
		if (wanted != null && !wanted.equals(current)) {
			((GameRendererAccessor) mc.gameRenderer).halloween$setPostEffect(wanted);
			ourEffect = wanted;
		} else if (wanted == null && ourEffect != null) {
			if (ourEffect.equals(current)) {
				mc.gameRenderer.clearPostEffect();
			}
			ourEffect = null;
		}
	}

	/** Proximity theme for civilians: rises as the Shape gets closer, silent while he is in Shape Jump. */
	private static double themeVolume(Minecraft mc) {
		if (mc.player == null || mc.level == null || ClientState.myRole() != Role.CIVILIAN || mc.player.isSpectator() || ClientState.shapeShadow) {
			return 0;
		}
		Entity shape = mc.level.getEntity(ClientState.shapeEntityId);
		if (shape == null) {
			return 0;
		}
		double range = HwConfig.get().themeRange;
		double t = 1 - mc.player.distanceTo(shape) / range;
		return t <= 0 ? 0 : t * t * (3 - 2 * t);
	}
}
