package dev.halloween.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.halloween.Halloween;
import dev.halloween.Role;
import dev.halloween.game.HwItems;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Hands in first person, for survivors and the Runt (instead of vanilla's lone arm, or none with something in
 * hand). Whatever is held stays where vanilla puts it (its first-person display) and moves with what we do; the
 * arms reach it from out of view at the bottom: long weapons in both hands, the rest in the right. What the hands
 * do follows what the player does: swinging, throwing, shoving, aiming, the kick of a shot, reloading, pushing a
 * door open, a lock, climbing through a window, searching, a phone at the ear, wires and repairs, a med kit,
 * fighting Michael off in his grip, tearing at the Runt's hands at one's throat. The Runt's are his own long
 * forearms and claws: hanging, crawling, raised to pounce, flung out in a leap, round someone's throat, limp.
 */
public final class ViewArms {
	public enum Act {
		NONE, SHOVE, DOOR, VAULT, THROW, SPRAY, RECOIL, RELOAD, STAB, KNOCK, PULL
	}

	/** Which hand the last pull went out with (the side the thing was on). */
	public static boolean pullRight;

	/** For tests: vanilla's hands instead. */
	public static boolean enabled = true;
	/** For tests: aim as if the button were held (null: as it is). */
	public static Boolean testAim;
	private static Act act = Act.NONE;
	private static float actStart;
	private static HwItems.Kind lastKind;
	private static float equipStart = -100;
	private static float aim;
	private static float aimBefore;
	private static long aimAt;
	/** Which way the last shot's kick throws the gun (random each shot: -1 left .. 1 right). */
	private static float kickSide;
	private static BbModel claws;
	private static final Identifier RUNT_TEXTURE = Halloween.id("textures/entity/runt.png");

	private ViewArms() {
	}

	/** Something the player just did that the hands show. */
	public static void trigger(Act a) {
		act = a;
		actStart = now(0f);
		if (a == Act.RECOIL) {
			Minecraft mc = Minecraft.getInstance();
			kickSide = mc.level == null ? 0f : mc.level.getRandom().nextFloat() * 2f - 1f;
		}
	}

	// ---------------------------------------------------------------- iron sights

	/**
	 * A gun's iron sights in its 3D model (model units, 16 a block, as the model files have them): the top of the
	 * rear sight and of the front sight, the line one looks along; how far ahead of the eye the rear sight is held
	 * (a revolver out at arm's length, a long gun shouldered with the cheek on the stock); and the model's
	 * first-person display (turn about y in degrees, offset in units, scale), as in its file.
	 */
	private record Sights(Vector3f rear, Vector3f front, float eye, float displayYaw, Vector3f displayOffset, float displayScale) {
		/** A point of the model where the item renderer puts it, before our own placing and turning of it. */
		Vector3f local(Vector3f m) {
			Vector3f v = new Vector3f(m).div(16f).sub(0.5f, 0.5f, 0.5f).mul(this.displayScale);
			new Quaternionf().rotationY(this.displayYaw * Mth.DEG_TO_RAD).transform(v);
			return v.add(new Vector3f(this.displayOffset).div(16f));
		}
	}

	/**
	 * The line over a gun's sights as drawn under {@code pose} (the matrix the item is handed to the item renderer with):
	 * the rear sight and the direction to the front one, in that matrix's space. Null for what has no sights.
	 */
	public static Vector3f @org.jspecify.annotations.Nullable [] sightLine(HwItems.Kind kind, org.joml.Matrix4fc pose) {
		Sights s = sights(kind);
		if (s == null) {
			return null;
		}
		Vector3f rear = pose.transformPosition(s.local(s.rear));
		Vector3f front = pose.transformPosition(s.local(s.front));
		return new Vector3f[]{rear, front.sub(rear).normalize()};
	}

	private static Sights sights(HwItems.Kind kind) {
		return switch (kind) {
			case REVOLVER -> new Sights(new Vector3f(8f, 9.82f, 11.73f), new Vector3f(8f, 9.82f, 2.84f), 0.4f, 0f, new Vector3f(1f, 2.5f, -1f), 0.8f);
			case RIFLE -> new Sights(new Vector3f(7.245f, 11.55f, 7.15f), new Vector3f(7.245f, 11.55f, 24.7f), 0.3f, 180f, new Vector3f(1f, 1.5f, 0f), 0.65f);
			case SHOTGUN -> new Sights(new Vector3f(8f, 12.6f, 10.8f), new Vector3f(8f, 12.6f, 21.65f), 0.38f, 180f, new Vector3f(1f, 1.5f, 0f), 0.65f);
			default -> null;
		};
	}

