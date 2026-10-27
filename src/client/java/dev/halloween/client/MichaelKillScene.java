package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.halloween.Halloween;
import dev.halloween.game.MichaelKill;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Michael's kills after a struggle (game.MichaelKill) as every client near sees them: his moves and the body of the one
 * he kills, on the player models (his own model takes his pose from it, see MichaelRenderer), how his knife sits in his
 * hand (along the arm, point first, when it goes in), the knife left in them (his own blade, if he carries one of his
 * cosmetics in place of it), and the one left sitting against the wall.
 * The server moves them both.
 */
public final class MichaelKillScene {
	private static final class Scene {
		int kind;
		int michael;
		int victim;
		long start;
		int ticks;
		float yaw;
		/** The knife in his hand when it began (left in them in the wall). */
		ItemStack knife = ItemStack.EMPTY;
	}

	/** By the one he kills. */
	private static final Map<Integer, Scene> SCENES = new HashMap<>();
	/**
	 * How much the knife (the item: his own blades go along the arm as they are) is turned at the wrist to lie along the
	 * arm, point first: its picture is held slanting forward.
	 */
	public static final float ITEM_ALONG = 0.8f;
	/** His knife arm as it goes into them at the wall: raised to their chest, and in across to the middle of it. */
	private static final float STAB_X = -1.82f;
	private static final float STAB_Y = -0.3f;
	/** The knife left in them: the fist that held it before their chest (pixels, their body's own), the arm's line into them. */
	private static final float STUCK_X = 1f;
	private static final float STUCK_Y = 8.4f;
	private static final float STUCK_Z = -13.7f;
	private static final float STUCK_PITCH = 1.8f;
	private static final float STUCK_YAW = -0.3f;
	private static final ItemStackRenderState STUCK = new ItemStackRenderState();

	private MichaelKillScene() {
	}

