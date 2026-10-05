package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * How a bot civilian plays: like a careful person. It searches containers, carries items to the exits
 * and works them, and uses an exit as soon as one is open. It only knows about the Shape from what it
 * sees (with a human-like reaction delay; in the dark and in Shape Jump he is hard or impossible to
 * spot), what it hears right next to it and what townsfolk scream about. Spotting him it runs, through
 * doors it then locks and through windows, then hides in a dark corner for a while. With a melee weapon
 * it comes to help someone the Shape is holding; darts it throws from a distance.
 */
final class BotBrain {
	private enum Mode {
		ROAM, FLEE, HIDE, FIGHT
	}

	/** Something to walk to and hold use on (or just walk to, when {@code hold} is false). */
	private record Task(BlockPos target, double within, boolean hold, String key) {
	}

	private final BotPlayer bot;
	private final Walker walker;
	private final RandomSource random;
	private Mode mode = Mode.ROAM;

	private Vec3 threat;
	private int threatTick = -100000;
	private int seeing;
	private int reaction;

	private Task task;
	private int replanAt;
	private BlockPos holdPos;
	private int holdStart;
	private boolean holdBegan;
	private int quietUntil;
	private final Map<BlockPos, Integer> failed = new HashMap<>();

	private int fleeRepathAt;
	private int calmSince;
	private BlockPos lockDoor;
	private int lockUntil;
	private int hideUntil;
	private boolean hidePathSet;
	private int fightUntil;
	private int nextHit;
	private int nextDart;
	private int pathAt;

	BotBrain(BotPlayer bot) {
		this.bot = bot;
		this.walker = new Walker(bot);
		this.random = bot.getRandom();
		this.reaction = this.rollReaction();
	}

	private HwConfig cfg() {
		return HwConfig.get();
	}

	private ServerLevel level() {
		return this.bot.level();
	}

	private int rollReaction() {
		HwConfig cfg = this.cfg();
		return cfg.botReactionMinTicks + this.random.nextInt(Math.max(1, cfg.botReactionMaxTicks - cfg.botReactionMinTicks + 1));
	}

	BlockPos claimed() {
		return this.task == null ? null : this.task.target;
	}

	boolean isTask(String key) {
		return this.task != null && this.task.key.equals(key);
	}

	/** A townsperson screamed or something loud happened at {@code pos}. */
	void hear(Vec3 pos, int now) {
		if (this.bot.position().distanceTo(pos) <= this.cfg().botHearAlertRange) {
			this.threat = pos;
			this.threatTick = now;
		}
	}

	private boolean aware(int now) {
		return this.threat != null && now - this.threatTick <= this.cfg().botMemoryTicks;
	}

	// ---------------------------------------------------------------- tick

	void tick(int now) {
		if (this.bot.isSpectator() || !Match.running()) {
			this.walker.halt();
			this.bot.setSprinting(false);
			return;
		}
		if (MatchManager.isGrabbed(this.bot) || Windows.isVaulting(this.bot)) {
			this.walker.halt();
			return;
		}
		this.perceive(now);
		HwConfig cfg = this.cfg();
		ServerPlayer shape = Senses.shape(this.level());
		ShapeState shapeState = shape == null ? null : MatchManager.shapeState(shape);
		boolean danger = this.aware(now) && this.bot.position().distanceTo(this.threat) < cfg.botFleeDistance;

		if (danger && this.mode != Mode.FLEE && this.mode != Mode.FIGHT) {
			if (shape != null && shapeState != null && shapeState.grab != null && shapeState.grab.victim != this.bot
				&& this.meleeSlot() >= 0 && this.bot.distanceTo(shape) < 16) {
				this.enter(Mode.FIGHT, now);
				this.fightUntil = now + 240;
			} else {
				this.enter(Mode.FLEE, now);
			}
		}
		this.tryDart(now, shape, shapeState);

		switch (this.mode) {
			case FIGHT -> this.tickFight(now, shape, shapeState);
			case FLEE -> this.tickFlee(now);
			case HIDE -> this.tickHide(now);
			case ROAM -> this.tickRoam(now);
		}
	}

