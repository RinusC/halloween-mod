package dev.halloween.client;

import dev.halloween.game.Heroes;
import dev.halloween.game.Pings;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The survivor's own side of game.Heroes and game.Pings: the skill key and what it shows (Laurie's feeling of him,
 * Jennifer's finds), Alexis's sense of him near, and the team's marks over the world (off screen, an arrow at the
 * edge). In the headset the marks go on the world (VrMarks).
 */
public final class ClientHeroes {
	private record Mark(Vec3 pos, int kind, int entityId, String from, long until) {
	}

	private static HwNet.HeroSync hero = new HwNet.HeroSync("", 0, 0, 0, false);
	private static long cooldownEnd;
	private static int glowId = -1;
	private static long glowUntil;
	private static List<Vec3> finds = List.of();
	private static long findsUntil;
	private static final List<Mark> MARKS = new ArrayList<>();

	private ClientHeroes() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HwNet.HeroSync.TYPE, (payload, ctx) -> {
			hero = payload;
			Minecraft mc = Minecraft.getInstance();
			cooldownEnd = (mc.level == null ? 0 : mc.level.getGameTime()) + payload.cooldownLeft();
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.HeroReveal.TYPE, (payload, ctx) -> {
			Minecraft mc = Minecraft.getInstance();
			long now = mc.level == null ? 0 : mc.level.getGameTime();
			if (payload.kind() == HwNet.HeroReveal.FEEL) {
				glowId = payload.entityId();
				glowUntil = now + payload.ticks();
			} else {
				finds = payload.spots();
				findsUntil = now + payload.ticks();
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(HwNet.TeamPing.TYPE, (payload, ctx) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				return;
			}
			MARKS.removeIf(m -> m.from().equals(payload.from()) || m.kind() == Pings.KILLER && payload.kind() == Pings.KILLER);
			MARKS.add(new Mark(payload.pos(), payload.kind(), payload.entityId(), payload.from(), mc.level.getGameTime() + payload.ticks()));
			mc.getSoundManager().play(SimpleSoundInstance.forUI(payload.kind() == Pings.KILLER ? SoundEvents.NOTE_BLOCK_BELL.value() : SoundEvents.NOTE_BLOCK_HAT.value(),
				payload.kind() == Pings.KILLER ? 0.7f : 1.4f, 0.5f));
		});
	}

	public static void reset() {
		hero = new HwNet.HeroSync("", 0, 0, 0, false);
		glowId = -1;
		finds = List.of();
		MARKS.clear();
	}

	/** Laurie feels him: his outline through walls for a while. */
	public static boolean glows(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		return entity.getId() == glowId && mc.level != null && mc.level.getGameTime() < glowUntil;
	}

	/** A survivor's keys: the skill, and marking for the team. */
	static void tick(Minecraft mc) {
		while (HalloweenClient.KEY_SKILL.consumeClick()) {
			if (ClientState.myRole().survivor() && mc.gui.screen() == null && Heroes.Skill.values()[Math.min(hero.skill(), Heroes.Skill.values().length - 1)] != Heroes.Skill.NONE) {
				ClientPlayNetworking.send(new HwNet.ActionPress(HwNet.Action.SKILL));
			}
		}
		while (HalloweenClient.KEY_PING.consumeClick()) {
			if (ClientState.myRole().survivor() && mc.gui.screen() == null && !mc.player.isSpectator()) {
				ping(mc);
			}
		}
		MARKS.removeIf(m -> m.until() < mc.level.getGameTime());
		if (hero.alert() && mc.level.getGameTime() % 30 == 0) {
			// Alexis in the headset: a faint shiver in the free hand too.
			ClientVr.buzz(false, 0.08f, 0.35f);
		}
	}

