package dev.halloween.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.halloween.Halloween;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The crows (game.Birds) on their item displays: our own model (models/entity/crow.json, made by
 * tools/crow_model.pl). Perched it bobs and pecks now and then; flying, its body
 * tips forward and its wings beat about their inner edge.
 */
public final class BirdRenderer {
	private static final Identifier TEXTURE = Halloween.id("textures/entity/crow.png");

	private record Quad(int part, float[] v) {
	}

	private record Mesh(List<Quad> quads, Vector3f leftHinge, Vector3f rightHinge) {
	}

	private static Map<String, Mesh> meshes;
	private static boolean missing;
	private static final Map<Integer, float[]> SEEN = new HashMap<>();

	private BirdRenderer() {
	}

	private static Map<String, Mesh> meshes() {
		if (meshes != null || missing) {
			return meshes;
		}
		try (InputStream in = BirdRenderer.class.getResourceAsStream("/assets/halloween/models/entity/crow.json")) {
			if (in == null) {
				missing = true;
				return null;
			}
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, Mesh> out = new HashMap<>();
			JsonObject birds = json.getAsJsonObject("birds");
			for (String name : birds.keySet()) {
				JsonObject b = birds.getAsJsonObject(name);
				List<Quad> quads = new ArrayList<>();
				for (JsonElement qe : b.getAsJsonArray("quads")) {
					JsonObject q = qe.getAsJsonObject();
					String part = q.get("part").getAsString();
					float[] v = new float[20];
					JsonArray corners = q.getAsJsonArray("v");
					for (int i = 0; i < 4; i++) {
						JsonArray c = corners.get(i).getAsJsonArray();
						for (int k = 0; k < 5; k++) {
							v[i * 5 + k] = c.get(k).getAsFloat();
						}
					}
					quads.add(new Quad(part.equals("wing_left") ? 1 : part.equals("wing_right") ? 2 : 0, v));
				}
				JsonObject h = b.getAsJsonObject("hinge");
				out.put(name, new Mesh(quads, vec(h.getAsJsonArray("wing_left")), vec(h.getAsJsonArray("wing_right"))));
			}
			meshes = out;
		} catch (Exception e) {
			Halloween.LOGGER.warn("No crow model: {}", e.toString());
			missing = true;
		}
		return meshes;
	}

	private static Vector3f vec(JsonArray a) {
		return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	/** Which bird this carrier is (and flying or not), from its item. */
	public static void extract(Display.ItemDisplay entity, float partialTicks) {
		Display.ItemDisplay.ItemRenderState item = entity.itemRenderState();
		ItemStack stack = item == null ? ItemStack.EMPTY : item.itemStack();
		Identifier id = stack.get(DataComponents.ITEM_MODEL);
		if (id == null || !id.getNamespace().equals(Halloween.MOD_ID) || !id.getPath().startsWith("crow")) {
			SEEN.remove(entity.getId());
			return;
		}
		boolean flying = id.getPath().endsWith("flying");
		SEEN.put(entity.getId(), new float[]{0, flying ? 1 : 0, entity.tickCount + partialTicks, entity.getId()});
	}

	public static boolean submit(ItemDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, int light) {
		float[] s = SEEN.get(((HwRenderState) state).halloween$entityId());
		if (s == null) {
			return false;
		}
		Map<String, Mesh> all = meshes();
		Mesh mesh = all == null ? null : all.get("crow");
		if (mesh == null) {
			return true;
		}
		boolean flying = s[1] > 0;
		float t = s[2];
		float seed = s[3] * 0.37f;
		pose.pushPose();
		// (The display has turned it already; its model faces -z.)
		pose.mulPose(Axis.YP.rotationDegrees(180f));
		float wing;
		if (flying) {
			pose.mulPose(Axis.XP.rotationDegrees(-25f));
			wing = Mth.sin(t * 1.6f) * 1.1f + 0.35f;
		} else {
			// Bobbing, pecking at the ground now and then.
			float cycle = (t + seed * 40f) % 90f;
			float peck = cycle < 12f ? Mth.sin(cycle / 12f * Mth.PI) : 0f;
			pose.translate(0f, 0.008f * Mth.sin(t * 0.25f + seed), 0f);
			pose.mulPose(Axis.XP.rotationDegrees(28f * peck));
			pose.mulPose(Axis.YP.rotationDegrees(14f * Mth.sin(t * 0.05f + seed)));
			wing = 0f;
		}
		float beat = wing;
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE, false), (p, out) -> draw(p, out, mesh, beat, light));
		pose.popPose();
		return true;
	}

	private static void draw(PoseStack.Pose p, VertexConsumer out, Mesh mesh, float wing, int light) {
		Matrix4f m = p.pose();
		Quaternionf left = new Quaternionf().rotateZ(-wing);
		Quaternionf right = new Quaternionf().rotateZ(wing);
		Vector3f a = new Vector3f();
		Vector3f b = new Vector3f();
		Vector3f c = new Vector3f();
		Vector3f[] pts = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
		for (Quad q : mesh.quads) {
			for (int i = 0; i < 4; i++) {
				pts[i].set(q.v[i * 5], q.v[i * 5 + 1], q.v[i * 5 + 2]);
				if (q.part == 1) {
					pts[i].sub(mesh.leftHinge);
					left.transform(pts[i]);
					pts[i].add(mesh.leftHinge);
				} else if (q.part == 2) {
					pts[i].sub(mesh.rightHinge);
					right.transform(pts[i]);
					pts[i].add(mesh.rightHinge);
				}
			}
			a.set(pts[1]).sub(pts[0]);
			b.set(pts[2]).sub(pts[0]);
			c.set(a).cross(b);
			if (c.lengthSquared() < 1.0E-10f) {
				c.set(0, 1, 0);
			}
			c.normalize();
			p.transformNormal(c, c);
			for (int i = 0; i < 4; i++) {
				Vector3f w = m.transformPosition(pts[i], new Vector3f());
				out.addVertex(w.x, w.y, w.z).setColor(-1).setUv(q.v[i * 5 + 3], q.v[i * 5 + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
					.setNormal(c.x, c.y, c.z);
			}
		}
	}
}