	private void enter(Mode mode, int now) {
		if (this.mode == mode) {
			return;
		}
		this.mode = mode;
		this.walker.stop();
		this.holdPos = null;
		this.task = null;
		this.lockDoor = null;
		this.hidePathSet = false;
		this.bot.setShiftKeyDown(false);
		this.bot.setSprinting(mode == Mode.FLEE || mode == Mode.FIGHT);
		this.fleeRepathAt = now;
		this.calmSince = now;
		this.replanAt = now;
	}

	private void perceive(int now) {
		if (now % 2 != 0) {
			return;
		}
		HwConfig cfg = this.cfg();
		ServerPlayer seen = Senses.seenShape(this.bot, cfg.botSightRange, cfg.botDarkSightRange, cfg.botFovDeg);
		if (seen == null) {
			ServerPlayer shape = Senses.shape(this.level());
			ShapeState st = shape == null ? null : MatchManager.shapeState(shape);
			// Footsteps right behind you, or being hit.
			if (shape != null && st != null && st.shadowTicks == 0
				&& (this.bot.distanceTo(shape) < cfg.botHearRange || this.bot.hurtTime > 0 && this.bot.distanceTo(shape) < 6)) {
				seen = shape;
				this.seeing = this.reaction;
			}
		}
		if (seen == null) {
			this.seeing = 0;
			this.reaction = this.rollReaction();
			return;
		}
		this.seeing += 2;
		if (this.seeing >= this.reaction || this.aware(now)) {
			this.threat = seen.position();
			this.threatTick = now;
		}
	}

	// ---------------------------------------------------------------- flee / hide

	private void tickFlee(int now) {
		HwConfig cfg = this.cfg();
		if (this.lockDoor != null) {
			this.tickLock(now);
			return;
		}
		boolean seenRecently = now - this.threatTick < 40;
		if (seenRecently) {
			this.calmSince = now;
		} else if (now - this.calmSince > cfg.botCalmTicks) {
			if (this.random.nextFloat() < cfg.botHideChance) {
				this.enter(Mode.HIDE, now);
				this.hideUntil = now + cfg.botHideMinTicks + this.random.nextInt(Math.max(1, cfg.botHideMaxTicks - cfg.botHideMinTicks));
			} else {
				this.enter(Mode.ROAM, now);
			}
			return;
		}
		if (now >= this.fleeRepathAt || this.walker.idle()) {
			this.fleeRepathAt = now + 30;
			Vec3 from = this.threat;
			Nav.Options opt = Nav.Options.climber();
			opt.maxNodes = 2500;
			opt.windowCost = this.bot.getHealth() > this.bot.getMaxHealth() * cfg.vaultDamageFraction + 1 ? 3 : 6;
			this.walker.follow(Nav.search(this.level(), this.bot.blockPosition(), 28, opt, (p, cost) -> fleeScore(this.level(), p, cost, from, this.bot)));
		}
		Walker.Status status = this.walker.tick();
		if (status == Walker.Status.WINDOW) {
			Nav.Node node = this.walker.current();
			boolean close = this.threat != null && this.bot.position().distanceTo(this.threat) < 8;
			boolean canFast = this.bot.getHealth() > this.bot.getMaxHealth() * cfg.vaultDamageFraction + 0.5;
			Windows.request(this.bot, node.glass(), close && canFast);
			this.walker.skip();
		} else if (status == Walker.Status.STUCK) {
			this.fleeRepathAt = now;
		}
		// Just went through a door with the Shape some way behind: lock it.
		BlockPos door = this.walker.takePassedDoor();
		if (door != null && DoorLocks.isDoor(this.level().getBlockState(door)) && this.threat != null
			&& this.threat.distanceTo(Vec3.atCenterOf(door)) > cfg.botLockMinDistance
			&& !DoorLocks.isLocked(this.level(), door) && !DoorLocks.isLockBroken(this.level(), door)) {
			this.lockDoor = DoorLocks.base(this.level(), door);
			this.lockUntil = now + cfg.lockTicks + 12;
			this.walker.halt();
		}
	}

