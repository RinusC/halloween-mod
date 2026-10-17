#!/usr/bin/perl
# The gulls the user gave (a Blockbench "free" model: two polygon meshes, an adult and a juvenile, five textures
# each) turned into what client.BirdRenderer draws: every quad with its four corners (blocks, the bird standing at
# the origin facing -z, feet on y 0) and its texture coordinates in one atlas of all ten textures, and which part it
# is (body, or the left or right wing: the faces painted with the wing texture, split by side, so they can beat).
#
#   perl tools/gull_convert.pl <herring-gull-and-juvenile.bbmodel> <models/entity/gull.json> <textures/entity/gull.png>
#
# The source model is the user's own download and stays out of git (as the game's music does): its outputs are
# ignored too. Run it again to rebuild them.
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;

my ($in, $outJson, $outPng) = @ARGV;
die "usage: gull_convert.pl in.bbmodel out.json out.png\n" unless $outPng;
open my $f, '<:raw', $in or die "$in: $!";
my $bb = decode_json(do { local $/; <$f> });
close $f;

# ---------------------------------------------------------------- PNG in and out (8-bit RGBA / RGB, no interlace)
sub readPng {
	my ($data) = @_;
	my ($w, $h, $depth, $ct) = unpack('NNCC', substr($data, 16, 10));
	die "png: depth $depth\n" unless $depth == 8;
	my $bpp = $ct == 6 ? 4 : $ct == 2 ? 3 : die "png: colour type $ct\n";
	my $idat = '';
	my $p = 8;
	while ($p < length $data) {
		my $len = unpack('N', substr($data, $p, 4));
		$idat .= substr($data, $p + 8, $len) if substr($data, $p + 4, 4) eq 'IDAT';
		$p += 12 + $len;
	}
	my $raw = uncompress($idat);
	my $stride = $w * $bpp;
	my @prev = (0) x $stride;
	my @px;
	for my $y (0 .. $h - 1) {
		my $ft = ord(substr($raw, $y * ($stride + 1), 1));
		my @cur = unpack('C*', substr($raw, $y * ($stride + 1) + 1, $stride));
		for my $i (0 .. $stride - 1) {
			my $a = $i >= $bpp ? $cur[$i - $bpp] : 0;
			my $b = $prev[$i];
			my $c = $i >= $bpp ? $prev[$i - $bpp] : 0;
			if ($ft == 1) { $cur[$i] = ($cur[$i] + $a) & 255 }
			elsif ($ft == 2) { $cur[$i] = ($cur[$i] + $b) & 255 }
			elsif ($ft == 3) { $cur[$i] = ($cur[$i] + int(($a + $b) / 2)) & 255 }
			elsif ($ft == 4) {
				my $pp = $a + $b - $c;
				my ($pa, $pb, $pc) = (abs($pp - $a), abs($pp - $b), abs($pp - $c));
				$cur[$i] = ($cur[$i] + (($pa <= $pb && $pa <= $pc) ? $a : $pb <= $pc ? $b : $c)) & 255;
			}
		}
		for my $x (0 .. $w - 1) {
			my @c = @cur[$x * $bpp .. $x * $bpp + $bpp - 1];
			push @c, 255 if $bpp == 3;
			$px[$y][$x] = \@c;
		}
		@prev = @cur;
	}
	return ($w, $h, \@px);
}

# ---------------------------------------------------------------- the atlas: each bird a row of its five textures
my $AW = 128;
my $AH = 64;
my @atlas = map { [map { [0, 0, 0, 0] } 1 .. $AW] } 1 .. $AH;
my %place;
my %texById = map { $_->{id} => $_ } @{$bb->{textures}};
my @rowOf;
my @ids = sort { $a <=> $b } keys %texById;
for my $n (0 .. $#ids) {
	my $t = $texById{$ids[$n]};
	my $row = int($n / 5);
	my $col = $n % 5;
	my $x0 = (0, 16, 48, 80, 96)[$col];
	my $y0 = $row * 32;
	(my $src = $t->{source}) =~ s/^data:image\/png;base64,//;
	my ($w, $h, $px) = readPng(decode_base64($src));
	for my $y (0 .. $h - 1) {
		for my $x (0 .. $w - 1) {
			$atlas[$y0 + $y][$x0 + $x] = $px->[$y][$x];
		}
	}
	$place{$t->{id}} = [$x0, $y0, $t->{uv_width} || $w, $t->{uv_height} || $h, $w, $h, $t->{name}];
}

