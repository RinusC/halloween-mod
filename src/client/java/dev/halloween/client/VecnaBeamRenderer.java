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
 * ground up into the sky, drawn as a beacon's beam is (its scrolling, turning texture, always at full light), dark
 * crimson, its glow as wide as the Grip.
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
		// The pillar (scrolling, turning, at full light) and its faint glow out to the Grip's full width.
		BeaconRenderer.submitBeaconBeam(pose, collector, BeaconRenderer.BEAM_LOCATION, 1f, age, start, height, 0xFFB3121E, half * 0.7f, half);
		pose.popPose();
		return true;
	}

	public static void clear() {
		AGES.clear();
	}
}
