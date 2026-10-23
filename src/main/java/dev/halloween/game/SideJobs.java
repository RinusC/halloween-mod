package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Things worth doing on the side, there from the start of every match, each somewhere else each time (out on the
 * street or in a yard, away from Michael), on the survivors' goal list until done.
 * <ul>
 * <li><b>The radio.</b> A CB radio left out: tuned in (held use, 4 s), it picks up the police for the rest of the
 * match: every 25 s where he was last seen shows for everyone on the team (a mark from the radio).</li>
 * <li><b>The dog.</b> A dog on a chain: untied, it goes with whoever freed it; when Michael comes within 16 blocks
 * of it, it growls and barks, and shows him to the team. It does not fight, and nothing hurts it.</li>
 * </ul>
 * All of it is gone when the match ends.
 */
public final class SideJobs {
	public static final String RADIO = "radio";
	public static final String DOG = "dog";
	/** The dog (a match leftover if the server stops mid-match, see Leftovers). */
	public static final String TAG = "hw_job_dog";
	private static final int RADIO_TICKS = 80;
	private static final int RADIO_EVERY = 500;
	private static final double DOG_RANGE = 16;

	private static boolean set;
	private static BlockPos radio;
	private static BlockState radioBefore;
	private static boolean tuned;
	private static int nextReport;
	private static Wolf dog;
	private static boolean freed;
	private static int nextBark;
	private static String sent = "";

