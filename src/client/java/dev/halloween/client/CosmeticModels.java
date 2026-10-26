package dev.halloween.client;

import static dev.halloween.client.CosmeticKit.Face;
import static dev.halloween.client.CosmeticKit.cloth;
import static dev.halloween.client.CosmeticKit.edge;
import static dev.halloween.client.CosmeticKit.fur;
import static dev.halloween.client.CosmeticKit.hash;
import static dev.halloween.client.CosmeticKit.light;
import static dev.halloween.client.CosmeticKit.metal;
import static dev.halloween.client.CosmeticKit.mix;
import static dev.halloween.client.CosmeticKit.onFace;
import static dev.halloween.client.CosmeticKit.rgb;
import static dev.halloween.client.CosmeticKit.solid;
import static dev.halloween.client.CosmeticKit.wood;

import dev.halloween.client.CosmeticKit.Paint;
import dev.halloween.client.CosmeticRenderer.CosModel;
import dev.halloween.client.CosmeticRenderer.Motion;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The twenty things to wear (game.Cosmetics), each built from boxes with its texture painted as it is built (see
 * CosmeticKit), and how each one moves. Spaces are the player model's: the head's (its pivot at the neck, the head
 * from y -8 to 0, the face towards -z), the body's (from the neck at y 0 down to the hips at 12, the back at z +2,
 * the shoulders on top of the arms at x +-4..8, +x the left), or the knife hand's (the grip, the blade going down).
 */
final class CosmeticModels {
	enum Anchor {
		HEAD, BODY, HAND
	}

	interface Anim {
		void apply(CosModel m, AvatarRenderState s, Motion mo);
	}

	record Def(String id, Anchor anchor, CosModel model, Identifier texture, Identifier glow, boolean translucent, Function<Motion, Float> alpha,
		Function<Motion, Float> glowLevel) {
	}

	private CosmeticModels() {
	}

	static List<Def> all() {
		return List.of(bandages(), radio(), spider(), witchHat(), broom(), cat(), antlers(), batWings(), ghost(), pumpkinHead(), rustyKnife(), garland(),
			crow(), candySack(), crackedMask(), chain(), cleaver(), pumpkinMask(), cloak(), shadowEyes());
	}

	private static Def def(String id, Anchor a, CosmeticKit k, Anim anim) {
		return def(id, a, k, anim, false, null, null);
	}

	private static Def def(String id, Anchor a, CosmeticKit k, Anim anim, boolean translucent, Function<Motion, Float> alpha, Function<Motion, Float> glow) {
		ModelPart root = k.bake();
		Identifier[] tex = CosmeticRenderer.upload(id, k);
		return new Def(id, a, new CosModel(root, anim), tex[0], tex[1], translucent, alpha, glow);
	}

	/** A flame's flicker, 0..1, never quite the same twice (seeded per wearer). */
	static float flicker(float t, int seed) {
		float a = Mth.sin(t * 0.9f + seed) * 0.5f + Mth.sin(t * 2.3f + seed * 1.7f) * 0.3f + Mth.sin(t * 5.1f + seed * 0.3f) * 0.2f;
		return 0.5f + 0.5f * a;
	}

	private static float rad(float deg) {
		return deg * Mth.DEG_TO_RAD;
	}

	/** One stride's swing, as the legs (0 standing). */
	private static float stride(Motion m, float phase) {
		return Mth.sin(m.walkPos * 0.6662f + phase) * m.walkAmt;
	}

	// ================================================================ survivors

	/** Old linen strips, yellowed, with thread lines across, frayed ends and brown stains. */
	private static Paint linen(int base) {
		return (f, x, y, w, h, s) -> {
			float k = 1f + (y % 2 == 0 ? 0.05f : -0.05f) + (hash(x, y, s) - 0.5f) * 0.14f;
			if (x % 5 == hash(0, y, s) * 5) {
				k -= 0.06f;
			}
			k -= 0.16f * edge(x, y, w, h);
			int c = rgb(0xFF000000 | base, k * (f == Face.BOTTOM ? 0.7f : 1f));
			float stain = hash(x / 3, y / 2, s + 11);
			if (stain > 0.86f) {
				c = mix(c, 0xFF7B6440, (stain - 0.86f) * 5f);
			}
			return c;
		};
	}

	private static Def bandages() {
		CosmeticKit k = new CosmeticKit(128, 128);
		PartDefinition r = k.root();
		Paint l = linen(0xD8CDAE);
		Paint l2 = linen(0xC9BC98);
		// The crown, wrapped over the top.
		k.turned(r, 0, -4, 0, 3, 0, 5, -4, -4.6f, -4, 8, 2, 8, 0.78f, l, null);
		// Round the brow, slanting.
		k.turned(r, 0, -4, 0, -5, 0, -4, -4, -2.2f, -4, 8, 1, 8, 0.85f, l2, null);
		// Across one eye, the other left to look out.
		k.turned(r, 0, -4, 0, 4, 0, 19, -4, -0.6f, -4, 8, 1, 8, 0.82f, l, null);
		k.turned(r, 0, -4, 0, -2, 0, -14, -4, 1.0f, -4, 8, 1, 8, 0.8f, l2, null);
		// Over the mouth and chin.
		k.turned(r, 0, -4, 0, -3, 0, -6, -4, 2.6f, -4, 8, 1, 8, 0.8f, l2, null);
		// Down the neck.
		k.turned(r, 0, 0, 0, 0, 0, 3, -4, -0.8f, -4, 8, 2, 8, 0.35f, l, null);
		// A loose end at the back, two pieces that swing.
		PartDefinition tail = k.bone(r, "tail", 2.5f, -5.6f, 4.9f, 18, 0, -10);
		k.box(tail, -1, 0, -0.5f, 2, 4, 1, 0f, 0f, -0.35f, l, null);
		PartDefinition tail2 = k.bone(tail, "tail2", 0, 4, 0, 12, 0, 6);
		k.box(tail2, -1, 0, -0.5f, 2, 4, 1, -0.1f, 0f, -0.36f, l2, null);
		return def("bandages", Anchor.HEAD, k, (m, s, mo) -> {
			float wind = Mth.clamp(mo.swing, -0.4f, 1.4f);
			m.p("tail").xRot += rad(-wind * 35f - 6f * Mth.sin(mo.t * 0.11f)) + mo.bounce * 0.15f;
			m.p("tail").zRot += mo.side * 0.4f;
			m.p("tail2").xRot += rad(-wind * 30f - 10f * Mth.sin(mo.t * 0.11f - 0.8f)) + mo.bounce * 0.2f;
		});
	}

