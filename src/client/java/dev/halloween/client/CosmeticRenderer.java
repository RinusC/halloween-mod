package dev.halloween.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.halloween.Halloween;
import dev.halloween.Role;
import dev.halloween.game.Cosmetics;
import dev.halloween.game.HeroInfo;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Draws what everyone wears (game.Cosmetics) on them: each thing is a model of its own (CosmeticModels) put on
 * the player model's head, body or knife hand as they stand this frame, and moved by how they move (running,
 * turning, jumping; see {@link Motion}). Survivors wear the survivors' track (in the lobby too), Michael his own;
 * the Demogorgon and Vecna nothing. The character select's models wear one's own, or what is being tried on.
 */
public final class CosmeticRenderer {
	/** Who wears what, as the server says. */
	private static final Map<UUID, List<String>> WORN = new HashMap<>();
	/** The character select: what the models show instead of what one wears (null: what one wears). */
	public static List<String> tryOn;
	/** For tests: everyone (bots too) wears these. */
	public static List<String> forced;
	private static Map<String, CosmeticModels.Def> defs;
	private static final Map<Integer, Motion> MOTION = new HashMap<>();

	private CosmeticRenderer() {
	}

	public static void apply(HwNet.CosmeticSync sync) {
		Minecraft.getInstance().execute(() -> {
			WORN.clear();
			for (int i = 0; i < sync.players().size(); i++) {
				WORN.put(sync.players().get(i), List.of(sync.items().get(i).split(",")));
			}
		});
	}

	public static List<String> worn(UUID id) {
		return WORN.getOrDefault(id, List.of());
	}

