package dev.halloween.client.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.halloween.client.WavSounds;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Our voice clips and Shape Jump sounds are WAV, not Ogg: load them with {@link WavSounds} instead of the Vorbis decoder. */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {
	@Shadow
	@Final
	private ResourceProvider resourceManager;

	@Shadow
	@Final
	private Map<Identifier, CompletableFuture<SoundBuffer>> cache;

	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void halloween$wavVoices(Identifier location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		if (!location.getNamespace().equals("halloween") || !location.getPath().startsWith("sounds/voice/") && !location.getPath().startsWith("sounds/shape/")) {
			return;
		}
		cir.setReturnValue(this.cache.computeIfAbsent(location, l -> CompletableFuture.supplyAsync(() -> {
			try (InputStream is = this.resourceManager.open(l)) {
				return WavSounds.load(is);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool())));
	}
}