	private SideJobs() {
	}

	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		if (radio != null && level.getBlockState(radio).is(HwBlocks.RADIO)) {
			level.setBlock(radio, radioBefore == null ? Blocks.AIR.defaultBlockState() : radioBefore, Block.UPDATE_ALL);
		}
		if (dog != null) {
			dog.discard();
		}
		radio = null;
		dog = null;
		set = false;
		tuned = false;
		freed = false;
		sent = "";
		for (ServerPlayer p : level.players()) {
			ServerPlayNetworking.send(p, new HwNet.SideJobs(List.of(), List.of(), List.of()));
		}
	}

	/** Every tick: setting the jobs out once the match runs, the radio's reports, the dog's barking, the clients' list. */
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
			setUp(level);
		}
		ServerPlayer shape = Senses.shape(level);
		ShapeState st = shape == null ? null : MatchManager.shapeState(shape);
		boolean shown = st != null && st.shadowTicks == 0 && !shape.isSpectator();
		if (tuned && now >= nextReport) {
			nextReport = now + RADIO_EVERY;
			if (shown) {
				// "All units, he was seen at...": a mark for the team, the radio crackling for whoever is near it.
				Pings.mark(level, shape.position(), Pings.KILLER, -1, "#radio", 60);
				level.playSound(null, radio, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 1f, 1.6f);
			}
		}
		if (dog != null && freed && !dog.isRemoved()) {
			dog.setTarget(null);
			if (shown && now >= nextBark && dog.distanceTo(shape) <= DOG_RANGE) {
				nextBark = now + 40;
				level.playSound(null, dog.getX(), dog.getY(), dog.getZ(), (level.getRandom().nextBoolean() ? SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().growlSound() : SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().ambientSound()).value(),
					SoundSource.NEUTRAL, 2f, 0.85f);
				Pings.mark(level, shape.position(), Pings.KILLER, shape.getId(), "#dog", 40);
			}
		}
		if (now % 20 == 0) {
			sync(level);
		}
	}

	private static void setUp(ServerLevel level) {
		radio = Events.outdoorSpot(level, 30);
		if (radio != null) {
			radioBefore = level.getBlockState(radio);
			level.setBlock(radio, HwBlocks.RADIO.defaultBlockState().setValue(RadioBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom())),
				Block.UPDATE_ALL);
		}
		BlockPos kennel = Events.outdoorSpot(level, 30);
		if (kennel != null && (radio == null || kennel.distSqr(radio) > 400)) {
			Wolf wolf = EntityTypes.WOLF.create(level, EntitySpawnReason.EVENT);
			if (wolf != null) {
				wolf.snapTo(Vec3.atBottomCenterOf(kennel), level.getRandom().nextFloat() * 360f, 0f);
				// On the chain: lying where it is until someone frees it.
				wolf.setNoAi(true);
				wolf.setInvulnerable(true);
				wolf.setPersistenceRequired();
				wolf.addTag(TAG);
				level.addFreshEntity(wolf);
				dog = wolf;
			}
		}
		freed = false;
		tuned = false;
	}

	/** Held use on the radio: tune it. */
	public static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		if (radio == null || !pos.equals(radio) || tuned || !MatchManager.role(player).survivor()) {
			return null;
		}
		return new Holds.Hold(radio, "halloween.hud.progress.radio", false, RADIO_TICKS, p -> !tuned, p -> {
			tuned = true;
			nextReport = p.level().getServer().getTickCount() + 40;
			p.level().playSound(null, radio, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1f, 1.8f);
			Notices.send(p, Component.translatable("halloween.job.radio.done").withStyle(ChatFormatting.GOLD));
			Match.score(p, dev.halloween.HwConfig.get().scoreEvent, Component.translatable("halloween.job.radio"));
			AiStats.add(p, "job.radio");
		});
	}

	/** Use on the dog: untie it, it goes with them. */
	public static InteractionResult onUseEntity(Player user, Entity target) {
		if (user.level().isClientSide() || target != dog || freed || !(user instanceof ServerPlayer p) || !MatchManager.role(p).survivor()) {
			return InteractionResult.PASS;
		}
		freed = true;
		dog.setNoAi(false);
		// Tamed without the vanilla toast (the advancement).
		dog.setTame(true, true);
		dog.setOwner(p);
		dog.setOrderedToSit(false);
		dog.level().playSound(null, dog.getX(), dog.getY(), dog.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.NEUTRAL, 1f, 1.2f);
		dog.level().playSound(null, dog.getX(), dog.getY(), dog.getZ(), SoundEvents.WOLF_SOUNDS.get(net.minecraft.world.entity.animal.wolf.WolfSoundVariants.SoundSet.CLASSIC).adultSounds().ambientSound().value(), SoundSource.NEUTRAL, 1f, 1.1f);
		Notices.send(p, Component.translatable("halloween.job.dog.done").withStyle(ChatFormatting.GOLD));
		Match.score(p, dev.halloween.HwConfig.get().scoreEvent, Component.translatable("halloween.job.dog"));
		AiStats.add(p, "job.dog");
		return InteractionResult.SUCCESS;
	}

	/** Tests: where the radio stands and the dog, if set out. */
	public static BlockPos radioPos() {
		return radio;
	}

	public static Wolf dogEntity() {
		return dog;
	}

	public static boolean tuned() {
		return tuned;
	}

	public static boolean isDog(Entity e) {
		return e != null && e == dog;
	}

	private static void sync(ServerLevel level) {
		List<String> jobs = new ArrayList<>();
		List<Boolean> done = new ArrayList<>();
		List<Vec3> where = new ArrayList<>();
		if (radio != null) {
			jobs.add(RADIO);
			done.add(tuned);
			where.add(Vec3.atCenterOf(radio));
		}
		if (dog != null) {
			jobs.add(DOG);
			done.add(freed);
			where.add(dog.position());
		}
		String state = jobs + "" + done + (radio == null ? "" : radio.toShortString());
		if (state.equals(sent)) {
			return;
		}
		sent = state;
		HwNet.SideJobs packet = new HwNet.SideJobs(jobs, done, where);
		for (ServerPlayer p : level.players()) {
			Role role = MatchManager.role(p);
			if (role != Role.NONE && !Bots.isBot(p)) {
				ServerPlayNetworking.send(p, packet);
			}
		}
	}
}
