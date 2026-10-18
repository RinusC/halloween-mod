#!/usr/bin/perl
# The storm cloud hung under the Upside Down when it is up in the sky (UpsideDown.clouds): soft dark billows,
# heaviest in the middle and thinning out to nothing at the edges, so the patches overlap without seams.
#
#   perl tools/cloud_texture.pl src/main/resources/assets/halloween/textures/item/storm_cloud.png
use strict;
use warnings;
use Compress::Zlib;

my $out = shift or die "usage: cloud_texture.pl <out.png>\n";
my $M = 128;
srand(1031);
my @g;
for my $o (0 .. 4) {
	my $n = 4 << $o;
	$g[$o] = [map { rand() } 1 .. $n * $n];
}
sub noise {
	my ($x, $y, $o) = @_;
	my $n = 4 << $o;
	my ($fx, $fy) = ($x * $n, $y * $n);
	my ($ix, $iy) = (int($fx), int($fy));
	my ($tx, $ty) = ($fx - $ix, $fy - $iy);
	$tx = $tx * $tx * (3 - 2 * $tx);
	$ty = $ty * $ty * (3 - 2 * $ty);
	my $v = sub { $g[$o][($_[1] % $n) * $n + ($_[0] % $n)] };
	my $a = $v->($ix, $iy) + ($v->($ix + 1, $iy) - $v->($ix, $iy)) * $tx;
	my $b = $v->($ix, $iy + 1) + ($v->($ix + 1, $iy + 1) - $v->($ix, $iy + 1)) * $tx;
	return $a + ($b - $a) * $ty;
}
sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }
my $raw = '';
for my $y (0 .. $M - 1) {
	$raw .= "\0";
	for my $x (0 .. $M - 1) {
		my ($u, $w) = ($x / $M, $y / $M);
		my ($f, $amp) = (0, 0.5);
		for my $o (0 .. 4) {
			$f += noise($u, $w, $o) * $amp;
			$amp /= 2;
		}
		my $d = sqrt(($u - 0.5) ** 2 + ($w - 0.5) ** 2) / 0.5;
		my $edge = $d >= 1 ? 0 : (1 - $d * $d);
		my $a = ($f - 0.32) * 2.4 * $edge + $edge * 0.35;
		$a = $a < 0 ? 0 : $a > 1 ? 1 : $a;
		# Darker underneath the billows, a dull red where they thin (the lightning inside).
		my $s = 26 + $f * 40;
		push my @c, $s + (1 - $a) * 18, $s * 0.9, $s * 1.05;
		$raw .= pack("C4", (map { clamp($_) } @c), clamp($a * 235));
	}
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $o, '>:raw', $out or die "$out: $!";
print $o "\x89PNG\r\n\x1a\n", $chunk->("IHDR", pack("NNCCCCC", $M, $M, 8, 6, 0, 0, 0)), $chunk->("IDAT", compress($raw)), $chunk->("IEND", "");
close $o;
print "$out\n";
