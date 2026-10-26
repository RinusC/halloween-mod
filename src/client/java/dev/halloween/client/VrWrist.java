package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.halloween.Vr;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * The role's buttons on the wrist, in the headset. Raise the free hand before the face as if to look at a watch (the
 * forearm across, the hand towards the other side) and an arc of round buttons comes up over it: the same ones the
 * radial menu has (VrKeys), with the role's sign and name. Touch one with the tip of the other hand's controller: it
 * lights, the hand buzzes, and it is pressed (a held one, Killer Sense or Vecna's Grip, is switched on until touched
 * again). Needs no button bound: on headsets whose controllers Vivecraft has no bindings for (the radial menu on B
 * missing), this is how the role's powers are reached.
 */
public final class VrWrist {
	/** Spacing between the buttons along the arc, their size, how far over the hand the arc hangs (metres). */
	private static final double SPACING = 0.088;
	private static final double RADIUS = 0.034;
	private static final double LIFT = 0.10;
	/** The fingertip that touches: this far ahead of the controller along where it points. */
	private static final double TIP = 0.05;

	/** How long the watch gesture has been held (or let go: negative), and whether the arc is up. */
	private static int held;
	private static boolean open;
	private static boolean used;
	/** The button being touched (a touch counts once, until the tip leaves it), the one hovered, when one was last pressed. */
	private static int touching = -1;
	private static int hover = -1;
	private static int pressed = -1;
	private static long pressedAt;
	/** For tests: draw the arc here (its middle) facing the camera, whoever plays. */
	public static @Nullable Vec3 testAt;
	public static int testHover = -1;

	private VrWrist() {
	}

	/** The arc is switched off in the headset (the buttons go on the controllers instead); tests still draw it. */
	private static final boolean ENABLED = false;

	/** Whether the arc can be brought up in the headset at all. */
	public static boolean enabled() {
		return ENABLED;
	}

