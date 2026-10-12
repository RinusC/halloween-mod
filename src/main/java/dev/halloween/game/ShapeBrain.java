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
		PATROL, LISTEN, STALK, CHASE, RETREAT, LEAVE, SEARCH, CAMP, WORK
	}

	/** What he does about a survivor who noticed him (or ran) before he had stalked them enough: picked at random. */
	private enum Reaction {
		NONE,
		/** Keeps eight to fifteen blocks behind, stopping to stalk them. */
		FOLLOW,
		/** Gets out of their sight (into the shadow when he may), waits, and comes back for them. */
		RETREAT,
		/** Goes for them there and then. */
		ATTACK
	}

	/** Tests: how he reacts when noticed (FOLLOW, RETREAT, ATTACK); null: at random. */
	public static String forceReaction;

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
	/** Survivors who got out of his hands: stalked to the full before the next try. */
	private final java.util.Set<java.util.UUID> gotAway = new java.util.HashSet<>();
	private Reaction reaction = Reaction.NONE;
	private java.util.UUID reactionFor;
	private int noticedAt = -1000;
	private int retreats;
	private int retreatUntil;
	private Vec3 retreatFrom;
	/** Whom he holds (to tell, when the grab is over, whether they died or got away). */
	private LivingEntity grabbing;
	/** The body he just left, and until when he stays to look at it. */
	private Vec3 corpse;
	private int leaveUntil;
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
	/** Places he found no way to (a room upstairs with no stairs, a yard walled in), and until when he leaves them be. */
	private final Map<BlockPos, Integer> unreachable = new HashMap<>();
	/** Where his path is meant to take him (a fixed place: a goal, a wardrobe, a job; null when chasing someone). */
	private BlockPos aim;
	private Walker.Status lastStatus;
	/** A shadow caught in the light (he can't step out of it there): making for the dark, until when. */
	private int escapeUntil;
	private boolean escaping;
	/** A breaker to switch off first: the place he is making for is lit by the house lights. */
	private BlockPos lightJob;
	private int lightJobUntil;
	/** How near he has come to it, when that last got better, and what he has tried since to get unstuck. */
	private double bestDist;
	private int pathLeft;
	private int bestAt;
	private int unstick;
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
			+ " holds " + Holds.working(b.body) + " done " + b.done.size() + " leads " + b.leads.size() + " reaction " + b.reaction
			+ String.format(" pos %.2f %.2f %.2f", b.body.getX(), b.body.getY(), b.body.getZ()) + " status " + b.lastStatus + " aim " + (b.aim == null ? "-" : b.aim.toShortString())
			+ " best " + String.format("%.1f", b.bestDist) + " unstick " + b.unstick + " tokens " + String.format("%.1f", b.searches) + " zza " + b.body.zza;
	}

	/** How far he stalks this survivor before going for them: 2, or 3 once they got out of his hands. */
	public static int stalkGoal(LivingEntity e) {
		return brain == null ? 2 : brain.goalFor(e);
	}

	private int goalFor(LivingEntity e) {
		return this.gotAway.contains(e.getUUID()) ? 3 : 2;
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
			b.gotAway.clear();
			b.reaction = Reaction.NONE;
			b.reactionFor = null;
			b.retreats = 0;
			b.retreatUntil = 0;
			b.grabbing = null;
			b.corpse = null;
			b.unreachable.clear();
			b.aim = null;
			b.escaping = false;
			b.lightJob = null;
			b.mode = Mode.PATROL;
			b.walker.stop();
		}
	}

	/** Tests: the only place he makes for (nothing else on his mind). */
	public static BlockPos testGoal;

	/** Tests: has he given this place up as one he can't get to? */
	public static boolean unreachable(BlockPos pos) {
		return brain != null && brain.unreachable.containsKey(pos);
	}

	/** Tests: his mind stops (the body stands where it is put). */
	public static boolean paused;

	public static void tick(MinecraftServer server) {
		if (brain != null && !paused && !brain.body.isRemoved() && Match.running()) {
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
		if (id.contains("death")) {
			// A last scream: nobody left there to find (and mostly his own work).
			return;
		} else if (id.startsWith("voice.") || id.equals("npc.scream")) {
			weight = id.contains("scream") || id.contains("hurt") ? 4 : 2;
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
		this.unreachable.values().removeIf(until -> until < now);
		this.revealed.values().removeIf(until -> until < now);
		if (st.grab != null) {
			this.grabbing = st.grab.victim();
			this.grab(st, now);
			return;
		}
		if (this.grabbing != null) {
			// The grab is over: dead, or out of his hands.
			LivingEntity v = this.grabbing;
			this.grabbing = null;
			this.afterGrab(v, now);
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
			}
			// How they move, as he sees it (smoothed over a few ticks).
			Vec3 v = this.lastSeen == null || seen != this.target ? Vec3.ZERO : seen.position().subtract(this.lastSeen).scale(1.0 / Math.max(1, now - this.lastSeenAt));
			this.lastVelocity = seen != this.target ? v : this.lastVelocity.lerp(v, 0.3);
			this.target = seen;
			this.lastSeen = seen.position();
			this.lastSeenAt = now;
			this.mode = this.decide(st, seen, now);
		} else if (this.target != null && (this.mode == Mode.CHASE || this.mode == Mode.STALK)) {
			if (gone(this.target)) {
				this.afterDeath(this.target, now);
			} else if (now - this.lastSeenAt > 50 || !MatchManager.exposed(this.target)) {
				// Out of sight: on to where they were going.
				this.mode = Mode.SEARCH;
				this.lookAroundUntil = 0;
				stat("lost");
			}
		}
		if (this.escapeLight(st, now)) {
			MatchManager.onAim(this.body, this.body.getEyePosition(), this.body.getViewVector(1f));
			return;
		}
		if (this.lightJob != null && this.mode != Mode.CHASE && this.mode != Mode.STALK && this.mode != Mode.RETREAT && this.switchOff(st, now)) {
			MatchManager.onAim(this.body, this.body.getEyePosition(), this.body.getViewVector(1f));
			return;
		}
		switch (this.mode) {
			case STALK -> this.stalk(st, now);
			case CHASE -> this.chase(st, now);
			case RETREAT -> this.retreat(st, now);
			case LEAVE -> this.leave(st, now);
			case SEARCH -> this.search(st, now);
			default -> this.roam(st, now);
		}
		// His camera follows his eyes (stalking and grabs read it).
		MatchManager.onAim(this.body, this.body.getEyePosition(), this.body.getViewVector(1f));
	}

	// ---------------------------------------------------------------- stalking

	/**
	 * Someone in sight: what to do about them. Townsfolk and officers he goes for at once. A survivor he stalks
	 * first, to level 2 (to 3 if they once got out of his hands), unless they are at a way out. If they notice him
	 * (or run off) before that, he picks at random: follows them stalking, gets out of sight and comes back, or
	 * goes for them.
	 */
	private Mode decide(ShapeState st, LivingEntity e, int now) {
		if (!(e instanceof ServerPlayer p) || !MatchManager.role(p).survivor()) {
			return Mode.CHASE;
		}
		if (!p.getUUID().equals(this.reactionFor)) {
			this.reactionFor = p.getUUID();
			this.reaction = Reaction.NONE;
			this.retreats = 0;
		}
		if (MatchManager.stalkOf(p) >= this.goalFor(p) || this.nearExit(p)) {
			return Mode.CHASE;
		}
		if (this.noticedBy(st, p)) {
			this.noticedAt = now;
			if (this.reaction == Reaction.NONE) {
				this.reaction = this.pick();
				stat("noticed." + this.reaction.name().toLowerCase(java.util.Locale.ROOT));
			}
		} else if (this.reaction != Reaction.NONE && this.reaction != Reaction.RETREAT && now - this.noticedAt > 20 * 8) {
			// They have calmed down: back to watching them quietly.
			this.reaction = Reaction.NONE;
		}
		return switch (this.reaction) {
			case ATTACK -> Mode.CHASE;
			case RETREAT -> Mode.RETREAT;
			default -> Mode.STALK;
		};
	}

	private Reaction pick() {
		if (forceReaction != null) {
			return Reaction.valueOf(forceReaction);
		}
		List<Reaction> options = new ArrayList<>(List.of(Reaction.FOLLOW, Reaction.RETREAT, Reaction.ATTACK));
		if (this.retreats >= 2) {
			// Backed off twice already.
			options.remove(Reaction.RETREAT);
		}
		return options.get(this.random.nextInt(options.size()));
	}

	/** Have they seen him (eyes on him, as far as one makes him out), or are they running off from him? */
	private boolean noticedBy(ShapeState st, LivingEntity e) {
		HwConfig cfg = HwConfig.get();
		Vec3 eye = e.getEyePosition();
		Vec3 me = FocusPoints.bodyCenter(this.body);
		double d = eye.distanceTo(me);
		double range = Senses.lit(this.level(), this.body) ? cfg.civilianSightRange : 14;
		if (st.shadowTicks == 0 && d <= range && Sight.angleTo(eye, e.getViewVector(1f), me) <= cfg.civilianSightFovDeg && Sight.canSee(this.level(), eye, me, e)) {
			return true;
		}
		Vec3 away = e.position().subtract(this.body.position()).multiply(1, 0, 1);
		return d < 25 && away.lengthSqr() > 1e-4 && this.lastVelocity.multiply(1, 0, 1).dot(away.normalize()) > 0.15;
	}

	/** Right by a way out: no time for stalking. */
	private boolean nearExit(LivingEntity e) {
		for (HwNet.ExitInfo x : Exits.infos()) {
			if (Vec3.atBottomCenterOf(x.pos()).distanceTo(e.position()) < 6) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Watching them with his Sense up: from where he stands while they don't know (closer if they are too far or
	 * out of sight), eight to fifteen blocks behind them if they do. Sense and focus slow him nearly to a stop,
	 * so he lets them go to catch up and holds them when he stands.
	 */
	private void stalk(ShapeState st, int now) {
		LivingEntity t = this.target;
		double d = this.body.distanceTo(t);
		boolean visible = now - this.lastSeenAt <= 2;
		double far = this.reaction == Reaction.FOLLOW ? 14 : HwConfig.get().senseRange - 7;
		if (st.shadowTicks > 0 && d < far) {
			// Nothing is stalked from the shadow.
			this.wantOut(st, now);
		}
		if (d > far || !visible || st.shadowTicks > 0) {
			this.sense(st, false, false);
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 10;
				this.follow(st, BlockPos.containing(t.position()), 2);
			}
			this.walk(st, now);
			this.lookAt(t.getEyePosition());
			return;
		}
		this.walker.halt();
		// Eyes on them (on the head: that is where the stalk builds fastest).
		this.walker.face(t.getEyePosition());
		this.sense(st, true, true);
		if (now % 40 == 0) {
			stat("stalk");
		}
	}

	/** Where he is looking about towards, and when he picks another way to look. */
	private float glanceYaw = Float.NaN;
	private int glanceNext;

	/**
	 * Looking about where he stands: a look one way, held a moment, then another (not turning round and round), the
	 * head and body turning there at a man's pace.
	 */
	private void glance(int now) {
		if (Float.isNaN(this.glanceYaw) || now >= this.glanceNext) {
			this.glanceYaw = this.body.getYRot() + (this.random.nextBoolean() ? 1 : -1) * (60f + this.random.nextFloat() * 100f);
			this.glanceNext = now + 30 + this.random.nextInt(20);
		}
		float turn = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(this.glanceYaw - this.body.getYRot()), -9f, 9f);
		float yaw = this.body.getYRot() + turn;
		this.body.setYRot(yaw);
		this.body.setYHeadRot(yaw);
		this.body.setYBodyRot(yaw);
		this.body.setXRot(5f);
	}

	/** Turns his head (not his walk) towards a point. */
	private void lookAt(Vec3 point) {
		Vec3 d = point.subtract(this.body.getEyePosition());
		this.body.setYHeadRot((float) (net.minecraft.util.Mth.atan2(d.z, d.x) * net.minecraft.util.Mth.RAD_TO_DEG) - 90f);
		this.body.setXRot((float) -(net.minecraft.util.Mth.atan2(d.y, d.horizontalDistance()) * net.minecraft.util.Mth.RAD_TO_DEG));
	}

	/** Out of their sight, into the shadow when he may, a while; then back for them from where they were. */
	private void retreat(ShapeState st, int now) {
		LivingEntity t = this.target;
		this.sense(st, false, false);
		if (this.retreatUntil == 0) {
			this.retreatUntil = now + 20 * (5 + this.random.nextInt(5));
			this.retreatFrom = t != null ? t.position() : this.body.position();
			this.retreats++;
			stat("retreat");
			Vec3 away = this.body.position().subtract(this.retreatFrom).multiply(1, 0, 1);
			away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
			this.walker.stop();
			for (int turn = 0; turn < 4 && this.walker.idle(); turn++) {
				// Twelve blocks away from them (or off to a side if that way is shut).
				Vec3 dir = away.yRot((float) (turn * Math.PI / 2 * (turn % 2 == 0 ? 1 : -1)));
				this.follow(st, BlockPos.containing(this.body.position().add(dir.scale(12))), 4);
			}
		}
		boolean watched = t != null && MatchManager.exposed(t) && this.watchedBy(t);
		if (st.shadowTicks == 0 && !watched && MatchManager.jumpBlockReason(this.body, st) == 0) {
			// Out of their eyes and in the dark: gone.
			MatchManager.onShapeJump(this.body);
			stat("shadow.in_retreat");
		}
		if (now < this.retreatUntil) {
			if (st.shadowTicks > 0 && !watched || this.walker.idle()) {
				this.walker.halt();
			} else {
				this.walk(st, now);
			}
			if (t != null && MatchManager.exposed(t)) {
				this.lookAt(t.getEyePosition());
			}
			return;
		}
		this.retreatUntil = 0;
		this.reaction = Reaction.NONE;
		this.mode = Mode.SEARCH;
		this.lastSeen = this.retreatFrom;
		this.lastVelocity = Vec3.ZERO;
		this.lookAroundUntil = 0;
		this.walker.stop();
	}

	// ---------------------------------------------------------------- after a grab, after a kill

	private void afterGrab(LivingEntity v, int now) {
		if (gone(v)) {
			this.afterDeath(v, now);
			return;
		}
		if (v instanceof ServerPlayer p && MatchManager.role(p).survivor() && this.gotAway.add(p.getUUID())) {
			stat("got_away");
		}
		// Whatever they do now, he decides afresh.
		this.reaction = Reaction.NONE;
	}

	/** Dead (or out of the match): if their body lies there, he steps off it and looks at it a moment. */
	private void afterDeath(LivingEntity v, int now) {
		Vec3 at = v.position();
		this.target = null;
		this.reaction = Reaction.NONE;
		this.reactionFor = null;
		this.walker.stop();
		boolean body = !this.level().getEntities((Entity) null, new net.minecraft.world.phys.AABB(at, at).inflate(1.5),
			e -> e.entityTags().contains(MatchManager.CORPSE_TAG)).isEmpty();
		if (!body) {
			this.mode = Mode.PATROL;
			return;
		}
		this.corpse = at;
		this.leaveUntil = now + 20 * 6;
		this.repathAt = now;
		this.mode = Mode.LEAVE;
		stat("kill");
	}

	private void leave(ShapeState st, int now) {
		this.sense(st, false, false);
		if (this.body.position().distanceTo(this.corpse) < 3.2 && now < this.leaveUntil) {
			// Off the body first.
			if (this.walker.idle() || now >= this.repathAt) {
				this.repathAt = now + 20;
				Vec3 off = this.body.position().subtract(this.corpse).multiply(1, 0, 1);
				off = off.lengthSqr() < 0.04 ? new Vec3(1, 0, 0).yRot(this.random.nextFloat() * 6.28f) : off.normalize();
				this.walker.stop();
				for (int turn = 0; turn < 4 && this.walker.idle(); turn++) {
					Vec3 dir = off.yRot((float) (turn * Math.PI / 2));
					this.follow(st, BlockPos.containing(this.corpse.add(dir.scale(4.5))), 1.2);
				}
			}
			this.walk(st, now);
			return;
		}
		this.walker.halt();
		if (now < this.leaveUntil) {
			// A look at his work (Bloodthirst for it).
			this.walker.face(this.corpse.add(0, 0.3, 0));
			return;
		}
		this.corpse = null;
		this.goal = null;
		this.mode = Mode.PATROL;
	}

	/** Dead, or no longer in the match (gone out through an exit). */
	private static boolean gone(LivingEntity v) {
		return v.isRemoved() || !v.isAlive() || v instanceof ServerPlayer p && (p.isSpectator() || !MatchManager.role(p).survivor());
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
		if (t == null) {
			this.mode = Mode.SEARCH;
			return;
		}
		if (gone(t)) {
			// Struck down.
			this.afterDeath(t, now);
			return;
		}
		if (!MatchManager.exposed(t)) {
			this.mode = Mode.SEARCH;
			return;
		}
		boolean visible = now - this.lastSeenAt <= 2;
		Vec3 aim = visible ? FocusPoints.bodyCenter(t) : this.lastSeen.add(0, 1, 0);
		double d = this.body.distanceTo(t);
		// A shadow to cover the ground unseen, out again close by.
		if (st.shadowTicks > 0) {
			this.sense(st, false, false);
			if (d < 12 && this.wantOut(st, now)) {
				stat("shadow.out_close");
			}
			if (st.shadowTicks > 0 && d < 3.5) {
				// No nearer as a shadow: he couldn't step out of it there (see wantOut).
				this.walker.halt();
				this.walker.face(aim);
				return;
			}
		} else if (d > 14 && !this.watchedBy(t) && !this.lit(t.blockPosition()) && MatchManager.jumpBlockReason(this.body, st) == 0 && st.jumpCooldown == 0) {
			MatchManager.onShapeJump(this.body);
			stat("shadow.in_approach");
		} else {
			// No Sense on the run: it slows him to a walk.
			this.sense(st, false, false);
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
				MatchManager.onShapeAttack(this.body, t.getId(), this.lastVelocity.horizontalDistance() < 0.05);
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
			Vec3 to = visible ? t.position().add(this.lastVelocity.multiply(6, 0, 6)) : this.lastSeen.add(this.lastVelocity.scale(30));
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

	/** Path searches he may still make now: one comes back every few ticks, a few can be saved up (see follow). */
	private float searches = 4f;
	private int searchedAt;

	/**
	 * A path there. Searching is costly (thousands of cells when the place can't be reached), and many callers ask
	 * again whenever he stands still: at most one search every six ticks on average, or the server chokes.
	 */
	private boolean follow(ShapeState st, BlockPos to, double within) {
		return this.follow(st, to, within, false);
	}

	/**
	 * As above; {@code fixed}: a place that stays put (not someone running). One he has found no way to is left be:
	 * no path (he stops, and the caller sees him idle and gives it up). A search that ends well short of a near place
	 * means there is no way there: it goes on that list for three minutes.
	 */
	private boolean follow(ShapeState st, BlockPos to, double within, boolean fixed) {
		int now = this.level().getServer().getTickCount();
		if (fixed && this.unreachable.containsKey(to)) {
			this.walker.stop();
			return true;
		}
		if (!fixed) {
			this.aim = null;
		} else if (!to.equals(this.aim)) {
			if (this.lightJob == null && !to.equals(this.lightJob)) {
				BlockPos breaker = this.breakerFor(to);
				if (breaker != null && !breaker.equals(to)) {
					this.lightJob = breaker;
					this.lightJobUntil = now + 20 * 25;
					stat("lights.first");
				}
			}
			this.aim = to;
			this.bestDist = this.body.position().distanceTo(Vec3.atBottomCenterOf(to));
			this.bestAt = now;
			this.unstick = 0;
		}
		this.searches = Math.min(4f, this.searches + (now - this.searchedAt) / 6f);
		this.searchedAt = now;
		if (this.searches < 1f) {
			return false;
		}
		this.searches -= 1f;
		boolean shadow = st.shadowTicks > 0;
		Nav.Options opt = shadow ? Nav.Options.climber().lockedDoors(0.5) : Nav.Options.walker().lockedDoors(3);
		this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), to, within, opt.nodes(4000)));
		this.pathLeft = this.walker.remaining();
		stat("search");
		if (fixed && this.body.position().distanceTo(Vec3.atBottomCenterOf(to)) < 24) {
			// No path at all, though he isn't there yet; or one that ends well short of it.
			BlockPos end = this.walker.destination();
			double left = this.body.position().distanceTo(Vec3.atBottomCenterOf(to));
			if (end == null ? left > within + 0.5 : Vec3.atBottomCenterOf(end).distanceTo(Vec3.atBottomCenterOf(to)) > within + 1.5) {
				this.giveUp(to, now, "unreachable.path");
			}
		}
		return true;
	}

	/** Steps along the path: a locked door in the way gets broken, a window slipped through as a shadow. */
	private Walker.Status walk(ShapeState st, int now) {
		if (st.shadowTicks > 0 && !this.escaping && this.litAhead(5) && !this.lit(this.body.blockPosition())
			&& MatchManager.jumpBlockReason(this.body, st) == 0) {
			// Light ahead: out of the shadow while it is still dark here, and on in his own form.
			MatchManager.onShapeJump(this.body);
			stat("shadow.out_before_light");
		}
		this.walker.throughDoors = st.shadowTicks > 0;
		Walker.Status status = this.walker.tick();
		this.lastStatus = status;
		if (this.aim != null && !this.walker.idle() && status != Walker.Status.LOCKED) {
			// Nearer as the crow flies, or fewer steps to go (going round a wall takes him away first).
			int left = this.walker.remaining();
			boolean onward = left < this.pathLeft;
			this.pathLeft = left;
			double d = this.body.position().distanceTo(Vec3.atBottomCenterOf(this.aim));
			if (d < this.bestDist - 0.5 || onward) {
				this.bestDist = d;
				this.bestAt = now;
			} else if (now - this.bestAt > 80) {
				this.stalled(st, now);
			}
		}
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
			stat("stuck");
			if (this.aim != null) {
				this.stalled(st, now);
			} else {
				this.walker.stop();
			}
		}
		return status;
	}

	/**
	 * Four seconds without getting any nearer: first into the shadow (it goes through doors and windows), then a step
	 * aside and a fresh path, then he gives the place up.
	 */
	private void stalled(ShapeState st, int now) {
		this.bestAt = now;
		this.unstick++;
		stat("stall");
		if (this.unstick == 1 && st.shadowTicks == 0 && MatchManager.jumpBlockReason(this.body, st) == 0 && !this.litAhead(6)) {
			MatchManager.onShapeJump(this.body);
			this.walker.stop();
			this.repathAt = now;
			stat("unstick.shadow");
			return;
		}
		if (this.unstick <= 2) {
			// A step aside, then a fresh path from there.
			for (int k = 0; k < 8; k++) {
				BlockPos side = this.body.blockPosition().offset(this.random.nextInt(5) - 2, 0, this.random.nextInt(5) - 2);
				if (Nav.standable(this.level(), side) && !side.equals(this.body.blockPosition())) {
					this.walker.follow(List.of(new Nav.Node(side, Nav.Step.WALK, null)));
					this.repathAt = now + 15;
					stat("unstick.step");
					return;
				}
			}
		}
		this.giveUp(this.aim, now, "unreachable.stall");
	}

	// ---------------------------------------------------------------- light and the shadow

	/** Is it light here (feet or head): no stepping out of the shadow, everyone sees him. */
	private boolean lit(BlockPos feet) {
		int threshold = HwConfig.get().lightThreshold;
		ServerLevel level = this.level();
		return level.getBrightness(LightLayer.BLOCK, feet) > threshold || level.getBrightness(LightLayer.BLOCK, feet.above()) > threshold;
	}

	/** Light within the next few steps of his path. */
	private boolean litAhead(int steps) {
		for (Nav.Node n : this.walker.ahead(steps)) {
			if (this.lit(n.pos())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Out of the shadow, if he is in it: at once where he may; caught in the light, he makes for the dark first (see
	 * escapeLight). True when he is in his own form.
	 */
	private boolean wantOut(ShapeState st, int now) {
		if (st.shadowTicks == 0) {
			return true;
		}
		int reason = MatchManager.jumpBlockReason(this.body, st);
		if (reason == 0) {
			MatchManager.onShapeJump(this.body);
			this.walker.stop();
			this.repathAt = now;
			return true;
		}
		// In the light (1), inside a door (5), right by someone (6): a step to where he may comes first.
		if ((reason == 1 || reason == 5 || reason == 6) && !this.escaping) {
			this.escaping = true;
			this.escapeUntil = now + 20 * 10;
			this.walker.stop();
			stat(reason == 1 ? "shadow.caught_in_light" : reason == 5 ? "shadow.caught_in_door" : "shadow.caught_too_close");
		}
		return false;
	}

	/**
	 * A shadow can't step out of it in the light, inside a door, or right by someone: to the nearest place where he
	 * may (out of the house through a door or a window if need be), out of the shadow there, and on in his own form.
	 * True while that is what he is doing.
	 */
	private boolean escapeLight(ShapeState st, int now) {
		if (!this.escaping) {
			return false;
		}
		if (st.shadowTicks == 0 || now > this.escapeUntil) {
			this.escaping = false;
			return false;
		}
		int reason = MatchManager.jumpBlockReason(this.body, st);
		if (reason == 0) {
			MatchManager.onShapeJump(this.body);
			this.escaping = false;
			this.walker.stop();
			this.repathAt = now;
			stat("shadow.out_in_dark");
			return true;
		}
		if (this.walker.idle()) {
			ServerLevel level = this.level();
			double room = HwConfig.get().shapeJumpExitMinDistance + 1;
			List<Vec3> people = new ArrayList<>();
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.body.getBoundingBox().inflate(28), MatchManager::isVictim)) {
				people.add(e.position());
			}
			List<Nav.Node> path = Nav.search(level, this.body.blockPosition(), 24, Nav.Options.climber().lockedDoors(0.5).nodes(3000), (pos, cost) -> {
				if (this.lit(pos) || level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
					return -1000;
				}
				Vec3 c = Vec3.atBottomCenterOf(pos);
				for (Vec3 p : people) {
					if (p.distanceTo(c) < room) {
						return -1000;
					}
				}
				return 100 - cost;
			});
			stat("search");
			if (path.isEmpty()) {
				// Nowhere dark within reach: he stays a shadow and goes on as he was.
				this.escaping = false;
				stat("shadow.no_dark");
				return false;
			}
			this.walker.follow(path);
		}
		this.walk(st, now);
		return true;
	}

	/**
	 * The place he makes for is lit by a house's lights: the breaker that works them, near it, if it is on (and he
	 * hasn't just been at it).
	 */
	private BlockPos breakerFor(BlockPos target) {
		if (!this.lit(target)) {
			return null;
		}
		ServerLevel level = this.level();
		BlockPos best = null;
		for (BlockPos b : LightSystem.breakers(level, target, HwConfig.get().breakerRadius)) {
			if (LightSystem.isOn(level, b) && !this.done.containsKey(b) && !this.unreachable.containsKey(b)
				&& (best == null || b.distSqr(target) < best.distSqr(target))) {
				best = b;
			}
		}
		return best != null && Vec3.atCenterOf(best).distanceTo(this.body.position()) < 40 ? best : null;
	}

	/** To the breaker in his own form (a shadow can't work it), and off with the lights. True while at it. */
	private boolean switchOff(ShapeState st, int now) {
		BlockPos b = this.lightJob;
		ServerLevel level = this.level();
		if (!LightSystem.isOn(level, b) || now > this.lightJobUntil) {
			this.lightJob = null;
			return false;
		}
		if (!this.wantOut(st, now)) {
			// Still a shadow (escapeLight takes over if he is caught in the light).
			return false;
		}
		Vec3 at = Vec3.atCenterOf(b);
		if (at.distanceTo(this.body.getEyePosition()) > 2.6) {
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 30;
				if (this.follow(st, b, 2, true) && this.walker.idle()) {
					this.done.put(b, now + 20 * 120);
					this.lightJob = null;
					return false;
				}
			}
			this.walk(st, now);
			return true;
		}
		this.walker.halt();
		this.walker.face(at);
		LightSystem.flip(level, b, false);
		this.done.put(b, now + 20 * 120);
		this.lightJob = null;
		this.repathAt = now;
		stat("power.off.before_going_in");
		return true;
	}

	/** No way there: he leaves the place be for three minutes, and stops (his caller sees him idle and moves on). */
	private void giveUp(BlockPos place, int now, String why) {
		if (place != null) {
			this.unreachable.put(place, now + 20 * 180);
			stat(why);
		}
		this.aim = null;
		this.walker.stop();
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
				if (this.follow(st, where, 2, true) && this.walker.idle()) {
					this.lookAroundUntil = now + 60;
				}
			}
			this.sense(st, false, false);
			Walker.Status status = this.walk(st, now);
			if (status == Walker.Status.ARRIVED || status == Walker.Status.IDLE) {
				this.lookAroundUntil = now + 60;
			}
			return;
		}
		if (this.lookAroundUntil == 0) {
			this.lookAroundUntil = now + 60;
		}
		if (now < this.lookAroundUntil) {
			// Turning round, Sense up, listening.
			this.walker.halt();
			this.sense(st, true, false);
			this.glance(now);
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
			if (st.shadowTicks > 0 && this.body.position().distanceTo(Vec3.atBottomCenterOf(front)) < 8) {
				this.wantOut(st, now);
			}
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 20;
				if (this.follow(st, front, 0.8, true) && this.walker.idle()) {
					this.suspects.remove(base);
					this.done.put(base, now + 600);
					return false;
				}
			}
			this.walk(st, now);
			return true;
		}
		if (!this.wantOut(st, now)) {
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
		if (testGoal != null) {
			this.mode = Mode.PATROL;
			this.goal = testGoal;
			if (this.body.position().distanceTo(Vec3.atBottomCenterOf(testGoal)) > 1.8 && !this.unreachable.containsKey(testGoal)) {
				this.travel(st, now, testGoal, 1.2);
			} else {
				this.walker.halt();
			}
			return;
		}
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
		if (watch != null && this.done.containsKey(watch)) {
			watch = null;
		}
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
				this.glance(now);
			}
			return;
		}
		if (this.camping != null) {
			// Watched long enough: not that way out again for two minutes (else he stands there all match).
			this.done.put(this.camping, now + 20 * 120);
		}
		this.camping = null;
		// Now and then he stops in the dark and looks all round with his Sense (it tires him): someone far off.
		this.mode = Mode.PATROL;
		if (now < this.scanUntil) {
			this.walker.halt();
			this.sense(st, true, false);
			this.glance(now);
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
		} else if (st.shadowTicks > 0 && d < 8) {
			this.wantOut(st, now);
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + 40;
			if (this.follow(st, to, within, true) && this.walker.idle() && d > within + 1) {
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
		spots.removeIf(p -> this.done.containsKey(p) || this.unreachable.containsKey(p) || p.distSqr(this.body.blockPosition()) < 36);
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
			if (this.done.containsKey(e.pos()) || this.unreachable.containsKey(e.pos()) || Vec3.atCenterOf(e.pos()).distanceTo(me) > 30) {
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
				if (Exits.phoneWorks(phone) && !this.done.containsKey(phone) && !this.unreachable.containsKey(phone) && Vec3.atCenterOf(phone).distanceTo(me) < 14) {
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
			if (st.shadowTicks > 0 && this.body.position().distanceTo(Vec3.atCenterOf(at)) < 6) {
				this.wantOut(st, now);
			}
			if (now >= this.repathAt || this.walker.idle()) {
				this.repathAt = now + 30;
				if (this.follow(st, at, 1.6, true) && this.walker.idle()) {
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
