package dev.halloween.game;

import com.mojang.math.Transformation;
import dev.halloween.HwSounds;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The earthquake of the last minutes (Midnight, see Events): tremors every quarter of a minute or so, harder and
 * harder, each smashing a share of the town's windows and knocking a share of its doors off their hinges (they lie
 * on the floor), so that half a minute before the end nothing is left whole. The wardrobes jam: whoever is in one
 * tumbles out, nobody gets in. Each tremor: the screens shake, the ground rumbles, dust comes down from the ceilings
 * and the street crumbles underfoot. Doors and windows are back after the match (ExitProps, BrokenWindows), even
 * if the game was closed in the middle of it.
 */
final class Quake {
	static final String TAG = "hw_quake";

	private record Target(BlockPos pos, boolean door) {
	}

	private final List<Target> targets = new ArrayList<>();
	private final int start;
	private final int end;
	private int done;
	private int nextTremor;
	private int tremorUntil;

	private Quake(int start, int end) {
		this.start = start;
		this.end = end;
		this.nextTremor = start + 40;
	}

	/** It begins: every window and door in the match area listed, the wardrobes emptied. */
	static Quake begin(ServerLevel level, int now, int ticksLeft) {
		Quake q = new Quake(now, now + Math.max(20 * 60, ticksLeft - 20 * 30));
		BoundingBox area = Nav.area();
		if (area != null) {
			for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
				for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
					LevelChunk chunk = level.getChunk(cx, cz);
					for (int i = 0; i < chunk.getSections().length; i++) {
						LevelChunkSection section = chunk.getSections()[i];
						if (section.hasOnlyAir() || !section.maybeHas(s -> Windows.isWindow(s) || s.getBlock() instanceof DoorBlock)) {
							continue;
						}
						int y0 = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
						for (int ly = 0; ly < 16; ly++) {
							for (int lz = 0; lz < 16; lz++) {
								for (int lx = 0; lx < 16; lx++) {
									BlockState s = section.getBlockState(lx, ly, lz);
									BlockPos p = new BlockPos(chunk.getPos().getMinBlockX() + lx, y0 + ly, chunk.getPos().getMinBlockZ() + lz);
									if (!area.isInside(p.getX(), area.minY(), p.getZ()) || Exits.isProp(p)) {
										continue;
									}
									if (s.getBlock() instanceof DoorBlock && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
										q.targets.add(new Target(p, true));
									} else if (Windows.isWindow(s) && !Windows.isBrokenPane(s) && !Windows.isWindow(level.getBlockState(p.below()))) {
										// One per window column (the whole column goes at once).
										q.targets.add(new Target(p, false));
									}
								}
							}
						}
					}
				}
			}
		}
		Collections.shuffle(q.targets, new java.util.Random(level.getRandom().nextLong()));
		Lockers.evictAll();
		AiStats.add("*total", "quake.targets." + q.targets.size());
		return q;
	}

	void tick(ServerLevel level, int now) {
		if (now >= this.nextTremor) {
			float progress = Math.min(1f, (now - this.start) / (float) Math.max(1, this.end - this.start));
			boolean last = now >= this.end;
			int length = last ? 100 : 50 + level.getRandom().nextInt(30);
			float strength = last ? 1f : 0.35f + 0.65f * progress;
			this.tremorUntil = now + length;
			this.nextTremor = last ? Integer.MAX_VALUE : Math.min(this.end, now + 20 * (14 + level.getRandom().nextInt(10)));
			for (ServerPlayer p : level.players()) {
				ServerPlayNetworking.send(p, new HwNet.Quake(length, strength));
				p.connection.send(new ClientboundSoundPacket(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.WARDEN_EMERGE), SoundSource.AMBIENT, p.getX(), p.getY() - 6, p.getZ(),
					0.6f + 0.6f * strength, 0.5f, p.getRandom().nextLong()));
				if (strength > 0.6f) {
					p.connection.send(new ClientboundSoundPacket(SoundEvents.GENERIC_EXPLODE, SoundSource.AMBIENT, p.getX(), p.getY() - 10, p.getZ(),
						0.4f * strength, 0.35f, p.getRandom().nextLong()));
				}
			}
			AiStats.add("*total", "quake.tremor");
		}
		if (now >= this.tremorUntil) {
			return;
		}
		// What should be down by the end of this tremor: a steady share of it all, everything by the end.
		float by = Math.min(1f, (this.tremorUntil - this.start) / (float) Math.max(1, this.end - this.start));
		int want = now >= this.end ? this.targets.size() : Math.round(this.targets.size() * by);
		int ticksLeft = Math.max(1, this.tremorUntil - now);
		int now2 = (int) Math.ceil((want - this.done) / (double) ticksLeft);
		for (int k = 0; k < now2 && this.done < this.targets.size(); k++) {
			Target t = this.targets.get(this.done++);
			if (t.door) {
				this.knockOut(level, t.pos);
			} else if (Windows.isWindow(level.getBlockState(t.pos))) {
				Windows.breakColumn(level, t.pos);
			}
		}
		if (now % 4 == 0) {
			this.dust(level);
		}
	}

	/** A door off its hinges: gone from the doorway, lying on the floor across it. */
	private void knockOut(ServerLevel level, BlockPos lower) {
		BlockState state = level.getBlockState(lower);
		if (!(state.getBlock() instanceof DoorBlock)) {
			return;
		}
		BlockState upper = level.getBlockState(lower.above());
		DoorLocks.knockedOut(level, lower);
		Exits.removeForMatch(level, lower.above());
		Exits.removeForMatch(level, lower);
		Vec3 c = Vec3.atCenterOf(lower.above());
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), c.x, c.y - 0.5, c.z, 40, 0.4, 0.8, 0.4, 0.2);
		level.playSound(null, lower, HwSounds.DOOR_BREAK, SoundSource.BLOCKS, 2.2f, 0.8f);
		// The panel, both halves, fallen flat out of the doorway (drawn as block displays, see Events.clear).
		Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		float yaw = switch (facing) {
			case EAST -> -90f;
			case SOUTH -> 180f;
			case WEST -> 90f;
			default -> 0f;
		} + (level.getRandom().nextBoolean() ? 180f : 0f);
		Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(yaw)).rotateX((float) Math.toRadians(90));
		Vector3f pivot = new Vector3f(0.5f, 0f, 1f);
		Vector3f center = new Vector3f(0.5f, 0f, 0.5f);
		BlockState flat = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.OPEN, false);
		for (int half = 0; half < 2; half++) {
			// p' = Ry (Rx (p - pivot) + pivot - c) + c, p shifted up a block for the upper half.
			Vector3f t = new Quaternionf().rotateY((float) Math.toRadians(yaw)).transform(new Vector3f(pivot).sub(center)).add(center)
				.sub(turn.transform(new Vector3f(pivot).sub(0, half, 0)));
			Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level);
			d.setBlockState(half == 0 ? flat : (upper.getBlock() instanceof DoorBlock ? upper : flat.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.OPEN, false));
			d.setTransformation(new Transformation(t, new Quaternionf(turn), new Vector3f(1, 1, 1), new Quaternionf()));
			d.setPos(lower.getX(), lower.getY() + 0.01, lower.getZ());
			d.addTag(TAG);
			d.addTag(Leftovers.stamp());
			level.addFreshEntity(d);
		}
		AiStats.add("*total", "quake.door");
	}

	/** Dust down from the ceilings over everyone indoors; the ground crumbling under those out in the street. */
	private void dust(ServerLevel level) {
		var random = level.getRandom();
		for (ServerPlayer p : level.players()) {
			if (p.isSpectator()) {
				continue;
			}
			BlockPos head = p.blockPosition().above(2);
			BlockPos ceiling = null;
			for (int dy = 0; dy < 6; dy++) {
				if (!level.getBlockState(head.above(dy)).isAir()) {
					ceiling = head.above(dy);
					break;
				}
			}
			if (ceiling != null) {
				BlockState from = level.getBlockState(ceiling);
				level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, from.isAir() ? Blocks.STONE.defaultBlockState() : from),
					p.getX(), ceiling.getY() - 0.1, p.getZ(), 10, 3.5, 0.05, 3.5, 0);
			} else {
				BlockState ground = level.getBlockState(p.blockPosition().below());
				if (!ground.isAir()) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), p.getX(), p.getY() + 0.1, p.getZ(), 8, 3, 0.05, 3, 0.05);
				}
			}
			if (random.nextInt(5) == 0) {
				level.sendParticles(ParticleTypes.POOF, p.getX() + random.nextGaussian() * 4, p.getY() + 0.2, p.getZ() + random.nextGaussian() * 4, 4, 0.5, 0.1, 0.5, 0.01);
			}
		}
	}
}