	/**
	 * Brought up to the eye ({@code k} of the way): turned so the line over the sights runs straight ahead and moved
	 * so it runs through the middle of the view, the rear sight its distance ahead of the eye.
	 */
	private static void toSights(Pose p, Sights s, float k) {
		Vector3f rear = p.itemTurn.transform(s.local(s.rear));
		Vector3f front = p.itemTurn.transform(s.local(s.front));
		Vector3f along = front.sub(rear).normalize();
		Quaternionf turned = new Quaternionf().rotationTo(along, new Vector3f(0, 0, -1)).mul(p.itemTurn);
		Vector3f at = new Vector3f(0, 0, -s.eye).sub(turned.transform(s.local(s.rear)));
		p.item.lerp(at, k);
		p.itemTurn.slerp(turned, k);
	}

	/** Using what is in hand: a throw for what is thrown, a spray for the spray. */
	public static void used(HwItems.Kind kind) {
		if (kind == null) {
			return;
		}
		if (kind.thrown() || kind == HwItems.Kind.ROAD_FLARE || kind == HwItems.Kind.FIRECRACKERS) {
			trigger(Act.THROW);
		} else if (kind == HwItems.Kind.HEALING_SPRAY) {
			trigger(Act.SPRAY);
		} else if (kind == HwItems.Kind.FLARE_GUN) {
			trigger(Act.RECOIL);
		}
	}

