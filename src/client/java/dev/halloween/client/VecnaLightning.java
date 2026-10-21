package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.halloween.Halloween;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Orange lightning across the sky of the Upside Down while Vecna's rift is open (as in the show): jagged, forking
 * bolts high over the town, a near-white core in an orange glow, flickering for a moment with each red flash of the
 * murk. Each rides on an item display only this client has; its path is drawn here as ribbons turned to the camera.
 */
public final class VecnaLightning {
	private static final Identifier MODEL = Halloween.id("vecna_lightning");
	private static final int LIFE = 9;

	/** A bolt: its carrier, when it struck, and its path (polylines, relative to the carrier). */
	private static final class Bolt {
		final Display.ItemDisplay carrier;
		final long born;
		final List<List<Vector3f>> lines = new ArrayList<>();

		Bolt(Display.ItemDisplay carrier, long born) {
			this.carrier = carrier;
			this.born = born;
		}
	}

	private static final Map<Integer, Bolt> BOLTS = new HashMap<>();
	private static int ids = -8_000_000;
	/** The bolts drawn this frame: carrier id, its age. */
	private static final Map<Integer, Float> AGES = new HashMap<>();
	/** For tests: how many bolts were looked at and drawn. */
	public static int extracted;
	public static int drawn;

	private VecnaLightning() {
	}

