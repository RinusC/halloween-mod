#!/usr/bin/perl
# A crow of our own, in the format client.BirdRenderer draws (as tools/gull_convert.pl writes it): quads with their
# corners in blocks (the bird standing at the origin facing -z, feet on y 0) and texture coordinates, each marked
# body / wing_left / wing_right, and where each wing hinges. Built of boxes: body, chest, head, beak (two halves),
# tail feathers, legs and toes, and each wing in two parts (the inner along the body, the primaries reaching back
# past the tail). Painted here: black with a blue-green sheen, the feathers' edges a little lighter, a grey beak, a
# black eye with a pale glint.
#
#   perl tools/crow_model.pl src/main/resources/assets/halloween/models/entity/crow.json src/main/resources/assets/halloween/textures/entity/crow.png
use strict;
use warnings;
use JSON::PP;
use Compress::Zlib;

my ($outJson, $outPng) = @ARGV;
die "usage: crow_model.pl out.json out.png\n" unless $outPng;
srand(1978);
my $T = 32;
my @px = map { [map { [0, 0, 0, 255] } 1 .. $T] } 1 .. $T;

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }

# Texture regions (x, y, w, h), each painted.
my %R = (body => [0, 0, 8, 8], wing => [8, 0, 8, 8], tail => [16, 0, 8, 8], beak => [24, 0, 4, 4], leg => [28, 0, 4, 4],
	head => [0, 8, 8, 8], eye => [24, 4, 4, 4]);

sub paint {
	my ($r, $f) = @_;
	for my $y (0 .. $r->[3] - 1) {
		for my $x (0 .. $r->[2] - 1) {
			$px[$r->[1] + $y][$r->[0] + $x] = [map({ clamp($_) } @{$f->($x, $y)}), 255];
		}
	}
}
my $n = sub { return (rand() - 0.5) * 2 * shift; };
paint($R{body}, sub { my ($x, $y) = @_; my $s = ($x + $y) % 5 == 0 ? 10 : 0; my $d = $n->(4); return [18 + $d + $s * 0.4, 20 + $d + $s * 0.6, 26 + $d + $s]; });
paint($R{head}, sub { my ($x, $y) = @_; my $d = $n->(3); return [14 + $d, 15 + $d, 20 + $d]; });
paint($R{wing}, sub {
	my ($x, $y) = @_;
	# Feathers in rows, each with a lighter edge; a blue-green sheen.
	my $edge = $y % 2 == 1 ? 14 : 0;
	my $d = $n->(3);
	return [16 + $d + $edge * 0.6, 20 + $d + $edge, 28 + $d + $edge * 1.2];
});
paint($R{tail}, sub { my ($x, $y) = @_; my $edge = $x % 2 ? 10 : 0; my $d = $n->(3); return [14 + $d + $edge * 0.5, 17 + $d + $edge * 0.8, 24 + $d + $edge]; });
paint($R{beak}, sub { my ($x, $y) = @_; return [48 + $y * 6, 46 + $y * 6, 50 + $y * 6]; });
paint($R{leg}, sub { return [36, 34, 34]; });
paint($R{eye}, sub { my ($x, $y) = @_; return $x == 1 && $y == 1 ? [190, 190, 170] : [4, 4, 6]; });

