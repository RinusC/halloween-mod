package dev.halloween.client;

import dev.halloween.net.HwNet;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The match's side jobs as the survivors see them (game.SideJobs): on the goal list, and what the radio is. */
public final class ClientJobs {
	private static HwNet.SideJobs jobs = new HwNet.SideJobs(List.of(), List.of(), List.of());

	private ClientJobs() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HwNet.SideJobs.TYPE, (payload, ctx) -> jobs = payload);
	}

	public static void reset() {
		jobs = new HwNet.SideJobs(List.of(), List.of(), List.of());
	}

	/** The radio still to tune, at this block? */
	public static boolean isRadio(BlockPos pos) {
		int i = jobs.jobs().indexOf(dev.halloween.game.SideJobs.RADIO);
		return i >= 0 && !jobs.done().get(i) && BlockPos.containing(jobs.where().get(i)).equals(pos);
	}

	/** The dog still on its chain? */
	public static boolean dogWaiting() {
		int i = jobs.jobs().indexOf(dev.halloween.game.SideJobs.DOG);
		return i >= 0 && !jobs.done().get(i);
	}

	/** Lines for the goal list: each job, ticked off when done. */
	static int count() {
		return jobs.jobs().size();
	}

	static Component line(int i) {
		return Component.translatable("halloween.job." + jobs.jobs().get(i));
	}

	static boolean done(int i) {
		return jobs.done().get(i);
	}
}
