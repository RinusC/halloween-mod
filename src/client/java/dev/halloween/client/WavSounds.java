package dev.halloween.client;

import com.mojang.blaze3d.audio.SoundBuffer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.lwjgl.BufferUtils;

/**
 * Plain 16-bit mono WAV clips (the people's screams under sounds/voice/, kept under .ogg names because
 * that is what sounds.json points at). Minecraft only decodes Ogg Vorbis; Java reads WAV by itself.
 */
public final class WavSounds {
	private WavSounds() {
	}

	public static SoundBuffer load(InputStream is) throws IOException {
		try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(is))) {
			AudioFormat format = in.getFormat();
			byte[] bytes = in.readAllBytes();
			ByteBuffer data = BufferUtils.createByteBuffer(bytes.length);
			data.put(bytes).flip();
			return new SoundBuffer(data, format);
		} catch (UnsupportedAudioFileException e) {
			throw new IOException(e);
		}
	}
}
