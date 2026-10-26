import java.io.*; import java.nio.*; import javax.sound.sampled.*;
/** The clock's tick and tock for the curse, cut from the clock loop (see split-vecna-music.sh): java tools/VecnaClockTick.java in.wav out.ogg from to gain: a mono cut (channels mixed), 5 ms fade in, 120 ms fade out, as 16-bit WAV under the out name. */
public class VecnaClockTick { public static void main(String[] a) throws Exception {
 AudioInputStream in = AudioSystem.getAudioInputStream(new File(a[0])); AudioFormat f = in.getFormat(); byte[] raw = in.readAllBytes();
 int ch=f.getChannels(); double rate=f.getSampleRate(); short[] p=new short[raw.length/2]; ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(p);
 int s=(int)(Double.parseDouble(a[2])*rate), e=(int)(Double.parseDouble(a[3])*rate); double g=Double.parseDouble(a[4]); int n=e-s; short[] o=new short[n];
 for(int i=0;i<n;i++){double v=0;for(int c=0;c<ch;c++)v+=p[(s+i)*ch+c];v/=ch;v*=g*Math.min(1,Math.min(i/(0.005*rate),(n-i)/(0.12*rate)));o[i]=(short)Math.max(-32768,Math.min(32767,Math.round(v)));}
 ByteBuffer b=ByteBuffer.allocate(n*2).order(ByteOrder.LITTLE_ENDIAN); b.asShortBuffer().put(o);
 AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(b.array()),new AudioFormat((float)rate,16,1,true,false),n),AudioFileFormat.Type.WAVE,new File(a[1]));
 System.out.println(a[1]+" "+n/rate+" s"); } }
