package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.halloween.Halloween;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;

/**
 * The Demogorgon's rift (art/rift.bbmodel, made by tools/rift_model.pl) in place of the item display that carries
 * it (game.Demogorgon): the wound in the ground, its lips, cracks, the membrane standing out of it and the
 * tentacles. Its animations: open from the moment it appears, then idle (breathing, writhing); when the server
 * swaps the carrier's item for the closing one, close. Drawn twice: the flesh lit as the world is, the split, the
 * pit's heart and the cracks glowing at full brightness.
 */
public final class RiftRenderer {
	private static final Identifier TEXTURE = Halloween.id("textures/entity/rift.png");
	private static final Identifier GLOW = Halloween.id("textures/entity/rift_glow.png");
	private static final Identifier MODEL_OPEN = Halloween.id("rift");
	private static final Identifier MODEL_CLOSING = Halloween.id("rift_closing");
	private static final int FULL_BRIGHT = 0xF000F0;

	/** What the renderer needs of one rift this frame. */
	static final class Pose {
		float age;
		float closingFor = -1;
	}

	static final class RiftModel extends Model<Pose> {
		final BbModel bb;

		RiftModel(BbModel bb) {
			super(bb.root, RenderTypes::entityTranslucent);
			this.bb = bb;
		}

		@Override
		public void setupAnim(Pose p) {
			this.resetPose();
			if (p.closingFor >= 0f) {
				this.bb.apply("close", p.closingFor / 20f, 1f);
				return;
			}
			float s = p.age / 20f;
			BbModel.Animation open = this.bb.animation("open");
			if (open == null || s >= open.length()) {
				this.bb.apply("idle", s, 1f);
			} else {
				this.bb.apply("open", s, 1f);
			}
		}
	}

	private static RiftModel model;
	/** The rifts seen this frame (entity id: its pose), and when each began closing (its age then). */
	private static final Map<Integer, Pose> POSES = new HashMap<>();
	private static final Map<Integer, Float> CLOSING_AT = new HashMap<>();

	private RiftRenderer() {
	}

	private static RiftModel model() {
		if (model == null) {
			model = new RiftModel(BbModel.load("/assets/halloween/models/entity/rift.json", Set.of()));
		}
		return model;
	}

	/** From the carrier's state being filled in: is it a rift, and is it closing yet? */
	public static void extract(Display.ItemDisplay entity, ItemDisplayEntityRenderState state, float partialTicks) {
		Display.ItemDisplay.ItemRenderState item = entity.itemRenderState();
		ItemStack stack = item == null ? ItemStack.EMPTY : item.itemStack();
		Identifier id = stack.get(DataComponents.ITEM_MODEL);
		int key = entity.getId();
		if (!MODEL_OPEN.equals(id) && !MODEL_CLOSING.equals(id)) {
			POSES.remove(key);
			return;
		}
		Pose p = POSES.computeIfAbsent(key, k -> new Pose());
		p.age = entity.tickCount + partialTicks;
		if (MODEL_CLOSING.equals(id)) {
			float since = CLOSING_AT.computeIfAbsent(key, k -> p.age);
			p.closingFor = p.age - since;
		} else {
			CLOSING_AT.remove(key);
			p.closingFor = -1;
		}
	}

	/** Draws it, if this carrier is a rift; true if it was. */
	public static boolean submit(ItemDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, int light) {
		Pose p = POSES.get(((HwRenderState) state).halloween$entityId());
		if (p == null) {
			return false;
		}
		RiftModel m = model();
		pose.pushPose();
		// Entity models stand upside down in their space.
		pose.scale(-1f, -1f, 1f);
		pose.translate(0f, -1.501f, 0f);
		collector.submitModel(m, p, pose, RenderTypes.entityTranslucent(TEXTURE), light, OverlayTexture.NO_OVERLAY, -1, null, 0, null);
		collector.submitModel(m, p, pose, RenderTypes.entityTranslucentEmissive(GLOW), FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1, null, 0, null);
		pose.popPose();
		return true;
	}

	public static void clear() {
		POSES.clear();
		CLOSING_AT.clear();
	}
}
