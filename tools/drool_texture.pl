#!/usr/bin/perl
# The Demogorgon's slaver (particle halloween:drool): a long thin drop, glassy, pale and a little yellow, see-through
# at the edges, brighter down its middle.
#
#   perl tools/drool_texture.pl src/main/resources/assets/halloween/textures/particle/drool.png
use strict;
use warnings;
use Compress::Zlib;

my $out = shift or die "usage: drool_texture.pl <out.png>\n";
my ($W, $H) = (8, 16);
my $raw = '';
for my $y (0 .. $H - 1) {
	$raw .= "\0";
	for my $x (0 .. $W - 1) {
		# Thin at the top (a strand), swelling to a drop at the bottom.
		my $t = $y / ($H - 1);
		my $r = 0.6 + 2.6 * $t ** 3;
		my $dx = abs($x + 0.5 - $W / 2);
		my $a = $dx < $r ? 1 - ($dx / $r) ** 2 : 0;
		$a = 0 if $y == $H - 1 && $dx > 2;
		my $shine = $dx < $r * 0.35 ? 30 : 0;
		$raw .= pack("C4", 205 + $shine > 255 ? 255 : 205 + $shine, 210 + $shine > 255 ? 255 : 210 + $shine, 170 + $shine, int($a * 200));
	}
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $o, '>:raw', $out or die "$out: $!";
print $o "\x89PNG\r\n\x1a\n", $chunk->("IHDR", pack("NNCCCCC", $W, $H, 8, 6, 0, 0, 0)), $chunk->("IDAT", compress($raw)), $chunk->("IEND", "");
close $o;
print "$out\n";
