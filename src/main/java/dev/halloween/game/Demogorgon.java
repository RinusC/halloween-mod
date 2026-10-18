package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Demogorgon (Stranger Things), the other killer (/hw killer demogorgon): played by a person (a bot always plays
 * Michael). Everything a killer does (Sense, stalking, the grab and the kill) is as Michael's, the Impulse too; his
 * own powers take the place of Michael's on the same keys.
 * <ul>
 * <li>The Upside Down (the Shape Jump key): anywhere, he sinks through the ground (a second and a half, seen going)
 * into the Upside Down (a dark, rotten copy of the town, see {@link UpsideDown}). Down there he runs five times as
 * fast and nothing stops him (there are no doors), nobody up above sees or hears him, and every house with a
 * survivor in it glows red through the walls (which house, not where in it). The key again: he comes up at the very
 * same spot (x, y, z: the same floor of the same house), clawing his way out of the ground for five seconds, seen and
 * heard, nothing stopping it; out, he senses every survivor within 30 blocks (rings, a few seconds). Then 30 s before
 * he can go down again. No time limit below.</li>
 * <li>Shred (the dash key): a second's charge, then a lunge forwards that strikes the first one in his way and
 * breaks a door or a window it runs into.</li>
 * </ul>
 */
public final class Demogorgon {
	private static final int SINK_TICKS = 30;
	private static final int EMERGE_TICKS = 100;
	private static final int RIFT_COOLDOWN = 600;
	private static final double SENSE_RANGE = 30;
	private static final int SENSE_TICKS = 100;
	/** Below: five times his speed. */
	private static final double BELOW_SPEED = 4.0;
	private static final Identifier BELOW_SPEED_ID = Halloween.id("upside_down_speed");
	/** Sinking and climbing out: rooted to the spot. */
	private static final Identifier ROOTED_ID = Halloween.id("upside_down_rooted");
	private static final int SHRED_CHARGE = 20;
	private static final int SHRED_TICKS = 10;

	/** Where he is in a crossing (HwNet.DemoHud's phase). */
	public static final int UP = 0;
	public static final int SINKING = 1;
	public static final int BELOW = 2;
	public static final int EMERGING = 3;

	private static boolean chosen;
	private static int phase = UP;
	private static int phaseLeft;
	/** Shred: ticks charged (-1: not charging), ticks of lunge left, its way. */
	private static int shredCharge = -1;
	private static int shredTicks;
	private static Vec3 shredDir = Vec3.ZERO;
	/** When he last had a survivor in sight (or in his hands), and the pose last told to everyone (HwNet.DemoPose). */
	private static int sawSurvivorAt = -100000;
	private static int toldPose = -1;
	/** On all fours this long after a survivor was last in sight. */
	private static final int HUNT_TICKS = 200;
	private static final double SIGHT_RANGE = 40;

	/** The rift (as in Stranger Things): a glowing tear in the air where he goes down and where he comes up. */
	static final String RIFT_TAG = "hw_rift";
	private static final int RIFT_OPEN = 8;
	private static final int RIFT_CLOSE = 12;

	private static final class Rift {
		final Display.ItemDisplay display;
		final int born;
		/** When it starts closing (-1: not yet). */
		int closeAt = -1;

		Rift(Display.ItemDisplay display, int born) {
			this.display = display;
			this.born = born;
		}
	}

	private static final List<Rift> RIFTS = new ArrayList<>();
	/** The rift he is going down or coming up through now. */
	private static Rift current;

	private Demogorgon() {
	}

