package dev.halloween.client;

import dev.halloween.Halloween;
import dev.halloween.game.Cutscenes;
import dev.halloween.game.Skins;
import dev.halloween.net.HwNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The opening scene of each survivor, by who they are (game.Cutscenes): seen at what they do (a pose and something in
 * their hands), named, with him standing behind them for a moment; then into their eyes. Michael gets one of these
 * (at random) through his mask (see Cutscene): the pose and the prop here, the camera there.
 */
public final class HeroScene {
	/** Which of Skins.SURVIVORS, or -1 for none. */
	private static int hero = -1;
	private static boolean own;
	private static Vec3 feet;
	private static Vec3 front;
	/** Where he shows behind them (their own scene), or null. */
	private static Vec3 ghost;
	/** Whose scene it is (their entity), and what they held before it. */
	private static int subject = -1;
	private static ItemStack before = ItemStack.EMPTY;
	private static boolean propped;
	private static long start;

	private HeroScene() {
	}

	/** Their own scene (a = feet, b = towards the camera). */
	static void begin(HwNet.Cutscene payload) {
		Minecraft mc = Minecraft.getInstance();
		hero = payload.kind() - Cutscenes.HERO;
		own = true;
		feet = payload.a();
		front = payload.b();
		ghost = payload.c().lengthSqr() > 0 ? payload.c() : null;
		subject = mc.player.getId();
		start = mc.level.getGameTime();
		propped = false;
	}

