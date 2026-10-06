package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What survivors do with the things they find, on right click: throw them (bricks, rocks, balls,
 * firecrackers, road flares, the hourglass, cleavers, hatchets and pitchforks), patch themselves up (spray,
 * med kit), fire the flare gun. Also breaking a grab with a knife, the amulet that saves you once, and the
 * brass lantern's light. Lawn darts keep their own throw (see {@link Fight#onUseItem}).
 */
public final class Gear {
	public static final String THROW_TAG = "hw_throw";

	/** Something in flight, by projectile: what it is and who threw it. */
	private record Flying(HwItems.Kind kind, ItemStack stack, UUID thrower) {
	}

	/** A firecracker string about to go off, a flare burning on the ground. */
	private static final class Burning {
		final BlockPos light;
		final Vec3 at;
		final UUID thrower;
		int ticks;
		final boolean bang;

		Burning(BlockPos light, Vec3 at, UUID thrower, int ticks, boolean bang) {
			this.light = light;
			this.at = at;
			this.thrower = thrower;
			this.ticks = ticks;
			this.bang = bang;
		}
	}

	/** Someone standing still with the med kit open. */
	private static final class Channel {
		final Vec3 at;
		final int slot;
		int ticks;

		Channel(Vec3 at, int slot) {
			this.at = at;
			this.slot = slot;
		}
	}

	private static final Map<UUID, Flying> FLYING = new HashMap<>();
	private static final List<Burning> BURNING = new ArrayList<>();
	private static final Map<UUID, Channel> HEALING = new HashMap<>();
	private static final Map<UUID, Integer> NEXT_USE = new HashMap<>();
	/** The spray needs a moment between two puffs. */
	private static final Map<UUID, Integer> NEXT_SPRAY = new HashMap<>();
	/** Light blocks of brass lanterns, by holder. */
	private static final Map<UUID, BlockPos> LANTERNS = new HashMap<>();

