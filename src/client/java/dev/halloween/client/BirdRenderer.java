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
import java.util.Arrays;
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
 * tools/crow_model.pl), its parts (head, wings, legs, tail) each turning about its own hinge.
 * <ul>
 * <li>Perched, a crow is never quite still: the head snaps from one way to another and holds there, cocks to one
 * side, now and then it pecks; it shifts its weight from foot to foot, lifting one a little; the tail flicks; once
 * in a while it ruffles its wings half open and folds them again.</li>
 * <li>Flying: wings spread and beating (hard at first, taking off, then steadier), folding a little on the
 * upstroke; legs tucked back under it, tail spread, body level, head forward.</li>
 * </ul>
 * Each bird has its own rhythm (from its entity id).
 */
public final class BirdRenderer {
	private static final Identifier TEXTURE = Halloween.id("textures/entity/crow.png");
	private static final String[] PARTS = {"body", "wing_left", "wing_right", "head", "leg_left", "leg_right", "tail"};
	private static final int WING_L = 1;
	private static final int WING_R = 2;
	private static final int HEAD = 3;
	private static final int LEG_L = 4;
	private static final int LEG_R = 5;
	private static final int TAIL = 6;

	private record Quad(int part, float[] v) {
	}

	private record Mesh(List<Quad> quads, Vector3f[] hinges) {
	}

	/** What one bird is doing this frame. */
	private static final class Seen {
		boolean flying;
		float t;
		float flyStart = -1;
		int id;
	}

	private static Mesh mesh;
	private static boolean missing;
	private static final Map<Integer, Seen> SEEN = new HashMap<>();

	private BirdRenderer() {
	}

	private static Mesh mesh() {
		if (mesh != null || missing) {
			return mesh;
		}
		try (InputStream in = BirdRenderer.class.getResourceAsStream("/assets/halloween/models/entity/crow.json")) {
			if (in == null) {
				missing = true;
				return null;
			}
			JsonObject b = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
				.getAsJsonObject("birds").getAsJsonObject("crow");
			List<Quad> quads = new ArrayList<>();
			for (JsonElement qe : b.getAsJsonArray("quads")) {
				JsonObject q = qe.getAsJsonObject();
				float[] v = new float[20];
				JsonArray corners = q.getAsJsonArray("v");
				for (int i = 0; i < 4; i++) {
					JsonArray c = corners.get(i).getAsJsonArray();
					for (int k = 0; k < 5; k++) {
						v[i * 5 + k] = c.get(k).getAsFloat();
					}
				}
				quads.add(new Quad(Math.max(0, Arrays.asList(PARTS).indexOf(q.get("part").getAsString())), v));
			}
			JsonObject h = b.getAsJsonObject("hinge");
			Vector3f[] hinges = new Vector3f[PARTS.length];
			for (int k = 0; k < PARTS.length; k++) {
				hinges[k] = h.has(PARTS[k]) ? vec(h.getAsJsonArray(PARTS[k])) : new Vector3f();
			}
			mesh = new Mesh(quads, hinges);
		} catch (Exception e) {
			Halloween.LOGGER.warn("No crow model: {}", e.toString());
			missing = true;
		}
		return mesh;
	}