	/** What is under the aim, up to 64 blocks: Michael, something lying about, something to search, a way out, or a place. */
	private static void ping(Minecraft mc) {
		Vec3 from = CameraAim.origin();
		Vec3 dir = CameraAim.direction();
		Vec3 to = from.add(dir.scale(64));
		BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (block.getType() != HitResult.Type.MISS) {
			to = block.getLocation();
		}
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, from, to, new AABB(from, to).inflate(1.5),
			e -> e != mc.player && !e.isSpectator() && (e.isPickable() || e instanceof ItemEntity), from.distanceToSqr(to));
		if (hit != null) {
			Entity e = hit.getEntity();
			int kind = e.getId() == ClientState.shapeEntityId ? Pings.KILLER : e instanceof ItemEntity || e instanceof Interaction ? Pings.ITEM : Pings.PLACE;
			ClientPlayNetworking.send(new HwNet.Ping(e.position(), kind, e.getId()));
			return;
		}
		if (block.getType() == HitResult.Type.MISS) {
			return;
		}
		int object = ClientInteractions.matchObject(mc.level, block.getBlockPos());
		int kind = object == 1 ? Pings.SEARCH : object == 3 ? Pings.EXIT : object == 4 ? Pings.ITEM : Pings.PLACE;
		Vec3 at = kind == Pings.PLACE ? block.getLocation() : Vec3.atCenterOf(block.getBlockPos());
		ClientPlayNetworking.send(new HwNet.Ping(at, kind, -1));
	}

	private static Component who(String from) {
		if (from.startsWith("#")) {
			// The radio, the dog (game.SideJobs).
			return Component.translatable("halloween.ping.from." + from.substring(1));
		}
		return dev.halloween.game.Skins.SURVIVORS.contains(from) ? Component.translatable("halloween.hero.name." + from) : Component.literal(from);
	}

	private static String icon(int kind) {
		return switch (kind) {
			case Pings.KILLER -> "☠";
			case Pings.ITEM -> "✦";
			case Pings.SEARCH -> "⌕";
			case Pings.EXIT -> "⇱";
			default -> "◆";
		};
	}

	private static int color(int kind) {
		return switch (kind) {
			case Pings.KILLER -> 0xFFE0261F;
			case Pings.ITEM -> 0xFFE8C860;
			case Pings.SEARCH -> 0xFFB8D0E8;
			case Pings.EXIT -> 0xFF7FE07F;
			default -> 0xFFE8E2D6;
		};
	}

	/** The team's marks and Jennifer's finds over the world; the skill and Alexis's sense of him at the side. */
	static void hud(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float partial) {
		long now = mc.level.getGameTime();
		Font font = mc.font;
		for (Mark m : MARKS) {
			Vec3 at = m.pos();
			Entity e = m.entityId() < 0 ? null : mc.level.getEntity(m.entityId());
			if (e != null) {
				at = e.getPosition(partial);
			}
			at = at.add(0, m.kind() == Pings.KILLER ? 2.4 : 0.8, 0);
			int alpha = (int) (255 * Mth.clamp((m.until() - now - partial) / 30f, 0.25f, 1f));
			int color = (alpha << 24) | (color(m.kind()) & 0xFFFFFF);
			int dist = (int) Math.round(mc.player.position().distanceTo(at));
			Component label = Component.translatable("halloween.ping." + m.kind(), who(m.from()), dist);
			if (ClientVr.active()) {
				VrMarks.add(at, icon(m.kind()), color, 2.2f);
				VrMarks.add(at.add(0, -0.4, 0), label, color, 1f);
				continue;
			}
			Projection.Point p = Projection.toScreen(at, w, h);
			if (p != null && p.x() >= 0 && p.x() <= w && p.y() >= 0 && p.y() <= h) {
				g.pose().pushMatrix();
				g.pose().translate(p.x(), p.y() - 6);
				g.pose().scale(2f, 2f);
				g.centeredText(font, Component.literal(icon(m.kind())), 0, -4, color);
				g.pose().popMatrix();
				g.centeredText(font, label, (int) p.x(), (int) p.y() + 8, color);
				continue;
			}
			// Behind or beside: which way.
			Vec3 to = at.subtract(mc.player.getEyePosition(partial));
			Vec3 look = mc.player.getViewVector(partial);
			boolean right = look.x * to.z - look.z * to.x > 0;
			Component side = right ? label.copy().append(" ▶") : Component.literal("◀ ").append(label);
			g.centeredText(font, side, right ? w - 70 : 70, h / 2 - 40, color);
		}
		if (now < findsUntil) {
			int alpha = (int) (255 * Mth.clamp((findsUntil - now - partial) / 30f, 0.2f, 1f));
			int color = (alpha << 24) | 0xE8C860;
			for (Vec3 spot : finds) {
				if (ClientVr.active()) {
					VrMarks.add(spot, "✦", color, 2f);
					continue;
				}
				Projection.Point p = Projection.toScreen(spot, w, h);
				if (p != null) {
					g.centeredText(font, Component.literal("✦"), (int) p.x(), (int) p.y() - 4, color);
				}
			}
		}
		// The skill, under the shove line.
		Heroes.Skill skill = Heroes.Skill.values()[Math.min(hero.skill(), Heroes.Skill.values().length - 1)];
		if (skill != Heroes.Skill.NONE) {
			long left = cooldownEnd - now;
			Component name = Component.translatable("halloween.hero.skill." + skill.name().toLowerCase(java.util.Locale.ROOT));
			Component text = left > 0
				? Component.translatable("halloween.hud.small.cooldown", name, (left + 19) / 20)
				: Component.translatable("halloween.hud.small.ready", name, HalloweenClient.KEY_SKILL.getTranslatedKeyMessage());
			g.text(font, text, w - 12 - font.width(text), h - 96, left > 0 ? 0xFF8A847A : 0xFFE8E2D6, true);
		}
		if (hero.alert()) {
			// Alexis: someone is near, unseen. The sign breathes in and out.
			float pulse = 0.55f + 0.45f * Mth.sin((now + partial) * 0.25f);
			Component line = Component.translatable("halloween.hero.alexis.near");
			int color = ((int) (255 * pulse) << 24) | 0xD06060;
			g.centeredText(font, line, w / 2, h / 2 + 64, color);
		}
	}
}
