package dev.halloween.test;

import dev.halloween.client.ClientDarkness;
import dev.halloween.client.VrWrist;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The role's buttons on the wrist (client.VrWrist), drawn as the headset would show them over the free hand, here
 * hung before the camera (the test client has no Vivecraft): as they come up, one hovered by the other hand.
 */
public class VrWristClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-vr-wrist");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("vrwrist")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("time set 18500");
			server.runCommand("gamerule advance_time false");
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			server.runOnServer(s -> {
				ServerPlayer p = s.getPlayerList().getPlayers().getFirst();
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				run(s, "fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:dark_oak_planks");
				run(s, "fill " + (x - 8) + " 151 " + (z - 8) + " " + (x + 8) + " 156 " + (z + 8) + " minecraft:air");
				run(s, "fill " + (x - 8) + " 151 " + (z + 4) + " " + (x + 8) + " 154 " + (z + 4) + " minecraft:stripped_spruce_wood");
				p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
				p.teleportTo(s.overworld(), x + 0.5, 151, z + 0.5, java.util.Set.of(), 0f, 25f, false);
			});
			ctx.waitTicks(10);
			Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
			// Where it would hang over a left hand held up before the face, a little low and to the left.
			VrWrist.testAt = eye.add(-0.06, -0.22, 0.42);
			VrWrist.testHover = -1;
			ctx.waitTicks(3);
			ctx.takeScreenshot("vr_wrist");
			VrWrist.testHover = 1;
			ctx.waitTicks(3);
			ctx.takeScreenshot("vr_wrist_hover");
		} finally {
			VrWrist.testAt = null;
			VrWrist.testHover = -1;
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
		}
		LOG.info("VR wrist client test finished OK");
	}

	private static void run(MinecraftServer s, String c) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), c);
	}
}
