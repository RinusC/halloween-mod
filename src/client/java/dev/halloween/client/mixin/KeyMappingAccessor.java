package dev.halloween.client.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Presses passed on from the VR buttons to the keys they stand for (see VrKeys). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int halloween$getClickCount();

	@Accessor("clickCount")
	void halloween$setClickCount(int count);
}
