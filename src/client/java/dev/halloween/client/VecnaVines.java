package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.halloween.Halloween;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The vines of Vecna's kills (VecnaKillScene says where): living tubes of Upside-Down flesh along a path through the
 * world, thick at the root and pointed at the tip, knotted, sliding along themselves as they move, writhing where they
 * hang free (not where they are wound round someone or run through them), drawn only as far as they have grown. Over the
 * skin, at full brightness, veins of fire pulse from the root to the tip. And the light of his kills: rays out of
 * someone's eyes, a glow up out of a pit.
 */
final class VecnaVines {
	private static final Identifier SKIN = Halloween.id("textures/entity/vecna_vine.png");
	private static final Identifier VEINS = Halloween.id("textures/entity/vecna_vine_glow.png");
	private static final int SIDES = 7;

	/**
	 * One vine: its path (world points, root first), the first {@code free} points of which writhe; its radius at the
	 * root and further on; how much of it has grown (0..1 of its length); how far it writhes; a seed so no two move alike.
	 */
	record Vine(List<Vec3> path, int free, float r0, float r1, float grow, float writhe, float seed) {
	}

	/** A ray of light (rays out of eyes, a glow out of a pit): from, to, width at each end, colour, strength. */
	record Ray(Vec3 from, Vec3 to, float w0, float w1, float r, float g, float b, float a) {
	}

	/** A burning crack lying on the ground: its path, how wide, how bright. */
	record Crack(List<Vec3> path, float width, float a) {
	}

	private VecnaVines() {
	}

	// ---------------------------------------------------------------- paths

