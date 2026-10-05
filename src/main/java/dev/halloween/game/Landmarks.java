package dev.halloween.game;

import dev.halloween.net.HwNet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Names a spot the way a teammate would call it out: "by the car", "in a house", "on the street". */
public final class Landmarks {
	private Landmarks() {
	}

	public static Component describe(ServerLevel level, Vec3 pos) {
		String key = null;
		double best = 12;
		for (HwNet.ExitInfo exit : Exits.infos()) {
			double d = pos.distanceTo(Vec3.atCenterOf(exit.pos()));
			if (d < best) {
				best = d;
				key = switch (exit.type()) {
					case Exits.CELLAR -> "halloween.place.cellar";
					case Exits.CAR -> "halloween.place.car";
					case Exits.POLICE -> "halloween.place.police";
					default -> "halloween.place.gate";
				};
			}
		}
		for (BlockPos phone : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
			double d = pos.distanceTo(Vec3.atCenterOf(phone));
			if (d < Math.min(best, 6)) {
				best = d;
				key = "halloween.place.phone";
			}
		}
		if (key == null) {
			key = level.canSeeSky(BlockPos.containing(pos).above()) ? "halloween.place.street" : "halloween.place.house";
		}
		return Component.translatable(key);
	}
}
