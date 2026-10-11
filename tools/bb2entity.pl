#!/usr/bin/perl
# Blockbench (.bbmodel, box UV) to the mod's entity model format: bones (name, parent, pivot, rotation) with their
# cubes (from, to, UV offset, inflate, mirror), and the animations (keyframes per bone and channel). Coordinates
# stay Blockbench's; the mod turns them into Minecraft's (see client.BbModel). The texture is written out as a PNG.
#
#   perl tools/bb2entity.pl in.bbmodel out.json out.png
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;

my ($in, $out, $png) = @ARGV;
die "usage: bb2entity.pl in.bbmodel out.json out.png\n" unless $png;
local $/;
open my $fh, "<:raw", $in or die "$in: $!";
my $bb = decode_json(<$fh>);
close $fh;

my $src = $bb->{textures}[0]{source} or die "no texture";
$src =~ s/^data:image\/png;base64,//;
open my $p, ">:raw", $png or die $!;
print $p decode_base64($src);
close $p;

my %cube = map { $_->{uuid} => $_ } @{$bb->{elements}};
my %group = map { $_->{uuid} => $_ } @{$bb->{groups} || []};
my @bones;
my %boneName;

sub num { my ($v) = @_; return 0 + sprintf("%.4f", $v // 0); }

sub cubeOut {
	my ($c) = @_;
	return {
		from => [map { num($_) } @{$c->{from}}],
		to => [map { num($_) } @{$c->{to}}],
		uv => [map { num($_) } @{$c->{uv_offset} || [0, 0]}],
		inflate => num($c->{inflate}),
		mirror => $c->{mirror_uv} ? JSON::PP::true : JSON::PP::false,
	};
}

my $loose;
sub walk {
	my ($node, $parent) = @_;
	if (!ref $node) {
		my $c = $cube{$node} or return;
		return if defined $c->{visibility} && !$c->{visibility};
		if (!defined $parent) {
			# A cube outside any group: under a bone of its own at the origin.
			if (!$loose) {
				$loose = {name => "loose", parent => undef, pivot => [0, 0, 0], rotation => [0, 0, 0], cubes => []};
				push @bones, $loose;
			}
			push @{$loose->{cubes}}, cubeOut($c);
			return;
		}
		push @{$parent->{cubes}}, cubeOut($c);
		return;
	}
	my $g = $group{$node->{uuid}} // $node;
	my $name = $g->{name} || "bone" . scalar(@bones);
	$name .= "_" . scalar(@bones) while $boneName{$name};
	$boneName{$name} = 1;
	my $bone = {
		name => $name,
		parent => $parent ? $parent->{name} : undef,
		pivot => [map { num($_) } @{$g->{origin} || [0, 0, 0]}],
		rotation => [map { num($_) } @{$g->{rotation} || [0, 0, 0]}],
		cubes => [],
	};
	$bone->{uuid} = $node->{uuid};
	push @bones, $bone;
	walk($_, $bone) for @{$node->{children} || []};
}
walk($_, undef) for @{$bb->{outliner}};
my %byUuid = map { ($_->{uuid} // "") => $_->{name} } @bones;
delete $_->{uuid} for @bones;

my %anims;
for my $an (@{$bb->{animations} || []}) {
	my %bonesOut;
	for my $id (keys %{$an->{animators} || {}}) {
		my $name = $byUuid{$id} or next;
		my %ch;
		for my $k (@{$an->{animators}{$id}{keyframes} || []}) {
			my $d = $k->{data_points}[0];
			my @v = map { my $x = $d->{$_}; $x = 0 unless defined $x && $x =~ /^\s*-?[\d.]+(e-?\d+)?\s*$/i; num($x) } qw(x y z);
			push @{$ch{$k->{channel}}}, [num($k->{time}), @v, $k->{interpolation} // "linear"];
		}
		for my $c (keys %ch) {
			$ch{$c} = [sort { $a->[0] <=> $b->[0] } @{$ch{$c}}];
		}
		$bonesOut{$name} = \%ch if %ch;
	}
	$anims{$an->{name}} = {length => num($an->{length}), loop => $an->{loop} // "once", bones => \%bonesOut};
}

my %model = (
	texture => [$bb->{resolution}{width}, $bb->{resolution}{height}],
	bones => \@bones,
	animations => \%anims,
);
open my $o, ">", $out or die $!;
print $o JSON::PP->new->canonical->encode(\%model);
close $o;
printf "%s: %d bones, %d cubes, animations: %s\n", $out, scalar(@bones), (eval { my $n = 0; $n += @{$_->{cubes}} for @bones; $n }), join(", ", sort keys %anims);
