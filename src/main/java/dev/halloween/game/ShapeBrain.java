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
		PATROL, LISTEN, STALK, CHASE, RETREAT, LEAVE, SEARCH, CAMP, WORK, RAID
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

	/**
	 * A sign that someone has been there (a door moved, a drawer gone through, the lights on again, blood): where,
	 * when he noticed it, how much it says, and the building it is in or by ({@link #NO_HOUSE} if none).
	 */
	private record Clue(Vec3 pos, int at, double weight, long house) {
	}

	private static final long NO_HOUSE = Long.MIN_VALUE;
	/** How long a clue counts for anything (it fades out over this). */
	private static final int CLUE_TICKS = 20 * 120;

	/** What he last saw of a thing that gives people away (a door open, a breaker on, a drawer gone through), and when. */
	private record Seen(boolean state, int at) {
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
	/** How close he goes to where he keeps watch: near a way out, or right on the spot he watches a house from. */
	private double campWithin = 7;
	/** What he keeps his eyes on while he watches (the house he left), or null: he looks about. */
	private Vec3 campFace;
	private final List<Clue> clues = new ArrayList<>();
	private final Map<BlockPos, Seen> props = new HashMap<>();
	/** Noises of his own making a step or two off (a breaker he flips, a wardrobe he throws open, a door he hits), and when. */
	private final List<Lead> ownNoise = new ArrayList<>();
	/** The newest drop of blood he has followed. */
	private int lastDripAt = -1;
	/** The blackout as he last knew it: when it ends every breaker comes back on, and that is nobody's sign. */
	private boolean blackoutWas;
	/** A house he goes through room by room: the rooms in the order he takes them, how far he has got. */
	private Houses.Building raid;
	private final List<Houses.Room> raidRooms = new ArrayList<>();
	private int raidRoom;
	private int raidSpot;
	private int raidStarted;
	private int raidUntil;
	/** When he got to where he stands looking round the room (-1: not there yet). */
	private int roomLookAt = -1;
	private float sweepYaw;
	private int raidLockers;
	/** Gone in after someone seen lately or on good signs: worth watching the house a while after, if he finds nobody. */
	private boolean raidFresh;
	private int raidCheckAt;
	/** Houses he went through, by key, and when he was done. */
	private final Map<Long, Integer> raided = new HashMap<>();
	private boolean raidImpulse;
	/** The raid's shadow: tried once at its start; the wardrobes left for a pass in his own form at the end. */
	private boolean raidShadowTried;
	private boolean raidLockersLeft;
	private boolean raidLockerPass;
	private int slipRepath;

	// What his screen and his eyes and ears give him of the others (a person's knowledge, no more).
	/** His Runt as his screen shows it (HwNet.RuntInfo, every 5 ticks): entity id, whom it holds (-1), bot or not, status, when. */
	private int runtId = -1;
	private int runtHolding = -1;
	private boolean runtBot;
	private int runtStatus = -1;
	private int runtInfoAt = -1000;
	/** His last order (the wheel takes a moment), and whom he last sent it at and when (not the same one again at once). */
	private int runtOrderAt = -1000;
	private int runtAttackId = -1;
	private int runtAttackAt = -1000;
	/** A house he sent the Runt into while he watches its ways out, whether he still has to point it in, houses sent to. */
	private Houses.Building runtSent;
	private boolean runtSearchPending;
	private final java.util.Set<Long> runtSentTo = new java.util.HashSet<>();
	/** Who he hears breathing hard out in the open (entity id), since when. */
	private final Map<Integer, Integer> panting = new HashMap<>();

	/** A gun he has seen in someone's hand: what, and the rounds he reckons are in it (by the shots and reloads he heard). */
	private record Gun(HwItems.Kind kind, int loaded, int at) {
	}

	private final Map<java.util.UUID, Gun> guns = new HashMap<>();
	/** Someone in sight with a gun on him that would floor him (this tick), and whom he keeps out of the line of, until when. */
	private LivingEntity threat;
	private LivingEntity shooter;
	private int evadeUntil;
	private int evadeRepath;
	/** Traps he has seen (until when he keeps them in mind), and spots he heard one being set. */
	private final Map<BlockPos, Integer> traps = new HashMap<>();
	private final Map<BlockPos, Integer> trapsHeard = new HashMap<>();
	private int trappedWas;
	/** Flames about (a house on fire near him): paths keep out of them. */
	private boolean fireNear;
	/** A firecracker landed by him: where, and until when he keeps away from it and his eyes off it. */
	private Vec3 cracker;
	private int crackerUntil;
	/** The patrol car, last heard (its engine) or seen (its light bar): when. */
	private int patrolAtTick = -1000;
	/** A phone heard ringing (someone calling the police, Loomis on the line): which, and until when it is worth cutting. */
	private BlockPos ringing;
	private int ringingUntil;
	/** Townsfolk he saw at a survivor's heels lately (being led out), by entity id, and when. */
	private final Map<Integer, Integer> escorted = new HashMap<>();
	/** Midnight reveals already followed (ids glowing now), and when he last followed the beams. */
	private final java.util.Set<Integer> glowSeen = new java.util.HashSet<>();
	private int beamsAt = -1000;
	/** No grab tried again for a moment (each try is a lunge everyone sees). */
	private int grabTryAt;
	/** Where he last ran to head them off. */
	private BlockPos lastCut;
	private int impulseAt;
	/** The way out he keeps watch on (he may wait for it from a hiding place near it, see ambushSpot). */
	private BlockPos campFor;
	/** Where he stalks from (dark, against something, their head in view), and when he last looked for such a place. */
	private BlockPos stalkSpot;
	private int stalkSpotAt = -1000;
	/** Who hurt him (a blow, a shove, a shot he saw them fire), until when he keeps it in mind; his meters last tick. */
	private final Map<java.util.UUID, Integer> hurtBy = new HashMap<>();
	private float stunWas;
	private int stunnedWas;
	private int pushedWas;

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
		Houses.clear();
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
			+ " best " + String.format("%.1f", b.bestDist) + " unstick " + b.unstick + " tokens " + String.format("%.1f", b.searches) + " zza " + b.body.zza
			+ " raid " + (b.raid == null ? "-" : b.raidRoom + "/" + b.raidRooms.size()) + " clues " + b.clues.size()
			+ " traps " + b.traps.size() + " runt " + b.runtStatus + (b.runtHolding >= 0 ? " holding " + b.runtHolding : "");
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
			b.clues.clear();
			b.props.clear();
			b.lastDripAt = -1;
			b.raid = null;
			b.raided.clear();
			b.campWithin = 7;
			b.campFace = null;
			b.runtSent = null;
			b.runtSearchPending = false;
			b.runtSentTo.clear();
			b.runtAttackId = -1;
			b.panting.clear();
			b.guns.clear();
			b.threat = null;
			b.shooter = null;
			b.evadeUntil = 0;
			b.traps.clear();
			b.trapsHeard.clear();
			b.cracker = null;
			b.crackerUntil = 0;
			b.ringing = null;
			b.escorted.clear();
			b.glowSeen.clear();
			b.campFor = null;
			b.stalkSpot = null;
			b.hurtBy.clear();
			b.mode = Mode.PATROL;
			b.walker.stop();
		}
	}

	/** Tests: how many rooms of the house he is going through he has done, of how many (null: no raid). */
	public static int[] raidProgress() {
		return brain == null || brain.raid == null ? null : new int[]{brain.raidRoom, brain.raidRooms.size()};
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
					if (pant.volume() >= HwConfig.get().staminaPantVolume - 1e-3f) {
						// Breathing hard out in the open (run out, or scared): heard a few blocks off.
						if (pant.on() && who != null && who.distanceTo(this.body) <= 16) {
							this.panting.put(pant.entityId(), now);
							this.lead(who.position(), now, 2.5, "pant");
						} else if (!pant.on()) {
							this.panting.remove(pant.entityId());
						}
					} else if (pant.on() && who != null) {
						// Breathing in a wardrobe right by him.
						BlockPos locker = this.nearestLocker(who.blockPosition(), 2, false);
						if (locker != null) {
							this.suspects.put(locker, now + 400);
							stat("heard.breathing");
						}
					}
				}
				case HwNet.RuntInfo info -> {
					this.runtId = info.runtId();
					this.runtHolding = info.holdingId();
					this.runtBot = info.bot();
					this.runtStatus = info.status();
					this.runtInfoAt = now;
					// Shared sight (outlined on his screen while fresh), and whom it holds (marked wherever they are).
					for (int id : info.scent()) {
						this.revealed.merge(id, now + 20, Integer::max);
					}
					if (info.holdingId() >= 0) {
						this.revealed.merge(info.holdingId(), now + 40, Integer::max);
					}
				}
				case HwNet.EventSync sync -> {
					// The midnight bell: every survivor shown to him a moment, once a minute.
					for (int id : sync.glows()) {
						this.revealed.merge(id, now + 15, Integer::max);
						if (this.glowSeen.add(id)) {
							Entity e = this.level().getEntity(id);
							if (e != null) {
								this.lead(e.position(), now, 6, "shown");
							}
						}
					}
					this.glowSeen.retainAll(sync.glows());
				}
				case HwNet.ShapeHud hud -> {
					// Bloodthirst at its height, a shadow: beams over everyone now and then.
					if (!hud.beams().isEmpty() && now - this.beamsAt > 40) {
						this.beamsAt = now;
						for (Vec3 b : hud.beams()) {
							this.lead(b, now, 4, "beam");
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
		for (Lead own : this.ownNoise) {
			if (now - own.at() < 15 && own.pos().distanceTo(at) < 2) {
				return;
			}
		}
		for (ServerPlayer p : this.level().players()) {
			if (MatchManager.role(p) == dev.halloween.Role.RUNT && p.position().distanceTo(at) < 2) {
				// The Runt's: his own side.
				return;
			}
		}
		String id = sound.getSound().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		float pitch = sound.getPitch();
		double weight;
		String kind;
		if (id.contains("death")) {
			// A last scream: nobody left there to find (and mostly his own work).
			return;
		} else if (id.contains("minecart.riding") && Math.abs(pitch - 0.45f) < 0.03f) {
			// The patrol car's engine (lower than the getaway car's): keep out of its headlights, nobody to find there.
			this.patrolAtTick = now;
			return;
		} else if (id.equals("entity.tnt.primed")) {
			// A firecracker down by him: it goes off in a moment.
			if (at.distanceTo(this.body.getEyePosition()) < 8) {
				this.cracker = at;
				this.crackerUntil = now + 34;
				stat("heard.cracker");
			}
			return;
		} else if (id.equals("gun.ricochet")) {
			// Where the bullet went, not who fired it.
			return;
		} else if (id.equals("block.iron_trapdoor.open") || id.equals("block.chain.place") || id.equals("block.iron_chain.place")) {
			// A trap being set (jaws opened, a can string staked).
			this.trapsHeard.put(BlockPos.containing(at), now + 20 * 120);
			weight = 2;
			kind = "trap.set";
		} else if (id.equals("block.note_block.bell") && sound.getSource() != net.minecraft.sounds.SoundSource.MASTER) {
			// A phone ringing: someone calling the police, or Loomis on the line. Cut it.
			BlockPos phone = this.phoneNear(at, 3);
			if (phone != null && Exits.phoneWorks(phone)) {
				this.ringing = phone;
				this.ringingUntil = now + 20 * 20;
			}
			weight = 4;
			kind = "phone";
		} else if (id.equals("block.chain.break") || id.equals("block.iron_chain.break")) {
			// A padlock coming off a cellar (or a can string set off).
			weight = 5;
			kind = "exit";
		} else if (id.equals("item.bucket.fill") || id.equals("block.anvil.use") || id.equals("block.copper_bulb.turn_on")) {
			// Fuel going in, a car being mended, a fuse clicking home.
			weight = 4;
			kind = "exit";
		} else if (id.equals("block.bell.use")) {
			// The police are here.
			weight = 3;
			kind = "police";
		} else if (id.equals("gun.revolver") || id.equals("gun.rifle")) {
			this.gunHeard(at, now, 0);
			weight = 4;
			kind = "shot";
		} else if (id.equals("gun.dry")) {
			this.gunHeard(at, now, 1);
			weight = 2;
			kind = "shot";
		} else if (id.equals("gun.load_round")) {
			this.gunHeard(at, now, 2);
			weight = 2;
			kind = "shot";
		} else if (id.equals("gun.bolt") && pitch < 0.9f) {
			// The rifle's bolt home again: loaded.
			this.gunHeard(at, now, 3);
			weight = 1.5;
			kind = "shot";
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
			// (Not one he has just been through himself: that was him pushing its door to.)
			if (locker != null && !this.done.containsKey(locker)) {
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

	/** A noise he is about to make himself: not one to go and see. */
	private void own(Vec3 at, int now) {
		this.ownNoise.removeIf(n -> now - n.at() >= 15);
		this.ownNoise.add(new Lead(at, now, 0, "own"));
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
		if (light >= 7 || e instanceof ServerPlayer p && Fight.shining(p)) {
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

	/**
	 * Who is in sight (marked ones, outlined red on his screen, at any distance in his line of sight), what they hold,
	 * who walks at whose heels, whether a gun is on him; and the best one to go for (see preyScore).
	 */
	private LivingEntity look(ShapeState st, int now) {
		this.threat = null;
		if (st.blindTicks > 0) {
			return null;
		}
		ServerLevel level = this.level();
		Vec3 eye = this.body.getEyePosition();
		Vec3 facing = this.body.getViewVector(1f);
		List<LivingEntity> seen = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.body.getBoundingBox().inflate(45), MatchManager::exposed)) {
			Vec3 c = FocusPoints.bodyCenter(e);
			double d = eye.distanceTo(c);
			boolean shown = this.revealed.getOrDefault(e.getId(), 0) > now;
			if (!shown) {
				double range = MatchManager.marked(e) ? 256 : this.sightRange(e, st);
				if (d > range || d > 2.5 && Sight.angleTo(eye, facing, c) > 75) {
					continue;
				}
				if (!Sight.canSee(level, eye, c, this.body) && !Sight.canSee(level, eye, e.getEyePosition(), this.body)) {
					continue;
				}
			}
			seen.add(e);
		}
		for (LivingEntity e : seen) {
			this.noteArms(e, now);
			if (Townsfolk.isNpc(e) && e.getDeltaMovement().horizontalDistanceSqr() > 0.0009) {
				for (LivingEntity o : seen) {
					if (o instanceof ServerPlayer && o.distanceTo(e) < 4.5) {
						// At a survivor's heels, on the move: being led out.
						this.escorted.put(e.getId(), now);
						break;
					}
				}
			}
			if (st.shadowTicks == 0 && this.body.distanceTo(e) > 4.5 && this.gunOnMe(e) && this.deadly(e, st)) {
				this.threat = e;
			}
		}
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (LivingEntity e : seen) {
			double score = this.preyScore(e, seen, eye.distanceTo(FocusPoints.bodyCenter(e)), now);
			if (score < bestScore) {
				bestScore = score;
				best = e;
			}
		}
		return best;
	}

	/**
	 * Whom to go for among those in sight (lower is better): the nearest; survivors before officers before townsfolk;
	 * and whoever is easiest or matters most: held by his Runt, at a way out, a way out in hand (keys, a fuse...),
	 * bleeding, out of breath, a neighbour being led out. A loaded gun in hand, an armed officer, friends about count
	 * against.
	 */
	private double preyScore(LivingEntity e, List<LivingEntity> seen, double d, int now) {
		HwItems.Kind k = HwItems.kindOf(e.getMainHandItem());
		boolean armed = k != null && (k.gun() || k == HwItems.Kind.FLARE_GUN) && this.gunLoaded(e, k);
		double s = d;
		if (Police.isOfficer(e)) {
			s += armed ? 40 : 12;
		} else if (!(e instanceof ServerPlayer)) {
			s += 25;
			if (Townsfolk.isNpc(e) && now - this.escorted.getOrDefault(e.getId(), -1000) < 60) {
				// Being led out: a resident lost and a way out used up.
				s -= 12;
			}
		} else {
			if (k != null && k.escape()) {
				s -= 8;
			}
			if (this.nearExit(e)) {
				s -= 10;
			}
			if (Blood.bleeding(e)) {
				s -= 4;
			}
			if (this.panting.containsKey(e.getId()) && !e.isSprinting() && e.distanceTo(this.body) <= 16) {
				s -= 5;
			}
			if (armed) {
				s += 6;
			}
			for (LivingEntity o : seen) {
				if (o != e && o instanceof ServerPlayer && o.distanceTo(e) < 6) {
					HwItems.Kind ok = HwItems.kindOf(o.getMainHandItem());
					s += ok != null && (ok.gun() || ok.melee()) ? 5 : 2;
				}
			}
		}
		if (e == this.target) {
			s -= 6;
		}
		if (e.getId() == this.runtHolding && this.runtFresh(now)) {
			s -= 30;
		}
		return s;
	}

	/** A gun he sees in someone's hand: noted, full unless he has heard otherwise. */
	private void noteArms(LivingEntity e, int now) {
		HwItems.Kind k = HwItems.kindOf(e.getMainHandItem());
		if (k != null && k.gun()) {
			Gun g = this.guns.get(e.getUUID());
			if (g == null || g.kind() != k) {
				this.guns.put(e.getUUID(), new Gun(k, Guns.capacity(k), now));
			}
		}
	}

	/** Is there anything in it, by his count? (A flare gun is good for one shot, and it goes once fired.) */
	private boolean gunLoaded(LivingEntity e, HwItems.Kind k) {
		Gun g = this.guns.get(e.getUUID());
		return !k.gun() || g == null || g.kind() != k || g.loaded() > 0;
	}

	/**
	 * A gun heard where someone stands whose gun he has seen: {@code what} 0 a shot, 1 a dry click, 2 a round going in,
	 * 3 the rifle's bolt home. His count of what is left in it.
	 */
	private void gunHeard(Vec3 at, int now, int what) {
		for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(2), MatchManager::isVictim)) {
			Gun g = this.guns.get(e.getUUID());
			if (g == null) {
				continue;
			}
			int full = Guns.capacity(g.kind());
			int loaded = switch (what) {
				case 0 -> Math.max(0, g.loaded() - 1);
				case 2 -> Math.min(full, g.loaded() + 1);
				case 3 -> full;
				default -> 0;
			};
			this.guns.put(e.getUUID(), new Gun(g.kind(), loaded, now));
			if (what == 0 && loaded == 0) {
				stat("gun.empty");
			}
			return;
		}
	}

	/** A gun levelled at him: in their hand (loaded by his count), their face turned on him, a clear line, in its range. */
	private boolean gunOnMe(LivingEntity e) {
		HwItems.Kind k = HwItems.kindOf(e.getMainHandItem());
		if (k == null || !(k.gun() || k == HwItems.Kind.FLARE_GUN) || !this.gunLoaded(e, k)) {
			return false;
		}
		Vec3 me = FocusPoints.bodyCenter(this.body);
		Vec3 eye = e.getEyePosition();
		double range = k == HwItems.Kind.RIFLE ? 110 : k == HwItems.Kind.SHOTGUN ? 18 : k == HwItems.Kind.FLARE_GUN ? 40 : 50;
		return eye.distanceTo(me) <= range && Sight.angleTo(eye, e.getViewVector(1f), me) < 12
			&& Guns.lineClear(this.level(), e, eye, me, this.body);
	}

	/** Would one more hit from it floor him: a rifle, a flare, a shotgun close by, or his stun meter near full? */
	private boolean deadly(LivingEntity e, ShapeState st) {
		HwItems.Kind k = HwItems.kindOf(e.getMainHandItem());
		if (k == null) {
			return false;
		}
		return switch (k) {
			case RIFLE, FLARE_GUN -> true;
			case SHOTGUN -> this.body.distanceTo(e) < 10 || st.stun > 0.5f;
			case REVOLVER -> st.stun > 0.6f;
			default -> false;
		};
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
		this.clues.removeIf(c -> now - c.at > CLUE_TICKS);
		this.raided.values().removeIf(at -> now - at > 20 * 300);
		this.traps.values().removeIf(until -> until < now);
		this.trapsHeard.values().removeIf(until -> until < now);
		this.escorted.values().removeIf(at -> now - at > 200);
		this.panting.values().removeIf(at -> now - at > 20 * 30);
		this.hurtBy.values().removeIf(until -> until < now);
		boolean hit = st.stun > this.stunWas + 1e-4f || st.stunnedTicks > 0 && this.stunnedWas == 0 || st.pushedTicks > 0 && this.pushedWas == 0;
		this.stunWas = st.stun;
		this.stunnedWas = st.stunnedTicks;
		this.pushedWas = st.pushedTicks;
		if (hit) {
			this.blame(now);
		}
		if (Events.quaking() && !this.suspects.isEmpty()) {
			// Midnight: the wardrobes are jammed, nobody is in one.
			this.suspects.clear();
		}
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
		if (st.trappedTicks > 0 && this.trappedWas == 0) {
			// Caught: that trap is spent (it was here).
			this.traps.keySet().removeIf(p -> Vec3.atBottomCenterOf(p).distanceTo(this.body.position()) < 1.8);
			stat("trapped");
		}
		this.trappedWas = st.trappedTicks;
		if (st.trappedTicks > 0 && st.shadowTicks == 0 && !st.busy() && !this.strikeNear(st, now)
			&& MatchManager.jumpBlockReason(this.body, st) == 0) {
			// Held by the jaws: a blow at anyone in reach, else out as a shadow (a shadow walks free of it).
			MatchManager.onShapeJump(this.body);
			stat("shadow.out_of_trap");
		}
		if (st.busy() || st.trappedTicks > 0 && st.shadowTicks == 0 || Windows.isVaulting(this.body)) {
			this.walker.halt();
			this.sense(st, false, false);
			return;
		}
		if (now % 10 == 0) {
			this.observe(st, now);
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
			// Someone in sight: the house can wait (gone through again later if they get away in it).
			this.raid = null;
		} else if (this.target != null && (this.mode == Mode.CHASE || this.mode == Mode.STALK)) {
			if (gone(this.target)) {
				this.afterDeath(this.target, now);
			} else if (now - this.lastSeenAt > 70 || !MatchManager.exposed(this.target)) {
				// Out of sight: on to where they were going.
				this.mode = Mode.SEARCH;
				this.lookAroundUntil = 0;
				stat("lost");
			}
		}
		this.rushToRunt(now);
		if (this.threat != null && (this.mode == Mode.CHASE || this.mode == Mode.STALK || this.mode == Mode.RETREAT)) {
			// A gun on him that would floor him: out of its line first.
			if (now >= this.evadeUntil) {
				stat("evade");
			}
			this.shooter = this.threat;
			this.evadeUntil = now + 30;
		}
		if (now < this.evadeUntil && this.evade(st, now)
			|| now < this.crackerUntil && this.dodge(st, now)
			|| this.avoidPatrol(st, now)
			|| this.escapeLight(st, now)) {
			MatchManager.onAim(this.body, this.body.getEyePosition(), this.body.getViewVector(1f));
			return;
		}
		if (this.lightJob != null && st.shadowTicks == 0 && this.mode != Mode.CHASE && this.mode != Mode.STALK && this.mode != Mode.RETREAT
			&& this.switchOff(st, now)) {
			MatchManager.onAim(this.body, this.body.getEyePosition(), this.body.getViewVector(1f));
			return;
		}
		switch (this.mode) {
			case STALK -> this.stalk(st, now);
			case CHASE -> this.chase(st, now);
			case RETREAT -> this.retreat(st, now);
			case LEAVE -> this.leave(st, now);
			case SEARCH -> this.search(st, now);
			case RAID -> this.raid(st, now);
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
		if (p.getId() == this.runtHolding && this.runtFresh(now)) {
			// Pinned by his Runt: no stalking, take them now.
			return Mode.CHASE;
		}
		HwItems.Kind held = HwItems.kindOf(p.getMainHandItem());
		if (MatchManager.stalkOf(p) >= this.goalFor(p) || this.nearExit(p) || held != null && held.escape() && this.nearExit(p, 20)) {
			// Stalked enough, or about to get out (at a way out, or making for one with its key in hand).
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
		// Going for them is the likeliest; backing off at most once for each of them.
		List<Reaction> options = new ArrayList<>(List.of(Reaction.FOLLOW, Reaction.RETREAT, Reaction.ATTACK, Reaction.ATTACK));
		if (this.retreats >= 1) {
			// Backed off once already.
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
		if (st.shadowTicks == 0 && MatchManager.jumpBlockReason(this.body, st) == 2 && this.watchedBy(e)) {
			// His screen says someone has eyes on him (dark or not), and these are turned his way.
			return true;
		}
		Vec3 away = e.position().subtract(this.body.position()).multiply(1, 0, 1);
		return d < 25 && away.lengthSqr() > 1e-4 && this.lastVelocity.multiply(1, 0, 1).dot(away.normalize()) > 0.15;
	}

	/** Right by a way out: no time for stalking. */
	private boolean nearExit(LivingEntity e) {
		return this.nearExit(e, 6);
	}

	/** Within {@code r} of a way out that could still take someone. */
	private boolean nearExit(LivingEntity e, double r) {
		for (HwNet.ExitInfo x : Exits.infos()) {
			if (exitProgress(x) >= 0 && Vec3.atBottomCenterOf(x.pos()).distanceTo(e.position()) < r) {
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
			// Nothing is stalked from the shadow: out of it (out of the house first, if they are in it with him).
			if (this.slipOut(st, now, t)) {
				return;
			}
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
		if (this.stalkSpot != null && !this.walker.idle() && this.body.position().distanceTo(Vec3.atBottomCenterOf(this.stalkSpot)) > 1.2) {
			// Into cover first, the Sense down while he goes.
			this.sense(st, false, false);
			this.walk(st, now);
			this.lookAt(t.getEyePosition());
			return;
		}
		if (this.findStalkSpot(t, now)) {
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

	/**
	 * Every few seconds while stalking: a better place to stalk from near by, if there is one: in the dark, against a
	 * wall or a tree, off the middle of their view, their head still in sight, not too near. True if he is off there.
	 */
	private boolean findStalkSpot(LivingEntity t, int now) {
		if (now - this.stalkSpotAt < 100 || this.searches < 1f) {
			return false;
		}
		this.stalkSpotAt = now;
		this.searches -= 1f;
		Vec3 head = t.getEyePosition();
		Vec3 look = t.getViewVector(1f);
		double far = HwConfig.get().senseRange - 7;
		BlockPos here = this.body.blockPosition();
		double stay = this.coverScore(here, head, look, far);
		List<Nav.Node> path = Nav.search(this.level(), here, 10, Nav.Options.walker().lockedDoors(3).nodes(500),
			(pos, cost) -> this.coverScore(pos, head, look, far) - cost * 1.5);
		stat("search");
		if (path.isEmpty()) {
			return false;
		}
		BlockPos end = path.getLast().pos();
		if (this.coverScore(end, head, look, far) < stay + 10) {
			return false;
		}
		this.stalkSpot = end;
		this.walker.follow(path);
		stat("stalk.cover");
		return true;
	}

	/** How good a place this is to stalk them from (or -1000: their head out of sight, too near, too far). */
	private double coverScore(BlockPos pos, Vec3 head, Vec3 look, double far) {
		ServerLevel level = this.level();
		Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, 1.6, 0);
		double d = eye.distanceTo(head);
		if (d < 8 || d > far || !Sight.canSee(level, eye, head, this.body)) {
			return -1000;
		}
		double s = this.lit(pos) ? -40 : 0;
		if (Sight.angleTo(head, look, eye) > 60) {
			s += 15;
		}
		if (this.againstSomething(pos)) {
			s += 8;
		}
		return s;
	}

	/** Something solid beside him at head height (a wall, a tree, a fence post): no outline against open ground. */
	private boolean againstSomething(BlockPos pos) {
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			if (Nav.passable(this.level(), pos.relative(dir).above()) == 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A dark place near a way out (4 to 10 blocks), against something, the way out in view: where he waits for whoever
	 * comes to it. Null if there is none.
	 */
	private BlockPos ambushSpot(BlockPos exit) {
		ServerLevel level = this.level();
		Vec3 x = Vec3.atCenterOf(exit).add(0, 1, 0);
		Nav.Scorer score = (pos, cost) -> {
			Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, 1.6, 0);
			double d = eye.distanceTo(x);
			if (d < 4 || d > 10 || !Sight.canSee(level, eye, x, this.body)) {
				return -1000;
			}
			return (this.lit(pos) ? -40 : 0) + (this.againstSomething(pos) ? 10 : 0) - Math.abs(d - 6);
		};
		List<Nav.Node> path = Nav.search(level, Nav.snap(level, exit), 14, Nav.Options.walker().lockedDoors(3).nodes(600), score);
		stat("search");
		if (path.isEmpty()) {
			return null;
		}
		BlockPos end = path.getLast().pos();
		return score.score(end, 0) > -30 ? end : null;
	}

	/** Hit just now (a blow, a shove, a shot): the one he sees who did it, kept in mind a few minutes. */
	private void blame(int now) {
		ServerLevel level = this.level();
		Vec3 me = FocusPoints.bodyCenter(this.body);
		Vec3 eye = this.body.getEyePosition();
		LivingEntity who = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.body.getBoundingBox().inflate(50), MatchManager::exposed)) {
			double d = e.getEyePosition().distanceTo(me);
			HwItems.Kind k = HwItems.kindOf(e.getMainHandItem());
			boolean gun = k != null && (k.gun() || k == HwItems.Kind.FLARE_GUN);
			// Close by and turned to him (a blow, a shove, a throw), or a gun on him.
			boolean could = d < 4 ? Sight.angleTo(e.getEyePosition(), e.getViewVector(1f), me) < 60
				: gun && Sight.angleTo(e.getEyePosition(), e.getViewVector(1f), me) < 12;
			if (could && d < best && Sight.canSee(level, eye, e.getEyePosition(), this.body)) {
				best = d;
				who = e;
			}
		}
		if (who != null) {
			this.hurtBy.put(who.getUUID(), now + 20 * 180);
			stat("hurt.by");
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
		this.leaveUntil = now + 20 * 5;
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
		boolean officer = Police.isOfficer(t);
		HwItems.Kind arm = HwItems.kindOf(t.getMainHandItem());
		// A loaded gun in their hand: come at them as a shadow, out of it only close by.
		boolean armed = arm != null && (arm.gun() || arm == HwItems.Kind.FLARE_GUN) && this.gunLoaded(t, arm) || this.hurtBy.containsKey(t.getUUID());
		if (visible && t instanceof ServerPlayer && d > 6 && d <= 60) {
			this.sendRunt(t, now);
		}
		// A shadow to cover the ground unseen, out again close by.
		if (st.shadowTicks > 0) {
			this.sense(st, false, false);
			if (d < 4.5 && this.wantOut(st, now)) {
				stat("shadow.out_close");
			}
			if (st.shadowTicks > 0 && d < 3.5) {
				// No nearer as a shadow: he couldn't step out of it there (see wantOut).
				this.walker.halt();
				this.walker.face(aim);
				return;
			}
		} else if ((d > 14 || armed && d > 6 || d > 8 && this.lastVelocity.horizontalDistance() > 0.2) && !this.watchedBy(t) && !this.lit(t.blockPosition()) && MatchManager.jumpBlockReason(this.body, st) == 0
			&& st.jumpCooldown == 0) {
			MatchManager.onShapeJump(this.body);
			stat(armed ? "shadow.in_approach.armed" : "shadow.in_approach");
		} else {
			// No Sense on the run: it slows him to a walk.
			this.sense(st, false, false);
		}
		if (st.shadowTicks == 0 && visible) {
			// Officers he strikes down (in a grab they nearly always break free); a grab tried at most every few ticks
			// (every try is a lunge).
			if (!officer && d <= cfg.grabRange - 0.2 && st.grabCooldown == 0 && now >= this.grabTryAt) {
				this.grabTryAt = now + 4;
				this.walker.face(aim);
				MatchManager.onAim(this.body, this.body.getEyePosition(), aim.subtract(this.body.getEyePosition()).normalize());
				MatchManager.onGrab(this.body, t.getId());
				if (st.grab != null) {
					stat("grab");
					return;
				}
			}
			if (d <= cfg.attackReach && (st.grabCooldown > 0 || officer) && st.attackCooldown == 0) {
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
			// Where they will be in a moment, if they are running; or, running for a way out, there ahead of them.
			Vec3 to = visible ? t.position().add(this.lastVelocity.multiply(6, 0, 6)) : this.lastSeen.add(this.lastVelocity.scale(30));
			BlockPos cut = visible && d > 10 ? this.cutOff(t) : null;
			this.follow(st, cut != null ? cut : BlockPos.containing(to), 1.0);
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
			if (st.shadowTicks == 0 && this.lightJob == null && !to.equals(this.lightJob)) {
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
		BlockPos fire = Events.fireHouse();
		this.fireNear = fire != null && Vec3.atCenterOf(fire).distanceTo(this.body.position()) < 60;
		if (!shadow && (!this.traps.isEmpty() || !this.trapsHeard.isEmpty() || this.fireNear)) {
			// A shadow walks over traps and through fire unharmed; in his own form he keeps off them.
			opt.cellCost(this::hazardCost);
		}
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
		boolean soonOut = this.mode == Mode.WORK || this.mode == Mode.CAMP || testGoal != null
			|| this.mode == Mode.CHASE && this.target != null && this.body.distanceTo(this.target) < 6;
		if (soonOut && st.shadowTicks > 0 && !this.escaping && this.litAhead(5) && !this.lit(this.body.blockPosition())
			&& MatchManager.jumpBlockReason(this.body, st) == 0) {
			// Light ahead: out of the shadow while it is still dark here, and on in his own form.
			MatchManager.onShapeJump(this.body);
			stat("shadow.out_before_light");
		}
		this.walker.throughDoors = st.shadowTicks > 0;
		Walker.Status status = this.walker.tick();
		this.lastStatus = status;
		BlockPos passed = this.walker.takePassedDoor();
		if (passed != null) {
			// His own doing: no sign of anyone when he sees it open later.
			BlockPos door = DoorLocks.base(this.level(), passed);
			net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(door);
			if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
				this.props.put(door, new Seen(state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN), now));
			}
		}
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
				this.own(door, now);
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
		this.own(at, now);
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
		if (now >= this.raidCheckAt) {
			// Last seen in a house, or making for one: through it room by room.
			this.raidCheckAt = now + 20;
			Houses.Building b = Houses.at(this.level(), BlockPos.containing(this.lastSeen));
			if (b == null) {
				b = Houses.at(this.level(), where);
			}
			if (b != null && this.mayRaid(b, now, this.lastSeenAt)) {
				this.raidOrSend(b, this.lastSeen, now, "lost");
				return;
			}
		}
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
			if (st.impulseCooldown == 0 && !st.busy()) {
				// Lost them: the Impulse, if he has it.
				MatchManager.onImpulse(this.body);
				stat("impulse.lost");
			}
		}
		if (now < this.lookAroundUntil) {
			// Turning round, Sense up, listening.
			this.walker.halt();
			this.sense(st, true, false);
			this.glance(now);
			return;
		}
		if (this.lockersOpened == 0 && now >= this.raidCheckAt) {
			// Nothing out here: the house by where they were, if there is one.
			this.raidCheckAt = now + 20;
			Houses.Building b = Houses.near(this.level(), this.lastSeen, 8);
			if (b != null && this.mayRaid(b, now, this.lastSeenAt)) {
				this.raidOrSend(b, this.lastSeen, now, "near");
				return;
			}
		}
		// Nothing: the wardrobes round here, nearest first (a few).
		BlockPos locker = this.lockersOpened < 3 && !Events.quaking() ? this.nearestLocker(BlockPos.containing(this.lastSeen), 7, true) : null;
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
		this.own(c, now);
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

	// ---------------------------------------------------------------- signs that someone was there

	/**
	 * What the place tells him, as it would a player looking about: a door not as he last saw it, a drawer gone
	 * through since, the lights on again where they were off, fresh blood on the ground. Only what is in front of
	 * him, in plain view and near enough; what is right by him he just takes note of (most likely his own doing).
	 */
	private void observe(ShapeState st, int now) {
		if (st.blindTicks > 0) {
			return;
		}
		ServerLevel level = this.level();
		Vec3 eye = this.body.getEyePosition();
		Vec3 facing = this.body.getViewVector(1f);
		// Blood: the freshest drop in sight he hasn't followed yet.
		Blood.Drip drip = null;
		for (Blood.Drip d : Blood.drips()) {
			if (d.at() > this.lastDripAt && (drip == null || d.at() > drip.at()) && this.sees(eye, facing, d.pos().add(0, 0.1, 0), 20)) {
				drip = d;
			}
		}
		if (drip != null) {
			this.lastDripAt = drip.at();
			this.clue(drip.pos(), now, 4 * Math.max(0.3, 1 - (now - drip.at()) / 1200.0), "blood");
		}
		if (now % 20 != 0) {
			return;
		}
		if (Events.blackout() != this.blackoutWas) {
			this.blackoutWas = Events.blackout();
			this.props.keySet().removeIf(p -> LightSystem.isBreaker(level, p));
		}
		BlockPos me = this.body.blockPosition();
		int r = 18;
		for (BlockPos p : BlockPos.betweenClosed(me.offset(-r, -3, -r), me.offset(r, 5, r))) {
			net.minecraft.world.level.block.state.BlockState state = level.getBlockState(p);
			if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
				&& state.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
				this.notice(p.immutable(), state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN), Vec3.atCenterOf(p.above()), eye, facing, now, 2, "door",
					false, false);
			} else if (state.getBlock() instanceof TrapBlock) {
				// A trap on the ground (small: seen only near, less in the dark).
				Vec3 at = Vec3.atBottomCenterOf(p).add(0, 0.1, 0);
				if (eye.distanceTo(at) < 2.5 || this.sees(eye, facing, at, 9)) {
					if (this.traps.put(p.immutable(), now + 20 * 300) == null) {
						stat("trap.seen");
					}
				}
			} else if (state.is(net.minecraft.world.level.block.Blocks.LEVER) && LightSystem.isBreaker(level, p) && !p.equals(Events.partyHouse())) {
				// Lights on again where they were off: somebody wants to see (off is his own work, or the blackout's).
				this.notice(p.immutable(), state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED), Vec3.atCenterOf(p), eye, facing, now, 4,
					"lights", true, false);
			}
		}
		// Traps he finds gone (sprung by someone, or by his Runt).
		this.traps.keySet().removeIf(t -> t.distSqr(me) <= r * r && !(level.getBlockState(t).getBlock() instanceof TrapBlock)
			&& this.sees(eye, facing, Vec3.atBottomCenterOf(t).add(0, 0.1, 0), 9));
		// The patrol car's light bar, lit even in the dark.
		Vec3 car = Events.patrolPosition();
		if (car != null && eye.distanceTo(car) < 48 && Sight.canSee(level, eye, car.add(0, 1.6, 0), this.body)) {
			this.patrolAtTick = now;
		}
		java.util.Set<BlockPos> searched = new java.util.HashSet<>(Loot.searched());
		for (BlockPos c : Loot.containers()) {
			if (c.distSqr(me) <= r * r) {
				this.notice(c, searched.contains(c), Vec3.atCenterOf(c), eye, facing, now, 3, "drawer", true, true);
			}
		}
	}

	/**
	 * He sees this thing as {@code state} now: if he saw it otherwise before, someone has been at it (the sooner he
	 * saw it before, the fresher the sign). {@code onlyOn}: only a change to on (opened, searched, switched on) says
	 * anything. {@code startsOff}: everyone knows it starts off (a drawer not gone through), so finding it on the
	 * first time he sees it says a little.
	 */
	private void notice(BlockPos pos, boolean state, Vec3 at, Vec3 eye, Vec3 facing, int now, double weight, String kind, boolean onlyOn, boolean startsOff) {
		boolean near = eye.distanceTo(at) < 3;
		if (!near && !this.sees(eye, facing, at, 18)) {
			return;
		}
		Seen before = this.props.put(pos, new Seen(state, now));
		if (near) {
			return;
		}
		if (before == null) {
			if (startsOff && state) {
				// Gone through some time before he came by.
				this.clue(at, now, weight * 0.25, kind + ".old");
			}
			return;
		}
		if (before.state() == state || onlyOn && !state) {
			return;
		}
		this.clue(at, now, weight * Math.max(0.3, 1 - (now - before.at()) / 1200.0), kind);
	}

	/** Is this point in front of him, near enough (less far in the dark) and in plain view? */
	private boolean sees(Vec3 eye, Vec3 facing, Vec3 point, double range) {
		if (this.level().getBrightness(LightLayer.BLOCK, BlockPos.containing(point)) < 7) {
			range = Math.min(range, 10);
		}
		double d = eye.distanceTo(point);
		if (d > range || d > 2.5 && Sight.angleTo(eye, facing, point) > 75) {
			return false;
		}
		// Just short of it: the thing itself would stop the ray.
		Vec3 front = d < 0.7 ? point : point.add(eye.subtract(point).scale(0.6 / d));
		return Sight.canSee(this.level(), eye, front, this.body);
	}

	/** A sign noticed: kept a while (it adds to its house's), and something worth going to see when it says enough. */
	private void clue(Vec3 pos, int now, double weight, String kind) {
		Houses.Building b = Houses.near(this.level(), pos, 2);
		this.clues.add(new Clue(pos, now, weight, b == null ? NO_HOUSE : b.key));
		if (this.clues.size() > 60) {
			this.clues.removeFirst();
		}
		if (weight >= 1.5) {
			this.lead(pos, now, weight, kind);
		}
		stat("clue." + kind);
	}

	private static double fade(Clue c, int now) {
		return Math.max(0, 1 - (now - c.at()) / (double) CLUE_TICKS);
	}

	/** What the signs in (or by) this house add up to now. */
	private double heat(Houses.Building b, int now) {
		double h = 0;
		for (Clue c : this.clues) {
			if (c.house() == b.key) {
				h += c.weight() * fade(c, now);
			}
		}
		return h;
	}

	/** What the signs in this room add up to now. */
	private double roomHeat(Houses.Building b, Houses.Room room, int now) {
		double h = 0;
		for (Clue c : this.clues) {
			if (c.house() == b.key && b.nearestRoom(c.pos()) == room) {
				h += c.weight() * fade(c, now);
			}
		}
		return h;
	}

	/**
	 * The house the signs point to most (only those since he last went through it count), if they add up to enough:
	 * its strongest sign, or null.
	 */
	private Clue hottest(int now) {
		Map<Long, Double> sum = new HashMap<>();
		Map<Long, Clue> best = new HashMap<>();
		for (Clue c : this.clues) {
			Integer done = this.raided.get(c.house());
			if (c.house() == NO_HOUSE || done != null && c.at() <= done || c.pos().distanceTo(this.body.position()) > 80) {
				continue;
			}
			double w = c.weight() * fade(c, now);
			sum.merge(c.house(), w, Double::sum);
			Clue b = best.get(c.house());
			if (b == null || w > b.weight() * fade(b, now)) {
				best.put(c.house(), c);
			}
		}
		Clue pick = null;
		double top = 3;
		for (Map.Entry<Long, Double> e : sum.entrySet()) {
			if (e.getValue() >= top) {
				top = e.getValue();
				pick = best.get(e.getKey());
			}
		}
		return pick;
	}

	// ---------------------------------------------------------------- going through a house

	/** Not a house he has just been through, unless there is something newer to go by ({@code evidenceAt}). */
	private boolean mayRaid(Houses.Building b, int now, int evidenceAt) {
		Integer done = this.raided.get(b.key);
		return done == null || now - done > 20 * 90 || evidenceAt > done;
	}

	/**
	 * Through a house room by room: the room they were last in (or the one by where they were) first, then on from
	 * room to room, the nearest next, rooms the signs point to sooner. In each he stands where he sees it all, looks
	 * round with his Sense and throws open its wardrobes. The lights go off as he goes.
	 */
	private void startRaid(Houses.Building b, Vec3 hint, int now, String why) {
		this.raid = b;
		this.raidRooms.clear();
		List<Houses.Room> left = new ArrayList<>(b.rooms);
		Vec3 from = hint != null ? hint : this.body.position();
		Houses.Room first = b.nearestRoom(from);
		if (first != null) {
			this.raidRooms.add(first);
			left.remove(first);
			from = first.middle();
		}
		while (!left.isEmpty()) {
			Houses.Room next = null;
			double best = Double.MAX_VALUE;
			for (Houses.Room r : left) {
				double d = r.middle().distanceTo(from) + Math.abs(r.center.getY() - from.y) * 3 - 5 * this.roomHeat(b, r, now);
				if (d < best) {
					best = d;
					next = r;
				}
			}
			this.raidRooms.add(next);
			left.remove(next);
			from = next.middle();
		}
		this.raidRoom = 0;
		this.raidSpot = 0;
		this.roomLookAt = -1;
		this.raidLockers = 0;
		this.lockersOpened = 0;
		this.raidStarted = now;
		this.raidImpulse = false;
		this.raidShadowTried = false;
		this.raidLockersLeft = false;
		this.raidLockerPass = false;
		this.raidUntil = now + 20 * Math.min(100, 25 + 10 * this.raidRooms.size());
		this.raidFresh = now - this.lastSeenAt < 20 * 20 || this.heat(b, now) >= 3;
		this.mode = Mode.RAID;
		this.goal = null;
		this.walker.stop();
		this.repathAt = now;
		stat("raid." + why);
	}

	private void raid(ShapeState st, int now) {
		Houses.Building b = this.raid;
		if (b == null) {
			this.mode = Mode.PATROL;
			return;
		}
		if (this.raidRoom >= this.raidRooms.size() && !this.raidLockerPass && this.raidLockersLeft && this.raidFresh && !Events.quaking()
			&& now <= this.raidUntil) {
			// Nobody in the rooms, but good reason to think someone is here: the wardrobes, in his own form.
			this.raidLockerPass = true;
			this.raidRoom = 0;
			this.raidSpot = 0;
			this.roomLookAt = -1;
			this.raidUntil = Math.max(this.raidUntil, now + 20 * 25);
			stat("raid.lockers");
		}
		if (now > this.raidUntil || this.raidRoom >= this.raidRooms.size()) {
			this.endRaid(now, true);
			return;
		}
		if (!this.raidShadowTried) {
			// Through the house unseen, as a shadow (through its doors, through walls of light).
			this.raidShadowTried = true;
			if (st.shadowTicks == 0 && MatchManager.jumpBlockReason(this.body, st) == 0) {
				MatchManager.onShapeJump(this.body);
				stat("raid.shadow");
			}
		}
		if (!this.raidImpulse) {
			this.raidImpulse = true;
			if (st.impulseCooldown == 0 && !st.busy()) {
				// Whoever is in there shows as a blur: that room first.
				MatchManager.onImpulse(this.body);
				stat("impulse.raid");
			}
		}
		if (this.raidHeard(now) || this.suspect(st, now)) {
			return;
		}
		if (st.shadowTicks == 0 && now % 10 == 0) {
			this.lightsOff();
		}
		Houses.Room room = this.raidRooms.get(this.raidRoom);
		if (this.raidLockerPass) {
			// The second pass: only the wardrobes, room after room.
			for (BlockPos locker : room.lockers) {
				if (this.raidLockers >= 6) {
					break;
				}
				if (!this.done.containsKey(locker) && this.openLocker(st, locker, now)) {
					if (this.done.containsKey(locker)) {
						this.raidLockers++;
					}
					return;
				}
			}
			this.raidSpot = 0;
			this.roomLookAt = -1;
			this.raidRoom++;
			return;
		}
		BlockPos spot = room.spots.get(Math.min(this.raidSpot, room.spots.size() - 1));
		double d = this.body.position().distanceTo(Vec3.atBottomCenterOf(spot));
		boolean there = d < 1.6 || d < 3.5 && this.walker.idle() && b.roomAt(this.body.blockPosition()) == room;
		if (this.roomLookAt < 0 && !there) {
			this.sense(st, false, false);
			if (this.go(st, now, spot, 1.0)) {
				stat("raid.no_way");
				this.nextSpot(room);
			}
			return;
		}
		this.walker.halt();
		if (this.roomLookAt < 0) {
			this.roomLookAt = now;
			this.sweepYaw = this.body.getYRot();
			stat("raid.room");
		}
		int looked = now - this.roomLookAt;
		if (looked < (room.cells.size() < 20 ? 30 : 45)) {
			// All round the room, Sense up.
			this.sense(st, true, false);
			this.sweep(looked);
			return;
		}
		this.sense(st, false, false);
		if (st.shadowTicks > 0 && !room.lockers.isEmpty()) {
			// A shadow can't throw a wardrobe open: left for the end, if there is reason to.
			this.raidLockersLeft = true;
		} else if (this.raidSpot >= room.spots.size() - 1 && !Events.quaking()) {
			// Its wardrobes (not those he opened lately), a few in all.
			for (BlockPos locker : room.lockers) {
				if (this.raidLockers >= 6) {
					break;
				}
				if (this.done.containsKey(locker)) {
					continue;
				}
				if (this.openLocker(st, locker, now)) {
					if (this.done.containsKey(locker)) {
						this.raidLockers++;
					}
					return;
				}
			}
		}
		this.nextSpot(room);
	}

	/**
	 * A shadow indoors with someone in sight in the same house, not chasing yet: not out of the shadow in there (they
	 * would see him come out, and the light, the doorways, standing close may not let him): out of the house first,
	 * by a way out they can't see, and out of the shadow there. True while at it.
	 */
	private boolean slipOut(ShapeState st, int now, LivingEntity t) {
		if (st.shadowTicks == 0) {
			return false;
		}
		Houses.Building b = Houses.at(this.level(), this.body.blockPosition());
		if (b == null || !b.touches(t.blockPosition()) || b.outside.isEmpty()) {
			return false;
		}
		if (now >= this.slipRepath || this.walker.idle()) {
			this.slipRepath = now + 20;
			BlockPos best = null;
			double bestD = Double.MAX_VALUE;
			for (BlockPos o : b.outside) {
				double d = o.distSqr(this.body.blockPosition());
				if (Sight.canSee(this.level(), t.getEyePosition(), Vec3.atBottomCenterOf(o).add(0, 1, 0), t)) {
					// They would see him step out there.
					d += 400;
				}
				if (d < bestD) {
					bestD = d;
					best = o;
				}
			}
			if (best == null) {
				return false;
			}
			if (this.walker.idle()) {
				stat("shadow.slip_out");
			}
			this.follow(st, best, 1.0, true);
		}
		this.sense(st, false, false);
		this.walk(st, now);
		return true;
	}

	/** On to the next place to look from in this room, or to the next room. */
	private void nextSpot(Houses.Room room) {
		this.roomLookAt = -1;
		this.walker.stop();
		if (++this.raidSpot >= room.spots.size()) {
			this.raidSpot = 0;
			this.raidRoom++;
		}
	}

	/** Looking all round a room: three looks a third of a turn apart, head and body turning at a man's pace. */
	private void sweep(int looked) {
		this.turnTo(this.sweepYaw + 120f * Math.min(2, looked / 15), 12f);
		this.body.setXRot(8f);
	}

	private void turnTo(float yaw, float step) {
		float turn = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(yaw - this.body.getYRot()), -step, step);
		float y = this.body.getYRot() + turn;
		this.body.setYRot(y);
		this.body.setYHeadRot(y);
		this.body.setYBodyRot(y);
	}

	/**
	 * Something heard while he goes through the house. In it: that room comes next. Outside it, at a way out, or a
	 * window: they are slipping out, and he goes round after them. True when he leaves the house for it.
	 */
	private boolean raidHeard(int now) {
		Lead heard = null;
		for (Lead l : this.leads) {
			// Only what is in or by this house (something far off waits till he is done).
			if (l.at() > this.raidStarted && (l.weight() >= 1.5 || l.kind().equals("step")) && !l.kind().equals("locker")
				&& l.pos().distanceTo(this.raid.center) < 24 && (heard == null || l.at() > heard.at())) {
				heard = l;
			}
		}
		if (heard == null) {
			return false;
		}
		this.leads.remove(heard);
		Houses.Building b = this.raid;
		BlockPos at = BlockPos.containing(heard.pos());
		if (!b.touches(at) || heard.kind().equals("glass") || byWayOut(b, heard.pos())) {
			BlockPos to = this.outsideOf(b, heard.pos());
			this.endRaid(now, false);
			this.mode = Mode.LISTEN;
			this.goal = to;
			this.goalUntil = now + 20 * 15;
			this.lastSeen = Vec3.atBottomCenterOf(to);
			this.lastVelocity = Vec3.ZERO;
			this.leadScore = 10;
			this.repathAt = now;
			stat("raid.flush");
			return true;
		}
		Houses.Room r = b.nearestRoom(heard.pos());
		int i = r == null ? -1 : this.raidRooms.indexOf(r);
		if (r != null && i != this.raidRoom) {
			if (i >= 0) {
				this.raidRooms.remove(i);
				if (i < this.raidRoom) {
					this.raidRoom--;
				}
			}
			this.raidRooms.add(this.raidRoom, r);
			this.raidSpot = 0;
			this.roomLookAt = -1;
			this.walker.stop();
			this.raidUntil = Math.max(this.raidUntil, now + 20 * 20);
			stat("raid.heard");
		}
		return false;
	}

	private static boolean byWayOut(Houses.Building b, Vec3 p) {
		for (BlockPos w : b.ways) {
			if (Vec3.atBottomCenterOf(w).distanceTo(p) < 2.5) {
				return true;
			}
		}
		for (BlockPos g : b.windows) {
			if (Vec3.atCenterOf(g).distanceTo(p) < 2) {
				return true;
			}
		}
		return false;
	}

	/** Ground outside the house by this point: the nearest way out's, or a few steps out from the wall. */
	private BlockPos outsideOf(Houses.Building b, Vec3 p) {
		BlockPos best = null;
		for (BlockPos o : b.outside) {
			if (best == null || Vec3.atBottomCenterOf(o).distanceTo(p) < Vec3.atBottomCenterOf(best).distanceTo(p)) {
				best = o;
			}
		}
		if (best != null && Vec3.atBottomCenterOf(best).distanceTo(p) < 4) {
			return best;
		}
		Vec3 out = p.subtract(b.center).multiply(1, 0, 1);
		if (out.lengthSqr() > 1e-4) {
			out = out.normalize();
			for (double k : new double[]{2.5, 3.5, 5}) {
				BlockPos c = Nav.snap(this.level(), BlockPos.containing(p.add(out.scale(k))));
				if (Nav.standable(this.level(), c) && !b.contains(c)) {
					return c;
				}
			}
		}
		return best != null ? best : BlockPos.containing(p);
	}

	/**
	 * Done with the house. {@code watch}: gone through it all and found nobody; if he went in on good grounds, he
	 * leaves as if for good and keeps an eye on its ways out from the dark a while (whoever hid well comes out).
	 */
	private void endRaid(int now, boolean watch) {
		Houses.Building b = this.raid;
		this.raid = null;
		this.lockersOpened = 0;
		this.roomLookAt = -1;
		if (b == null) {
			return;
		}
		this.raided.put(b.key, now);
		stat(watch ? "raid.done" : "raid.left");
		this.target = null;
		this.goal = null;
		this.mode = Mode.PATROL;
		this.walker.stop();
		if (watch && this.raidFresh) {
			BlockPos spot = this.watchSpot(b);
			if (spot != null) {
				this.camping = spot;
				this.campFor = null;
				this.campUntil = now + 20 * 20;
				this.campWithin = 1.5;
				this.campFace = b.center;
				stat("raid.watch");
			}
		}
	}

	/** Where to watch a house from: dark ground out from it with as many of its ways out in view as can be. */
	private BlockPos watchSpot(Houses.Building b) {
		ServerLevel level = this.level();
		List<Vec3> exits = new ArrayList<>();
		for (BlockPos o : b.outside) {
			Vec3 v = Vec3.atBottomCenterOf(o);
			if (exits.stream().noneMatch(e -> e.distanceTo(v) < 3)) {
				exits.add(v);
			}
			if (exits.size() >= 8) {
				break;
			}
		}
		BlockPos best = null;
		double bestScore = -10;
		for (Vec3 e : exits) {
			Vec3 dir = e.subtract(b.center).multiply(1, 0, 1);
			if (dir.lengthSqr() < 1e-4) {
				continue;
			}
			dir = dir.normalize();
			for (double k : new double[]{6, 9}) {
				BlockPos c = Nav.snap(level, BlockPos.containing(e.add(dir.scale(k))));
				if (!Nav.standable(level, c) || b.contains(c) || this.unreachable.containsKey(c)) {
					continue;
				}
				Vec3 eye = Vec3.atBottomCenterOf(c).add(0, 1.6, 0);
				double score = this.lit(c) ? -1.5 : 0;
				for (Vec3 x : exits) {
					if (Sight.canSee(level, eye, x.add(0, 1, 0), this.body)) {
						score += 1;
					}
				}
				if (score > bestScore) {
					bestScore = score;
					best = c;
				}
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- his Runt

	/** His screen still shows the Runt (the HUD and the order wheel drop it after two seconds). */
	private boolean runtFresh(int now) {
		return now - this.runtInfoAt <= 40;
	}

	/** Whom his Runt holds (his screen marks them wherever they are), or null. */
	private LivingEntity runtHeld(int now) {
		if (!this.runtFresh(now) || this.runtHolding < 0) {
			return null;
		}
		Entity e = this.level().getEntity(this.runtHolding);
		if (!(e instanceof LivingEntity l) || gone(l)) {
			return null;
		}
		return l;
	}

	/**
	 * An order, as the wheel gives it: only to a bot Runt on his screen, a second at least between orders. The callers
	 * keep to what the wheel needs (whom he points at in view within 60 blocks, a spot in sight within 80).
	 */
	private boolean orderRunt(int kind, LivingEntity at, BlockPos pos, int now) {
		if (!this.runtFresh(now) || !this.runtBot || now - this.runtOrderAt < 20) {
			return false;
		}
		this.runtOrderAt = now;
		Runt.order(this.body, kind, at == null ? -1 : at.getId(), pos == null ? BlockPos.ZERO : pos);
		stat("runt.order." + kind);
		return true;
	}

	/** Someone getting away from him: the Runt (on its feet, not at someone already, near enough to them) sent at them. */
	private void sendRunt(LivingEntity t, int now) {
		if (!this.runtFresh(now) || !this.runtBot || this.runtStatus == 2 || this.runtStatus >= 5
			|| t.getId() == this.runtAttackId && now - this.runtAttackAt < 20 * 10) {
			return;
		}
		Entity runt = this.level().getEntity(this.runtId);
		if (runt != null && runt.distanceTo(t) < 40 && this.orderRunt(HwNet.RuntOrder.ATTACK, t, t.blockPosition(), now)) {
			this.runtAttackId = t.getId();
			this.runtAttackAt = now;
		}
	}

	/** His Runt holds someone he can't see from here (his screen marks them): straight there, nothing else matters. */
	private void rushToRunt(int now) {
		LivingEntity held = this.runtHeld(now);
		if (held == null || this.mode == Mode.CHASE || this.mode == Mode.STALK
			|| this.mode == Mode.LISTEN && this.goal != null && Vec3.atBottomCenterOf(this.goal).distanceTo(held.position()) < 4) {
			return;
		}
		this.raid = null;
		this.camping = null;
		this.workAt = null;
		this.mode = Mode.LISTEN;
		this.goal = held.blockPosition();
		this.goalUntil = now + 20 * 20;
		this.lastSeen = held.position();
		this.lastVelocity = Vec3.ZERO;
		this.leadScore = 100;
		this.repathAt = now;
		stat("runt.rush");
	}

	/**
	 * A house to go through: his bot Runt sent in while he watches its ways out from the dark (the first time for a
	 * house), whoever bolts runs into him; else in himself.
	 */
	private void raidOrSend(Houses.Building b, Vec3 hint, int now, String why) {
		Entity runt = this.runtFresh(now) ? this.level().getEntity(this.runtId) : null;
		if (runt != null && this.runtBot && !this.runtSentTo.contains(b.key) && (this.runtStatus == 0 || this.runtStatus == 4)
			&& !b.ways.isEmpty() && runt.position().distanceTo(b.center) < 70) {
			BlockPos spot = this.watchSpot(b);
			if (spot != null) {
				this.runtSent = b;
				this.runtSentTo.add(b.key);
				this.runtSearchPending = true;
				this.raid = null;
				this.goal = null;
				this.camping = spot;
				this.campFor = null;
				this.campUntil = now + 20 * 30;
				this.campWithin = 1.5;
				this.campFace = b.center;
				this.mode = Mode.PATROL;
				this.walker.stop();
				stat("runt.send." + why);
				return;
			}
		}
		this.startRaid(b, hint, now, why);
	}

	/** From where he watches: the Runt pointed in through a way in he can see (the cell inside if it is open, else the step). */
	private void pointRuntIn(int now) {
		Houses.Building b = this.runtSent;
		Vec3 eye = this.body.getEyePosition();
		for (int i = 0; i < b.ways.size(); i++) {
			BlockPos in = b.ways.get(i);
			BlockPos out = b.outside.get(i);
			BlockPos spot = Sight.canSee(this.level(), eye, Vec3.atCenterOf(in), this.body) ? in
				: Sight.canSee(this.level(), eye, Vec3.atCenterOf(out), this.body) ? out : null;
			if (spot != null && eye.distanceTo(Vec3.atCenterOf(spot)) <= 80) {
				this.walker.face(Vec3.atCenterOf(spot));
				if (this.orderRunt(HwNet.RuntOrder.SEARCH, null, spot, now)) {
					this.runtSearchPending = false;
				}
				return;
			}
		}
	}

	// ---------------------------------------------------------------- what can hurt him

	/** Caught in a trap: a blow at whomever he is after if they are in reach. True if he struck. */
	private boolean strikeNear(ShapeState st, int now) {
		LivingEntity t = this.target;
		if (t == null || st.attackCooldown > 0 || !MatchManager.exposed(t) || now - this.lastSeenAt > 2
			|| this.body.distanceTo(t) > HwConfig.get().attackReach) {
			return false;
		}
		Vec3 aim = FocusPoints.bodyCenter(t);
		this.walker.face(aim);
		MatchManager.onAim(this.body, this.body.getEyePosition(), aim.subtract(this.body.getEyePosition()).normalize());
		MatchManager.onShapeAttack(this.body, t.getId(), false);
		stat("strike.trapped");
		return true;
	}

	/**
	 * A gun on him that would floor him: out of its line. Into the shadow if he may (nothing hits a shadow); else to the
	 * nearest cover from them, a few steps, towards whom he is after. True while at it.
	 */
	private boolean evade(ShapeState st, int now) {
		LivingEntity s = this.shooter;
		if (s == null || gone(s) || st.shadowTicks > 0) {
			this.evadeUntil = 0;
			return false;
		}
		if (MatchManager.jumpBlockReason(this.body, st) == 0) {
			MatchManager.onShapeJump(this.body);
			stat("evade.shadow");
			this.evadeUntil = 0;
			return false;
		}
		ServerLevel level = this.level();
		Vec3 eye = s.getEyePosition();
		if (!Guns.lineClear(level, s, eye, FocusPoints.bodyCenter(this.body), this.body)) {
			// Out of the line already (a wall, someone in between).
			this.evadeUntil = 0;
			return false;
		}
		if (this.walker.idle() || now >= this.evadeRepath) {
			this.evadeRepath = now + 10;
			Vec3 toward = this.target != null ? this.target.position() : this.body.position();
			List<Nav.Node> path = Nav.search(level, this.body.blockPosition(), 9, Nav.Options.walker().lockedDoors(3).nodes(400), (pos, cost) -> {
				Vec3 c = Vec3.atBottomCenterOf(pos).add(0, 1.1, 0);
				return (Sight.canSee(level, eye, c, s) ? 0 : 100) - cost * 2 - c.distanceTo(toward) * 0.3;
			});
			stat("search");
			if (path.isEmpty()) {
				this.evadeUntil = 0;
				return false;
			}
			this.walker.follow(path);
		}
		this.sense(st, false, false);
		this.walk(st, now);
		return true;
	}

	/**
	 * A firecracker by him about to go off: a shadow if he may (it can't touch one); else away from it, six blocks or
	 * out of its sight, his face turned from it (the flash blinds only those looking). True while at it.
	 */
	private boolean dodge(ShapeState st, int now) {
		Vec3 c = this.cracker;
		if (c == null || st.shadowTicks > 0) {
			this.crackerUntil = 0;
			return false;
		}
		ServerLevel level = this.level();
		Vec3 eye = this.body.getEyePosition();
		Vec3 bang = c.add(0, 0.3, 0);
		boolean safe = eye.distanceTo(bang) > 6.5 || !Sight.canSee(level, bang, eye, this.body);
		if (!safe && MatchManager.jumpBlockReason(this.body, st) == 0) {
			MatchManager.onShapeJump(this.body);
			stat("cracker.shadow");
			this.crackerUntil = 0;
			return false;
		}
		this.sense(st, false, false);
		if (!safe) {
			if (this.walker.idle() || now >= this.evadeRepath) {
				this.evadeRepath = now + 10;
				List<Nav.Node> path = Nav.search(level, this.body.blockPosition(), 10, Nav.Options.walker().lockedDoors(3).nodes(500), (pos, cost) -> {
					Vec3 p = Vec3.atBottomCenterOf(pos).add(0, 1.6, 0);
					double d = p.distanceTo(bang);
					return (d > 6.5 || !Sight.canSee(level, bang, p, this.body) ? 100 : d * 5) - cost;
				});
				stat("cracker.dodge");
				this.walker.follow(path);
			}
			this.walk(st, now);
			return true;
		}
		this.walker.halt();
		Vec3 away = eye.subtract(bang);
		this.turnTo((float) (net.minecraft.util.Mth.atan2(away.z, away.x) * net.minecraft.util.Mth.RAD_TO_DEG) - 90f, 30f);
		return true;
	}

	/**
	 * The patrol car about (heard or seen lately) with him in the cone of its headlights: a shadow if he may (it can't
	 * see one); else out of its way, sideways off the road. True while at it.
	 */
	private boolean avoidPatrol(ShapeState st, int now) {
		Vec3 car = Events.patrolPosition();
		Vec3 heading = Events.patrolHeading();
		if (car == null || heading == null || now - this.patrolAtTick > 60 || st.shadowTicks > 0 || !this.inPatrolCone(car, heading)) {
			return false;
		}
		if (MatchManager.jumpBlockReason(this.body, st) == 0) {
			MatchManager.onShapeJump(this.body);
			stat("patrol.shadow");
			return false;
		}
		if (this.walker.idle() || now >= this.evadeRepath) {
			this.evadeRepath = now + 10;
			Vec3 side = new Vec3(-heading.z, 0, heading.x);
			Vec3 me = this.body.position();
			if (me.subtract(car).dot(side) < 0) {
				side = side.scale(-1);
			}
			this.walker.stop();
			for (double k : new double[]{5, 3, 7}) {
				BlockPos to = Nav.snap(this.level(), BlockPos.containing(me.add(side.scale(k))));
				if (Nav.standable(this.level(), to)) {
					this.walker.follow(Nav.to(this.level(), this.body.blockPosition(), to, 1, Nav.Options.walker().lockedDoors(3).nodes(300)));
					break;
				}
			}
			stat("patrol.step_aside");
			if (this.walker.idle()) {
				return false;
			}
		}
		this.walk(st, now);
		return true;
	}

	/** In front of the car within the reach of its headlights (a little wider than they see, to be safe). */
	private boolean inPatrolCone(Vec3 car, Vec3 heading) {
		HwConfig cfg = HwConfig.get();
		Vec3 front = car.add(heading.scale(2.5)).add(0, 0.8, 0);
		Vec3 me = FocusPoints.bodyCenter(this.body);
		return me.distanceTo(front) <= cfg.patrolSightRange + 3 && Sight.angleTo(front, heading, me) <= cfg.patrolSightAngle + 10;
	}

	/** What a step into this cell costs him in his own form: a trap he saw (beside one too), where he heard one set, flames. */
	private double hazardCost(BlockPos p) {
		double c = 0;
		if (this.traps.containsKey(p)) {
			c += 40;
		} else {
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				if (this.traps.containsKey(p.relative(d))) {
					// A trap springs on whoever passes close by it, corners cut.
					c += 4;
					break;
				}
			}
		}
		for (BlockPos h : this.trapsHeard.keySet()) {
			if (h.distManhattan(p) <= 1) {
				c += 6;
				break;
			}
		}
		if (this.fireNear) {
			ServerLevel level = this.level();
			if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
				c += 60;
			} else if (level.getBlockState(p.above()).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
				c += 30;
			}
		}
		return c;
	}

	// ---------------------------------------------------------------- the ways out

	/**
	 * How near a way out is to taking someone, as his screen shows it: 0 nothing done yet, up to 1 open (a cellar
	 * open, the car running, the police car waiting, the gate open); -1 used up or gone.
	 */
	private static double exitProgress(HwNet.ExitInfo e) {
		HwConfig cfg = HwConfig.get();
		double p = switch (e.type()) {
			case Exits.CELLAR -> e.state() == Exits.OPEN ? 1 : e.state() == Exits.USED ? -1 : 0;
			case Exits.CAR -> (e.state() & Exits.CAR_GONE) != 0 ? -1 : (e.state() & Exits.CAR_RUNNING) != 0 ? 1
				: Integer.bitCount(e.state() & (Exits.CAR_FUEL | Exits.CAR_REPAIRED)) / 3.0;
			case Exits.POLICE -> switch (e.state()) {
				case Exits.POLICE_HERE -> 1;
				case Exits.POLICE_COMING -> 0.5 + 0.4 * (1 - Math.min(1, e.extra() / (cfg.policeArriveSeconds * 20.0)));
				case Exits.POLICE_IDLE -> 0.25;
				case Exits.POLICE_NEED_BODY -> 0;
				default -> -1;
			};
			default -> e.state() == Exits.OPEN ? 1 : e.state() == Exits.USED ? -1 : e.extra() != 0 ? 0.5 : 0;
		};
		if (e.type() == Exits.GATE && p >= 0 && cfg.gateOpenLastSeconds > 0 && Match.ticksLeft() > cfg.gateOpenLastSeconds * 20) {
			// The gate won't open before the last minutes.
			p = Math.min(p, 0.25);
		}
		return p;
	}

	/**
	 * Running off at full tilt for a way out that is open or nearly so, ahead of them along that line: he makes for it
	 * (a shadow outruns anyone) rather than for their heels. Null if there is none, or he wouldn't be there first.
	 */
	private BlockPos cutOff(LivingEntity t) {
		Vec3 v = this.lastVelocity.multiply(1, 0, 1);
		if (v.lengthSqr() < 0.04) {
			return null;
		}
		Vec3 dir = v.normalize();
		Vec3 from = t.position();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (HwNet.ExitInfo e : Exits.infos()) {
			if (exitProgress(e) < 0.5) {
				continue;
			}
			Vec3 x = Vec3.atBottomCenterOf(e.pos());
			Vec3 to = x.subtract(from).multiply(1, 0, 1);
			double dist = to.length();
			if (dist < 6 || dist > 60) {
				continue;
			}
			double angle = Math.toDegrees(Math.acos(net.minecraft.util.Mth.clamp(to.scale(1 / dist).dot(dir), -1, 1)));
			if (angle > 30 || this.body.position().distanceTo(x) > dist * 1.4) {
				continue;
			}
			double score = angle + dist * 0.2;
			if (score < bestScore) {
				bestScore = score;
				best = e.pos();
			}
		}
		if (best != null && !best.equals(this.lastCut)) {
			stat("cutoff");
		}
		this.lastCut = best;
		return best;
	}

	/** The phone (a map point) within {@code r} of this point, or null. */
	private BlockPos phoneNear(Vec3 at, double r) {
		BlockPos best = null;
		for (BlockPos phone : MapPoints.get(this.level()).list(MapPoints.Type.PHONE)) {
			double d = Vec3.atCenterOf(phone).distanceTo(at);
			if (d <= r && (best == null || d < Vec3.atCenterOf(best).distanceTo(at))) {
				best = phone;
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
				this.travel(st, now, testGoal, 1.2, true);
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
			// Time enough to get there, however far (a far one from the Impulse too).
			this.goalUntil = now + Math.max(20 * 25, (int) (best.pos.distanceTo(this.body.position()) / 3.5 * 20));
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
		// Nobody seen for a long while (they keep to the far end of town): the Impulse before any job.
		if (now - this.lastSeenAt > 20 * 45 && st.impulseCooldown == 0 && !st.busy() && now >= this.impulseAt) {
			this.impulseAt = now + 40;
			MatchManager.onImpulse(this.body);
			stat("impulse.long");
			return;
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
		if (watch != null && !watch.equals(this.campFor)) {
			// Not standing by it in plain sight: waiting near it in the dark, against something, the way out in view.
			BlockPos hide = this.ambushSpot(watch);
			this.campFor = watch;
			this.camping = hide != null ? hide : watch;
			this.campUntil = now + 20 * 30;
			this.campWithin = hide != null ? 1.5 : 7;
			this.campFace = hide != null ? Vec3.atCenterOf(watch) : null;
			stat(hide != null ? "camp.ambush" : "camp");
		}
		if (this.camping != null && now < this.campUntil) {
			this.mode = Mode.CAMP;
			if (this.body.position().distanceTo(Vec3.atBottomCenterOf(this.camping)) > this.campWithin) {
				this.travel(st, now, this.camping, Math.min(5, this.campWithin), true);
			} else {
				this.walker.halt();
				this.sense(st, now % 120 < 60, false);
				if (this.campFace != null) {
					// Eyes on the house, now a little this way, now that.
					Vec3 d = this.campFace.subtract(this.body.position());
					float yaw = (float) (net.minecraft.util.Mth.atan2(d.z, d.x) * net.minecraft.util.Mth.RAD_TO_DEG) - 90f;
					this.turnTo(yaw + (float) Math.sin(now / 25.0) * 35f, 6f);
					this.body.setXRot(5f);
				} else {
					this.glance(now);
				}
				if (this.runtSent != null) {
					if (this.runtSearchPending) {
						this.pointRuntIn(now);
					} else if (this.runtFresh(now) && (this.runtStatus == 1 || this.runtStatus == 2)) {
						// It is still at it in there: keep watching.
						this.campUntil = Math.max(this.campUntil, now + 40);
					}
				}
			}
			return;
		}
		if (this.camping != null) {
			// Watched long enough: not that way out again for two minutes (else he stands there all match).
			this.done.put(this.campFor != null ? this.campFor : this.camping, now + 20 * 120);
		}
		this.camping = null;
		this.campFor = null;
		this.campWithin = 7;
		this.campFace = null;
		if (this.runtSent != null) {
			// The Runt found nobody (or nobody bolted): in himself.
			Houses.Building b = this.runtSent;
			this.runtSent = null;
			this.runtSearchPending = false;
			if (this.mayRaid(b, now, this.lastSeenAt)) {
				this.startRaid(b, b.center, now, "after_runt");
				return;
			}
		}
		// Signs that someone is in a house (a door moved, drawers gone through, the lights on again, blood): through it.
		if (now % 20 == 0) {
			Clue hot = this.hottest(now);
			Houses.Building b = hot == null ? null : Houses.near(this.level(), hot.pos(), 2);
			if (b != null) {
				this.raidOrSend(b, hot.pos(), now, "clues");
				return;
			}
		}
		// Nothing to go by: the Impulse, when he has it (whoever it shows becomes something to go and see).
		if (st.impulseCooldown == 0 && !st.busy() && now >= this.impulseAt) {
			this.impulseAt = now + 40;
			MatchManager.onImpulse(this.body);
			stat("impulse");
			return;
		}
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

	/** Getting somewhere with nobody about: far, as a shadow through the dark; a shadow still when he gets there. */
	private void travel(ShapeState st, int now, BlockPos to, double within) {
		this.travel(st, now, to, within, false);
	}

	/** As above; {@code out}: in his own form when he gets there (to keep watch, the tests' places). */
	private void travel(ShapeState st, int now, BlockPos to, double within, boolean out) {
		if (this.go(st, now, to, within, out)) {
			this.done.put(to, now + 20 * 60);
			this.goal = null;
			this.mode = Mode.PATROL;
		}
	}

	/** As travel (a shadow still when he gets there); true when he finds no way there (the caller decides what then). */
	private boolean go(ShapeState st, int now, BlockPos to, double within) {
		return this.go(st, now, to, within, false);
	}

	private boolean go(ShapeState st, int now, BlockPos to, double within, boolean out) {
		double d = this.body.position().distanceTo(Vec3.atBottomCenterOf(to));
		if (st.shadowTicks == 0 && d > 25 && st.jumpCooldown == 0 && MatchManager.jumpBlockReason(this.body, st) == 0) {
			MatchManager.onShapeJump(this.body);
			this.repathAt = now;
			stat("shadow.in_travel");
		} else if (out && st.shadowTicks > 0 && d < 8) {
			this.wantOut(st, now);
		}
		if (now >= this.repathAt || this.walker.idle()) {
			this.repathAt = now + 40;
			if (this.follow(st, to, within, true) && this.walker.idle() && d > within + 1) {
				return true;
			}
		}
		this.walk(st, now);
		return false;
	}

	/** A place worth looking in on, and how much. */
	private record Spot(BlockPos pos, double weight) {
	}

	/**
	 * Where they have to come: the ways out (the further along, the more), the phones, the drawers not yet gone
	 * through, the event spots everyone's screen marks (the transformer, the bag, the burning house), the party's
	 * crowd. Nearer and weightier first, but not always the very best: he covers the town.
	 */
	private BlockPos patrolGoal() {
		ServerLevel level = this.level();
		List<Spot> spots = new ArrayList<>();
		for (HwNet.ExitInfo e : Exits.infos()) {
			double p = exitProgress(e);
			if (p >= 0) {
				spots.add(new Spot(e.pos(), 1 + 4 * p));
			}
		}
		boolean calls = Exits.policePoint() != null && Police.wanted() < HwConfig.get().policeMaxCalls;
		for (BlockPos phone : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
			if (Exits.phoneWorks(phone)) {
				spots.add(new Spot(phone, calls ? 1.2 : 0.4));
			}
		}
		java.util.Set<BlockPos> searched = Loot.searched();
		for (BlockPos c : Loot.containers()) {
			if (!searched.contains(c)) {
				spots.add(new Spot(c, 0.5));
			}
		}
		for (BlockPos ev : new BlockPos[]{Events.transformer(), Events.bagSpot(), Events.fireHouse()}) {
			if (ev != null) {
				spots.add(new Spot(ev, 3));
			}
		}
		if (Events.partyHouse() != null) {
			spots.add(new Spot(Events.partyHouse(), 2));
		}
		BlockPos me = this.body.blockPosition();
		spots.removeIf(p -> this.done.containsKey(p.pos()) || this.unreachable.containsKey(p.pos()) || p.pos().distSqr(me) < 36);
		if (spots.isEmpty()) {
			BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
			return center == null ? null : center.offset(this.random.nextInt(41) - 20, 0, this.random.nextInt(41) - 20);
		}
		spots.sort((a, b) -> Double.compare(b.weight() * 30 / (15 + Math.sqrt(b.pos().distSqr(me))), a.weight() * 30 / (15 + Math.sqrt(a.pos().distSqr(me)))));
		return spots.get(this.random.nextInt(Math.min(5, spots.size()))).pos();
	}

	/** Lights off where he goes: darkness is his. */
	private void lightsOff() {
		for (BlockPos breaker : LightSystem.breakers(this.level(), this.body.blockPosition(), 3)) {
			if (LightSystem.isOn(this.level(), breaker) && Sight.canSee(this.level(), this.body.getEyePosition(), Vec3.atCenterOf(breaker), this.body)) {
				this.walker.face(Vec3.atCenterOf(breaker));
				this.own(Vec3.atCenterOf(breaker), this.level().getServer().getTickCount());
				LightSystem.flip(this.level(), breaker, false);
				stat("power.off");
				return;
			}
		}
	}

	/**
	 * Something to undo: an opened cellar (anywhere: his screen shows it open), fuel in the car (further off once it
	 * is mended too), a phone ringing (someone calling the police, Loomis), a working phone near him.
	 */
	private BlockPos job(int now) {
		Vec3 me = this.body.position();
		for (HwNet.ExitInfo e : Exits.infos()) {
			if (this.done.containsKey(e.pos()) || this.unreachable.containsKey(e.pos())) {
				continue;
			}
			double d = Vec3.atCenterOf(e.pos()).distanceTo(me);
			if (e.type() == Exits.CAR && (e.state() & Exits.CAR_FUEL) != 0 && (e.state() & (Exits.CAR_GONE | Exits.CAR_RUNNING)) == 0
				&& d <= ((e.state() & Exits.CAR_REPAIRED) != 0 ? 70 : 30)) {
				return e.pos();
			}
			if (e.type() == Exits.CELLAR && e.state() == Exits.OPEN && d <= 150) {
				return e.pos();
			}
		}
		if (this.ringing != null && now < this.ringingUntil && Exits.phoneWorks(this.ringing) && Police.wanted() < HwConfig.get().policeMaxCalls
			&& !this.done.containsKey(this.ringing) && !this.unreachable.containsKey(this.ringing) && Vec3.atCenterOf(this.ringing).distanceTo(me) < 70) {
			return this.ringing;
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

	/**
	 * A way out someone is getting going (the furthest along): the gate open or its fuse in, the car running or
	 * mended, the police car waiting or half a minute off. Else where the survivors have to come during an event (the
	 * transformer in a blackout, the forgotten bag).
	 */
	private BlockPos exitInTrouble() {
		BlockPos best = null;
		double top = 0;
		for (HwNet.ExitInfo e : Exits.infos()) {
			double p = switch (e.type()) {
				case Exits.GATE -> e.state() == Exits.OPEN ? 1 : e.state() != Exits.USED && e.extra() != 0 ? 0.6 : 0;
				case Exits.CAR -> (e.state() & Exits.CAR_GONE) != 0 ? 0 : (e.state() & Exits.CAR_RUNNING) != 0 ? 1 : (e.state() & Exits.CAR_REPAIRED) != 0 ? 0.6 : 0;
				case Exits.POLICE -> e.state() == Exits.POLICE_HERE ? 1 : e.state() == Exits.POLICE_COMING && e.extra() < 20 * 30 ? 0.7 : 0;
				default -> 0;
			};
			if (p > top && !this.done.containsKey(e.pos())) {
				top = p;
				best = e.pos();
			}
		}
		if (best == null) {
			for (BlockPos ev : new BlockPos[]{Events.transformer(), Events.bagSpot()}) {
				if (ev != null && !this.done.containsKey(ev)) {
					return ev;
				}
			}
		}
		return best;
	}
}
