package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.Halloween;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;

/**
 * Vecna's thrown Grip (game.Vecna) in place of the item display that carries it: a pillar of light from below the
 * ground up into the sky, drawn as beacon beams are (scrolling, turning, always at full light): a dark crimson glow as
 * wide as the Grip, breathing, round a burning red core that swells and shrinks.
 */
public final class VecnaBeamRenderer {
	private static final Identifier MODEL = Halloween.id("vecna_beam");
	/** The beams seen this frame: entity id, its age. */
	private static final Map<Integer, Float> AGES = new HashMap<>();

	private VecnaBeamRenderer() {
	}

	public static void extract(Display.ItemDisplay entity, float partialTicks) {
		Display.ItemDisplay.ItemRenderState item = entity.itemRenderState();
		ItemStack stack = item == null ? ItemStack.EMPTY : item.itemStack();
		if (MODEL.equals(stack.get(DataComponents.ITEM_MODEL))) {
			AGES.put(entity.getId(), entity.tickCount + partialTicks);
		} else {
			AGES.remove(entity.getId());
		}
	}

	public static boolean submit(ItemDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector) {
		Float age = AGES.get(((HwRenderState) state).halloween$entityId());
		if (age == null) {
			return false;
		}
		float half = (float) (dev.halloween.HwConfig.get().vecnaGripWidth / 2);
		pose.pushPose();
		// The beam is drawn from a block's corner: back to the middle.
		pose.translate(-0.5f, 0f, -0.5f);
		int start = -8;
		int height = 400;
		// Two pillars, each scrolling and turning at its own pace: a dark crimson glow as wide as the Grip, breathing,
		// and inside it the burning red core that swells and shrinks.
		float pulse = 0.85f + 0.15f * net.minecraft.util.Mth.sin(age * 0.45f) + 0.05f * net.minecraft.util.Mth.sin(age * 1.7f);
		BeaconRenderer.submitBeaconBeam(pose, collector, BeaconRenderer.BEAM_LOCATION, 1f, age * 1.3f, start, height, 0xFF6A0810, 0.001f, half * (0.9f + 0.1f * net.minecraft.util.Mth.sin(age * 0.3f)));
		BeaconRenderer.submitBeaconBeam(pose, collector, BeaconRenderer.BEAM_LOCATION, 1f, age * 2.2f, start, height, 0xFFE0281C, half * 0.3f * pulse, half * 0.5f * pulse);
		pose.popPose();
		return true;
	}

	public static void clear() {
		AGES.clear();
	}
}
