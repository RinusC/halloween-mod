package dev.halloween.client;

import dev.halloween.Halloween;
import dev.halloween.game.HeroInfo;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The heroes as the character select shows them (CharacterSelectScreen): drawn from the local player's own body
 * with the hero's skin (a survivor) or as the killer's model (MichaelRenderer), under ids no entity has, so that
 * nothing in the world takes them for anyone. A survivor can stand in the pose of their opening scene (HeroScene).
 */
public final class HeroPreview {
	/** The ids the preview states are made under: one per hero shown at a time (the model, the ghost, the list's heads). */
	private static final int BASE = -7100;
	private static final Map<Integer, String> WHO = new HashMap<>();
	private static final Map<Integer, Float> SCENE_TIME = new HashMap<>();

	private HeroPreview() {
	}

	public static boolean is(int id) {
		return id <= BASE && id > BASE - 64;
	}

	/** Who this preview is ("michael", a survivor), or null. */
	public static String who(int id) {
		return is(id) ? WHO.get(id) : null;
	}

	/** The killer this preview is ("michael", "demogorgon", "vecna"), or null for a survivor or no preview. */
	public static String killerKind(int id) {
		String who = is(id) ? WHO.get(id) : null;
		return who != null && HeroInfo.killer(who) ? who : null;
	}

	/** The survivor's scene pose, if this preview stands in it. */
	static boolean pose(HumanoidModel<?> m, int id) {
		String who = WHO.get(id);
		Float t = SCENE_TIME.get(id);
		return who != null && t != null && !HeroInfo.killer(who) && HeroScene.pose(m, who, t);
	}

	/** The thing in their hand: what they hold in their scene, a flashlight, a knife, nothing. */
	static ItemStack item(String who, int choice) {
		if (who.equals("michael")) {
			return switch (choice) {
				case 0 -> knife("michael_knife");
				case 1 -> knife("michael_knife_bloody");
				default -> ItemStack.EMPTY;
			};
		}
		if (HeroInfo.killer(who)) {
			return ItemStack.EMPTY;
		}
		return switch (choice) {
			case 0 -> HeroScene.prop(who);
			case 1 -> HeroScene.ours("flashlight");
			case 2 -> HeroScene.ours("kitchen_knife");
			default -> ItemStack.EMPTY;
		};
	}

	/** How many things there are to put in their hand (0: nothing to choose). */
	static int items(String who) {
		return who.equals("michael") ? 3 : HeroInfo.killer(who) ? 0 : 4;
	}

	private static ItemStack knife(String model) {
		ItemStack s = new ItemStack(Items.IRON_SWORD);
		s.set(DataComponents.ITEM_MODEL, Halloween.id(model));
		return s;
	}

	/**
	 * A state to draw {@code who} with, in slot {@code slot} (0..63): turned by {@code yaw} degrees, walking if
	 * {@code walk} (the stride at {@code time} ticks), in their scene's pose at {@code sceneTime} (negative: none),
	 * holding {@code held}. Null when there is no player to make it from.
	 */
	static AvatarRenderState state(int slot, String who, float yaw, boolean walk, float time, float sceneTime, ItemStack held, float partial) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return null;
		}
		int id = BASE - slot;
		WHO.put(id, who);
		if (sceneTime >= 0) {
			SCENE_TIME.put(id, sceneTime);
		} else {
			SCENE_TIME.remove(id);
		}
		// What they hold goes in the player's hand while the state is made, and straight back (on this screen only).
		ItemStack main = player.getItemBySlot(EquipmentSlot.MAINHAND);
		ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
		player.setItemSlot(EquipmentSlot.MAINHAND, held);
		player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		EntityRenderState made;
		try {
			EntityRenderer<? super LocalPlayer, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
			made = renderer.createRenderState(player, partial);
		} finally {
			player.setItemSlot(EquipmentSlot.MAINHAND, main);
			player.setItemSlot(EquipmentSlot.OFFHAND, off);
		}
		if (!(made instanceof AvatarRenderState s)) {
			return null;
		}
		((HwRenderState) s).halloween$setEntityId(id);
		if (!HeroInfo.killer(who)) {
			s.skin = ClientSkins.skin(who);
		}
		s.shadowPieces.clear();
		s.outlineColor = 0;
		s.nameTag = null;
		s.scoreText = null;
		s.isInvisible = false;
		s.isCrouching = false;
		s.isDiscrete = false;
		s.isVisuallySwimming = false;
		s.isFallFlying = false;
		s.isPassenger = false;
		s.isUsingItem = false;
		s.swimAmount = 0;
		s.attackTime = 0;
		s.deathTime = 0;
		s.hasRedOverlay = false;
		s.displayFireAnimation = false;
		s.pose = Pose.STANDING;
		s.bedOrientation = null;
		s.leashStates = null;
		s.headEquipment = ItemStack.EMPTY;
		s.headItem.clear();
		s.showCape = false;
		s.parrotOnLeftShoulder = null;
		s.parrotOnRightShoulder = null;
		s.arrowCount = 0;
		s.stingerCount = 0;
		s.walkAnimationSpeed = walk ? 0.75f : 0f;
		s.walkAnimationPos = walk ? time * 0.6f : 0f;
		s.bodyRot = 180f + yaw;
		s.yRot = 0f;
		s.xRot = 0f;
		s.boundingBoxWidth = 0.6f;
		s.boundingBoxHeight = 1.8f;
		s.scale = who.equals("michael") ? MichaelRenderer.TALL : 1f;
		return s;
	}
}
