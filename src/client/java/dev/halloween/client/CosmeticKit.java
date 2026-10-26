package dev.halloween.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Builds a cosmetic's model out of boxes and paints its texture as it goes (see CosmeticModels): every box gets
 * its own patch of the texture, packed on shelves, painted face by face by a {@link Paint} (the box's base, a
 * second one for what glows in the dark). Positions are the model's own (1/16 of a block, y down, the front -z);
 * rotations in degrees.
 */
final class CosmeticKit {
	enum Face {
		TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK
	}

	/** The colour (ARGB, 0 for none) of a pixel at (x, y) on a face w by h; s is the box's own seed. */
	interface Paint {
		int at(Face f, int x, int y, int w, int h, int s);
	}

	private record Box(PartDefinition parent, String name, float x, float y, float z, int w, int h, int d, CubeDeformation grow, PartPose pose, Paint paint,
		Paint glow, int u, int v) {
	}

	final int tw;
	final int th;
	final MeshDefinition mesh = new MeshDefinition();
	private final List<Box> boxes = new ArrayList<>();
	private int cx;
	private int cy;
	private int row;
	private int n;

	CosmeticKit(int tw, int th) {
		this.tw = tw;
		this.th = th;
	}

	PartDefinition root() {
		return this.mesh.getRoot();
	}

	/** A bone to move things together: its pivot, and how it is turned. */
	PartDefinition bone(PartDefinition parent, String name, float x, float y, float z, float rx, float ry, float rz) {
		return parent.addOrReplaceChild(name, CubeListBuilder.create(),
			PartPose.offsetAndRotation(x, y, z, rx * Mth.DEG_TO_RAD, ry * Mth.DEG_TO_RAD, rz * Mth.DEG_TO_RAD));
	}

	/** A bone with everything on it made smaller or larger (finer detail in the same space). */
	PartDefinition bone(PartDefinition parent, String name, float x, float y, float z, float rx, float ry, float rz, float scale) {
		return parent.addOrReplaceChild(name, CubeListBuilder.create(),
			PartPose.offsetAndRotation(x, y, z, rx * Mth.DEG_TO_RAD, ry * Mth.DEG_TO_RAD, rz * Mth.DEG_TO_RAD).withScale(scale));
	}

	PartDefinition bone(PartDefinition parent, String name, float x, float y, float z) {
		return this.bone(parent, name, x, y, z, 0, 0, 0);
	}

	void box(PartDefinition parent, float x, float y, float z, int w, int h, int d, Paint paint) {
		this.box(parent, x, y, z, w, h, d, 0f, paint, null);
	}

	void box(PartDefinition parent, float x, float y, float z, int w, int h, int d, float grow, Paint paint) {
		this.box(parent, x, y, z, w, h, d, grow, paint, null);
	}

	void box(PartDefinition parent, float x, float y, float z, int w, int h, int d, float grow, Paint paint, Paint glow) {
		this.add(parent, null, x, y, z, w, h, d, grow, grow, grow, PartPose.ZERO, paint, glow);
	}

	/** A box shrunk or grown per axis (a thin strap, a flat plate). */
	void box(PartDefinition parent, float x, float y, float z, int w, int h, int d, float gx, float gy, float gz, Paint paint, Paint glow) {
		this.add(parent, null, x, y, z, w, h, d, gx, gy, gz, PartPose.ZERO, paint, glow);
	}

	/** A box turned about its own pivot (px, py, pz), given relative to that pivot. */
	void turned(PartDefinition parent, float px, float py, float pz, float rx, float ry, float rz, float x, float y, float z, int w, int h, int d, float grow,
		Paint paint, Paint glow) {
		this.add(parent, null, x, y, z, w, h, d, grow, grow, grow,
			PartPose.offsetAndRotation(px, py, pz, rx * Mth.DEG_TO_RAD, ry * Mth.DEG_TO_RAD, rz * Mth.DEG_TO_RAD), paint, glow);
	}

	/** A box as a bone of its own (to be moved by name). */
	PartDefinition named(PartDefinition parent, String name, float px, float py, float pz, float rx, float ry, float rz, float x, float y, float z, int w, int h,
		int d, float grow, Paint paint, Paint glow) {
		Box b = this.add(parent, name, x, y, z, w, h, d, grow, grow, grow,
			PartPose.offsetAndRotation(px, py, pz, rx * Mth.DEG_TO_RAD, ry * Mth.DEG_TO_RAD, rz * Mth.DEG_TO_RAD), paint, glow);
		return b.parent.getChild(b.name);
	}