	private static Vector3f vec(JsonArray a) {
		return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	/** Which carrier is a crow, and whether it flies (from its item). */
	public static void extract(Display.ItemDisplay entity, float partialTicks) {
		Display.ItemDisplay.ItemRenderState item = entity.itemRenderState();
		ItemStack stack = item == null ? ItemStack.EMPTY : item.itemStack();
		Identifier id = stack.get(DataComponents.ITEM_MODEL);
		if (id == null || !id.getNamespace().equals(Halloween.MOD_ID) || !id.getPath().startsWith("crow")) {
			SEEN.remove(entity.getId());
			return;
		}
		Seen s = SEEN.computeIfAbsent(entity.getId(), k -> new Seen());
		s.id = entity.getId();
		s.t = entity.tickCount + partialTicks;
		s.flying = id.getPath().endsWith("flying");
		if (s.flying && s.flyStart < 0) {
			s.flyStart = s.t;
		}
	}

	public static boolean submit(ItemDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, int light) {
		Seen s = SEEN.get(((HwRenderState) state).halloween$entityId());
		if (s == null) {
			return false;
		}
		Mesh m = mesh();
		if (m == null) {
			return true;
		}
		Quaternionf[] turn = new Quaternionf[PARTS.length];
		for (int k = 0; k < turn.length; k++) {
			turn[k] = new Quaternionf();
		}
		pose.pushPose();
		// (The display has turned it already; its model faces -z.)
		pose.mulPose(Axis.YP.rotationDegrees(180f));
		if (s.flying) {
			flying(pose, turn, s.t - s.flyStart, s.id);
		} else {
			perched(pose, turn, s.t, s.id);
		}
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE, false), (p, out) -> draw(p, out, m, turn, light));
		pose.popPose();
		return true;
	}

	/** A hash of (bird, slot): the same every frame, different for each bird and each moment. */
	private static float noise(int id, int slot) {
		int h = id * 73856093 ^ slot * 19349663;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		h ^= h >>> 15;
		return (h & 0xFFFF) / 65535f;
	}

	/** Eased step between held values: a head that snaps round and holds. */
	private static float held(float t, float every, int id, int salt, float range, float snap) {
		int slot = (int) Math.floor(t / every);
		float into = t - slot * every;
		float from = (noise(id, slot * 7 + salt) - 0.5f) * 2f * range;
		float to = (noise(id, (slot + 1) * 7 + salt) - 0.5f) * 2f * range;
		float k = Mth.clamp((into - (every - snap)) / snap, 0f, 1f);
		return Mth.lerp(k * k * (3 - 2 * k), from, to);
	}

	private static void perched(PoseStack pose, Quaternionf[] turn, float t, int id) {
		float seed = noise(id, 1) * 100f;
		// Breathing.
		pose.translate(0f, 0.004f * Mth.sin(t * 0.3f + seed), 0f);
		// The head: snaps round every second or so and holds; cocks to one side; now and then a peck.
		float yaw = held(t + seed, 22f, id, 1, 70f, 3f);
		float tilt = held(t + seed, 31f, id, 2, 22f, 4f);
		float cycle = (t + seed * 3f) % 140f;
		float peck = cycle < 10f ? Mth.sin(cycle / 10f * Mth.PI) : 0f;
		turn[HEAD].rotateY(yaw * Mth.DEG_TO_RAD).rotateZ(tilt * Mth.DEG_TO_RAD).rotateX(-55f * peck * Mth.DEG_TO_RAD);
		if (peck > 0f) {
			pose.mulPose(Axis.XP.rotationDegrees(-18f * peck));
		}
		// Weight from foot to foot: one lifted a little now and then.
		float step = (t + seed * 5f) % 70f;
		float lift = step < 8f ? Mth.sin(step / 8f * Mth.PI) : 0f;
		boolean left = noise(id, (int) ((t + seed * 5f) / 70f)) < 0.5f;
		turn[left ? LEG_L : LEG_R].rotateX(-35f * lift * Mth.DEG_TO_RAD);
		pose.mulPose(Axis.ZP.rotationDegrees((left ? 3f : -3f) * lift));
		// The tail flicks.
		float flick = (t + seed * 2f) % 50f;
		turn[TAIL].rotateX((flick < 4f ? 18f * Mth.sin(flick / 4f * Mth.PI) : 0f) * Mth.DEG_TO_RAD);
		// Once in a while it ruffles its wings, half open, and folds them back.
		float ruffle = (t + seed * 7f) % 240f;
		float open = ruffle < 14f ? Mth.sin(ruffle / 14f * Mth.PI) : 0f;
		turn[WING_L].rotateZ(-0.9f * open);
		turn[WING_R].rotateZ(0.9f * open);
	}

	private static void flying(PoseStack pose, Quaternionf[] turn, float e, int id) {
		float seed = noise(id, 3) * 6f;
		// Taking off: hard, fast beats nose up; then steadier, level.
		float takeoff = Mth.clamp(1f - e / 14f, 0f, 1f);
		float rate = 1.9f + 0.8f * takeoff;
		float beat = Mth.sin(e * rate + seed);
		pose.mulPose(Axis.XP.rotationDegrees(-20f * takeoff));
		pose.translate(0f, -0.03f * beat, 0f);
		// Spread out to the sides (about a quarter turn), beating about that; a little folded on the way up.
		float spread = 1.35f;
		float stroke = 0.85f * beat;
		float fold = beat > 0 ? 0.25f * beat : 0f;
		turn[WING_L].rotateZ(-(spread + stroke)).rotateY(-fold);
		turn[WING_R].rotateZ(spread + stroke).rotateY(fold);
		// Legs tucked back, tail spread level, head forward and steady.
		turn[LEG_L].rotateX(80f * Mth.DEG_TO_RAD);
		turn[LEG_R].rotateX(80f * Mth.DEG_TO_RAD);
		turn[TAIL].rotateX(-8f * Mth.DEG_TO_RAD + 0.08f * beat);
		turn[HEAD].rotateX((10f * takeoff - 4f * beat) * Mth.DEG_TO_RAD);
	}

	private static void draw(PoseStack.Pose p, VertexConsumer out, Mesh mesh, Quaternionf[] turn, int light) {
		Matrix4f m = p.pose();
		Vector3f a = new Vector3f();
		Vector3f b = new Vector3f();
		Vector3f c = new Vector3f();
		Vector3f[] pts = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
		for (Quad q : mesh.quads) {
			Vector3f hinge = mesh.hinges[q.part];
			for (int i = 0; i < 4; i++) {
				pts[i].set(q.v[i * 5], q.v[i * 5 + 1], q.v[i * 5 + 2]);
				if (q.part != 0) {
					pts[i].sub(hinge);
					turn[q.part].transform(pts[i]);
					pts[i].add(hinge);
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
