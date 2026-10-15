#!/usr/bin/perl
# The heart the Demogorgon sees over a house with someone in it (client HwHud.heart): an anatomical heart, dark
# and wet, 48x48. A swollen, lopsided muscle tipped down to the left, the great vessels standing up out of the top
# (the aorta arching right, the pulmonary trunk, cut veins), coronary veins over it, pale fat in the grooves, a
# black rim; lit from the upper left. Written as a PNG.
#
#   perl tools/heart_texture.pl src/main/resources/assets/halloween/textures/gui/heart.png
use strict;
use warnings;
use Compress::Zlib;

my $out = shift or die "usage: heart_texture.pl out.png [preview scale]\n";
# A preview: blown up, on a dark ground.
my $SC = shift // 1;
my $N = 48;
my @px = map { [0, 0, 0, 0] } 1 .. $N * $N;
srand(1978);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }

sub mix { my ($a, $b, $t) = @_; return [map { $a->[$_] + ($b->[$_] - $a->[$_]) * $t } 0 .. 2]; }

my %C = (
	deep => [44, 4, 8], body => [92, 10, 18], lit => [150, 26, 34], shine => [214, 120, 116],
	vein => [26, 6, 22], vessel => [118, 30, 40], vesselD => [58, 10, 18], fat => [150, 128, 92], rim => [8, 0, 2],
);

# Inside the muscle: an egg tipped over (wide at the top right, the apex down at the lower left), the top dented
# between the two atria.
sub inBody {
	my ($x, $y) = @_;
	my ($cx, $cy) = (24.5, 27);
	my $a = -0.55;
	my $dx = $x - $cx;
	my $dy = $y - $cy;
	my $u = $dx * cos($a) - $dy * sin($a);
	my $v = $dx * sin($a) + $dy * cos($a);
	# Narrower towards the apex (v > 0).
	my $rx = 13.5 - ($v > 0 ? $v * 0.42 : 0);
	my $ry = 15;
	my $inside = ($u / $rx) ** 2 + ($v / $ry) ** 2 <= 1;
	# The dent at the top between the two halves.
	my $dent = ($x - 22) ** 2 / 9 + ($y - 13) ** 2 / 10 <= 1;
	return $inside && !$dent;
}

# The vessels: tubes out of the top. [x, y bottom, y top, radius, bend right per pixel up]
my @vessels = ([27, 18, 6, 3.6, 0.3], [21, 16, 6, 2.6, -0.15], [33, 20, 11, 1.8, 0.25], [16, 20, 12, 1.6, -0.35]);

sub inVessel {
	my ($x, $y) = @_;
	for my $i (0 .. $#vessels) {
		my ($vx, $y0, $y1, $r, $bend) = @{$vessels[$i]};
		next if $y > $y0 || $y < $y1;
		my $cx = $vx + ($y0 - $y) * $bend * ($i == 0 && $y < 10 ? 1.4 : 1);
		return $i + 1 if abs($x + 0.5 - $cx) <= $r;
	}
	return 0;
}

my @shape = map { [(0) x $N] } 1 .. $N;
for my $y (0 .. $N - 1) {
	for my $x (0 .. $N - 1) {
		$shape[$y][$x] = inBody($x, $y) ? 1 : inVessel($x, $y) ? 2 : 0;
	}
}

# Paint: shading from the upper left, mottling, the coronary veins and fat in the groove.
for my $y (0 .. $N - 1) {
	for my $x (0 .. $N - 1) {
		my $s = $shape[$y][$x];
		next unless $s;
		my $light = (48 - $x - $y * 0.8) / 48;
		my $c;
		if ($s == 1) {
			$c = $light > 0.35 ? mix($C{body}, $C{lit}, ($light - 0.35) * 2) : mix($C{deep}, $C{body}, $light / 0.35 + 0.1);
		} else {
			$c = mix($C{vesselD}, $C{vessel}, $light + 0.3);
		}
		my $n = (rand() - 0.5) * 18;
		$c = [map { $_ + $n } @$c];
		$px[$y * $N + $x] = [@$c, 255];
	}
}

