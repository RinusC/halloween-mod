package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Townsfolk asking for a hand: now and then one at home, well away from Michael, has a favour to ask (a mark over its
 * head, at most {@link HwConfig#errandMax} at a time). A survivor takes it on from the talk menu, or walks on.
 * <ul>
 * <li><b>Medicine.</b> Bring a healing spray or a med kit for the wife: handed over from the talk menu.</li>
 * <li><b>Walk me home.</b> The townsperson follows to another house; on the way it is one more person for him to see.</li>
 * <li><b>The radio.</b> A broken radio in its house: the wires by hand, like a breaker box (a slip is heard).</li>
 * <li><b>The dog.</b> Its dog ran off: found somewhere out on the street, it follows back to its owner.</li>
 * </ul>
 * Done, it pays points and sometimes something useful: rounds, a spray, or word of where Michael was just now, marked
 * for the team. Michael close by (or the townsperson running, hiding, gone) calls the favour off.
 */
public final class Errands {
	public static final int MEDICINE = 0;
	public static final int ESCORT = 1;
	public static final int RADIO = 2;
	public static final int DOG = 3;
	private static final int KINDS = 4;

	/** How close Michael may come before a townsperson thinks better of it. */
	private static final double CANCEL_RANGE = 14;
	/** Asks only with him at least this far away. */
	private static final double ASK_RANGE = 32;
	private static final int FIRST_ASK = 1200;
	/** An offer nobody takes up is dropped after a while; one taken on has to be done in time. */
	private static final int OFFER_TICKS = 2400;
	private static final int DONE_TICKS = 4800;

	private static final class Errand {
		final int kind;
		Mannequin npc;
		final String name;
		UUID taker;
		final int offeredAt;
		int takenAt;
		/** Walk me home: where to. The radio: where it stands. The dog: where it was lost. */
		BlockPos target;
		BlockState before;
		Wolf dog;
		boolean found;

		Errand(int kind, Mannequin npc, String name, int now) {
			this.kind = kind;
			this.npc = npc;
			this.name = name;
			this.offeredAt = now;
		}
	}

	private static final List<Errand> ERRANDS = new ArrayList<>();
	/** Each townsperson asks once a match. */
	private static final Set<UUID> ASKED = new HashSet<>();
	private static boolean set;
	private static int nextAsk;
	private static String sent = "";

	private Errands() {
	}

	public static void clear(MinecraftServer server) {
		for (Errand e : ERRANDS) {
			undo(server.overworld(), e);
		}
		ERRANDS.clear();
		ASKED.clear();
		set = false;
		sent = "";
		for (ServerPlayer p : server.overworld().players()) {
			ServerPlayNetworking.send(p, new HwNet.Errands(List.of()));
		}
	}

	/** What an errand leaves in the world (the radio, a dog nobody found) goes. */
	private static void undo(ServerLevel level, Errand e) {
		if (e.kind == RADIO && e.target != null && level.getBlockState(e.target).is(HwBlocks.RADIO)) {
			level.setBlock(e.target, e.before == null ? Blocks.AIR.defaultBlockState() : e.before, Block.UPDATE_ALL);
		}
		if (e.dog != null && !e.found) {
			e.dog.discard();
		}
	}

	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			if (set) {
				clear(server);
			}
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		if (!set) {
			set = true;
			nextAsk = now + FIRST_ASK;
		}
		if (now % 10 == 0) {
			for (Errand e : new ArrayList<>(ERRANDS)) {
				check(level, e, now);
			}
		}
		HwConfig cfg = HwConfig.get();
		if (now >= nextAsk) {
			nextAsk = now + cfg.errandEveryTicks / 2 + level.getRandom().nextInt(Math.max(1, cfg.errandEveryTicks));
			if (ERRANDS.size() < cfg.errandMax) {
				ask(level, now);
			}
		}
		if (now % 10 == 0) {
			sync(level);
		}
	}

	// ---------------------------------------------------------------- asking

	private static void ask(ServerLevel level, int now) {
		RandomSource random = level.getRandom();
		List<Mannequin> idle = new ArrayList<>();
		for (Mannequin body : Townsfolk.bodies()) {
			if (!ASKED.contains(body.getUUID()) && Townsfolk.idle(body) && shapeDistance(level, body.position()) >= ASK_RANGE) {
				idle.add(body);
			}
		}
		if (idle.isEmpty()) {
			return;
		}
		Mannequin npc = idle.get(random.nextInt(idle.size()));
		// Something not already asked for, if there is.
		List<Integer> kinds = new ArrayList<>();
		for (int k = 0; k < KINDS; k++) {
			final int kind = k;
			if (ERRANDS.stream().noneMatch(e -> e.kind == kind)) {
				kinds.add(k);
			}
		}
		java.util.Collections.shuffle(kinds, new java.util.Random(random.nextLong()));
		for (int kind : kinds) {
			Errand e = new Errand(kind, npc, Townsfolk.nameOf(npc), now);
			if (prepare(level, e)) {
				ASKED.add(npc.getUUID());
				ERRANDS.add(e);
				AiStats.add(npc, "errand.offer." + kind);
				return;
			}
		}
	}

	/** Finds what the favour needs (another house, a spot in its own, the dog's spot); false if there is none. */
	private static boolean prepare(ServerLevel level, Errand e) {
		BlockPos at = e.npc.blockPosition();
		switch (e.kind) {
			case ESCORT -> {
				List<BlockPos> homes = new ArrayList<>();
				for (BlockPos p : MapPoints.get(level).list(MapPoints.Type.NPC)) {
					double d = Math.sqrt(p.distSqr(at));
					if (d >= 25 && d <= 70 && shapeDistance(level, Vec3.atCenterOf(p)) >= ASK_RANGE) {
						homes.add(p);
					}
				}
				if (homes.isEmpty()) {
					return false;
				}
				e.target = homes.get(level.getRandom().nextInt(homes.size()));
				return true;
			}
			case RADIO -> {
				// Indoors, on its own floor, near where it stands.
				for (int i = 0; i < 40; i++) {
					BlockPos p = Nav.snap(level, at.offset(level.getRandom().nextInt(13) - 6, 0, level.getRandom().nextInt(13) - 6));
					if (Math.abs(p.getY() - at.getY()) <= 1 && Nav.standable(level, p) && !level.canSeeSky(p) && level.getBlockState(p).isAir()
						&& level.getBlockState(p.above()).isAir() && p.distSqr(at) >= 4) {
						e.target = p;
						return true;
					}
				}
				return false;
			}
			case DOG -> {
				for (int i = 0; i < 6; i++) {
					BlockPos p = Events.outdoorSpot(level, ASK_RANGE);
					if (p != null && p.distSqr(at) >= 20 * 20 && p.distSqr(at) <= 70 * 70) {
						e.target = p;
						return true;
					}
				}
				return false;
			}
			default -> {
				return true;
			}
		}
	}

	// ---------------------------------------------------------------- taking it on

	/** Is there a favour this player could take on (or finish) from this townsperson's talk menu? */
	public static boolean offers(ServerPlayer player, Entity npc) {
		Errand e = of(npc);
		return e != null && (e.taker == null || e.taker.equals(player.getUUID()));
	}

	/** The talk menu's fourth choice: take the favour on, or hand the medicine over. */
	public static void talk(ServerPlayer player, Entity npc) {
		Errand e = of(npc);
		if (e == null || !Match.running() || !MatchManager.role(player).survivor() || player.isSpectator() || Bots.isBot(player)
			|| player.distanceTo(npc) > HwConfig.get().talkRange) {
			return;
		}
		ServerLevel level = player.level();
		int now = level.getServer().getTickCount();
		if (e.taker == null) {
			if (taken(player) >= 1) {
				// One favour at a time.
				player.sendOverlayMessage(Component.translatable("halloween.errand.busy").withStyle(ChatFormatting.GRAY));
				return;
			}
			e.taker = player.getUUID();
			e.takenAt = now;
			start(level, e, player);
			Dialogue.speak(e.npc, player, Dialogue.Mood.REQUEST, Component.translatable("halloween.errand.ask." + e.kind));
			AiStats.add(player, "errand.take." + e.kind);
			sent = "";
		} else if (e.taker.equals(player.getUUID()) && e.kind == MEDICINE) {
			HwItems.Kind med = Inventory.has(player, HwItems.Kind.MED_KIT) ? HwItems.Kind.MED_KIT
				: Inventory.has(player, HwItems.Kind.HEALING_SPRAY) ? HwItems.Kind.HEALING_SPRAY : null;
			if (med == null) {
				player.sendOverlayMessage(Component.translatable("halloween.errand.no_medicine").withStyle(ChatFormatting.GRAY));
				return;
			}
			Inventory.consume(player, med);
			done(level, e, player);
		}
	}

	private static void start(ServerLevel level, Errand e, ServerPlayer taker) {
		switch (e.kind) {
			case ESCORT -> Townsfolk.escort(e.npc, taker);
			case RADIO -> {
				e.before = level.getBlockState(e.target);
				if (!e.before.isAir()) {
					// Someone put something there meanwhile: the radio goes by the townsperson instead.
					e.target = e.npc.blockPosition();
					e.before = level.getBlockState(e.target);
				}
				level.setBlock(e.target, HwBlocks.RADIO.defaultBlockState().setValue(RadioBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom())),
					Block.UPDATE_ALL);
			}
			case DOG -> {
				Wolf wolf = EntityTypes.WOLF.create(level, EntitySpawnReason.EVENT);
				if (wolf != null) {
					wolf.snapTo(Vec3.atBottomCenterOf(e.target), level.getRandom().nextFloat() * 360f, 0f);
					// Cowering where it ran to until someone it trusts comes along.
					wolf.setNoAi(true);
					wolf.setInvulnerable(true);
					wolf.setPersistenceRequired();
					wolf.addTag(SideJobs.TAG);
					level.addFreshEntity(wolf);
					e.dog = wolf;
				}
			}
			default -> {
			}
		}
	}

	/** Holding use on the broken radio: the wires by hand (whoever took the favour on). */
	public static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		for (Errand e : ERRANDS) {
			if (e.kind == RADIO && e.taker != null && pos.equals(e.target)) {
				if (e.taker.equals(player.getUUID()) && !Bots.isBot(player)) {
					Minigames.open(player, HwNet.Minigame.WIRES, pos);
				}
				return null;
			}
		}
		return null;
	}

	/** Is this the broken radio of a favour (its wires are joined through Minigames)? */
	public static boolean isRadio(BlockPos pos) {
		return ERRANDS.stream().anyMatch(e -> e.kind == RADIO && e.taker != null && pos.equals(e.target));
	}

	/** The wires of the broken radio joined. */
	public static boolean repaired(ServerPlayer player, BlockPos pos) {
		for (Errand e : new ArrayList<>(ERRANDS)) {
			if (e.kind == RADIO && e.taker != null && pos.equals(e.target)) {
				if (e.taker.equals(player.getUUID())) {
					ServerLevel level = player.level();
					level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.8f, 1.3f);
					done(level, e, player);
				}
				return true;
			}
		}
		return false;
	}

	/** Use on the lost dog: it knows it is being taken home, and comes along. */
	public static InteractionResult onUseEntity(Player user, Entity target) {
		if (user.level().isClientSide() || !(user instanceof ServerPlayer p) || !MatchManager.role(p).survivor()) {
			return InteractionResult.PASS;
		}
		for (Errand e : ERRANDS) {
			if (e.dog == target && !e.found) {
				e.found = true;
				e.dog.setNoAi(false);
				e.dog.setTame(true, true);
				e.dog.setOwner(p);
				e.dog.setOrderedToSit(false);
				e.dog.level().playSound(null, e.dog.getX(), e.dog.getY(), e.dog.getZ(),
					SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().whineSound().value(),
					SoundSource.NEUTRAL, 1f, 1.1f);
				Notices.send(p, Component.translatable("halloween.errand.dog_found", e.name).withStyle(ChatFormatting.GOLD));
				sent = "";
				return InteractionResult.SUCCESS;
			}
		}
		return InteractionResult.PASS;
	}

	// ---------------------------------------------------------------- keeping an eye on it

	private static void check(ServerLevel level, Errand e, int now) {
		ServerPlayer taker = e.taker == null ? null : level.getServer().getPlayerList().getPlayer(e.taker);
		if (Townsfolk.destroyed(e.npc) || !e.npc.isAlive()) {
			cancel(level, e, taker, "halloween.errand.gone");
			return;
		}
		if (e.npc.isRemoved()) {
			// Its chunk unloaded: it waits there, and so does the favour.
			return;
		}
		if (e.taker == null) {
			if (!Townsfolk.idle(e.npc) || now - e.offeredAt > OFFER_TICKS || shapeDistance(level, e.npc.position()) < CANCEL_RANGE) {
				cancel(level, e, null, null);
			}
			return;
		}
		if (taker == null || taker.isSpectator() || !MatchManager.role(taker).survivor()) {
			cancel(level, e, null, null);
			return;
		}
		if (shapeNear(level, e.npc.position(), CANCEL_RANGE)) {
			// "Not now, he's here!": it runs for it, the favour is off.
			Townsfolk.spook(e.npc, now);
			cancel(level, e, taker, "halloween.errand.scared");
			return;
		}
		String state = Townsfolk.stateOf(e.npc);
		boolean busy = !state.equals("home") && !state.equals("talk") && !(e.kind == ESCORT && state.equals("follow"));
		if (busy) {
			cancel(level, e, taker, "halloween.errand.scared");
			return;
		}
		if (now - e.takenAt > DONE_TICKS) {
			cancel(level, e, taker, "halloween.errand.late");
			return;
		}
		switch (e.kind) {
			case ESCORT -> {
				if (!Townsfolk.follows(e.npc, taker)) {
					cancel(level, e, taker, "halloween.errand.left");
				} else if (e.npc.position().distanceTo(Vec3.atBottomCenterOf(e.target)) <= 3.5) {
					Townsfolk.settle(e.npc, e.target);
					done(level, e, taker);
				}
			}
			case DOG -> {
				if (e.dog == null || e.dog.isRemoved()) {
					cancel(level, e, taker, "halloween.errand.gone");
				} else if (e.found && e.dog.distanceTo(e.npc) <= 4) {
					// Home: it sits down by its owner and stays.
					e.dog.setOrderedToSit(true);
					e.dog.getNavigation().stop();
					done(level, e, taker);
				}
			}
			default -> {
			}
		}
	}

	private static void cancel(ServerLevel level, Errand e, ServerPlayer taker, String why) {
		ERRANDS.remove(e);
		if (e.kind == ESCORT && e.taker != null && taker != null && Townsfolk.follows(e.npc, taker)) {
			Townsfolk.settle(e.npc, e.npc.blockPosition());
		}
		undo(level, e);
		if (taker != null && why != null) {
			Notices.send(taker, Component.translatable(why, e.name).withStyle(ChatFormatting.GRAY));
		}
		sent = "";
	}

	/** Favours done so far (tests, debugging). */
	public static int doneCount;

	private static void done(ServerLevel level, Errand e, ServerPlayer taker) {
		ERRANDS.remove(e);
		doneCount++;
		if (e.kind == RADIO && e.target != null && level.getBlockState(e.target).is(HwBlocks.RADIO)) {
			// Mended, it stays where it is; it is only a radio again.
			e.target = null;
		}
		sent = "";
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, e.npc.getX(), e.npc.getY() + 1.2, e.npc.getZ(), 10, 0.4, 0.5, 0.4, 0);
		Dialogue.speak(e.npc, taker, Dialogue.Mood.AGREE, Component.translatable("halloween.errand.thanks." + e.kind));
		HwConfig cfg = HwConfig.get();
		Match.score(taker, cfg.scoreErrand, Component.translatable("halloween.score.errand", e.name));
		AiStats.add(taker, "errand.done." + e.kind);
		reward(level, e, taker);
	}

	/** Now and then more than thanks: rounds, a spray, or what it saw of him. */
	private static void reward(ServerLevel level, Errand e, ServerPlayer taker) {
		float roll = level.getRandom().nextFloat();
		ServerPlayer shape = Senses.shape(level);
		if (roll < 0.3f && shape != null) {
			// "I saw him, just now, over by ..." (where he is: not followed after).
			Pings.mark(level, shape.position(), Pings.KILLER, -1, "#townsfolk", 120);
			Notices.send(taker, Component.translatable("halloween.errand.reward.tip", e.name).withStyle(ChatFormatting.GOLD));
		} else if (roll < 0.6f) {
			HwItems.Kind ammo = ammoFor(taker, level.getRandom());
			net.minecraft.world.item.ItemStack stack = HwItems.create(ammo);
			stack.setCount(ammo == HwItems.Kind.SHOTGUN_SHELLS ? 2 : 3);
			Inventory.give(taker, stack);
			Notices.send(taker, Component.translatable("halloween.errand.reward.ammo", e.name).withStyle(ChatFormatting.GOLD));
		} else if (roll < 0.75f && e.kind != MEDICINE) {
			Inventory.give(taker, HwItems.create(HwItems.Kind.HEALING_SPRAY));
			Notices.send(taker, Component.translatable("halloween.errand.reward.spray", e.name).withStyle(ChatFormatting.GOLD));
		}
	}

	/** Rounds for the gun they carry, or any. */
	private static HwItems.Kind ammoFor(ServerPlayer player, RandomSource random) {
		for (HwItems.Kind gun : List.of(HwItems.Kind.REVOLVER, HwItems.Kind.RIFLE, HwItems.Kind.SHOTGUN)) {
			if (Inventory.has(player, gun)) {
				return Guns.ammoFor(gun);
			}
		}
		HwItems.Kind[] all = {HwItems.Kind.REVOLVER_AMMO, HwItems.Kind.RIFLE_AMMO, HwItems.Kind.SHOTGUN_SHELLS};
		return all[random.nextInt(all.length)];
	}

	// ---------------------------------------------------------------- helpers

	private static Errand of(Entity npc) {
		for (Errand e : ERRANDS) {
			if (npc != null && e.npc.getUUID().equals(npc.getUUID())) {
				return e;
			}
		}
		return null;
	}

	private static int taken(ServerPlayer player) {
		return (int) ERRANDS.stream().filter(e -> player.getUUID().equals(e.taker)).count();
	}

	private static double shapeDistance(ServerLevel level, Vec3 at) {
		double d = 1000;
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE && !p.isSpectator()) {
				d = Math.min(d, p.position().distanceTo(at));
			}
		}
		return d;
	}

	/** Him close and to be seen (not slipping by in Shape Jump, nor under in the Upside Down). */
	private static boolean shapeNear(ServerLevel level, Vec3 at, double range) {
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE && !p.isSpectator() && p.position().distanceTo(at) < range && !UpsideDown.isBelow(p)) {
				ShapeState st = MatchManager.shapeState(p);
				if (st == null || st.shadowTicks == 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** Tests: how many favours are out, and the first one's townsperson. */
	public static int count() {
		return ERRANDS.size();
	}

	public static Entity firstNpc() {
		return ERRANDS.isEmpty() ? null : ERRANDS.getFirst().npc;
	}

	/** Tests: ask now for this kind of favour (a townsperson at home, whatever the clock says); false if it could not be. */
	public static boolean askNow(ServerLevel level, int kind) {
		int before = ERRANDS.size();
		for (Mannequin body : Townsfolk.bodies()) {
			if (!ASKED.contains(body.getUUID()) && Townsfolk.idle(body)) {
				Errand e = new Errand(kind, body, Townsfolk.nameOf(body), level.getServer().getTickCount());
				if (prepare(level, e)) {
					ASKED.add(body.getUUID());
					ERRANDS.add(e);
					break;
				}
			}
		}
		sync(level);
		return ERRANDS.size() > before;
	}

	/** Tests: where the favour of this townsperson leads (the home, the radio), and its dog. */
	public static BlockPos targetOf(Entity npc) {
		Errand e = of(npc);
		return e == null ? null : e.target;
	}

	public static Wolf dogOf(Entity npc) {
		Errand e = of(npc);
		return e == null ? null : e.dog;
	}

	public static Entity lastNpc() {
		return ERRANDS.isEmpty() ? null : ERRANDS.getLast().npc;
	}

	private static void sync(ServerLevel level) {
		List<HwNet.Errands.Entry> list = new ArrayList<>();
		for (Errand e : ERRANDS) {
			ServerPlayer taker = e.taker == null ? null : level.getServer().getPlayerList().getPlayer(e.taker);
			Vec3 target = e.kind == ESCORT || e.kind == RADIO ? Vec3.atBottomCenterOf(e.target)
				: e.kind == DOG && e.dog != null ? (e.found ? e.npc.position() : e.dog.position()) : e.npc.position();
			int targetId = e.kind == DOG && e.dog != null && !e.found ? e.dog.getId() : e.kind == DOG || e.kind == MEDICINE ? e.npc.getId() : -1;
			list.add(new HwNet.Errands.Entry(e.npc.getId(), e.kind, taker == null ? -1 : taker.getId(), target, targetId, e.name, e.found));
		}
		String state = list.toString();
		if (state.equals(sent)) {
			return;
		}
		sent = state;
		HwNet.Errands packet = new HwNet.Errands(list);
		for (ServerPlayer p : level.players()) {
			Role role = MatchManager.role(p);
			if (role.survivor() && !Bots.isBot(p)) {
				ServerPlayNetworking.send(p, packet);
			}
		}
	}
}