	private void tickLock(int now) {
		this.walker.halt();
		Vec3 door = Vec3.atCenterOf(this.lockDoor.above());
		this.walker.face(door);
		boolean tooClose = this.threat != null && now - this.threatTick < 20 && this.bot.position().distanceTo(this.threat) < 3;
		if (now > this.lockUntil || tooClose || DoorLocks.isLocked(this.level(), this.lockDoor)) {
			this.lockDoor = null;
			this.fleeRepathAt = now;
			return;
		}
		if (now % 4 == 0) {
			DoorLocks.ping(this.bot, this.lockDoor);
		}
	}

	/** Far from where the Shape was and out of his sight, without running a marathon. Shared with townsfolk. */
	static double fleeScore(ServerLevel level, BlockPos p, double cost, Vec3 from, net.minecraft.world.entity.Entity viewer) {
		if (from == null) {
			return -cost;
		}
		Vec3 c = Vec3.atBottomCenterOf(p);
		double d = c.distanceTo(from);
		double score = Math.min(d, 30) - cost * 0.3;
		if (d < 22 && Sight.canSee(level, from.add(0, 1.6, 0), c.add(0, 1.5, 0), viewer)) {
			score -= 8;
		}
		return score;
	}

	private void tickHide(int now) {
		HwConfig cfg = this.cfg();
		if (now > this.hideUntil) {
			this.enter(Mode.ROAM, now);
			return;
		}
		if (!this.hidePathSet) {
			this.hidePathSet = true;
			Vec3 from = this.threat;
			Nav.Options opt = Nav.Options.walker();
			opt.maxNodes = 2500;
			this.walker.follow(Nav.search(this.level(), this.bot.blockPosition(), 22, opt, (p, cost) -> hideScore(this.level(), p, cost, from, this.bot)));
		}
		Walker.Status status = this.walker.tick();
		if (status == Walker.Status.ARRIVED || status == Walker.Status.IDLE || status == Walker.Status.STUCK) {
			this.walker.halt();
			this.bot.setShiftKeyDown(true);
			if (this.threat != null) {
				this.walker.face(this.threat.add(0, 1.5, 0));
			}
		}
	}

	/** Dark, indoors, out of sight of where the Shape was. Shared with townsfolk. */
	static double hideScore(ServerLevel level, BlockPos p, double cost, Vec3 from, net.minecraft.world.entity.Entity viewer) {
		double score = -cost * 0.2;
		if (level.getBrightness(LightLayer.BLOCK, p) <= 4) {
			score += 6;
		}
		if (!level.canSeeSky(p)) {
			score += 5;
		}
		if (from != null) {
			Vec3 c = Vec3.atBottomCenterOf(p);
			score += Math.min(c.distanceTo(from), 20) * 0.3;
			if (c.distanceTo(from) < 26 && Sight.canSee(level, from.add(0, 1.6, 0), c.add(0, 1.0, 0), viewer)) {
				score -= 10;
			}
		}
		return score;
	}

	// ---------------------------------------------------------------- fight back

	private int meleeSlot() {
		for (int i = 0; i < Inventory.SLOTS; i++) {
			HwItems.Kind kind = HwItems.kindOf(this.bot.getInventory().getItem(i));
			if (kind != null && kind.melee()) {
				return i;
			}
		}
		return -1;
	}

	private void tickFight(int now, ServerPlayer shape, ShapeState state) {
		if (shape == null || state == null || now > this.fightUntil || state.stunnedTicks > 0 || state.grab == null || this.meleeSlot() < 0) {
			this.enter(this.aware(now) ? Mode.FLEE : Mode.ROAM, now);
			return;
		}
		this.bot.getInventory().setSelectedSlot(this.meleeSlot());
		double dist = this.bot.distanceTo(shape);
		if (dist > 2.4) {
			if (now >= this.pathAt || this.walker.idle()) {
				this.pathAt = now + 10;
				this.walker.follow(Nav.to(this.level(), this.bot.blockPosition(), shape.blockPosition(), 1.6, Nav.Options.walker()));
			}
			this.walker.tick();
			return;
		}
		this.walker.halt();
		this.walker.face(shape.getEyePosition().add(0, -0.4, 0));
		if (now >= this.nextHit) {
			this.nextHit = now + this.cfg().survivorHitCooldownTicks + 3 + this.random.nextInt(6);
			this.bot.swing(InteractionHand.MAIN_HAND, true);
			Fight.onSurvivorAttack(this.bot, shape);
		}
	}

