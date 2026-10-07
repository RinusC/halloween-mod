package dev.halloween.client;

/** Added to every entity render state (see mixin.EntityRenderStateMixin): which entity it was made from. */
public interface HwRenderState {
	int halloween$entityId();

	void halloween$setEntityId(int id);
}