	/** Michael watching one of them: the one standing nearest to {@code eyes}. */
	static void watch(int index, Vec3 eyes) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		hero = index;
		own = false;
		ghost = null;
		subject = -1;
		double best = 4;
		for (Player p : mc.level.players()) {
			double d = p.getEyePosition().distanceTo(eyes);
			if (d < best) {
				best = d;
				subject = p.getId();
			}
		}
		start = mc.level.getGameTime();
		propped = false;
	}

	public static boolean own() {
		return hero >= 0 && own && Cutscene.active();
	}

	/** Every client tick: the prop in their hand while the scene plays, their own back once it is over. */
	public static void tick(Minecraft mc) {
		if (mc.level == null || hero < 0) {
			return;
		}
		Entity e = mc.level.getEntity(subject);
		boolean on = Cutscene.active();
		if (on && e instanceof LivingEntity living && !propped) {
			before = living.getItemBySlot(EquipmentSlot.MAINHAND).copy();
			living.setItemSlot(EquipmentSlot.MAINHAND, prop(hero));
			propped = true;
		} else if (!on) {
			if (propped && e instanceof LivingEntity living) {
				living.setItemSlot(EquipmentSlot.MAINHAND, before);
			}
			propped = false;
			hero = -1;
		}
	}

	private static float time(float partial) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() - start + partial;
	}

	/** Is this the mannequin that stands in for him behind them in their scene? */
	public static boolean isGhost(Entity e) {
		// (Loosely: a step up or down from where it was asked for, settled on a path or a slab.)
		return ghost != null && hero >= 0 && e instanceof net.minecraft.world.entity.decoration.Mannequin
			&& Math.abs(e.getX() - ghost.x) < 1.5 && Math.abs(e.getZ() - ghost.z) < 1.5 && Math.abs(e.getY() - ghost.y) < 2.5;
	}

	/** The light a body in the scene is drawn with: a little more for the one it is about and for him behind them. */
	public static int light(Object state, int light) {
		if (hero < 0 || !Cutscene.active() || !(state instanceof net.minecraft.client.renderer.entity.state.EntityRenderState s)) {
			return light;
		}
		boolean it = state instanceof dev.halloween.client.HwRenderState r && r.halloween$entityId() == subject;
		boolean him = ghost != null && Math.abs(s.x - ghost.x) < 1.2 && Math.abs(s.z - ghost.z) < 1.2 && Math.abs(s.y - ghost.y) < 2;
		if (!it && !him) {
			return light;
		}
		int min = (it ? 9 : 6) << 4;
		return Math.max(light & 0xFFFF, min) | Math.max(light >>> 16, min) << 16;
	}

	/** What they hold. */
	private static ItemStack prop(int i) {
		return switch (Skins.SURVIVORS.get(i)) {
			case "laurie", "jennifer" -> new ItemStack(Items.BOOK);
			case "lynda" -> new ItemStack(Items.HONEY_BOTTLE);
			case "rachel" -> new ItemStack(Items.CARVED_PUMPKIN);
			case "tanya" -> new ItemStack(Items.BUNDLE);
			case "bob" -> ours("tin_cans");
			case "eric" -> ours("pipe_wrench");
			case "marcus" -> ours("flashlight");
			case "thomas" -> new ItemStack(Items.WOODEN_HOE);
			case "richard" -> new ItemStack(Items.PAPER);
			default -> ItemStack.EMPTY;
		};
	}

	private static ItemStack ours(String model) {
		ItemStack s = new ItemStack(Items.PAPER);
		s.set(DataComponents.ITEM_MODEL, Halloween.id(model));
		return s;
	}

	/** Their pose in the scene, over the model's own; true if one was laid on. */
	static boolean pose(HumanoidModel<?> m, int entityId) {
		if (hero < 0 || entityId != subject || !Cutscene.active()) {
			return false;
		}
		float t = time(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
		float a = Mth.sin(t * 0.25f);
		float b = Mth.sin(t * 0.11f);
		switch (Skins.SURVIVORS.get(hero)) {
			case "laurie" -> {
				// Books hugged to her chest, walking; now and then a look back over her shoulder.
				m.rightArm.xRot = -0.95f;
				m.leftArm.xRot = -0.95f;
				m.rightArm.yRot = -0.55f;
				m.leftArm.yRot = 0.55f;
				m.head.yRot = Mth.clamp(b * 2.2f, 0f, 1.2f);
				m.rightLeg.xRot = a * 0.4f;
				m.leftLeg.xRot = -a * 0.4f;
			}
			case "annie" -> {
				// The phone at her ear, the other hand on her hip, chattering away.
				m.rightArm.xRot = -2.3f;
				m.rightArm.yRot = -0.55f;
				m.rightArm.zRot = 0.25f;
				m.leftArm.xRot = -0.25f;
				m.leftArm.zRot = -0.6f;
				m.head.zRot = 0.22f + a * 0.06f;
				m.head.xRot = a * 0.08f;
			}
			case "lynda" -> {
				// A swig, then laughing, head thrown back.
				float swig = Mth.clamp(Mth.sin(t * 0.08f) * 2f, 0f, 1f);
				m.rightArm.xRot = Mth.lerp(swig, -0.5f, -2.2f);
				m.rightArm.yRot = -0.3f * swig;
				m.head.xRot = -0.35f * swig - 0.15f * (1f - swig) * Math.abs(Mth.sin(t * 0.9f));
				m.leftArm.zRot = -0.25f;
			}
			case "rachel" -> {
				// The pumpkin held out, the other hand carving at it.
				m.rightArm.xRot = -1.15f;
				m.rightArm.yRot = -0.2f;
				m.leftArm.xRot = -1.1f + Mth.sin(t * 0.7f) * 0.25f;
				m.leftArm.yRot = 0.5f;
				m.head.xRot = 0.45f;
			}
			case "tanya" -> {
				// Skipping along, the sweets bag swinging.
				m.rightArm.xRot = -0.35f + a * 0.45f;
				m.leftArm.xRot = -a * 0.5f;
				m.rightLeg.xRot = a * 0.6f;
				m.leftLeg.xRot = -a * 0.6f;
				m.head.zRot = a * 0.1f;
			}
			case "jennifer" -> {
				// Reading, the book in both hands, head down; a page turned now and then.
				m.rightArm.xRot = -1.05f;
				m.leftArm.xRot = -1.05f + Math.max(0f, Mth.sin(t * 0.12f) - 0.8f) * 2.5f;
				m.rightArm.yRot = -0.3f;
				m.leftArm.yRot = 0.3f;
				m.head.xRot = 0.5f;
			}
			case "alexis" -> {
				// Rocking the baby in her arms, looking out of the window.
				m.rightArm.xRot = -0.95f;
				m.leftArm.xRot = -0.95f;
				m.rightArm.yRot = -0.65f + b * 0.1f;
				m.leftArm.yRot = 0.65f + b * 0.1f;
				m.head.yRot = 0.7f;
				m.body.yRot = b * 0.08f;
			}
			case "bob" -> {
				// Leaning about, a can to his mouth now and then, the other hand on his hip.
				float swig = Mth.clamp(Mth.sin(t * 0.07f) * 2f, 0f, 1f);
				m.rightArm.xRot = Mth.lerp(swig, -0.4f, -2.1f);
				m.head.xRot = -0.3f * swig;
				m.leftArm.zRot = -0.55f;
				m.leftArm.xRot = -0.2f;
			}
			case "eric" -> {
				// Bent over the engine, the wrench going.
				m.rightArm.xRot = -1.35f + Mth.sin(t * 0.9f) * 0.35f;
				m.rightArm.yRot = -0.15f;
				m.leftArm.xRot = -1.2f;
				m.leftArm.yRot = 0.35f;
				m.head.xRot = 0.6f;
			}
			case "marcus" -> {
				// The flashlight under his chin, then a lunge: boo.
				float boo = Mth.clamp(Mth.sin(t * 0.15f) * 3f - 2f, 0f, 1f);
				m.rightArm.xRot = Mth.lerp(boo, -1.9f, -1.6f);
				m.rightArm.yRot = -0.55f * (1f - boo);
				m.leftArm.xRot = -1.7f * boo;
				m.leftArm.zRot = -0.4f * boo;
				m.head.xRot = -0.2f * (1f - boo);
			}
			case "thomas" -> {
				// Raking leaves, a sweep and a sweep.
				m.rightArm.xRot = -0.8f + a * 0.2f;
				m.leftArm.xRot = -0.8f + a * 0.2f;
				m.rightArm.yRot = a * 0.5f;
				m.leftArm.yRot = a * 0.5f;
				m.head.xRot = 0.4f;
			}
			case "richard" -> {
				// The paper open in both hands, head bent over it, a page shaken straight now and then.
				float shake = Math.max(0f, Mth.sin(t * 0.13f) - 0.85f) * 3f;
				m.rightArm.xRot = -1.15f + shake * 0.3f;
				m.leftArm.xRot = -1.15f + shake * 0.3f;
				m.rightArm.yRot = -0.4f;
				m.leftArm.yRot = 0.4f;
				m.head.xRot = 0.35f;
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Their own scene's camera: position, yaw, pitch. In front of them, edging round; at the end, into their eyes. */
	static double[] camera(float f, float partial) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.player.getEyePosition(partial);
		Vec3 look = eye.add(0, -0.15, 0);
		Vec3 side = new Vec3(-front.z, 0, front.x);
		float orbit = Mth.lerp(Math.min(1f, f / 0.82f), 0.6f, -0.4f);
		float dist = Mth.lerp(Math.min(1f, f / 0.82f), 3.2f, 2.4f);
		Vec3 want = eye.add(front.scale(dist)).add(side.scale(orbit)).add(0, 0.15, 0);
		// Never inside a wall.
		HitResult hit = mc.level.clip(new ClipContext(look, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		Vec3 cam = hit.getType() == HitResult.Type.MISS ? want : look.lerp(hit.getLocation(), 0.9);
		float[] rot = lookAt(cam, look);
		if (f < 0.86f) {
			return new double[]{cam.x, cam.y, cam.z, rot[0], rot[1]};
		}
		// Into their eyes, turning to where they look.
		float k = (f - 0.86f) / 0.14f;
		k = k * k * (3 - 2 * k);
		Vec3 pos = cam.lerp(eye, k);
		float yaw = rot[0] + Mth.wrapDegrees(mc.player.getViewYRot(partial) - rot[0]) * k;
		float pitch = Mth.lerp(k, rot[1], mc.player.getViewXRot(partial));
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}

	private static float[] lookAt(Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)))};
	}

	/** Their own scene's titles: letterboxed, their name and a line about them. */
	static void overlay(GuiGraphicsExtractor g, Minecraft mc, float f) {
		int w = g.guiWidth();
		int h = g.guiHeight();
		float bars = f < 0.88f ? 1f : 1f - (f - 0.88f) / 0.12f;
		int bar = Math.round(h * 0.11f * bars);
		g.fill(0, 0, w, bar, 0xFF000000);
		g.fill(0, h - bar, w, h, 0xFF000000);
		String name = Skins.SURVIVORS.get(Math.max(0, hero));
		float t1 = Cutscene.fade(f, 0.1f, 0.5f, 0.06f);
		if (t1 > 0) {
			Cutscene.bigText(g, mc, Component.translatable("halloween.hero.name." + name), w / 2, h - bar - 34, 1.6f, Cutscene.alpha(t1, 0xE8E2D4));
		}
		float t2 = Cutscene.fade(f, 0.16f, 0.52f, 0.06f);
		if (t2 > 0) {
			Cutscene.bigText(g, mc, Component.translatable("halloween.hero." + name), w / 2, h - bar - 14, 1.0f, Cutscene.alpha(t2, 0xC8C0B0));
		}
		float t3 = Cutscene.fade(f, 0.66f, 0.84f, 0.05f);
		if (t3 > 0) {
			Cutscene.bigText(g, mc, Component.translatable("halloween.cutscene.survivor"), w / 2, h - bar - 14, 1.0f, Cutscene.alpha(t3, 0xE8E2D4));
		}
		if (f < 0.05f) {
			g.fill(0, 0, w, h, Cutscene.alpha(1 - f / 0.05f, 0x000000));
		}
	}
}
