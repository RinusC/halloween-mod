package dev.halloween.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.game.FocusPoints;
import dev.halloween.net.HwNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class HalloweenClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Halloween.id("main"));
	public static final KeyMapping KEY_SENSE = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.killer_sense", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
	public static final KeyMapping KEY_SHAPE_JUMP = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.shape_jump", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));

	private static boolean sentSense;
	private static int lastTier;
	private static boolean lastShadow;
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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientState.reset();
			sentSense = false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(HalloweenClient::tick);

		HudElementRegistry.addLast(Halloween.id("hud"), HwHud::render);
		// Vanilla bars are replaced by our own HUD while a match role is set.
		for (Identifier id : new Identifier[]{
			VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
			VanillaHudElements.ARMOR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.AIR_BAR
		}) {
			HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
				if (!ClientState.inMatch()) {
					original.extractRenderState(graphics, delta);
				}
			});
		}
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Role role = ClientState.myRole();
		if (role != Role.NONE && mc.options.getCameraType() != CameraType.FIRST_PERSON) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}

		if (role == Role.SHAPE) {
			tickShape(mc);
		} else {
			if (sentSense) {
				sentSense = false;
				ClientPlayNetworking.send(new HwNet.SenseToggle(false));
			}
			while (KEY_SHAPE_JUMP.consumeClick()) {
				// Swallow presses outside the Shape role.
			}
		}

		if (role == Role.CIVILIAN && themeVolume(mc) > 0 && (theme == null || !mc.getSoundManager().isActive(theme))) {
			theme = new LoopSound(HwSounds.THEME, SoundSource.HOSTILE, 1f, () -> themeVolume(mc));
			mc.getSoundManager().play(theme);
		}

		if (ClientState.qte.active()) {
			while (mc.options.keyJump.consumeClick()) {
				ClientPlayNetworking.send(new HwNet.QtePress());
			}
		}
	}

	private static void tickShape(Minecraft mc) {
		mc.options.keySprint.setDown(false);
		mc.player.setSprinting(false);

		boolean sense = KEY_SENSE.isDown() && mc.gui.screen() == null;
		if (sense != sentSense) {
			sentSense = sense;
			ClientPlayNetworking.send(new HwNet.SenseToggle(sense));
		}
		while (KEY_SHAPE_JUMP.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ShapeJump());
		}

		HwNet.ShapeHud hud = ClientState.hud;
		if (hud == null) {
			return;
		}
		if (hud.sense() && (breath == null || !mc.getSoundManager().isActive(breath))) {
			breath = new LoopSound(HwSounds.SENSE_BREATH, SoundSource.PLAYERS, 0.9f,
				() -> ClientState.hud != null && ClientState.hud.sense() && ClientState.myRole() == Role.SHAPE ? 1 : 0);
			mc.getSoundManager().play(breath);
		}
		if (hud.tier() > lastTier) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(HwSounds.TIER_UP, 1f));
		}
		lastTier = hud.tier();
		boolean shadow = hud.shadowTicks() > 0;
		if (shadow && !lastShadow) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(HwSounds.SHADOW_ENTER, 1f));
		}
		lastShadow = shadow;

		// Focus points are only visible through Killer Sense.
		if (hud.sense() && mc.player.tickCount % 2 == 0) {
			for (HwNet.VictimInfo info : hud.victims()) {
				Entity entity = mc.level.getEntity(info.entityId());
				if (entity instanceof LivingEntity living && info.stalk() < 100f) {
					Vec3 p = FocusPoints.get(living, info.focus(), 1f);
					mc.level.addParticle(new DustParticleOptions(0xF4F1E8, 0.55f), p.x, p.y, p.z, 0, 0, 0);
				}
			}
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