	private Gear() {
	}

	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Burning b : BURNING) {
			removeLight(level, b.light);
		}
		LANTERNS.values().forEach(pos -> removeLight(level, pos));
		BURNING.clear();
		LANTERNS.clear();
		FLYING.clear();
		HEALING.clear();
		NEXT_USE.clear();
		NEXT_SPRAY.clear();
	}

	// ---------------------------------------------------------------- right click

	/** Right click with whatever is in hand (the client sends it when the click isn't meant for a door or an object). */
	public static void use(ServerPlayer player) {
		if (!Match.running() || !MatchManager.role(player).survivor() || player.isSpectator() || MatchManager.isGrabbed(player)
			|| Lockers.isInside(player) || Windows.isVaulting(player) || Minigames.active(player)) {
			return;
		}
		ItemStack stack = player.getMainHandItem();
		HwItems.Kind kind = HwItems.kindOf(stack);
		if (kind == null) {
			return;
		}
		int now = player.level().getServer().getTickCount();
		if (now < NEXT_USE.getOrDefault(player.getUUID(), 0)) {
			return;
		}
		NEXT_USE.put(player.getUUID(), now + 8);
		switch (kind) {
			case DART -> Fight.onUseItem(player, stack);
			case HEALING_SPRAY -> spray(player, stack);
			case MED_KIT -> startMedKit(player);
			case FLARE_GUN -> fireFlareGun(player, stack);
			default -> {
				if (kind.thrown()) {
					throwItem(player, stack, kind);
				}
			}
		}
	}

	// ---------------------------------------------------------------- throwing

	/** Throws one of what is in hand along the look direction. */
	public static void throwItem(LivingEntity thrower, ItemStack stack, HwItems.Kind kind) {
		ServerLevel level = (ServerLevel) thrower.level();
		HwConfig cfg = HwConfig.get();
		ItemStack one = stack.copyWithCount(1);
		Snowball ball = new Snowball(level, thrower, one);
		ball.addTag(THROW_TAG);
		float speed = kind == HwItems.Kind.PITCHFORK ? cfg.throwSpeed * 1.15f
			: kind == HwItems.Kind.FIRECRACKERS || kind == HwItems.Kind.ROAD_FLARE || kind == HwItems.Kind.HOURGLASS ? cfg.throwSpeed * 0.8f : cfg.throwSpeed;
		ball.shootFromRotation(thrower, thrower.getXRot(), thrower.getYRot(), 0f, speed, 1f);
		level.addFreshEntity(ball);
		FLYING.put(ball.getUUID(), new Flying(kind, one, thrower.getUUID()));
		level.playSound(null, thrower.getX(), thrower.getEyeY(), thrower.getZ(), SoundEvents.WITCH_THROW, SoundSource.PLAYERS, 0.8f, 0.8f);
		thrower.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
		stack.shrink(1);
		AiStats.add(thrower, "throw." + kind.key());
	}

	/** A thrown thing came down (called from the projectile). Returns false if it isn't ours. */
	public static boolean landed(Projectile projectile, HitResult hit) {
		Flying flying = FLYING.remove(projectile.getUUID());
		if (flying == null || !(projectile.level() instanceof ServerLevel level)) {
			return false;
		}
		HwConfig cfg = HwConfig.get();
		ServerPlayer thrower = level.getServer().getPlayerList().getPlayer(flying.thrower);
		Entity target = hit instanceof EntityHitResult eh ? eh.getEntity() : null;
		ServerPlayer shape = target instanceof ServerPlayer sp && MatchManager.role(sp) == Role.SHAPE ? sp : null;
		ShapeState state = shape == null ? null : MatchManager.shapeState(shape);
		if (state != null && state.shadowTicks > 0) {
			// Nothing there to hit while he is a shadow.
			shape = null;
			state = null;
		}
		Vec3 at = hit.getLocation();
		BlockPos spot = hit instanceof BlockHitResult bh ? bh.getBlockPos().relative(bh.getDirection()) : BlockPos.containing(at);
		ServerPlayer credit = thrower != null && !Bots.isBot(thrower) ? thrower : null;
		switch (flying.kind) {
			case FIRECRACKERS -> {
				BURNING.add(new Burning(null, at, flying.thrower, cfg.firecrackerFuseTicks, true));
				level.playSound(null, at.x, at.y, at.z, SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 0.6f, 1.6f);
			}
			case ROAD_FLARE -> light(level, spot, at, flying.thrower, cfg.roadFlareTicks);
			case FLARE_GUN -> {
				if (shape != null) {
					Fight.stun(shape, cfg.flareGunStun, credit);
					state.blindTicks = Math.max(state.blindTicks, cfg.flareGunBlindTicks);
					state.senseHeld = false;
					level.sendParticles(ParticleTypes.FLAME, shape.getX(), shape.getEyeY(), shape.getZ(), 20, 0.2, 0.2, 0.2, 0.02);
					AiStats.add(thrower, "flare.hit_shape");
				}
				light(level, spot, at, flying.thrower, cfg.flareGunBurnTicks);
			}
			case HOURGLASS -> {
				if (shape != null && state.stunnedTicks > 0) {
					// Thrown at him while he is down: he stays down longer.
					state.stunnedTicks += cfg.hourglassExtraTicks;
					shape.sendOverlayMessage(Component.translatable("halloween.msg.hourglass").withStyle(ChatFormatting.DARK_RED));
					if (thrower != null) {
						thrower.sendOverlayMessage(Component.translatable("halloween.msg.hourglass_hit").withStyle(ChatFormatting.GREEN));
					}
					AiStats.add(thrower, "hourglass.hit");
				}
				level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 1.4f);
				level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.05);
			}
			default -> {
				if (shape != null) {
					Fight.stun(shape, flying.kind.stun, credit);
					state.pushedTicks = Math.max(state.pushedTicks, cfg.throwStaggerTicks);
					level.playSound(null, shape.getX(), shape.getEyeY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.1f, 1.1f);
					AiStats.add(thrower, "throw.hit");
				} else {
					level.playSound(null, at.x, at.y, at.z, SoundEvents.STONE_HIT, SoundSource.PLAYERS, 0.8f, 1f);
				}
				if (flying.kind.melee()) {
					// A thrown blade or fork lies where it fell, a little more worn.
					ItemStack back = flying.stack.copy();
					if (!HwItems.wear(back)) {
						ItemEntity item = new ItemEntity(level, at.x, at.y + 0.1, at.z, back);
						item.setDeltaMovement(Vec3.ZERO);
						level.addFreshEntity(item);
					}
				}
			}
		}
		projectile.discard();
		return true;
	}

	/** A flare burning on the ground (or a flare gun's shot that came down): real light, smoke and sparks. */
	private static void light(ServerLevel level, BlockPos spot, Vec3 at, UUID thrower, int ticks) {
		BlockPos pos = level.getBlockState(spot).isAir() ? spot : level.getBlockState(spot.above()).isAir() ? spot.above() : null;
		if (pos != null) {
			level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), Block.UPDATE_CLIENTS);
		}
		BURNING.add(new Burning(pos, at, thrower, ticks, false));
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1f, 1.2f);
	}

	private static void removeLight(ServerLevel level, BlockPos pos) {
		if (pos != null && level.getBlockState(pos).is(Blocks.LIGHT)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private static void bang(ServerLevel level, Burning b) {
		HwConfig cfg = HwConfig.get();
		Vec3 at = b.at;
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 3f, 0.9f);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 2f, 1f);
		level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y + 0.3, at.z, 40, 0.3, 0.3, 0.3, 0.15);
		level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF0C0), true, false, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		ServerPlayer thrower = level.getServer().getPlayerList().getPlayer(b.thrower);
		ServerPlayer credit = thrower != null && !Bots.isBot(thrower) ? thrower : null;
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) != Role.SHAPE) {
				continue;
			}
			ShapeState state = MatchManager.shapeState(p);
			// The Shape hears where it went off.
			ServerPlayNetworking.send(p, new HwNet.Impulse(List.of(), List.of(at), cfg.firecrackerRevealTicks));
			if (state == null || state.shadowTicks > 0 || p.getEyePosition().distanceTo(at) > cfg.firecrackerRange
				|| !Sight.canSee(level, at.add(0, 0.3, 0), p.getEyePosition(), p)) {
				continue;
			}
			// Close by, the flash and the bang knock him off balance; looking at it, they blind him.
			Fight.stun(p, cfg.firecrackerStun, credit);
			if (Sight.angleTo(p.getEyePosition(), p.getViewVector(1f), at) <= 70) {
				state.blindTicks = Math.max(state.blindTicks, cfg.firecrackerBlindTicks);
				state.senseHeld = false;
			}
			AiStats.add(thrower, "firecracker.hit");
		}
		Townsfolk.hearNoise(level, at, level.getServer().getTickCount());
	}

	// ---------------------------------------------------------------- first aid

	/** One spray: on whoever we look at up close (survivors, townsfolk), else on ourselves. */
	private static void spray(ServerPlayer player, ItemStack stack) {
		HwConfig cfg = HwConfig.get();
		int now = player.level().getServer().getTickCount();
		if (now < NEXT_SPRAY.getOrDefault(player.getUUID(), 0)) {
			return;
		}
		LivingEntity target = lookedAtPatient(player);
		LivingEntity patient = target != null ? target : player;
		if (patient.getHealth() >= patient.getMaxHealth()) {
			player.sendOverlayMessage(Component.translatable("halloween.hint.healthy").withStyle(ChatFormatting.GRAY));
			return;
		}
		patient.setHealth(Math.min(patient.getMaxHealth(), patient.getHealth() + cfg.sprayHeal));
		NEXT_SPRAY.put(player.getUUID(), now + cfg.sprayCooldownTicks);
		ServerLevel level = player.level();
		level.playSound(null, patient.getX(), patient.getEyeY(), patient.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4f, 1.9f);
		level.sendParticles(ParticleTypes.CLOUD, patient.getX(), patient.getY() + 1, patient.getZ(), 6, 0.2, 0.3, 0.2, 0.01);
		HwItems.wear(stack);
		AiStats.add(player, patient == player ? "heal.spray_self" : "heal.spray_other");
	}

	/** A survivor or townsperson right in front within spraying reach, hurt. */
	private static LivingEntity lookedAtPatient(ServerPlayer player) {
		HwConfig cfg = HwConfig.get();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1f);
		LivingEntity best = null;
		double bestAngle = 25;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(cfg.sprayRange + 1),
			e -> e != player && MatchManager.isVictim(e) && !Lockers.isInside(e))) {
			Vec3 body = e.position().add(0, e.getBbHeight() * 0.6, 0);
			double angle = Sight.angleTo(eye, look, body);
			if (eye.distanceTo(body) <= cfg.sprayRange && angle < bestAngle && e.getHealth() < e.getMaxHealth()) {
				bestAngle = angle;
				best = e;
			}
		}
		return best;
	}

	private static void startMedKit(ServerPlayer player) {
		if (player.getHealth() >= player.getMaxHealth()) {
			player.sendOverlayMessage(Component.translatable("halloween.hint.healthy").withStyle(ChatFormatting.GRAY));
			return;
		}
		HEALING.put(player.getUUID(), new Channel(player.position(), player.getInventory().getSelectedSlot()));
		player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.8f, 1f);
	}

	/** Standing still with the med kit open (bots don't walk away from it either). */
	public static boolean healing(ServerPlayer player) {
		return HEALING.containsKey(player.getUUID());
	}

	// ---------------------------------------------------------------- flare gun

	private static void fireFlareGun(ServerPlayer player, ItemStack stack) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = player.level();
		if (player.getXRot() <= -cfg.flareSkyPitch) {
			// Up into the sky: a distress signal, as good as a call, and as useless without a body to report.
			if (!Exits.policeCallable()) {
				player.sendOverlayMessage(Component.translatable("halloween.hint.flare_no_body").withStyle(ChatFormatting.GRAY));
				return;
			}
			stack.shrink(1);
			Vec3 sky = player.getEyePosition().add(player.getViewVector(1f).scale(30));
			level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 3f, 0.8f);
			level.sendParticles(ParticleTypes.FIREWORK, true, true, sky.x, sky.y, sky.z, 60, 1.5, 1.5, 1.5, 0.05);
			level.sendParticles(ParticleTypes.FLAME, true, true, sky.x, sky.y, sky.z, 30, 0.6, 0.6, 0.6, 0.02);
			Match.score(player, cfg.scoreCallPolice, Component.translatable("halloween.score.police_flare"));
			Exits.callPolice(level, Component.translatable("halloween.match.police_flare"));
			AiStats.add(player, "flare.sky");
			return;
		}
		// Straight at something: a fast burning shot.
		ItemStack shot = HwItems.create(HwItems.Kind.ROAD_FLARE);
		Snowball ball = new Snowball(level, player, shot);
		ball.addTag(THROW_TAG);
		ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0f, 3.2f, 0.5f);
		level.addFreshEntity(ball);
		FLYING.put(ball.getUUID(), new Flying(HwItems.Kind.FLARE_GUN, shot, player.getUUID()));
		level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2f, 1.2f);
		stack.shrink(1);
		AiStats.add(player, "flare.shot");
	}

	// ---------------------------------------------------------------- grab and relics

	/** Has something to stab the Shape with and get free. */
	public static int stabSlot(ServerPlayer player) {
		for (HwItems.Kind kind : new HwItems.Kind[]{HwItems.Kind.POCKET_KNIFE, HwItems.Kind.BUTTERFLY_KNIFE, HwItems.Kind.ICE_PICK, HwItems.Kind.DART}) {
			int slot = Inventory.slotOf(player, kind);
			if (slot >= 0) {
				return slot;
			}
		}
		return -1;
	}

	/** Held by the Shape: stab him with a knife (or an ice pick, a lawn dart) and break free; the blade is gone. */
	public static boolean stab(ServerPlayer player) {
		int slot = stabSlot(player);
		GrabSession grab = MatchManager.grabHolding(player);
		if (slot < 0 || grab == null || !grab.struggling()) {
			return false;
		}
		HwItems.Kind kind = HwItems.kindOf(player.getInventory().getItem(slot));
		player.getInventory().getItem(slot).shrink(1);
		player.getInventory().setChanged();
		grab.stabbed();
		AiStats.add(player, "stab." + (kind == null ? "?" : kind.key()));
		return true;
	}

	/** The amulet takes a grab or a blow meant for its owner, blinds the Shape and crumbles. */
	public static boolean amuletSaves(LivingEntity victim, ServerPlayer shape) {
		if (!(victim instanceof ServerPlayer player) || !Inventory.has(player, HwItems.Kind.AMULET)) {
			return false;
		}
		HwConfig cfg = HwConfig.get();
		Inventory.consume(player, HwItems.Kind.AMULET);
		ShapeState state = MatchManager.shapeState(shape);
		if (state != null) {
			state.blindTicks = Math.max(state.blindTicks, cfg.amuletBlindTicks);
			state.recoverTicks = Math.max(state.recoverTicks, cfg.escapeShapeRecoverTicks);
			state.senseHeld = false;
		}
		ServerLevel level = player.level();
		level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.5f, 0.8f);
		level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getEyeY(), player.getZ(), 30, 0.3, 0.3, 0.3, 0.08);
		player.sendOverlayMessage(Component.translatable("halloween.msg.amulet").withStyle(ChatFormatting.GOLD));
		shape.sendOverlayMessage(Component.translatable("halloween.msg.amulet_shape").withStyle(ChatFormatting.DARK_RED));
		AiStats.add(player, "amulet.saved");
		return true;
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		ServerLevel level = server.overworld();
		HwConfig cfg = HwConfig.get();
		int now = server.getTickCount();
		FLYING.keySet().removeIf(id -> level.getEntity(id) == null);
		Iterator<Burning> it = BURNING.iterator();
		while (it.hasNext()) {
			Burning b = it.next();
			if (--b.ticks <= 0) {
				it.remove();
				if (b.bang) {
					bang(level, b);
				} else {
					removeLight(level, b.light);
				}
				continue;
			}
			if (b.bang) {
				if (b.ticks % 4 == 0) {
					level.sendParticles(ParticleTypes.SMOKE, b.at.x, b.at.y + 0.1, b.at.z, 2, 0.05, 0.05, 0.05, 0.01);
				}
				continue;
			}
			if (b.ticks % 3 == 0) {
				level.sendParticles(ParticleTypes.FLAME, b.at.x, b.at.y + 0.1, b.at.z, 2, 0.05, 0.05, 0.05, 0.02);
				level.sendParticles(ParticleTypes.SMOKE, b.at.x, b.at.y + 0.2, b.at.z, 1, 0.05, 0.1, 0.05, 0.01);
			}
			if (b.ticks % 30 == 0) {
				level.playSound(null, b.at.x, b.at.y, b.at.z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 0.8f, 1.3f);
			}
			glare(level, b.at, cfg.flareBlindRange);
		}
		tickMedKits(server, cfg);
		tickLanterns(server, cfg, now);
	}

	/** A bright light at {@code at}: the Shape near it is lit, and blinded looking into it. */
	private static void glare(ServerLevel level, Vec3 at, double range) {
		for (ServerPlayer p : level.players()) {
			ShapeState state = MatchManager.shapeState(p);
			if (state == null || p.isSpectator() || p.getEyePosition().distanceTo(at) > range || !Sight.canSee(level, at.add(0, 0.3, 0), p.getEyePosition(), p)) {
				continue;
			}
			state.litTicks = Math.max(state.litTicks, 3);
			if (Sight.angleTo(p.getEyePosition(), p.getViewVector(1f), at) <= 50) {
				state.blindTicks = Math.max(state.blindTicks, 3);
				state.senseHeld = false;
			}
		}
	}

	private static void tickMedKits(MinecraftServer server, HwConfig cfg) {
		Iterator<Map.Entry<UUID, Channel>> it = HEALING.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Channel> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			Channel c = e.getValue();
			boolean keep = player != null && Match.running() && !player.isSpectator() && !MatchManager.isGrabbed(player)
				&& player.getInventory().getSelectedSlot() == c.slot && HwItems.is(player.getMainHandItem(), HwItems.Kind.MED_KIT)
				&& player.position().distanceTo(c.at) < 0.5;
			if (!keep) {
				it.remove();
				if (player != null) {
					ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				}
				continue;
			}
			if (++c.ticks >= cfg.medKitTicks) {
				it.remove();
				player.setHealth(player.getMaxHealth());
				player.getMainHandItem().shrink(1);
				player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.3f, 1.6f);
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				AiStats.add(player, "heal.med_kit");
			} else if (c.ticks % 2 == 0) {
				ServerPlayNetworking.send(player, new HwNet.Progress("halloween.hud.progress.med_kit", c.ticks / (float) cfg.medKitTicks, false));
			}
		}
	}

	/** The brass lantern: light all round whoever carries it in hand; the Shape near it shows up on their screen. */
	private static void tickLanterns(MinecraftServer server, HwConfig cfg, int now) {
		ServerLevel level = server.overworld();
		Iterator<Map.Entry<UUID, BlockPos>> it = LANTERNS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, BlockPos> e = it.next();
			ServerPlayer holder = server.getPlayerList().getPlayer(e.getKey());
			if (holder == null || !holdsLantern(holder) || !holder.blockPosition().above().equals(e.getValue())) {
				removeLight(level, e.getValue());
				it.remove();
			}
		}
		for (ServerPlayer player : level.players()) {
			if (!holdsLantern(player)) {
				continue;
			}
			BlockPos head = player.blockPosition().above();
			if (!LANTERNS.containsKey(player.getUUID()) && level.getBlockState(head).isAir()) {
				level.setBlock(head, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, cfg.lanternLightLevel), Block.UPDATE_CLIENTS);
				LANTERNS.put(player.getUUID(), head.immutable());
			}
			glare(level, player.getEyePosition(), cfg.lanternBlindRange);
			if (now % 20 == 0) {
				ServerPlayer shape = Senses.shape(level);
				if (shape != null && shape.distanceTo(player) <= cfg.lanternRevealRange) {
					ServerPlayNetworking.send(player, new HwNet.Alert(shape.position(), 24));
				}
			}
		}
	}

	public static boolean holdsLantern(ServerPlayer player) {
		return Match.running() && MatchManager.role(player).survivor() && !player.isSpectator() && !Lockers.isInside(player)
			&& HwItems.is(player.getMainHandItem(), HwItems.Kind.BRASS_LANTERN);
	}
}
