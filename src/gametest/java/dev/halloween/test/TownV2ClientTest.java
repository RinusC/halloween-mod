package dev.halloween.test;

import dev.halloween.game.MapPoints;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.phys.AABB;

/**
 * Town V2 (/hw map load town_v2): the town built up by hand and shipped with the mod as a file. The same checks
 * as the generated town, and its paintings and item frames are back in place.
 */
public class TownV2ClientTest extends TownClientTest {
	public TownV2ClientTest() {
		super("townv2", "hw map load town_v2", "townv2");
	}

	@Override
	protected void more(TestServerContext server) {
		int[] counts = server.computeOnServer(s -> {
			BlockPos c = MapPoints.get(s.overworld()).first(MapPoints.Type.CENTER);
			AABB box = new AABB(c).inflate(160, 50, 160);
			int frames = s.overworld().getEntities((Entity) null, box, e -> e instanceof ItemFrame).size();
			int paintings = s.overworld().getEntities((Entity) null, box, e -> e instanceof Painting).size();
			int animals = s.overworld().getEntities((Entity) null, box, e -> e instanceof Animal).size();
			return new int[]{frames, paintings, animals};
		});
		check(counts[0] == 36 && counts[1] == 10, "the item frames and paintings are back (" + counts[0] + "/36, " + counts[1] + "/10)");
		// The animals the flat world had put about the streets were left out of the file.
		check(counts[2] == 0, "no animals wander the streets (" + counts[2] + ")");
	}
}