sub put {
	my ($x, $y, $c, $k) = @_;
	return if $x < 0 || $y < 0 || $x >= $N || $y >= $N || !$shape[$y][$x];
	my $p = $px[$y * $N + $x];
	my $m = mix($p, $C{$c}, $k // 1);
	$px[$y * $N + $x] = [@$m, 255];
}

# The groove down the front (the interventricular), pale fat in it, a vein along it.
for my $t (0 .. 30) {
	my $x = 30 - $t * 0.55 + sin($t * 0.4) * 0.8;
	my $y = 17 + $t * 0.75;
	put(int($x) - 1, int($y), 'fat', 0.55);
	put(int($x) + 1, int($y), 'fat', 0.45);
	put(int($x), int($y), 'vein', 0.85);
}
# Coronary veins branching over the muscle.
for my $b (0 .. 9) {
	my $x = 14 + rand(20);
	my $y = 16 + rand(8);
	my $dir = rand() < 0.5 ? -1 : 1;
	for my $t (0 .. 14 + int(rand(10))) {
		$x += $dir * (0.3 + rand(0.5));
		$y += 0.6 + rand(0.5);
		put(int($x), int($y), 'vein', 0.75);
	}
}
# A wet shine on the upper left of the muscle, and on the aorta.
for my $y (0 .. $N - 1) {
	for my $x (0 .. $N - 1) {
		next unless $shape[$y][$x];
		my $d1 = ($x - 16) ** 2 / 10 + ($y - 21) ** 2 / 5;
		my $d2 = ($x - 25) ** 2 / 2 + ($y - 10) ** 2 / 6;
		put($x, $y, 'shine', 0.4 * (1 - $d1)) if $d1 < 1;
		put($x, $y, 'shine', 0.5 * (1 - $d2)) if $d2 < 1 && $shape[$y][$x] == 2;
	}
}
# The cut ends of the vessels: dark holes.
for my $v (@vessels) {
	my ($vx, $y0, $y1, $r, $bend) = @$v;
	my $cx = $vx + ($y0 - $y1) * $bend;
	for my $dx (-1 .. 1) {
		put(int($cx + $dx), $y1, 'rim', 0.9);
		put(int($cx + $dx), $y1 + 1, 'deep', 0.6);
	}
}
# A black rim all round, a drip running off the apex.
my @rim;
for my $y (0 .. $N - 1) {
	for my $x (0 .. $N - 1) {
		next if $shape[$y][$x];
		for my $d ([1, 0], [-1, 0], [0, 1], [0, -1]) {
			my ($nx, $ny) = ($x + $d->[0], $y + $d->[1]);
			if ($nx >= 0 && $ny >= 0 && $nx < $N && $ny < $N && $shape[$ny][$nx]) {
				push @rim, [$x, $y];
				last;
			}
		}
	}
}
$px[$_->[1] * $N + $_->[0]] = [@{$C{rim}}, 230] for @rim;
my ($ax, $ay) = (13, 40);
for my $y ($ay .. $ay + 6) {
	my $w = $y < $ay + 5 ? 1 : 2;
	for my $x ($ax .. $ax + $w - 1) {
		$px[$y * $N + $x] = [@{mix($C{body}, $C{deep}, ($y - $ay) / 7)}, 255];
	}
}

# PNG out.
my $M = $N * $SC;
my $raw = '';
for my $y (0 .. $M - 1) {
	$raw .= "\0";
	for my $x (0 .. $M - 1) {
		my $p = $px[int($y / $SC) * $N + int($x / $SC)];
		$raw .= pack("C4", $SC > 1 && !$p->[3] ? (28, 26, 30, 255) : map { clamp($_) } @$p);
	}
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $o, '>:raw', $out or die "$out: $!";
print $o "\x89PNG\r\n\x1a\n", $chunk->("IHDR", pack("NNCCCCC", $M, $M, 8, 6, 0, 0, 0)), $chunk->("IDAT", compress($raw)), $chunk->("IEND", "");
close $o;
print "$out: ${N}x$N\n";
