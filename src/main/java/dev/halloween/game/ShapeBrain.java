package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Michael played by the server (/hw shape bot): a strong player, and an honest one. He knows only what a person
 * in his place would: what he sees (in front of him, far in the light, close in the dark, anyone in the open
 * with his Sense up), what reaches his ears (the packets a client would get: running footsteps, screams, shots,
 * glass, doors and locks, a wardrobe door closing, breathing in a locker right by him, the engine of a car) and
 * what his Impulse, the firecrackers, the Runt and the like show him.
 *
 * <p>With nobody in sight he goes where the survivors have to come (the ways out, the phones, the furniture not yet
 * searched), through the dark as a shadow when it is far, switching off the lights he passes, cutting the phones,
 * draining the car, closing an opened cellar, and keeps watch where someone has got a way out going. A noise
 * sends him there. Someone in sight, unaware, far: he stalks them from the dark with his Sense, then comes as a
 * shadow and steps out close. Running: he chases, dashes, grabs (and wins the struggle as a good player does,
 * throwing them through a window when there is one), strikes when he can't grab, breaks the locked doors in his
 * way. Lost: he goes where they were heading, listens, throws open the wardrobes there. Townsfolk he kills when
 * there is nobody better.
 */
public final class ShapeBrain {
	public static final String NAME = "Michael";
	private static boolean chosen;
	private static ShapeBrain brain;

	private enum Mode {
		PATROL, LISTEN, STALK, CHASE, SEARCH, CAMP, WORK
	}

	/** Something heard or shown: where, when, how much it is worth going to. */
	private record Lead(Vec3 pos, int at, double weight, String kind) {
	}

	private final BotPlayer body;
	private final Walker walker;
	private final Random random = new Random();
	private Mode mode = Mode.PATROL;
	private LivingEntity target;
	private Vec3 lastSeen;
	private Vec3 lastVelocity = Vec3.ZERO;
	private int lastSeenAt = -1000;
	private int stalkSince;
	private BlockPos goal;
	private int goalUntil;
	private int repathAt;
	private int lookAroundUntil;
	private int nextPress;
	private BlockPos workAt;
	private int workSince = -1;
	private final List<Lead> leads = new ArrayList<>();
	/** Wardrobes he has reason to think someone is in (a door closing, breathing), and until when. */
	private final Map<BlockPos, Integer> suspects = new HashMap<>();
	/** Places he looked at lately (patrol goals, searched wardrobes) and until when he leaves them be. */
	private final Map<BlockPos, Integer> done = new HashMap<>();
	/** Who the Impulse (or the Runt, a firecracker...) showed him, and until when he sees them anywhere. */
	private final Map<Integer, Integer> revealed = new HashMap<>();
	private double leadScore;
	private int scanUntil;
	private int nextScan;
	private int lockersOpened;
	private BlockPos camping;
	private int campUntil;

	private ShapeBrain(BotPlayer body) {
		this.body = body;
		this.walker = new Walker(body);
		this.walker.closeDoors = false;
	}

	// ---------------------------------------------------------------- who plays him

	/** The next matches: Michael is a bot. */
	public static void chooseBot() {
		chosen = true;
	}

	public static void off() {
		chosen = false;
	}

	public static boolean chosen() {
		return chosen;
	}

	/** The bot's body for a new match. */
	static BotPlayer spawn(MinecraftServer server, BlockPos at) {
		return Bots.spawnLoose(server, NAME, at);
	}

	/** His mind, once the body has his role. */
	static void attach(BotPlayer bot) {
		brain = new ShapeBrain(bot);
		if (bot.wire != null) {
			bot.wire.listener = brain::packet;
		}
	}

	public static void clear(MinecraftServer server) {
		if (brain != null) {
			BotPlayer bot = brain.body;
			if (bot.wire != null) {
				bot.wire.listener = null;
			}
			if (server.getPlayerList().getPlayer(bot.getUUID()) == bot) {
				server.getPlayerList().remove(bot);
			}
			brain = null;
		}
	}

