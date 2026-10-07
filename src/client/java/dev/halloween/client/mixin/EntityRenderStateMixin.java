package dev.halloween.client.mixin;

import dev.halloween.client.HwRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Render states remember whose they are (for the grab animations, see ClientAnims). */
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements HwRenderState {
	@Unique
	private int halloween$entityId = -1;

	@Override
	public int halloween$entityId() {
		return this.halloween$entityId;
	}

	@Override
	public void halloween$setEntityId(int id) {
		this.halloween$entityId = id;
	}
}