	private Box add(PartDefinition parent, String name, float x, float y, float z, int w, int h, int d, float gx, float gy, float gz, PartPose pose,
		Paint paint, Paint glow) {
		// A flat plate (a wing, a web) is a box a pixel deep shrunk almost to nothing: both its sides drawn, neither fighting the other.
		if (w == 0) {
			w = 1;
			x -= 0.5f;
			gx = -0.49f;
		}
		if (h == 0) {
			h = 1;
			y -= 0.5f;
			gy = -0.49f;
		}
		if (d == 0) {
			d = 1;
			z -= 0.5f;
			gz = -0.49f;
		}
		CubeDeformation grow = new CubeDeformation(gx, gy, gz);
		int bw = 2 * (w + d);
		int bh = d + h;
		if (this.cx + bw > this.tw) {
			this.cx = 0;
			this.cy += this.row;
			this.row = 0;
		}
		if (this.cy + bh > this.th) {
			throw new IllegalStateException("Cosmetic texture too small");
		}
		String id = name != null ? name : "b" + this.n;
		this.n++;
		Box b = new Box(parent, id, x, y, z, w, h, d, grow, pose, paint, glow, this.cx, this.cy);
		// Made at once, so that it can be a bone to others.
		parent.addOrReplaceChild(id, CubeListBuilder.create().texOffs(b.u, b.v).addBox(x, y, z, w, h, d, grow), pose);
		this.boxes.add(b);
		this.cx += bw;
		this.row = Math.max(this.row, bh);
		return b;
	}

	ModelPart bake() {
		return LayerDefinition.create(this.mesh, this.tw, this.th).bakeRoot();
	}

	/** The texture (glow false) or what of it glows (true; null if nothing does). */
	NativeImage paint(boolean glow) {
		boolean any = false;
		NativeImage img = new NativeImage(this.tw, this.th, true);
		for (Box b : this.boxes) {
			Paint p = glow ? b.glow : b.paint;
			if (p == null) {
				continue;
			}
			any = true;
			int seed = b.u * 31 + b.v * 17;
			int w = b.w;
			int h = b.h;
			int d = b.d;
			this.face(img, p, Face.TOP, b.u + d, b.v, w, d, seed);
			this.face(img, p, Face.BOTTOM, b.u + d + w, b.v, w, d, seed);
			this.face(img, p, Face.RIGHT, b.u, b.v + d, d, h, seed);
			this.face(img, p, Face.FRONT, b.u + d, b.v + d, w, h, seed);
			this.face(img, p, Face.LEFT, b.u + d + w, b.v + d, d, h, seed);
			this.face(img, p, Face.BACK, b.u + 2 * d + w, b.v + d, w, h, seed);
		}
		if (glow && !any) {
			img.close();
			return null;
		}
		return img;
	}