	private static float now(float partial) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getGameTime() + partial;
	}

	private static float length(Act a) {
		return switch (a) {
			case SHOVE -> 9;
			case DOOR -> 10;
			case VAULT -> 14;
			case THROW -> 10;
			case SPRAY -> 12;
			case RECOIL -> 18;
			case RELOAD -> 20;
			case STAB -> 8;
			case KNOCK -> 8;
			case PULL -> 13;
			default -> 0;
		};
	}

	/** How far through the current one-off action (0..1), or -1 if none is going. */
	private static float actProgress(Act a, float t) {
		if (act != a) {
			return -1;
		}
		float p = (t - actStart) / Math.max(1, length(a));
		if (p < 0 || p > 1) {
			if (p > 1) {
				act = Act.NONE;
			}
			return -1;
		}
		return p;
	}

	/** Up and back down over 0..1 (a bump). */
	private static float bump(float p) {
		return p < 0 ? 0 : Mth.sin(p * Mth.PI);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0f, 1f);
		return x * x * (3 - 2 * x);
	}

	// ---------------------------------------------------------------- what is held, and how

	private enum Grip {
		EMPTY, ONE_HAND, TWO_HAND_MELEE, LONG_GUN, PISTOL
	}

	private static Grip grip(HwItems.Kind kind, ItemStack stack) {
		if (stack.isEmpty()) {
			return Grip.EMPTY;
		}
		if (kind == null) {
			return Grip.ONE_HAND;
		}
		return switch (kind) {
			case RIFLE, SHOTGUN -> Grip.LONG_GUN;
			case REVOLVER, FLARE_GUN -> Grip.PISTOL;
			case BAT, AXE, PICKAXE, PITCHFORK, PIPE_WRENCH, FIRE_POKER -> Grip.TWO_HAND_MELEE;
			default -> Grip.ONE_HAND;
		};
	}

	/** Where the hands and what is held are this frame (camera space, blocks: x right, y up, -z ahead). */
	private static final class Pose {
		final Vector3f item = new Vector3f(0.56f, -0.52f, -0.72f);
		final Quaternionf itemTurn = new Quaternionf();
		boolean showItem = true;
		final Vector3f right = new Vector3f();
		final Vector3f left = new Vector3f();
		boolean showRight = true;
		boolean showLeft;
		/** Where each elbow is (out of view; null: below the hand, the usual). */
		Vector3f rightFrom;
		Vector3f leftFrom;
	}

	// ---------------------------------------------------------------- drawing

	/** Draws the hands; true if it did (vanilla's are then skipped). */
	public static boolean render(ItemInHandRenderer renderer, float partial, PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light) {
		Role role = ClientState.myRole();
		// At the school's range between matches: hands on the gun too.
		boolean range = !ClientState.inMatch() && ClientState.lobby.inLobby() && ClientGuns.heldGun(Minecraft.getInstance()) != null;
		if (!enabled || !range && (ClientState.match.state() == 0 || !(role.survivor() || role == Role.RUNT))) {
			return false;
		}
		if (player.isInvisible() || player.isSpectator()) {
			// In a locker: nothing to see of oneself.
			return true;
		}
		float t = now(partial);
		// Vanilla's sway as the view turns.
		float xBob = Mth.lerp(partial, player.xBobO, player.xBob);
		float yBob = Mth.lerp(partial, player.yBobO, player.yBob);
		pose.pushPose();
		pose.mulPose(Axis.XP.rotationDegrees((player.getViewXRot(partial) - xBob) * 0.1f));
		pose.mulPose(Axis.YP.rotationDegrees((player.getViewYRot(partial) - yBob) * 0.1f));
		if (role == Role.RUNT) {
			runt(pose, collector, player, light, t, partial);
		} else {
			survivor(renderer, pose, collector, player, light, t, partial);
		}
		pose.popPose();
		return true;
	}

	private static void survivor(ItemInHandRenderer renderer, PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light, float t, float partial) {
		Minecraft mc = Minecraft.getInstance();
		ItemStack stack = player.getMainHandItem();
		HwItems.Kind kind = HwItems.kindOf(stack);
		Grip grip = grip(kind, stack);
		if (kind != lastKind) {
			lastKind = kind;
			equipStart = t;
		}
		long gameTime = mc.level.getGameTime();
		if (gameTime != aimAt) {
			aimAt = gameTime;
			aimBefore = aim;
			aim = Mth.clamp(aim + ((testAim != null ? testAim : ClientGuns.aiming()) ? 0.25f : -0.25f), 0f, 1f);
		}
		// Smooth between ticks, eased in and out.
		float aimed = Mth.lerp(partial, aimBefore, aim);
		aimed = aimed * aimed * (3 - 2 * aimed);
		Pose p = new Pose();
		switch (grip) {
			case LONG_GUN -> p.item.set(0.34f, -0.24f, -0.72f);
			case PISTOL -> p.item.set(0.38f, -0.22f, -0.72f);
			case TWO_HAND_MELEE -> p.item.set(0.46f, -0.3f, -0.72f);
			case ONE_HAND -> p.item.set(0.48f, -0.3f, -0.72f);
			default -> {
			}
		}
		walkPos = player.walkAnimation.position(partial);
		walkSpeed = Mth.clamp(player.walkAnimation.speed(partial) * (player.isSprinting() ? 1.6f : 1f), 0f, 1f);
		String work = ClientState.progress.label();
		float attack = player.getAttackAnim(partial);
		boolean grabbed = ClientState.qte.active() && !ClientState.qte.forShape();
		boolean strangled = ClientAnims.hold(player.getId()) == dev.halloween.net.HwNet.Anim.STRUGGLE || mc.gui.screen() instanceof HandsScreen;
		// Breathing, and a lowered weapon on the run.
		p.item.y += Mth.sin(t * 0.09f) * 0.006f;
		float run = player.isSprinting() ? 1f : 0f;
		p.item.add(0.04f * run, -0.07f * run, 0.06f * run);
		p.itemTurn.rotateY(-0.35f * run).rotateX(0.15f * run);
		// Just took it out: it comes up from below.
		float equip = Mth.clamp((t - equipStart) / 6f, 0f, 1f);
		p.item.y -= 0.45f * (1 - equip) * (1 - equip);
		hands(p, grip, t);

		if (grabbed) {
			// In Michael's grip: both hands up against him, shoving, shaking; a knife stabbing if one is used.
			float shake = Mth.sin(t * 2.7f) * 0.03f;
			float stab = bump(actProgress(Act.STAB, t));
			p.showItem = stab > 0;
			p.right.set(0.24f + shake, -0.22f - 0.1f * stab, -0.48f - 0.2f * stab);
			p.left.set(-0.24f - shake, -0.21f + shake, -0.5f);
			p.showLeft = true;
			p.item.set(p.right).add(-0.02f, 0.1f, -0.05f);
			p.itemTurn.identity().rotateX(-0.6f * stab);
		} else if (strangled) {
			// The Runt's hands at the throat: tearing at them.
			float pull = Mth.sin(t * 1.9f);
			p.showItem = false;
			p.right.set(0.11f + 0.03f * pull, -0.2f, -0.44f);
			p.left.set(-0.11f - 0.03f * pull, -0.19f + 0.02f * pull, -0.45f);
			p.showLeft = true;
		} else if (work.endsWith(".search")) {
			// Rummaging, both hands in it.
			float a = Mth.sin(t * 0.8f);
			p.showItem = false;
			p.right.set(0.17f + 0.05f * a, -0.25f + 0.03f * Mth.cos(t * 1.1f), -0.55f);
			p.left.set(-0.17f + 0.05f * a, -0.24f - 0.03f * Mth.cos(t * 0.9f), -0.56f);
			p.showLeft = true;
		} else if (work.endsWith(".call_police") || work.endsWith(".loomis")) {
			// The receiver at the ear.
			p.left.set(-0.3f, -0.1f, -0.36f);
			p.leftFrom = new Vector3f(-0.42f, -0.7f, 0.0f);
			p.showLeft = true;
		} else if (work.endsWith(".lock") || work.endsWith(".unlock")) {
			// Working the lock: a hand at it, turning.
			p.left.set(-0.14f, -0.18f + 0.02f * Mth.sin(t * 1.3f), -0.56f);
			p.showLeft = true;
			p.item.add(0.08f, -0.18f, 0.1f);
		} else if (work.endsWith(".vault_slow") || work.endsWith(".vault_fast") || actProgress(Act.VAULT, t) >= 0) {
			// Hands on the sill, pulling through.
			float a = work.isEmpty() ? bump(actProgress(Act.VAULT, t)) : 1f;
			p.showItem = false;
			p.right.set(0.3f, -0.28f + 0.06f * (1 - a), -0.62f);
			p.left.set(-0.3f, -0.28f + 0.06f * (1 - a), -0.62f);
			// Palms down on it, elbows out to the sides.
			p.rightFrom = new Vector3f(p.right).add(0.34f, -0.3f, 0.22f);
			p.leftFrom = new Vector3f(p.left).add(-0.34f, -0.3f, 0.22f);
			p.showLeft = true;
		} else if (work.endsWith(".locker")) {
			// Pulling the wardrobe doors to.
			float a = Mth.sin(t * 0.5f) * 0.03f;
			p.showItem = false;
			p.right.set(0.22f + a, -0.17f, -0.52f);
			p.left.set(-0.22f - a, -0.17f, -0.52f);
			p.showLeft = true;
		} else if (work.endsWith(".med_kit")) {
			// Bandaging: hands at the chest.
			float a = Mth.sin(t * 0.7f) * 0.04f;
			p.right.set(0.14f + a, -0.27f, -0.5f);
			p.left.set(-0.14f - a, -0.28f, -0.51f);
			p.showLeft = true;
			p.item.set(0.06f, -0.25f, -0.6f);
		} else if (work.endsWith(".reload") || actProgress(Act.RELOAD, t) >= 0) {
			// Rounds in one by one: the gun tilted, the free hand going to it and back.
			float a = Mth.abs(Mth.sin(t * 0.45f));
			p.item.add(-0.12f, 0.05f, 0.08f);
			p.itemTurn.rotateZ(0.45f).rotateX(-0.25f);
			p.left.set(held(p, 0.0f, -0.04f - 0.1f * (1 - a), 0.02f)).add(-0.06f * (1 - a), 0, 0);
			p.showLeft = true;
		} else if (!work.isEmpty() && !work.endsWith(".talk")) {
			// Wires, repairs, keys, fuel, the gate: both hands busy ahead.
			float a = Mth.sin(t * 0.9f);
			p.showItem = false;
			p.right.set(0.15f + 0.03f * a, -0.2f + 0.025f * Mth.sin(t * 1.4f), -0.58f);
			p.left.set(-0.15f + 0.03f * a, -0.21f - 0.025f * Mth.sin(t * 1.2f), -0.58f);
			p.showLeft = true;
		} else {
			actions(p, grip, t, attack);
		}
		// Aiming down the sights: the gun up to the eye, the line over its sights through the middle of the view.
		Sights sights = kind == null ? null : sights(kind);
		boolean reloading = work.endsWith(".reload") || actProgress(Act.RELOAD, t) >= 0;
		if (aimed > 0 && sights != null && !grabbed && !strangled && !reloading && work.isEmpty()) {
			toSights(p, sights, aimed);
			hands(p, grip, t);
			if (grip == Grip.PISTOL) {
				// Both hands on it: the left cupped under the right, elbows out.
				p.left.lerp(held(p, 0.02f, -0.07f, 0.13f), aimed);
				p.leftFrom = new Vector3f(p.left).add(-0.25f, -0.45f, 0.2f);
				p.rightFrom = new Vector3f(p.right).add(0.22f, -0.45f, 0.22f);
				p.showLeft = true;
			} else {
				// The elbows up and out, the stock in the shoulder.
				p.rightFrom = new Vector3f(p.right).add(0.32f, -0.3f, 0.25f);
				p.leftFrom = new Vector3f(p.left).add(-0.12f, -0.45f, 0.25f);
			}
		}
		recoil(p, grip, kind, t, aimed);

		Identifier skin = player.getSkin().body().texturePath();
		AvatarRenderer<net.minecraft.client.player.AbstractClientPlayer> avatar = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
		if (p.showItem && !stack.isEmpty()) {
			pose.pushPose();
			pose.translate(p.item.x, p.item.y, p.item.z);
			pose.mulPose(p.itemTurn);
			renderer.renderItem(player, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, pose, collector, light);
			pose.popPose();
		}
		if (p.showRight) {
			arm(pose, collector, light, avatar, skin, player, true, p.right, p.rightFrom != null ? p.rightFrom : below(p.right, 1f));
		}
		if (p.showLeft) {
			arm(pose, collector, light, avatar, skin, player, false, p.left, p.leftFrom != null ? p.leftFrom : below(p.left, -1f));
		}
	}

	/**
	 * A shot's kick: the gun driven back (into the shoulder, or the arms giving at the elbows), the muzzle thrown up
	 * and a little to one side, and brought back down. The shotgun is then pumped: the left hand runs the fore-end
	 * back and forward.
	 */
	private static void recoil(Pose p, Grip grip, HwItems.Kind kind, float t, float aimed) {
		if (act != Act.RECOIL) {
			return;
		}
		boolean shotgun = kind == HwItems.Kind.SHOTGUN;
		float len = shotgun ? 18f : kind == HwItems.Kind.RIFLE ? 10f : 8f;
		float e = t - actStart;
		if (e < 0 || e > len) {
			if (e > len) {
				act = Act.NONE;
			}
			return;
		}
		float strength = shotgun ? 1.25f : kind == HwItems.Kind.RIFLE ? 1f : kind == HwItems.Kind.REVOLVER ? 0.75f : 0.6f;
		// Up in a tick, down over five or six (the shoulder held firm brings it back sooner).
		float settle = grip == Grip.PISTOL ? 5f : 6f - 1.5f * aimed;
		float k = e < 1f ? e : Math.max(0f, 1f - (e - 1f) / settle);
		k = k * k * (3 - 2 * k) * strength;
		// Up at the eye the kick moves it less (the hold is firm), and the muzzle climbs about the shoulder (a long gun)
		// or the wrists (a revolver held out), not about the hand.
		float near = 1f - 0.75f * aimed;
		Vector3f back = new Vector3f(0.012f * kickSide * k * near, 0.03f * k * near, 0.12f * k * near);
		p.item.add(back);
		p.right.add(back);
		p.left.add(new Vector3f(back).mul(0.8f));
		// (Aimed, the view itself is thrown up with it: in the view the gun barely moves.)
		Vector3f grip0 = grip == Grip.PISTOL ? new Vector3f(p.right) : new Vector3f(0.12f * (1f - aimed), -0.1f - 0.1f * (1f - aimed), 0.1f);
		float climb = (0.24f - 0.19f * aimed) * k;
		Quaternionf flip = new Quaternionf().rotateX(climb).rotateY(-0.04f * kickSide * k).rotateZ(-0.05f * kickSide * k);
		p.item.sub(grip0);
		flip.transform(p.item);
		p.item.add(grip0);
		p.itemTurn.premul(flip);
		p.left.sub(grip0);
		flip.transform(p.left);
		p.left.add(grip0);
		p.right.sub(grip0);
		flip.transform(p.right);
		p.right.add(grip0);
		if (shotgun && e > 4f) {
			// Racking it: the fore-end back and forward again, the gun dipping a little with it.
			float r = Mth.clamp((e - 4f) / 12f, 0f, 1f);
			float pump = Mth.sin(r * Mth.PI);
			Vector3f slide = p.itemTurn.transform(new Vector3f(0f, 0f, 0.11f * pump));
			p.left.add(slide);
			p.item.add(0f, -0.02f * pump, 0.02f * pump);
			p.right.add(0f, -0.02f * pump, 0.02f * pump);
			p.left.add(0f, -0.02f * pump, 0.02f * pump);
		}
	}

	/** The elbow: below the hand (out of view), a little out to the side and back. */
	private static Vector3f below(Vector3f hand, float side) {
		return new Vector3f(hand).add(side * 0.14f, -0.5f, 0.3f);
	}

	/** Where the hands hold what is held, as it stands (they move with it from here on). */
	private static void hands(Pose p, Grip grip, float t) {
		switch (grip) {
			case EMPTY -> {
				// Swinging as one walks or runs, both on the run; else the right hanging at the edge.
				p.showItem = false;
				float ph = walkPos * 0.6662f;
				float sw = walkSpeed;
				p.right.set(0.3f, -0.22f + Mth.sin(t * 0.09f) * 0.006f + 0.12f * sw * Math.max(0, Mth.sin(ph)) - 0.05f * sw, -0.6f - 0.1f * sw * Mth.sin(ph));
				if (sw > 0.25f) {
					p.left.set(-0.3f, -0.27f + 0.12f * sw * Math.max(0, -Mth.sin(ph)), -0.6f + 0.1f * sw * Mth.sin(ph));
					p.showLeft = true;
				}
			}
			case LONG_GUN -> {
				// Right hand at the trigger, left under the fore-end (the shotgun's pump), as the models have them.
				boolean shotgun = lastKind == HwItems.Kind.SHOTGUN;
				p.right.set(held(p, shotgun ? 0.06f : 0.09f, shotgun ? -0.01f : -0.01f, shotgun ? 0.04f : 0.25f));
				p.left.set(held(p, shotgun ? 0.06f : 0.09f, shotgun ? 0.02f : 0.07f, shotgun ? -0.31f : -0.08f));
				p.showLeft = true;
			}
			case PISTOL -> p.right.set(held(p, 0.06f, -0.02f, 0.12f));
			case TWO_HAND_MELEE -> {
				p.right.set(held(p, 0.07f, -0.1f, -0.03f));
				p.left.set(held(p, 0.075f, -0.19f, -0.065f));
				p.showLeft = true;
			}
			default -> p.right.set(held(p, 0.07f, -0.1f, -0.03f));
		}
	}

	/** A point on what is held (offset from where it is held up, as it stands unturned), turned with it. */
	private static Vector3f held(Pose p, float x, float y, float z) {
		return p.itemTurn.transform(new Vector3f(x, y, z)).add(p.item);
	}

	private static float walkPos;
	private static float walkSpeed;

	/** What the hands do now and then: a swing, a shove, a throw, a spray, a stab, a door. */
	private static void actions(Pose p, Grip grip, float t, float attack) {
		float pull = actProgress(Act.PULL, t);
		if (pull >= 0) {
			// Flung out to the side for it, a grip on its edge, yanked back and down hard as it goes over.
			float out = pull < 0.4f ? smooth(pull / 0.4f) : 1f;
			float yank = pull < 0.4f ? 0f : smooth((pull - 0.4f) / 0.45f);
			float s = pullRight ? 1f : -1f;
			Vector3f hand = new Vector3f(0.3f * s, -0.33f, -0.5f).lerp(new Vector3f(0.62f * s, -0.16f, -0.44f), out).lerp(new Vector3f(0.42f * s, -0.62f, 0.02f), yank);
			if (pullRight) {
				Vector3f d = new Vector3f(hand).sub(p.right);
				p.right.set(hand);
				if (p.showItem) {
					p.item.add(d);
				}
			} else {
				p.left.set(hand);
				p.showLeft = true;
			}
			return;
		}
		float shove = actProgress(Act.SHOVE, t);
		if (shove >= 0) {
			// Both palms out, hard.
			float a = bump(shove);
			p.showItem = grip != Grip.EMPTY && grip != Grip.ONE_HAND ? p.showItem : false;
			p.right.set(0.24f, -0.25f + 0.12f * a, -0.46f - 0.22f * a);
			p.left.set(-0.24f, -0.25f + 0.12f * a, -0.46f - 0.22f * a);
			p.showLeft = true;
			if (p.showItem) {
				p.item.add(0.05f, -0.2f, 0.1f);
			}
			return;
		}
		float door = actProgress(Act.DOOR, t);
		if (door >= 0) {
			// A hand out to push the door.
			float a = bump(door);
			p.left.set(-0.32f + 0.08f * a, -0.33f + 0.2f * a, -0.5f - 0.2f * a);
			p.showLeft = true;
			return;
		}
		float th = actProgress(Act.THROW, t);
		if (th >= 0) {
			// Back over the shoulder, then out hard.
			float back = th < 0.45f ? th / 0.45f : 1 - (th - 0.45f) / 0.55f;
			float out = th < 0.45f ? 0 : bump((th - 0.45f) / 0.55f);
			p.item.add(0.04f * back, 0.18f * back - 0.1f * out, 0.1f * back - 0.4f * out);
			p.itemTurn.rotateX(-0.8f * back + 0.6f * out);
			p.right.set(p.item).add(0.0f, -0.12f, 0.1f);
			return;
		}
		float spray = actProgress(Act.SPRAY, t);
		if (spray >= 0) {
			// Held out ahead, pressing.
			float a = bump(Math.min(1f, spray * 2f));
			p.item.add(-0.22f * a, 0.12f * a, -0.12f * a);
			p.right.set(p.item).add(0.0f, -0.12f, 0.1f);
			return;
		}
		if (attack > 0) {
			float s = Mth.sqrt(attack);
			if (grip == Grip.TWO_HAND_MELEE) {
				// From up over the right shoulder down across to the left.
				p.item.add(-0.35f * Mth.sin(attack * Mth.PI), 0.25f * Mth.sin(s * Mth.PI) - 0.2f * attack, -0.25f * Mth.sin(attack * Mth.PI));
				p.itemTurn.rotateZ(0.9f * Mth.sin(s * Mth.PI)).rotateX(-1.1f * Mth.sin(attack * Mth.PI));
				hands(p, grip, t);
			} else if (grip == Grip.EMPTY) {
				// A fist out.
				p.right.add(-0.2f * Mth.sin(s * Mth.PI), 0.15f * Mth.sin(s * Mth.PI), -0.3f * Mth.sin(attack * Mth.PI));
			} else {
				// A slash from the right, across.
				p.item.add(-0.3f * Mth.sin(s * Mth.PI), 0.12f * Mth.sin(s * Mth.PI * 2), -0.3f * Mth.sin(attack * Mth.PI));
				p.itemTurn.rotateY(0.9f * Mth.sin(s * Mth.PI)).rotateX(-1.2f * Mth.sin(attack * Mth.PI));
				hands(p, grip, t);
			}
		}
	}

	/**
	 * One of the player's own arms, its hand at {@code hand}, coming in from {@code from} (out of view): the arm
	 * model's length runs from its shoulder to the hand, its outer side to the outside.
	 */
	private static void arm(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderer<net.minecraft.client.player.AbstractClientPlayer> avatar,
		Identifier skin, LocalPlayer player, boolean right, Vector3f hand, Vector3f elbow) {
		// From the elbow (always out of view, below) to the hand: the arm stretched or drawn in to fit, so it
		// never reaches back past the elbow into one's face.
		Vector3f along = new Vector3f(hand).sub(elbow);
		float length = along.length();
		Vector3f d = along.normalize();
		pose.pushPose();
		pose.translate(elbow.x, elbow.y, elbow.z);
		pose.mulPose(basis(d, new Vector3f(-1, 0, 0)));
		pose.scale(1f, Mth.clamp(length / (10f / 16f), 0.45f, 1.8f), 1f);
		// The arm's pivot sits off the model's middle: back onto the elbow.
		pose.translate(right ? 5f / 16f : -5f / 16f, -2f / 16f, 0f);
		if (right) {
			avatar.renderRightHand(pose, collector, light, skin, player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
		} else {
			avatar.renderLeftHand(pose, collector, light, skin, player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
		}
		pose.popPose();
	}

	/** A turn taking the model's +y onto {@code along}, its +x as near {@code side} as can be. */
	private static Quaternionf basis(Vector3f along, Vector3f side) {
		Vector3f y = new Vector3f(along).normalize();
		Vector3f x = new Vector3f(side).sub(new Vector3f(y).mul(side.dot(y)));
		if (x.lengthSquared() < 1.0E-5f) {
			x.set(0, 0, 1).sub(new Vector3f(y).mul(y.z));
		}
		x.normalize();
		Vector3f z = new Vector3f(x).cross(y);
		Matrix3f m = new Matrix3f(x, y, z);
		return new Quaternionf().setFromNormalized(m);
	}

	// ---------------------------------------------------------------- the Runt's

	private static BbModel claws() {
		if (claws == null) {
			claws = BbModel.load("/assets/halloween/models/entity/runt.json", Set.of("earg"));
		}
		return claws;
	}

	private static void runt(PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light, float t, float partial) {
		dev.halloween.net.HwNet.RuntHud hud = ClientState.runtHud;
		boolean holding = hud != null && hud.holding() > 0;
		boolean down = hud != null && hud.stunned() > 0;
		float attack = player.getAttackAnim(partial);
		Vector3f l = new Vector3f();
		Vector3f r = new Vector3f();
		float curl = 0f;
		float moving = Mth.clamp((float) player.getDeltaMovement().horizontalDistance() * 8f, 0f, 1f);
		if (down) {
			l.set(-0.42f, -0.72f, -0.5f);
			r.set(0.42f, -0.72f, -0.5f);
			curl = 0.2f;
		} else if (holding) {
			// Round their throat, squeezing.
			float sq = Mth.sin(t * 1.6f);
			l.set(-0.12f - 0.02f * sq, -0.3f, -0.48f);
			r.set(0.12f + 0.02f * sq, -0.3f, -0.48f);
			curl = 0.9f + 0.1f * sq;
		} else if (!player.onGround()) {
			// In the air: flung out ahead.
			l.set(-0.45f, -0.08f, -0.8f);
			r.set(0.45f, -0.08f, -0.8f);
			curl = 0.1f;
		} else if (player.isCrouching()) {
			// Wound up to pounce: up and trembling.
			float tr = Mth.sin(t * 3.1f) * 0.012f;
			l.set(-0.28f + tr, -0.16f, -0.66f);
			r.set(0.28f - tr, -0.16f - tr, -0.66f);
			curl = 0.5f;
		} else {
			// Crawling (on all fours the claws go down in turn), or hanging there twitching.
			float ph = player.walkAnimation.position(partial) * 0.6f;
			float tw = Mth.sin(t * 0.37f) * 0.01f;
			l.set(-0.34f, -0.36f + tw + 0.1f * moving * Math.max(0, Mth.sin(ph)), -0.6f - 0.18f * moving * Mth.sin(ph));
			r.set(0.34f, -0.36f - tw + 0.1f * moving * Math.max(0, -Mth.sin(ph)), -0.6f + 0.18f * moving * Mth.sin(ph));
			curl = 0.35f;
		}
		// Striking: knocking on a door or a window, frightening someone.
		float knock = Math.max(attack > 0 ? bump(attack) : 0, bump(actProgress(Act.KNOCK, t)));
		r.add(-0.12f * knock, 0.14f * knock, -0.28f * knock);
		claw(pose, collector, light, true, l, new Vector3f(-0.6f, -1.0f, 0.3f), curl);
		claw(pose, collector, light, false, r, new Vector3f(0.6f, -1.0f, 0.3f), curl);
	}

	/** One of his forearms, its claws at {@code hand}, coming in from {@code from}; the fingers curled by {@code curl}. */
	private static void claw(PoseStack pose, SubmitNodeCollector collector, int light, boolean left, Vector3f hand, Vector3f from, float curl) {
		BbModel m = claws();
		String side = left ? "L_" : "R_";
		ModelPart forearm = m.part(side + "lowerarm");
		if (forearm == null) {
			return;
		}
		forearm.resetPose();
		forearm.x = 0;
		forearm.y = 0;
		forearm.z = 0;
		for (String f : new String[]{"middle", "pointer", "pinky"}) {
			ModelPart finger = m.part(side + f + "_finger");
			if (finger != null) {
				finger.resetPose();
				finger.zRot += (left ? -1 : 1) * curl * 1.1f;
			}
		}
		// The forearm runs 59 px from the elbow to the claw tips; this much of a block of it shows.
		float length = 0.85f;
		float k = length * 16f / 59f;
		Vector3f d = new Vector3f(hand).sub(from).normalize();
		Vector3f elbow = new Vector3f(hand).sub(new Vector3f(d).mul(length));
		pose.pushPose();
		pose.translate(elbow.x, elbow.y, elbow.z);
		// In model space the left forearm runs along +x, the right along -x; their undersides (+y) down.
		pose.mulPose(xAlong(d, left));
		pose.scale(k, k, k);
		collector.submitModelPart(forearm, pose, RenderTypes.entityCutout(RUNT_TEXTURE), light, OverlayTexture.NO_OVERLAY, null);
		pose.popPose();
	}

	/** A turn taking the model's x (left forearm: +x, right: -x) along {@code d}, its +y (underside) as far down as can be. */
	private static Quaternionf xAlong(Vector3f d, boolean left) {
		Vector3f x = left ? new Vector3f(d).normalize() : new Vector3f(d).normalize().negate();
		Vector3f down = new Vector3f(0, -1, 0);
		Vector3f y = new Vector3f(down).sub(new Vector3f(x).mul(down.dot(x)));
		if (y.lengthSquared() < 1.0E-5f) {
			y.set(0, 0, 1);
		}
		y.normalize();
		Vector3f z = new Vector3f(x).cross(y);
		return new Quaternionf().setFromNormalized(new Matrix3f(x, y, z));
	}
}