	/** A rift opening here: a sliver at first, torn wide over a few ticks; it faces whoever looks, it glows. */
	/**
	 * A rift opening here, on the ground, its membrane across the way he faces: the model (client.RiftRenderer)
	 * plays its own opening from the moment it appears; the carrier's item says when to close.
	 */
	private static Rift openRift(ServerLevel level, Vec3 at, float yaw, int now) {
		Display.ItemDisplay d = new Display.ItemDisplay(net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY, level);
		d.setItemStack(riftStack("rift"));
		d.setItemTransform(ItemDisplayContext.FIXED);
		d.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		d.setViewRange(2f);
		d.setPos(at.x, at.y, at.z);
		d.setYRot(yaw);
		d.addTag(RIFT_TAG);
		d.addTag(Leftovers.stamp());
		level.addFreshEntity(d);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1.4f, 0.4f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 0.8f, 0.5f);
		Rift r = new Rift(d, now);
		RIFTS.add(r);
		return r;
	}

	private static ItemStack riftStack(String model) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Halloween.id(model));
		return stack;
	}

	/** Open, sparks and spores drifting out of it; closing (the model draws itself shut) and gone. */
	private static void tickRifts(ServerLevel level, int now) {
		java.util.Iterator<Rift> it = RIFTS.iterator();
		while (it.hasNext()) {
			Rift r = it.next();
			Display.ItemDisplay d = r.display;
			if (d.isRemoved()) {
				it.remove();
				continue;
			}
			if (now == r.closeAt) {
				d.setItemStack(riftStack("rift_closing"));
				level.playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.HOSTILE, 1.4f, 0.4f);
			}
			if (r.closeAt >= 0 && now >= r.closeAt + RIFT_CLOSE + 2) {
				d.discard();
				it.remove();
				continue;
			}
			if (now % 2 == 0) {
				level.sendParticles(ParticleTypes.CRIMSON_SPORE, d.getX(), d.getY() + 1.5, d.getZ(), 6, 0.6, 1.2, 0.4, 0.02);
				level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xFF3020, 1.2f), d.getX(), d.getY() + 1.5, d.getZ(), 3, 0.3, 1.1, 0.2, 0.0);
			}
		}
	}

	/** For tests: a rift opened here (facing {@code yaw}), and every rift closed. */
	public static void testRift(ServerLevel level, Vec3 at, float yaw) {
		openRift(level, at, yaw, level.getServer().getTickCount());
	}

	public static void testCloseRifts(int now) {
		for (Rift r : RIFTS) {
			if (r.closeAt < 0) {
				r.closeAt = now + 1;
			}
		}
	}

	/** The rift he went through closes behind him in a moment. */
	private static void closeRift(int now, int delay) {
		if (current != null) {
			current.closeAt = now + delay;
			current = null;
		}
	}

	/** The next matches' killer: the Demogorgon (true) or Michael. */
	public static void choose(boolean demogorgon) {
		chosen = demogorgon;
	}

	public static boolean chosen() {
		return chosen;
	}

	/** Is the killer the Demogorgon (chosen, and a person plays him: a bot plays Michael)? */
	public static boolean active() {
		return chosen && !ShapeBrain.chosen();
	}

	/** Charging a Shred (he goes slowly meanwhile). */
	public static boolean charging() {
		return active() && shredCharge >= 0;
	}

	/** Climbing out of the Upside Down: nothing stops it (no stun, no grab). */
	public static boolean emerging() {
		return active() && phase == EMERGING;
	}

	/** Sinking or climbing out: rooted, nothing else to be done. */
	public static boolean rooted() {
		return active() && (phase == SINKING || phase == EMERGING);
	}

	/** Sinking, down below or climbing out: busy with a crossing. */
	public static boolean crossing() {
		return active() && phase != UP;
	}

	// ---------------------------------------------------------------- the keys

	/** The Shape Jump key: down into the Upside Down here, or (down there) up again at this very spot. */
	static void riftKey(ServerPlayer p, ShapeState st) {
		if (shredCharge >= 0 || shredTicks > 0) {
			return;
		}
		if (phase == BELOW) {
			emerge(p);
			return;
		}
		if (phase != UP || st.busy()) {
			return;
		}
		if (st.jumpCooldown > 0) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.cooldown").withStyle(ChatFormatting.GRAY));
		} else if (!UpsideDown.ready()) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.not_ready").withStyle(ChatFormatting.GRAY));
		} else if (!p.onGround()) {
			p.sendOverlayMessage(Component.translatable("halloween.msg.demo.bad_ground").withStyle(ChatFormatting.GRAY));
		} else {
			phase = SINKING;
			phaseLeft = SINK_TICKS;
			root(p, true);
			ServerLevel level = p.level();
			current = openRift(level, p.position(), p.getYRot(), level.getServer().getTickCount());
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 2f, 0.8f);
			level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 1f, 0.5f);
			AiStats.add(p, "demo.down");
		}
	}

	/** Down: the same place in the Upside Down, his eyes made for the dark, his legs for its streets. */
	private static void goDown(ServerPlayer p) {
		ServerLevel level = p.level();
		Vec3 to = UpsideDown.below(p.position());
		p.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
		p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60 * 30, 0, true, false));
		root(p, false);
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.addOrUpdateTransientModifier(new AttributeModifier(BELOW_SPEED_ID, BELOW_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		p.setSilent(true);
		phase = BELOW;
	}

	/** Up again at this very spot (a free one beside it if the town is solid there): five seconds clawing out. */
	private static void emerge(ServerPlayer p) {
		ServerLevel level = p.level();
		Vec3 at = free(level, p, UpsideDown.above(p.position()));
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(BELOW_SPEED_ID);
		}
		p.teleportTo(level, at.x, at.y, at.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
		p.removeEffect(MobEffects.NIGHT_VISION);
		p.setSilent(false);
		root(p, true);
		phase = EMERGING;
		phaseLeft = EMERGE_TICKS;
		current = openRift(level, at, p.getYRot(), level.getServer().getTickCount());
		level.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 2.5f, 0.6f);
		AiStats.add(p, "demo.up");
	}

	/** Out: a roar, every survivor within 30 blocks felt; the crossing rests. */
	private static void out(ServerPlayer p, ShapeState st) {
		ServerLevel level = p.level();
		root(p, false);
		phase = UP;
		closeRift(level.getServer().getTickCount(), 15);
		st.jumpCooldownMax = RIFT_COOLDOWN;
		st.jumpCooldown = RIFT_COOLDOWN;
		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 2.5f, 0.8f);
		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2f, 0.6f);
		List<Vec3> spots = new ArrayList<>();
		for (ServerPlayer s : level.players()) {
			if (s != p && MatchManager.role(s).survivor() && !s.isSpectator() && s.isAlive() && s.distanceTo(p) <= SENSE_RANGE) {
				spots.add(s.position());
			}
		}
		ServerPlayNetworking.send(p, new HwNet.Impulse(List.of(), spots, SENSE_TICKS));
		Match.markDirty();
	}

	/** This spot if he fits there, else the nearest one that he does (a step or two off, up to a floor up). */
	private static Vec3 free(ServerLevel level, ServerPlayer p, Vec3 want) {
		AABB box = p.getBoundingBox();
		for (int r = 0; r <= 3; r++) {
			for (int dy = 0; dy <= 2; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						Vec3 at = new Vec3(Math.floor(want.x) + 0.5 + dx, Math.floor(want.y) + dy, Math.floor(want.z) + 0.5 + dz);
						AABB moved = box.move(at.subtract(p.position()).add(0, 0.01, 0));
						if (level.noCollision(p, moved) && !level.getBlockState(BlockPos.containing(at).below()).isAir()) {
							return at;
						}
					}
				}
			}
		}
		return want;
	}

	private static void root(ServerPlayer p, boolean on) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		if (on) {
			speed.addOrUpdateTransientModifier(new AttributeModifier(ROOTED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			p.setDeltaMovement(Vec3.ZERO);
			p.hurtMarked = true;
		} else {
			speed.removeModifier(ROOTED_ID);
		}
	}

	/** The dash key: Shred, a second's charge then a lunge. */
	static void shredKey(ServerPlayer p, ShapeState st) {
		if (st.busy() || st.dashCooldown > 0 || shredCharge >= 0 || shredTicks > 0 || phase != UP) {
			return;
		}
		shredCharge = 0;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_STUNNED, SoundSource.HOSTILE, 1f, 1.4f);
	}

	// ---------------------------------------------------------------- every tick

	public static void tick(MinecraftServer server) {
		if (!active() || !Match.running()) {
			return;
		}
		UpsideDown.tick();
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		ServerPlayer d = Senses.shape(level);
		if (d == null) {
			return;
		}
		ShapeState st = MatchManager.shapeState(d);
		if (st == null) {
			return;
		}
		tickRifts(level, now);
		tickCrossing(level, d, st, now);
		tickShred(d, st);
		tickPose(level, d, st, now);
		if (now % 5 == 0) {
			ServerPlayNetworking.send(d, new HwNet.DemoHud(phase, phaseLeft, phase == BELOW, UpsideDown.ready(), shredCharge >= 0));
		}
	}

	private static void tickCrossing(ServerLevel level, ServerPlayer d, ShapeState st, int now) {
		switch (phase) {
			case SINKING -> {
				d.setDeltaMovement(0, d.getDeltaMovement().y, 0);
				BlockState ground = level.getBlockState(d.blockPosition().below());
				if (now % 2 == 0 && !ground.isAir()) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), d.getX(), d.getY() + 0.1, d.getZ(), 14, 0.6, 0.1, 0.6, 0.1);
				}
				level.sendParticles(ParticleTypes.CRIMSON_SPORE, d.getX(), d.getY() + 0.5, d.getZ(), 6, 0.5, 0.5, 0.5, 0.02);
				if (--phaseLeft <= 0) {
					level.sendParticles(ParticleTypes.LARGE_SMOKE, d.getX(), d.getY() + 0.2, d.getZ(), 20, 0.4, 0.2, 0.4, 0.02);
					goDown(d);
					closeRift(now, 10);
				}
			}
			case BELOW -> {
				if (!UpsideDown.isBelow(d)) {
					// Somehow out of it (a command, a test): up he is.
					AttributeInstance speed = d.getAttribute(Attributes.MOVEMENT_SPEED);
					if (speed != null) {
						speed.removeModifier(BELOW_SPEED_ID);
					}
					d.removeEffect(MobEffects.NIGHT_VISION);
					d.setSilent(false);
					phase = UP;
					return;
				}
				if (now % 5 == 0) {
					// Ash and spores drifting in the dark (for his eyes only).
					level.sendParticles(d, ParticleTypes.WHITE_ASH, true, false, d.getX(), d.getY() + 1.5, d.getZ(), 40, 10, 4, 10, 0.01);
					level.sendParticles(d, ParticleTypes.CRIMSON_SPORE, true, false, d.getX(), d.getY() + 1.5, d.getZ(), 12, 8, 3, 8, 0.01);
				}
				if (now % 10 == 0) {
					ServerPlayNetworking.send(d, new HwNet.DemoHouses(occupied(level)));
				}
			}
			case EMERGING -> {
				d.setDeltaMovement(0, d.getDeltaMovement().y, 0);
				int done = EMERGE_TICKS - phaseLeft;
				BlockState ground = level.getBlockState(d.blockPosition().below());
				if (!ground.isAir() && now % 2 == 0) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), d.getX(), d.getY() + 0.1, d.getZ(), 10 + done / 6, 0.7, 0.1, 0.7, 0.15);
				}
				level.sendParticles(ParticleTypes.CRIMSON_SPORE, d.getX(), d.getY() + 0.3, d.getZ(), 8, 0.8, 0.3, 0.8, 0.02);
				if (done % 20 == 10) {
					level.playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.5f + done / 50f, 0.6f + done / 200f);
					level.playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.HOSTILE, 1.5f, 0.5f);
				}
				if (--phaseLeft <= 0) {
					out(d, st);
				}
			}
			default -> {
				if (d.hasEffect(MobEffects.NIGHT_VISION) && !UpsideDown.isBelow(d)) {
					d.removeEffect(MobEffects.NIGHT_VISION);
				}
			}
		}
	}

	/** The houses (their boxes, as they stand down below) with a survivor in them. */
	private static List<BlockPos> occupied(ServerLevel level) {
		List<BlockPos> out = new ArrayList<>();
		List<Long> seen = new ArrayList<>();
		// Survivors and townsfolk alike.
		List<net.minecraft.world.entity.LivingEntity> who = new ArrayList<>(Townsfolk.bodies());
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator()) {
				who.add(p);
			}
		}
		for (net.minecraft.world.entity.LivingEntity s : who) {
			if (!s.isAlive() || s.level() != level || UpsideDown.isBelow(s)) {
				continue;
			}
			// The house they are in; one whose door they are at (on the stairs, in a wardrobe the scan misses).
			Houses.Building b = Houses.at(level, s.blockPosition());
			if (b == null) {
				b = Houses.near(level, s.position(), 3);
			}
			if (b == null) {
				if (!level.canSeeSky(s.blockPosition().above())) {
					// Under a roof all the same, but no house found: the block of ground round them, roughly.
					int gx = Math.floorDiv(s.getBlockX(), 8) * 8;
					int gz = Math.floorDiv(s.getBlockZ(), 8) * 8;
					Vec3 lo = UpsideDown.below(new Vec3(gx - 2, s.getBlockY() - 1, gz - 2));
					out.add(BlockPos.containing(lo));
					out.add(BlockPos.containing(lo.add(12, 5, 12)));
				}
				continue;
			}
			if (seen.contains(b.key)) {
				continue;
			}
			seen.add(b.key);
			int[] box = b.bounds();
			if (box[3] - box[0] > 26 || box[5] - box[2] > 26) {
				// Not one house (a run of them under one roof, a covered yard): the room they are in.
				Houses.Room room = b.roomAt(s.blockPosition());
				if (room == null) {
					continue;
				}
				box = Houses.bounds(room.cells);
			}
			Vec3 lo = UpsideDown.below(new Vec3(box[0], box[1], box[2]));
			Vec3 hi = UpsideDown.below(new Vec3(box[3], box[4], box[5]));
			out.add(BlockPos.containing(lo));
			out.add(BlockPos.containing(hi));
		}
		return out;
	}

	/**
	 * Upright while nobody is about; on all fours while he sees a survivor, or saw one in the last ten seconds, or
	 * holds one; coiled and lunging for a Shred; sinking and climbing out. Told to everyone when it changes, and every
	 * two seconds.
	 */
	private static void tickPose(ServerLevel level, ServerPlayer d, ShapeState st, int now) {
		if (now % 4 == 0 && phase == UP) {
			Vec3 eye = d.getEyePosition();
			Vec3 look = d.getViewVector(1f);
			// Survivors and townsfolk alike (anyone he could go for), out in the open.
			for (LivingEntity p : level.getEntitiesOfClass(LivingEntity.class, d.getBoundingBox().inflate(SIGHT_RANGE), MatchManager::exposed)) {
				if (p == d || !p.isAlive() || p.distanceTo(d) > SIGHT_RANGE) {
					continue;
				}
				Vec3 body = FocusPoints.bodyCenter(p);
				if (Sight.angleTo(eye, look, body) <= 65 && Sight.canSee(level, eye, body, d)) {
					sawSurvivorAt = now;
					break;
				}
			}
		}
		if (st.grab != null) {
			sawSurvivorAt = now;
		}
		int pose = phase == SINKING ? HwNet.DemoPose.SINKING : phase == EMERGING ? HwNet.DemoPose.EMERGING
			: shredTicks > 0 ? HwNet.DemoPose.LUNGING : shredCharge >= 0 ? HwNet.DemoPose.CHARGING
			: now - sawSurvivorAt <= HUNT_TICKS || phase == BELOW ? HwNet.DemoPose.HUNTING : HwNet.DemoPose.UPRIGHT;
		if (pose != toldPose || now % 40 == 0) {
			toldPose = pose;
			HwNet.DemoPose msg = new HwNet.DemoPose(d.getId(), pose);
			for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
				ServerPlayNetworking.send(p, msg);
			}
		}
	}

	private static void tickShred(ServerPlayer d, ShapeState st) {
		if (shredCharge >= 0) {
			if (st.busy()) {
				shredCharge = -1;
				return;
			}
			if (++shredCharge >= SHRED_CHARGE) {
				// Charged: the lunge.
				shredCharge = -1;
				Vec3 look = d.getLookAngle().multiply(1, 0, 1);
				shredDir = look.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : look.normalize();
				shredTicks = SHRED_TICKS;
				HwConfig cfg = HwConfig.get();
				st.dashCooldownMax = HwConfig.at(cfg.dashCooldownByTier, st.tier());
				st.dashCooldown = st.dashCooldownMax;
				d.level().playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.4f, 1.5f);
				AiStats.add(d, "demo.shred");
			}
			return;
		}
		if (shredTicks <= 0) {
			return;
		}
		shredTicks--;
		// What is right ahead: a door goes flying off its hinges, a window he dives through.
		if (smash(d)) {
			return;
		}
		d.setDeltaMovement(shredDir.x * 0.95, d.getDeltaMovement().y, shredDir.z * 0.95);
		d.hurtMarked = true;
		Vec3 eye = d.getEyePosition();
		MatchManager.onAim(d, eye, shredDir);
		for (LivingEntity v : d.level().getEntitiesOfClass(LivingEntity.class, d.getBoundingBox().inflate(2.2), MatchManager::exposed)) {
			Vec3 to = v.position().subtract(d.position()).multiply(1, 0, 1);
			if (to.lengthSqr() > 1e-4 && to.normalize().dot(shredDir) > 0.5) {
				// The first in his way takes it.
				MatchManager.onAim(d, eye, FocusPoints.bodyCenter(v).subtract(eye).normalize());
				MatchManager.onShapeAttack(d, v.getId(), true);
				shredTicks = 0;
				AiStats.add(d, "demo.shred_hit");
				return;
			}
		}
		if (d.horizontalCollision) {
			// Into a wall.
			shredTicks = 0;
		}
	}

	/**
	 * In a Shred, what is right in his way: a door (shut, locked or not) is knocked clean out, lying on the floor as
	 * the earthquake leaves them, and he runs on; a window he dives through, the glass smashed, landing on the other
	 * side (still lunging). True if he went through a window (moved already this tick).
	 */
	private static boolean smash(ServerPlayer d) {
		ServerLevel level = d.level();
		// Right ahead; and for a door or a window a little more (0.2 of a block further and to either side): a leap at one
		// that is nearly in line still finds it.
		Vec3 side = new Vec3(-shredDir.z, 0, shredDir.x).scale(0.2);
		// (Near too: pressed right up against it, or standing in a doorway, the block 0.9 ahead is already past it.)
		Vec3 near = d.position().add(shredDir.scale(0.45));
		Vec3 ahead = d.position().add(shredDir.scale(0.9));
		Vec3 further = d.position().add(shredDir.scale(1.1));
		Vec3[] samples = {near, ahead, further, near.add(side), near.subtract(side), ahead.add(side), ahead.subtract(side), further.add(side), further.subtract(side)};
		for (int k = 0; k < samples.length * 2; k++) {
			Vec3 s = samples[k / 2];
			int dy = k % 2;
			BlockPos at = BlockPos.containing(s.x, d.getY() + 0.2 + dy, s.z);
			BlockState state = level.getBlockState(at);
			if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
				BlockPos lower = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
					== net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? at : at.below();
				Quake.knockOut(level, lower);
				level.playSound(null, at, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 2f, 0.8f);
				Bots.alert(Vec3.atCenterOf(at), level.getServer().getTickCount());
				Townsfolk.hearNoise(level, Vec3.atCenterOf(at), level.getServer().getTickCount());
				AiStats.add(d, "demo.shred_door");
				return false;
			}
			if (Windows.isWindow(state)) {
				// The bottom of the window: a sill he can leap (no higher than a jump).
				int bottom = at.getY();
				while (Windows.isWindow(level.getBlockState(new BlockPos(at.getX(), bottom - 1, at.getZ())))) {
					bottom--;
				}
				if (bottom - d.getY() > 1.6) {
					return false;
				}
				Windows.breakColumn(level, at);
				Vec3 c = Vec3.atBottomCenterOf(at);
				boolean acrossZ = Math.abs(shredDir.z) >= Math.abs(shredDir.x);
				Vec3 across = acrossZ ? new Vec3(0, 0, Math.signum(shredDir.z)) : new Vec3(Math.signum(shredDir.x), 0, 0);
				Vec3 land = new Vec3(c.x + across.x * 1.3, d.getY(), c.z + across.z * 1.3);
				if (!level.noCollision(d, d.getBoundingBox().move(land.subtract(d.position()).add(0, 0.01, 0)))) {
					shredTicks = 0;
					return true;
				}
				d.teleportTo(level, land.x, land.y, land.z, java.util.Set.of(), d.getYRot(), d.getXRot(), false);
				d.setDeltaMovement(shredDir.x * 0.95, 0.15, shredDir.z * 0.95);
				d.hurtMarked = true;
				level.sendParticles(ParticleTypes.CRIT, c.x, c.y + 1, c.z, 20, 0.3, 0.5, 0.3, 0.2);
				Bots.alert(c, level.getServer().getTickCount());
				Townsfolk.hearNoise(level, c, level.getServer().getTickCount());
				AiStats.add(d, "demo.shred_window");
				return true;
			}
		}
		return false;
	}

	/** Back to school: nothing of the Upside Down left on him (its speed, rooted, silent). */
	public static void reset(ServerPlayer p) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(BELOW_SPEED_ID);
			speed.removeModifier(ROOTED_ID);
		}
		if (p.isSilent()) {
			p.setSilent(false);
		}
	}

	/** A match over: no crossing under way, the Upside Down left as it is. */
	public static void clear(ServerLevel level) {
		ServerPlayer d = Senses.shape(level);
		if (d != null) {
			AttributeInstance speed = d.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speed != null) {
				speed.removeModifier(BELOW_SPEED_ID);
				speed.removeModifier(ROOTED_ID);
			}
			d.setSilent(false);
		}
		for (Rift r : RIFTS) {
			r.display.discard();
		}
		RIFTS.clear();
		current = null;
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
			"kill @e[tag=" + RIFT_TAG + "]");
		phase = UP;
		phaseLeft = 0;
		shredCharge = -1;
		shredTicks = 0;
		sawSurvivorAt = -100000;
		toldPose = -1;
		UpsideDown.clear();
	}
}
