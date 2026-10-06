import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts an Ogg Vorbis file into parts without re-encoding: the three header packets, then the audio
 * packets of each part (cut at page boundaries, about a second's precision), granules shifted to start at
 * zero, the last page marked end of stream.
 * Usage: OggSplit in.ogg outPrefix rate t1 t2 ... (seconds; parts are [0,t1), [t1,t2), ..., [tn,end)).
 */
public class OggSplit {
	record Pkt(byte[] data, long granule) {
	}

	public static void main(String[] a) throws Exception {
		List<Pkt> packets = new ArrayList<>();
		int serial = 0;
		try (InputStream in = new FileInputStream(a[0])) {
			SyncState oy = new SyncState();
			StreamState os = new StreamState();
			Page og = new Page();
			Packet op = new Packet();
			oy.init();
			boolean started = false;
			boolean eof = false;
			while (!eof) {
				int at = oy.buffer(8192);
				int n = in.read(oy.data, at, 8192);
				if (n <= 0) {
					eof = true;
					n = 0;
				}
				oy.wrote(n);
				while (oy.pageout(og) == 1) {
					if (!started) {
						serial = og.serialno();
						os.init(serial);
						started = true;
					}
					os.pagein(og);
					while (os.packetout(op) == 1) {
						byte[] copy = new byte[op.bytes];
						System.arraycopy(op.packet_base, op.packet, copy, 0, op.bytes);
						packets.add(new Pkt(copy, op.granulepos));
					}
				}
			}
		}
		String prefix = a[1];
		int rate = Integer.parseInt(a[2]);
		List<Long> cuts = new ArrayList<>();
		cuts.add(0L);
		for (int i = 3; i < a.length; i++) {
			cuts.add((long) (Double.parseDouble(a[i]) * rate));
		}
		cuts.add(Long.MAX_VALUE);
		List<Pkt> audio = packets.subList(3, packets.size());
		int start = 0;
		long startGranule = 0;
		for (int part = 0; part + 1 < cuts.size(); part++) {
			long end = cuts.get(part + 1);
			// The part runs to the first page boundary at or past the cut.
			int last = start;
			for (int i = start; i < audio.size(); i++) {
				last = i;
				if (audio.get(i).granule >= 0 && audio.get(i).granule >= end) {
					break;
				}
			}
			long endGranule = startGranule;
			for (int i = last; i >= start; i--) {
				if (audio.get(i).granule >= 0) {
					endGranule = audio.get(i).granule;
					break;
				}
			}
			String out = prefix + "_" + (part + 1) + ".ogg";
			write(out, serial + part + 1, packets.subList(0, 3), audio.subList(start, last + 1), startGranule);
			System.out.printf("%s: %.1f - %.1f s (%d packets)%n", out, startGranule / (double) rate, endGranule / (double) rate, last + 1 - start);
			start = last + 1;
			startGranule = endGranule;
			if (start >= audio.size()) {
				break;
			}
		}
	}

	static void write(String file, int serial, List<Pkt> headers, List<Pkt> audio, long startGranule) throws Exception {
		StreamState os = new StreamState();
		os.init(serial);
		Page page = new Page();
		long no = 0;
		try (OutputStream out = new FileOutputStream(file)) {
			for (int i = 0; i < headers.size(); i++) {
				os.packetin(packet(headers.get(i).data, 0, i == 0, false, no++));
				if (i == 0) {
					while (os.flush(page) != 0) {
						write(out, page);
					}
				}
			}
			while (os.flush(page) != 0) {
				write(out, page);
			}
			for (int i = 0; i < audio.size(); i++) {
				Pkt p = audio.get(i);
				boolean lastOne = i == audio.size() - 1;
				long granule = p.granule >= 0 ? p.granule - startGranule : -1;
				os.packetin(packet(p.data, granule, false, lastOne, no++));
				while (os.pageout(page) != 0) {
					write(out, page);
				}
			}
			while (os.flush(page) != 0) {
				write(out, page);
			}
		}
	}

	static Packet packet(byte[] data, long granule, boolean bos, boolean eos, long no) {
		Packet p = new Packet();
		p.packet_base = data;
		p.packet = 0;
		p.bytes = data.length;
		p.b_o_s = bos ? 1 : 0;
		p.e_o_s = eos ? 1 : 0;
		p.granulepos = granule;
		p.packetno = no;
		return p;
	}

	static void write(OutputStream out, Page page) throws Exception {
		out.write(page.header_base, page.header, page.header_len);
		out.write(page.body_base, page.body, page.body_len);
	}
}
