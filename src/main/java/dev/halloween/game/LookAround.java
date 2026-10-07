package dev.halloween.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Someone standing about or hiding doesn't stare at one spot: now and then they turn their head, and their body
 * after it, to look somewhere else, without hurry. Hiding, they look about more often and quicker, mostly
 * towards where the danger was; in a locker only the head moves, as far as the slats let them see.
 */
public final class LookAround {
	private float yaw = Float.NaN;
	private float pitch;
	private int nextAt;
	/** For tests: how many times anyone turned to look somewhere new. */
	public static int turns;

	/** {@code locker}: the locker they are in, or null; {@code danger}: where they last knew the Shape was, or null. */
	void tick(LivingEntity body, int now, RandomSource random, boolean nervous, Vec3 danger, BlockPos locker) {
		float head = body.getYHeadRot();
		Float lockerYaw = null;
		if (locker != null && body.level() instanceof ServerLevel level) {
			BlockPos base = LockerBlock.base(level, locker);
			if (LockerBlock.is(level.getBlockState(base))) {
				lockerYaw = level.getBlockState(base).getValue(LockerBlock.FACING).toYRot();
			}
		}
		if (Float.isNaN(this.yaw) || now >= this.nextAt) {
			if (lockerYaw != null) {
				this.yaw = lockerYaw + (random.nextFloat() - 0.5f) * 60;
			} else if (nervous && danger != null && random.nextFloat() < 0.5f) {
				Vec3 d = danger.subtract(body.position());
				this.yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f + (random.nextFloat() - 0.5f) * 40;
			} else {
				// Mostly a glance to one side; now and then a look over the shoulder.
				this.yaw = head + (random.nextFloat() < 0.25f ? 180 : 0) + (random.nextFloat() - 0.5f) * 150;
			}
			this.pitch = (random.nextFloat() - 0.5f) * (nervous ? 16 : 24);
			this.nextAt = now + (nervous ? 25 + random.nextInt(45) : 50 + random.nextInt(110));
			turns++;
		}
		float turned = Mth.approachDegrees(head, this.yaw, nervous ? 10f : 5f);
		body.setYHeadRot(turned);
		if (lockerYaw == null) {
			body.setYRot(turned);
			body.setYBodyRot(turned);
		}
		body.setXRot(Mth.approach(body.getXRot(), this.pitch, 3f));
	}

	/** Moving on: the next stop starts with a fresh look. */
	void reset() {
		this.yaw = Float.NaN;
	}
}
