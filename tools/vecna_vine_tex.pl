#!/usr/bin/perl
# The texture of Vecna's vines (client.VecnaVines draws them as tubes): u goes round a vine, v along it, and it
# repeats both ways. Near-black flesh of the Upside Down with a dark wine mottle, sinewy fibres running along it,
# knotted rings every so often, a wet sheen down one side; and, on a layer of its own drawn at full brightness, thin
# veins of raw red fire wandering along under the skin.
#
#   perl tools/vecna_vine_tex.pl src/main/resources/assets/halloween/textures/entity
use strict;
use warnings;
use Compress::Zlib;

my $dir = shift or die "usage: vecna_vine_tex.pl <textures/entity dir>\n";
my ($W, $H) = (32, 128);
my $PI = 3.14159265358979;
srand(1983);

# Tileable value noise on the (W x H) torus.
my %lat;
sub lat { my ($x, $y, $p, $q) = @_; $x %= $p; $y %= $q; return $lat{"$p,$q,$x,$y"} //= rand(); }
sub vnoise {
	my ($u, $v, $p, $q) = @_;    # u, v in lattice cells; p, q cells across (wraps)
	my ($x0, $y0) = (int($u), int($v));
	my ($fx, $fy) = ($u - $x0, $v - $y0);
	$fx = $fx * $fx * (3 - 2 * $fx);
	$fy = $fy * $fy * (3 - 2 * $fy);
	my $a = lat($x0, $y0, $p, $q) + (lat($x0 + 1, $y0, $p, $q) - lat($x0, $y0, $p, $q)) * $fx;
	my $b = lat($x0, $y0 + 1, $p, $q) + (lat($x0 + 1, $y0 + 1, $p, $q) - lat($x0, $y0 + 1, $p, $q)) * $fx;
	return $a + ($b - $a) * $fy;
}
sub fbm {
	my ($x, $y) = @_;    # pixels
	return 0.5 * vnoise($x / 8, $y / 8, $W / 8, $H / 8) + 0.3 * vnoise($x / 4, $y / 4, $W / 4, $H / 4) + 0.2 * vnoise($x / 2, $y / 2, $W / 2, $H / 2);
}
sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }
sub mix { my ($a, $b, $t) = @_; return [map { $a->[$_] + ($b->[$_] - $a->[$_]) * $t } 0 .. 2]; }

my (@skin, @glow);
my @dark = (16, 7, 8);
my @wine = (52, 14, 16);
my @sheen = (120, 70, 72);
for my $y (0 .. $H - 1) {
	for my $x (0 .. $W - 1) {
		my $u = $x / $W;
		my $n = fbm($x, $y);
		my $c = mix(\@dark, \@wine, $n * 0.9);
		# Fibres along the vine.
		my $fib = sin($u * 2 * $PI * 7 + sin($y / $H * 2 * $PI * 3) * 1.3);
		$c = mix($c, [8, 3, 4], 0.35) if $fib > 0.75;
		$c = mix($c, [70, 26, 26], 0.25) if $fib < -0.85;
		# Knotted rings: every 32 px, a swollen band, bumpy, darker in its folds.
		my $ring = ($y % 32) / 32;
		my $rd = abs($ring - 0.5) * 2;    # 1 at the band edges, 0 in the middle
		if ($rd < 0.18) {
			my $bump = 0.5 + 0.5 * sin($u * 2 * $PI * 5 + int($y / 32) * 1.7);
			$c = mix($c, [64, 22, 22], (1 - $rd / 0.18) * 0.5 * $bump);
			$c = mix($c, [6, 2, 3], 0.5) if $rd > 0.12;
		}
		# The wet sheen down one side.
		my $s = exp(-(($u - 0.28) / 0.06)**2) * (0.55 + 0.45 * vnoise($x / 2, $y / 6, $W / 2, $H / 6));
		$c = mix($c, \@sheen, $s * 0.7);
		push @skin, [map({ clamp($_) } @$c), 255];

		# Veins of fire: three wandering threads (they wrap round in u, and repeat along v).
		my $g = 0;
		for my $k (0 .. 2) {
			my $centre = 0.12 + $k * 0.33 + 0.07 * sin($y / $H * 2 * $PI * (2 + $k) + $k * 2.1) + 0.03 * sin($y / $H * 2 * $PI * 7 + $k);
			my $d = abs($u - $centre);
			$d = 1 - $d if $d > 0.5;
			my $width = 0.022 + 0.012 * sin($y / $H * 2 * $PI * 5 + $k * 3);
			my $line = exp(-($d / $width)**2);
			# Brighter in pulses along it, fading in places (under thicker skin).
			my $pulse = 0.45 + 0.55 * vnoise($y / 6 + $k * 7, $k * 3, $H / 6, 9);
			$g = $line * $pulse if $line * $pulse > $g;
		}
		# Little branches off the veins.
		my $br = sin($u * 2 * $PI * 3 + $y / $H * 2 * $PI * 9);
		$g = 0.35 * (1 - abs($br) * 4) if abs($br) < 0.06 && fbm($x * 2, $y) > 0.55 && 0.35 > $g;
		my $a = clamp($g * 255 * 1.1);
		push @glow, [255, clamp(60 + 120 * $g), clamp(20 + 40 * $g), $a < 10 ? 0 : $a];
	}
}

sub png {
	my ($px) = @_;
	my $raw = '';
	for my $y (0 .. $H - 1) {
		$raw .= "\0";
		$raw .= pack("C4", @{$px->[$y * $W + $_]}) for 0 .. $W - 1;
	}
	my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
	return "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $W, $H, 8, 6, 0, 0, 0)) . $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
}
for ([\@skin, "vecna_vine.png"], [\@glow, "vecna_vine_glow.png"]) {
	open my $o, ">:raw", "$dir/$_->[1]" or die "$dir/$_->[1]: $!";
	print $o png($_->[0]);
	close $o;
	print "$dir/$_->[1]\n";
}