	private static Def radio() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		int olive = 0x55603A;
		Paint caseP = metal(olive, 0.08f);
		// The face of the set (turned outwards, on the box's back): a speaker grille, the tuning dial, a label.
		Paint face = (f, x, y, w, h, s) -> {
			int c = caseP.at(f, x, y, w, h, s);
			if (y >= 1 && y <= 3 && x >= 1 && x <= w - 2) {
				return (x + y) % 2 == 0 ? 0xFF1A1E12 : rgb(c, 0.7f);
			}
			if (y == 5 && x >= 1 && x <= w - 2) {
				return x == 3 ? 0xFF2A0A04 : 0xFF6A4A16;
			}
			if (y == 7 && x >= 2 && x <= 4) {
				return 0xFFD8D0B8;
			}
			return c;
		};
		Paint dial = (f, x, y, w, h, s) -> f == Face.BACK && y == 5 && x >= 1 && x <= w - 2 && x != 3 ? 0xFFFFB347 : f == Face.BACK && y == 7 && x == w - 2
			? 0xFFFF3020 : 0;
		k.box(r, -3.5f, 1.2f, 2.3f, 7, 9, 3, 0f, onFace(Face.BACK, face, caseP), dial);
		// Corner guards.
		Paint dark = metal(0x2A2E22, 0.15f);
		k.box(r, -3.8f, 0.9f, 2.0f, 1, 9, 1, 0.1f, dark);
		k.box(r, 2.8f, 0.9f, 2.0f, 1, 9, 1, 0.1f, dark);
		k.box(r, -3.8f, 0.9f, 4.6f, 1, 9, 1, 0.1f, dark);
		k.box(r, 2.8f, 0.9f, 4.6f, 1, 9, 1, 0.1f, dark);
		// Knobs on its face.
		Paint chrome = metal(0xC8CCD0, 0f);
		k.box(r, -2.6f, 8.3f, 5.0f, 1, 1, 1, 0.1f, chrome);
		k.box(r, 1.4f, 8.3f, 5.0f, 1, 1, 1, 0.1f, chrome);
		// The carrying handle on top.
		k.box(r, -2f, 0.2f, 3.3f, 1, 1, 1, -0.15f, dark);
		k.box(r, 1f, 0.2f, 3.3f, 1, 1, 1, -0.15f, dark);
		k.box(r, -2f, -0.4f, 3.3f, 4, 1, 1, -0.2f, dark);
		// The straps, over the shoulders and down the front.
		Paint web = cloth(0x3A3828, 0.08f);
		k.box(r, -3.2f, -0.55f, -2.6f, 1, 1, 6, -0.18f, web);
		k.box(r, 2.2f, -0.55f, -2.6f, 1, 1, 6, -0.18f, web);
		k.box(r, -3.2f, -0.2f, -3.0f, 1, 9, 1, 0f, 0f, -0.25f, web, null);
		k.box(r, 2.2f, -0.2f, -3.0f, 1, 9, 1, 0f, 0f, -0.25f, web, null);
		k.box(r, -3.2f, 8.4f, -3.0f, 1, 1, 1, 0.05f, metal(0x9A9070, 0f));
		k.box(r, 2.2f, 8.4f, -3.0f, 1, 1, 1, 0.05f, metal(0x9A9070, 0f));
		// The aerial: a base, two thin rods, a red light at the tip.
		k.box(r, 1.9f, 0.3f, 2.6f, 2, 1, 2, -0.2f, dark);
		PartDefinition ant = k.bone(r, "ant", 2.9f, 0.6f, 3.6f, -6, 0, 8);
		k.box(ant, -0.5f, -9, -0.5f, 1, 9, 1, -0.32f, chrome);
		PartDefinition ant2 = k.bone(ant, "ant2", 0, -8.8f, 0, -3, 0, 2);
		k.box(ant2, -0.5f, -7, -0.5f, 1, 7, 1, -0.37f, chrome);
		k.box(ant2, -0.5f, -7.7f, -0.5f, 1, 1, 1, -0.15f, solid(0x801010), light(0xFF4030));
		return def("radio", Anchor.BODY, k, (m, s, mo) -> {
			float sw = Mth.clamp(mo.swing, -0.5f, 1.5f);
			ModelPart a = m.p("ant");
			a.xRot += rad(sw * 16f) + mo.bounce * 0.12f + rad(2f * Mth.sin(mo.t * 0.13f));
			a.zRot += mo.side * 0.25f;
			ModelPart a2 = m.p("ant2");
			a2.xRot += rad(sw * 14f) + mo.bounce * 0.2f + rad(3f * Mth.sin(mo.t * 0.13f - 0.6f));
			a2.zRot += mo.side * 0.3f;
		}, false, null, mo -> {
			// The dial steady, the light at the tip blinking (both on the one glow texture: a soft pulse).
			return 0.75f + 0.25f * Mth.sin(mo.t * 0.25f);
		});
	}

	private static Def spider() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		// On the outer edge of the left shoulder; everything at half size (finer).
		PartDefinition at = k.bone(r, "at", 6.6f, -0.2f, 0.2f, 0, 0, 0, 0.5f);
		PartDefinition thread = k.bone(at, "thread", 0, -2f, 0);
		k.box(thread, -0.5f, 0, -0.5f, 1, 1, 1, -0.42f, solid(0xE6E8F0, 0f, 0f));
		PartDefinition sp = k.bone(at, "sp", 0, 0, 0);
		Paint black = (f, x, y, w, h, s) -> {
			float k2 = 0.9f + hash(x, y, s) * 0.2f + (f == Face.TOP ? 0.25f : 0f) - 0.2f * edge(x, y, w, h);
			return rgb(0xFF1A1418, k2);
		};
		// The abdomen, round, with a red hourglass on its back.
		Paint abdomen = (f, x, y, w, h, s) -> {
			int c = black.at(f, x, y, w, h, s);
			if (f == Face.TOP) {
				float cx = Math.abs(x - (w - 1) / 2f);
				float cy = y - (h - 1) / 2f;
				if (cx <= Math.abs(cy) * 0.8f + 0.3f && Math.abs(cy) <= 2f) {
					return rgb(0xFFC0201A, 0.9f + hash(x, y, s) * 0.2f);
				}
				if ((x + y) % 5 == 0) {
					return rgb(c, 1.8f);
				}
			}
			return c;
		};
		k.box(sp, -2.5f, -5.5f, 0.2f, 5, 4, 6, 0f, abdomen);
		k.box(sp, -2f, -6f, 0.7f, 4, 5, 5, 0f, abdomen);
		// The head, eight red eyes that glow.
		Paint eyes = (f, x, y, w, h, s) -> f == Face.FRONT && y <= 1 && x % 1 == 0 && (x + y) % 2 == 0 ? 0xFFFF2A1A : 0;
		k.box(sp, -1.5f, -4f, -3.2f, 3, 3, 4, 0f, onFace(Face.FRONT, (f, x, y, w, h, s) -> y <= 1 && (x + y) % 2 == 0 ? 0xFF600808 : black.at(f, x, y, w, h, s),
			black), eyes);
		// Fangs.
		k.box(sp, -1.2f, -1.6f, -3.6f, 1, 1, 1, -0.2f, solid(0x3A2A20));
		k.box(sp, 0.2f, -1.6f, -3.6f, 1, 1, 1, -0.2f, solid(0x3A2A20));
		// Eight legs, each in two pieces.
		Paint leg = (f, x, y, w, h, s) -> rgb(x % 3 == 0 ? 0xFF3A2A28 : 0xFF161214, 0.9f + hash(x, y, s) * 0.2f);
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < 4; i++) {
				String n = "leg" + (side < 0 ? "r" : "l") + i;
				PartDefinition up = k.bone(sp, n, side * 1.2f, -2.6f, -2.2f + i * 1.3f, 0, side * (-35 + i * 24), side * -38);
				k.box(up, side > 0 ? 0 : -4, -0.5f, -0.5f, 4, 1, 1, -0.28f, leg);
				PartDefinition low = k.bone(up, n + "b", side * 4f, 0, 0, 0, 0, side * 82);
				k.box(low, side > 0 ? 0 : -5, -0.5f, -0.5f, 5, 1, 1, -0.33f, leg);
			}
		}
		return def("spider", Anchor.BODY, k, (m, s, mo) -> {
			// Sits a while, lets itself down on its thread beside the arm, dangles, climbs back.
			float c = (mo.t + mo.seed * 37) % 240f;
			float drop;
			if (c < 140) {
				drop = 0;
			} else if (c < 170) {
				drop = CharacterSelectScreen.ease((c - 140) / 30f);
			} else if (c < 205) {
				drop = 1;
			} else {
				drop = 1 - CharacterSelectScreen.ease((c - 205) / 35f);
			}
			float len = drop * 22f;
			float out = drop * 4.5f;
			ModelPart body = m.p("sp");
			body.x += out;
			body.y += len + drop * Mth.sin(mo.t * 0.2f) * 0.8f + mo.bounce * 0.6f * drop;
			body.yRot += drop * Mth.sin(mo.t * 0.05f) * 0.9f;
			body.zRot += drop * (mo.side * 0.3f);
			body.xRot += drop * Mth.clamp(mo.swing, -0.5f, 1.2f) * 0.5f;
			ModelPart th = m.p("thread");
			th.visible = drop > 0.02f;
			float dist = (float) Math.sqrt(out * out + (len + 2) * (len + 2));
			th.yScale = dist;
			th.zRot = -(float) Math.atan2(out, len + 2);
			// Legs: still and tucked sitting (twitching now and then), paddling while it hangs.
			for (int side = -1; side <= 1; side += 2) {
				for (int i = 0; i < 4; i++) {
					String n = "leg" + (side < 0 ? "r" : "l") + i;
					float twitch = hash((int) (mo.t / 7), i * 2 + side, mo.seed) > 0.85f ? 0.25f : 0f;
					float paddle = drop * Mth.sin(mo.t * 0.6f + i * 1.6f + side) * 0.35f;
					m.p(n).zRot += side * (paddle + twitch * (1 - drop));
					m.p(n).yRot += side * paddle * 0.5f;
					m.p(n + "b").zRot += side * (paddle * 0.6f);
				}
			}
		}, false, null, mo -> 0.85f + 0.15f * Mth.sin(mo.t * 0.3f));
	}

	private static Def witchHat() {
		CosmeticKit k = new CosmeticKit(128, 128);
		PartDefinition r = k.root();
		Paint felt = (f, x, y, w, h, s) -> {
			float k2 = 1f + (hash(x, y, s) - 0.5f) * 0.18f + (hash(x / 2, y / 3, s + 4) - 0.5f) * 0.1f;
			k2 -= 0.15f * edge(x, y, w, h);
			k2 *= f == Face.BOTTOM ? 0.6f : f == Face.TOP ? 1.1f : 1f - 0.12f * (h > 1 ? y / (h - 1f) : 0f);
			return rgb(0xFF2E2238, k2);
		};
		Paint brimEdge = (f, x, y, w, h, s) -> {
			int c = felt.at(f, x, y, w, h, s);
			return f == Face.TOP && edge(x, y, w, h) >= 1f ? rgb(c, 1.25f) : c;
		};
		PartDefinition hat = k.bone(r, "hat", 0, -8.3f, 0, -5, 0, 4);
		// A round brim: three plates crossed.
		k.box(hat, -7, -0.5f, -5, 14, 1, 10, -0.22f, brimEdge);
		k.box(hat, -5, -0.5f, -7, 10, 1, 14, -0.22f, brimEdge);
		k.box(hat, -6, -0.5f, -6, 12, 1, 12, -0.2f, brimEdge);
		// The band, pumpkin orange, and its buckle.
		Paint band = cloth(0xC8621E, 0.06f);
		k.box(hat, -4.5f, -2.2f, -4.5f, 9, 2, 9, -0.02f, band);
		Paint gold = (f, x, y, w, h, s) -> {
			boolean rim = x == 0 || y == 0 || x == w - 1 || y == h - 1;
			return rim ? rgb(0xFFE0B040, 0.9f + hash(x, y, s) * 0.3f) : 0xFF2A1C10;
		};
		k.box(hat, -1.5f, -2.6f, -5.0f, 3, 3, 1, -0.3f, gold);
		// The crown, bending back, in pieces that follow one another.
		PartDefinition c1 = k.bone(hat, "c1", 0, -2f, 0, -7, 0, -3);
		k.box(c1, -3.5f, -3.2f, -3.5f, 7, 4, 7, -0.05f, felt);
		PartDefinition c2 = k.bone(c1, "c2", 0, -3f, 0.2f, -10, 0, 4);
		// A patch sewn on, crosses of thread over it.
		Paint patch = (f, x, y, w, h, s) -> {
			if (f == Face.LEFT && x >= 1 && x <= 4 && y >= 0 && y <= 2) {
				boolean stitch = (x == 1 || x == 4 || y == 0 || y == 2) && (x + y) % 2 == 0;
				return stitch ? 0xFFD8C8A0 : rgb(0xFF5E3A6A, 0.9f + hash(x, y, s) * 0.2f);
			}
			return felt.at(f, x, y, w, h, s);
		};
		k.box(c2, -2.75f, -3.2f, -2.75f, 6, 4, 6, -0.25f, patch);
		PartDefinition c3 = k.bone(c2, "c3", 0, -3f, 0.2f, -14, 0, 6);
		k.box(c3, -2, -3.2f, -2, 4, 4, 4, -0.1f, felt);
		PartDefinition c4 = k.bone(c3, "c4", 0, -3f, 0.1f, -18, 0, 8);
		k.box(c4, -1.5f, -3.2f, -1.5f, 3, 4, 3, -0.3f, felt);
		PartDefinition c5 = k.bone(c4, "c5", 0, -3f, 0f, -22, 0, 10);
		k.box(c5, -1, -2.6f, -1, 2, 3, 2, -0.3f, felt);
		PartDefinition c6 = k.bone(c5, "c6", 0, -2.4f, 0f, -26, 0, 12);
		k.box(c6, -0.5f, -2f, -0.5f, 1, 2, 1, -0.12f, felt);
		return def("witch_hat", Anchor.HEAD, k, (m, s, mo) -> {
			// The tip trails as they run, bobs with the stride, swings out on a turn.
			float sw = Mth.clamp(mo.swing, -0.5f, 1.6f);
			float bob = mo.bounce;
			float idle = Mth.sin(mo.t * 0.06f) * 0.03f;
			String[] seg = {"c1", "c2", "c3", "c4", "c5", "c6"};
			for (int i = 0; i < seg.length; i++) {
				float w = (i + 1) / 6f;
				ModelPart p = m.p(seg[i]);
				p.xRot += -sw * 0.16f * w + bob * 0.09f * w + idle * w + Mth.sin(mo.t * 0.35f - i * 0.6f) * 0.05f * mo.speed * w;
				p.zRot += mo.side * 0.12f * w + Mth.sin(mo.t * 0.08f - i * 0.5f) * 0.02f;
			}
		});
	}

	private static Def broom() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		PartDefinition b = k.bone(r, "broom", 0.3f, 6.2f, 3.15f, 0, 0, 38);
		Paint stick = wood(0x6E4A2A);
		k.box(b, -0.5f, -14, -0.5f, 1, 23, 1, 0.08f, stick);
		k.box(b, -0.5f, -15.4f, -0.5f, 1, 2, 1, -0.05f, wood(0x5A3A20));
		// Twine binding the bristles on.
		Paint twine = (f, x, y, w, h, s) -> rgb(0xFFB09050, (y % 2 == 0 ? 1.08f : 0.86f) + (hash(x, y, s) - 0.5f) * 0.1f);
		k.box(b, -1.5f, 8, -1.5f, 3, 2, 3, 0.05f, twine);
		// The bristles: a bundle, flaring, ragged at the end.
		Paint straw = (f, x, y, w, h, s) -> {
			float streak = hash(x, 0, s) * 0.35f;
			float k2 = 0.8f + streak + (hash(x, y, s) - 0.5f) * 0.1f;
			if (f == Face.BOTTOM) {
				k2 *= 0.7f;
			}
			return rgb(0xFFC9A24C, k2);
		};
		Paint ragged = (f, x, y, w, h, s) -> {
			if (f != Face.TOP && f != Face.BOTTOM && y >= h - 2 && hash(x, 99, s) > (y == h - 1 ? 0.45f : 0.8f)) {
				return 0;
			}
			return straw.at(f, x, y, w, h, s);
		};
		k.box(b, -2, 10, -2, 4, 4, 4, 0.1f, straw);
		k.box(b, -2.5f, 13, -2.5f, 5, 5, 5, 0f, ragged);
		k.box(b, -1.5f, 13.5f, -1.5f, 3, 5, 3, 0.2f, ragged);
		// The rope it hangs from, across the chest.
		Paint rope = (f, x, y, w, h, s) -> rgb(0xFF7A5A34, ((x + y) % 3 == 0 ? 1.15f : 0.92f));
		k.turned(r, 0, 5, -2.55f, 0, 0, -38, -0.5f, -8, -0.5f, 1, 16, 1, -0.22f, rope, null);
		return def("broom", Anchor.BODY, k, (m, s, mo) -> {
			ModelPart p = m.p("broom");
			p.zRot += stride(mo, 0) * 0.06f + mo.side * 0.05f;
			p.y += Math.abs(stride(mo, 0)) * 0.4f + mo.bounce * 0.3f;
			p.xRot += -Mth.clamp(mo.swing, 0f, 1.5f) * 0.05f;
		});
	}

	private static Def cat() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		PartDefinition at = k.bone(r, "at", 6.1f, -0.15f, 0.6f, 0, 0, 0, 0.42f);
		Paint coat = fur(0x1C1A22);
		PartDefinition body = k.bone(at, "body", 0, 0, 0);
		k.box(body, -3.5f, -6, -6, 7, 6, 12, 0f, coat);
		k.box(body, -3, -6.6f, -5, 6, 1, 10, 0f, coat);
		// Paws tucked under the front, lighter at the toes.
		Paint paw = (f, x, y, w, h, s) -> f == Face.FRONT ? rgb(0xFF3A3640, 1f) : coat.at(f, x, y, w, h, s);
		k.box(body, -3.4f, -1.6f, -8f, 3, 2, 4, 0f, paw);
		k.box(body, 0.4f, -1.6f, -8f, 3, 2, 4, 0f, paw);
		// The head: green eyes that glow, a pink nose, ears that twitch.
		PartDefinition head = k.bone(body, "head", 0, -6.5f, -5.2f);
		Paint face = (f, x, y, w, h, s) -> {
			if (f == Face.FRONT && y >= 2 && y <= 3 && (x == 1 || x == 2 || x == w - 2 || x == w - 3)) {
				return x == 2 || x == w - 3 ? 0xFF050505 : 0xFF2C7A2A;
			}
			return coat.at(f, x, y, w, h, s);
		};
		Paint eyeGlow = (f, x, y, w, h, s) -> f == Face.FRONT && y >= 2 && y <= 3 && (x == 1 || x == w - 2) ? 0xFF7CFF5A
			: f == Face.FRONT && y >= 2 && y <= 3 && (x == 2 || x == w - 3) ? 0xFF1A3A10 : 0;
		k.box(head, -3.5f, -5.6f, -5.4f, 7, 6, 6, 0f, face, eyeGlow);
		Paint muzzle = (f, x, y, w, h, s) -> f == Face.FRONT && y == 0 && (x == 1 || x == 2) ? 0xFFC07080 : rgb(0xFF2A2830, 0.95f + hash(x, y, s) * 0.1f);
		k.box(head, -2, -2.2f, -6.4f, 4, 2, 1, 0f, muzzle);
		PartDefinition lid = k.bone(head, "lid", 0, 0, 0);
		k.box(lid, -3.5f, -3.6f, -5.55f, 7, 2, 1, 0f, 0f, -0.45f, coat, null);
		Paint ear = (f, x, y, w, h, s) -> f == Face.FRONT && y >= 1 && x == 1 ? 0xFFB06878 : coat.at(f, x, y, w, h, s);
		PartDefinition earL = k.bone(head, "earL", 2.2f, -5.4f, -2.6f, 0, 0, 14);
		k.box(earL, -1.5f, -2.5f, -0.5f, 3, 3, 1, -0.1f, ear);
		PartDefinition earR = k.bone(head, "earR", -2.2f, -5.4f, -2.6f, 0, 0, -14);
		k.box(earR, -1.5f, -2.5f, -0.5f, 3, 3, 1, -0.1f, ear);
		// Whiskers.
		Paint white = solid(0xD8D8E0, 0f, 0f);
		k.box(head, 1.6f, -1.6f, -6.2f, 3, 1, 1, 0f, -0.45f, -0.45f, white, null);
		k.box(head, -4.6f, -1.6f, -6.2f, 3, 1, 1, 0f, -0.45f, -0.45f, white, null);
		// The tail, in four pieces, curling.
		PartDefinition t = body;
		float[] tz = {6, 3.6f, 3.4f, 3.2f};
		for (int i = 0; i < 4; i++) {
			t = k.bone(t, "t" + i, 0, i == 0 ? -3.2f : 0, tz[i], i == 0 ? -25 : -18, 0, 0);
			k.box(t, -1, -1, -0.3f, 2, 2, 4, i == 3 ? -0.15f : 0f, coat);
		}
		return def("cat", Anchor.BODY, k, (m, s, mo) -> {
			float t2 = mo.t + mo.seed * 13;
			// Looks about, now and then at whatever caught its eye.
			float look = Mth.sin(t2 * 0.021f) * 0.5f + (hash((int) (t2 / 60), 1, mo.seed) - 0.5f) * 0.8f;
			ModelPart hd = m.p("head");
			hd.yRot += look * 0.7f - mo.side * 0.1f;
			hd.xRot += Mth.sin(t2 * 0.033f) * 0.08f + mo.bounce * 0.05f;
			// Blinks.
			m.p("lid").visible = t2 % 97f < 4f;
			// Ears twitch.
			if (hash((int) (t2 / 9), 7, mo.seed) > 0.9f) {
				m.p("earL").zRot += 0.35f;
			}
			if (hash((int) (t2 / 11), 3, mo.seed) > 0.92f) {
				m.p("earR").zRot -= 0.35f;
			}
			// Breathing.
			m.p("body").yScale = 1f + Mth.sin(t2 * 0.1f) * 0.02f;
			m.p("body").y += mo.bounce * 0.4f;
			// The tail swishes, quicker when they run.
			float sw = Mth.sin(t2 * (0.07f + mo.speed * 0.12f));
			for (int i = 0; i < 4; i++) {
				ModelPart p = m.p("t" + i);
				p.yRot += sw * 0.25f * (i + 1) / 2f + Mth.sin(t2 * 0.1f - i) * 0.08f;
				p.xRot += -Mth.clamp(mo.swing, 0f, 1.5f) * 0.12f + Mth.sin(t2 * 0.05f + i) * 0.06f;
			}
		}, false, null, mo -> 1f);
	}

	private static Def antlers() {
		CosmeticKit k = new CosmeticKit(128, 128);
		PartDefinition r = k.root();
		Paint bone = (f, x, y, w, h, s) -> {
			float k2 = 0.92f + hash(x, y, s) * 0.16f - 0.1f * edge(x, y, w, h) + (y % 3 == 0 ? -0.05f : 0f);
			return rgb(0xFFC9B48E, k2 * (f == Face.BOTTOM ? 0.75f : 1f));
		};
		Paint burr = (f, x, y, w, h, s) -> rgb(0xFF7A644A, 0.85f + hash(x, y, s) * 0.3f);
		// A web: threads out from a centre, rings round it, the rest open.
		Paint web = (f, x, y, w, h, s) -> {
			if (f != Face.FRONT && f != Face.BACK) {
				return 0;
			}
			float cx = x - w * 0.5f;
			float cy = y - h * 0.15f;
			double ang = Math.atan2(cy, cx);
			double rr = Math.sqrt(cx * cx + cy * cy);
			boolean spoke = Math.abs(Math.sin(ang * 4)) < 0.16;
			boolean ring = Math.abs(rr % 3.2 - 1.6) < 0.35 && rr > 1.5;
			boolean outside = y > h - 1 - Math.abs(cx) * 0.25f;
			if ((spoke || ring) && !outside) {
				return 0xFFE4E8EE;
			}
			return 0;
		};
		for (int side = -1; side <= 1; side += 2) {
			String n = side > 0 ? "l" : "r";
			PartDefinition base = k.bone(r, "a" + n, side * 2.6f, -8.2f, -0.3f, -12, 0, side * 28);
			k.box(base, -1.5f, -0.8f, -1.5f, 3, 1, 3, 0f, burr);
			PartDefinition s1 = k.bone(base, "s1" + n, 0, -0.6f, 0);
			k.box(s1, -1, -5, -1, 2, 5, 2, -0.15f, bone);
			// A tine forward off the first piece.
			k.turned(s1, 0, -3.2f, -0.4f, 52, 0, 0, -0.5f, -4, -0.5f, 1, 4, 1, -0.1f, bone, null);
			PartDefinition s2 = k.bone(s1, "s2" + n, 0, -4.6f, 0, -14, 0, side * 24);
			k.box(s2, -1, -5, -1, 2, 5, 2, -0.3f, bone);
			k.turned(s2, 0, -2.5f, -0.2f, 38, 0, side * -18, -0.5f, -4, -0.5f, 1, 4, 1, -0.18f, bone, null);
			PartDefinition s3 = k.bone(s2, "s3" + n, 0, -4.6f, 0, 18, 0, side * -22);
			k.box(s3, -0.5f, -4, -0.5f, 1, 4, 1, -0.12f, bone);
			k.turned(s3, 0, -1.8f, 0, -30, 0, side * 30, -0.5f, -3, -0.5f, 1, 3, 1, -0.2f, bone, null);
			// Cobwebs between the tines (on the left one, and a ragged sheet on the right).
			if (side > 0) {
				PartDefinition w = k.bone(s1, "web" + n, 0, -6.5f, -1.5f, 25, 90, 0, 0.5f);
				k.box(w, -8, -3, 0, 16, 14, 0, 0f, web);
			} else {
				// Strung between the two, over the head.
				PartDefinition w = k.bone(r, "web" + n, 0, -12.5f, -0.4f, -8, 0, 0, 0.5f);
				k.box(w, -13, -2, 0, 26, 12, 0, 0f, web);
			}
		}
		return def("antlers", Anchor.HEAD, k, (m, s, mo) -> {
			float sw = Mth.clamp(mo.swing, 0f, 1.5f);
			m.p("webl").xRot += -sw * 0.25f + Mth.sin(mo.t * 0.09f) * 0.08f + mo.bounce * 0.05f;
			m.p("webr").xRot += -sw * 0.3f + Mth.sin(mo.t * 0.08f + 1) * 0.1f + mo.bounce * 0.06f;
			m.p("webr").zRot += mo.side * 0.15f;
		});
	}

	private static Def batWings() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		for (int side = -1; side <= 1; side += 2) {
			String n = side > 0 ? "l" : "r";
			final int sd = side;
			// Membrane: dark, veined, its lower edge scalloped between the fingers.
			Function<Boolean, Paint> membrane = outer -> (f, x, y, w, h, s) -> {
				if (f != Face.FRONT && f != Face.BACK) {
					return 0;
				}
				int xx = f == Face.BACK ? w - 1 - x : x;
				// From the root outward (mirrored for the right wing).
				float u = sd > 0 ? xx / (float) (w - 1) : 1 - xx / (float) (w - 1);
				float bottom = outer ? (h - 1) * (1f - u * 0.75f) : h - 1 - 0.3f;
				float scallop = Math.abs(Mth.sin(u * Mth.PI * (outer ? 2.5f : 2f))) * 2.6f;
				if (y > bottom - scallop) {
					return 0;
				}
				float vein = Math.abs((u * 3f) % 1f - 0.5f) < 0.08f ? 0.65f : 1f;
				float k2 = (0.9f + hash(x, y, s) * 0.15f) * vein * (1f - 0.25f * y / h);
				return rgb(0xFF3C2A40, f == Face.BACK ? k2 * 0.8f : k2);
			};
			Paint boneP = solid(0x1A1218, 0.06f, 0.05f);
			PartDefinition w1 = k.bone(r, "w" + n, side * 1.3f, 1.6f, 2.5f, 0, side * -50, side * -18);
			k.box(w1, side > 0 ? 0 : -9, -0.5f, -0.5f, 9, 1, 1, -0.2f, boneP);
			k.box(w1, side > 0 ? 0 : -9, 0, 0, 9, 11, 0, 0f, membrane.apply(false));
			PartDefinition w2 = k.bone(w1, "v" + n, side * 9f, 0, 0, 0, side * -45, side * 10);
			k.box(w2, side > 0 ? 0 : -10, -0.5f, -0.5f, 10, 1, 1, -0.25f, boneP);
			k.box(w2, side > 0 ? 0 : -10, 0, 0, 10, 11, 0, 0f, membrane.apply(true));
			// A claw at the joint.
			k.box(w2, -0.5f, -1.4f, -0.5f, 1, 1, 1, -0.15f, solid(0xD8D0C0));
		}
		return def("bat_wings", Anchor.BODY, k, (m, s, mo) -> {
			// Folded on the back, breathing; spread and beating in the air, a last flap on landing.
			float open = Mth.clamp(mo.air * 1.2f + Math.max(0f, 1f - mo.sinceJump / 18f), 0f, 1f);
			float beat = Mth.sin(mo.t * 0.9f) * open;
			float idle = Mth.sin(mo.t * 0.07f) * 0.06f;
			for (int side = -1; side <= 1; side += 2) {
				String n = side > 0 ? "l" : "r";
				ModelPart w = m.p("w" + n);
				w.yRot += side * (open * 0.7f - idle * 0.3f - Mth.clamp(mo.swing, 0f, 1.5f) * 0.12f);
				w.zRot += side * (beat * 0.55f - open * 0.15f) + idle * 0.2f * side;
				ModelPart v = m.p("v" + n);
				v.yRot += side * (open * 0.75f - idle * 0.4f);
				v.zRot += side * Mth.sin(mo.t * 0.9f - 0.7f) * open * 0.35f;
			}
		});
	}

	private static Def ghost() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		PartDefinition at = k.bone(r, "at", 10f, -7f, 1.5f, 0, -20, 0, 0.55f);
		Paint sheet = (f, x, y, w, h, s) -> {
			float k2 = 0.95f + hash(x, y, s) * 0.08f - 0.06f * edge(x, y, w, h);
			int c = rgb(0xFFEEF2FF, k2);
			return f == Face.BOTTOM ? rgb(c, 0.8f) : c;
		};
		Paint face = (f, x, y, w, h, s) -> {
			if (f == Face.FRONT) {
				boolean eye = (x == 1 || x == w - 2) && (y == 2 || y == 3);
				boolean mouth = y == 5 && x >= w / 2 - 1 && x <= w / 2;
				if (eye || mouth) {
					return 0xFF14141C;
				}
			}
			return sheet.at(f, x, y, w, h, s);
		};
		Paint hem = (f, x, y, w, h, s) -> {
			if (f != Face.TOP && f != Face.BOTTOM && y >= h - 2 && Math.abs(Mth.sin(x * 1.3f + s)) > (y == h - 1 ? 0.35f : 0.85f)) {
				return 0;
			}
			return f == Face.BOTTOM ? 0 : sheet.at(f, x, y, w, h, s);
		};
		PartDefinition body = k.bone(at, "body", 0, 0, 0);
		k.box(body, -2, -9.2f, -2, 4, 1, 4, 0f, sheet);
		k.box(body, -3, -8.4f, -3, 6, 6, 6, 0f, face, null);
		PartDefinition skirt = k.bone(body, "skirt", 0, -2.6f, 0);
		k.box(skirt, -3.5f, 0, -3.5f, 7, 4, 7, 0f, hem);
		PartDefinition armL = k.bone(body, "armL", 3.2f, -4.6f, 0, 0, 0, -30);
		k.box(armL, 0, -0.5f, -0.8f, 2, 2, 2, -0.3f, sheet);
		PartDefinition armR = k.bone(body, "armR", -3.2f, -4.6f, 0, 0, 0, 30);
		k.box(armR, -2, -0.5f, -0.8f, 2, 2, 2, -0.3f, sheet);
		Paint aura = (f, x, y, w, h, s) -> f == Face.BOTTOM ? 0 : 0xFF9CC4FF;
		k.box(body, -3, -8.4f, -3, 6, 6, 6, 0.15f, face, aura);
		return def("ghost", Anchor.BODY, k, (m, s, mo) -> {
			float t = mo.t + mo.seed * 7;
			ModelPart b = m.p("body");
			b.y += Mth.sin(t * 0.08f) * 1.6f - mo.bounce * 0.5f;
			b.x += Mth.sin(t * 0.031f) * 0.8f;
			b.yRot += Mth.sin(t * 0.04f) * 0.35f - mo.side * 0.1f;
			b.xRot += Mth.clamp(mo.swing, 0f, 1.5f) * 0.3f;
			b.zRot += Mth.sin(t * 0.06f) * 0.08f;
			m.p("skirt").xRot += Mth.sin(t * 0.12f) * 0.08f + Mth.clamp(mo.swing, 0f, 1.5f) * 0.25f;
			m.p("armL").zRot += Mth.sin(t * 0.15f) * 0.3f;
			m.p("armR").zRot -= Mth.sin(t * 0.15f + 1.2f) * 0.3f;
		}, true, mo -> {
			// See-through, wavering, sometimes gone for a moment.
			float t = mo.t + mo.seed * 7;
			float a = 0.62f + 0.18f * flicker(t * 0.5f, mo.seed);
			if (hash((int) (t / 6), 5, mo.seed) > 0.93f) {
				a *= 0.15f;
			}
			return a;
		}, mo -> 0.35f + 0.25f * flicker(mo.t * 0.4f, mo.seed + 3));
	}

	/** Pumpkin rind: ribbed, darker in the grooves, a sheen on top, flecks. */
	private static Paint rind(int base) {
		return (f, x, y, w, h, s) -> {
			float groove = f == Face.TOP || f == Face.BOTTOM ? 1f : 0.86f + 0.14f * Mth.cos(x * Mth.TWO_PI / 4.5f);
			float k2 = groove * (0.94f + hash(x, y, s) * 0.12f);
			if (f != Face.TOP && f != Face.BOTTOM && h > 1) {
				float v = y / (h - 1f);
				k2 *= 1.08f - 0.25f * v * v;
			}
			k2 *= f == Face.TOP ? 1.05f : f == Face.BOTTOM ? 0.6f : 1f;
			return rgb(0xFF000000 | base, k2);
		};
	}

	/** A carved face on a front w by h: is this pixel cut out? (Two slanted eyes, a nose, a toothy grin.) */
	private static boolean carved(int x, int y, int w, int h) {
		float u = x / (w - 1f);
		float v = y / (h - 1f);
		// Eyes: triangles, points up.
		for (float ex : new float[] {0.27f, 0.73f}) {
			float dx = Math.abs(u - ex);
			if (v > 0.22f && v < 0.43f && dx < (v - 0.22f) * 0.75f + 0.02f) {
				return true;
			}
		}
		// The nose.
		if (v > 0.47f && v < 0.56f && Math.abs(u - 0.5f) < (v - 0.47f) * 0.7f + 0.02f) {
			return true;
		}
		// The grin, curving up at the ends, with two teeth left in.
		float mouthTop = 0.64f - 0.12f * (float) Math.pow(Math.abs(u - 0.5f) * 2.4f, 2);
		float mouthBot = 0.80f - 0.10f * (float) Math.pow(Math.abs(u - 0.5f) * 2.4f, 2);
		if (u > 0.12f && u < 0.88f && v > mouthTop && v < mouthBot) {
			boolean tooth = (Math.abs(u - 0.38f) < 0.04f && v < mouthTop + 0.07f) || (Math.abs(u - 0.62f) < 0.04f && v > mouthBot - 0.07f);
			return !tooth;
		}
		return false;
	}

	private static boolean nearCarved(int x, int y, int w, int h) {
		return carved(x + 1, y, w, h) || carved(x - 1, y, w, h) || carved(x, y + 1, w, h) || carved(x, y - 1, w, h);
	}

	private static Def pumpkinHead() {
		CosmeticKit k = new CosmeticKit(256, 128);
		PartDefinition r = k.root();
		// Everything at half size, for a finely carved face.
		PartDefinition p = k.bone(r, "p", 0, 0.6f, 0, 0, 0, 0, 0.5f);
		Paint orange = rind(0xE07818);
		Paint carvedFace = (f, x, y, w, h, s) -> {
			if (f == Face.FRONT) {
				if (carved(x, y, w, h)) {
					return 0xFF2A0C02;
				}
				if (nearCarved(x, y, w, h)) {
					return rgb(0xFFF0C070, 0.9f + hash(x, y, s) * 0.15f);
				}
			}
			return orange.at(f, x, y, w, h, s);
		};
		Paint carvedGlow = (f, x, y, w, h, s) -> {
			if (f == Face.FRONT && carved(x, y, w, h)) {
				// Hottest in the middle of each hole.
				boolean deep = carved(x + 1, y, w, h) && carved(x - 1, y, w, h) && carved(x, y + 1, w, h) && carved(x, y - 1, w, h);
				return deep ? 0xFFFFE27A : 0xFFFF9A2A;
			}
			return 0;
		};
		k.box(p, -10, -19, -10, 20, 18, 20, 0f, rind(0xD8701A));
		k.box(p, -11, -18, -8, 22, 16, 16, 0f, rind(0xE47C1C));
		k.box(p, -8, -18, -11, 16, 16, 22, 0f, carvedFace, carvedGlow);
		k.box(p, -8, -20, -8, 16, 1, 16, 0f, rind(0xC86414));
		// The stem, curled, and a leaf.
		Paint stemP = (f, x, y, w, h, s) -> rgb(0xFF5A5A26, 0.85f + hash(x, y, s) * 0.3f - 0.15f * (y % 3 == 0 ? 1 : 0));
		PartDefinition stem = k.bone(p, "stem", 0, -19.5f, 0, -8, 0, 12);
		k.box(stem, -1.5f, -5, -1.5f, 3, 6, 3, 0f, stemP);
		k.turned(stem, 0, -4.6f, 0, 0, 0, -60, -1, -4, -1, 2, 4, 2, 0f, stemP, null);
		PartDefinition leaf = k.bone(p, "leaf", 2.5f, -19.6f, 1.5f, -20, 35, -15);
		Paint leafP = (f, x, y, w, h, s) -> {
			if (f != Face.TOP && f != Face.BOTTOM) {
				return 0;
			}
			float u = x / (w - 1f) - 0.5f;
			float v = y / (h - 1f);
			if (Math.abs(u) > 0.5f * Mth.sin(v * Mth.PI) + 0.05f) {
				return 0;
			}
			return Math.abs(u) < 0.06f ? 0xFF6A8A30 : rgb(0xFF3E6A22, 0.85f + hash(x, y, s) * 0.3f);
		};
		k.box(leaf, 0, 0, -3, 7, 0, 7, 0f, leafP);
		return def("pumpkin_head", Anchor.HEAD, k, (m, s, mo) -> {
			m.p("leaf").xRot += Mth.sin(mo.t * 0.09f) * 0.1f - Mth.clamp(mo.swing, 0f, 1.5f) * 0.3f + mo.bounce * 0.1f;
			m.p("stem").zRot += mo.side * 0.05f;
		}, false, null, mo -> 0.55f + 0.45f * flicker(mo.t * 0.45f, mo.seed));
	}

	// ================================================================ Michael

	private static Def rustyKnife() {
		CosmeticKit k = new CosmeticKit(64, 64);
		PartDefinition r = k.root();
		PartDefinition kn = k.bone(r, "k", 0, 0, 0, 0, 0, 0, 0.5f);
		Paint handle = (f, x, y, w, h, s) -> {
			boolean rivet = (f == Face.RIGHT || f == Face.LEFT) && x == w / 2 && (y == 2 || y == h - 3);
			return rivet ? rgb(0xFFB8B0A0, 0.9f + hash(x, y, s) * 0.2f) : wood(0x3E2616).at(f, x, y, w, h, s);
		};
		k.box(kn, -1, -5, -1.5f, 2, 9, 3, 0.2f, handle);
		k.box(kn, -1.25f, 4, -2, 2, 1, 4, 0f, metal(0x8A8478, 0.3f));
		// The blade, thin, its edge forward, rust eating it from the spine; dried blood at the point.
		Paint blade = (f, x, y, w, h, s) -> {
			int c = metal(0xA8AAAE, 0.42f).at(f, x, y, w, h, s);
			if ((f == Face.RIGHT || f == Face.LEFT) && (f == Face.RIGHT ? x == 0 : x == w - 1)) {
				c = mix(c, 0xFFE0E4E8, 0.5f);
			}
			if (y > h - 5 && hash(x, y, s + 21) > 0.5f) {
				c = mix(c, 0xFF4A0806, 0.75f);
			}
			return c;
		};
		k.box(kn, -0.5f, 5, -2.5f, 1, 14, 5, -0.15f, 0f, 0f, blade, null);
		k.box(kn, -0.5f, 19, -2.5f, 1, 3, 4, -0.15f, 0f, -0.2f, blade, null);
		k.box(kn, -0.5f, 22, -2.6f, 1, 2, 2, -0.16f, 0f, -0.1f, blade, null);
		return def("rusty_knife", Anchor.HAND, k, null);
	}

	private static Def garland() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		Paint leather = (f, x, y, w, h, s) -> {
			int c = solid(0x2C1E16, 0.08f, 0.15f).at(f, x, y, w, h, s);
			return f != Face.TOP && f != Face.BOTTOM && y == h / 2 && x % 2 == 0 ? rgb(c, 1.6f) : c;
		};
		k.box(r, -5, 10, -3, 10, 2, 6, -0.05f, leather);
		Paint brass = (f, x, y, w, h, s) -> x == 0 || y == 0 || x == w - 1 || y == h - 1 ? rgb(0xFFB89A40, 0.9f + hash(x, y, s) * 0.3f) : 0xFF2C1E16;
		k.box(r, -1.5f, 9.7f, -3.4f, 3, 3, 1, -0.2f, brass);
		// Little pumpkins on strings round the belt, each with a face that glows.
		float[][] at = {{-3.2f, -3.1f, 0}, {3.2f, -3.1f, 0}, {5.2f, 0.2f, -90}, {-5.2f, 0.2f, 90}, {0f, 3.2f, 180}, {-2.8f, 3.2f, 180}};
		Paint twine = solid(0xA08850, 0.1f, 0f);
		for (int i = 0; i < at.length; i++) {
			PartDefinition b = k.bone(r, "p" + i, at[i][0], 11.6f, at[i][1], 0, at[i][2], 0);
			PartDefinition sw = k.bone(b, "s" + i, 0, 0, 0);
			k.box(sw, -0.5f, 0, -0.5f, 1, 2, 1, -0.38f, twine);
			Paint face = (f, x, y, w, h, s) -> f == Face.FRONT && (y == 0 && (x == 0 || x == w - 1) || y == 2 && x >= 0) ? 0xFF2A0C02
				: rind(0xE07818).at(f, x, y, w, h, s);
			Paint glow = (f, x, y, w, h, s) -> f == Face.FRONT && (y == 0 && (x == 0 || x == w - 1) || y == 2) ? 0xFFFFA030 : 0;
			k.box(sw, -1.5f, 1.8f, -1.5f, 3, 3, 3, -0.05f, face, glow);
			k.box(sw, -0.5f, 1.2f, -0.5f, 1, 1, 1, -0.25f, solid(0x4A5A22));
		}
		return def("garland", Anchor.BODY, k, (m, s, mo) -> {
			for (int i = 0; i < 6; i++) {
				ModelPart p = m.p("s" + i);
				p.xRot += stride(mo, i * 0.9f) * 0.5f - Mth.clamp(mo.swing, 0f, 1.5f) * 0.4f + mo.bounce * 0.15f;
				p.zRot += Mth.sin(mo.t * 0.07f + i) * 0.06f + mo.side * 0.3f;
			}
		}, false, null, mo -> 0.6f + 0.4f * flicker(mo.t * 0.3f, mo.seed));
	}

	private static Def crow() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		PartDefinition at = k.bone(r, "at", 6.2f, -0.6f, 0.4f, 0, 0, 0, 0.45f);
		// Black feathers with a blue-purple sheen, in rows.
		Paint feathers = (f, x, y, w, h, s) -> {
			float k2 = 0.85f + hash(x, y, s) * 0.2f + (y % 3 == 0 ? -0.08f : 0f);
			int c = rgb(0xFF17161E, k2 * (f == Face.BOTTOM ? 0.7f : 1f));
			if (f == Face.TOP && hash(x, y / 2, s + 2) > 0.75f) {
				c = mix(c, 0xFF3A3A6A, 0.5f);
			}
			return c;
		};
		PartDefinition body = k.bone(at, "body", 0, -3, 0, 22, 0, 0);
		k.box(body, -2.5f, -3, -4, 5, 5, 8, 0f, feathers);
		// Wings folded along its sides.
		PartDefinition wl = k.bone(body, "wl", 2.6f, -2.5f, -2.5f);
		k.box(wl, -0.5f, 0, 0, 1, 4, 8, 0f, feathers);
		PartDefinition wr = k.bone(body, "wr", -2.6f, -2.5f, -2.5f);
		k.box(wr, -0.5f, 0, 0, 1, 4, 8, 0f, feathers);
		// Its tail fanned behind and down.
		PartDefinition tail = k.bone(body, "tail", 0, -1.5f, 3.6f, 20, 0, 0);
		k.box(tail, -2, -0.5f, 0, 4, 1, 6, 0f, feathers);
		// Feet gripping the shoulder.
		Paint grey = solid(0x3A3A40, 0.05f, 0f);
		k.box(at, -1.8f, -1.6f, -0.5f, 1, 2, 1, -0.2f, grey);
		k.box(at, 0.8f, -1.6f, -0.5f, 1, 2, 1, -0.2f, grey);
		// Head: an eye that catches the light (a faint red), a heavy beak that opens.
		PartDefinition head = k.bone(at, "head", 0, -6.2f, -3.2f);
		Paint headP = (f, x, y, w, h, s) -> (f == Face.LEFT || f == Face.RIGHT) && y == 1 && x == 1 ? 0xFF8A2010 : feathers.at(f, x, y, w, h, s);
		Paint eye = (f, x, y, w, h, s) -> (f == Face.LEFT || f == Face.RIGHT) && y == 1 && x == 1 ? 0xFFFF4020 : 0;
		k.box(head, -2, -4, -3, 4, 4, 4, 0f, headP, eye);
		Paint beakP = solid(0x26262C, 0.08f, 0.1f);
		PartDefinition bt = k.bone(head, "beak", 0, -2.2f, -3);
		k.box(bt, -1, -0.6f, -3, 2, 1, 3, 0f, beakP);
		PartDefinition bb = k.bone(head, "jaw", 0, -1.4f, -3);
		k.box(bb, -0.8f, -0.3f, -2.4f, 2, 1, 3, -0.2f, beakP);
		return def("crow", Anchor.BODY, k, (m, s, mo) -> {
			float t = mo.t + mo.seed * 11;
			// The head turns in jerks, holding each look.
			int beat = (int) (t / 26);
			float target = (hash(beat, 1, mo.seed) - 0.5f) * 1.8f;
			float before = (hash(beat - 1, 1, mo.seed) - 0.5f) * 1.8f;
			float k2 = Math.min(1f, (t % 26) / 3f);
			ModelPart hd = m.p("head");
			hd.yRot += Mth.lerp(k2, before, target);
			hd.xRot += (hash(beat, 2, mo.seed) - 0.5f) * 0.4f;
			// A caw now and then: head up, beak open.
			float caw = (t % 180f);
			if (caw < 14f) {
				float o = Mth.sin(caw / 14f * Mth.PI);
				hd.xRot -= o * 0.5f;
				m.p("jaw").xRot += o * 0.6f;
			}
			// Wings open a little for balance when he moves, and a ruffle now and then.
			float ruffle = t % 230f < 10f ? Mth.sin(t * 1.4f) * 0.4f : 0f;
			float bal = Mth.clamp(mo.speed, 0f, 1f) * 0.35f + Math.abs(mo.side) * 0.3f + mo.air * 0.6f;
			m.p("wl").zRot -= bal + ruffle + Math.abs(stride(mo, 0)) * 0.1f;
			m.p("wr").zRot += bal + ruffle + Math.abs(stride(mo, 0)) * 0.1f;
			m.p("body").xRot += -Mth.clamp(mo.swing, 0f, 1.5f) * 0.12f + mo.bounce * 0.05f;
			m.p("tail").xRot += Mth.sin(t * 0.05f) * 0.05f + mo.bounce * 0.1f;
		}, false, null, mo -> 0.6f);
	}

	private static Def candySack() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		Paint burlap = (f, x, y, w, h, s) -> {
			float k2 = 1f + ((x % 2 == 0) != (y % 2 == 0) ? 0.08f : -0.08f) + (hash(x, y, s) - 0.5f) * 0.14f;
			if (hash(x / 3, y / 3, s + 5) > 0.85f) {
				k2 -= 0.15f;
			}
			k2 -= 0.12f * edge(x, y, w, h);
			return rgb(0xFF8C6E44, k2 * (f == Face.BOTTOM ? 0.7f : 1f));
		};
		PartDefinition sack = k.bone(r, "sack", 0.5f, 1.5f, 2.4f, 6, 0, -8);
		k.box(sack, -3.5f, 1, 0, 7, 8, 4, 0f, burlap);
		k.box(sack, -4, 3, 0.4f, 8, 5, 4, 0f, burlap);
		// A patch, stitched.
		Paint patch = (f, x, y, w, h, s) -> f == Face.BACK ? ((x + y) % 3 == 0 ? 0xFFD8C8A0 : rgb(0xFF5A4A34, 0.9f + hash(x, y, s) * 0.2f)) : 0;
		k.box(sack, -2.5f, 4, 4.2f, 3, 3, 1, -0.4f, patch);
		// The neck, tied with twine; the mouth flaring above.
		Paint twine = (f, x, y, w, h, s) -> rgb(0xFFB59A5E, y % 2 == 0 ? 1.1f : 0.85f);
		k.box(sack, -1.5f, -0.6f, 1, 3, 2, 2, 0f, twine);
		Paint mouth = (f, x, y, w, h, s) -> f != Face.TOP && f != Face.BOTTOM && y == 0 && hash(x, 3, s) > 0.5f ? 0 : burlap.at(f, x, y, w, h, s);
		k.box(sack, -2.5f, -2.6f, 0.4f, 5, 2, 3, 0f, mouth);
		// Wrappers sticking out: bright, twisted at the ends.
		int[] colours = {0xD8202A, 0x2AA84A, 0x8A3AC8, 0xF0C020, 0x2A6AD8};
		float[][] w = {{-1.5f, -3.4f, 1.2f, 30, 0, 20}, {0.6f, -3.8f, 1.6f, -20, 30, -25}, {-0.2f, -3.2f, 2.4f, 10, -40, 50}, {1.4f, -3f, 1f, 40, 10, -60},
			{-1.8f, -2.9f, 2.4f, -30, 20, -10}};
		for (int i = 0; i < w.length; i++) {
			int c = colours[i];
			Paint candy = (f, x, y, w2, h, s) -> rgb(0xFF000000 | ((x + y) % 3 == 0 ? 0xF0F0F0 : c), 0.9f + (y == 0 ? 0.3f : 0f) + hash(x, y, s) * 0.1f);
			k.turned(sack, w[i][0], w[i][1], w[i][2], w[i][3], w[i][4], w[i][5], -1, -0.5f, -0.5f, 2, 1, 1, 0f, candy, null);
			k.turned(sack, w[i][0], w[i][1], w[i][2], w[i][3], w[i][4], w[i][5], -1.8f, -0.5f, -0.5f, 1, 1, 1, -0.1f, candy, null);
			k.turned(sack, w[i][0], w[i][1], w[i][2], w[i][3], w[i][4], w[i][5], 0.8f, -0.5f, -0.5f, 1, 1, 1, -0.1f, candy, null);
		}
		// The rope over his shoulder and across the chest.
		Paint rope = (f, x, y, w2, h, s) -> rgb(0xFF8A6A3A, (x + y) % 3 == 0 ? 1.15f : 0.9f);
		k.box(r, 2.2f, -0.7f, -2.8f, 1, 1, 6, -0.2f, rope);
		k.turned(r, 0, 6, -2.75f, 0, 0, 36, -0.5f, -7.5f, -0.5f, 1, 15, 1, -0.22f, rope, null);
		return def("candy_sack", Anchor.BODY, k, (m, s, mo) -> {
			ModelPart p = m.p("sack");
			p.xRot += -Mth.clamp(mo.swing, 0f, 1.5f) * 0.15f + mo.bounce * 0.08f;
			p.zRot += stride(mo, 0) * 0.05f + mo.side * 0.1f;
			p.y += Math.abs(stride(mo, 0)) * 0.5f;
		});
	}

	private static Def crackedMask() {
		CosmeticKit k = new CosmeticKit(64, 64);
		PartDefinition r = k.root();
		PartDefinition plate = k.bone(r, "plate", 0, 0, 0, 0, 0, 0, 0.5f);
		// Pale and worn, the eyes dark, a crack running down from the brow through one eye, a piece of the cheek gone.
		Paint mask = (f, x, y, w, h, s) -> {
			if (f != Face.FRONT) {
				return f == Face.BACK ? 0 : rgb(0xFFC8C2B2, 0.9f);
			}
			boolean eye = y >= 7 && y <= 8 && (x >= 3 && x <= 6 || x >= 11 && x <= 14);
			if (eye) {
				return 0xFF0A0808;
			}
			// The chip: gone.
			if (x >= 12 && y >= 11 && y <= 14 && (x - 12) + (y - 11) < 5) {
				return 0;
			}
			int c = rgb(0xFFE4DFD0, 0.92f + hash(x, y, s) * 0.1f - 0.08f * y / h);
			if (crack(x, y)) {
				return 0xFF2A1A16;
			}
			if (crack(x - 1, y) || crack(x + 1, y)) {
				c = rgb(c, 0.8f);
			}
			// Old blood down the crack.
			if (x >= 8 && x <= 10 && y > 9 && crack(x - 1, y - 1)) {
				c = mix(c, 0xFF5A0A08, 0.7f);
			}
			return c;
		};
		Paint glow = (f, x, y, w, h, s) -> f == Face.FRONT && crack(x, y) ? 0xFFFF3A20 : 0;
		k.box(plate, -9, -17.2f, -9.9f, 18, 18, 1, 0f, 0f, -0.3f, mask, glow);
		// Two shards standing proud of the crack.
		Paint shard = solid(0xE0DACA, 0.05f, 0.1f);
		k.turned(plate, -1.5f, -11.5f, -9.8f, 0, 12, 18, -1, -1, -0.5f, 2, 2, 1, -0.1f, shard, null);
		k.turned(plate, 1.6f, -6.5f, -9.8f, 0, -10, -24, -1, -1, -0.5f, 2, 3, 1, -0.15f, shard, null);
		return def("cracked_mask", Anchor.HEAD, k, null, false, null, mo -> 0.25f + 0.35f * (0.5f + 0.5f * Mth.sin(mo.t * 0.08f)));
	}

	/** The crack on the mask's 18 by 18 face. */
	private static boolean crack(int x, int y) {
		// Down from the brow, bending, through the right eye, splitting under it.
		int[] main = {6, 6, 7, 7, 8, 7, 7, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13};
		if (y >= 0 && y < 18 && x == main[y]) {
			return true;
		}
		// A branch off to the left under the brow.
		return y >= 3 && y <= 6 && x == 7 - (y - 3) * 2 + (y == 6 ? 1 : 0) || y >= 10 && y <= 13 && x == 10 - (y - 10);
	}

	private static Def chain() {
		CosmeticKit k = new CosmeticKit(128, 64);
		PartDefinition r = k.root();
		PartDefinition c = k.bone(r, "c", 0, 0, 0, 0, 0, 0, 0.5f);
		Paint iron = metal(0x6E7076, 0.32f);
		// A link: a ring, the middle dark.
		Paint link = (f, x, y, w, h, s) -> (f == Face.FRONT || f == Face.BACK) && x > 0 && x < w - 1 && y > 0 && y < h - 1 ? 0 : iron.at(f, x, y, w, h, s);
		// Across his back from the right shoulder down to the left hip, and over the shoulder in front.
		float x0 = -7, y0 = 0.5f;
		float x1 = 7.5f, y1 = 21;
		int n = 11;
		float ang = (float) Math.toDegrees(Math.atan2(x1 - x0, y1 - y0));
		for (int i = 0; i < n; i++) {
			float t = i / (n - 1f);
			float x = Mth.lerp(t, x0, x1);
			float y = Mth.lerp(t, y0, y1);
			if (i % 2 == 0) {
				k.turned(c, x, y, 5.1f, 0, 0, -ang, -1, -1.5f, -0.5f, 2, 3, 1, 0f, link, null);
			} else {
				k.turned(c, x, y, 5.1f, 0, 90, -ang, -1, -1.5f, -0.5f, 2, 3, 1, -0.1f, link, null);
			}
		}
		for (int i = 0; i < 4; i++) {
			k.turned(c, -7, -0.6f, 3.4f - i * 2.6f, 90, i % 2 == 0 ? 0 : 90, 0, -1, -1.5f, -0.5f, 2, 3, 1, 0f, link, null);
		}
		for (int i = 0; i < 3; i++) {
			k.turned(c, -6.5f + i * 1.4f, 1.5f + i * 2.6f, -5.1f, 0, i % 2 == 0 ? 0 : 90, -22, -1, -1.5f, -0.5f, 2, 3, 1, 0f, link, null);
		}
		// The padlock hanging off the middle link.
		PartDefinition lock = k.bone(c, "lock", Mth.lerp(0.5f, x0, x1), Mth.lerp(0.5f, y0, y1) + 1.2f, 5.6f);
		k.box(lock, -1.5f, 0, -0.5f, 3, 3, 1, -0.15f, link);
		Paint body = (f, x, y, w, h, s) -> {
			int col = metal(0x3E3A36, 0.25f).at(f, x, y, w, h, s);
			if ((f == Face.BACK || f == Face.FRONT) && x == w / 2 && (y == 2 || y == 3)) {
				return 0xFF050505;
			}
			return f == Face.TOP && y == 0 ? rgb(col, 1.3f) : col;
		};
		k.box(lock, -2.5f, 2.4f, -1, 5, 5, 2, 0f, body);
		return def("chain", Anchor.BODY, k, (m, s, mo) -> {
			ModelPart l = m.p("lock");
			l.xRot += -Mth.clamp(mo.swing, 0f, 1.5f) * 0.5f + stride(mo, 0) * 0.3f + mo.bounce * 0.2f;
			l.zRot += Mth.sin(mo.t * 0.06f) * 0.08f + mo.side * 0.4f + stride(mo, 1.3f) * 0.2f;
		});
	}

	private static Def cleaver() {
		CosmeticKit k = new CosmeticKit(64, 64);
		PartDefinition r = k.root();
		PartDefinition kn = k.bone(r, "k", 0, 0, 0, 0, 0, 0, 0.5f);
		Paint handle = (f, x, y, w, h, s) -> {
			boolean rivet = (f == Face.RIGHT || f == Face.LEFT) && x == w / 2 && (y == 2 || y == 5 || y == h - 2);
			return rivet ? 0xFFB0A890 : wood(0x2E1C12).at(f, x, y, w, h, s);
		};
		k.box(kn, -1, -6, -1.5f, 2, 10, 3, 0.25f, handle);
		// A heavy square blade, a hole in its corner, the edge bright and nicked, the flat stained.
		Paint blade = (f, x, y, w, h, s) -> {
			boolean side = f == Face.RIGHT || f == Face.LEFT;
			int xx = f == Face.LEFT ? w - 1 - x : x;
			if (side && xx >= w - 4 && xx <= w - 3 && y >= 1 && y <= 2) {
				return 0;
			}
			int c = metal(0x9EA2A8, 0.18f).at(f, x, y, w, h, s);
			if (side && xx <= 1) {
				c = hash(x, y, s) > 0.85f ? 0xFF505458 : mix(c, 0xFFECF0F4, 0.6f);
			}
			if (side && hash(x / 2, y / 2, s + 31) > 0.72f) {
				c = mix(c, 0xFF5A0C08, 0.6f);
			}
			return c;
		};
		k.box(kn, -0.5f, 4, -7, 1, 13, 10, -0.12f, 0f, 0f, blade, null);
		k.box(kn, -0.5f, 4, 1.6f, 1, 13, 2, 0.05f, 0.05f, 0f, metal(0x5E6268, 0.3f), null);
		return def("cleaver", Anchor.HAND, k, null);
	}

	private static Def pumpkinMask() {
		CosmeticKit k = new CosmeticKit(256, 128);
		PartDefinition r = k.root();
		PartDefinition p = k.bone(r, "p", 0, 0, 0, 0, 0, 0, 0.5f);
		Paint orange = rind(0xD26A14);
		Paint face = (f, x, y, w, h, s) -> {
			if (f == Face.FRONT) {
				if (carved(x, y, w, h)) {
					return 0xFF1E0802;
				}
				if (nearCarved(x, y, w, h)) {
					return rgb(0xFFE8B060, 0.9f + hash(x, y, s) * 0.15f);
				}
			}
			return f == Face.BACK ? 0 : orange.at(f, x, y, w, h, s);
		};
		Paint glow = (f, x, y, w, h, s) -> f == Face.FRONT && carved(x, y, w, h) ? (carved(x, y - 1, w, h) ? 0xFFFFE070 : 0xFFFF8A20) : 0;
		// A shell over the front of the head, the face carved in it, lit from inside.
		k.box(p, -9, -18.4f, -10.6f, 18, 18, 4, 0f, face, glow);
		k.box(p, -10, -17.4f, -9.6f, 20, 16, 4, 0f, onFace(Face.BACK, (f, x, y, w, h, s) -> 0, orange));
		// The strap round the back of the head.
		Paint strap = solid(0x2A1A10, 0.08f, 0.1f);
		k.box(p, -9.6f, -11, -6, 19, 2, 16, 0.1f, onFace(Face.FRONT, (f, x, y, w, h, s) -> 0, strap));
		// The candle on top, wax running down, a flame that dances.
		Paint wax = (f, x, y, w, h, s) -> rgb(0xFFEAE0C4, 0.92f + hash(x, y, s) * 0.1f - (y > 1 && hash(x, 0, s) > 0.6f ? -0.06f : 0.05f));
		PartDefinition cnd = k.bone(p, "candle", 2.5f, -18.4f, -4);
		k.box(cnd, -1.5f, -5, -1.5f, 3, 5, 3, 0f, wax);
		k.box(cnd, -2.2f, -0.8f, -2.2f, 4, 1, 4, 0.1f, wax);
		k.box(cnd, 1.2f, -3.5f, -1, 1, 3, 1, -0.1f, wax);
		k.box(cnd, -0.5f, -6, -0.5f, 1, 1, 1, -0.3f, solid(0x1A1410, 0f, 0f));
		PartDefinition flame = k.bone(cnd, "flame", 0, -6, 0);
		Paint fl = (f, x, y, w, h, s) -> {
			if (f != Face.FRONT && f != Face.BACK) {
				return 0;
			}
			float u = Math.abs(x - (w - 1) / 2f) / (w / 2f);
			float v = y / (h - 1f);
			if (u > Mth.sin(Mth.clamp(v, 0f, 1f) * Mth.PI * 0.85f + 0.15f) * 0.95f) {
				return 0;
			}
			return v > 0.7f ? 0xFF6AA0FF : v > 0.45f ? 0xFFFFE890 : 0xFFFFA030;
		};
		k.box(flame, -1.5f, -5, 0, 3, 5, 0, 0f, (f, x, y, w, h, s) -> 0, fl);
		k.turned(flame, 0, 0, 0, 0, 90, 0, -1.5f, -5, 0, 3, 5, 0, 0f, (f, x, y, w, h, s) -> 0, fl);
		return def("pumpkin_mask", Anchor.HEAD, k, (m, s, mo) -> {
			ModelPart f = m.p("flame");
			float fk = flicker(mo.t * 0.8f, mo.seed);
			f.yScale = 0.8f + 0.45f * fk;
			f.xScale = 1f - 0.15f * fk;
			f.xRot += -Mth.clamp(mo.swing, 0f, 1.5f) * 0.5f + Mth.sin(mo.t * 0.4f) * 0.08f;
			f.zRot += mo.side * 0.4f + Mth.sin(mo.t * 0.33f) * 0.06f;
		}, false, null, mo -> 0.55f + 0.45f * flicker(mo.t * 0.8f, mo.seed));
	}

	private static Def cloak() {
		CosmeticKit k = new CosmeticKit(128, 128);
		PartDefinition r = k.root();
		Paint burlap = (f, x, y, w, h, s) -> {
			float k2 = 1f + ((x % 2 == 0) != (y % 2 == 0) ? 0.07f : -0.07f) + (hash(x, y, s) - 0.5f) * 0.16f;
			if (hash(x / 4, y / 3, s + 9) > 0.86f) {
				k2 -= 0.2f;
			}
			if (x % 7 == 3) {
				k2 -= 0.05f;
			}
			k2 -= 0.1f * edge(x, y, w, h);
			k2 *= f == Face.BOTTOM ? 0.7f : 1f;
			return rgb(0xFF5E4C32, k2);
		};
		Paint tattered = (f, x, y, w, h, s) -> {
			if (f != Face.TOP && f != Face.BOTTOM && y >= h - 4 && hash(x, 7, s) * 4 < y - (h - 4) + 0.6f) {
				return 0;
			}
			return f == Face.BOTTOM ? 0 : burlap.at(f, x, y, w, h, s);
		};
		// Over the shoulders.
		k.box(r, -8.4f, -0.9f, -3, 17, 3, 6, 0.05f, burlap);
		k.box(r, -6, -1.2f, -2.5f, 12, 1, 5, 0f, burlap);
		// The hood, down, bunched behind the neck.
		k.box(r, -3.5f, -2.6f, 2.2f, 7, 4, 3, 0f, burlap);
		k.box(r, -2.5f, -3.4f, 3.0f, 5, 2, 2, 0f, burlap);
		// Down the front, either side, open in the middle.
		k.box(r, -5, 1.8f, -3.1f, 2, 9, 1, -0.1f, tattered);
		k.box(r, 3, 1.8f, -3.1f, 2, 9, 1, -0.1f, tattered);
		// Down the back, in two pieces that flow.
		PartDefinition c1 = k.bone(r, "c1", 0, 1.0f, 2.7f);
		k.box(c1, -6, 0, 0, 12, 12, 1, -0.15f, burlap);
		PartDefinition c2 = k.bone(c1, "c2", 0, 11.6f, 0.1f);
		k.box(c2, -6.5f, 0, -0.1f, 13, 9, 1, -0.15f, tattered);
		// A rope tie at the throat.
		k.box(r, -1, 0.2f, -3.4f, 2, 1, 1, -0.1f, solid(0x8A6A3A));
		return def("cloak", Anchor.BODY, k, (m, s, mo) -> {
			float sw = Mth.clamp(mo.swing, 0f, 1.6f);
			ModelPart a = m.p("c1");
			a.xRot += sw * 0.55f + Mth.abs(stride(mo, 0)) * 0.08f + Mth.sin(mo.t * 0.05f) * 0.02f + mo.air * 0.3f;
			a.zRot += mo.side * 0.08f;
			ModelPart b = m.p("c2");
			b.xRot += sw * 0.35f + Mth.sin(mo.t * 0.12f + 0.5f) * 0.04f * (0.5f + mo.speed) + mo.bounce * 0.1f;
			b.zRot += mo.side * 0.12f;
		});
	}

	/** The shadow's two red eyes (the shadow itself is Michael's own model: see CosmeticRenderer). */
	private static Def shadowEyes() {
		CosmeticKit k = new CosmeticKit(32, 32);
		PartDefinition r = k.root();
		Paint red = light(0xFF2A1A);
		k.box(r, -3, -5, -4.8f, 2, 1, 1, -0.1f, (f, x, y, w, h, s) -> 0, red);
		k.box(r, 1, -5, -4.8f, 2, 1, 1, -0.1f, (f, x, y, w, h, s) -> 0, red);
		return def("shadow", Anchor.HEAD, k, null);
	}
}