	/** What he is doing (tests, debugging), or null if no bot plays him. */
	public static String mode() {
		return brain == null ? null : brain.mode.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** What he is up to, in a line (tests, debugging). */
	public static String debug() {
		if (brain == null) {
			return "none";
		}
		ShapeBrain b = brain;
		return b.mode + " at " + b.body.blockPosition().toShortString() + " goal " + (b.goal == null ? "-" : b.goal.toShortString())
			+ " work " + (b.workAt == null ? "-" : b.workAt.toShortString()) + " walking " + !b.walker.idle()
			+ " holds " + Holds.working(b.body) + " done " + b.done.size() + " leads " + b.leads.size();
	}

	/** The bot playing him, or null. */
	public static ServerPlayer body() {
		return brain == null ? null : brain.body;
	}

	/** Tests: he forgets all he heard, saw and did, and starts looking about afresh. */
	public static void forget() {
		if (brain != null) {
			ShapeBrain b = brain;
			b.leads.clear();
			b.suspects.clear();
			b.done.clear();
			b.revealed.clear();
			b.target = null;
			b.lastSeen = null;
			b.goal = null;
			b.workAt = null;
			b.camping = null;
			b.lockersOpened = 0;
			b.scanUntil = 0;
			b.nextScan = 0;
			b.mode = Mode.PATROL;
			b.walker.stop();
		}
	}

	public static void tick(MinecraftServer server) {
		if (brain != null && !brain.body.isRemoved() && Match.running()) {
			brain.tick(server.getTickCount());
		}
	}

	private ServerLevel level() {
		return this.body.level();
	}

	private static void stat(String event) {
		AiStats.add("~michael", event);
	}

	// ---------------------------------------------------------------- what he hears and is shown

	/** Everything the server sends him, as it would reach a person's client. */
	private void packet(Packet<?> packet) {
		int now = this.level().getServer().getTickCount();
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> p : bundle.subPackets()) {
				this.packet(p);
			}
		} else if (packet instanceof ClientboundCustomPayloadPacket custom) {
			switch (custom.payload()) {
				case HwNet.NoiseBurst burst -> this.lead(burst.pos(), now, burst.loud() ? 4 : 3, "noise");
				case HwNet.Impulse impulse -> {
					for (int id : impulse.outdoorIds()) {
						this.revealed.put(id, now + impulse.durationTicks());
						Entity e = this.level().getEntity(id);
						if (e != null) {
							this.lead(e.position(), now, 6, "shown");
						}
					}
					for (Vec3 spot : impulse.indoorSpots()) {
						this.lead(spot, now, 5, "spot");
					}
				}
				case HwNet.Pant pant -> {
					Entity who = this.level().getEntity(pant.entityId());
					if (pant.on() && who != null) {
						// Breathing in a wardrobe right by him.
						BlockPos locker = this.nearestLocker(who.blockPosition(), 2, false);
						if (locker != null) {
							this.suspects.put(locker, now + 400);
							stat("heard.breathing");
						}
					}
				}
				default -> {
				}
			}
		} else if (packet instanceof ClientboundSoundPacket sound) {
			this.sound(sound, now);
		}
	}

	private void sound(ClientboundSoundPacket sound, int now) {
		Vec3 at = new Vec3(sound.getX(), sound.getY(), sound.getZ());
		if (at.distanceTo(this.body.position()) < 2.5) {
			// His own.
			return;
		}
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p) == dev.halloween.Role.RUNT && p.position().distanceTo(at) < 2) {
				// The Runt's: his own side.
				return;
			}
		}
		String id = sound.getSound().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		double weight;
		String kind;
		if (id.startsWith("voice.") || id.equals("npc.scream")) {
			weight = id.contains("scream") || id.contains("hurt") || id.contains("death") ? 4 : 2;
			kind = "voice";
		} else if (id.startsWith("talk.")) {
			weight = 1;
			kind = "talk";
		} else if (id.startsWith("gun.")) {
			weight = 4;
			kind = "shot";
		} else if (id.equals("locker.close")) {
			BlockPos locker = this.nearestLocker(BlockPos.containing(at), 2, false);
			if (locker != null) {
				this.suspects.put(locker, now + 600);
			}
			weight = 4;
			kind = "locker";
		} else if (id.equals("door.lock") || id.equals("door.unlock") || id.equals("door.rattle") || id.equals("world.breaker")) {
			weight = 3;
			kind = "work";
		} else if (id.startsWith("window.") || id.contains("glass")) {
			weight = 3;
			kind = "glass";
		} else if (id.contains("minecart.riding")) {
			weight = 5;
			kind = "engine";
		} else if (id.contains("_door.open") || id.contains("_door.close")) {
			weight = 1.5;
			kind = "door";
		} else if (id.endsWith(".step") && at.distanceTo(this.body.position()) < 10) {
			weight = 1.2;
			kind = "step";
		} else {
			return;
		}
		this.lead(at, now, weight, kind);
	}

	private void lead(Vec3 pos, int now, double weight, String kind) {
		this.leads.add(new Lead(pos, now, weight, kind));
		if (this.leads.size() > 40) {
			this.leads.removeFirst();
		}
	}

	// ---------------------------------------------------------------- what he sees

	/** How far he makes someone out: far in the light, close in the dark (with the Sense up, anyone in the open in range). */
	private double sightRange(LivingEntity e, ShapeState st) {
		HwConfig cfg = HwConfig.get();
		double range;
		int light = this.level().getBrightness(LightLayer.BLOCK, BlockPos.containing(e.getEyePosition()));
		if (light >= 7 || e instanceof ServerPlayer p && Fight.flashlightOn(p)) {
			range = 40;
		} else if (e.isCrouching()) {
			range = 4;
		} else {
			range = e.isSprinting() ? 11 : 7;
		}
		if (st.senseActive()) {
			range = Math.max(range, cfg.senseRange);
		}
		if (Weather.current() == Weather.FOG) {
			range = Math.min(range, 18);
		}
		return range;
	}

	/** The best one in sight: survivors first, then officers, then townsfolk; nearer first. */
	private LivingEntity look(ShapeState st, int now) {
		if (st.blindTicks > 0) {
			return null;
		}
		ServerLevel level = this.level();
		Vec3 eye = this.body.getEyePosition();
		Vec3 facing = this.body.getViewVector(1f);
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.body.getBoundingBox().inflate(45), MatchManager::exposed)) {
			Vec3 c = FocusPoints.bodyCenter(e);
			double d = eye.distanceTo(c);
			boolean shown = this.revealed.getOrDefault(e.getId(), 0) > now;
			if (!shown) {
				if (d > this.sightRange(e, st) || d > 2.5 && Sight.angleTo(eye, facing, c) > 75) {
					continue;
				}
				if (!Sight.canSee(level, eye, c, this.body) && !Sight.canSee(level, eye, e.getEyePosition(), this.body)) {
					continue;
				}
			}
			double score = d + (e instanceof ServerPlayer ? 0 : Police.isOfficer(e) ? 12 : 25);
			if (e == this.target) {
				score -= 6;
			}
			if (score < bestScore) {
				bestScore = score;
				best = e;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- the tick

	private void tick(int now) {
		ShapeState st = MatchManager.shapeState(this.body);
		if (st == null) {
			return;
		}
		this.leads.removeIf(l -> now - l.at > 400);
		this.suspects.values().removeIf(until -> until < now);
		this.done.values().removeIf(until -> until < now);
		this.revealed.values().removeIf(until -> until < now);
		if (st.grab != null) {
			this.grab(st, now);
			return;
		}
		if (st.busy() || st.trappedTicks > 0 || Windows.isVaulting(this.body)) {
			this.walker.halt();
			this.sense(st, false, false);
			return;
		}
		LivingEntity seen = this.look(st, now);
		if (seen != null) {
			if (seen != this.target) {
				stat("spotted." + (seen instanceof ServerPlayer ? "survivor" : Townsfolk.isNpc(seen) ? "npc" : "other"));
				this.stalkSince = now;
			}
			this.lastVelocity = this.lastSeen == null || seen != this.target ? Vec3.ZERO : seen.position().subtract(this.lastSeen).scale(1.0 / Math.max(1, now - this.lastSeenAt));
			this.target = seen;
			this.lastSeen = seen.position();
			this.lastSeenAt = now;
			this.mode = this.stalkable(st, seen, now) ? Mode.STALK : Mode.CHASE;
		} else if (this.target != null && (this.mode == Mode.CHASE || this.mode == Mode.STALK)) {
			// Out of sight: on to where they were going.
			if (now - this.lastSeenAt > 50 || !MatchManager.exposed(this.target)) {
				this.mode = Mode.SEARCH;
				this.lookAroundUntil = 0;
				stat("lost");
			}
		}
		switch (this.mode) {
			case STALK -> this.stalk(st, now);
			case CHASE -> this.chase(st, now);
			case SEARCH -> this.search(st, now);
			default -> this.roam(st, now);
		}
		// His camera follows his eyes (stalking and grabs read it).
		MatchManager.onAim(this.body, this.body.getEyePosition(), this.body.getViewVector(1f));
	}

	// ---------------------------------------------------------------- stalking

	/** Worth stalking first: a survivor far off, not yet well stalked, who hasn't seen him and isn't running. */
	private boolean stalkable(ShapeState st, LivingEntity e, int now) {
		if (!(e instanceof ServerPlayer) || st.shadowTicks > 0) {
			return false;
		}
		double d = this.body.distanceTo(e);
		if (d < 8 || d > HwConfig.get().senseRange - 2 || MatchManager.stalkOf(e) >= 2 || e.isSprinting()) {
			return false;
		}
		if (now - this.stalkSince > 20 * 12) {
			// Long enough: go.
			return false;
		}
		// Not while they look at him.
		Vec3 eye = e.getEyePosition();
		Vec3 me = FocusPoints.bodyCenter(this.body);
		return Sight.angleTo(eye, e.getViewVector(1f), me) > 55 || !Sight.canSee(this.level(), eye, me, e);
	}

	private void stalk(ShapeState st, int now) {
		this.walker.halt();
		LivingEntity t = this.target;
		// Eyes on them (on the head: that is where the stalk builds fastest), Sense up while it lasts.
		this.walker.face(t.getEyePosition());
		boolean tired = st.fatigue > 0.85f;
		this.sense(st, !tired, !tired && this.body.distanceTo(t) > 14 && st.fatigue < 0.6f);
		if (now % 40 == 0) {
			stat("stalk");
		}
	}

	/** Killer Sense (and focus) held or let go. */
	private void sense(ShapeState st, boolean on, boolean focus) {
		st.senseHeld = on && st.shadowTicks == 0;
		st.focusHeld = st.senseHeld && focus;
	}

	// ---------------------------------------------------------------- the chase

	private void chase(ShapeState st, int now) {
		HwConfig cfg = HwConfig.get();
		LivingEntity t = this.target;
		if (t == null || t.isRemoved() || !MatchManager.exposed(t)) {
			this.mode = Mode.SEARCH;
			return;
		}
		boolean visible = now - this.lastSeenAt <= 2;
		Vec3 aim = visible ? FocusPoints.bodyCenter(t) : this.lastSeen.add(0, 1, 0);
		double d = this.body.distanceTo(t);
		// A shadow to cover the ground unseen, out again close by.
		if (st.shadowTicks > 0) {
			this.sense(st, false, false);
			if (d < 8 && MatchManager.jumpBlockReason(this.body, st) == 0) {
				MatchManager.onShapeJump(this.body);
				stat("shadow.out_close");
			}
		} else if (d > 14 && !this.watchedBy(t) && MatchManager.jumpBlockReason(this.body, st) == 0 && st.jumpCooldown == 0) {
			MatchManager.onShapeJump(this.body);
			stat("shadow.in_approach");
		} else {
			this.sense(st, d > 12, false);
		}
		if (st.shadowTicks == 0 && visible) {
			if (d <= cfg.grabRange - 0.2 && st.grabCooldown == 0) {
				this.walker.face(aim);
				MatchManager.onAim(this.body, this.body.getEyePosition(), aim.subtract(this.body.getEyePosition()).normalize());
				MatchManager.onGrab(this.body, t.getId());
				if (st.grab != null) {
					stat("grab");
					return;
				}
			}
			if (d <= cfg.attackReach && st.grabCooldown > 0 && st.attackCooldown == 0) {
				// No grab in him yet: a blow (a heavy one on someone standing still).
				this.walker.face(aim);
				MatchManager.onAim(this.body, this.body.getEyePosition(), aim.subtract(this.body.getEyePosition()).normalize());
				MatchManager.onShapeAttack(this.body, t.getId(), t.getDeltaMovement().horizontalDistance() < 0.05);
				stat("strike");
			}
			if (d > 3.5 && d < 11 && st.dashCooldown == 0 && st.dashTicks == 0) {
				this.walker.face(aim);
				MatchManager.onDash(this.body);
				stat("dash");
			}
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + (d < 8 ? 5 : 12);
			// Where they will be in a moment, if they are running.
			Vec3 to = visible ? t.position().add(t.getDeltaMovement().multiply(6, 0, 6)) : this.lastSeen.add(this.lastVelocity.scale(30));
			this.follow(st, BlockPos.containing(to), 1.0);
		}
		this.walk(st, now);
		if (visible && d < 6) {
			this.walker.face(aim);
		}
	}

	/** Are these eyes on him? */
	private boolean watchedBy(LivingEntity e) {
		Vec3 eye = e.getEyePosition();
		Vec3 me = FocusPoints.bodyCenter(this.body);
		return Sight.angleTo(eye, e.getViewVector(1f), me) < 50 && Sight.canSee(this.level(), eye, me, e);
	}

	private void follow(ShapeState st, BlockPos to, double within) {
		boolean shadow = st.shadowTicks > 0;
		Nav.Options opt = shadow ? Nav.Options.climber().lockedDoors(0.5) : Nav.Options.walker().lockedDoors(3);
		this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), to, within, opt.nodes(4000)));
	}

	/** Steps along the path: a locked door in the way gets broken, a window slipped through as a shadow. */
	private Walker.Status walk(ShapeState st, int now) {
		this.walker.throughDoors = st.shadowTicks > 0;
		Walker.Status status = this.walker.tick();
		if (status == Walker.Status.LOCKED && st.shadowTicks == 0) {
			Nav.Node node = this.walker.current();
			if (node != null && st.attackCooldown == 0) {
				Vec3 door = Vec3.atCenterOf(DoorLocks.base(this.level(), node.pos()).above());
				this.walker.face(door);
				MatchManager.onAim(this.body, this.body.getEyePosition(), door.subtract(this.body.getEyePosition()).normalize());
				MatchManager.onShapeAttack(this.body, -1, true);
				stat("door.hit");
			}
		} else if (status == Walker.Status.WINDOW) {
			Nav.Node node = this.walker.current();
			if (st.shadowTicks > 0 && node != null && node.glass() != null) {
				Windows.request(this.body, node.glass(), false);
				this.walker.skip();
				stat("window.slip");
			} else {
				this.walker.stop();
			}
		} else if (status == Walker.Status.STUCK) {
			this.walker.stop();
			this.goal = null;
		}
		return status;
	}

	// ---------------------------------------------------------------- the grab

	private void grab(ShapeState st, int now) {
		this.walker.halt();
		this.sense(st, false, false);
		GrabSession g = st.grab;
		LivingEntity v = g.victim();
		// Facing a window if there is one by them: through it they go.
		BlockPos window = null;
		int r = HwConfig.get().windowSearchRadius;
		for (BlockPos p : BlockPos.betweenClosed(this.body.blockPosition().offset(-r, 0, -r), this.body.blockPosition().offset(r, 2, r))) {
			if (Sight.isGlass(this.level().getBlockState(p)) && (window == null || p.distSqr(this.body.blockPosition()) < window.distSqr(this.body.blockPosition()))) {
				window = p.immutable();
			}
		}
		this.walker.face(window != null ? Vec3.atCenterOf(window) : v.getEyePosition());
		if (g.shapeWouldHit() && now >= this.nextPress) {
			this.nextPress = now + 4;
			// A good player's hand: now and then a beat late.
			if (this.random.nextFloat() < 0.9f) {
				MatchManager.onQtePress(this.body);
			}
		}
	}

	// ---------------------------------------------------------------- lost them

	private void search(ShapeState st, int now) {
		if (this.lastSeen == null) {
			this.mode = Mode.PATROL;
			return;
		}
		if (this.suspect(st, now)) {
			return;
		}
		Vec3 heading = this.lastSeen.add(this.lastVelocity.scale(30));
		BlockPos where = BlockPos.containing(heading);
		if (this.body.position().distanceTo(Vec3.atBottomCenterOf(where)) > 3 && this.lookAroundUntil == 0) {
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 20;
				this.follow(st, where, 2);
				if (this.walker.idle()) {
					this.lookAroundUntil = now + 50;
				}
			}
			this.sense(st, true, false);
			Walker.Status status = this.walk(st, now);
			if (status == Walker.Status.ARRIVED || status == Walker.Status.IDLE) {
				this.lookAroundUntil = now + 50;
			}
			return;
		}
		if (this.lookAroundUntil == 0) {
			this.lookAroundUntil = now + 50;
		}
		if (now < this.lookAroundUntil) {
			// Turning round, Sense up, listening.
			this.walker.halt();
			this.sense(st, true, false);
			float yaw = this.body.getYRot() + 9f;
			this.body.setYRot(yaw);
			this.body.setYHeadRot(yaw);
			this.body.setYBodyRot(yaw);
			return;
		}
		// Nothing: the wardrobes round here, nearest first (a few).
		BlockPos locker = this.lockersOpened < 3 ? this.nearestLocker(BlockPos.containing(this.lastSeen), 7, true) : null;
		if (locker != null && this.openLocker(st, locker, now)) {
			return;
		}
		this.lockersOpened = 0;
		this.sense(st, false, false);
		this.target = null;
		this.mode = Mode.PATROL;
	}

	/** A wardrobe he has reason to suspect (a door heard closing, breathing), near: he goes and opens it. */
	private boolean suspect(ShapeState st, int now) {
		BlockPos suspect = null;
		for (BlockPos b : this.suspects.keySet()) {
			if (b.distSqr(this.body.blockPosition()) < 30 * 30 && (suspect == null || b.distSqr(this.body.blockPosition()) < suspect.distSqr(this.body.blockPosition()))) {
				suspect = b;
			}
		}
		return suspect != null && this.openLocker(st, suspect, now);
	}

	/** Goes to a wardrobe and throws it open. True while that is what he is doing. */
	private boolean openLocker(ShapeState st, BlockPos base, int now) {
		ServerLevel level = this.level();
		if (!LockerBlock.is(level.getBlockState(base))) {
			this.suspects.remove(base);
			this.done.put(base, now + 600);
			return false;
		}
		BlockPos front = Lockers.front(level, base);
		if (this.body.position().distanceTo(Vec3.atBottomCenterOf(front)) > HwConfig.get().grabRange) {
			if (st.shadowTicks > 0 && MatchManager.jumpBlockReason(this.body, st) == 0) {
				MatchManager.onShapeJump(this.body);
			}
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 20;
				this.follow(st, front, 0.8);
				if (this.walker.idle()) {
					this.suspects.remove(base);
					this.done.put(base, now + 600);
					return false;
				}
			}
			this.walk(st, now);
			return true;
		}
		if (st.shadowTicks > 0) {
			if (MatchManager.jumpBlockReason(this.body, st) == 0) {
				MatchManager.onShapeJump(this.body);
			}
			return true;
		}
		this.walker.halt();
		Vec3 c = Vec3.atCenterOf(base.above());
		this.walker.face(c);
		if (st.attackCooldown > 0) {
			return true;
		}
		boolean someone = Lockers.occupied(base);
		Lockers.shapeOpen(this.body, base);
		this.lockersOpened++;
		this.suspects.remove(base);
		this.done.put(base, now + 900);
		stat(someone ? "locker.found" : "locker.empty");
		return true;
	}

	private BlockPos nearestLocker(BlockPos around, int r, boolean fresh) {
		ServerLevel level = this.level();
		BlockPos best = null;
		for (BlockPos p : BlockPos.betweenClosed(around.offset(-r, -2, -r), around.offset(r, 3, r))) {
			if (LockerBlock.is(level.getBlockState(p))) {
				BlockPos base = LockerBlock.base(level, p);
				if (fresh && this.done.containsKey(base)) {
					continue;
				}
				if (best == null || base.distSqr(around) < best.distSqr(around)) {
					best = base.immutable();
				}
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- nobody in sight

	private void roam(ShapeState st, int now) {
		this.sense(st, false, false);
		if (this.suspect(st, now)) {
			this.mode = Mode.SEARCH;
			this.lastSeen = this.body.position();
			this.lastVelocity = Vec3.ZERO;
			this.lookAroundUntil = 1;
			return;
		}
		// Something heard: go and see (the freshest and loudest first, nearer first).
		Lead best = null;
		double bestScore = 0;
		for (Lead l : this.leads) {
			double score = l.weight * (1 - (now - l.at) / 400.0) * 30 / (10 + l.pos.distanceTo(this.body.position()));
			if (score > bestScore) {
				bestScore = score;
				best = l;
			}
		}
		boolean better = best != null && bestScore > 0.6
			&& (this.mode != Mode.LISTEN || this.goal == null || bestScore > this.leadScore * 1.5 && best.pos.distanceTo(Vec3.atBottomCenterOf(this.goal)) > 5);
		if (best != null && this.mode == Mode.LISTEN && !better && this.goal != null && best.pos.distanceTo(Vec3.atBottomCenterOf(this.goal)) <= 5) {
			// More of the same: there it is still.
			this.leads.remove(best);
			this.goalUntil = Math.max(this.goalUntil, now + 20 * 10);
		}
		if (better) {
			this.leadScore = bestScore;
			this.leads.remove(best);
			this.mode = Mode.LISTEN;
			this.goal = BlockPos.containing(best.pos);
			this.goalUntil = now + 20 * 25;
			this.lastSeen = best.pos;
			this.lastVelocity = Vec3.ZERO;
			this.repathAt = now;
			stat("lead." + best.kind);
		}
		if (this.mode == Mode.LISTEN && this.goal != null) {
			if (this.body.position().distanceTo(Vec3.atBottomCenterOf(this.goal)) < 3 || now > this.goalUntil) {
				// There: look round for them.
				this.mode = Mode.SEARCH;
				this.lookAroundUntil = now + 40;
				return;
			}
			this.sense(st, st.fatigue < 0.7f && this.body.position().distanceTo(Vec3.atBottomCenterOf(this.goal)) < 16, false);
			this.travel(st, now, this.goal, 2);
			return;
		}
		// A job on the way: a breaker on, a phone that works, the car's fuel, an opened cellar.
		if (st.shadowTicks == 0 && now % 10 == 0) {
			this.lightsOff();
		}
		if (this.mode == Mode.WORK && this.workAt != null) {
			this.work(st, now);
			return;
		}
		BlockPos job = this.job(now);
		if (job != null) {
			this.workAt = job;
			this.mode = Mode.WORK;
			this.work(st, now);
			return;
		}
		// Someone has a way out going: keep watch there.
		BlockPos watch = this.exitInTrouble();
		if (watch != null && (this.camping == null || !this.camping.equals(watch))) {
			this.camping = watch;
			this.campUntil = now + 20 * 30;
			stat("camp");
		}
		if (this.camping != null && now < this.campUntil) {
			this.mode = Mode.CAMP;
			if (this.body.position().distanceTo(Vec3.atBottomCenterOf(this.camping)) > 7) {
				this.travel(st, now, this.camping, 5);
			} else {
				this.walker.halt();
				this.sense(st, now % 120 < 60, false);
				float yaw = this.body.getYRot() + 3f;
				this.body.setYRot(yaw);
				this.body.setYHeadRot(yaw);
				this.body.setYBodyRot(yaw);
			}
			return;
		}
		this.camping = null;
		// Now and then he stops in the dark and looks all round with his Sense (it tires him): someone far off.
		this.mode = Mode.PATROL;
		if (now < this.scanUntil) {
			this.walker.halt();
			this.sense(st, true, false);
			float yaw = this.body.getYRot() + 6f;
			this.body.setYRot(yaw);
			this.body.setYHeadRot(yaw);
			this.body.setYBodyRot(yaw);
			this.body.setXRot(5f);
			return;
		}
		if (st.shadowTicks == 0 && st.fatigue < 0.3f && now >= this.nextScan && !MatchManager.inLight(this.body)) {
			this.scanUntil = now + 60;
			this.nextScan = now + 20 * 12;
			stat("scan");
			return;
		}
		// Where they have to come: the ways out, the phones, the drawers not yet gone through.
		if (this.goal == null || now > this.goalUntil || this.walker.idle() && this.body.position().distanceTo(Vec3.atBottomCenterOf(this.goal)) < 4) {
			if (this.goal != null) {
				this.done.put(this.goal, now + 20 * 60);
			}
			this.goal = this.patrolGoal();
			this.goalUntil = now + 20 * 40;
			this.repathAt = now;
		}
		if (this.goal != null) {
			this.travel(st, now, this.goal, 3);
		}
	}

	/** Getting somewhere with nobody about: far, as a shadow through the dark; close, on foot. */
	private void travel(ShapeState st, int now, BlockPos to, double within) {
		double d = this.body.position().distanceTo(Vec3.atBottomCenterOf(to));
		if (st.shadowTicks == 0 && d > 25 && st.jumpCooldown == 0 && MatchManager.jumpBlockReason(this.body, st) == 0) {
			MatchManager.onShapeJump(this.body);
			this.repathAt = now;
			stat("shadow.in_travel");
		} else if (st.shadowTicks > 0 && d < 8 && MatchManager.jumpBlockReason(this.body, st) == 0) {
			MatchManager.onShapeJump(this.body);
			this.repathAt = now;
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + 40;
			this.follow(st, to, within);
			if (this.walker.idle() && d > within + 1) {
				this.done.put(to, now + 20 * 60);
				this.goal = null;
				this.mode = Mode.PATROL;
			}
		}
		this.walk(st, now);
	}

	private BlockPos patrolGoal() {
		ServerLevel level = this.level();
		List<BlockPos> spots = new ArrayList<>();
		for (HwNet.ExitInfo e : Exits.infos()) {
			spots.add(e.pos());
			spots.add(e.pos());
		}
		for (BlockPos phone : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
			if (Exits.phoneWorks(phone)) {
				spots.add(phone);
			}
		}
		List<BlockPos> searched = Loot.searched();
		for (BlockPos c : Loot.containers()) {
			if (!searched.contains(c)) {
				spots.add(c);
			}
		}
		spots.removeIf(p -> this.done.containsKey(p) || p.distSqr(this.body.blockPosition()) < 36);
		if (spots.isEmpty()) {
			BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
			return center == null ? null : center.offset(this.random.nextInt(41) - 20, 0, this.random.nextInt(41) - 20);
		}
		// Not always the nearest: he covers the town.
		spots.sort((a, b) -> Double.compare(a.distSqr(this.body.blockPosition()), b.distSqr(this.body.blockPosition())));
		return spots.get(this.random.nextInt(Math.min(6, spots.size())));
	}

	/** Lights off where he goes: darkness is his. */
	private void lightsOff() {
		for (BlockPos breaker : LightSystem.breakers(this.level(), this.body.blockPosition(), 3)) {
			if (LightSystem.isOn(this.level(), breaker) && Sight.canSee(this.level(), this.body.getEyePosition(), Vec3.atCenterOf(breaker), this.body)) {
				this.walker.face(Vec3.atCenterOf(breaker));
				LightSystem.flip(this.level(), breaker, false);
				stat("power.off");
				return;
			}
		}
	}

	/** Something to undo near: a working phone, fuel in the car, an opened cellar. */
	private BlockPos job(int now) {
		Vec3 me = this.body.position();
		for (HwNet.ExitInfo e : Exits.infos()) {
			if (this.done.containsKey(e.pos()) || Vec3.atCenterOf(e.pos()).distanceTo(me) > 30) {
				continue;
			}
			if (e.type() == Exits.CAR && (e.state() & Exits.CAR_FUEL) != 0 && (e.state() & (Exits.CAR_GONE | Exits.CAR_RUNNING)) == 0) {
				return e.pos();
			}
			if (e.type() == Exits.CELLAR && e.state() == Exits.OPEN) {
				return e.pos();
			}
		}
		if (Exits.policePoint() != null && Police.wanted() < HwConfig.get().policeMaxCalls) {
			for (BlockPos phone : MapPoints.get(this.level()).list(MapPoints.Type.PHONE)) {
				if (Exits.phoneWorks(phone) && !this.done.containsKey(phone) && Vec3.atCenterOf(phone).distanceTo(me) < 14) {
					return phone;
				}
			}
		}
		return null;
	}

	private void work(ShapeState st, int now) {
		BlockPos at = this.workAt;
		if (Vec3.atCenterOf(at).distanceTo(this.body.getEyePosition()) > 2.8) {
			this.workSince = -1;
			if (st.shadowTicks > 0 && this.body.position().distanceTo(Vec3.atCenterOf(at)) < 6 && MatchManager.jumpBlockReason(this.body, st) == 0) {
				MatchManager.onShapeJump(this.body);
			}
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 30;
				this.follow(st, at, 1.6);
				if (this.walker.idle()) {
					this.done.put(at, now + 20 * 60);
					this.workAt = null;
					this.mode = Mode.PATROL;
					return;
				}
			}
			this.walk(st, now);
			return;
		}
		this.walker.halt();
		this.walker.face(Vec3.atCenterOf(at));
		// The button held down, as a player holds it.
		Holds.ping(this.body, at);
		if (this.workSince < 0) {
			this.workSince = now;
		}
		if (!Holds.working(this.body) && now - this.workSince > 3) {
			// Done, or nothing to do there after all.
			this.done.put(at, now + 20 * 90);
			this.workAt = null;
			this.workSince = -1;
			this.mode = Mode.PATROL;
			stat("work.done");
		}
	}

	/** A way out someone is getting going: the gate's fuse in, the car mended or fuelled, the police van waiting. */
	private BlockPos exitInTrouble() {
		for (HwNet.ExitInfo e : Exits.infos()) {
			switch (e.type()) {
				case Exits.GATE -> {
					if (e.extra() != 0 || e.state() == Exits.OPEN) {
						return e.pos();
					}
				}
				case Exits.CAR -> {
					if ((e.state() & (Exits.CAR_REPAIRED | Exits.CAR_RUNNING)) != 0 && (e.state() & Exits.CAR_GONE) == 0) {
						return e.pos();
					}
				}
				case Exits.POLICE -> {
					if (e.state() == Exits.POLICE_HERE) {
						return e.pos();
					}
				}
				default -> {
				}
			}
		}
		return null;
	}
}
