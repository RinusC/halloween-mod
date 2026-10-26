#!/usr/bin/perl
# Vecna's sounds: writes 16-bit mono 44.1 kHz WAV clips under .ogg names (loaded by client.WavSounds).
#   perl tools/vecna_sounds.pl src/main/resources/assets/halloween/sounds
# Bells, clock, thunder, rift, Grip: synthesised here. His voice: CC0 groans and screams from sounds/voice
# slowed and saturated. Whispers: townsfolk talk lines turned to whispers by a noise vocoder.
use strict;
use warnings;
use POSIX qw(floor);

my $SR = 44100;
my $PI = 3.14159265358979;
my $root = shift or die "usage: vecna_sounds.pl <sounds dir>\n";
my $out = "$root/vecna";
mkdir $out unless -d $out;
srand(1984);

# ---------- basics ----------
sub zeros { my $n = int($_[0] * $SR); return [(0) x $n]; }
sub len { scalar @{$_[0]} }
sub mix {    # mix(dst, src, at_seconds, gain)
	my ($d, $s, $at, $g) = @_;
	my $o = int(($at // 0) * $SR);
	$g //= 1;
	my $n = @$s;
	push @$d, (0) x ($o + $n - @$d) if $o + $n > @$d;
	for my $i (0 .. $n - 1) { $d->[$o + $i] = ($d->[$o + $i] // 0) + $s->[$i] * $g; }
	return $d;
}
sub gain { my ($s, $g) = @_; $_ *= $g for @$s; $s }
sub rev { [reverse @{$_[0]}] }
sub noise { my $n = int($_[0] * $SR); [map { rand() * 2 - 1 } 1 .. $n] }
sub brown {
	my $n = int($_[0] * $SR);
	my ($y, @o) = (0);
	for (1 .. $n) { $y = $y * 0.995 + (rand() * 2 - 1) * 0.1; push @o, $y; }
	\@o;
}
sub env {    # piecewise-linear envelope: [t, v], ... (seconds)
	my ($s, @pts) = @_;
	my $n = @$s;
	my $k = 0;
	for my $i (0 .. $n - 1) {
		my $t = $i / $SR;
		$k++ while $k < $#pts && $pts[$k + 1][0] <= $t;
		my $v;
		if ($k >= $#pts) { $v = $pts[-1][1]; }
		else {
			my ($a, $b) = ($pts[$k], $pts[$k + 1]);
			my $f = ($t - $a->[0]) / (($b->[0] - $a->[0]) || 1e-9);
			$f = 0 if $f < 0;
			$v = $a->[1] + ($b->[1] - $a->[1]) * $f;
		}
		$s->[$i] *= $v;
	}
	$s;
}
sub expdecay { my ($s, $tau, $at) = @_; $at //= 0; for my $i (0 .. $#$s) { my $t = $i / $SR - $at; $s->[$i] *= $t < 0 ? 0 : exp(-$t / $tau); } $s }
sub fade { my ($s, $in, $o) = @_; my $n = @$s; my ($a, $b) = (int($in * $SR), int($o * $SR));
	for my $i (0 .. $a - 1) { last if $i >= $n; $s->[$i] *= $i / $a; }
	for my $i (0 .. $b - 1) { my $j = $n - 1 - $i; last if $j < 0; $s->[$j] *= $i / $b; } $s }
sub tanhs { my ($s, $drive) = @_; for (@$s) { my $x = $_ * $drive; my $e = exp(2 * ($x > 15 ? 15 : $x < -15 ? -15 : $x)); $_ = ($e - 1) / ($e + 1); } $s }

# ---------- filters ----------
sub biquad {    # RBJ: type lp hp bp peak; f may be a number or a sub(t) -> Hz
	my ($s, $type, $f, $q, $dbg) = @_;
	my ($x1, $x2, $y1, $y2) = (0, 0, 0, 0);
	my @o;
	my ($b0, $b1, $b2, $a1, $a2);
	my $calc = sub {
		my $fc = shift;
		$fc = 20 if $fc < 20;
		$fc = $SR * 0.45 if $fc > $SR * 0.45;
		my $w = 2 * $PI * $fc / $SR;
		my ($cw, $sw) = (cos $w, sin $w);
		my $al = $sw / (2 * $q);
		my $a0;
		if ($type eq 'lp') { ($b0, $b1, $b2) = ((1 - $cw) / 2, 1 - $cw, (1 - $cw) / 2); }
		elsif ($type eq 'hp') { ($b0, $b1, $b2) = ((1 + $cw) / 2, -(1 + $cw), (1 + $cw) / 2); }
		elsif ($type eq 'bp') { ($b0, $b1, $b2) = ($al, 0, -$al); }
		elsif ($type eq 'peak') { my $A = 10**($dbg / 40); ($b0, $b1, $b2) = (1 + $al * $A, -2 * $cw, 1 - $al * $A); $a0 = 1 + $al / $A; $a1 = -2 * $cw; $a2 = 1 - $al / $A; }
		$a0 //= 1 + $al;
		unless ($type eq 'peak') { $a1 = -2 * $cw; $a2 = 1 - $al; }
		$_ /= $a0 for ($b0, $b1, $b2, $a1, $a2);
	};
	my $dyn = ref $f eq 'CODE';
	$calc->($f) unless $dyn;
	for my $i (0 .. $#$s) {
		$calc->($f->($i / $SR)) if $dyn && $i % 32 == 0;
		my $x = $s->[$i];
		my $y = $b0 * $x + $b1 * $x1 + $b2 * $x2 - $a1 * $y1 - $a2 * $y2;
		($x2, $x1, $y2, $y1) = ($x1, $x, $y1, $y);
		push @o, $y;
	}
	\@o;
}
sub lp { biquad($_[0], 'lp', $_[1], $_[2] // 0.707) }
sub hp { biquad($_[0], 'hp', $_[1], $_[2] // 0.707) }
sub bp { biquad($_[0], 'bp', $_[1], $_[2] // 1) }

sub reverb {    # mono Freeverb; wet 0..1, room 0..1 (size), damp 0..1
	my ($s, $wet, $room, $damp, $tail) = @_;
	my @in = (@$s, (0) x int(($tail // 2) * $SR));
	my @cd = (1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617);
	my @ad = (556, 441, 341, 225);
	my $fb = 0.7 + 0.28 * $room;
	my (@cb, @cp, @cs, @ab, @ap);
	for my $c (0 .. 7) { $cb[$c] = [(0) x $cd[$c]]; $cp[$c] = 0; $cs[$c] = 0; }
	for my $a (0 .. 3) { $ab[$a] = [(0) x $ad[$a]]; $ap[$a] = 0; }
	my @o;
	for my $x (@in) {
		my $inp = $x * 0.015;
		my $y = 0;
		for my $c (0 .. 7) {
			my $buf = $cb[$c];
			my $v = $buf->[$cp[$c]];
			$y += $v;
			$cs[$c] = $v * (1 - $damp) + $cs[$c] * $damp;
			$buf->[$cp[$c]] = $inp + $cs[$c] * $fb;
			$cp[$c] = ($cp[$c] + 1) % $cd[$c];
		}
		for my $a (0 .. 3) {
			my $buf = $ab[$a];
			my $v = $buf->[$ap[$a]];
			my $t = -$y + $v;
			$buf->[$ap[$a]] = $y + $v * 0.5;
			$ap[$a] = ($ap[$a] + 1) % $ad[$a];
			$y = $t;
		}
		push @o, $x * (1 - $wet) + $y * $wet * 3;
	}
	\@o;
}

sub resample {    # speed < 1 = slower and lower
	my ($s, $speed) = @_;
	my @o;
	my $n = @$s;
	for (my $p = 0; $p < $n - 1; $p += $speed) {
		my $i = int $p;
		my $f = $p - $i;
		push @o, $s->[$i] * (1 - $f) + $s->[$i + 1] * $f;
	}
	\@o;
}

# ---------- oscillators ----------
sub sine {    # freq may be sub(t)
	my ($dur, $f, $ph) = @_;
	my $n = int($dur * $SR);
	my ($p, @o) = ($ph // 0);
	for my $i (0 .. $n - 1) { my $fr = ref $f ? $f->($i / $SR) : $f; $p += 2 * $PI * $fr / $SR; push @o, sin $p; }
	\@o;
}
sub saw {    # band-limited-ish: sum of harmonics up to 4 kHz
	my ($dur, $f) = @_;
	my $n = int($dur * $SR);
	my ($p, @o) = (0);
	for my $i (0 .. $n - 1) {
		my $fr = ref $f ? $f->($i / $SR) : $f;
		$p += 2 * $PI * $fr / $SR;
		my $v = 0;
		my $h = int(4000 / $fr) || 1;
		$h = 40 if $h > 40;
		$v += sin($p * $_) / $_ for 1 .. $h;
		push @o, $v * 0.5;
	}
	\@o;
}

# ---------- wav io ----------
sub readwav {
	my $path = shift;
	open my $fh, '<:raw', $path or die "$path: $!";
	local $/;
	my $d = <$fh>;
	close $fh;
	my $pos = 12;
	my ($ch, $data) = (1);
	while ($pos < length $d) {
		my ($id, $sz) = unpack 'a4 V', substr($d, $pos, 8);
		if ($id eq 'fmt ') { $ch = unpack 'v', substr($d, $pos + 10, 2); }
		if ($id eq 'data') { $data = substr($d, $pos + 8, $sz); last; }
		$pos += 8 + $sz + ($sz & 1);
	}
	my @v = map { $_ / 32768 } unpack 's<*', $data;
	if ($ch == 2) { my @m; push @m, ($v[2 * $_] + $v[2 * $_ + 1]) / 2 for 0 .. int(@v / 2) - 1; @v = @m; }
	\@v;
}
my %LOUD;
sub write_clip {
	my ($name, $s, $peak) = @_;
	$s = hp($s, 25);
	$peak //= 0.89;
	my $m = 0;
	for (@$s) { my $a = abs $_; $m = $a if $a > $m; }
	$m ||= 1;
	my $g = $peak / $m;
	# trim the silent tail
	my $end = $#$s;
	$end-- while $end > 0 && abs($s->[$end] * $g) < 0.0025;
	my $fo = int(0.15 * $SR);
	for my $i (0 .. $fo - 1) { last if $end - $i < 0; $s->[$end - $i] *= $i / $fo; }
	my $data = pack 's<*', map { my $v = int($s->[$_] * $g * 32767); $v > 32767 ? 32767 : $v < -32768 ? -32768 : $v } 0 .. $end;
	open my $fh, '>:raw', "$out/$name.ogg" or die $!;
	print $fh 'RIFF', pack('V', 36 + length $data), 'WAVE', 'fmt ', pack('V v v V V v v', 16, 1, 1, $SR, $SR * 2, 2, 16), 'data', pack('V', length $data), $data;
	close $fh;
	printf "%-22s %5.2fs\n", $name, ($end + 1) / $SR;
}

# ---------- building blocks ----------
# A bell: inharmonic partials, the high ones dying first; each one a beating pair.
sub bell {
	my ($f, $dur, $decay, $bright) = @_;
	$bright //= 1;
	my @p = ([0.5, 0.55, 1.6], [1, 1, 1.25], [1.19, 0.45, 0.9], [1.5, 0.32, 0.75], [2, 0.55, 0.6], [2.51, 0.22 * $bright, 0.38], [2.66, 0.2 * $bright, 0.33],
		[3.01, 0.17 * $bright, 0.28], [4.17, 0.11 * $bright, 0.18], [5.43, 0.07 * $bright, 0.12], [6.8, 0.04 * $bright, 0.08]);
	my $s = zeros($dur);
	for my $p (@p) {
		my ($r, $a, $d) = @$p;
		next if $f * $r > 16000;
		for my $det (-1, 1) {
			my $t = sine($dur, $f * $r * (1 + $det * 0.0011 * $r), rand 6);
			expdecay($t, $decay * $d);
			mix($s, $t, 0, $a / 2);
		}
	}
	# the strike
	my $k = bp(noise(0.05), $f * 6, 2);
	expdecay($k, 0.008);
	mix($s, $k, 0, 0.8);
	$s;
}
# A low thud with the pitch dropping.
sub thud {
	my ($f0, $f1, $dur, $tau) = @_;
	my $s = sine($dur, sub { $f1 + ($f0 - $f1) * exp(-$_[0] / 0.06) });
	expdecay($s, $tau);
	my $n = lp(noise($dur), 300);
	expdecay($n, $tau * 0.3);
	mix($s, $n, 0, 0.8);
}
# Vecna's voice: a CC0 groan slowed down, an octave below underneath, saturated and darkened.
my $V = "$root/voice";
sub growl {
	my ($file, $speed, $dur) = @_;
	my $src = readwav("$V/$file.ogg");
	my $a = resample($src, $speed);
	my $b = resample($src, $speed / 2);
	my $s = [@$a];
	mix($s, $b, 0, 0.7);
	# a rasp: a little ring with a low wobble
	for my $i (0 .. $#$s) { $s->[$i] *= 0.75 + 0.25 * sin(2 * $PI * 31 * $i / $SR); }
	$s = tanhs($s, 2.2);
	$s = lp($s, 2600);
	$s = hp($s, 45);
	$s = biquad($s, 'peak', 180, 1, 6);
	splice @$s, int($dur * $SR) if $dur && @$s > $dur * $SR;
	fade($s, 0.02, 0.3);
}
# A whisper: a talk line through a noise vocoder (16 bands), a bit slower.
my $T = "$root/talk";
sub whisper {
	my ($file, $speed) = @_;
	my $src = resample(readwav("$T/$file.ogg"), $speed);
	my $nz = [map { rand() * 2 - 1 } 0 .. $#$src];
	my $o = [(0) x @$src];
	my @bands = map { 250 * (7200 / 250)**($_ / 15) } 0 .. 15;
	for my $fc (@bands) {
		my $mb = bp($src, $fc, 4);
		my $cb = bp($nz, $fc, 4);
		my $e = 0;
		for my $i (0 .. $#$o) {
			my $a = abs $mb->[$i];
			$e += ($a - $e) * ($a > $e ? 0.01 : 0.002);
			$o->[$i] += $cb->[$i] * $e * ($fc > 2000 ? 1.6 : 1);
		}
	}
	hp($o, 300);
}
# Crackle: little sharp snaps scattered through a window.
sub crackle {
	my ($dur, $rate, $lo, $hi) = @_;
	my $s = zeros($dur);
	my $n = int($dur * $rate);
	for (1 .. $n) {
		my $c = bp(noise(0.03), $lo + rand($hi - $lo), 3);
		expdecay($c, 0.002 + rand 0.006);
		mix($s, $c, rand($dur - 0.03), 0.4 + rand 0.8);
	}
	$s;
}
# A whoosh: noise through a band-pass sweeping from f0 to f1.
sub whoosh {
	my ($dur, $f0, $f1, $q) = @_;
	my $s = bp(noise($dur), sub { my $t = $_[0] / $dur; $f0 * ($f1 / $f0)**$t }, $q // 1.5);
	$s;
}

# ---------- the clips ----------
my %clip;

# Curse stage: the grandfather clock strikes, deep, with its hum.
$clip{clock_chime} = sub {
	my $s = bell(98, 7, 3.6, 0.8);
	my $hum = sine(7, 49);
	env($hum, [0, 0], [0.3, 0.25], [7, 0]);
	mix($s, $hum);
	$s = lp($s, 5200);
	reverb($s, 0.35, 0.85, 0.4, 3);
};
# Tick and tock of the clock (last twenty seconds; where a phantom stands).
for my $v ([clock_tick => 2600, 3900, 700], [clock_tock => 1750, 2900, 520]) {
	my ($name, $f1, $f2, $body) = @$v;
	$clip{$name} = sub {
		my $s = zeros(0.4);
		my $x = noise(0.06);
		expdecay($x, 0.0015);
		for ([$f1, 25, 0.03, 1], [$f2, 30, 0.02, 0.6], [$body, 6, 0.05, 0.9]) {
			my ($f, $q, $tau, $g) = @$_;
			my $r = bp($x, $f, $q);
			expdecay($r, $tau);
			mix($s, $r, 0, $g * $q / 6);
		}
		reverb($s, 0.28, 0.6, 0.5, 0.8);
	};
}
# The last stage: a swell out of nothing into a huge distorted toll and a sub boom.
$clip{curse_last} = sub {
	my $s = zeros(9);
	my $sw = whoosh(2, 200, 3000, 2);
	env($sw, [0, 0], [1.9, 1], [2, 0]);
	mix($s, $sw, 0, 0.5);
	my $b = tanhs(bell(73, 7, 4, 1.2), 1.8);
	mix($s, $b, 1.95, 0.9);
	mix($s, thud(80, 32, 4, 1.2), 1.95, 1.2);
	mix($s, growl('m_death_4', 0.45, 3), 2.1, 0.35);
	reverb(lp($s, 6000), 0.4, 0.9, 0.35, 3);
};
# The curse lifted at a booth: a warm, clear chime and a breath of relief.
$clip{curse_lifted} = sub {
	my $s = bell(523, 4, 1.6, 0.6);
	my $s2 = bell(784, 4, 1.2, 0.4);
	mix($s, $s2, 0.12, 0.5);
	my $air = bp(noise(1.5), 1200, 0.8);
	env($air, [0, 0], [0.3, 0.3], [1.5, 0]);
	mix($s, $air, 0, 0.3);
	reverb($s, 0.4, 0.8, 0.3, 2.5);
};
# Lifted into the air: a deep blow, then a rising, dissonant wall.
$clip{curse_float} = sub {
	my $s = zeros(5);
	mix($s, thud(70, 30, 3, 0.9), 0, 1);
	for my $f (82, 87, 123, 164.5, 174) {
		my $t = saw(4.5, sub { $f * (1 + 0.5 * $_[0] / 4.5) });
		$t = bp($t, sub { 500 + 900 * $_[0] / 4.5 }, 1.2);
		env($t, [0, 0], [2.5, 0.35], [4.2, 0.4], [4.5, 0]);
		mix($s, $t, 0.2, 0.5);
	}
	reverb($s, 0.4, 0.85, 0.4, 3);
};
# Bones cracking while the curse kills.
for my $k (1 .. 4) {
	$clip{"bone_crack_$k"} = sub {
		my $s = zeros(0.5);
		my $n = 3 + int rand 6;
		my $t = 0;
		for (1 .. $n) {
			my $c = bp(noise(0.04), 1800 + rand 3500, 2.5);
			expdecay($c, 0.002 + rand 0.004);
			mix($s, $c, $t, 0.6 + rand 0.6);
			my $w = hp(noise(0.01), 3000);
			expdecay($w, 0.001);
			mix($s, $w, $t, 0.5);
			$t += 0.005 + rand 0.02;
		}
		my $wet = lp(noise(0.25), 900);
		env($wet, [0, 0], [0.02, 0.4], [0.25, 0]);
		mix($s, $wet, 0.01, 0.5);
		mix($s, thud(180, 90, 0.2, 0.05), 0, 0.5);
		reverb($s, 0.15, 0.4, 0.5, 0.4);
	};
}

# A phantom looked at: a long, cold exhale, the reverb running backwards into it.
$clip{phantom_vanish} = sub {
	my $w = whisper('damien_black_refuse_2', 0.6);
	splice @$w, int(1.4 * $SR) if @$w > 1.4 * $SR;
	fade($w, 0.05, 0.4);
	my $air = bp(noise(1.6), sub { 900 - 400 * $_[0] / 1.6 }, 1.2);
	env($air, [0, 0], [0.25, 1], [1.6, 0]);
	my $s = zeros(1.6);
	mix($s, $air, 0, 0.8);
	mix($s, $w, 0, 0.6);
	my $pre = rev(reverb(rev([@$s[0 .. int(0.4 * $SR)]]), 1, 0.9, 0.3, 1.2));
	my $o = zeros(0);
	mix($o, $pre, 0, 0.5);
	mix($o, reverb($s, 0.4, 0.8, 0.4, 1.5), @$pre / $SR - 0.4, 1);
	$o;
};
# Voices in the head of the one he is watching through.
my @wl = (['viktor_eka_question_3', 'gracie_wise_refuse_4'], ['royston_min_request_2', 'tammie_ema_question_1'], ['craig_gutsy_refuse_5', 'ana_florence_agree_3'],
	['badr_odhiambo_question_4', 'sofia_hellen_request_3']);
for my $k (1 .. 4) {
	$clip{"whisper_$k"} = sub {
		my ($a, $b) = @{$wl[$k - 1]};
		my $s = zeros(0);
		mix($s, whisper($a, 0.82), 0, 1);
		mix($s, rev(whisper($b, 0.7)), 0.35, 0.6);
		fade($s, 0.1, 0.4);
		$s = biquad($s, 'peak', 3000, 1, 4);
		reverb($s, 0.45, 0.8, 0.5, 1.8);
	};
}

# The Eyes: a pull inwards, then a tight ring as he is inside them; and letting go.
$clip{eyes_open} = sub {
	my $s = zeros(2.6);
	my $in = rev(reverb(thud(120, 50, 0.6, 0.15), 1, 0.9, 0.3, 1.4));
	mix($s, $in, 0, 0.9);
	my $sw = whoosh(1.4, 4000, 300, 2);
	env($sw, [0, 0], [1.35, 0.7], [1.4, 0]);
	mix($s, $sw, 0, 0.6);
	my $at = @$in / $SR;
	mix($s, thud(90, 40, 1, 0.3), $at, 0.8);
	mix($s, bell(220, 2, 0.8, 0.5), $at, 0.3);
	reverb($s, 0.25, 0.7, 0.5, 1);
};
$clip{eyes_close} = sub {
	my $s = zeros(1.5);
	my $sw = whoosh(1.2, 300, 3500, 2);
	env($sw, [0, 0.7], [1.2, 0]);
	mix($s, $sw, 0, 0.6);
	mix($s, thud(60, 40, 0.6, 0.12), 0, 0.6);
	reverb($s, 0.35, 0.7, 0.5, 1);
};

# The Anchor: sucked out of sight; tearing in where he comes; showing himself with a toll and his voice.
$clip{anchor_vanish} = sub {
	my $hit = zeros(1.5);
	mix($hit, thud(110, 35, 1.2, 0.35), 0, 1);
	mix($hit, crackle(0.4, 60, 800, 4000), 0, 0.5);
	my $r = rev(reverb($hit, 0.7, 0.85, 0.3, 1.5));
	my $s = zeros(0);
	mix($s, $r, 0, 1);
	mix($s, thud(60, 30, 0.6, 0.12), @$r / $SR - 0.02, 0.7);
	$s;
};
$clip{anchor_arrive} = sub {
	my $s = zeros(3);
	mix($s, crackle(1.6, 90, 600, 3500), 0, 0.7);
	my $rum = lp(brown(3), 140);
	env($rum, [0, 0], [0.4, 1], [3, 0]);
	mix($s, $rum, 0, 1.4);
	my $tear = whoosh(1.2, 2500, 400, 3);
	env($tear, [0, 0], [0.1, 1], [1.2, 0]);
	mix($s, $tear, 0, 0.5);
	reverb($s, 0.3, 0.8, 0.5, 1.5);
};
$clip{anchor_reveal} = sub {
	my $s = zeros(6);
	mix($s, tanhs(bell(82, 6, 3, 1), 1.4), 0, 0.8);
	mix($s, thud(90, 35, 2, 0.6), 0, 1);
	mix($s, growl('m_death_8', 0.48, 3), 0.15, 0.7);
	reverb(lp($s, 7000), 0.3, 0.85, 0.4, 2.5);
};
# The target hears a far bell each second while he takes shape.
$clip{anchor_toll} = sub {
	my $s = bell(147, 2.5, 1.2, 0.5);
	$s = lp($s, 2500);
	reverb($s, 0.55, 0.9, 0.6, 2);
};

# The Grip.
$clip{grip_charge} = sub {
	my $s = zeros(1.4);
	my $d = saw(1.3, sub { 55 + 25 * $_[0] / 1.3 });
	$d = bp($d, sub { 250 + 1400 * ($_[0] / 1.3)**2 }, 2);
	env($d, [0, 0], [1.2, 1], [1.3, 0]);
	mix($s, $d, 0, 0.9);
	my $c = crackle(1.3, 40, 1500, 6000);
	env($c, [0, 0.1], [1.3, 1]);
	mix($s, $c, 0, 0.35);
	reverb($s, 0.2, 0.6, 0.5, 0.8);
};
$clip{grip_ready} = sub {
	my $s = zeros(2);
	for ([220, 0.5], [233, 0.4], [330, 0.35], [440, 0.15]) {
		my ($f, $g) = @$_;
		my $t = sine(1.8, $f);
		for my $i (0 .. $#$t) { $t->[$i] *= 0.7 + 0.3 * sin(2 * $PI * 7 * $i / $SR); }
		expdecay($t, 0.6);
		mix($s, $t, 0, $g);
	}
	mix($s, bell(660, 1.5, 0.4, 0.6), 0, 0.25);
	reverb($s, 0.35, 0.8, 0.4, 1.5);
};
# Thrown: the air sucked in and torn open with a crack of power, a metal shriek going down, a deep blow under it.
$clip{grip_cast} = sub {
	my $s = zeros(3);
	my $suck = whoosh(0.35, 300, 5000, 2);
	env($suck, [0, 0], [0.33, 1], [0.35, 0]);
	mix($s, $suck, 0, 0.5);
	mix($s, thud(62, 28, 2.2, 0.55), 0.33, 1.3);
	my $tear = crackle(0.5, 300, 1500, 8000);
	env($tear, [0, 1], [0.5, 0]);
	mix($s, $tear, 0.33, 0.9);
	for my $f (310, 327, 466) {
		my $sc = saw(1.6, sub { $f * 2**(-1.2 * $_[0] / 1.6) });
		$sc = bp($sc, sub { 2400 - 1500 * $_[0] / 1.6 }, 5);
		env($sc, [0, 0], [0.05, 1], [1.6, 0]);
		mix($s, $sc, 0.33, 0.25);
	}
	reverb(tanhs($s, 1.3), 0.3, 0.88, 0.4, 2);
};
# Flying (played every second, overlapping): a roaring column of power, fluttering, sizzling, a thin whine over a throb.
$clip{grip_hum} = sub {
	my $d = 1.5;
	my $s = zeros($d);
	my $roar = bp(noise($d), 520, 0.9);
	for my $i (0 .. $#$roar) { $roar->[$i] *= 0.65 + 0.35 * sin(2 * $PI * 11 * $i / $SR); }
	mix($s, $roar, 0, 0.9);
	mix($s, crackle($d, 70, 2500, 9000), 0, 0.45);
	my $throb = sine($d, 55);
	for my $i (0 .. $#$throb) { $throb->[$i] *= 0.6 + 0.4 * sin(2 * $PI * 3 * $i / $SR); }
	mix($s, $throb, 0, 0.7);
	my $whine = sine($d, sub { 1700 + 40 * sin(2 * $PI * 6 * $_[0]) });
	mix($s, $whine, 0, 0.06);
	$s = tanhs($s, 1.4);
	fade($s, 0.25, 0.25);
};
$clip{grip_fizzle} = sub {
	my $s = zeros(1.6);
	my $h = hp(noise(1.2), 2500);
	expdecay($h, 0.3);
	mix($s, $h, 0, 0.5);
	my $p = lp(noise(1), 400);
	env($p, [0, 0], [0.05, 1], [1, 0]);
	mix($s, $p, 0, 1);
	mix($s, thud(70, 40, 0.8, 0.2), 0, 0.6);
	reverb($s, 0.35, 0.8, 0.5, 1.4);
};
$clip{grip_catch} = sub {
	my $s = zeros(3);
	mix($s, thud(100, 40, 1.5, 0.35), 0, 1.1);
	# a dissonant "ahh" swelling
	for my $f (130.8, 138.6, 196, 207.7) {
		my $t = saw(2.6, $f);
		my $v = zeros(0);
		mix($v, bp($t, 700, 4), 0, 1);
		mix($v, bp($t, 1150, 5), 0, 0.6);
		mix($v, bp($t, 2600, 6), 0, 0.3);
		env($v, [0, 0], [0.35, 1], [2.6, 0]);
		mix($s, $v, 0.02, 0.6);
	}
	reverb($s, 0.35, 0.85, 0.4, 2);
};
# The pull: something drawn tight, creaking, a slow throb.
$clip{grip_pull} = sub {
	my $s = zeros(1);
	my $pulse = sine(1, 52);
	env($pulse, [0, 0], [0.1, 1], [1, 0]);
	mix($s, $pulse, 0, 0.8);
	my $cr = zeros(0.9);
	my ($t, $rate) = (0.05, 35);
	while ($t < 0.85) {
		my $c = bp(noise(0.02), 950, 9);
		expdecay($c, 0.004);
		mix($cr, $c, $t, 1);
		$t += 1 / ($rate + 25 * sin($t * 7));
	}
	mix($s, $cr, 0, 1.2);
	fade($s, 0.02, 0.15);
	reverb($s, 0.2, 0.6, 0.5, 0.6);
};
$clip{grip_release} = sub {
	my $s = zeros(1.6);
	my $w = whoosh(1.2, 2500, 200, 1.5);
	env($w, [0, 0.8], [1.2, 0]);
	mix($s, $w, 0, 0.7);
	my $g = sine(1.2, sub { 330 * 0.5**($_[0] / 1.2) });
	expdecay($g, 0.4);
	mix($s, $g, 0, 0.3);
	reverb($s, 0.35, 0.8, 0.5, 1.2);
};
$clip{grip_snap} = sub {
	my $s = zeros(2.5);
	my $c = hp(noise(0.02), 1500);
	expdecay($c, 0.003);
	mix($s, $c, 0, 1.5);
	mix($s, crackle(0.15, 200, 2000, 7000), 0, 0.6);
	mix($s, thud(130, 35, 2, 0.6), 0, 1.3);
	my $ring = sine(2, 3100);
	expdecay($ring, 0.6);
	mix($s, $ring, 0.05, 0.04);
	reverb($s, 0.35, 0.9, 0.4, 2);
};

# Bodies.
$clip{body_lift} = sub {
	my $s = zeros(0.9);
	my $cl = bp(noise(0.7), 2800, 0.8);
	for my $i (0 .. $#$cl) { $cl->[$i] *= abs sin(2 * $PI * 9 * $i / $SR + sin($i / 3000)); }
	env($cl, [0, 0], [0.1, 1], [0.7, 0]);
	mix($s, $cl, 0, 0.35);
	mix($s, thud(110, 60, 0.4, 0.08), 0.35, 0.8);
	reverb($s, 0.12, 0.5, 0.6, 0.5);
};
$clip{body_drop} = sub {
	my $s = zeros(1);
	mix($s, thud(95, 45, 0.8, 0.14), 0, 1.2);
	my $cl = bp(noise(0.4), 2200, 0.8);
	env($cl, [0, 0.8], [0.4, 0]);
	mix($s, $cl, 0.01, 0.3);
	mix($s, thud(80, 50, 0.4, 0.06), 0.13, 0.4);
	reverb($s, 0.15, 0.5, 0.6, 0.6);
};
# The first body down: the altar kindles.
$clip{altar_kindle} = sub {
	my $s = zeros(5);
	my $f = bp(noise(4.5), sub { 300 + 600 * $_[0] / 4.5 }, 0.9);
	env($f, [0, 0], [0.5, 1], [4.5, 0]);
	mix($s, $f, 0, 0.6);
	mix($s, crackle(4.5, 25, 700, 3500), 0, 0.5);
	for my $hz (65.4, 69.3, 98) {
		my $d = saw(4.5, $hz);
		$d = lp($d, 400);
		env($d, [0, 0], [1.2, 1], [4.5, 0]);
		mix($s, $d, 0, 0.35);
	}
	mix($s, bell(131, 4.5, 2, 0.8), 0.1, 0.4);
	reverb($s, 0.35, 0.85, 0.4, 2);
};
# The rift opens: the ground tears, a boom, his roar.
$clip{rift_open} = sub {
	my $s = zeros(10);
	mix($s, thud(65, 25, 6, 2), 0, 1.6);
	my $rum = lp(brown(9), 110);
	for my $i (0 .. $#$rum) { $rum->[$i] *= 0.7 + 0.3 * sin(2 * $PI * 0.8 * $i / $SR); }
	env($rum, [0, 0], [0.2, 1], [6, 0.6], [9, 0]);
	mix($s, $rum, 0, 2.5);
	my $tear = crackle(4, 120, 400, 3000);
	env($tear, [0, 1], [4, 0]);
	mix($s, $tear, 0, 0.8);
	mix($s, whoosh(3, 3000, 150, 2), 0, 0.5);
	mix($s, growl('m_scream_20', 0.32, 4.5), 0.4, 0.8);
	reverb(tanhs($s, 1.2), 0.35, 0.95, 0.4, 3);
};
# Thunder over the rift.
for my $k (1 .. 3) {
	$clip{"thunder_$k"} = sub {
		my $dur = 6 + $k;
		my $s = zeros($dur);
		my $crack = hp(noise(0.3), 800);
		env($crack, [0, 1], [0.3, 0]);
		mix($s, $crack, 0.05 * $k, 0.5);
		mix($s, crackle(0.8, 60, 300, 2500), 0.05 * $k, 0.6);
		my $rum = lp(brown($dur), 220);
		my $m = 0;
		for my $i (0 .. $#$rum) {
			$m = $m * 0.9995 + (rand() < 0.0004 ? 1 : 0) * 0.6 if $i % 1 == 0;
			$rum->[$i] *= 0.4 + $m;
		}
		env($rum, [0, 0], [0.15, 1], [$dur, 0]);
		mix($s, $rum, 0.05 * $k, 3);
		reverb(lp($s, 3500), 0.4, 0.95, 0.4, 3);
	};
}
# The rift's air: a low pad that never settles, wind, a far moan.
$clip{rift_drone} = sub {
	my $dur = 9;
	my $s = zeros($dur);
	for ([41.2, 1, 0.07], [61.7, 0.6, 0.11], [82.4, 0.45, 0.05], [87.3, 0.25, 0.13]) {
		my ($f, $g, $w) = @$_;
		mix($s, sine($dur, sub { $f * (1 + 0.006 * sin(2 * $PI * $w * $_[0])) }), 0, $g);
	}
	my $wind = bp(noise($dur), sub { 400 + 300 * sin(2 * $PI * 0.13 * $_[0]) }, 2);
	mix($s, $wind, 0, 0.6);
	my $moan = lp(growl('m_death_1', 0.35, 4), 900);
	mix($s, $moan, 3, 0.25);
	fade($s, 1.5, 2);
	reverb($s, 0.3, 0.9, 0.5, 2);
};

# ---------- the kills (game.VecnaKill) ----------
# Vines bursting out of him (or the ground): a wet tearing rush, gristle snapping, a low swell under it.
$clip{vine_burst} = sub {
	my $s = zeros(1.6);
	my $w = whoosh(0.5, 300, 2600, 1.2);
	env($w, [0, 0], [0.12, 1], [0.5, 0]);
	mix($s, $w, 0, 0.7);
	my $wet = bp(noise(0.7), sub { 600 + 900 * sin($_[0] * 30) ** 2 }, 1.5);
	env($wet, [0, 0], [0.05, 1], [0.7, 0]);
	mix($s, $wet, 0.02, 0.6);
	mix($s, crackle(0.35, 90, 900, 4500), 0.03, 0.7);
	mix($s, thud(90, 38, 1.2, 0.3), 0, 0.9);
	reverb(tanhs($s, 1.4), 0.25, 0.7, 0.5, 1);
};
# Vines writhing, sliding over skin and each other: slow wet squelches and creaks.
$clip{vine_slither} = sub {
	my $dur = 2.2;
	my $s = zeros($dur);
	my $body = lp(brown($dur), 500);
	for my $i (0 .. $#$body) { $body->[$i] *= 0.5 + 0.5 * sin(2 * $PI * (1.3 + 0.4 * sin($i / $SR * 2)) * $i / $SR) ** 2; }
	mix($s, $body, 0, 2.2);
	my $t = 0.05;
	while ($t < $dur - 0.2) {
		my $sq = bp(noise(0.12), 700 + rand 900, 4);
		env($sq, [0, 0], [0.03, 1], [0.12, 0]);
		mix($s, $sq, $t, 0.5 + rand 0.4);
		$t += 0.12 + rand 0.25;
	}
	my $cr = zeros($dur);
	$t = 0.3;
	while ($t < $dur - 0.3) {
		my $c = bp(noise(0.02), 1100 + rand 500, 9);
		expdecay($c, 0.005);
		mix($cr, $c, $t, 1);
		$t += 0.03 + rand 0.06;
	}
	env($cr, [0, 0], [0.6, 0.6], [1.6, 0.3], [$dur, 0]);
	mix($s, $cr, 0, 0.8);
	fade($s, 0.1, 0.4);
	reverb($s, 0.2, 0.6, 0.5, 0.8);
};
# Run through: a wet punch, crunching ribs, a spatter.
$clip{impale} = sub {
	my $s = zeros(1.0);
	mix($s, thud(150, 55, 0.6, 0.09), 0, 1.2);
	my $p = bp(noise(0.12), 380, 1.2);
	expdecay($p, 0.03);
	mix($s, $p, 0, 1.0);
	mix($s, crackle(0.12, 260, 1500, 5000), 0.01, 0.8);
	my $sp = bp(noise(0.5), 2400, 0.8);
	env($sp, [0, 0], [0.04, 0.6], [0.5, 0]);
	mix($s, $sp, 0.05, 0.45);
	reverb($s, 0.18, 0.5, 0.6, 0.6);
};
# The neck: one sharp, dry, loud crack and the grind of bone after it.
$clip{neck_snap} = sub {
	my $s = zeros(1.4);
	my $c = hp(noise(0.015), 1200);
	expdecay($c, 0.0025);
	mix($s, $c, 0, 1.8);
	my $c2 = bp(noise(0.03), 2600, 2);
	expdecay($c2, 0.004);
	mix($s, $c2, 0.004, 1.2);
	mix($s, thud(200, 90, 0.3, 0.05), 0, 0.6);
	my $grind = zeros(0.4);
	my $t = 0.0;
	while ($t < 0.35) {
		my $g = bp(noise(0.015), 1400 + rand 1600, 6);
		expdecay($g, 0.003);
		mix($grind, $g, $t, 0.6);
		$t += 0.012 + rand 0.02;
	}
	env($grind, [0, 1], [0.4, 0]);
	mix($s, $grind, 0.06, 0.6);
	reverb($s, 0.25, 0.7, 0.4, 1.2);
};
# The ground splitting under them: a deep crack, earth grinding open, a breath of the rift from below.
$clip{ground_split} = sub {
	my $s = zeros(4.5);
	my $c = hp(noise(0.05), 600);
	expdecay($c, 0.01);
	mix($s, $c, 0, 1.0);
	mix($s, thud(70, 28, 3, 0.9), 0, 1.6);
	my $rum = lp(brown(3.5), 160);
	env($rum, [0, 0], [0.08, 1], [1.5, 0.6], [3.5, 0]);
	mix($s, $rum, 0, 2.6);
	my $tear = crackle(1.8, 70, 300, 2400);
	env($tear, [0, 1], [1.8, 0]);
	mix($s, $tear, 0.02, 0.8);
	my $breath = bp(noise(3), sub { 200 + 400 * $_[0] / 3 }, 1.5);
	env($breath, [0, 0], [0.8, 0.6], [3, 0]);
	mix($s, $breath, 0.5, 0.5);
	my $moan = bp(saw(2.4, sub { 58 * (1 - 0.15 * $_[0] / 2.4) }), 420, 2);
	env($moan, [0, 0], [0.7, 1], [2.4, 0]);
	mix($s, $moan, 0.9, 0.35);
	reverb(tanhs($s, 1.2), 0.35, 0.9, 0.4, 2);
};
# A wardrobe shaking: its doors rattling hard in their frame, faster and faster, wood creaking.
$clip{locker_rattle} = sub {
	my $s = zeros(1.4);
	my $t = 0.0;
	my $gap = 0.11;
	while ($t < 1.15) {
		my $k = bp(noise(0.05), 380 + rand 260, 3);
		expdecay($k, 0.012);
		mix($s, $k, $t, 0.8 + rand 0.4);
		my $h = bp(noise(0.03), 1800 + rand 900, 5);
		expdecay($h, 0.004);
		mix($s, $h, $t + 0.003, 0.35);
		$t += $gap;
		$gap = $gap * 0.93 < 0.035 ? 0.035 : $gap * 0.93;
	}
	my $cr = bp(noise(1.2), 520, 12);
	env($cr, [0, 0], [0.6, 0.4], [1.2, 0]);
	mix($s, $cr, 0.1, 0.5);
	reverb($s, 0.15, 0.5, 0.5, 0.6);
};

my @want = @ARGV ? @ARGV : sort keys %clip;
write_clip($_, $clip{$_}->()) for @want;