	private void tryDart(int now, ServerPlayer shape, ShapeState state) {
		if (shape == null || state == null || now < this.nextDart || state.shadowTicks > 0) {
			return;
		}
		int slot = Inventory.slotOf(this.bot, HwItems.Kind.DART);
		if (slot < 0 || now - this.threatTick > 4) {
			return;
		}
		double dist = this.bot.distanceTo(shape);
		boolean helping = state.grab != null && state.grab.victim != this.bot;
		if (dist > 14 || dist < 3 || !(helping || dist < 8)) {
			return;
		}
		this.nextDart = now + 30 + this.random.nextInt(30);
		int previous = this.bot.getInventory().getSelectedSlot();
		this.bot.getInventory().setSelectedSlot(slot);
		Vec3 aim = shape.position().add(0, 1.2 + dist * 0.02, 0);
		this.walker.face(aim);
		ItemStack stack = this.bot.getInventory().getItem(slot);
		Fight.onUseItem(this.bot, stack);
		this.bot.swing(InteractionHand.MAIN_HAND, true);
		this.bot.getInventory().setSelectedSlot(previous);
	}

	// ---------------------------------------------------------------- objectives

	private void tickRoam(int now) {
		if (this.holdPos != null) {
			this.tickHold(now);
			return;
		}
		if (now < this.quietUntil) {
			this.walker.halt();
			return;
		}
		if (this.task == null || now >= this.replanAt) {
			this.plan(now);
		}
		if (this.task == null) {
			this.walker.halt();
			return;
		}
		Walker.Status status = this.walker.tick();
		boolean there = Vec3.atBottomCenterOf(this.bot.blockPosition()).distanceTo(Vec3.atCenterOf(this.task.target)) <= this.task.within + 0.8;
		if (status == Walker.Status.WINDOW) {
			Windows.request(this.bot, this.walker.current().glass(), false);
			this.walker.skip();
		} else if (status == Walker.Status.STUCK || (status != Walker.Status.MOVING && !there)) {
			this.failed.put(this.task.target, now + 200);
			this.task = null;
		} else if (status != Walker.Status.MOVING || there && this.walker.idle()) {
			if (this.task.hold) {
				this.walker.halt();
				this.holdPos = this.task.target;
				this.holdStart = now;
				this.holdBegan = false;
			} else {
				this.task = null;
				this.quietUntil = now + 20 + this.random.nextInt(60);
			}
		}
	}

	private void tickHold(int now) {
		this.walker.face(Vec3.atCenterOf(this.holdPos));
		if (now % 4 == 0) {
			Holds.ping(this.bot, this.holdPos);
		}
		boolean working = Holds.working(this.bot);
		if (working) {
			this.holdBegan = true;
		} else if (this.holdBegan) {
			// Done (or interrupted): let go of the button before anything else.
			this.holdPos = null;
			this.task = null;
			this.quietUntil = now + 10;
		} else if (now - this.holdStart > 14) {
			this.failed.put(this.holdPos, now + 400);
			this.holdPos = null;
			this.task = null;
			this.quietUntil = now + 10;
		}
	}

	private void plan(int now) {
		this.replanAt = now + 100;
		Task best = null;
		double bestScore = -Double.MAX_VALUE;
		for (Map.Entry<Task, Double> e : this.candidates(now).entrySet()) {
			Task t = e.getKey();
			Integer until = this.failed.get(t.target);
			if (until != null && now < until) {
				continue;
			}
			double score = e.getValue() - this.bot.position().distanceTo(Vec3.atCenterOf(t.target)) * 0.6;
			if (this.threat != null && now - this.threatTick < 600 && this.threat.distanceTo(Vec3.atCenterOf(t.target)) < 12) {
				score -= 40;
			}
			if (score > bestScore) {
				bestScore = score;
				best = t;
			}
		}
		if (best == null) {
			this.task = null;
			return;
		}
		if (this.task == null || !this.task.equals(best) || this.walker.idle()) {
			this.task = best;
			Nav.Options opt = Nav.Options.climber();
			opt.windowCost = 8;
			this.walker.follow(Nav.to(this.level(), this.bot.blockPosition(), best.target, best.within, opt));
		}
	}

