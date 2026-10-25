import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Cuts Vecna's music into loops: java tools/VecnaMusic.java in.wav out_prefix [from to]... (seconds; no pairs: the whole file).
 * Every part is written as a 16-bit WAV under an .ogg name (loaded by client.WavSounds) that loops without a seam:
 * the last stretch is blended into the start (equal power), so the end flows into the beginning.
 */
public class VecnaMusic {
	public static void main(String[] args) throws Exception {
		AudioInputStream in = AudioSystem.getAudioInputStream(new File(args[0]));
		AudioFormat f = in.getFormat();
		byte[] raw = in.readAllBytes();
		int ch = f.getChannels();
		double rate = f.getSampleRate();
		int frames = raw.length / (2 * ch);
		short[] pcm = new short[frames * ch];
		ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
		int parts = Math.max(1, (args.length - 2) / 2);
		for (int p = 0; p < parts; p++) {
			int a = 0;
			int b = frames;
			String name = args[1];
			if (args.length > 2) {
				a = (int) (Double.parseDouble(args[2 + p * 2]) * rate);
				b = Math.min(frames, (int) (Double.parseDouble(args[3 + p * 2]) * rate));
				name = args[1] + "_" + (p + 1);
			}
			int len = b - a;
			int x = (int) Math.min(2.0 * rate, len * 0.1);
			int outLen = len - x;
			short[] out = new short[outLen * ch];
			for (int i = 0; i < outLen; i++) {
				for (int c = 0; c < ch; c++) {
					double v = pcm[(a + i) * ch + c];
					if (i < x) {
						double w = (double) i / x;
						v = v * Math.sin(w * Math.PI / 2) + pcm[(b - x + i) * ch + c] * Math.cos(w * Math.PI / 2);
					}
					out[i * ch + c] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
				}
			}
			ByteBuffer bytes = ByteBuffer.allocate(out.length * 2).order(ByteOrder.LITTLE_ENDIAN);
			bytes.asShortBuffer().put(out);
			AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, (float) rate, 16, ch, ch * 2, (float) rate, false);
			AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(bytes.array()), fmt, outLen), AudioFileFormat.Type.WAVE, new File(name + ".ogg"));
			System.out.printf("%s: %.1f s%n", name, outLen / rate);
		}
	}
}