	/** One to three bolts somewhere across the sky round this player. */
	public static void strike(Minecraft mc) {
		RandomSource r = mc.level.getRandom();
		int n = 1 + r.nextInt(3);
		for (int i = 0; i < n; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			double dist = 10 + r.nextDouble() * 14;
			double x = mc.player.getX() + Math.cos(a) * dist;
			double z = mc.player.getZ() + Math.sin(a) * dist;
			double y = mc.player.getY() + 24 + r.nextDouble() * 12;
			Display.ItemDisplay d = new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, mc.level);
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.ITEM_MODEL, MODEL);
			d.setItemStack(stack);
			d.setViewRange(16f);
			d.setId(ids--);
			d.snapTo(x, y, z, 0f, 0f);
			mc.level.addEntity(d);
			Bolt b = new Bolt(d, mc.level.getGameTime());
			// The main stroke: down and across, jagged; forks off it.
			Vector3f dir = new Vector3f((float) Math.cos(a + Mth.HALF_PI) * (r.nextBoolean() ? 1 : -1), -0.55f - r.nextFloat() * 0.5f, 0)
				.add(0, 0, (float) Math.sin(a + Mth.HALF_PI) * 0.6f).normalize();
			List<Vector3f> main = path(r, new Vector3f(), dir, 14 + r.nextInt(12), 2.2f);
			b.lines.add(main);
			int forks = 2 + r.nextInt(4);
			for (int f = 0; f < forks; f++) {
				Vector3f from = main.get(2 + r.nextInt(main.size() - 3));
				Vector3f fd = new Vector3f(dir).add((r.nextFloat() - 0.5f) * 1.6f, (r.nextFloat() - 0.6f) * 0.8f, (r.nextFloat() - 0.5f) * 1.6f).normalize();
				b.lines.add(path(r, new Vector3f(from), fd, 4 + r.nextInt(8), 1.6f));
			}
			BOLTS.put(d.getId(), b);
		}
	}

	private static List<Vector3f> path(RandomSource r, Vector3f start, Vector3f dir, int steps, float len) {
		List<Vector3f> pts = new ArrayList<>();
		Vector3f p = new Vector3f(start);
		pts.add(new Vector3f(p));
		for (int i = 0; i < steps; i++) {
			Vector3f d = new Vector3f(dir).add((r.nextFloat() - 0.5f) * 1.3f, (r.nextFloat() - 0.5f) * 0.7f, (r.nextFloat() - 0.5f) * 1.3f).normalize();
			p.add(d.mul(len * (0.6f + r.nextFloat() * 0.8f)));
			pts.add(new Vector3f(p));
		}
		return pts;
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			BOLTS.clear();
			return;
		}
		long now = mc.level.getGameTime();
		BOLTS.values().removeIf(b -> {
			if (now - b.born <= LIFE) {
				return false;
			}
			mc.level.removeEntity(b.carrier.getId(), Entity.RemovalReason.DISCARDED);
			return true;
		});
	}

	public static void clear() {
		Minecraft mc = Minecraft.getInstance();
		for (Bolt b : BOLTS.values()) {
			if (mc.level != null) {
				mc.level.removeEntity(b.carrier.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		BOLTS.clear();
	}

	public static void extract(Display.ItemDisplay entity, float partialTicks) {
		if (BOLTS.containsKey(entity.getId())) {
			extracted++;
			AGES.put(entity.getId(), Minecraft.getInstance().level.getGameTime() - BOLTS.get(entity.getId()).born + partialTicks);
		}
	}

	public static boolean submit(ItemDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector) {
		int id = ((HwRenderState) state).halloween$entityId();
		Bolt b = BOLTS.get(id);
		Float age = AGES.get(id);
		if (b == null || age == null) {
			return b != null;
		}
		// A double flicker, then fading.
		float k = age < 2 ? 1f : age < 3.5f ? 0.35f : age < 5.5f ? 0.9f : Math.max(0f, 1f - (age - 5.5f) / 3.5f);
		if (k <= 0.01f) {
			return true;
		}
		drawn++;
		net.minecraft.world.phys.Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		Vector3f eye = new Vector3f((float) (cam.x - b.carrier.getX()), (float) (cam.y - b.carrier.getY()), (float) (cam.z - b.carrier.getZ()));
		collector.submitCustomGeometry(pose, RenderTypes.lightning(), (p, buffer) -> {
			Matrix4fc m = p.pose();
			for (List<Vector3f> line : b.lines) {
				boolean main = line == b.lines.get(0);
				float scale = main ? 1f : 0.6f;
				// Wide dim glow, the orange body, the hot core.
				ribbon(m, buffer, line, eye, 1.7f * scale, 1f, 0.28f, 0f, 0.3f * k);
				ribbon(m, buffer, line, eye, 0.6f * scale, 1f, 0.42f, 0.02f, 0.9f * k);
				ribbon(m, buffer, line, eye, 0.6f * scale, 1f, 0.42f, 0.02f, 0.9f * k);
				ribbon(m, buffer, line, eye, 0.2f * scale, 1f, 0.78f, 0.35f, 1f * k);
			}
		});
		return true;
	}

	/** A strip along the line, {@code width} across, always turned to the eye. */
	private static void ribbon(Matrix4fc m, VertexConsumer buffer, List<Vector3f> line, Vector3f eye, float width, float r, float g, float bl, float a) {
		for (int i = 0; i + 1 < line.size(); i++) {
			Vector3f p0 = line.get(i);
			Vector3f p1 = line.get(i + 1);
			Vector3f along = new Vector3f(p1).sub(p0);
			Vector3f toEye = new Vector3f(eye).sub(p0);
			Vector3f side = along.cross(toEye, new Vector3f());
			if (side.lengthSquared() < 1.0E-6f) {
				continue;
			}
			side.normalize().mul(width / 2);
			buffer.addVertex(m, p0.x - side.x, p0.y - side.y, p0.z - side.z).setColor(r, g, bl, a);
			buffer.addVertex(m, p0.x + side.x, p0.y + side.y, p0.z + side.z).setColor(r, g, bl, a);
			buffer.addVertex(m, p1.x + side.x, p1.y + side.y, p1.z + side.z).setColor(r, g, bl, a);
			buffer.addVertex(m, p1.x - side.x, p1.y - side.y, p1.z - side.z).setColor(r, g, bl, a);
			// And the other way round (faces turned away are not drawn).
			buffer.addVertex(m, p1.x - side.x, p1.y - side.y, p1.z - side.z).setColor(r, g, bl, a);
			buffer.addVertex(m, p1.x + side.x, p1.y + side.y, p1.z + side.z).setColor(r, g, bl, a);
			buffer.addVertex(m, p0.x + side.x, p0.y + side.y, p0.z + side.z).setColor(r, g, bl, a);
			buffer.addVertex(m, p0.x - side.x, p0.y - side.y, p0.z - side.z).setColor(r, g, bl, a);
		}
	}
}