	/** Drawn on the world from now on (in the headset, or for a test). */
	static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			if (open && ClientVr.active() || testAt != null) {
				draw(ctx.poseStack(), ctx.submitNodeCollector());
			}
		});
	}

	/** Is the arc up (the free hand is busy with it, not the game)? */
	public static boolean open() {
		return open;
	}

	/** Brought up at least once this game (the HUD stops saying how). */
	public static boolean used() {
		return used;
	}

	/** Every client tick: the gesture, and a touch. */
	static void tick(Minecraft mc) {
		KeyMapping[] keys = VrKeys.keys();
		if (!ENABLED || !ClientVr.active() || mc.player == null || mc.gui.screen() != null || keys.length == 0 || mc.player.isSpectator()) {
			open = false;
			held = 0;
			touching = -1;
			return;
		}
		Vr.Ray head = ClientVr.head();
		Vr.Ray off = ClientVr.hand(false);
		Vr.Ray main = ClientVr.hand(true);
		if (head == null || off == null || main == null) {
			open = false;
			return;
		}
		boolean watch = watching(head, off);
		held = watch ? Math.max(1, held + 1) : Math.min(-1, held - 1);
		// Up after a moment of looking; down after a moment of not (a glance away keeps it).
		if (!open && held >= 4) {
			open = true;
			used = true;
			ClientVr.buzz(false, 0.03f, 0.25f);
		} else if (open && held <= -6) {
			open = false;
		}
		if (!open) {
			touching = -1;
			hover = -1;
			return;
		}
		Vec3[] centers = centers(head.pos(), off.pos(), keys.length);
		Vec3 tip = main.pos().add(main.dir().scale(TIP));
		int near = -1;
		double best = 1e9;
		for (int i = 0; i < centers.length; i++) {
			double d = tip.distanceTo(centers[i]);
			if (d < best) {
				best = d;
				near = i;
			}
		}
		hover = best < RADIUS * 2.2 ? near : -1;
		if (touching >= 0 && tip.distanceTo(centers[touching]) > RADIUS * 2.2) {
			touching = -1;
		}
		if (touching < 0 && best < RADIUS * 1.25) {
			touching = near;
			pressed = near;
			pressedAt = mc.level.getGameTime();
			VrKeys.press(keys[near]);
			ClientVr.buzz(true, 0.05f, 0.7f);
			ClientVr.buzz(false, 0.03f, 0.3f);
		}
	}

	/** Looking at the free hand held up across before the face, as at a watch. */
	private static boolean watching(Vr.Ray head, Vr.Ray off) {
		Vec3 to = off.pos().subtract(head.pos());
		double dist = to.length();
		if (dist < 0.18 || dist > 0.62 || to.y > 0.12 || to.y < -0.55) {
			return false;
		}
		Vec3 fwd = ClientVr.headForward();
		Vec3 look = head.dir();
		// The eyes on it.
		if (to.normalize().dot(look) < 0.72) {
			return false;
		}
		// The controller pointing across the body (to the right for a left hand), not ahead.
		Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
		Vec3 across = new Vec3(off.dir().x, 0, off.dir().z);
		double side = isLeftOff() ? 1 : -1;
		return across.lengthSqr() > 0.15 && across.normalize().dot(right.scale(side)) > 0.55 && Math.abs(off.dir().y) < 0.6;
	}

	/** The free hand is the left one (Vivecraft's left-handed mode swaps them). */
	private static boolean isLeftOff() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null || mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
	}

	/** Where each button's middle is: in an arc over the hand, square to the eyes. */
	private static Vec3[] centers(Vec3 eye, Vec3 hand, int n) {
		Vec3[] basis = basis(eye, hand);
		Vec3 mid = hand.add(basis[1].scale(LIFT)).subtract(basis[2].scale(0.03));
		Vec3[] out = new Vec3[n];
		for (int i = 0; i < n; i++) {
			double x = (i - (n - 1) / 2.0) * SPACING;
			double y = -Math.abs(x) * 0.35;
			out[i] = mid.add(basis[0].scale(x)).add(basis[1].scale(y));
		}
		return out;
	}

	/** Right, up and towards the eye, for a panel at {@code at} square to the eye. */
	private static Vec3[] basis(Vec3 eye, Vec3 at) {
		Vec3 back = eye.subtract(at).normalize();
		Vec3 right = new Vec3(0, 1, 0).cross(back);
		right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 up = back.cross(right).normalize();
		return new Vec3[]{right, up, back};
	}

	// ---------------------------------------------------------------- drawing

	private static void draw(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		KeyMapping[] keys = VrKeys.keys();
		Vec3 eye;
		Vec3 hand;
		int hovered = hover;
		if (testAt != null) {
			eye = cam;
			hand = testAt.subtract(0, LIFT, 0);
			hovered = testHover;
			if (keys.length == 0) {
				keys = VrKeys.testKeys();
			}
		} else {
			Vr.Ray head = ClientVr.renderHead();
			Vr.Ray off = ClientVr.renderHand(false);
			if (head == null || off == null) {
				return;
			}
			eye = head.pos();
			hand = off.pos();
		}
		if (keys.length == 0) {
			return;
		}
		Vec3[] basis = basis(eye, hand);
		Vec3[] centers = centers(eye, hand, keys.length);
		long now = mc.level.getGameTime();
		Font font = mc.font;
		for (int i = 0; i < keys.length; i++) {
			boolean on = VrKeys.toggled(keys[i]);
			boolean lit = i == hovered;
			float flash = i == pressed ? Math.max(0f, 1f - (now - pressedAt) / 6f) : 0f;
			Vec3 d = centers[i].subtract(cam);
			pose.pushPose();
			pose.translate(d.x, d.y, d.z);
			pose.mulPose(new Matrix4f().set3x3(new org.joml.Matrix3f(
				(float) basis[0].x, (float) basis[0].y, (float) basis[0].z,
				(float) basis[1].x, (float) basis[1].y, (float) basis[1].z,
				(float) basis[2].x, (float) basis[2].y, (float) basis[2].z)));
			float r = (float) RADIUS * (lit ? 1.12f : 1f) * (1f + 0.15f * flash);
			collector.submitCustomGeometry(pose, RenderTypes.textBackground(), (p, buffer) -> {
				// The face: dark glass, a shade warmer when touched or on.
				int base = on ? 0xE8603010 : lit ? 0xE8382416 : 0xD8181210;
				disc(p.pose(), buffer, r, base, 0f);
			});
			collector.submitCustomGeometry(pose, RenderTypes.lightning(), (p, buffer) -> {
				// The rim: amber, brighter hovered, full when on or just touched.
				float a = on ? 1f : lit ? 0.95f : 0.7f;
				a = Math.max(a, flash);
				ring(p.pose(), buffer, r, r * 0.16f, 1f, 0.62f, 0.18f, a);
				if (on || flash > 0) {
					disc(p.pose(), buffer, r * 0.85f, 0, Math.max(0.25f, flash) * 0.5f);
				}
			});
			// The sign in the middle, the name under it.
			float s = 0.0011f;
			pose.pushPose();
			pose.translate(0, 0, 0.002);
			pose.scale(s * 3.2f, -s * 3.2f, s);
			FormattedCharSequence icon = Component.literal(icon(keys[i])).getVisualOrderText();
			collector.submitText(pose, -font.width(icon) / 2f, -font.lineHeight / 2f + 0.5f, icon, false, Font.DisplayMode.NORMAL,
				0xF000F0, on || lit ? 0xFFFFF0D0 : 0xFFF0C880, 0, 0);
			pose.popPose();
			pose.pushPose();
			pose.translate(0, -r - 0.012, 0.002);
			pose.scale(s * 1.4f, -s * 1.4f, s);
			FormattedCharSequence name = Component.translatable(keys[i].getName()).getVisualOrderText();
			collector.submitText(pose, -font.width(name) / 2f, 0, name, false, Font.DisplayMode.NORMAL, 0xF000F0,
				lit || on ? 0xFFFFE6C0 : 0xC8D8C8B8, 0x90000000, 0);
			pose.popPose();
			pose.popPose();
		}
	}

	/** A filled disc in the panel's plane, as quads from the middle; a colour as ARGB (or, with {@code glow} > 0, white light). */
	private static void disc(Matrix4fc m, VertexConsumer buffer, float r, int argb, float glow) {
		int seg = 24;
		float a = glow > 0 ? glow : (argb >>> 24) / 255f;
		float cr = glow > 0 ? 1f : (argb >> 16 & 0xFF) / 255f;
		float cg = glow > 0 ? 0.75f : (argb >> 8 & 0xFF) / 255f;
		float cb = glow > 0 ? 0.4f : (argb & 0xFF) / 255f;
		for (int k = 0; k < seg; k++) {
			float t0 = k * Mth.TWO_PI / seg;
			float t1 = (k + 1) * Mth.TWO_PI / seg;
			vertex(buffer, m, 0, 0, cr, cg, cb, a, glow == 0);
			vertex(buffer, m, 0, 0, cr, cg, cb, a, glow == 0);
			vertex(buffer, m, Mth.cos(t0) * r, Mth.sin(t0) * r, cr, cg, cb, a, glow == 0);
			vertex(buffer, m, Mth.cos(t1) * r, Mth.sin(t1) * r, cr, cg, cb, a, glow == 0);
		}
	}

	private static void ring(Matrix4fc m, VertexConsumer buffer, float r, float w, float cr, float cg, float cb, float a) {
		int seg = 32;
		for (int k = 0; k < seg; k++) {
			float t0 = k * Mth.TWO_PI / seg;
			float t1 = (k + 1) * Mth.TWO_PI / seg;
			buffer.addVertex(m, Mth.cos(t0) * r, Mth.sin(t0) * r, 0.001f).setColor(cr, cg, cb, a * 0.6f);
			buffer.addVertex(m, Mth.cos(t1) * r, Mth.sin(t1) * r, 0.001f).setColor(cr, cg, cb, a * 0.6f);
			buffer.addVertex(m, Mth.cos(t1) * (r - w), Mth.sin(t1) * (r - w), 0.001f).setColor(cr, cg, cb, a);
			buffer.addVertex(m, Mth.cos(t0) * (r - w), Mth.sin(t0) * (r - w), 0.001f).setColor(cr, cg, cb, a);
		}
	}

	private static void vertex(VertexConsumer buffer, Matrix4fc m, float x, float y, float r, float g, float b, float a, boolean lit) {
		VertexConsumer v = buffer.addVertex(m, x, y, 0).setColor(r, g, b, a);
		if (lit) {
			v.setLight(0xF000F0);
		}
	}

	/** The sign of each button. */
	private static String icon(KeyMapping key) {
		String n = key.getName();
		return switch (n.substring(n.lastIndexOf('.') + 1)) {
			case "dash" -> "»";
			case "sense" -> "◉";
			case "grip" -> "✋";
			case "reload" -> "↻";
			case "shape_jump" -> "☾";
			case "upside_down" -> "⇩";
			case "eyes" -> "◎";
			case "runt_swap" -> "⇄";
			case "impulse" -> "✺";
			case "anchor" -> "⚓";
			case "orders" -> "☰";
			case "perks" -> "★";
			case "skill" -> "✦";
			case "ping" -> "◆";
			case "gift1" -> "Ⅰ";
			case "gift2" -> "Ⅱ";
			default -> "•";
		};
	}
}
