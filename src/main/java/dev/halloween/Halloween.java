package dev.halloween;

import dev.halloween.game.DoorLocks;
import dev.halloween.game.HwCommands;
import dev.halloween.game.LightSystem;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Windows;
import dev.halloween.net.HwNet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Halloween implements ModInitializer {
	public static final String MOD_ID = "halloween";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		HwConfig.load();
		HwSounds.init();
		HwBlocks.init();
		HwParticles.init();
		HwNet.registerPayloads();

		ServerPlayNetworking.registerGlobalReceiver(HwNet.SenseInput.TYPE, (payload, ctx) -> {
			var state = MatchManager.shapeState(ctx.player());
			if (state != null) {
				state.senseHeld = payload.sense();
				state.focusHeld = payload.focus();
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(HwNet.UseAction.TYPE, (payload, ctx) -> {
			switch (payload.kind()) {
				case HwNet.UseAction.DOOR_LOCK -> DoorLocks.ping(ctx.player(), payload.pos());
				case HwNet.UseAction.VAULT_SLOW -> Windows.request(ctx.player(), payload.pos(), false);
				case HwNet.UseAction.VAULT_FAST -> Windows.request(ctx.player(), payload.pos(), true);
				case HwNet.UseAction.INTERACT -> dev.halloween.game.Holds.ping(ctx.player(), payload.pos());
				case HwNet.UseAction.LOCKER -> dev.halloween.game.Lockers.use(ctx.player(), payload.pos());
				default -> {
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(HwNet.NpcTalk.TYPE, (payload, ctx) -> dev.halloween.game.Talks.request(ctx.player(), payload.entityId(), payload.option()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.Aim.TYPE, (payload, ctx) -> MatchManager.onAim(ctx.player(), payload.origin(), payload.direction()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.Grab.TYPE, (payload, ctx) -> MatchManager.onGrab(ctx.player(), payload.targetId()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.ShapeAttack.TYPE, (payload, ctx) -> MatchManager.onShapeAttack(ctx.player(), payload.targetId(), payload.heavy()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.ActionPress.TYPE, (payload, ctx) -> {
			switch (payload.action()) {
				case SHAPE_JUMP -> MatchManager.onShapeJump(ctx.player());
				case DASH -> MatchManager.onDash(ctx.player());
				case PUSH -> MatchManager.onPush(ctx.player());
				case IMPULSE -> MatchManager.onImpulse(ctx.player());
				case QTE_PRESS -> MatchManager.onQtePress(ctx.player());
				case FLASHLIGHT -> dev.halloween.game.Fight.toggleFlashlight(ctx.player());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(MatchManager::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			MatchManager.markDirty();
			DoorLocks.syncTo(handler.player);
			dev.halloween.game.Match.markDirty();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(dev.halloween.game.Match::stop);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> MatchManager.clearAll());

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> MatchManager.onVanillaAttack(player, entity));
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> level.isClientSide()
			? net.minecraft.world.InteractionResult.PASS : dev.halloween.game.Fight.onUseItem(player, player.getItemInHand(hand)));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> MatchManager.onUseEntity(player, hand, entity));
		UseBlockCallback.EVENT.register(LightSystem::onUseBlock);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide()) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			// Exit hatches and gates open only through the exits' own actions.
			if (dev.halloween.game.Exits.isProp(hit.getBlockPos())) {
				return net.minecraft.world.InteractionResult.FAIL;
			}
			// Civilians' window climbing arrives as its own packet; the vanilla click does nothing.
			if (player instanceof net.minecraft.server.level.ServerPlayer sp && MatchManager.role(sp).survivor()
				&& Windows.isWindow(level.getBlockState(hit.getBlockPos()))) {
				return net.minecraft.world.InteractionResult.FAIL;
			}
			// Match containers are searched by holding use, never opened.
			if (player instanceof net.minecraft.server.level.ServerPlayer sp2 && MatchManager.role(sp2) != Role.NONE
				&& (dev.halloween.game.Loot.isContainer(hit.getBlockPos()) || level.getBlockState(hit.getBlockPos()).is(HwBlocks.PHONE))) {
				return net.minecraft.world.InteractionResult.FAIL;
			}
			return DoorLocks.onUseBlock(player, level, hit.getBlockPos());
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> MatchManager.allowDamage(entity, source));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> MatchManager.allowDeath(entity));

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> HwCommands.register(dispatcher));
		LOGGER.info("Halloween loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
