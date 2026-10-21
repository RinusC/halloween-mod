#!/usr/bin/perl
# Vecna's particle textures (white, soft alpha; tinted in client.VecnaParticles):
#   perl tools/vecna_particles.pl src/main/resources/assets/halloween/textures/particle
# smoke_0..3 (64 px soft clouds), mist (64 px, very soft), ember (32 px glowing dot), ash_0..3 (16 px flakes),
# spore (32 px dot with a halo).
use strict;
use warnings;
use Compress::Zlib;

my $dir = shift or die "usage: vecna_particles.pl <texture dir>\n";
srand(666);

sub png {
	my ($name, $n, $px) = @_;
	my $raw = '';
	for my $y (0 .. $n - 1) {
		$raw .= "\0";
		for my $x (0 .. $n - 1) {
			my ($v, $a) = @{$px->[$y][$x]};
			$v = $v < 0 ? 0 : $v > 1 ? 1 : $v;
			$a = $a < 0 ? 0 : $a > 1 ? 1 : $a;
			my $c = int($v * 255 + 0.5);
			$raw .= pack 'C4', $c, $c, $c, int($a * 255 + 0.5);
		}
	}
	my $chunk = sub { my ($t, $d) = @_; pack('N', length $d) . $t . $d . pack('N', crc32($t . $d)) };
	open my $o, '>:raw', "$dir/vecna_$name.png" or die $!;
	print $o "\x89PNG\r\n\x1a\n", $chunk->('IHDR', pack('NNCCCCC', $n, $n, 8, 6, 0, 0, 0)), $chunk->('IDAT', compress($raw)), $chunk->('IEND', '');
	close $o;
	print "vecna_$name.png\n";
}

# Tileable-enough value noise, summed in octaves.
sub noise_field {
	my ($n, $cells) = @_;
	my @g = map { [map { rand } 0 .. $cells] } 0 .. $cells;
	return sub {
		my ($x, $y) = @_;
		my ($fx, $fy) = ($x / $n * $cells, $y / $n * $cells);
		my ($ix, $iy) = (int $fx, int $fy);
		my ($tx, $ty) = ($fx - $ix, $fy - $iy);
		$_ = $_ * $_ * (3 - 2 * $_) for $tx, $ty;
		my $a = $g[$iy][$ix] * (1 - $tx) + $g[$iy][$ix + 1] * $tx;
		my $b = $g[$iy + 1][$ix] * (1 - $tx) + $g[$iy + 1][$ix + 1] * $tx;
		return $a * (1 - $ty) + $b * $ty;
	};
}
sub fbm {
	my $n = shift;
	my @o = map { noise_field($n, 2 ** ($_ + 2)) } 0 .. 3;
	return sub { my ($x, $y) = @_; my ($s, $w) = (0, 0.5); for (@o) { $s += $_->($x, $y) * $w; $w /= 2; } $s / 0.9375 };
}

# Smoke: a lumpy cloud, denser and lighter in the middle, frayed at the edge.
for my $k (0 .. 3) {
	my $n = 64;
	my $f = fbm($n);
	my $px;
	my ($ox, $oy) = (rand(0.12) - 0.06, rand(0.12) - 0.06);
	for my $y (0 .. $n - 1) {
		for my $x (0 .. $n - 1) {
			my ($u, $v) = (($x + 0.5) / $n - 0.5 - $ox, ($y + 0.5) / $n - 0.5 - $oy);
			my $r = sqrt($u * $u + $v * $v) * 2;
			my $nz = $f->($x, $y);
			my $edge = 1 - $r + ($nz - 0.5) * 0.9;
			my $a = $edge <= 0 ? 0 : $edge >= 0.55 ? 1 : ($edge / 0.55)**1.6;
			$a *= 0.55 + 0.45 * $nz;
			my $shade = 0.6 + 0.4 * $nz - 0.15 * $r;
			$px->[$y][$x] = [$shade, $a];
		}
	}
	png("smoke_$k", $n, $px);
}

# Mist: a wide, very soft blob with faint billows.
{
	my $n = 64;
	my $f = fbm($n);
	my $px;
	for my $y (0 .. $n - 1) {
		for my $x (0 .. $n - 1) {
			my ($u, $v) = (($x + 0.5) / $n - 0.5, ($y + 0.5) / $n - 0.5);
			my $r = sqrt($u * $u + $v * $v) * 2;
			my $a = $r >= 1 ? 0 : (1 - $r)**2 * (0.6 + 0.4 * $f->($x, $y));
			$px->[$y][$x] = [1, $a];
		}
	}
	png('mist', $n, $px);
}

# Ember: a hot core with a glow falling off round it.
{
	my $n = 32;
	my $px;
	for my $y (0 .. $n - 1) {
		for my $x (0 .. $n - 1) {
			my ($u, $v) = (($x + 0.5) / $n - 0.5, ($y + 0.5) / $n - 0.5);
			my $r = sqrt($u * $u + $v * $v) * 2;
			my $a = exp(-($r / 0.22)**2) + 0.45 * exp(-($r / 0.6)**2);
			$px->[$y][$x] = [0.7 + 0.3 * exp(-($r / 0.2)**2), $a];
		}
	}
	png('ember', $n, $px);
}

# Ash: small torn flakes, darker at the edges.
for my $k (0 .. 3) {
	my $n = 16;
	my @rad = map { 0.55 + rand 0.4 } 0 .. 6;
	my $rot = rand 6.283;
	my $px;
	for my $y (0 .. $n - 1) {
		for my $x (0 .. $n - 1) {
			my ($u, $v) = (($x + 0.5) / $n - 0.5, ($y + 0.5) / $n - 0.5);
			my $r = sqrt($u * $u + $v * $v) * 2;
			my $ang = atan2($v, $u) + $rot;
			$ang += 6.283 while $ang < 0;
			my $s = $ang / 6.283 * 7;
			my $i = int($s) % 7;
			my $t = $s - int $s;
			my $lim = ($rad[$i] * (1 - $t) + $rad[($i + 1) % 7] * $t) * 0.85;
			my $a = $r < $lim - 0.12 ? 1 : $r < $lim ? ($lim - $r) / 0.12 : 0;
			$px->[$y][$x] = [0.85 - 0.35 * ($r / $lim), $a];
		}
	}
	png("ash_$k", $n, $px);
}

# Spore: a pale soft speck with a faint halo.
{
	my $n = 32;
	my $px;
	for my $y (0 .. $n - 1) {
		for my $x (0 .. $n - 1) {
			my ($u, $v) = (($x + 0.5) / $n - 0.5, ($y + 0.5) / $n - 0.5);
			my $r = sqrt($u * $u + $v * $v) * 2;
			$px->[$y][$x] = [1, 0.9 * exp(-($r / 0.28)**2) + 0.25 * exp(-($r / 0.7)**2)];
		}
	}
	png('spore', $n, $px);
}