	/** What I wear. */
	public static List<String> mine() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? List.of() : worn(mc.player.getUUID());
	}

	static Map<String, CosmeticModels.Def> defs() {
		if (defs == null) {
			defs = new HashMap<>();
			for (CosmeticModels.Def d : CosmeticModels.all()) {
				defs.put(d.id(), d);
			}
		}
		return defs;
	}

	/** Paints a model's texture into the game (and what of it glows); returns the two ids (the second null if none). */
	static Identifier[] upload(String id, CosmeticKit kit) {
		Minecraft mc = Minecraft.getInstance();
		Identifier tex = Halloween.id("cosmetic/" + id);
		mc.getTextureManager().register(tex, new DynamicTexture(() -> "halloween cosmetic " + id, kit.paint(false)));
		NativeImage glow = kit.paint(true);
		Identifier glowId = null;
		if (glow != null) {
			glowId = Halloween.id("cosmetic/" + id + "_glow");
			mc.getTextureManager().register(glowId, new DynamicTexture(() -> "halloween cosmetic glow " + id, glow));
		}
		return new Identifier[] {tex, glowId};
	}

	// ---------------------------------------------------------------- motion

	/** How someone moves, smoothed, for the things on them to follow (tips swaying, wings flapping). */
	public static final class Motion {
		/** Ticks (with the partial one). */
		public float t;
		/** Speed over the ground, 0 standing to about 1 sprinting. */
		public float speed;
		/** The walk's stride and how much of it (as the player model's). */
		public float walkPos;
		public float walkAmt;
		/** In the air (0..1, eased), and ticks since they last left the ground (large when on it long). */
		public float air;
		public float sinceJump = 999;
		/** Turning: degrees a tick, smoothed. */
		public float turn;
		/** A spring pulled back by speeding up (forward/back) and to the side by turning: for things that dangle. */
		public float swing;
		public float swingV;
		public float side;
		public float sideV;
		/** Up and down, from landing and the stride. */
		public float bounce;
		public float bounceV;
		public int seed;
		private float lastT = Float.NaN;
		private float lastYaw;
		private float lastSpeed;
		private boolean wasAir;

		void update(float t, float speed, boolean inAir, float yaw, float walkPos, float walkAmt) {
			float dt = Float.isNaN(this.lastT) ? 0f : Mth.clamp(t - this.lastT, 0f, 3f);
			this.lastT = t;
			this.t = t;
			this.walkPos = walkPos;
			this.walkAmt = walkAmt;
			if (dt <= 0f) {
				this.lastYaw = yaw;
				return;
			}
			float k = 1f - (float) Math.exp(-dt * 0.35f);
			this.speed = Mth.lerp(k, this.speed, Mth.clamp(speed, 0f, 1.4f));
			float accel = (this.speed - this.lastSpeed) / dt;
			this.lastSpeed = this.speed;
			float dyaw = Mth.wrapDegrees(yaw - this.lastYaw) / dt;
			this.lastYaw = yaw;
			this.turn = Mth.lerp(1f - (float) Math.exp(-dt * 0.3f), this.turn, Mth.clamp(dyaw, -40f, 40f));
			if (inAir && !this.wasAir) {
				this.sinceJump = 0f;
				this.bounceV -= 1.5f;
			}
			if (!inAir && this.wasAir) {
				// Landing: everything dangling drops and comes back.
				this.bounceV += 2.2f;
			}
			this.wasAir = inAir;
			this.sinceJump += dt;
			this.air = Mth.lerp(1f - (float) Math.exp(-dt * 0.5f), this.air, inAir ? 1f : 0f);
			// Springs: a target, a pull and a damping; a few small steps for steadiness.
			float stride = Mth.sin(walkPos * 0.6662f * 2f) * walkAmt;
			int steps = Math.max(1, Math.round(dt * 2));
			float h = dt / steps;
			for (int i = 0; i < steps; i++) {
				this.swingV += ((this.speed * 1.2f + Math.max(0f, accel) * 6f) - this.swing) * 0.25f * h - this.swingV * 0.22f * h;
				this.swing += this.swingV * h;
				this.sideV += (-this.turn / 25f - this.side) * 0.22f * h - this.sideV * 0.2f * h;
				this.side += this.sideV * h;
				this.bounceV += (stride * 0.6f - this.bounce) * 0.3f * h - this.bounceV * 0.25f * h;
				this.bounce += this.bounceV * h;
			}
		}
	}

	static Motion motion(EntityRenderState s) {
		return MOTION.computeIfAbsent(((HwRenderState) s).halloween$entityId(), k -> {
			Motion m = new Motion();
			m.seed = k;
			return m;
		});
	}

	// ---------------------------------------------------------------- drawing

	/** Whose things these are on the model: the track (Cosmetics.SURVIVOR / KILLER) and the items, or null for none. */
	record Wearer(int track, List<String> items) {
	}

	static Wearer wearer(EntityRenderState state) {
		int id = ((HwRenderState) state).halloween$entityId();
		if (HeroPreview.is(id)) {
			String who = HeroPreview.who(id);
			if (who == null || HeroInfo.killer(who) && !who.equals("michael")) {
				return null;
			}
			List<String> items = forced != null ? forced : tryOn != null ? tryOn : mine();
			return new Wearer(who.equals("michael") ? Cosmetics.KILLER : Cosmetics.SURVIVOR, items);
		}
		Minecraft mc = Minecraft.getInstance();
		Entity e = mc.level == null ? null : mc.level.getEntity(id);
		if (!(e instanceof Player)) {
			return null;
		}
		List<String> items = forced != null ? forced : worn(e.getUUID());
		if (items.isEmpty()) {
			return null;
		}
		Role role = ClientState.ROLES.get(e.getUUID());
		if (MichaelRenderer.isMichael(e)) {
			if (role != Role.SHAPE || MichaelRenderer.demogorgon() || MichaelRenderer.vecna()) {
				return null;
			}
			return new Wearer(Cosmetics.KILLER, items);
		}
		if (role != null && !role.survivor()) {
			return null;
		}
		return new Wearer(Cosmetics.SURVIVOR, items);
	}

	/** Does Michael hold one of his own in place of the knife (the knife then not drawn)? */
	public static boolean ownBlade(EntityRenderState state) {
		if (!(state instanceof AvatarRenderState)) {
			return false;
		}
		Wearer w = wearer(state);
		if (w == null || w.track != Cosmetics.KILLER) {
			return false;
		}
		for (String item : w.items) {
			Cosmetics.Item i = Cosmetics.byId(item);
			if (i != null && i.slot() == Cosmetics.Slot.HAND) {
				return true;
			}
		}
		return false;
	}

	/** After the player model (or Michael's) is submitted: what they wear, on it (see mixin.LivingEntityRendererMixin). */
	public static void submit(SubmitNodeCollector collector, Model<?> playerModel, Object state, PoseStack pose, int light, int overlay, int tint, int outline) {
		if (!(state instanceof AvatarRenderState s) || !(playerModel instanceof HumanoidModel<?> humanoid) || tint != -1 || s.isInvisible) {
			return;
		}
		Wearer w = wearer(s);
		if (w == null || w.items.isEmpty()) {
			return;
		}
		Motion m = motion(s);
		int id = ((HwRenderState) s).halloween$entityId();
		Minecraft mc = Minecraft.getInstance();
		Entity e = mc.level == null || HeroPreview.is(id) ? null : mc.level.getEntity(id);
		if (e != null) {
			double dx = e.getX() - e.xo;
			double dz = e.getZ() - e.zo;
			m.update(s.ageInTicks, (float) Math.sqrt(dx * dx + dz * dz) / 0.22f, !e.onGround() && !e.isPassenger(), s.bodyRot, s.walkAnimationPos,
				s.walkAnimationSpeed);
		} else {
			// The character select: a hop every few seconds, so wings and hats show what they do.
			float hop = s.ageInTicks % 90f;
			m.update(s.ageInTicks, s.walkAnimationSpeed * 0.9f, hop < 9f, s.bodyRot, s.walkAnimationPos, s.walkAnimationSpeed);
		}
		// The player model as it stands this frame (it is set up again for the layers right after).
		@SuppressWarnings({"unchecked", "rawtypes"})
		HumanoidModel<AvatarRenderState> h = (HumanoidModel) humanoid;
		h.setupAnim(s);
		for (String item : w.items) {
			Cosmetics.Item info = Cosmetics.byId(item);
			CosmeticModels.Def d = defs().get(item);
			if (info == null || d == null || info.track() != w.track) {
				continue;
			}
			if (info.slot() == Cosmetics.Slot.HAND && MichaelKillScene.inWall(id)) {
				// His blade is in the one he pinned to the wall (MichaelKillScene draws it there).
				continue;
			}
			if (item.equals("shadow")) {
				shadow(collector, h, s, pose, light, m, d);
				continue;
			}
			draw(collector, h, s, pose, light, overlay, outline, m, d);
		}
	}

	private static void anchor(PoseStack pose, HumanoidModel<?> h, CosmeticModels.Anchor a, int id) {
		h.root().translateAndRotate(pose);
		switch (a) {
			case HEAD -> h.head.translateAndRotate(pose);
			case BODY -> h.body.translateAndRotate(pose);
			case HAND -> {
				h.rightArm.translateAndRotate(pose);
				// The grip in the fist; turned at the wrist so the point stays down as the arm comes up (as his knife),
				// or along the arm, point first, as it goes in in one of his kills.
				pose.translate(-1f / 16f, 10.5f / 16f, -0.2f / 16f);
				if (!MichaelKillScene.along(id)) {
					pose.mulPose(Axis.XP.rotation(-MichaelRenderer.WRIST * h.rightArm.xRot));
				}
			}
		}
	}

	/** The blade Michael carries in place of the knife (one of his own that he wears), or null: the knife. */
	public static CosmeticModels.Def blade(Entity michael) {
		if (michael == null) {
			return null;
		}
		for (String item : forced != null ? forced : worn(michael.getUUID())) {
			Cosmetics.Item i = Cosmetics.byId(item);
			if (i != null && i.track() == Cosmetics.KILLER && i.slot() == Cosmetics.Slot.HAND) {
				return defs().get(item);
			}
		}
		return null;
	}

	/** That blade in a fist at {@code pose} (a right arm's frame, as the hand anchor's), along the arm. */
	public static void drawBlade(SubmitNodeCollector collector, CosmeticModels.Def d, AvatarRenderState s, PoseStack pose, int light, int overlay, int outline) {
		pose.pushPose();
		pose.translate(-1f / 16f, 10.5f / 16f, -0.2f / 16f);
		collector.submitModel(d.model(), s, pose, RenderTypes.entityCutoutCull(d.texture()), light, overlay, -1, null, outline, null);
		pose.popPose();
	}

	private static void draw(SubmitNodeCollector collector, HumanoidModel<?> h, AvatarRenderState s, PoseStack pose, int light, int overlay, int outline,
		Motion m, CosmeticModels.Def d) {
		pose.pushPose();
		anchor(pose, h, d.anchor(), ((HwRenderState) s).halloween$entityId());
		d.model().motion = m;
		float alpha = d.alpha() == null ? 1f : d.alpha().apply(m);
		if (d.translucent()) {
			collector.submitModel(d.model(), s, pose, RenderTypes.entityTranslucent(d.texture()), light, overlay, ARGB.color(alpha, 0xFFFFFF), null, outline, null);
		} else {
			collector.submitModel(d.model(), s, pose, RenderTypes.entityCutoutCull(d.texture()), light, overlay, -1, null, outline, null);
		}
		if (d.glow() != null) {
			float g = d.glowLevel() == null ? 1f : d.glowLevel().apply(m);
			if (g > 0.01f) {
				collector.submitModel(d.model(), s, pose, RenderTypes.entityTranslucentEmissive(d.glow()), 0xF000F0, overlay,
					ARGB.color(Mth.clamp(g, 0f, 1f) * alpha, 0xFFFFFF), null, 0, null);
			}
		}
		pose.popPose();
	}

	/**
	 * His shadow: Michael again a step behind him and off to the side, near black and see-through, slow to follow
	 * when he turns, now and then gone for a blink; two red points for eyes.
	 */
	private static void shadow(SubmitNodeCollector collector, HumanoidModel<AvatarRenderState> h, AvatarRenderState s, PoseStack pose, int light, Motion m,
		CosmeticModels.Def eyes) {
		float t = m.t;
		float flick = CosmeticModels.flicker(t * 0.6f, m.seed);
		float a = 0.45f * (0.75f + 0.25f * Mth.sin(t * 0.07f)) * (flick < 0.06f ? 0.2f : 1f);
		pose.pushPose();
		// Behind (+z in the model's space), a little to his left, lagging when he turns.
		float lag = Mth.clamp(m.turn * 0.04f, -0.6f, 0.6f);
		pose.translate(0.28f + lag * 0.5f, -0.02f + 0.03f * Mth.sin(t * 0.05f), 0.95f + Math.min(0.5f, m.speed * 0.35f));
		pose.mulPose(Axis.YP.rotation(lag * 0.5f));
		MichaelRenderer.submitShadow(collector, h, s, pose, light, ARGB.color(a, 0x0C0610));
		// The eyes, on its head as it stands.
		pose.pushPose();
		anchor(pose, h, CosmeticModels.Anchor.HEAD, 0);
		eyes.model().motion = m;
		collector.submitModel(eyes.model(), s, pose, RenderTypes.entityTranslucentEmissive(eyes.glow()), 0xF000F0, OverlayTexture.NO_OVERLAY,
			ARGB.color(Mth.clamp(a * 1.5f, 0f, 1f), 0xFFFFFF), null, 0, null);
		pose.popPose();
		pose.popPose();
	}

	/** A cosmetic's model: its bones by name, moved each frame by its own animation. */
	static final class CosModel extends Model<AvatarRenderState> {
		final Function<String, ModelPart> parts;
		final CosmeticModels.Anim anim;
		Motion motion;
		private final Map<String, ModelPart> cache = new HashMap<>();

		CosModel(ModelPart root, CosmeticModels.Anim anim) {
			super(root, RenderTypes::entityCutout);
			this.parts = root.createPartLookup();
			this.anim = anim;
		}

		ModelPart p(String name) {
			return this.cache.computeIfAbsent(name, n -> {
				ModelPart part = this.parts.apply(n);
				if (part == null) {
					throw new IllegalStateException("No bone " + n);
				}
				return part;
			});
		}

		@Override
		public void setupAnim(AvatarRenderState s) {
			this.resetPose();
			if (this.anim != null) {
				Motion m = this.motion != null ? this.motion : new Motion();
				// Each one's own motion (the same model is drawn on everyone).
				Motion own = MOTION.get(((HwRenderState) s).halloween$entityId());
				this.anim.apply(this, s, own != null ? own : m);
			}
		}
	}

	/** For tests: the cosmetics that have models. */
	public static List<String> modelled() {
		return new ArrayList<>(defs().keySet());
	}
}