	private Map<Task, Double> candidates(int now) {
		HwConfig cfg = this.cfg();
		Map<Task, Double> out = new HashMap<>();
		boolean policeIdle = false;
		for (HwNet.ExitInfo exit : Exits.infos()) {
			BlockPos pos = exit.pos();
			switch (exit.type()) {
				case Exits.CELLAR -> {
					if (exit.state() == Exits.OPEN) {
						out.put(new Task(pos, 1.8, true, "exit"), 100.0);
					} else if (exit.state() == Exits.CLOSED && (Inventory.has(this.bot, HwItems.Kind.PADLOCK_KEY) || Inventory.has(this.bot, HwItems.Kind.BOLT_CUTTERS))) {
						out.put(new Task(pos, 1.8, true, "cellar"), 80.0);
					}
				}
				case Exits.CAR -> {
					int flags = exit.state();
					if ((flags & Exits.CAR_GONE) != 0) {
						break;
					}
					if ((flags & Exits.CAR_PART) == 0) {
						if (Inventory.has(this.bot, HwItems.Kind.CAR_PART)) {
							out.put(new Task(pos, 2.4, true, "car"), 70.0);
						}
					} else if ((flags & Exits.CAR_REPAIRED) == 0) {
						if (Inventory.has(this.bot, HwItems.Kind.REPAIR_KIT)) {
							out.put(new Task(pos, 2.4, true, "car"), 70.0);
						}
					} else if (Inventory.has(this.bot, HwItems.Kind.CAR_KEYS)) {
						out.put(new Task(pos, 2.4, true, "exit"), 100.0);
					}
				}
				case Exits.POLICE -> {
					if (exit.state() == Exits.POLICE_HERE) {
						out.put(new Task(pos, 2.6, true, "exit"), 100.0);
					}
					policeIdle = exit.state() == Exits.POLICE_IDLE;
				}
				case Exits.GATE -> {
					if (exit.state() == Exits.OPEN) {
						out.put(new Task(pos, 1.8, true, "exit"), 95.0);
					} else if (exit.state() == Exits.CLOSED && Match.ticksLeft() <= cfg.gateOpenLastSeconds * 20) {
						out.put(new Task(pos, 1.8, true, "gate"), 60.0);
					}
				}
				default -> {
				}
			}
		}
		if (policeIdle) {
			List<BlockPos> cut = Exits.cutPhones();
			for (BlockPos phone : MapPoints.get(this.level()).list(MapPoints.Type.PHONE)) {
				if (cut.contains(phone)) {
					if (Inventory.has(this.bot, HwItems.Kind.REPAIR_KIT)) {
						out.put(new Task(phone, 2.0, true, "phone"), 50.0);
					}
				} else if (!Bots.othersOn("call", this.bot)) {
					out.put(new Task(phone, 2.0, true, "call"), 55.0);
				}
			}
		}
		List<BlockPos> searched = Loot.searched();
		for (BlockPos container : Loot.containers()) {
			if (!searched.contains(container) && !Bots.claimedByOther(container, this.bot)) {
				out.put(new Task(container, 2.0, true, "search"), 30.0);
			}
		}
		if (out.isEmpty()) {
			// Nothing to do: drift towards another survivor, or somewhere random near the middle.
			BlockPos center = MapPoints.get(this.level()).first(MapPoints.Type.CENTER);
			List<ServerPlayer> mates = new ArrayList<>();
			for (ServerPlayer p : this.level().players()) {
				if (p != this.bot && MatchManager.role(p).survivor() && !p.isSpectator()) {
					mates.add(p);
				}
			}
			BlockPos to = !mates.isEmpty() && this.random.nextBoolean() ? mates.get(this.random.nextInt(mates.size())).blockPosition()
				: center == null ? this.bot.blockPosition() : center.offset(this.random.nextInt(31) - 15, 0, this.random.nextInt(31) - 15);
			out.put(new Task(to, 3.0, false, "wander"), 1.0);
		}
		return out;
	}
}
