package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/**
 * The HUD's marks on the world, in the headset. On a monitor the HUD projects points of the world onto the screen
 * (Projection); in VR the HUD is a panel hanging before the face, so those marks would sit beside what they mark.
 * There the HUD hands them here instead (Projection answers "not on screen") and they are drawn in the world itself:
 * words and signs turned to the eyes, seen through walls and the same size to the eye near or far, and the Shape
 * Jump's beams as columns of light. Gathered while the HUD is drawn, shown on every eye's pass until the next HUD.
 */
public final class VrMarks {
	private record Mark(Vec3 pos, Component text, int color, float scale) {
	}

	private static List<Mark> marks = new ArrayList<>();
	private static List<Mark> next = new ArrayList<>();
	private static List<Vec3> beams = new ArrayList<>();
	private static List<Vec3> nextBeams = new ArrayList<>();

	private VrMarks() {
	}

	/** Drawn on the world from now on, only in the headset. */
	static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
			if (ClientVr.active()) {
				draw(ctx.poseStack(), ctx.submitNodeCollector());
			}
		});
	}

	/** The HUD begins: what it marks this time replaces what it marked last time. */
	static void begin() {
		List<Mark> m = marks;
		marks = next;
		next = m;
		next.clear();
		List<Vec3> b = beams;
		beams = nextBeams;
		nextBeams = b;
		nextBeams.clear();
	}

	/** A word or sign over a point of the world ({@code scale} 1 is a line of HUD text). */
	static void add(Vec3 pos, Component text, int color, float scale) {
		if ((color >>> 24) < 8) {
			return;
		}
		next.add(new Mark(pos, text, color, scale));
	}

	static void add(Vec3 pos, String text, int color, float scale) {
		add(pos, Component.literal(text), color, scale);
	}

	/** A column of light rising from a point (Shape Jump). */
	static void beam(Vec3 base) {
		nextBeams.add(base);
	}

	private static void draw(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		Camera camera = mc.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		Font font = mc.font;
		for (Mark m : marks) {
			Vec3 d = m.pos().subtract(cam);
			double dist = d.length();
			// The same size to the eye however far: about a HUD line at arm's length.
			float s = (float) (Math.max(dist, 1.5) * 0.0016) * m.scale();
			pose.pushPose();
			pose.translate(d.x, d.y, d.z);
			pose.mulPose(camera.rotation());
			pose.scale(s, -s, s);
			FormattedCharSequence text = m.text().getVisualOrderText();
			collector.submitText(pose, -font.width(text) / 2f, -font.lineHeight / 2f, text, false, Font.DisplayMode.SEE_THROUGH,
				0xF000F0, m.color(), 0, 0);
			pose.popPose();
		}
		for (Vec3 base : beams) {
			Vec3 d = base.subtract(cam);
			pose.pushPose();
			pose.translate(d.x, d.y, d.z);
			collector.submitCustomGeometry(pose, RenderTypes.lightning(), (p, buffer) -> {
				Matrix4fc mat = p.pose();
				// Two crossed sheets, gold, fading upwards; a hot thin core.
				column(mat, buffer, 0.45f, 1f, 0.82f, 0.29f, 0.55f);
				column(mat, buffer, 0.12f, 1f, 0.95f, 0.75f, 0.85f);
			});
			pose.popPose();
		}
	}

	private static void column(Matrix4fc m, com.mojang.blaze3d.vertex.VertexConsumer buffer, float half, float r, float g, float b, float a) {
		float top = 80f;
		for (int k = 0; k < 2; k++) {
			float x = k == 0 ? half : 0f;
			float z = k == 0 ? 0f : half;
			for (int side = 0; side < 2; side++) {
				// Both faces (turned-away faces are not drawn).
				float sx = side == 0 ? x : -x;
				float sz = side == 0 ? z : -z;
				buffer.addVertex(m, -sx, 0, -sz).setColor(r, g, b, a);
				buffer.addVertex(m, sx, 0, sz).setColor(r, g, b, a);
				buffer.addVertex(m, sx, top, sz).setColor(r, g, b, 0f);
				buffer.addVertex(m, -sx, top, -sz).setColor(r, g, b, 0f);
			}
		}
	}
}
