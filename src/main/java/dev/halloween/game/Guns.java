package dev.halloween.game;

import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Firearms: a three-shot revolver (police, deputies) and a single-shot bolt-action rifle (from the locked
 * gun case). A shot is instant along the line of fire, inside a cone that is wide from the hip, narrow
 * when aimed, and wider still on the move. The first body in the way takes the bullet: the Shape gets his
 * stun meter filled (the rifle drops him at once), anyone else is wounded. Shots are loud: the Shape sees
 * where they came from, townsfolk around are frightened. Reloading takes time: the revolver one round at a
 * time, the rifle a new round and the bolt worked.
 */
public final class Guns {
	private static final String LOADED = "hw_loaded";

	private static final class Reload {
		final HwItems.Kind kind;
		final int slot;
		int ticks;

		Reload(HwItems.Kind kind, int slot) {
			this.kind = kind;
			this.slot = slot;
		}
	}

	private static final Set<UUID> AIMING = new HashSet<>();
	/** Bots and officers with a gun up (entity id: the tick they last had it up), and who everyone was last told aims. */
	private static final Map<Integer, Integer> AIM_MARKS = new HashMap<>();
	private static final Set<Integer> TOLD_AIMING = new HashSet<>();
	private static final Map<UUID, Reload> RELOADS = new HashMap<>();
	private static final Map<UUID, Integer> NEXT_SHOT = new HashMap<>();

	private Guns() {
	}

	// ---------------------------------------------------------------- items

	public static int capacity(HwItems.Kind kind) {
		HwConfig cfg = HwConfig.get();
		return switch (kind) {
			case RIFLE -> cfg.rifleCapacity;
			case REVOLVER -> cfg.revolverCapacity;
			case SHOTGUN -> cfg.shotgunCapacity;
			default -> 0;
		};
	}

	public static HwItems.Kind ammoFor(HwItems.Kind gun) {
		return gun == HwItems.Kind.RIFLE ? HwItems.Kind.RIFLE_AMMO : gun == HwItems.Kind.SHOTGUN ? HwItems.Kind.SHOTGUN_SHELLS : HwItems.Kind.REVOLVER_AMMO;
	}

	/** Ticks before the next shot: the revolver's heavy trigger, the shotgun's pump, the rifle's bolt. */
	public static int shotTicks(HwItems.Kind kind) {
		HwConfig cfg = HwConfig.get();
		return kind == HwItems.Kind.REVOLVER ? cfg.revolverShotTicks : kind == HwItems.Kind.SHOTGUN ? cfg.shotgunPumpTicks : 12;
	}

	/** Revolver and shotgun load a round at a time (and can stop to fire); the rifle all at once. */
	private static boolean roundByRound(HwItems.Kind kind) {
		return kind != HwItems.Kind.RIFLE;
	}

	private static int roundTicks(HwItems.Kind kind) {
		HwConfig cfg = HwConfig.get();
		return kind == HwItems.Kind.SHOTGUN ? cfg.shotgunShellTicks : cfg.revolverRoundTicks;
	}

	public static int loaded(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? 0 : data.copyTag().getIntOr(LOADED, 0);
	}

	public static void setLoaded(ItemStack stack, int rounds) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
		tag.putInt(LOADED, rounds);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	private static HwItems.Kind gunIn(ItemStack stack) {
		HwItems.Kind kind = HwItems.kindOf(stack);
		return kind != null && kind.gun() ? kind : null;
	}

	// ---------------------------------------------------------------- player input

	public static boolean aiming(ServerPlayer player) {
		return AIMING.contains(player.getUUID());
	}

	public static boolean reloading(ServerPlayer player) {
		return RELOADS.containsKey(player.getUUID());
	}

	public static void setAiming(ServerPlayer player, boolean aim) {
		if (aim && canHandle(player) && gunIn(player.getMainHandItem()) != null) {
			AIMING.add(player.getUUID());
		} else {
			AIMING.remove(player.getUUID());
		}
	}

	private static boolean canHandle(ServerPlayer player) {
		return Match.running() && MatchManager.role(player).survivor() && !player.isSpectator() && !MatchManager.isGrabbed(player)
			&& !Windows.isVaulting(player) && !Lockers.isInside(player) && !Minigames.active(player);
	}