# ---------------------------------------------------------------- the meshes
my $PI = 3.14159265358979;
my %models;
my $n = 0;
for my $e (@{$bb->{elements}}) {
	next unless $e->{type} eq 'mesh';
	my @o = @{$e->{origin}};
	my ($rx, $ry, $rz) = map { $_ * $PI / 180 } @{$e->{rotation} || [0, 0, 0]};
	# Blockbench turns about z, then y, then x (applied to the vertex: x first).
	my $turn = sub {
		my ($x, $y, $z) = @_;
		($y, $z) = ($y * cos($rx) - $z * sin($rx), $y * sin($rx) + $z * cos($rx));
		($x, $z) = ($x * cos($ry) + $z * sin($ry), -$x * sin($ry) + $z * cos($ry));
		($x, $y) = ($x * cos($rz) - $y * sin($rz), $x * sin($rz) + $y * cos($rz));
		return ($x, $y, $z);
	};
	# Undo the element's own yaw (the birds were posed turned on the scene): only its pitch and roll stay.
	my %v;
	for my $k (keys %{$e->{vertices}}) {
		my @p = @{$e->{vertices}{$k}};
		my ($x, $y, $z) = $turn->(@p);
		($x, $z) = ($x * cos(-$ry) + $z * sin(-$ry), -$x * sin(-$ry) + $z * cos(-$ry));
		$v{$k} = [$x, $y, $z];
	}
	my @mn = (1e9) x 3;
	my @mx = (-1e9) x 3;
	for my $p (values %v) {
		for (0 .. 2) {
			$mn[$_] = $p->[$_] if $p->[$_] < $mn[$_];
			$mx[$_] = $p->[$_] if $p->[$_] > $mx[$_];
		}
	}
	my @c = (($mn[0] + $mx[0]) / 2, $mn[1], ($mn[2] + $mx[2]) / 2);
	# A gull some 0.55 blocks long, feet on the ground; facing -z (the longest side along z, the head end forwards:
	# the end where the "head" texture is).
	my $len = $mx[2] - $mn[2];
	my $scale = 0.55 / ($len || 1);
	my $headZ = 0;
	my $heads = 0;
	for my $face (values %{$e->{faces}}) {
		next unless ($place{$face->{texture}}[6] // '') =~ /head/;
		for my $k (@{$face->{vertices}}) {
			$headZ += $v{$k}[2];
			$heads++;
		}
	}
	my $flip = $heads && $headZ / $heads > $c[2] ? -1 : 1;
	my @quads;
	for my $face (values %{$e->{faces}}) {
		my $pl = $place{$face->{texture}} or next;
		my ($ax, $ay, $uw, $uh, $tw, $th, $tname) = @$pl;
		my @corners;
		for my $k (@{$face->{vertices}}) {
			my $p = $v{$k};
			my $uv = $face->{uv}{$k};
			push @corners, [map({ 0 + sprintf('%.4f', $_) } ($p->[0] - $c[0]) * $scale * $flip, ($p->[1] - $c[1]) * $scale, ($p->[2] - $c[2]) * $scale * $flip),
				0 + sprintf('%.5f', ($ax + $uv->[0] * $tw / $uw) / $AW), 0 + sprintf('%.5f', ($ay + $uv->[1] * $th / $uh) / $AH)];
		}
		push @corners, $corners[-1] while @corners < 4;
		my $part = 'body';
		if ($tname =~ /wang|wing/) {
			my $sx = 0;
			$sx += $_->[0] for @corners;
			$part = $sx > 0 ? 'wing_right' : 'wing_left';
		}
		push @quads, {part => $part, v => \@corners};
	}
	# Where the wings hinge: the inner edge of each wing, at its middle height.
	my %hinge;
	for my $side ('wing_left', 'wing_right') {
		my ($ix, $iy, $iz, $k) = (undef, 0, 0, 0);
		for my $q (grep { $_->{part} eq $side } @quads) {
			for my $p (@{$q->{v}}) {
				$ix = $p->[0] if !defined $ix || abs($p->[0]) < abs($ix);
				$iy += $p->[1];
				$iz += $p->[2];
				$k++;
			}
		}
		$hinge{$side} = [$ix // 0, $k ? $iy / $k : 0, $k ? $iz / $k : 0];
	}
	$models{$n == 0 ? 'juvenile' : 'adult'} = {quads => \@quads, hinge => \%hinge};
	printf "mesh %d: %d quads, %d wing\n", $n, scalar(@quads), scalar(grep { $_->{part} ne 'body' } @quads);
	$n++;
}

open my $o, '>', $outJson or die;
print $o JSON::PP->new->canonical->encode({atlas => [$AW, $AH], birds => \%models});
close $o;

my $raw = '';
for my $y (0 .. $AH - 1) {
	$raw .= "\0" . join('', map { pack('C4', @$_) } @{$atlas[$y]});
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $g, '>:raw', $outPng or die;
print $g "\x89PNG\r\n\x1a\n", $chunk->("IHDR", pack("NNCCCCC", $AW, $AH, 8, 6, 0, 0, 0)), $chunk->("IDAT", compress($raw)), $chunk->("IEND", "");
close $g;
