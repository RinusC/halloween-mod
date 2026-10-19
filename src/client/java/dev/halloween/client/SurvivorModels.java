package dev.halloween.client;

import dev.halloween.Halloween;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The survivors' own models (tools/survivor_model.pl: each to their build, hair, clothes), drawn in place of the player
 * model for whoever wears that survivor's skin in a match. They take the player model's pose bone by bone, as Michael's
 * does, so everything a player does (walking, grabs, falls, held items) carries over.
 */
public final class SurvivorModels {
	/** The ones built so far (the rest keep the player model). */
	private static final Set<String> BUILT = Set.of(); // plain player skins for now
	/** How tall each stands, against a player. */
	private static final Map<String, Float> TALL = Map.of("tanya", 0.92f, "alexis", 1.05f, "bob", 1.04f, "eric", 1.03f, "marcus", 1.02f,
		"thomas", 0.97f);
	private static final Map<String, MichaelRenderer.MichaelModel> MODELS = new HashMap<>();

	private SurvivorModels() {
	}

	/** Their survivor, if a model of theirs is built. */
	static String of(int entityId) {
		String key = ClientSkins.keyFor(entityId);
		return key != null && BUILT.contains(key) ? key : null;
	}

	public static float tall(int entityId) {
		String key = of(entityId);
		return key == null ? 1f : TALL.getOrDefault(key, 1f);
	}

	/** Draws them as their own model; false to leave it to the player model. */
	public static boolean submit(SubmitNodeCollector collector, Model<?> playerModel, Object state, PoseStack pose, int light, int overlay, int tint,
		int outline) {
		if (!(state instanceof AvatarRenderState avatar) || !(playerModel instanceof HumanoidModel<?> humanoid)) {
			return false;
		}
		String key = of(((HwRenderState) state).halloween$entityId());
		if (key == null) {
			return false;
		}
		MichaelRenderer.MichaelModel m = MODELS.computeIfAbsent(key,
			k -> new MichaelRenderer.MichaelModel(BbModel.load("/assets/halloween/models/entity/survivor_" + k + ".json", Set.of())));
		m.source = humanoid;
		Identifier texture = Halloween.id("textures/entity/survivor_" + key + ".png");
		net.minecraft.client.renderer.rendertype.RenderType type = tint != -1 ? RenderTypes.entityTranslucentCullItemTarget(texture)
			: avatar.isInvisible ? RenderTypes.outline(texture) : RenderTypes.entityCutout(texture);
		collector.submitModel(m, avatar, pose, type, light, overlay, tint, null, outline, null);
		return true;
	}
}
