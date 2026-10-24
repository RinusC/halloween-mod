package dev.halloween.client;

import dev.halloween.Halloween;
import dev.halloween.Role;
import dev.halloween.Vr;
import dev.halloween.game.LockerBlock;
import dev.halloween.game.Sight;
import dev.halloween.game.Windows;
import dev.halloween.net.HwNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.vivecraft.api.client.HeldInteractModule;
import org.vivecraft.api.client.InteractModule;
import org.vivecraft.api.client.VRClientAPI;

/**
 * The hands in the headset: what is done by moving them, not by buttons.
 *
 * <ul>
 * <li>Michael and the Demogorgon strike by swinging the hand that holds the knife (or the claw) at someone; with the
 * trigger held a while first, the blow is the heavy one. The trigger alone still strikes along the hand.</li>
 * <li>The killer grabs by reaching out to someone and closing the hand (grip or trigger), and throws a locker open by
 * putting his hand to it; Vecna takes a body by reaching down to it.</li>
 * <li>Michael listens (Killer Sense) with his free hand cupped to his ear; Vecna holds his free hand out before him
 * for the Grip and throws it by letting the hand drop.</li>
 * <li>Survivors shove with both hands at once; raise a gun to the eye to aim it down the sights; put the free hand to
 * the gun to reload. A reaction check (or breaking free) is answered by a sharp move of either hand.</li>
 * <li>The Runt knocks on doors and windows by rapping them with a hand.</li>
 * <li>Everyone works doors, windows, lockers, phones, breakers and whatever is searched by putting a hand on it and
 * closing it (held, for what is held).</li>
 * </ul>
 * Seated players (no room to swing) keep the buttons; gestures that need room are off for them.
 */
public final class VrHands {
	/** Where each hand was in the room last tick (0 the main hand, 1 the other), and how fast it moves (blocks a second). */
	private static final Vec3[] LAST = new Vec3[2];
	private static final float[] SPEED = new float[2];
	/** A hand must slow down before it can count as a new swing. */
	private static final boolean[] ARMED = {true, true};
	private static int shoveWait;
	private static int knockWait;
	private static int reloadWait;
	private static int answerWait;
	/** A swing landed while the trigger was held: its letting go is not a second blow. */
	private static boolean swungWhileHeld;

	private VrHands() {
	}