# A box (pixels, 16 to a block) as six quads, every face painted with region $reg; $part as given.
my @quads;
sub box {
	my ($part, $reg, $from, $to, $faces) = @_;
	my ($x0, $y0, $z0) = map { $_ / 16 } @$from;
	my ($x1, $y1, $z1) = map { $_ / 16 } @$to;
	my $r = $R{$reg};
	my ($u0, $v0, $u1, $v1) = map { $_ / $T } ($r->[0], $r->[1], $r->[0] + $r->[2], $r->[1] + $r->[3]);
	my $uv = [[$u0, $v1], [$u1, $v1], [$u1, $v0], [$u0, $v0]];
	my %f = (
		north => [[$x1, $y0, $z0], [$x0, $y0, $z0], [$x0, $y1, $z0], [$x1, $y1, $z0]],
		south => [[$x0, $y0, $z1], [$x1, $y0, $z1], [$x1, $y1, $z1], [$x0, $y1, $z1]],
		west => [[$x0, $y0, $z0], [$x0, $y0, $z1], [$x0, $y1, $z1], [$x0, $y1, $z0]],
		east => [[$x1, $y0, $z1], [$x1, $y0, $z0], [$x1, $y1, $z0], [$x1, $y1, $z1]],
		up => [[$x0, $y1, $z1], [$x1, $y1, $z1], [$x1, $y1, $z0], [$x0, $y1, $z0]],
		down => [[$x0, $y0, $z0], [$x1, $y0, $z0], [$x1, $y0, $z1], [$x0, $y0, $z1]],
	);
	for my $name (sort keys %f) {
		next if $faces && !$faces->{$name};
		my @v;
		for my $i (0 .. 3) {
			push @v, [map({ 0 + sprintf('%.4f', $_) } @{$f{$name}[$i]}), map { 0 + sprintf('%.5f', $_) } @{$uv->[$i]}];
		}
		push @quads, {part => $part, v => \@v};
	}
}

# Body: a little over half a block long, leaning slightly forward; feet at y 0.
box('body', 'body', [-1.6, 2.2, -2.4], [1.6, 5.2, 3.0]);
box('body', 'body', [-1.4, 2.6, -3.4], [1.4, 5.0, -2.4]);
box('head', 'head', [-1.3, 4.6, -5.4], [1.3, 7.2, -2.6]);
box('head', 'beak', [-0.5, 5.2, -7.6], [0.5, 6.1, -5.4]);
box('head', 'beak', [-0.4, 4.8, -7.0], [0.4, 5.2, -5.4]);
box('head', 'eye', [-1.32, 6.0, -4.9], [-1.2, 6.6, -4.3]);
box('head', 'eye', [1.2, 6.0, -4.9], [1.32, 6.6, -4.3]);
box('tail', 'tail', [-1.2, 2.8, 3.0], [1.2, 3.4, 7.2]);
for my $s (-1, 1) {
	my $part = $s < 0 ? 'leg_left' : 'leg_right';
	box($part, 'leg', [$s * 0.8 - 0.25, 0.4, -0.4], [$s * 0.8 + 0.25, 2.4, 0.1]);
	box($part, 'leg', [$s * 0.8 - 0.35, 0, -1.4], [$s * 0.8 + 0.35, 0.4, 0.6]);
}
# Wings: folded along the sides, the primaries reaching back over the tail.
for my $s (-1, 1) {
	my $part = $s < 0 ? 'wing_left' : 'wing_right';
	my ($a, $b) = $s < 0 ? (-2.0, -1.4) : (1.4, 2.0);
	box($part, 'wing', [$a, 2.6, -1.8], [$b, 5.0, 3.2]);
	box($part, 'wing', [$a, 2.9, 3.2], [$b, 4.2, 6.4]);
}

# Where each moving part turns: the wings at the shoulder, the head at the neck, the legs at the hip, the tail at its root.
my %hinge = (wing_left => [-1.4 / 16, 4.6 / 16, -1.0 / 16], wing_right => [1.4 / 16, 4.6 / 16, -1.0 / 16], head => [0, 5.2 / 16, -2.8 / 16],
	leg_left => [-0.8 / 16, 2.4 / 16, -0.15 / 16], leg_right => [0.8 / 16, 2.4 / 16, -0.15 / 16], tail => [0, 3.1 / 16, 3.0 / 16]);
open my $o, '>', $outJson or die "$outJson: $!";
print $o JSON::PP->new->canonical->encode({atlas => [$T, $T], birds => {crow => {quads => \@quads, hinge => \%hinge}}});
close $o;
my $raw = '';
for my $y (0 .. $T - 1) {
	$raw .= "\0" . join('', map { pack('C4', @$_) } @{$px[$y]});
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $g, '>:raw', $outPng or die;
print $g "\x89PNG\r\n\x1a\n", $chunk->("IHDR", pack("NNCCCCC", $T, $T, 8, 6, 0, 0, 0)), $chunk->("IDAT", compress($raw)), $chunk->("IEND", "");
close $g;
printf "crow: %d quads\n", scalar(@quads);
