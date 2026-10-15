#!/usr/bin/perl
# The Demogorgon's rift (as in Stranger Things): a tear in the air, 64x64. A jagged split down the middle glowing
# white-hot to red, a ragged membrane round it, dark red and see-through, strands and fibres stretched across it,
# flecks of light drifting in it. Shown standing upright facing whoever looks (game.Demogorgon), stretched tall.
#
#   perl tools/rift_texture.pl src/main/resources/assets/halloween/textures/item/rift.png [preview scale]
use strict;
use warnings;
use Compress::Zlib;

my $out = shift or die "usage: rift_texture.pl out.png [preview scale]\n";
my $SC = shift // 1;
my $N = 64;
my @px = map { [0, 0, 0, 0] } 1 .. $N * $N;
srand(1983);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }

# Blends a colour over what is there.
sub over {
	my ($x, $y, $r, $g, $b, $a) = @_;
	return if $x < 0 || $y < 0 || $x >= $N || $y >= $N || $a <= 0;
	my $p = $px[$y * $N + $x];
	my $oa = $p->[3] / 255;
	my $na = $a / 255;
	my $ta = $na + $oa * (1 - $na);
	return if $ta <= 0;
	$px[$y * $N + $x] = [($r * $na + $p->[0] * $oa * (1 - $na)) / $ta, ($g * $na + $p->[1] * $oa * (1 - $na)) / $ta,
		($b * $na + $p->[2] * $oa * (1 - $na)) / $ta, $ta * 255];
}

# The split: x of its middle at each row, wandering, and how wide it gapes (widest in the middle).
my @mid;
my $x = 32;
for my $y (0 .. $N - 1) {
	$x += (rand() - 0.5) * 2.2;
	$x = 28 if $x < 28;
	$x = 36 if $x > 36;
	push @mid, $x;
}
sub gape { my $y = shift; my $t = ($y - 32) / 32; return 2.6 * (1 - $t * $t) + 0.4; }

# The membrane: ragged, widest round the middle, see-through dark red, darker towards its edge.
for my $y (0 .. $N - 1) {
	my $t = ($y - 32) / 32;
	my $half = 24 * sqrt(1 - $t * $t * 0.92) + (rand() - 0.5) * 6;
	for my $xx (0 .. $N - 1) {
		my $d = abs($xx - $mid[$y]) / ($half || 1);
		next if $d > 1;
		my $edge = $d ** 1.6;
		my $a = 200 * (1 - $edge) * (0.75 + rand() * 0.25);
		over($xx, $y, 90 + 70 * (1 - $d), 10 + 12 * (1 - $d), 14, $a);
	}
}
# Strands across it: thin, darker, curving.
for (1 .. 26) {
	my $y0 = int(rand($N));
	my $side = rand() < 0.5 ? -1 : 1;
	my $len = 8 + int(rand(18));
	my ($sx, $sy) = ($mid[$y0] + $side * 2, $y0);
	my $dy = (rand() - 0.5) * 1.2;
	for my $k (0 .. $len) {
		$sx += $side * 1;
		$sy += $dy + sin($k * 0.4) * 0.3;
		over(int($sx), int($sy), 40, 4, 8, 160);
	}
}
# Fibres glowing faintly along it.
for (1 .. 14) {
	my $y0 = int(rand($N));
	my ($sx, $sy) = ($mid[$y0], $y0);
	my $side = rand() < 0.5 ? -1 : 1;
	for my $k (0 .. 10 + int(rand(10))) {
		$sx += $side * (0.6 + rand(0.6));
		$sy += (rand() - 0.5) * 1.6;
		over(int($sx), int($sy), 255, 70, 50, 140);
	}
}
# The split itself: a red glow, then orange, a white-hot thread down the middle.
for my $y (0 .. $N - 1) {
	my $g = gape($y);
	for my $xx (0 .. $N - 1) {
		my $d = abs($xx + 0.5 - $mid[$y]);
		if ($d < $g * 3.2) {
			over($xx, $y, 255, 30, 20, 150 * (1 - $d / ($g * 3.2)));
		}
		if ($d < $g * 1.4) {
			over($xx, $y, 255, 90, 40, 230 * (1 - $d / ($g * 1.4)) + 25);
		}
		if ($d < $g * 0.45) {
			over($xx, $y, 255, 230, 200, 255);
		}
	}
}
# Flecks drifting in it.
for (1 .. 40) {
	my $y = int(rand($N));
	my $xx = int($mid[$y] + (rand() - 0.5) * 30);
	over($xx, $y, 255, 140 + int(rand(80)), 90, 200);
}

my $M = $N * $SC;
my $raw = '';
for my $y (0 .. $M - 1) {
	$raw .= "\0";
	for my $xx (0 .. $M - 1) {
		my $p = $px[int($y / $SC) * $N + int($xx / $SC)];
		if ($SC > 1) {
			# The preview: over a dark wall.
			my $a = $p->[3] / 255;
			$raw .= pack("C4", map({ clamp($p->[$_] * $a + 22 * (1 - $a)) } 0 .. 2), 255);
		} else {
			$raw .= pack("C4", map { clamp($_) } @$p);
		}
	}
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $o, '>:raw', $out or die "$out: $!";
print $o "\x89PNG\r\n\x1a\n", $chunk->("IHDR", pack("NNCCCCC", $M, $M, 8, 6, 0, 0, 0)), $chunk->("IDAT", compress($raw)), $chunk->("IEND", "");
close $o;
print "$out: ${M}x$M\n";
