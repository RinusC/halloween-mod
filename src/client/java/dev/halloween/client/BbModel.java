package dev.halloween.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * A model made in Blockbench (box UV), as tools/bb2entity.pl writes it: its bones become model parts, and its
 * animations are played by sampling their keyframes (linear, Catmull-Rom, step). Blockbench's space is the
 * entity's own (y up, the front towards -z); Minecraft's model space has x and y the other way round, so
 * positions get x and y negated, rotations x and y (cubes are mirrored to keep their textures where they were).
 */
final class BbModel {
	private record Key(float time, float x, float y, float z, String interpolation) {
	}

	private record Channel(ModelPart part, String kind, List<Key> keys) {
	}

	record Animation(float length, boolean loop, List<Channel> channels) {
	}

	final ModelPart root;
	final int textureWidth;
	final int textureHeight;
	private final Map<String, Animation> animations = new HashMap<>();

	private BbModel(ModelPart root, int w, int h) {
		this.root = root;
		this.textureWidth = w;
		this.textureHeight = h;
	}

	/** Loads a model from the mod's resources; bones named in {@code hidden} are left out. */
	static BbModel load(String path, Set<String> hidden) {
		JsonObject json;
		try (InputStream in = BbModel.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("No model at " + path);
			}
			json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Could not read " + path, e);
		}
		JsonArray tex = json.getAsJsonArray("texture");
		int w = tex.get(0).getAsInt();
		int h = tex.get(1).getAsInt();
		MeshDefinition mesh = new MeshDefinition();
		Map<String, PartDefinition> defs = new HashMap<>();
		Map<String, String> parents = new java.util.LinkedHashMap<>();
		Map<String, float[]> pivots = new HashMap<>();
		for (JsonElement e : json.getAsJsonArray("bones")) {
			JsonObject bone = e.getAsJsonObject();
			String name = bone.get("name").getAsString();
			if (hidden.contains(name)) {
				continue;
			}
			String parentName = bone.has("parent") && !bone.get("parent").isJsonNull() ? bone.get("parent").getAsString() : null;
			if (parentName != null && !defs.containsKey(parentName)) {
				// Its parent was left out: so is it.
				continue;
			}
			float[] o = floats(bone.getAsJsonArray("pivot"));
			float[] r = floats(bone.getAsJsonArray("rotation"));
			float[] po = parentName == null ? new float[3] : pivots.get(parentName);
			PartPose pose = parentName == null
				? PartPose.offsetAndRotation(-o[0], 24f - o[1], o[2], rad(-r[0]), rad(-r[1]), rad(r[2]))
				: PartPose.offsetAndRotation(-(o[0] - po[0]), -(o[1] - po[1]), o[2] - po[2], rad(-r[0]), rad(-r[1]), rad(r[2]));
			CubeListBuilder cubes = CubeListBuilder.create();
			for (JsonElement ce : bone.getAsJsonArray("cubes")) {
				JsonObject c = ce.getAsJsonObject();
				float[] from = floats(c.getAsJsonArray("from"));
				float[] to = floats(c.getAsJsonArray("to"));
				float[] uv = floats(c.getAsJsonArray("uv"));
				float inflate = c.get("inflate").getAsFloat();
				boolean mirror = c.get("mirror").getAsBoolean();
				// Mirrored across x into Minecraft's space: the texture mirrored back.
				cubes.texOffs((int) uv[0], (int) uv[1]).mirror(!mirror);
				cubes.addBox(-(to[0] - o[0]), -(to[1] - o[1]), from[2] - o[2], to[0] - from[0], to[1] - from[1], to[2] - from[2], new CubeDeformation(inflate));
			}
			PartDefinition parent = parentName == null ? mesh.getRoot() : defs.get(parentName);
			defs.put(name, parent.addOrReplaceChild(name, cubes, pose));
			parents.put(name, parentName);
			pivots.put(name, o);
		}
		ModelPart root = LayerDefinition.create(mesh, w, h).bakeRoot();
		BbModel model = new BbModel(root, w, h);
		Map<String, ModelPart> parts = new HashMap<>();
		for (Map.Entry<String, String> e : parents.entrySet()) {
			ModelPart parent = e.getValue() == null ? root : parts.get(e.getValue());
			parts.put(e.getKey(), parent.getChild(e.getKey()));
		}
		JsonObject anims = json.getAsJsonObject("animations");
		for (String id : anims.keySet()) {
			JsonObject a = anims.getAsJsonObject(id);
			List<Channel> channels = new ArrayList<>();
			JsonObject bones = a.getAsJsonObject("bones");
			for (String boneName : bones.keySet()) {
				ModelPart part = parts.get(boneName);
				if (part == null) {
					continue;
				}
				JsonObject chans = bones.getAsJsonObject(boneName);
				for (String kind : chans.keySet()) {
					List<Key> keys = new ArrayList<>();
					for (JsonElement ke : chans.getAsJsonArray(kind)) {
						JsonArray k = ke.getAsJsonArray();
						keys.add(new Key(k.get(0).getAsFloat(), k.get(1).getAsFloat(), k.get(2).getAsFloat(), k.get(3).getAsFloat(), k.get(4).getAsString()));
					}
					channels.add(new Channel(part, kind, keys));
				}
			}
			model.animations.put(id, new Animation(a.get("length").getAsFloat(), "loop".equals(a.get("loop").getAsString()), channels));
		}
		return model;
	}

	private static float[] floats(JsonArray a) {
		float[] f = new float[a.size()];
		for (int i = 0; i < f.length; i++) {
			f[i] = a.get(i).getAsFloat();
		}
		return f;
	}

	private static float rad(float deg) {
		return deg * Mth.DEG_TO_RAD;
	}

	Animation animation(String id) {
		return this.animations.get(id);
	}

	/** Lays an animation, at {@code seconds} into it, over the parts' current pose ({@code weight} 1: fully). */
	void apply(String id, float seconds, float weight) {
		Animation a = this.animations.get(id);
		if (a == null || weight <= 0f) {
			return;
		}
		float t = a.loop ? (a.length > 0 ? seconds % a.length : 0) : Math.min(seconds, a.length);
		for (Channel c : a.channels) {
			float[] v = sample(c.keys, t);
			ModelPart p = c.part;
			switch (c.kind) {
				case "rotation" -> {
					p.xRot += rad(-v[0]) * weight;
					p.yRot += rad(-v[1]) * weight;
					p.zRot += rad(v[2]) * weight;
				}
				case "position" -> {
					p.x += -v[0] * weight;
					p.y += -v[1] * weight;
					p.z += v[2] * weight;
				}
				case "scale" -> {
					p.xScale *= Mth.lerp(weight, 1f, v[0]);
					p.yScale *= Mth.lerp(weight, 1f, v[1]);
					p.zScale *= Mth.lerp(weight, 1f, v[2]);
				}
				default -> {
				}
			}
		}
	}

	private static float[] sample(List<Key> keys, float t) {
		int n = keys.size();
		if (n == 1 || t <= keys.getFirst().time) {
			Key k = keys.getFirst();
			return new float[]{k.x, k.y, k.z};
		}
		if (t >= keys.getLast().time) {
			Key k = keys.getLast();
			return new float[]{k.x, k.y, k.z};
		}
		int i = 0;
		while (i < n - 2 && keys.get(i + 1).time <= t) {
			i++;
		}
		Key k0 = keys.get(i);
		Key k1 = keys.get(i + 1);
		float a = (t - k0.time) / Math.max(1.0E-4f, k1.time - k0.time);
		if ("step".equals(k0.interpolation)) {
			return new float[]{k0.x, k0.y, k0.z};
		}
		if ("catmullrom".equals(k0.interpolation) || "catmullrom".equals(k1.interpolation)) {
			Key km = keys.get(Math.max(0, i - 1));
			Key k2 = keys.get(Math.min(n - 1, i + 2));
			return new float[]{catmull(km.x, k0.x, k1.x, k2.x, a), catmull(km.y, k0.y, k1.y, k2.y, a), catmull(km.z, k0.z, k1.z, k2.z, a)};
		}
		return new float[]{Mth.lerp(a, k0.x, k1.x), Mth.lerp(a, k0.y, k1.y), Mth.lerp(a, k0.z, k1.z)};
	}

	private static float catmull(float p0, float p1, float p2, float p3, float t) {
		return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
	}
}