	/** Every client tick, before the role's own input. */
	static void tick(Minecraft mc) {
		if (!ClientVr.active()) {
			LAST[0] = LAST[1] = null;
			return;
		}
		for (int i = 0; i < 2; i++) {
			Vec3 p = ClientVr.roomHand(i == 0);
			SPEED[i] = p == null || LAST[i] == null ? 0f : (float) p.distanceTo(LAST[i]) * 20f;
			LAST[i] = p;
			if (SPEED[i] < 1.2f) {
				ARMED[i] = true;
			}
		}
		shoveWait = Math.max(0, shoveWait - 1);
		knockWait = Math.max(0, knockWait - 1);
		reloadWait = Math.max(0, reloadWait - 1);
		answerWait = Math.max(0, answerWait - 1);
		if (mc.gui.screen() != null || mc.player == null || mc.player.isSpectator() || ClientVr.seated()) {
			return;
		}
		Role role = ClientState.myRole();
		boolean mineToAnswer = role == Role.SHAPE ? ClientState.qte.active() && ClientState.qte.forShape()
			: role.survivor() && (ClientState.qte.active() && !ClientState.qte.forShape() || ClientState.skill.active());
		if (mineToAnswer) {
			int hand = swing(2.5f);
			if (hand >= 0 && answerWait == 0) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.QTE_PRESS));
				ClientVr.buzz(hand == 0, 0.05f, 0.6f);
				answerWait = 4;
			}
			return;
		}
		if (role.survivor()) {
			survivor(mc);
		} else if (role == Role.RUNT) {
			runt(mc);
		}
	}

	/** A hand moving fast enough for a swing now (and not already counted): 0 main, 1 the other, -1 none. */
	private static int swing(float speed) {
		for (int i = 0; i < 2; i++) {
			if (ARMED[i] && SPEED[i] >= speed) {
				ARMED[i] = false;
				return i;
			}
		}
		return -1;
	}

	private static void survivor(Minecraft mc) {
		// Both hands thrust out together: a shove.
		if (shoveWait == 0 && ARMED[0] && ARMED[1] && SPEED[0] >= 2.2f && SPEED[1] >= 2.2f) {
			ARMED[0] = ARMED[1] = false;
			shoveWait = 10;
			HalloweenClient.shove(mc);
			ClientVr.buzz(true, 0.06f, 0.7f);
			ClientVr.buzz(false, 0.06f, 0.7f);
		}
		Vr.Ray main = ClientVr.hand(true);
		Vr.Ray off = ClientVr.hand(false);
		if (ClientGuns.heldGun(mc) != null && main != null && off != null && reloadWait == 0 && main.pos().distanceTo(off.pos()) < 0.16) {
			// The free hand to the gun: load it.
			reloadWait = 30;
			ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.RELOAD));
			ViewArms.trigger(ViewArms.Act.RELOAD);
			ClientVr.buzz(false, 0.05f, 0.4f);
		}
	}

	private static void runt(Minecraft mc) {
		if (knockWait > 0) {
			return;
		}
		for (int i = 0; i < 2; i++) {
			Vr.Ray hand = ClientVr.hand(i == 0);
			if (hand == null || !ARMED[i] || SPEED[i] < 2.0f) {
				continue;
			}
			BlockPos pos = touched(mc.level, hand.pos(), 0.12, (level, p, s) -> s.getBlock() instanceof DoorBlock || Windows.isWindow(s));
			if (pos != null) {
				ARMED[i] = false;
				knockWait = 10;
				ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.RUNT_KNOCK, pos));
				ViewArms.trigger(ViewArms.Act.KNOCK);
				ClientVr.buzz(i == 0, 0.04f, 0.8f);
				return;
			}
		}
	}

	/** A killer's swing: who it was swung at (null for the air, or a window or door along the hand). */
	record Swing(@Nullable LivingEntity target) {
	}

	/** The killer's swing this tick, or null; {@code triggerHeld}: the trigger is down (its release is then swallowed). */
	static @Nullable Swing killerSwing(Minecraft mc, boolean triggerHeld) {
		if (!ClientVr.active() || ClientVr.seated() || mc.gui.screen() != null || !ARMED[0] || SPEED[0] < 3.0f) {
			return null;
		}
		ARMED[0] = false;
		if (triggerHeld) {
			swungWhileHeld = true;
		}
		Vr.Ray hand = ClientVr.hand(true);
		LivingEntity target = hand == null ? null : nearest(mc, hand.pos(), 0.9);
		ClientVr.buzz(true, 0.07f, target != null ? 1f : 0.35f);
		return new Swing(target);
	}

	/** True once after a swing landed with the trigger held: its release must not strike again. */
	static boolean swallowRelease() {
		boolean was = swungWhileHeld;
		swungWhileHeld = false;
		return was;
	}

	/** Gestures that stand for a held key: the hand to the ear (Killer Sense), the hand held out (Vecna's Grip). */
	static boolean holds(KeyMapping key) {
		if (key != HalloweenClient.KEY_SENSE || !ClientVr.active() || ClientState.myRole() != Role.SHAPE) {
			return false;
		}
		Vr.Ray head = ClientVr.head();
		Vr.Ray off = ClientVr.hand(false);
		if (head == null || off == null) {
			return false;
		}
		Vec3 d = off.pos().subtract(head.pos());
		if (MichaelRenderer.vecna()) {
			// Out before him at shoulder height or above.
			double ahead = new Vec3(d.x, 0, d.z).dot(ClientVr.headForward());
			return ahead >= 0.45 && d.y >= -0.3;
		}
		return d.length() <= 0.22 && Math.abs(d.y) <= 0.15;
	}

	/** A gun raised to the eye: aiming down the sights. */
	static boolean gunRaised() {
		Vr.Ray head = ClientVr.head();
		Vr.Ray main = ClientVr.hand(true);
		return head != null && main != null && main.pos().distanceTo(head.pos()) <= 0.32;
	}

	/** The nearest living thing (not the player) within {@code reach} of a point, by its box. */
	private static @Nullable LivingEntity nearest(Minecraft mc, Vec3 at, double reach) {
		LivingEntity best = null;
		double bestDist = reach;
		for (Entity e : mc.level.getEntities(mc.player, new AABB(at, at).inflate(reach + 2))) {
			if (!(e instanceof LivingEntity living) || e.isSpectator() || !e.isPickable() || e.isInvisible() && !HeroScene.isGhost(e)) {
				continue;
			}
			AABB box = e.getBoundingBox();
			double dx = Math.max(Math.max(box.minX - at.x, 0), at.x - box.maxX);
			double dy = Math.max(Math.max(box.minY - at.y, 0), at.y - box.maxY);
			double dz = Math.max(Math.max(box.minZ - at.z, 0), at.z - box.maxZ);
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < bestDist) {
				bestDist = d;
				best = living;
			}
		}
		return best;
	}

	private interface BlockTest {
		boolean test(Level level, BlockPos pos, BlockState state);
	}

	/** The block the hand is on (inside its shape, or within {@code slack} of it) that passes the test, or null. */
	private static @Nullable BlockPos touched(Level level, Vec3 at, double slack, BlockTest test) {
		BlockPos center = BlockPos.containing(at);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || !test.test(level, pos, state)) {
				continue;
			}
			VoxelShape shape = state.getShape(level, pos);
			if (shape.isEmpty()) {
				continue;
			}
			for (AABB box : shape.toAabbs()) {
				if (box.move(pos).inflate(slack).contains(at)) {
					return pos.immutable();
				}
			}
		}
		return null;
	}

	/** What a hand can work by touch: doors, windows, lockers, phones, breakers, and the match's own things. */
	private static boolean touchable(Level level, BlockPos pos, BlockState state) {
		return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof LockerBlock || Sight.isGlass(state)
			|| state.is(dev.halloween.HwBlocks.PHONE) || state.is(Blocks.LEVER) || ClientInteractions.matchObject(level, pos) != 0
			|| dev.halloween.game.PropBlock.is(state) && ClientState.myRole().survivor();
	}

	/** At client start: hand on and close it (Vivecraft's "interact", grip or trigger, while the hand is on something). */
	static void register() {
		if (!Vr.LOADED) {
			return;
		}
		try {
			Modules.register();
		} catch (Throwable e) {
			Halloween.LOGGER.warn("Vivecraft did not take the hand modules: {}", e.toString());
		}
	}

	/** Only loaded when Vivecraft is there. */
	private static final class Modules {
		static void register() {
			VRClientAPI.instance().addClientRegistrationHandler(event -> event.registerInteractModules(new Grab(), new Touch()));
		}
	}

	/** The killer's hand on someone (a grab), on a locker (thrown open), or down on a body (Vecna). */
	private static final class Grab implements InteractModule {
		private final Object[] what = new Object[2];

		@Override
		public int getPriority() {
			return 100;
		}

		@Override
		public Identifier getId() {
			return Halloween.id("grab");
		}

		@Override
		public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 pos) {
			Minecraft mc = Minecraft.getInstance();
			int i = hand.ordinal();
			this.what[i] = null;
			if (ClientState.myRole() != Role.SHAPE || mc.gui.screen() != null) {
				return false;
			}
			LivingEntity target = nearest(mc, pos, 0.45);
			if (target != null) {
				this.what[i] = target;
			} else {
				BlockPos locker = touched(mc.level, pos, 0.15, (level, p, s) -> s.getBlock() instanceof LockerBlock);
				if (locker != null) {
					this.what[i] = locker;
				} else if (MichaelRenderer.vecna() && pos.y < player.getY() + 0.5) {
					this.what[i] = Boolean.TRUE;
				}
			}
			return this.what[i] != null;
		}

		@Override
		public boolean onPress(LocalPlayer player, InteractionHand hand) {
			Object w = this.what[hand.ordinal()];
			if (w instanceof LivingEntity target) {
				ClientPlayNetworking.send(new HwNet.Grab(target.getId()));
			} else if (w instanceof BlockPos locker) {
				ClientPlayNetworking.send(new HwNet.UseAction(HwNet.UseAction.LOCKER, locker));
			} else if (w != null) {
				ClientPlayNetworking.send(new HwNet.Grab(-1));
			} else {
				return false;
			}
			ClientVr.buzz(hand == InteractionHand.MAIN_HAND, 0.08f, 0.8f);
			return true;
		}

		@Override
		public boolean swingsArm() {
			return true;
		}
	}

	/** A hand on a door, window, locker, phone, breaker or something to search: used as if aimed at and clicked. */
	private static final class Touch implements HeldInteractModule {
		private final BlockPos[] on = new BlockPos[2];
		private final Vec3[] at = new Vec3[2];
		private int held;

		@Override
		public int getPriority() {
			return 90;
		}

		@Override
		public Identifier getId() {
			return Halloween.id("touch");
		}

		@Override
		public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 pos) {
			Minecraft mc = Minecraft.getInstance();
			int i = hand.ordinal();
			this.on[i] = ClientState.myRole() == Role.NONE || mc.gui.screen() != null ? null : touched(mc.level, pos, 0.12, VrHands::touchable);
			this.at[i] = pos;
			return this.on[i] != null;
		}

		@Override
		public boolean onPress(LocalPlayer player, InteractionHand hand) {
			this.held = 0;
			return this.use(player, hand);
		}

		@Override
		public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
			// Searching, a job, hiding: kept going while the hand stays closed on it (as a held click would).
			BlockPos pos = this.on[hand.ordinal()];
			Minecraft mc = Minecraft.getInstance();
			if (pos == null || ++this.held % 4 != 0) {
				return true;
			}
			BlockState state = mc.level.getBlockState(pos);
			if (ClientInteractions.matchObject(mc.level, pos) != 0 || state.getBlock() instanceof LockerBlock && ClientState.myRole().survivor()) {
				this.use(player, hand);
			}
			return true;
		}

		@Override
		public void onRelease(LocalPlayer player, InteractionHand hand) {
			this.held = 0;
		}

		private boolean use(LocalPlayer player, InteractionHand hand) {
			int i = hand.ordinal();
			BlockPos pos = this.on[i];
			if (pos == null) {
				return false;
			}
			Minecraft mc = Minecraft.getInstance();
			Vec3 at = this.at[i];
			Direction face = Direction.getApproximateNearest(at.subtract(Vec3.atCenterOf(pos)));
			// Whichever hand it is, it goes as the main hand's use: that is the one the game listens to.
			mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, pos, true));
			ClientVr.buzz(hand == InteractionHand.MAIN_HAND, 0.03f, 0.3f);
			return true;
		}

		@Override
		public boolean swingsArm() {
			return true;
		}
	}
}
