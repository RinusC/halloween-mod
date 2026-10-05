package dev.halloween.game;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a body along a {@link Nav} path the way a player would: turns towards the next point, walks
 * (sets the movement input; physics do the rest), jumps up steps, opens doors in the way and shuts
 * them behind. Window climbs are handed back to the owner.
 */
public final class Walker {
	public enum Status {
		IDLE, MOVING, ARRIVED, STUCK, WINDOW
	}

	private final LivingEntity body;
	private List<Nav.Node> path = List.of();
	private int index;
	private int stuckTicks;
	private Vec3 lastPos = Vec3.ZERO;
	private BlockPos doorToClose;
	private int doorCloseDelay;
	public boolean closeDoors = true;
	public float speed = 1f;
	private BlockPos passedDoor;

	public Walker(LivingEntity body) {
		this.body = body;
	}

	public void follow(List<Nav.Node> path) {
		this.path = path;
		this.passedDoor = null;
		this.index = 0;
		this.stuckTicks = 0;
	}

	public void stop() {
		this.path = List.of();
		this.index = 0;
		this.halt();
	}

	public boolean idle() {
		return this.index >= this.path.size();
	}

	public BlockPos destination() {
		return this.path.isEmpty() ? null : this.path.get(this.path.size() - 1).pos();
	}

	/** The window the path wants to climb now (status WINDOW). */
	public Nav.Node current() {
		return this.index < this.path.size() ? this.path.get(this.index) : null;
	}

	/** After the owner climbed the window, carry on from the far side. */
	public void skip() {
		this.index++;
	}

	/** The door just walked through (once), so the owner can lock it behind. */
	public BlockPos takePassedDoor() {
		BlockPos door = this.passedDoor;
		this.passedDoor = null;
		return door;
	}

	public void halt() {
		this.body.zza = 0;
		this.body.xxa = 0;
		this.body.setJumping(false);
	}

	public Status tick() {
		ServerLevel level = (ServerLevel) this.body.level();
		this.tickDoor(level);
		if (this.index >= this.path.size()) {
			this.halt();
			return this.path.isEmpty() ? Status.IDLE : Status.ARRIVED;
		}
		Nav.Node node = this.path.get(this.index);
		if (node.step() == Nav.Step.WINDOW) {
			this.halt();
			this.face(Vec3.atCenterOf(node.glass()));
			return Status.WINDOW;
		}
		Vec3 pos = this.body.position();
		Vec3 target = Vec3.atBottomCenterOf(node.pos());
		double dx = target.x - pos.x;
		double dz = target.z - pos.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		boolean last = this.index == this.path.size() - 1;
		double reach = last ? 0.35 : 0.55;
		if (flat < reach && Math.abs(target.y - pos.y) < 1.2) {
			if (this.index > 0 && this.path.get(this.index - 1).step() == Nav.Step.DOOR && node.step() != Nav.Step.DOOR) {
				this.passedDoor = this.path.get(this.index - 1).pos();
			}
			this.index++;
			this.stuckTicks = 0;
			return this.index >= this.path.size() ? Status.ARRIVED : Status.MOVING;
		}

		// A door on the way: open it when close.
		BlockPos doorPos = node.pos();
		BlockState state = level.getBlockState(doorPos);
		if (state.getBlock() instanceof DoorBlock door && flat < 2.2) {
			if (!door.isOpen(state)) {
				door.setOpen(this.body, level, state, doorPos, true);
			}
			this.doorToClose = DoorLocks.base(level, doorPos);
			this.doorCloseDelay = -1;
		} else if (this.doorToClose != null && this.doorCloseDelay < 0) {
			this.doorCloseDelay = 12;
		}

		if (!(this.body instanceof Player)) {
			// Players read their speed from the attribute; other living things only from this field.
			this.body.setSpeed((float) this.body.getAttributeValue(Attributes.MOVEMENT_SPEED));
		}
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		this.body.setYRot(yaw);
		this.body.setYHeadRot(yaw);
		this.body.setYBodyRot(yaw);
		this.body.setXRot(0);
		// Slow down for tight turns near the point to avoid orbiting it.
		this.body.zza = (float) Math.min(1.0, 0.35 + flat) * this.speed;
		this.body.xxa = 0;
		boolean up = target.y > pos.y + 0.5;
		this.body.setJumping((up && flat < 1.6) || (this.body.horizontalCollision && this.stuckTicks > 4));

		if (pos.distanceToSqr(this.lastPos) < 0.0009) {
			this.stuckTicks++;
		} else {
			this.stuckTicks = Math.max(0, this.stuckTicks - 1);
		}
		this.lastPos = pos;
		if (this.stuckTicks > 50) {
			this.halt();
			return Status.STUCK;
		}
		return Status.MOVING;
	}

	private void tickDoor(ServerLevel level) {
		if (this.doorToClose == null || this.doorCloseDelay < 0) {
			return;
		}
		if (--this.doorCloseDelay > 0) {
			return;
		}
		BlockState state = level.getBlockState(this.doorToClose);
		boolean clear = this.body.position().distanceTo(Vec3.atBottomCenterOf(this.doorToClose)) > 1.4;
		if (state.getBlock() instanceof DoorBlock door && door.isOpen(state)) {
			if (!clear) {
				this.doorCloseDelay = 4;
				return;
			}
			if (this.closeDoors) {
				door.setOpen(this.body, level, state, this.doorToClose, false);
			}
		}
		this.doorToClose = null;
	}

	public void face(Vec3 point) {
		Vec3 eye = this.body.getEyePosition();
		Vec3 d = point.subtract(eye);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		this.body.setYRot(yaw);
		this.body.setYHeadRot(yaw);
		this.body.setYBodyRot(yaw);
		this.body.setXRot(pitch);
	}
}
