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
		ServerPlayNetworking.registerGlobalReceiver(HwNet.VecnaAnchorKey.TYPE, (payload, ctx) -> dev.halloween.game.Vecna.anchorKey(ctx.player(), payload.held()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.UseAction.TYPE, (payload, ctx) -> {
			switch (payload.kind()) {
				case HwNet.UseAction.DOOR_LOCK -> DoorLocks.ping(ctx.player(), payload.pos());
				case HwNet.UseAction.VAULT_SLOW -> Windows.request(ctx.player(), payload.pos(), false);
				case HwNet.UseAction.VAULT_FAST -> Windows.request(ctx.player(), payload.pos(), true);
				case HwNet.UseAction.INTERACT -> dev.halloween.game.Holds.ping(ctx.player(), payload.pos());
				case HwNet.UseAction.LOCKER -> dev.halloween.game.Lockers.use(ctx.player(), payload.pos());
				case HwNet.UseAction.RUNT_MISCHIEF -> dev.halloween.game.Runt.mischief(ctx.player(), payload.pos());
				case HwNet.UseAction.RUNT_KNOCK -> dev.halloween.game.Runt.knock(ctx.player(), payload.pos());
				default -> {
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(HwNet.RuntOrder.TYPE, (payload, ctx) -> dev.halloween.game.Runt.order(ctx.player(), payload.kind(), payload.targetId(), payload.pos()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.RuntPick.TYPE, (payload, ctx) -> dev.halloween.game.Runt.pick(ctx.player(), payload.level(), payload.option()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.PerkAction.TYPE, (payload, ctx) -> dev.halloween.game.Perks.act(ctx.player(), payload.perk(), payload.action()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.NpcTalk.TYPE, (payload, ctx) -> dev.halloween.game.Talks.request(ctx.player(), payload.entityId(), payload.option()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.MinigameResult.TYPE, (payload, ctx) -> dev.halloween.game.Minigames.result(ctx.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.Ping.TYPE, (payload, ctx) -> dev.halloween.game.Pings.onPing(ctx.player(), payload.pos(), payload.kind(), payload.entityId()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.GunFire.TYPE, (payload, ctx) -> dev.halloween.game.Guns.fire(ctx.player(), payload.yaw(), payload.pitch()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.GunAim.TYPE, (payload, ctx) -> dev.halloween.game.Guns.setAiming(ctx.player(), payload.aiming()));
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
				case RELOAD -> dev.halloween.game.Guns.reload(ctx.player());
				case ITEM_USE -> dev.halloween.game.Gear.use(ctx.player());
				case STAB -> dev.halloween.game.Gear.stab(ctx.player());
				case RUNT_SPECIAL -> dev.halloween.game.Runt.special(ctx.player());
				case RUNT_LAUGH -> {
					// (The Runt's laugh is gone.)
				}
				case SKILL -> dev.halloween.game.Heroes.use(ctx.player());
			}
		});

		ServerTickEvents.START_SERVER_TICK.register(dev.halloween.game.TickTimer::start);
		ServerTickEvents.END_SERVER_TICK.register(MatchManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.halloween.game.Cutscenes::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.halloween.game.TickTimer::end);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(dev.halloween.game.Leftovers::onLoad);
		// Townsfolk and officers whose chunk loads again come back in a new body object.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
			dev.halloween.game.Townsfolk.rebind(e);
			dev.halloween.game.Police.rebind(e);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			MatchManager.markDirty();
			DoorLocks.syncTo(handler.player);
			dev.halloween.game.Skins.syncTo(handler.player);
			dev.halloween.game.Weather.syncTo(handler.player);
			dev.halloween.game.Perks.sync(handler.player);
			dev.halloween.game.Music.forget(handler.player);
			dev.halloween.game.Minimap.syncTo(handler.player);
			dev.halloween.game.Match.markDirty();
			// Between matches everyone waits in the gym.
			dev.halloween.game.Lobby.onJoin(handler.player);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(dev.halloween.game.Match::stop);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> MatchManager.clearAll());

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> MatchManager.onVanillaAttack(player, entity));
		// Match items do nothing the vanilla way: right click goes through Gear (the client sends ITEM_USE).
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> !level.isClientSide()
			&& dev.halloween.game.HwItems.kindOf(player.getItemInHand(hand)) != null ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> level.isClientSide() ? net.minecraft.world.InteractionResult.PASS
			: dev.halloween.game.Events.onUseEntity(player, entity));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> dev.halloween.game.SideJobs.onUseEntity(player, entity));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> MatchManager.onUseEntity(player, hand, entity));
		// In Shape Jump the Shape is a shadow: he slips through doors but can't work them, nor the breakers.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> !level.isClientSide()
			&& player instanceof net.minecraft.server.level.ServerPlayer sp && MatchManager.role(sp) == Role.SHAPE
			&& MatchManager.shapeState(sp) != null && MatchManager.shapeState(sp).shadowTicks > 0
			? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		UseBlockCallback.EVENT.register(LightSystem::onUseBlock);
		// In a chase: pulling things down behind you, a door in his face (game.Obstacles).
		UseBlockCallback.EVENT.register(dev.halloween.game.Obstacles::onUse);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> !level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp
			? dev.halloween.game.Lobby.onUse(sp, hit.getBlockPos()) : net.minecraft.world.InteractionResult.PASS);
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
				&& (dev.halloween.game.Loot.isContainer(hit.getBlockPos()) || dev.halloween.game.GunCase.isCase(hit.getBlockPos()) || level.getBlockState(hit.getBlockPos()).is(HwBlocks.PHONE))) {
				return net.minecraft.world.InteractionResult.FAIL;
			}
			// In a match the furniture is scenery: beds, stoves, jukeboxes, cakes, pots and the like do nothing.
			// Doors, trapdoors, gates, levers and buttons work as usual.
			if (player instanceof net.minecraft.server.level.ServerPlayer sp3 && MatchManager.role(sp3) != Role.NONE) {
				net.minecraft.world.level.block.Block block = level.getBlockState(hit.getBlockPos()).getBlock();
				if (!(block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.TrapDoorBlock
					|| block instanceof net.minecraft.world.level.block.FenceGateBlock || block instanceof net.minecraft.world.level.block.LeverBlock
					|| block instanceof net.minecraft.world.level.block.ButtonBlock)) {
					return net.minecraft.world.InteractionResult.FAIL;
				}
			}
			return DoorLocks.onUseBlock(player, level, hit.getBlockPos());
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> dev.halloween.game.Events.allowDamage(entity, source, amount)
			&& MatchManager.allowDamage(entity, source));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> MatchManager.allowDeath(entity));

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> HwCommands.register(dispatcher));
		LOGGER.info("Halloween loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