	public static void receive(HwNet.MichaelKill p) {
		if (p.kind() == MichaelKill.NONE) {
			SCENES.remove(p.victimId());
			return;
		}
		Scene s = new Scene();
		s.kind = p.kind();
		s.michael = p.michaelId();
		s.victim = p.victimId();
		s.start = p.start();
		s.ticks = p.ticks();
		s.yaw = p.yaw();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.level.getEntity(s.michael) instanceof LivingEntity him) {
			s.knife = him.getMainHandItem().copy();
		}
		if (s.knife.isEmpty()) {
			s.knife = new ItemStack(Items.IRON_SWORD);
			s.knife.set(DataComponents.ITEM_MODEL, Halloween.id("michael_knife"));
		}
		SCENES.put(s.victim, s);
	}

	public static void clear() {
		SCENES.clear();
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			SCENES.clear();
			return;
		}
		long now = mc.level.getGameTime();
		SCENES.values().removeIf(s -> now - s.start > s.ticks + 4);
	}

	private static float now() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	private static Scene live(Scene s) {
		return s != null && now() - s.start <= s.ticks ? s : null;
	}

	private static Scene ofMichael(int id) {
		for (Scene s : SCENES.values()) {
			if (s.michael == id && live(s) != null) {
				return s;
			}
		}
		return null;
	}

	/** A body left sitting against a wall (game.MichaelKill: a mannequin he pinned, marked by its pose). */
	public static boolean sitting(int id) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.getEntity(id) instanceof Mannequin body && body.getPose() == Pose.FALL_FLYING;
	}

	/** Which way this one faces in a kill of his (him towards them, them towards him; flung, turning in the air), or NaN. */
	public static float bodyYaw(int id) {
		Scene s = live(SCENES.get(id));
		if (s != null) {
			return s.yaw + 180f;
		}
		Scene m = ofMichael(id);
		if (m != null) {
			return m.yaw;
		}
		if (sitting(id)) {
			// The body left against the wall faces the way it sat down (the mannequin's own body turns as it likes).
			return Minecraft.getInstance().level.getEntity(id).getYRot();
		}
		return Float.NaN;
	}

	/** Does his knife go along his arm now, point first (as it goes in, and while it is in them)? */
	public static boolean along(int id) {
		Scene s = ofMichael(id);
		if (s == null) {
			return false;
		}
		float t = now() - s.start;
		return t > MichaelKill.P_SLAM + 6 && t < MichaelKill.P_LEAVE + 2 || t > MichaelKill.P_RETURN + 4 && t < MichaelKill.P_PULL + 4;
	}

	/** Is his knife in the one he pinned to the wall (not in his hand)? */
	public static boolean inWall(int id) {
		Scene s = ofMichael(id);
		if (s == null) {
			return false;
		}
		float t = now() - s.start;
		return t >= MichaelKill.P_LEAVE && t < MichaelKill.P_PULL;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	/** A jolt at {@code at}: up to 1 in two ticks, dying away. */
	private static float jolt(float t, float at) {
		float k = t - at;
		return k < 0 ? 0f : k < 2 ? k / 2f : (float) Math.exp(-(k - 2) / 4f);
	}

	/** Him or them in one of his kills, posed (or a body he left sitting against a wall); false if neither. */
	public static boolean pose(HumanoidModel<?> m, int id) {
		Scene s = live(SCENES.get(id));
		boolean him = false;
		if (s == null) {
			s = ofMichael(id);
			him = true;
		}
		if (s == null) {
			if (sitting(id)) {
				reset(m);
				slump(m, 1f);
				return true;
			}
			return false;
		}
		float t = now() - s.start;
		float now = now();
		reset(m);
		if (him) {
			pinHim(m, t, now);
		} else {
			pinThem(m, t, now);
		}
		return true;
	}

	private static void reset(HumanoidModel<?> m) {
		m.rightArm.yRot = 0;
		m.leftArm.yRot = 0;
		m.rightArm.zRot = 0;
		m.leftArm.zRot = 0;
		m.rightLeg.yRot = 0;
		m.leftLeg.yRot = 0;
		m.rightLeg.zRot = 0;
		m.leftLeg.zRot = 0;
		m.head.yRot = 0;
		m.head.zRot = 0;
		m.body.xRot = 0;
		m.body.yRot = 0;
	}

	/**
	 * The whole body in one of his kills, laid over where it stands (from mixin.LivingEntityRendererMixin's
	 * setupRotations): down the wall, sitting (and the body left so). False: as anyone.
	 */
	public static boolean rotations(int id, PoseStack pose) {
		Scene s = live(SCENES.get(id));
		if (s == null) {
			if (sitting(id)) {
				pose.translate(0f, -MichaelKill.SIT, 0f);
				return true;
			}
			return false;
		}
		float t = now() - s.start;
		float fold = fold(t);
		if (fold <= 0f) {
			return false;
		}
		pose.translate(0f, -MichaelKill.SIT * fold, 0f);
		return true;
	}

	/** Down the wall: how far the knees have given (0 hanging, 1 sitting on the floor against it). */
	private static float fold(float t) {
		return smooth((t - MichaelKill.P_DOWN + 2) / 7f);
	}

	/** Walking: the legs swinging as he goes ({@code k} how much). */
	private static void walk(HumanoidModel<?> m, float now, float k) {
		m.rightLeg.xRot = Mth.sin(now * 0.9f) * 0.55f * k;
		m.leftLeg.xRot = -Mth.sin(now * 0.9f) * 0.55f * k;
	}

	private static void pinHim(HumanoidModel<?> m, float t, float now) {
		float throat;
		float knife;
		float knifeY = 0f;
		m.rightLeg.xRot = 0;
		m.leftLeg.xRot = 0;
		if (t <= MichaelKill.P_SLAM) {
			// Walking them back into the wall by the throat.
			throat = Mth.lerp(t / MichaelKill.P_SLAM, -1.6f, -1.75f);
			knife = -0.1f;
			walk(m, now, 1f - smooth((t - MichaelKill.P_SLAM + 3) / 3f));
		} else if (t < MichaelKill.P_STAB - 2) {
			// Up the wall; the knife drawn back.
			float k = smooth((t - MichaelKill.P_SLAM) / 9f);
			throat = Mth.lerp(k, -1.75f, -2.15f);
			knife = Mth.lerp(k, -0.1f, 0.75f);
			knifeY = 0.25f * k;
		} else if (t < MichaelKill.P_STAB + 0.5f) {
			// Driven in, into the middle of the chest.
			float k = smooth((t - MichaelKill.P_STAB + 2) / 2.5f);
			throat = -2.15f;
			knife = Mth.lerp(k, 0.75f, STAB_X);
			knifeY = Mth.lerp(k, 0.25f, STAB_Y);
		} else if (t < MichaelKill.P_LEAVE) {
			// Leaning on it, deeper.
			throat = -2.15f;
			knife = STAB_X - 0.1f * jolt(t, 30) + Mth.sin(now * 2.1f) * 0.015f;
			knifeY = STAB_Y;
		} else if (t < MichaelKill.P_RETURN) {
			// Lets go; steps back and looks at them, the head slowly tilting.
			float k = smooth((t - MichaelKill.P_LEAVE) / 6f);
			throat = Mth.lerp(k, -2.15f, -0.05f);
			knife = Mth.lerp(k, STAB_X, -0.08f);
			knifeY = Mth.lerp(k, STAB_Y, 0f);
			if (t > MichaelKill.P_LEAVE + 4 && t < MichaelKill.P_BACK) {
				walk(m, now, 0.6f);
			}
			float tilt = smooth((t - MichaelKill.P_BACK) / 16f);
			m.head.zRot = 0.5f * tilt;
			m.head.xRot = 0.05f * tilt;
		} else {
			// In again; takes hold of the knife and pulls it out; a step back, looking down at them.
			float reach = smooth((t - MichaelKill.P_RETURN) / 8f);
			float yank = smooth((t - MichaelKill.P_PULL) / 2f);
			float rest = smooth((t - MichaelKill.P_PULL - 3) / 6f);
			throat = -0.05f;
			knife = Mth.lerp(rest, Mth.lerp(yank, Mth.lerp(reach, -0.08f, STAB_X), -0.85f), -0.15f);
			knifeY = STAB_Y * reach * (1f - yank);
			if (t < MichaelKill.P_RETURN + 8 || t > MichaelKill.P_PULL + 3 && t < MichaelKill.P_PULL + 10) {
				walk(m, now, 0.5f);
			}
			m.head.zRot = Mth.lerp(reach, 0.5f, 0.15f);
			m.head.xRot = Mth.lerp(rest, 0.05f, 0.6f);
		}
		m.leftArm.xRot = throat;
		m.leftArm.yRot = 0.3f;
		m.rightArm.xRot = knife;
		m.rightArm.yRot = knifeY;
		if (t < MichaelKill.P_LEAVE) {
			m.head.xRot = t < MichaelKill.P_SLAM ? 0.1f : -0.05f;
		}
	}

	private static void pinThem(HumanoidModel<?> m, float t, float now) {
		float a = Mth.sin(now * 1.3f);
		float l = Mth.sin(now * 1.6f);
		if (t < MichaelKill.P_STAB) {
			// Clawing at his arm, stumbling back, then kicking up the wall; slammed against it.
			m.rightArm.xRot = -2.1f + a * 0.3f;
			m.leftArm.xRot = -2.1f - a * 0.3f;
			m.rightArm.yRot = -0.45f;
			m.leftArm.yRot = 0.45f;
			m.rightArm.zRot = 0.3f;
			m.leftArm.zRot = -0.3f;
			m.rightLeg.xRot = l * 0.65f;
			m.leftLeg.xRot = -l * 0.65f;
			m.head.xRot = -0.5f - 0.5f * jolt(t, MichaelKill.P_SLAM) + Mth.cos(now * 1.1f) * 0.06f;
			m.head.zRot = Mth.sin(now * 0.5f) * 0.12f;
			return;
		}
		if (t >= MichaelKill.P_PULL) {
			// The knife pulled out (a jerk towards him), down the wall, the knees giving: sitting against it, slumped.
			float j = jolt(t, MichaelKill.P_PULL);
			slump(m, fold(t));
			m.head.xRot += 0.35f * j;
			m.rightArm.xRot -= 0.3f * j;
			m.leftArm.xRot -= 0.3f * j;
			return;
		}
		// Run through: hands to the knife, kicking weaker and weaker, then still, the head hanging.
		float j = jolt(t, MichaelKill.P_STAB);
		float weak = smooth((t - 44) / 20f);
		float life = 1f - weak;
		m.rightArm.xRot = Mth.lerp(weak, -0.95f + a * 0.08f, 0.05f) - 0.4f * j;
		m.leftArm.xRot = Mth.lerp(weak, -0.95f - a * 0.08f, 0.05f) - 0.4f * j;
		m.rightArm.yRot = Mth.lerp(weak, -0.5f, 0f);
		m.leftArm.yRot = Mth.lerp(weak, 0.5f, 0f);
		m.rightArm.zRot = Mth.lerp(weak, 0f, 0.15f) + 0.5f * j;
		m.leftArm.zRot = Mth.lerp(weak, 0f, -0.15f) - 0.5f * j;
		m.rightLeg.xRot = l * 0.5f * life - 0.5f * j + 0.05f;
		m.leftLeg.xRot = -l * 0.45f * life - 0.4f * j - 0.05f;
		m.head.xRot = Mth.lerp(weak, -0.2f, 0.7f) + 0.6f * j;
		m.head.zRot = 0.2f * weak;
	}

	/**
	 * From hanging dead on the wall ({@code k} 0) to sitting against it on the floor (1): the legs out along the floor and
	 * a little apart, the hands in the lap and at the side, the head down on the chest and over to one side.
	 */
	private static void slump(HumanoidModel<?> m, float k) {
		m.rightLeg.xRot = Mth.lerp(k, 0.05f, -1.47f);
		m.leftLeg.xRot = Mth.lerp(k, -0.05f, -1.38f);
		m.rightLeg.yRot = 0.22f * k;
		m.leftLeg.yRot = -0.16f * k;
		m.rightArm.xRot = Mth.lerp(k, 0.05f, -0.42f);
		m.leftArm.xRot = Mth.lerp(k, 0.05f, -0.12f);
		m.rightArm.yRot = -0.15f * k;
		m.rightArm.zRot = Mth.lerp(k, 0.15f, 0.1f);
		m.leftArm.zRot = Mth.lerp(k, -0.15f, -0.32f);
		m.head.xRot = Mth.lerp(k, 0.7f, 0.82f);
		m.head.zRot = Mth.lerp(k, 0.2f, 0.34f);
		m.head.yRot = 0.12f * k;
	}

	/**
	 * His knife left in the one he pinned to the wall (from mixin.LivingEntityRendererMixin, after their model): in their
	 * chest, where his fist drove it, the handle out towards him; his own blade if he carries one (CosmeticRenderer).
	 */
	public static void submitStuck(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack pose, int light, int overlay, int tint,
		int outline) {
		if (!(state instanceof AvatarRenderState s) || !(model instanceof HumanoidModel<?> h) || tint != -1 || s.isInvisible) {
			return;
		}
		Scene sc = live(SCENES.get(((HwRenderState) s).halloween$entityId()));
		if (sc == null) {
			return;
		}
		float t = now() - sc.start;
		if (t < MichaelKill.P_LEAVE || t >= MichaelKill.P_PULL) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Entity michael = mc.level == null ? null : mc.level.getEntity(sc.michael);
		pose.pushPose();
		h.root().translateAndRotate(pose);
		h.body.translateAndRotate(pose);
		// An arm (a right one, as his) reaching into them from before their chest, its line through them into the wall.
		pose.translate(STUCK_X / 16f, STUCK_Y / 16f, STUCK_Z / 16f);
		pose.mulPose(Axis.YP.rotation(STUCK_YAW));
		pose.mulPose(Axis.XP.rotation(STUCK_PITCH));
		CosmeticModels.Def blade = CosmeticRenderer.blade(michael);
		if (blade != null) {
			CosmeticRenderer.drawBlade(collector, blade, s, pose, light, overlay, outline);
		} else {
			// The knife as a right hand holds it (ItemInHandLayer), turned along the arm.
			mc.getItemModelResolver().updateForTopItem(STUCK, sc.knife, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, mc.level, null, sc.michael);
			pose.mulPose(Axis.XP.rotationDegrees(-90f));
			pose.mulPose(Axis.YP.rotationDegrees(180f));
			pose.translate(1f / 16f, 2f / 16f, -10f / 16f);
			pose.mulPose(Axis.XP.rotation(ITEM_ALONG));
			STUCK.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, outline);
		}
		pose.popPose();
	}
}
