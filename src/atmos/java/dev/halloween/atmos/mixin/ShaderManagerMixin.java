package dev.halloween.atmos.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sodium's terrain fog is ours (halloween:shaders/include/sodium_fog.glsl, see atmos.glsl): put in place of its
 * sodium:fog.glsl as the shaders load, so it wins whatever order the mods' resources come in.
 */
@Mixin(ShaderManager.class)
public class ShaderManagerMixin {
	private static final Identifier OURS = Identifier.fromNamespaceAndPath("halloween", "shaders/include/sodium_fog.glsl");
	private static final Identifier SODIUM = Identifier.fromNamespaceAndPath("sodium", "shaders/include/fog.glsl");

	@ModifyExpressionValue(method = "prepare", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/packs/resources/ResourceManager;listResources(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Map;"))
	private Map<Identifier, Resource> halloween$sodiumFog(Map<Identifier, Resource> files) {
		Resource ours = files.get(OURS);
		if (ours == null || !files.containsKey(SODIUM)) {
			return files;
		}
		Map<Identifier, Resource> swapped = new HashMap<>(files);
		swapped.put(SODIUM, ours);
		return swapped;
	}
}