	private void face(NativeImage img, Paint p, Face f, int u, int v, int w, int h, int seed) {
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (u + x < this.tw && v + y < this.th) {
					img.setPixel(u + x, v + y, p.at(f, x, y, w, h, seed));
				}
			}
		}
	}

	// ---------------------------------------------------------------- paints

	static float hash(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 2147483647;
		h = (h ^ h >>> 13) * 1274126177;
		return ((h ^ h >>> 16) & 0xFFFF) / 65535f;
	}

	static int rgb(int argb, float k) {
		int r = Mth.clamp(Math.round((argb >> 16 & 0xFF) * k), 0, 255);
		int g = Mth.clamp(Math.round((argb >> 8 & 0xFF) * k), 0, 255);
		int b = Mth.clamp(Math.round((argb & 0xFF) * k), 0, 255);
		return argb & 0xFF000000 | r << 16 | g << 8 | b;
	}

	static int mix(int a, int b, float k) {
		k = Mth.clamp(k, 0f, 1f);
		int ar = a >> 16 & 0xFF;
		int ag = a >> 8 & 0xFF;
		int ab = a & 0xFF;
		int r = Math.round(ar + ((b >> 16 & 0xFF) - ar) * k);
		int g = Math.round(ag + ((b >> 8 & 0xFF) - ag) * k);
		int bl = Math.round(ab + ((b & 0xFF) - ab) * k);
		int aa = a >>> 24;
		int al = Math.round(aa + ((b >>> 24) - aa) * k);
		return al << 24 | r << 16 | g << 8 | bl;
	}

	/** How near the face's edge (1 on it, 0 two pixels in): for a bevelled look. */
	static float edge(int x, int y, int w, int h) {
		int e = Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
		return w <= 2 || h <= 2 ? 0f : e == 0 ? 1f : e == 1 ? 0.35f : 0f;
	}

	/** A colour with grain, darker edges, lit from above and darker below. */
	static Paint solid(int color, float grain, float bevel) {
		return (f, x, y, w, h, s) -> {
			float k = 1f + (hash(x, y, s) - 0.5f) * 2f * grain;
			k -= bevel * edge(x, y, w, h);
			k *= switch (f) {
				case TOP -> 1.1f;
				case BOTTOM -> 0.72f;
				default -> h > 1 ? 1.06f - 0.16f * y / (h - 1f) : 1f;
			};
			return rgb(0xFF000000 | color, k);
		};
	}

	static Paint solid(int color) {
		return solid(color, 0.06f, 0.12f);
	}

	/** Glows: a colour, brighter in the middle of a face. */
	static Paint light(int color) {
		return (f, x, y, w, h, s) -> {
			float cxk = w > 1 ? Math.abs(x - (w - 1) / 2f) / (w / 2f) : 0f;
			float cyk = h > 1 ? Math.abs(y - (h - 1) / 2f) / (h / 2f) : 0f;
			float k = 1.15f - 0.35f * Math.max(cxk, cyk);
			return rgb(0xFF000000 | color, k);
		};
	}

	/** Only one face of the box painted (others left as they are by {@code rest}). */
	static Paint onFace(Face which, Paint p, Paint rest) {
		return (f, x, y, w, h, s) -> f == which ? p.at(f, x, y, w, h, s) : rest == null ? 0 : rest.at(f, x, y, w, h, s);
	}

	/** Wood: grain running along the longest side. */
	static Paint wood(int color) {
		return (f, x, y, w, h, s) -> {
			boolean along = h >= w;
			int a = along ? x : y;
			int b = along ? y : x;
			float streak = Mth.sin(a * 2.1f + hash(a, 0, s) * 3f + b * 0.15f) * 0.07f;
			float knot = hash(a / 2, b / 3, s + 7) > 0.93f ? -0.18f : 0f;
			float k = 1f + streak + knot + (hash(x, y, s) - 0.5f) * 0.08f - 0.15f * edge(x, y, w, h);
			k *= f == Face.BOTTOM ? 0.75f : f == Face.TOP ? 1.08f : 1f;
			return rgb(0xFF000000 | color, k);
		};
	}

	/** Metal: brushed, light catching the top edge, rust in patches. */
	static Paint metal(int color, float rust) {
		return (f, x, y, w, h, s) -> {
			float k = 1f + (hash(x, y / 3, s) - 0.5f) * 0.12f;
			if (y == 0 && f != Face.BOTTOM) {
				k += 0.25f;
			}
			k -= 0.12f * edge(x, y, w, h);
			int c = rgb(0xFF000000 | color, k * (f == Face.BOTTOM ? 0.7f : 1f));
			float r = hash(x / 2, y / 2, s + 3) * 0.6f + hash(x, y, s + 5) * 0.4f;
			if (r < rust) {
				c = mix(c, rgb(0xFF7A3A1A, 0.8f + hash(x, y, s + 9) * 0.4f), 0.85f);
			}
			return c;
		};
	}

	/** Cloth: a weave of threads. */
	static Paint cloth(int color, float weave) {
		return (f, x, y, w, h, s) -> {
			float k = 1f + ((x + y) % 2 == 0 ? weave : -weave) + (hash(x, y, s) - 0.5f) * 0.1f;
			k -= 0.1f * edge(x, y, w, h);
			k *= f == Face.BOTTOM ? 0.75f : 1f;
			return rgb(0xFF000000 | color, k);
		};
	}

	/** Fur: dark with lighter tips, in short strands. */
	static Paint fur(int color) {
		return (f, x, y, w, h, s) -> {
			float strand = hash(x, y / 2, s);
			float k = 0.85f + strand * 0.3f;
			k *= f == Face.TOP ? 1.12f : f == Face.BOTTOM ? 0.7f : 1f;
			return rgb(0xFF000000 | color, k);
		};
	}
}