	static List<Vec3> bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int n) {
		List<Vec3> out = new ArrayList<>(n + 1);
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			double u = 1 - t;
			out.add(a.scale(u * u * u).add(b.scale(3 * u * u * t)).add(c.scale(3 * u * t * t)).add(d.scale(t * t * t)));
		}
		return out;
	}

	static List<Vec3> line(Vec3 a, Vec3 b, int n) {
		List<Vec3> out = new ArrayList<>(n + 1);
		for (int i = 0; i <= n; i++) {
			out.add(a.lerp(b, i / (double) n));
		}
		return out;
	}

	/**
	 * Wound round someone: an ellipse {@code rx} across them and {@code rz} front to back (they face {@code yaw}), from
	 * height {@code y0} to {@code y1} (either way), {@code turns} times round, starting {@code phase} radians round.
	 */
	static List<Vec3> coil(Vec3 centre, float yaw, double rx, double rz, double y0, double y1, double turns, double phase, int n) {
		List<Vec3> out = new ArrayList<>(n + 1);
		double ya = yaw * Mth.DEG_TO_RAD;
		Vec3 front = new Vec3(-Math.sin(ya), 0, Math.cos(ya));
		Vec3 across = new Vec3(Math.cos(ya), 0, Math.sin(ya));
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			double a = phase + t * turns * Math.PI * 2;
			out.add(new Vec3(centre.x, Mth.lerp(t, y0, y1), centre.z).add(across.scale(Math.cos(a) * rx)).add(front.scale(Math.sin(a) * rz)));
		}
		return out;
	}

	/** Paths one after the other (each next one's first point dropped: it is the last one's end). */
	@SafeVarargs
	static List<Vec3> join(List<Vec3>... parts) {
		List<Vec3> out = new ArrayList<>();
		for (List<Vec3> p : parts) {
			out.addAll(out.isEmpty() ? p : p.subList(1, p.size()));
		}
		return out;
	}

	// ---------------------------------------------------------------- drawing

	static void submit(PoseStack pose, SubmitNodeCollector collector, List<Vine> vines, List<Ray> rays, List<Crack> cracks, float time) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || vines.isEmpty() && rays.isEmpty() && cracks.isEmpty()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		List<Tube> tubes = new ArrayList<>();
		for (Vine v : vines) {
			Tube t = tube(v, cam, time);
			if (t != null) {
				Vec3 mid = v.path.get(v.path.size() / 2);
				BlockPos at = BlockPos.containing(mid);
				// Never quite black: they are made out in the dark (as the crows are).
				int block = Math.max(mc.level.getBrightness(LightLayer.BLOCK, at), 4);
				t.light = LightCoordsUtil.pack(block, mc.level.getBrightness(LightLayer.SKY, at));
				tubes.add(t);
			}
		}
		if (!tubes.isEmpty()) {
			collector.submitCustomGeometry(pose, RenderTypes.entityCutout(SKIN), (p, out) -> {
				for (Tube t : tubes) {
					t.emit(p, out, 1f, false, time);
				}
			});
			collector.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(VEINS), (p, out) -> {
				for (Tube t : tubes) {
					t.emit(p, out, 1.04f, true, time);
				}
			});
		}
		if (!rays.isEmpty() || !cracks.isEmpty()) {
			Vector3f eye = new Vector3f();
			collector.submitCustomGeometry(pose, RenderTypes.lightning(), (p, out) -> {
				Matrix4f m = p.pose();
				for (Ray r : rays) {
					ray(m, out, r.from.subtract(cam), r.to.subtract(cam), eye, r);
				}
				for (Crack c : cracks) {
					crack(m, out, c, cam);
				}
			});
		}
	}

	/** A crack: a flat glowing strip along the ground, a wide dim glow under a thin hot seam, pointed at its far end. */
	private static void crack(Matrix4f m, VertexConsumer out, Crack c, Vec3 cam) {
		for (int pass = 0; pass < 2; pass++) {
			float w = pass == 0 ? c.width * 3.2f : c.width;
			float a = pass == 0 ? c.a * 0.35f : c.a;
			float g = pass == 0 ? 0.12f : 0.38f;
			float b = pass == 0 ? 0.03f : 0.08f;
			for (int i = 0; i + 1 < c.path.size(); i++) {
				Vec3 p0 = c.path.get(i).subtract(cam);
				Vec3 p1 = c.path.get(i + 1).subtract(cam);
				Vec3 along = p1.subtract(p0);
				if (along.lengthSqr() < 1e-6) {
					continue;
				}
				Vec3 side = new Vec3(-along.z, 0, along.x).normalize();
				float k0 = 1f - i / (float) (c.path.size() - 1);
				float k1 = 1f - (i + 1) / (float) (c.path.size() - 1);
				Vec3 s0 = side.scale(w * (0.3f + 0.7f * k0) / 2);
				Vec3 s1 = side.scale(w * (0.3f + 0.7f * k1) / 2);
				Vec3[] q = {p0.subtract(s0), p0.add(s0), p1.add(s1), p1.subtract(s1)};
				for (int face = 0; face < 2; face++) {
					for (int j = 0; j < 4; j++) {
						Vec3 v = q[face == 0 ? j : 3 - j];
						float ka = (face == 0 ? j : 3 - j) < 2 ? k0 : k1;
						out.addVertex(m, (float) v.x, (float) v.y, (float) v.z).setColor(1f, g, b, a * (0.4f + 0.6f * ka));
					}
				}
			}
		}
	}

	/** A vine's rings (camera-relative), ready to be drawn. */
	private static final class Tube {
		final List<Vector3f> centres = new ArrayList<>();
		final List<Vector3f[]> rings = new ArrayList<>();
		final List<Vector3f[]> normals = new ArrayList<>();
		final List<Float> along = new ArrayList<>();
		float seed;
		int light;

		void emit(PoseStack.Pose p, VertexConsumer out, float inflate, boolean veins, float time) {
			Matrix4f m = p.pose();
			Vector3f n = new Vector3f();
			Vector3f w = new Vector3f();
			for (int i = 0; i + 1 < this.rings.size(); i++) {
				Vector3f[] a = this.rings.get(i);
				Vector3f[] b = this.rings.get(i + 1);
				Vector3f[] na = this.normals.get(i);
				Vector3f[] nb = this.normals.get(i + 1);
				Vector3f ca = this.centres.get(i);
				Vector3f cb = this.centres.get(i + 1);
				float sa = this.along.get(i);
				float sb = this.along.get(i + 1);
				// The skin slides along as the vine moves; the fire in the veins runs from the root to the tip.
				float va = sa * 0.8f - time * 0.015f + this.seed;
				float vb = sb * 0.8f - time * 0.015f + this.seed;
				int colA = veins ? veinColour(sa, time, this.seed) : -1;
				int colB = veins ? veinColour(sb, time, this.seed) : -1;
				int light = veins ? LightCoordsUtil.FULL_BRIGHT : this.light;
				for (int j = 0; j < SIDES; j++) {
					int k = (j + 1) % SIDES;
					float u0 = j / (float) SIDES;
					float u1 = (j + 1) / (float) SIDES;
					vertex(out, m, p, ca, a[j], inflate, na[j], u0, va, colA, light, w, n);
					vertex(out, m, p, ca, a[k], inflate, na[k], u1, va, colA, light, w, n);
					vertex(out, m, p, cb, b[k], inflate, nb[k], u1, vb, colB, light, w, n);
					vertex(out, m, p, cb, b[j], inflate, nb[j], u0, vb, colB, light, w, n);
				}
			}
		}
	}

	private static int veinColour(float s, float time, float seed) {
		float pulse = 0.5f + 0.5f * Mth.sin(s * 5f - time * 0.45f + seed * 3f);
		int a = (int) (255 * Mth.clamp(0.3f + 0.7f * pulse * pulse, 0f, 1f));
		return a << 24 | 0xFFFFFF;
	}

	private static void vertex(VertexConsumer out, Matrix4f m, PoseStack.Pose p, Vector3f centre, Vector3f at, float inflate, Vector3f normal, float u, float v,
		int colour, int light, Vector3f w, Vector3f n) {
		w.set(at).sub(centre).mul(inflate).add(centre);
		m.transformPosition(w);
		p.transformNormal(normal, n);
		out.addVertex(w.x, w.y, w.z).setColor(colour).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(n.x, n.y, n.z);
	}

	/** A vine's path writhed, cut to what has grown, and turned into rings round it. */
	private static Tube tube(Vine v, Vec3 cam, float time) {
		int count = v.path.size();
		if (count < 2 || v.grow <= 0.001f) {
			return null;
		}
		// Writhing: sideways waves along the free part, still at its ends.
		List<Vector3f> pts = new ArrayList<>(count);
		float[] s = new float[count];
		for (int i = 0; i < count; i++) {
			Vec3 q = v.path.get(i);
			pts.add(new Vector3f((float) (q.x - cam.x), (float) (q.y - cam.y), (float) (q.z - cam.z)));
			s[i] = i == 0 ? 0 : s[i - 1] + pts.get(i).distance(pts.get(i - 1));
		}
		float freeLen = v.free > 0 && v.free < count ? s[v.free] : v.free >= count ? s[count - 1] : 0;
		if (v.writhe > 0 && freeLen > 0.05f) {
			Vector3f up = new Vector3f(0, 1, 0);
			for (int i = 1; i < Math.min(v.free, count - 1); i++) {
				Vector3f tan = new Vector3f(pts.get(i + 1)).sub(pts.get(i - 1)).normalize();
				Vector3f a = new Vector3f(tan).cross(Math.abs(tan.y) > 0.9f ? new Vector3f(1, 0, 0) : up).normalize();
				Vector3f b = new Vector3f(tan).cross(a).normalize();
				float env = Mth.sin(Mth.PI * s[i] / freeLen);
				float wa = v.writhe * env * Mth.sin(s[i] * 3.1f + time * 0.23f + v.seed * 5f);
				float wb = v.writhe * env * Mth.cos(s[i] * 2.3f - time * 0.17f + v.seed * 7f);
				pts.get(i).add(a.mul(wa)).add(b.mul(wb));
			}
			for (int i = 1; i < count; i++) {
				s[i] = s[i - 1] + pts.get(i).distance(pts.get(i - 1));
			}
		}
		float total = s[count - 1];
		float cut = total * Mth.clamp(v.grow, 0f, 1f);
		if (cut < 0.02f) {
			return null;
		}
		// Up to where it has grown to (the last point moved back onto the path).
		List<Vector3f> c = new ArrayList<>();
		List<Float> cs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			if (s[i] <= cut) {
				c.add(pts.get(i));
				cs.add(s[i]);
			} else {
				float f = (cut - s[i - 1]) / Math.max(1e-4f, s[i] - s[i - 1]);
				c.add(new Vector3f(pts.get(i - 1)).lerp(pts.get(i), f));
				cs.add(cut);
				break;
			}
		}
		if (c.size() < 2) {
			return null;
		}
		Tube t = new Tube();
		t.seed = v.seed;
		// Rings carried along the path without twisting (each one's "up" the last one's, straightened to the path).
		Vector3f normal = null;
		for (int i = 0; i < c.size(); i++) {
			Vector3f tan = i == 0 ? new Vector3f(c.get(1)).sub(c.get(0)) : i == c.size() - 1 ? new Vector3f(c.get(i)).sub(c.get(i - 1))
				: new Vector3f(c.get(i + 1)).sub(c.get(i - 1));
			if (tan.lengthSquared() < 1e-10f) {
				tan.set(0, 1, 0);
			}
			tan.normalize();
			if (normal == null) {
				normal = new Vector3f(tan).cross(Math.abs(tan.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0)).normalize();
			} else {
				normal.sub(new Vector3f(tan).mul(tan.dot(normal)));
				if (normal.lengthSquared() < 1e-8f) {
					normal = new Vector3f(tan).cross(new Vector3f(1, 0, 0)).normalize();
				}
				normal.normalize();
			}
			Vector3f binormal = new Vector3f(tan).cross(normal).normalize();
			float at = cs.get(i);
			float r = radius(v, at, cut, total);
			Vector3f[] ring = new Vector3f[SIDES];
			Vector3f[] norms = new Vector3f[SIDES];
			for (int j = 0; j < SIDES; j++) {
				float a = j * Mth.TWO_PI / SIDES;
				Vector3f d = new Vector3f(normal).mul(Mth.cos(a)).add(new Vector3f(binormal).mul(Mth.sin(a)));
				norms[j] = d;
				ring[j] = new Vector3f(d).mul(r).add(c.get(i));
			}
			t.centres.add(c.get(i));
			t.rings.add(ring);
			t.normals.add(norms);
			t.along.add(at);
		}
		return t;
	}

	/** Thick at the root (swelling where it comes out of him), knotted, tapering to a point at its tip (where it has grown to). */
	private static float radius(Vine v, float at, float cut, float total) {
		float r = Mth.lerp(Math.min(1f, at / Math.max(0.5f, total)), v.r0, v.r1);
		if (at < 0.12f) {
			r *= 1f + 0.5f * (1f - at / 0.12f);
		}
		float knot = Math.max(0f, Mth.sin(at * 19f + v.seed * 11f));
		r *= 1f + 0.22f * knot * knot * knot * knot * knot * knot;
		float tip = Math.min(0.3f, cut * 0.25f);
		if (at > cut - tip) {
			r *= Mth.lerp((at - (cut - tip)) / tip, 1f, 0.12f);
		}
		return r;
	}

	/** A ray of light: a strip from one point to another, widening, turned to the eye, fading to nothing at its end. */
	private static void ray(Matrix4f m, VertexConsumer out, Vec3 from, Vec3 to, Vector3f eye, Ray r) {
		Vector3f p0 = new Vector3f((float) from.x, (float) from.y, (float) from.z);
		Vector3f p1 = new Vector3f((float) to.x, (float) to.y, (float) to.z);
		Vector3f along = new Vector3f(p1).sub(p0);
		Vector3f toEye = new Vector3f(eye).sub(p0);
		Vector3f side = along.cross(toEye, new Vector3f());
		if (side.lengthSquared() < 1e-8f) {
			return;
		}
		side.normalize();
		Vector3f s0 = new Vector3f(side).mul(r.w0 / 2);
		Vector3f s1 = new Vector3f(side).mul(r.w1 / 2);
		for (int pass = 0; pass < 2; pass++) {
			// A soft wide glow, then the bright narrow core.
			float k = pass == 0 ? 1f : 0.35f;
			float a = pass == 0 ? r.a * 0.45f : r.a;
			float cr = pass == 0 ? r.r : Math.min(1f, r.r + 0.1f);
			float cg = pass == 0 ? r.g : Math.min(1f, r.g + 0.12f);
			float cb = pass == 0 ? r.b : Math.min(1f, r.b + 0.05f);
			Vector3f a0 = new Vector3f(s0).mul(k);
			Vector3f a1 = new Vector3f(s1).mul(k);
			for (int face = 0; face < 2; face++) {
				// Both ways round (the far side is not drawn).
				Vector3f[] q = face == 0
					? new Vector3f[]{new Vector3f(p0).sub(a0), new Vector3f(p0).add(a0), new Vector3f(p1).add(a1), new Vector3f(p1).sub(a1)}
					: new Vector3f[]{new Vector3f(p1).sub(a1), new Vector3f(p1).add(a1), new Vector3f(p0).add(a0), new Vector3f(p0).sub(a0)};
				for (int i = 0; i < 4; i++) {
					boolean far = face == 0 ? i >= 2 : i < 2;
					out.addVertex(m, q[i].x, q[i].y, q[i].z).setColor(cr, cg, cb, far ? 0f : a);
				}
			}
		}
	}
}
