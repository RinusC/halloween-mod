#!/usr/bin/perl
# Blockbench (.bbmodel) to a Minecraft Java item model: cubes (inflate applied, any rotation), per-face UVs,
# the embedded textures written out as PNGs. Optionally moved and scaled (to stand where an older model stood,
# so its display settings still fit) and given display settings.
#
#   perl tools/bb2java.pl in.bbmodel out_model.json texture_dir texture_name '{"center":[x,y,z],"length":L,"display":{...}}'
#
# center: where the middle of the model ends up; length: its longest side then (both optional; without them it
# stays where it is, but is shrunk to fit the -16..32 limit). display: replaces the model's own display settings
# ("own" keeps them). Texture i becomes <texture_dir>/<texture_name>_i.png, referenced as halloween:item/<texture_name>_i.
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;

my ($in, $out, $texdir, $texname, $opts) = @ARGV;
die "usage: bb2java.pl in.bbmodel out.json texture_dir texture_name [options-json]\n" unless $texname;
my $o = $opts ? decode_json($opts) : {};
local $/;
open my $fh, "<:raw", $in or die "$in: $!";
my $bb = decode_json(<$fh>);
close $fh;

my @tex = @{$bb->{textures} || []};
my $resW = $bb->{resolution}{width} || 16;
my $resH = $bb->{resolution}{height} || 16;
my %textures;
for my $i (0 .. $#tex) {
	my $src = $tex[$i]{source} or die "texture $i has no embedded image";
	$src =~ s/^data:image\/png;base64,//;
	open my $png, ">:raw", "$texdir/${texname}_$i.png" or die $!;
	print $png decode_base64($src);
	close $png;
	$textures{$i} = "halloween:item/${texname}_$i";
}
$textures{particle} = $textures{0} if exists $textures{0};

# Bounds (inflate included) of what is shown.
my @cubes = grep { ($_->{type} // "cube") eq "cube" && !(defined $_->{visibility} && !$_->{visibility}) } @{$bb->{elements}};
my @mn = (1e9) x 3;
my @mx = (-1e9) x 3;
for my $e (@cubes) {
	my $inf = $e->{inflate} // 0;
	for my $k (0 .. 2) {
		my ($a, $b) = sort { $a <=> $b } ($e->{from}[$k], $e->{to}[$k]);
		$mn[$k] = $a - $inf if $a - $inf < $mn[$k];
		$mx[$k] = $b + $inf if $b + $inf > $mx[$k];
	}
}
my @c = map { ($mn[$_] + $mx[$_]) / 2 } 0 .. 2;
my $longest = (sort { $b <=> $a } map { $mx[$_] - $mn[$_] } 0 .. 2)[0];
my $s = $o->{length} ? $o->{length} / $longest : 1;
my @t = $o->{center} ? @{$o->{center}} : @c;
# Whatever was asked, it must fit in -16..32.
for my $k (0 .. 2) {
	my $half = ($mx[$k] - $mn[$k]) / 2 * $s;
	if ($half > 24) {
		$s *= 24 / $half;
	}
}
for my $k (0 .. 2) {
	my $half = ($mx[$k] - $mn[$k]) / 2 * $s;
	$t[$k] = -16 + $half if $t[$k] - $half < -16;
	$t[$k] = 32 - $half if $t[$k] + $half > 32;
}
sub place { my ($p) = @_; return [map { 0 + sprintf("%.4f", ($p->[$_] - $c[$_]) * $s + $t[$_]) } 0 .. 2]; }

my @elements;
for my $e (@cubes) {
	my $inf = $e->{inflate} // 0;
	my @f = map { ($e->{from}[$_] < $e->{to}[$_] ? $e->{from}[$_] : $e->{to}[$_]) - $inf } 0 .. 2;
	my @to = map { ($e->{from}[$_] < $e->{to}[$_] ? $e->{to}[$_] : $e->{from}[$_]) + $inf } 0 .. 2;
	my %el = (from => place(\@f), to => place(\@to));
	my @r = map { 0 + ($_ // 0) } @{$e->{rotation} || [0, 0, 0]};
	my @nz = grep { $r[$_] != 0 } 0 .. 2;
	if (@nz) {
		my $origin = place($e->{origin} || [8, 8, 8]);
		if (@nz == 1) {
			$el{rotation} = {origin => $origin, axis => (qw(x y z))[$nz[0]], angle => $r[$nz[0]]};
		} else {
			$el{rotation} = {origin => $origin, x => $r[0], y => $r[1], z => $r[2]};
		}
	}
	my %faces;
	for my $dir (qw(north east south west up down)) {
		my $face = $e->{faces}{$dir} or next;
		next if !defined $face->{texture} || JSON::PP::is_bool($face->{texture});
		my $ti = $face->{texture};
		$ti = 0 unless $ti =~ /^\d+$/;
		my $uw = $tex[$ti]{uv_width} || $resW;
		my $uh = $tex[$ti]{uv_height} || $resH;
		my @uv = @{$face->{uv}};
		my %fc = (uv => [map { 0 + sprintf("%.4f", $_) } ($uv[0] * 16 / $uw, $uv[1] * 16 / $uh, $uv[2] * 16 / $uw, $uv[3] * 16 / $uh)], texture => "#$ti");
		$fc{rotation} = $face->{rotation} if $face->{rotation};
		$faces{$dir} = \%fc;
	}
	next unless %faces;
	$el{faces} = \%faces;
	push @elements, \%el;
}

my %model = (textures => \%textures, elements => \@elements);
if ($o->{display} && !ref $o->{display} && $o->{display} eq "own") {
	$model{display} = $bb->{display} if $bb->{display};
} elsif ($o->{display}) {
	$model{display} = $o->{display};
}
open my $outfh, ">", $out or die $!;
print $outfh JSON::PP->new->canonical->pretty->encode(\%model);
close $outfh;
printf "%s: %d elements, scale %.3f, centre (%s)\n", $out, scalar(@elements), $s, join(", ", map { sprintf "%.2f", $_ } @t);
