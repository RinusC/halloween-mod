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
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import dev.halloween.game.DoorPass;
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
	public static final KeyMapping KEY_IMPULSE = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.impulse", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
	public static final KeyMapping KEY_RUNT_ORDER = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.runt_order", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
	/** A survivor's own skill (game.Heroes), and marking something for the team (game.Pings). */
	public static final KeyMapping KEY_SKILL = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.skill", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));
	public static final KeyMapping KEY_PING = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY));
	public static final KeyMapping KEY_PERKS = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.halloween.perks", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));

	private static final Identifier FX_SHAPE_JUMP = Halloween.id("shape_jump");
	private static final Identifier FX_SENSE = Halloween.id("killer_sense");
	private static final Identifier FX_FOCUS = Halloween.id("focus");
	/** Vecna's curse at its last stage, for the cursed: the world swims. */
	private static final Identifier FX_CURSE = Halloween.id("curse");

	private static boolean sentSense;
	private static boolean sentFocus;
	private static boolean attackWasDown;
	private static int chargeTicks;
	private static int lastLevel;
	private static Identifier ourEffect;
	private static LoopSound breath;

	@Override
	public void onInitializeClient() {
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(dev.halloween.HwParticles.BEAM, BeamParticle.Provider::new);
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(dev.halloween.HwParticles.BLOOD, BloodParticles.DropProvider::new);
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(dev.halloween.HwParticles.DROOL, DroolParticle.Provider::new);
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(dev.halloween.HwParticles.BLOOD_SPLAT, BloodParticles.SplatProvider::new);
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(dev.halloween.HwParticles.BLOOD_POOL, BloodParticles.PoolProvider::new);
		net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance().register(dev.halloween.HwParticles.BLOOD_DRIP, BloodParticles.DripProvider::new);
		VecnaParticles.register();
		// In the headset: short buttons for the radial menu, and the hands (see VrKeys, VrHands).
		VrKeys.register();
		VrHands.register();
		VrMarks.register();
		VrWrist.register();
		ClientHeroes.register();
		ClientJobs.register();
		ClientPlayNetworking.registerGlobalReceiver(HwNet.WorldSync.TYPE, (payload, ctx) -> {
			ClientState.ROLES.clear();
			for (HwNet.RoleEntry entry : payload.roles()) {
				ClientState.ROLES.put(entry.player(), entry.role());
			}
			ClientState.shapeEntityId = payload.shapeEntityId();
			ClientState.shapeShadow = payload.shadow();
			ClientState.killer = payload.killer();
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.DemoHud.TYPE, (payload, ctx) -> ClientState.demo = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.VecnaHud.TYPE, (payload, ctx) -> ClientState.vecna = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.CurseHud.TYPE, (payload, ctx) -> ClientState.curse = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.VecnaRitual.TYPE, (payload, ctx) -> ClientState.ritual = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.VecnaDeath.TYPE, (payload, ctx) -> VecnaDeathScene.begin(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.VecnaEyesEnter.TYPE, (payload, ctx) -> VecnaClient.eyesEnter(payload.entity()));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.DemoPose.TYPE, (payload, ctx) -> DemogorgonModel.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.GunPose.TYPE, (payload, ctx) -> GunPoses.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.DemoHouses.TYPE, (payload, ctx) -> ClientState.demoHouses = payload.corners());
		ClientPlayNetworking.registerGlobalReceiver(HwNet.RuntHud.TYPE, (payload, ctx) -> ClientState.runtHud = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.EventSync.TYPE, (payload, ctx) -> ClientEvents.state = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Quake.TYPE, (payload, ctx) -> ClientEvents.quake(payload.ticks(), payload.strength()));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.RuntInfo.TYPE, (payload, ctx) -> {
			ClientState.runtInfo = payload;
			ClientState.runtInfoAt = ctx.client().level == null ? 0 : ctx.client().level.getGameTime();
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
		ClientPlayNetworking.registerGlobalReceiver(HwNet.SkillCheck.TYPE, (payload, ctx) -> ClientState.skill = payload);
		// Using a townsperson opens the radial talk menu (or, mid-talk, the tick breaks the talk off).
		net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() || !ClientState.myRole().survivor() || !ClientState.match.npcs().contains(entity.getId())) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			Minecraft mc = Minecraft.getInstance();
			if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && !ClientState.talking() && mc.gui.screen() == null) {
				mc.gui.setScreen(new NpcMenuScreen(entity.getId(), entity.getDisplayName(), ClientState.match.followers().contains(entity.getId())));
			}
			return net.minecraft.world.InteractionResult.FAIL;
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Notice.TYPE, (payload, ctx) -> ClientState.notice(payload.text()));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Minigame.TYPE, (payload, ctx) -> {
			// Screens (and the mouse cursor with them) only on the game thread.
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				if (payload.kind() == HwNet.Minigame.WRONG_CODE) {
					if (mc.gui.screen() instanceof KeypadScreen keypad) {
						keypad.wrong();
					}
					return;
				}
				if (mc.gui.screen() instanceof MinigameScreen open) {
					if (payload.kind() < 0 || !open.shows(payload)) {
						open.closeQuietly();
					} else {
						return;
					}
				}
				if (payload.kind() >= 0) {
					mc.gui.setScreen(MinigameScreen.create(payload));
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Alert.TYPE, (payload, ctx) -> {
			long now = ctx.client().level == null ? 0 : ctx.client().level.getGameTime();
			ClientState.ALERTS.removeIf(a -> a.until() < now);
			ClientState.ALERTS.add(new ClientState.AlertMark(payload.pos(), now + payload.durationTicks()));
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Skins.TYPE, (payload, ctx) -> ClientSkins.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.MinimapSync.TYPE, (payload, ctx) -> ClientMinimap.apply(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.MusicMood.TYPE, (payload, ctx) -> ClientMusic.set(payload.mood(), payload.variant()));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Anim.TYPE, (payload, ctx) -> ClientAnims.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Roster.TYPE, (payload, ctx) -> ClientState.roster = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.LobbyInfo.TYPE, (payload, ctx) -> ClientState.lobby = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Cutscene.TYPE, (payload, ctx) -> Minecraft.getInstance().execute(() -> Cutscene.begin(payload)));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.NoiseBurst.TYPE, (payload, ctx) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				ClientState.NOISES.add(new ClientState.NoiseMark(payload.pos(), payload.loud(), mc.level.getGameTime()));
				if (ClientState.NOISES.size() > 12) {
					ClientState.NOISES.removeFirst();
				}
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.PerkSync.TYPE, (payload, ctx) -> {
			ClientState.perkPoints = payload.points();
			ClientState.perkOwned = payload.owned();
			ClientState.perkEquipped = payload.equipped();
			ClientState.perkSlots = payload.slots();
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				if (mc.gui.screen() instanceof PerkScreen perks) {
					perks.refresh();
				}
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Pant.TYPE, (payload, ctx) -> ClientPanting.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(HwNet.StaminaSync.TYPE, (payload, ctx) -> {
			ClientState.stamina = payload.value();
			ClientState.staminaExhausted = payload.exhausted();
			ClientState.panic = payload.panic();
		});
		dev.halloween.game.SprintRules.clientSurvivor = e -> e == net.minecraft.client.Minecraft.getInstance().player && ClientState.myRole().survivor();
		dev.halloween.game.SprintRules.clientExhausted = e -> e == net.minecraft.client.Minecraft.getInstance().player && ClientState.staminaExhausted;
		ClientPlayNetworking.registerGlobalReceiver(HwNet.MatchWeather.TYPE, (payload, ctx) -> ClientWeather.kind = payload.kind());
		ClientPlayNetworking.registerGlobalReceiver(HwNet.DoorSync.TYPE, (payload, ctx) -> {
			ClientState.LOCKED_DOORS.clear();
			ClientState.LOCKED_DOORS.addAll(payload.locked());
			ClientState.BROKEN_LOCKS.clear();
			ClientState.BROKEN_LOCKS.addAll(payload.broken());
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.Progress.TYPE, (payload, ctx) -> ClientState.progress = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.MatchSync.TYPE, (payload, ctx) -> ClientState.match = payload);
		ClientPlayNetworking.registerGlobalReceiver(HwNet.MatchEnd.TYPE, (payload, ctx) -> {
			ClientState.matchEnd = payload;
			ClientState.matchEndAt = System.currentTimeMillis();
			MatchEndScreen.open(payload);
		});
		UseBlockCallback.EVENT.register(ClientInteractions::onUseBlock);
		// The local Shape walks through doors during Shape Jump (the server agrees, see DoorPass).
		DoorPass.clientCheck = entity -> entity == Minecraft.getInstance().player && ClientState.shadowActive();
		// What was pulled down across the way stops the local Michael (the server agrees, see DebrisBlock).
		dev.halloween.game.DebrisBlock.clientBlocked = entity -> entity == Minecraft.getInstance().player && ClientObstacles.blocked();
		ClientPlayNetworking.registerGlobalReceiver(HwNet.KnockAllowed.TYPE, (payload, ctx) -> ClientObstacles.allowed = payload.allowed());
		ClientPlayNetworking.registerGlobalReceiver(HwNet.ObstacleFx.TYPE, (payload, ctx) -> ClientObstacles.effect(payload));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientState.reset();
			MinigameScreen.forgetAll();
			ClientHeroes.reset();
			ClientJobs.reset();
			ClientGuns.reset();
			sentSense = false;
			sentFocus = false;
		});

		// The Shape's melee goes through our own light/heavy logic instead of vanilla attacks.
		// Survivors: the left button answers reaction checks, and with no weapon in hand it shoves the Shape.
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> ClientState.myRole() == Role.SHAPE || runtKnock(client) || survivorAttack(client, clickCount));

		ClientTickEvents.END_CLIENT_TICK.register(HalloweenClient::tick);

		HudElementRegistry.addLast(Halloween.id("hud"), HwHud::render);
		// No chat over the opening scene.
		HudElementRegistry.replaceElement(VanillaHudElements.CHAT, original -> (graphics, delta) -> {
			if (!Cutscene.active()) {
				original.extractRenderState(graphics, delta);
			}
		});
		// Vanilla bars are replaced by our own HUD while a match role is set.
		for (Identifier id : new Identifier[]{
			VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
			VanillaHudElements.ARMOR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.AIR_BAR, VanillaHudElements.CROSSHAIR
		}) {
			HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
				// (With a gun at the range, no crosshair: the sights, or our dot.)
				if (!ClientState.inMatch() && !(id == VanillaHudElements.CROSSHAIR && ClientGuns.heldGun(Minecraft.getInstance()) != null)) {
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
		if (down(KEY_SENSE) && !MichaelRenderer.demogorgon() && !MichaelRenderer.vecna()) {
			return true;
		}
		LivingEntity picked = CameraAim.pickLiving(HwConfig.get().grabRange);
		if (picked == null) {
			// Someone on a window sill, through the glass and a little further (the server checks they are climbing).
			picked = CameraAim.pickLiving(HwConfig.get().grabRange + HwConfig.get().grabVaultBonusRange, true);
		}
		if (picked != null) {
			ClientPlayNetworking.send(new HwNet.Grab(picked.getId()));
			return true;
		}
		if (MichaelRenderer.vecna()) {
			// Vecna: a body at his feet, perhaps (the server looks).
			ClientPlayNetworking.send(new HwNet.Grab(-1));
		}
		// A locker: throw the door open (and grab whoever hides inside).
		net.minecraft.core.BlockPos block = CameraAim.pickBlock(HwConfig.get().grabRange);
		if (block != null && Minecraft.getInstance().level.getBlockState(block).getBlock() instanceof dev.halloween.game.LockerBlock) {
			ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.LOCKER, block));
			return true;
		}
		return false;
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		HeroScene.tick(mc);
		ClientVr.tick(mc);
		VrKeys.tick(mc);
		VrHands.tick(mc);
		VrWrist.tick(mc);
		ClientHeroes.tick(mc);
		ClientObstacles.tick(mc);
		ClientInteractions.tick(mc);
		ShapeJumpAudio.tick(mc);
		BloodParticles.tick(mc);
		DashSteps.tick(mc);
		ShapeBreath.tick(mc);
		ClientWeather.tick();
		ClientEvents.tick(mc);
		ClientPanting.tick(mc);
		ClientPanic.tick(mc);
		VecnaClient.tick(mc);
		while (KEY_PERKS.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new PerkScreen());
			}
		}
		Role role = ClientState.myRole();
		// Michael from behind; the Runt too while it rides somebody's back (then straight back to its own eyes).
		boolean riding = role == Role.RUNT && ClientState.runtHud != null && ClientState.runtHud.holding() > 0;
		CameraType wanted = role == Role.SHAPE || riding ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
		if (VecnaClient.eyesActive() || ClientVr.active()) {
			// Vecna's Eyes: through theirs. In the headset always one's own eyes.
			wanted = CameraType.FIRST_PERSON;
		}
		if (role != Role.NONE && mc.options.getCameraType() != wanted) {
			mc.options.setCameraType(wanted);
		}

		if (role == Role.SHAPE) {
			tickShape(mc);
		} else if (role == Role.RUNT) {
			tickRunt(mc);
		} else {
			if (sentSense || sentFocus) {
				sentSense = false;
				sentFocus = false;
				ClientPlayNetworking.send(new HwNet.SenseInput(false, false));
			}
			// R and the right mouse button work the gun, if there is one in hand.
			ClientGuns.tick(mc);
			ClientUpsideDown.tick(mc);
			for (KeyMapping key : new KeyMapping[]{KEY_SHAPE_JUMP, KEY_IMPULSE, KEY_RUNT_ORDER}) {
				while (key.consumeClick()) {
					// Shape-only keys do nothing for other roles.
				}
			}
		}
		updatePostEffect(mc);

		ClientMusic.tick(mc);
	}

	/**
	 * A survivor's left click. During a reaction check (or breaking free) it is the answer and nothing else;
	 * with no weapon in hand it shoves the Shape. Returns true when the vanilla attack must not run.
	 */
	static boolean survivorAttack(Minecraft mc, int clickCount) {
		if (clickCount > 0 && mc.player != null && mc.level != null && !ClientState.inMatch() && ClientState.lobby.inLobby()) {
			// The school's range: a gun fires, anything else as vanilla has it.
			return ClientGuns.trigger(mc);
		}
		if (clickCount <= 0 || mc.player == null || mc.level == null || !ClientState.myRole().survivor() || mc.player.isSpectator()) {
			return false;
		}
		if (ClientState.qte.active() && !ClientState.qte.forShape() || ClientState.skill.active()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.QTE_PRESS));
			return true;
		}
		if (ClientGuns.trigger(mc)) {
			return true;
		}
		dev.halloween.game.HwItems.Kind held = dev.halloween.game.HwItems.kindOf(mc.player.getMainHandItem());
		if (held != null && held.melee() || mc.player.isInvisible()) {
			return false;
		}
		shove(mc);
		return false;
	}

	/** A survivor's shove (the left click with empty hands, or both hands thrust out in the headset). */
	static void shove(Minecraft mc) {
		if (mc.player.isInvisible()) {
			return;
		}
		ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.PUSH));
		ViewArms.trigger(ViewArms.Act.SHOVE);
		// The cooldown on screen starts only when the Shape was really in reach.
		net.minecraft.world.entity.Entity shape = mc.level.getEntity(ClientState.shapeEntityId);
		if (shape != null && ClientState.pushReadyAt <= mc.level.getGameTime()
			&& mc.player.getEyePosition().distanceTo(shape.getBoundingBox().getCenter()) <= HwConfig.get().survivorPushRange + 0.5) {
			ClientState.pushReadyAt = mc.level.getGameTime() + HwConfig.get().survivorPushCooldownTicks;
		}
	}

	/** A key held down: on the keyboard, by a VR button, or by a gesture standing for it. */
	static boolean down(KeyMapping key) {
		return key.isDown() || VrKeys.held(key) || VrHands.holds(key);
	}

	private static final boolean[] PICK_KEYS_DOWN = new boolean[2];

	/** The Runt: no running (he scurries as he is), V trades places with Michael, 1 and 2 pick a gift when one is waiting. */
	private static void tickRunt(Minecraft mc) {
		while (mc.options.keySprint.consumeClick()) {
			// Nothing: he has no breath to run with.
		}
		mc.options.keySprint.setDown(false);
		mc.player.setSprinting(false);
		while (KEY_SHAPE_JUMP.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.RUNT_SPECIAL));
		}
		while (KEY_IMPULSE.consumeClick()) {
			// Michael's key: nothing for the Runt.
		}
		while (KEY_RUNT_ORDER.consumeClick()) {
			// Michael's key.
		}
		HwNet.RuntHud hud = ClientState.runtHud;
		int pending = hud == null ? -1 : hud.pending();
		for (int k = 0; k < 2; k++) {
			boolean down = mc.options.keyHotbarSlots[k].isDown() && mc.gui.screen() == null;
			if (down && !PICK_KEYS_DOWN[k] && pending >= 0) {
				ClientPlayNetworking.send(new HwNet.RuntPick(pending, k));
			}
			PICK_KEYS_DOWN[k] = down;
		}
	}

	/** The Runt's left click on a door or a window: knocking (the server says if it may). True if it was that. */
	private static boolean runtKnock(Minecraft mc) {
		if (ClientState.myRole() != Role.RUNT || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
			|| hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return false;
		}
		var state = mc.level.getBlockState(hit.getBlockPos());
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) && !dev.halloween.game.Windows.isWindow(state)) {
			return false;
		}
		ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.RUNT_KNOCK, hit.getBlockPos()));
		ViewArms.trigger(ViewArms.Act.KNOCK);
		return true;
	}

	/** Michael's order wheel: what he looks at now is "there" and "that one". Only for a bot Runt. */
	private static void openRuntOrders(Minecraft mc) {
		HwNet.RuntInfo info = ClientState.runtInfo;
		if (mc.gui.screen() != null || info == null || mc.level.getGameTime() - ClientState.runtInfoAt > 40) {
			return;
		}
		if (!info.bot()) {
			mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("halloween.hud.runt.person"));
			return;
		}
		net.minecraft.world.entity.LivingEntity aimed = CameraAim.pickLiving(60);
		net.minecraft.core.BlockPos spot = CameraAim.pickBlock(80);
		if (spot != null) {
			spot = spot.above();
		}
		mc.gui.setScreen(new RuntOrderScreen(aimed == null ? -1 : aimed.getId(), spot));
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

		// (The Demogorgon has no Killer Sense: he follows blood.)
		boolean sense = down(KEY_SENSE) && !screenOpen && !MichaelRenderer.demogorgon();
		boolean focus = sense && mc.options.keyUse.isDown();
		if (sense != sentSense || focus != sentFocus) {
			sentSense = sense;
			sentFocus = focus;
			ClientPlayNetworking.send(new HwNet.SenseInput(sense, focus));
		}
		while (KEY_SHAPE_JUMP.consumeClick()) {
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.SHAPE_JUMP));
		}
		while (KEY_RUNT_ORDER.consumeClick()) {
			openRuntOrders(mc);
		}
		if (MichaelRenderer.vecna()) {
			// Vecna's Anchor: held and let go, not pressed.
			VecnaClient.tickAnchorKey(mc);
		}
		while (KEY_IMPULSE.consumeClick()) {
			if (!MichaelRenderer.vecna()) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.IMPULSE));
			}
		}

		// Attack button: tap = light attack, hold = heavy attack, during a grab = reaction check.
		boolean attackDown = mc.options.keyAttack.isDown() && !screenOpen;
		if (ClientState.qte.active() && ClientState.qte.forShape()) {
			if (attackDown && !attackWasDown) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.QTE_PRESS));
			}
			chargeTicks = 0;
		} else if (VrHands.killerSwing(mc, attackDown) instanceof VrHands.Swing swing) {
			// In the headset: the blow is the swing of the hand, heavy when the trigger was held long enough first.
			boolean heavy = attackDown && chargeTicks >= cfg.heavyChargeTicks;
			mc.player.swing(InteractionHand.MAIN_HAND);
			ClientPlayNetworking.send(new HwNet.ShapeAttack(swing.target() == null ? -1 : swing.target().getId(), heavy));
			chargeTicks = 0;
		} else if (attackDown) {
			chargeTicks++;
		} else if (attackWasDown && VrHands.swallowRelease()) {
			chargeTicks = 0;
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
	}

	/** Shape Jump, Killer Sense and the last stage of Vecna's curse colour the whole world through post-processing shaders. */
	private static void updatePostEffect(Minecraft mc) {
		Identifier wanted = null;
		if (ClientState.shadowActive()) {
			wanted = FX_SHAPE_JUMP;
		} else if (ClientState.focusActive()) {
			wanted = FX_FOCUS;
		} else if (ClientState.senseActive()) {
			wanted = FX_SENSE;
		} else if (ClientState.curse.stage() >= 4 && ClientState.myRole().survivor() && mc.player != null && !mc.player.isSpectator()) {
			wanted = FX_CURSE;
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

}