	/** The trigger, from the client, along the direction it was looking at that moment. */
	public static void fire(ServerPlayer player, float yaw, float pitch) {
		ItemStack gun = player.getMainHandItem();
		HwItems.Kind kind = gunIn(gun);
		if (kind == null || !canHandle(player)) {
			return;
		}
		int now = player.level().getServer().getTickCount();
		if (now < NEXT_SHOT.getOrDefault(player.getUUID(), 0)) {
			return;
		}
		Reload reload = RELOADS.get(player.getUUID());
		if (reload != null) {
			// A revolver can stop loading and fire what is in it; a rifle halfway through its reload can't.
			if (roundByRound(kind) && loaded(gun) > 0) {
				stopReload(player);
			} else {
				return;
			}
		}
		int rounds = loaded(gun);
		if (rounds <= 0) {
			player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), HwSounds.GUN_DRY, SoundSource.PLAYERS, 1f, 1f);
			NEXT_SHOT.put(player.getUUID(), now + 6);
			return;
		}
		setLoaded(gun, rounds - 1);
		HwConfig cfg = HwConfig.get();
		NEXT_SHOT.put(player.getUUID(), now + shotTicks(kind));
		// Trust the client's aim if it is close to where the server thinks the player looks (it is the newer of the two).
		Vec3 look = Vec3.directionFromRotation(pitch, yaw);
		if (Math.toDegrees(Math.acos(Mth.clamp(look.dot(player.getViewVector(1f)), -1, 1))) > 25) {
			look = player.getViewVector(1f);
		}
		AiStats.add(player, "shot." + kind.key());
		shoot(player, kind, player.getEyePosition(), look, spread(player, kind, aiming(player)), player);
	}

	/** R with a gun in hand: load what the pockets have. */
	public static void reload(ServerPlayer player) {
		ItemStack gun = player.getMainHandItem();
		HwItems.Kind kind = gunIn(gun);
		if (kind == null || !canHandle(player) || reloading(player) || loaded(gun) >= capacity(kind)) {
			return;
		}
		if (Inventory.count(player, ammoFor(kind)) <= 0) {
			player.sendOverlayMessage(Component.translatable("halloween.msg.no_ammo").withStyle(ChatFormatting.GRAY));
			player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), HwSounds.GUN_DRY, SoundSource.PLAYERS, 0.6f, 0.8f);
			return;
		}
		RELOADS.put(player.getUUID(), new Reload(kind, player.getInventory().getSelectedSlot()));
		if (kind == HwItems.Kind.RIFLE) {
			// Bolt back.
			player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), HwSounds.GUN_BOLT, SoundSource.PLAYERS, 0.8f, 1.2f);
		}
	}

	private static void stopReload(ServerPlayer player) {
		if (RELOADS.remove(player.getUUID()) != null) {
			ServerPlayNetworking.send(player, HwNet.Progress.NONE);
		}
	}

	public static void tick(MinecraftServer server) {
		HwConfig cfg = HwConfig.get();
		AIMING.removeIf(id -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			return p == null || !canHandle(p) || gunIn(p.getMainHandItem()) == null;
		});
		tellPoses(server);
		Iterator<Map.Entry<UUID, Reload>> it = RELOADS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Reload> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			Reload r = e.getValue();
			ItemStack gun = player == null ? ItemStack.EMPTY : player.getMainHandItem();
			if (player == null || !canHandle(player) || player.getInventory().getSelectedSlot() != r.slot || gunIn(gun) != r.kind) {
				it.remove();
				if (player != null) {
					ServerPlayNetworking.send(player, HwNet.Progress.NONE);
				}
				continue;
			}
			HwItems.Kind ammo = ammoFor(r.kind);
			int cap = capacity(r.kind);
			r.ticks++;
			boolean done = false;
			if (roundByRound(r.kind)) {
				if (r.ticks >= roundTicks(r.kind)) {
					r.ticks = 0;
					if (Inventory.count(player, ammo) > 0 && loaded(gun) < cap) {
						Inventory.consume(player, ammo);
						setLoaded(gun, loaded(gun) + 1);
						player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), HwSounds.GUN_LOAD_ROUND, SoundSource.PLAYERS, 0.7f, 1f);
					}
					done = loaded(gun) >= cap || Inventory.count(player, ammo) <= 0;
				}
			} else if (r.ticks >= cfg.rifleReloadTicks) {
				if (Inventory.count(player, ammo) > 0) {
					Inventory.consume(player, ammo);
					setLoaded(gun, cap);
				}
				// Bolt home.
				player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), HwSounds.GUN_BOLT, SoundSource.PLAYERS, 0.9f, 0.8f);
				done = true;
			}
			if (done) {
				it.remove();
				ServerPlayNetworking.send(player, HwNet.Progress.NONE);
			} else if (r.ticks % 2 == 0) {
				float frac = roundByRound(r.kind)
					? (loaded(gun) + r.ticks / (float) roundTicks(r.kind)) / cap
					: r.ticks / (float) cfg.rifleReloadTicks;
				ServerPlayNetworking.send(player, new HwNet.Progress("halloween.hud.progress.reload", frac, false));
			}
		}
	}

	/** A bot or an officer has his gun up this tick (seen so by everyone: HwNet.GunPose). */
	public static void markAiming(LivingEntity who) {
		if (who.level() instanceof ServerLevel level) {
			AIM_MARKS.put(who.getId(), level.getServer().getTickCount());
		}
	}

	/** Everyone is told who raised a gun or lowered it. */
	private static void tellPoses(MinecraftServer server) {
		int now = server.getTickCount();
		Set<Integer> aiming = new HashSet<>();
		for (UUID id : AIMING) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				aiming.add(p.getId());
			}
		}
		AIM_MARKS.values().removeIf(at -> now - at > 2);
		aiming.addAll(AIM_MARKS.keySet());
		for (int id : aiming) {
			if (!TOLD_AIMING.contains(id)) {
				tellAll(server, new HwNet.GunPose(id, HwNet.GunPose.AIMED));
			}
		}
		for (int id : TOLD_AIMING) {
			if (!aiming.contains(id)) {
				tellAll(server, new HwNet.GunPose(id, HwNet.GunPose.LOWERED));
			}
		}
		TOLD_AIMING.clear();
		TOLD_AIMING.addAll(aiming);
	}

	private static void tellAll(MinecraftServer server, HwNet.GunPose pose) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) {
				ServerPlayNetworking.send(p, pose);
			}
		}
	}

	public static void clear() {
		AIMING.clear();
		AIM_MARKS.clear();
		TOLD_AIMING.clear();
		RELOADS.clear();
		NEXT_SHOT.clear();
	}

	// ---------------------------------------------------------------- the shot

	/** Cone of fire in degrees for this shooter right now. */
	public static float spread(LivingEntity shooter, HwItems.Kind kind, boolean aimed) {
		HwConfig cfg = HwConfig.get();
		float spread = switch (kind) {
			case RIFLE -> aimed ? cfg.rifleAimSpread : cfg.rifleHipSpread;
			case SHOTGUN -> aimed ? cfg.shotgunAimSpread : cfg.shotgunHipSpread;
			default -> aimed ? cfg.revolverAimSpread : cfg.revolverHipSpread;
		};
		double speed = shooter.getKnownMovement().horizontalDistance();
		if (shooter.isSprinting()) {
			spread += cfg.runSpread;
		} else if (speed > 0.03) {
			spread += cfg.moveSpread;
		}
		// A panicking hand shakes.
		spread += Panic.value(shooter) * cfg.panicSpread;
		return spread;
	}

	/** {@code look} turned by a random angle inside a cone of {@code spreadDeg}, evenly over the cone's face. */
	static Vec3 cone(Vec3 look, float spreadDeg, RandomSource random) {
		if (spreadDeg <= 0) {
			return look;
		}
		double r = Math.tan(Math.toRadians(spreadDeg)) * Math.sqrt(random.nextDouble());
		double a = random.nextDouble() * Math.PI * 2;
		Vec3 side = look.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 up = side.cross(look).normalize();
		return look.add(side.scale(Math.cos(a) * r)).add(up.scale(Math.sin(a) * r)).normalize();
	}

	/** Can a bullet stop in this body? */
	private static boolean shootable(Entity e, LivingEntity shooter) {
		if (e == shooter || !(e instanceof LivingEntity living) || !living.isAlive() || e.isSpectator() || Lockers.isInside(e)) {
			return false;
		}
		if (e instanceof ServerPlayer player) {
			Role role = MatchManager.role(player);
			if (role == Role.SHAPE) {
				ShapeState state = MatchManager.shapeState(player);
				// In Shape Jump there is nothing there to hit.
				return state == null || state.shadowTicks == 0;
			}
			return role != Role.NONE;
		}
		return MatchManager.isVictim(e);
	}

	/**
	 * Nothing but air between the muzzle and {@code target}: no wall, and nobody else within an arm's
	 * width of the line (police and bots hold fire rather than shoot through people).
	 */
	public static boolean lineClear(ServerLevel level, LivingEntity shooter, Vec3 from, Vec3 to, Entity target) {
		if (!Sight.canSee(level, from, to, shooter)) {
			return false;
		}
		for (Entity e : level.getEntities(shooter, new AABB(from, to).inflate(1.0), e -> e != target && shootable(e, shooter))) {
			if (e.getBoundingBox().inflate(0.6).clip(from, to).isPresent()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Fires one round from {@code eye} towards {@code look} with the given cone. Returns who was hit, or
	 * null. {@code credit} gets the score for hitting the Shape (players only).
	 */
	public static LivingEntity shoot(LivingEntity shooter, HwItems.Kind kind, Vec3 eye, Vec3 look, float spreadDeg, ServerPlayer credit) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = (ServerLevel) shooter.level();
		LivingEntity first = null;
		// Everyone sees the kick.
		tellAll(level.getServer(), new HwNet.GunPose(shooter.getId(), HwNet.GunPose.SHOT));
		if (kind == HwItems.Kind.SHOTGUN) {
			// A spread of pellets: up close nearly all of them land, each worth a little; far off they fade to nothing.
			float stun = 0f;
			ServerPlayer shapeHit = null;
			for (int i = 0; i < cfg.shotgunPellets; i++) {
				Hit hit = bullet(shooter, kind, eye, cone(look.normalize(), spreadDeg, level.getRandom()), i == 0);
				if (hit == null) {
					continue;
				}
				first = first == null ? hit.entity : first;
				double force = 1 - Mth.clamp((hit.distance - cfg.shotgunFullForce) / Math.max(1, cfg.shotgunRange - cfg.shotgunFullForce), 0, 1);
				if (hit.entity instanceof ServerPlayer shape && MatchManager.role(shape) == Role.SHAPE) {
					shapeHit = shape;
					stun += (float) (cfg.shotgunPelletStun * force);
				} else if (hit.entity instanceof ServerPlayer runt && MatchManager.role(runt) == Role.RUNT) {
					Runt.shot(runt, shooter instanceof ServerPlayer sp ? sp : null);
				} else if (force > 0 && hit.entity.isAlive()) {
					wound(hit.entity, (float) (cfg.shotgunPelletDamage * force));
				}
			}
			if (shapeHit != null && stun > 0) {
				AiStats.add(shooter, "shot.hit_shape");
				Fight.stun(shapeHit, stun, credit);
				Bots.alert(shapeHit.position(), level.getServer().getTickCount());
			} else if (first != null) {
				AiStats.add(shooter, "shot.hit_friend");
			}
		} else {
			Hit hit = bullet(shooter, kind, eye, cone(look.normalize(), spreadDeg, level.getRandom()), true);
			if (hit != null) {
				first = hit.entity;
				if (hit.entity instanceof ServerPlayer shape && MatchManager.role(shape) == Role.SHAPE) {
					AiStats.add(shooter, "shot.hit_shape");
					Fight.stun(shape, kind == HwItems.Kind.RIFLE ? cfg.rifleStun : cfg.revolverStun, credit);
					Bots.alert(shape.position(), level.getServer().getTickCount());
				} else if (hit.entity instanceof ServerPlayer runt && MatchManager.role(runt) == Role.RUNT) {
					// A shot floors the little one for a good while.
					AiStats.add(shooter, "shot.hit_runt");
					Runt.shot(runt, shooter instanceof ServerPlayer sp ? sp : null);
				} else {
					AiStats.add(shooter, "shot.hit_friend");
					wound(hit.entity, kind == HwItems.Kind.RIFLE ? cfg.rifleDamage : cfg.revolverDamage);
				}
			}
		}

		// Bang: the shot, the crack, the flash and smoke at the muzzle.
		Vec3 muzzle = eye.add(look.scale(0.9)).add(0, -0.15, 0);
		level.playSound(null, muzzle.x, muzzle.y, muzzle.z, kind == HwItems.Kind.REVOLVER ? HwSounds.GUN_REVOLVER : HwSounds.GUN_RIFLE,
			SoundSource.PLAYERS, cfg.gunshotVolume, (kind == HwItems.Kind.SHOTGUN ? 0.75f : 0.95f) + level.getRandom().nextFloat() * 0.1f);
		level.playSound(null, muzzle.x, muzzle.y, muzzle.z, HwSounds.GUN_CRACK, SoundSource.PLAYERS, cfg.gunshotVolume * 0.6f, kind == HwItems.Kind.SHOTGUN ? 0.7f : 1f);
		if (kind == HwItems.Kind.SHOTGUN) {
			// The pump racked for the next shell.
			level.playSound(null, muzzle.x, muzzle.y, muzzle.z, HwSounds.GUN_BOLT, SoundSource.PLAYERS, 0.8f, 1.5f);
		}
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFE0A0), true, false, muzzle.x, muzzle.y, muzzle.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.SMOKE, true, false, muzzle.x, muzzle.y, muzzle.z, kind == HwItems.Kind.SHOTGUN ? 12 : 6, 0.04, 0.04, 0.04, 0.01);

		// Everyone hears it: the Shape sees where it came from, townsfolk around take fright.
		int now = level.getServer().getTickCount();
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE && p != shooter) {
				ServerPlayNetworking.send(p, new HwNet.Impulse(List.of(), List.of(shooter.position()), cfg.gunshotRevealTicks));
			}
		}
		Townsfolk.hearNoise(level, shooter.position(), now);
		return first;
	}

	private record Hit(LivingEntity entity, double distance) {
	}

	/** One bullet or pellet along {@code dir}: through glass, into the first body or wall. */
	private static Hit bullet(LivingEntity shooter, HwItems.Kind kind, Vec3 eye, Vec3 dir, boolean loud) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = (ServerLevel) shooter.level();
		double range = kind == HwItems.Kind.RIFLE ? cfg.rifleRange : kind == HwItems.Kind.SHOTGUN ? cfg.shotgunRange : cfg.revolverRange;
		Vec3 end = eye.add(dir.scale(range));
		// A window doesn't stop a bullet: the glass shatters and it flies on.
		Vec3 from = eye;
		BlockHitResult block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
		for (int pass = 0; pass < 4 && block.getType() != HitResult.Type.MISS; pass++) {
			BlockState glass = level.getBlockState(block.getBlockPos());
			if (!Windows.isWindow(glass)) {
				break;
			}
			if (!Windows.isBrokenPane(glass)) {
				Windows.breakColumn(level, block.getBlockPos());
			}
			from = block.getLocation().add(dir.scale(0.3));
			block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
		}
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		LivingEntity hit = null;
		Vec3 hitAt = null;
		double best = Double.MAX_VALUE;
		List<Entity> near = level.getEntities(shooter, new AABB(eye, stop).inflate(1.0), e -> shootable(e, shooter));
		for (Entity e : near) {
			AABB box = e.getBoundingBox().inflate(0.05);
			if (box.contains(eye)) {
				continue;
			}
			Optional<Vec3> at = box.clip(eye, stop);
			if (at.isPresent() && at.get().distanceTo(eye) < best) {
				best = at.get().distanceTo(eye);
				hit = (LivingEntity) e;
				hitAt = at.get();
			}
		}

		if (hit != null) {
			level.sendParticles(ParticleTypes.CRIT, hitAt.x, hitAt.y, hitAt.z, 6, 0.05, 0.05, 0.05, 0.2);
			MatchManager.bleed(level, hit, kind == HwItems.Kind.RIFLE ? 30 : kind == HwItems.Kind.SHOTGUN ? 6 : 16);
			return new Hit(hit, hitAt.distanceTo(eye));
		} else if (block.getType() != HitResult.Type.MISS) {
			// Splinters and dust where it struck.
			BlockState state = level.getBlockState(block.getBlockPos());
			Vec3 at = block.getLocation();
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 10, 0.05, 0.05, 0.05, 0.15);
			level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 2, 0.02, 0.02, 0.02, 0.01);
			if (loud) {
				level.playSound(null, at.x, at.y, at.z, HwSounds.GUN_RICOCHET, SoundSource.BLOCKS, 0.7f, 1f);
			}
		}
		return null;
	}

	/** A bullet in someone who isn't the Shape: it hurts, and can kill (leaving a body like any death). */
	private static void wound(LivingEntity target, float damage) {
		ServerLevel level = (ServerLevel) target.level();
		if (target.getHealth() - damage <= 0) {
			Voices.play(target, Voices.Kind.DEATH);
			MatchManager.shotDead(target);
			return;
		}
		Voices.play(target, Voices.Kind.HURT);
		target.invulnerableTime = 0;
		target.hurtServer(level, level.damageSources().generic(), damage);
		target.setDeltaMovement(Vec3.ZERO);
		target.hurtMarked = true;
	}
}
