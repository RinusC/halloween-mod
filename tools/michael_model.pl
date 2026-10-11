#!/usr/bin/perl
# Michael (Halloween, 1978) as a Blockbench model: writes a .bbmodel (box UV) that opens in Blockbench and that
# tools/bb2entity.pl turns into the game's model. The bones are a player's (head, body, arms, legs, with a
# player's pivots), so every pose the game gives a player carries over to it and the knife stays in his hand.
# On them: the mask with its nose and brow, the hair in a layer of its own, the coverall's collar, belt and chest
# pockets, cuffs, bare hands, work boots. The texture is painted here, two texels to a pixel. The animation
# "walk" is his heavy walk (the game plays it in place of the usual one).
#
#   perl tools/michael_model.pl art/michael.bbmodel
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;

my $out = shift or die "usage: michael_model.pl out.bbmodel\n";
my ($UW, $UH) = (128, 64);
my $S = 2;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1978);

# ---------------------------------------------------------------- colours
my %C = (
	navy => [31, 42, 68], navyD => [22, 30, 51], navyDD => [13, 18, 33], navyL => [44, 57, 88],
	mask => [228, 222, 204], maskS => [200, 193, 175], maskD => [158, 150, 133], hole => [10, 10, 10],
	hair => [72, 50, 36], hairD => [50, 34, 23], hairL => [96, 68, 47],
	skin => [214, 184, 158], skinD => [182, 150, 124],
	boot => [24, 23, 22], bootL => [44, 42, 40], sole => [9, 9, 9], lace => [62, 60, 58],
	metal => [150, 155, 162], metalD => [92, 96, 104],
);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v); }

sub put {
	my ($x, $y, $c, $a) = @_;
	return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH;
	$px[$y * $TW + $x] = [@$c[0 .. 2], $a // 255];
}

sub jit {
	my ($c, $n) = @_;
	my $d = int(rand(2 * $n + 1)) - $n;
	return [map { clamp($_ + $d) } @$c];
}

sub fill {
	my ($r, $c, $n, $x0, $y0, $w, $h) = @_;
	($x0, $y0, $w, $h) = (0, 0, $r->[2], $r->[3]) unless defined $x0;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			put($r->[0] + $x0 + $i, $r->[1] + $y0 + $j, $n ? jit($C{$c}, $n) : $C{$c});
		}
	}
}

sub at { my ($r, $i, $j, $c, $n) = @_; put($r->[0] + $i, $r->[1] + $j, $n ? jit($C{$c}, $n) : $C{$c}); }

sub clear { my ($r, $i, $j) = @_; put($r->[0] + $i, $r->[1] + $j, [0, 0, 0], 0); }

# Coverall cloth: navy, a little uneven, a darker thread here and there.
sub cloth {
	my ($r, $base) = @_;
	$base //= 'navy';
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			my $c = rand() < 0.06 ? 'navyD' : $base;
			at($r, $i, $j, $c, 4);
		}
	}
}

# Hair: strands running down, each column its own shade.
my %strand;
sub hairpx {
	my ($r, $i, $j) = @_;
	my $k = "$r->[0],$i";
	$strand{$k} //= int(rand(13)) - 6;
	my $c = rand() < 0.12 ? 'hairD' : rand() < 0.1 ? 'hairL' : 'hair';
	put($r->[0] + $i, $r->[1] + $j, [map { clamp($_ + $strand{$k}) } @{jit($C{$c}, 3)}]);
}

# ---------------------------------------------------------------- the model
my $uuidN = 0;
sub uuid { return sprintf("%08x-%04x-4%03x-a%03x-%012x", int(rand(0xffffffff)), int(rand(0xffff)), int(rand(0xfff)), int(rand(0xfff)), ++$uuidN); }

my (@elements, @outliner, %bones);

sub bone {
	my ($name, $origin) = @_;
	my $b = {name => $name, origin => $origin, color => 0, uuid => uuid(), export => JSON::PP::true, mirror_uv => JSON::PP::false,
		isOpen => JSON::PP::true, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0, children => []};
	$bones{$name} = $b;
	push @outliner, $b;
	return $b;
}

# A cube with box UV at $uv: its faces' rectangles in the texture (texels), by Blockbench's face names. Facing
# north (-z) as the model does, east is his right: "east" is his right side (its left column towards his back),
# "north" his front (its left column on his right), "west" his left side (left column at the front), "south"
# his back (left column on his left).
sub cube {
	my ($bone, $name, $from, $to, $inflate, $uv) = @_;
	my ($w, $h, $d) = map { $to->[$_] - $from->[$_] } 0 .. 2;
	my ($u, $v) = @$uv;
	push @elements, {
		name => $name, box_uv => JSON::PP::true, rescale => JSON::PP::false, locked => JSON::PP::false, render_order => "default",
		allow_mirror_modeling => JSON::PP::true, from => $from, to => $to, autouv => 0, color => 0, inflate => $inflate,
		origin => $bone->{origin}, uv_offset => $uv, type => "cube", uuid => uuid(),
		faces => {
			north => {uv => [$u + $d, $v + $d, $u + $d + $w, $v + $d + $h], texture => 0},
			east => {uv => [$u, $v + $d, $u + $d, $v + $d + $h], texture => 0},
			south => {uv => [$u + 2 * $d + $w, $v + $d, $u + 2 * $d + 2 * $w, $v + $d + $h], texture => 0},
			west => {uv => [$u + $d + $w, $v + $d, $u + 2 * $d + $w, $v + $d + $h], texture => 0},
			up => {uv => [$u + $d + $w, $v + $d, $u + $d, $v], texture => 0},
			down => {uv => [$u + $d + 2 * $w, $v, $u + $d + $w, $v + $d], texture => 0},
		},
	};
	push @{$bone->{children}}, $elements[-1]{uuid};
	return {
		up => [($u + $d) * $S, $v * $S, $w * $S, $d * $S],
		down => [($u + $d + $w) * $S, $v * $S, $w * $S, $d * $S],
		east => [$u * $S, ($v + $d) * $S, $d * $S, $h * $S],
		north => [($u + $d) * $S, ($v + $d) * $S, $w * $S, $h * $S],
		west => [($u + $d + $w) * $S, ($v + $d) * $S, $d * $S, $h * $S],
		south => [($u + 2 * $d + $w) * $S, ($v + $d) * $S, $w * $S, $h * $S],
	};
}

# The bones where a player has them (Blockbench units: feet at 0, the head's top at 32, the front towards -z).
my $body = bone("body", [0, 24, 0]);
my $head = bone("head", [0, 24, 0]);
my $rArm = bone("right_arm", [5, 22, 0]);
my $lArm = bone("left_arm", [-5, 22, 0]);
my $rLeg = bone("right_leg", [1.9, 12, 0]);
my $lLeg = bone("left_leg", [-1.9, 12, 0]);

# ---------------------------------------------------------------- the head: mask, hair
my $H = cube($head, "mask", [-4, 24, -4], [4, 32, 4], 0, [0, 0]);
{
	my $f = $H->{north};
	fill($f, 'mask', 4);
	for my $j (0 .. 15) { at($f, 0, $j, 'maskS', 3); at($f, 15, $j, 'maskS', 3); }
	for my $i (1 .. 14) { at($f, $i, 15, 'maskS', 3); }
	# The hairline, ragged.
	for my $i (0 .. 15) {
		my $len = 2 + (rand() < 0.45 ? 1 : 0) + (($i == 3 || $i == 12) ? 1 : 0);
		hairpx($f, $i, $_) for 0 .. $len - 1;
	}
	# Hollows round the eyes, the eyes black holes.
	fill($f, 'maskS', 3, 2, 7, 5, 4);
	fill($f, 'maskS', 3, 9, 7, 5, 4);
	fill($f, 'hole', 0, 3, 8, 3, 2);
	fill($f, 'hole', 0, 10, 8, 3, 2);
	fill($f, 'maskD', 2, 3, 10, 3, 1);
	fill($f, 'maskD', 2, 10, 10, 3, 1);
	# Cheeks, the shadow under the nose, the thin mouth turning down.
	fill($f, 'maskS', 3, 1, 11, 2, 3);
	fill($f, 'maskS', 3, 13, 11, 2, 3);
	fill($f, 'maskS', 2, 6, 11, 4, 1);
	fill($f, 'maskD', 2, 5, 13, 6, 1);
	at($f, 4, 14, 'maskD', 2);
	at($f, 11, 14, 'maskD', 2);
	fill($f, 'maskS', 3, 5, 14, 6, 1);
}
my %hairTo;
for my $side ('east', 'west') {
	my $f = $H->{$side};
	for my $i (0 .. 15) {
		# 1 at his face, 0 at the back of his head.
		my $front = $side eq 'east' ? $i / 15 : 1 - $i / 15;
		my $len = $front > 0.75 ? 3 + int(rand(2)) : $front > 0.45 ? 6 + int(rand(2)) : 11 + int(rand(3));
		$hairTo{"$side,$i"} = $len;
		for my $j (0 .. 15) {
			if ($j < $len) {
				hairpx($f, $i, $j);
			} else {
				at($f, $i, $j, $front > 0.85 ? 'maskS' : 'mask', 4);
			}
		}
	}
	# The ear under the hair.
	my $ex = $side eq 'east' ? 7 : 7;
	fill($f, 'maskS', 3, $ex, 7, 3, 4);
	fill($f, 'maskD', 2, $ex + 1, 8, 1, 2);
}
{
	my $f = $H->{south};
	for my $i (0 .. 15) {
		my $len = 13 + int(rand(3));
		for my $j (0 .. 15) {
			$j < $len ? hairpx($f, $i, $j) : at($f, $i, $j, 'maskS', 3);
		}
	}
	my $f2 = $H->{up};
	for my $i (0 .. 15) { hairpx($f2, $i, $_) for 0 .. 15; }
	fill($H->{down}, 'maskS', 3);
}

# The hair again, over the head and a little out from it, ragged at its edges (the rest left clear).
my $Hh = cube($head, "hair", [-4, 24, -4], [4, 32, 4], 0.5, [32, 0]);
{
	my $f = $Hh->{up};
	for my $i (0 .. 15) { for my $j (0 .. 15) { rand() < 0.93 ? hairpx($f, $i, $j) : clear($f, $i, $j); } }
	$f = $Hh->{north};
	for my $i (0 .. 15) {
		my $len = 1 + int(rand(3)) + (($i == 4 || $i == 10) ? 1 : 0);
		for my $j (0 .. 15) { $j < $len ? hairpx($f, $i, $j) : clear($f, $i, $j); }
	}
	for my $side ('east', 'west') {
		$f = $Hh->{$side};
		for my $i (0 .. 15) {
			my $len = $hairTo{"$side,$i"} - 1 + int(rand(2));
			for my $j (0 .. 15) { $j < $len ? hairpx($f, $i, $j) : clear($f, $i, $j); }
		}
	}
	$f = $Hh->{south};
	for my $i (0 .. 15) {
		my $len = 12 + int(rand(4));
		for my $j (0 .. 15) { $j < $len ? hairpx($f, $i, $j) : clear($f, $i, $j); }
	}
	$f = $Hh->{down};
	clear($f, $_ % 16, int($_ / 16)) for 0 .. 255;
}

# The nose and the brow standing out of the mask.
my $N = cube($head, "nose", [-1, 26.5, -4.75], [1, 28.5, -3.75], -0.25, [64, 0]);
fill($N->{north}, 'mask', 3);
fill($N->{north}, 'maskS', 2, 0, 3, 4, 1);
at($N->{north}, 0, 3, 'maskD', 2);
at($N->{north}, 3, 3, 'maskD', 2);
fill($N->{$_}, 'maskS', 3) for qw(east west south);
fill($N->{up}, 'mask', 3);
fill($N->{down}, 'maskD', 2);
my $B = cube($head, "brow", [-3, 28, -4.75], [3, 29, -3.75], -0.3, [72, 0]);
fill($B->{north}, 'mask', 3);
fill($B->{up}, 'mask', 2);
fill($B->{down}, 'maskD', 2);
fill($B->{$_}, 'maskS', 3) for qw(east west south);
# The hair at the back of the neck, over the collar.
my $Hb = cube($head, "hair_back", [-4, 22, 3], [4, 24, 5], -0.15, [88, 0]);
for my $face (qw(up down east north west south)) {
	my $f = $Hb->{$face};
	for my $i (0 .. $f->[2] - 1) {
		for my $j (0 .. $f->[3] - 1) {
			($face eq 'south' || $face eq 'east' || $face eq 'west') && $j == $f->[3] - 1 && rand() < 0.5 ? clear($f, $i, $j) : hairpx($f, $i, $j);
		}
	}
}

# ---------------------------------------------------------------- the body: coverall
my $T = cube($body, "torso", [-4, 12, -2], [4, 24, 2], 0.25, [0, 16]);
cloth($T->{$_}) for qw(east north west south down);
cloth($T->{up}, 'navyL');
{
	my $f = $T->{north};
	# The open collar, the zip down the middle (its pull at the top), creases by the arms and at the waist.
	fill($f, 'navyDD', 2, 6, 0, 4, 1);
	fill($f, 'navyDD', 2, 7, 1, 2, 2);
	for my $j (3 .. 22) { at($f, 7, $j, $j % 2 ? 'navyDD' : 'metalD', 2); at($f, 8, $j, 'navyD', 2); }
	fill($f, 'metal', 6, 8, 3, 1, 2);
	for my $k (0 .. 3) { at($f, 1 + $k, 13 + $k, 'navyD', 2); at($f, 14 - $k, 13 + $k, 'navyD', 2); }
	fill($f, 'navyD', 2, 0, 22, 16, 2);
	# Shoulder seams.
	fill($f, 'navyD', 2, 0, 1, 3, 1);
	fill($f, 'navyD', 2, 13, 1, 3, 1);
	$f = $T->{south};
	fill($f, 'navyD', 2, 0, 6, 16, 1);
	for my $j (7 .. 21) { at($f, 8, $j, 'navyD', 2) if rand() < 0.7; }
	for my $k (0 .. 2) { at($f, 3 + $k, 17 + $k, 'navyD', 2); at($f, 12 - $k, 17 + $k, 'navyD', 2); }
	for my $side (qw(east west)) {
		$f = $T->{$side};
		for my $j (0 .. 23) { at($f, 4, $j, 'navyD', 2); }
	}
}
my $Co = cube($body, "collar", [-4, 23, -3], [4, 25, 3], -0.25, [24, 16]);
cloth($Co->{$_}, 'navyL') for qw(east west south north up down);
{
	my $f = $Co->{north};
	# The two points of the collar, open at his throat.
	fill($f, 'navyDD', 2, 6, 0, 4, 4);
	fill($f, 'navyD', 2, 0, 3, 6, 1);
	fill($f, 'navyD', 2, 10, 3, 6, 1);
	fill($Co->{up}, 'navyDD', 2, 3, 2, 10, 8);
	fill($Co->{$_}, 'navyD', 2, 0, 3, $Co->{$_}[2], 1) for qw(east west south);
}
my $Be = cube($body, "belt", [-5, 12, -3], [5, 14, 3], -0.45, [52, 16]);
for my $face (qw(up down east north west south)) {
	my $f = $Be->{$face};
	cloth($f, 'navyD');
	next if $face eq 'up' || $face eq 'down';
	fill($f, 'navyDD', 2, 0, 0, $f->[2], 1);
	fill($f, 'navyDD', 2, 0, 3, $f->[2], 1);
}
fill($Be->{north}, 'metal', 8, 8, 0, 4, 4);
fill($Be->{north}, 'navyDD', 1, 9, 1, 2, 2);
for my $p ([0.5, "pocket_right", [84, 16]], [-3.5, "pocket_left", [92, 16]]) {
	my $P = cube($body, $p->[1], [$p->[0], 18.5, -3], [$p->[0] + 3, 21.5, -2], -0.3, $p->[2]);
	cloth($P->{$_}) for qw(up down east north west south);
	my $f = $P->{north};
	fill($f, 'navyL', 3, 0, 0, 6, 2);
	fill($f, 'navyDD', 2, 0, 2, 6, 1);
	at($f, 2, 1, 'bootL', 2);
	at($f, 3, 1, 'bootL', 2);
	fill($f, 'navyD', 2, 0, 3, 1, 3);
	fill($f, 'navyD', 2, 5, 3, 1, 3);
}

# ---------------------------------------------------------------- arms: sleeves, cuffs, bare hands
sub arm {
	my ($bone, $side, $x0, $uvs) = @_;
	# The side of the arm away from his body.
	my $out = $side eq 'right' ? 'east' : 'west';
	my $in = $side eq 'right' ? 'west' : 'east';
	my $A = cube($bone, "sleeve_$side", [$x0, 15, -2], [$x0 + 4, 24, 2], 0.25, $uvs->[0]);
	cloth($A->{$_}) for qw(east north west south down);
	cloth($A->{up}, 'navyL');
	for my $j (0 .. 17) { at($A->{$out}, 4, $j, 'navyD', 2); }
	for my $face (qw(north south)) {
		# Creases at the elbow.
		for my $k (0 .. 3) { at($A->{$face}, 1 + $k, 8 + ($k % 2), 'navyD', 2); at($A->{$face}, 3 + $k, 11 - ($k % 2), 'navyD', 2); }
	}
	fill($A->{$in}, 'navyD', 2, 0, 0, 8, 2);
	my $Cu = cube($bone, "cuff_$side", [$x0, 15, -2], [$x0 + 4, 16, 2], 0.4, $uvs->[1]);
	cloth($Cu->{$_}, 'navyD') for qw(east north west south up down);
	fill($Cu->{$_}, 'navyDD', 2, 0, 1, $Cu->{$_}[2], 1) for qw(east north west south);
	at($Cu->{$out}, 4, 0, 'metal', 6);
	my $Ha = cube($bone, "hand_$side", [$x0, 12, -2], [$x0 + 4, 15, 2], -0.05, $uvs->[2]);
	fill($Ha->{$_}, 'skin', 4) for qw(east north west south up);
	fill($Ha->{down}, 'skinD', 3);
	# Fingers curled round the grip (front and the side towards his body), knuckles, the thumb.
	for my $i (1, 3, 5) { at($Ha->{north}, $i, $_, 'skinD', 2) for 2 .. 5; }
	fill($Ha->{north}, 'skinD', 2, 0, 0, 8, 1);
	for my $i (1, 3, 5) { at($Ha->{$in}, $i, $_, 'skinD', 2) for 3 .. 5; }
	fill($Ha->{$out}, 'skinD', 2, 2, 1, 4, 3);
	fill($Ha->{south}, 'skinD', 2, 0, 0, 8, 1);
}
arm($rArm, 'right', 4, [[0, 32], [16, 32], [32, 32]]);
arm($lArm, 'left', -8, [[48, 32], [64, 32], [80, 32]]);

# ---------------------------------------------------------------- legs: trouser legs, work boots
sub leg {
	my ($bone, $side, $x0, $uvs) = @_;
	my $out = $side eq 'right' ? 'east' : 'west';
	my $L = cube($bone, "trousers_$side", [$x0, 3, -2], [$x0 + 4, 12, 2], 0.2, $uvs->[0]);
	cloth($L->{$_}) for qw(east north west south up down);
	for my $j (0 .. 17) { at($L->{$out}, 4, $j, 'navyD', 2); }
	# Creases at the knee, a pocket on the hip, the bottoms darker where they go into the boots.
	for my $k (0 .. 3) { at($L->{north}, 1 + $k, 7 + ($k % 2), 'navyD', 2); at($L->{north}, 4 + $k % 3, 10, 'navyD', 2); }
	fill($L->{$out}, 'navyD', 2, 1, 1, 6, 1);
	fill($L->{$out}, 'navyD', 2, 1, 1, 1, 5);
	fill($L->{$out}, 'navyD', 2, 6, 1, 1, 5);
	fill($L->{$out}, 'navyD', 2, 1, 6, 6, 1);
	fill($L->{$_}, 'navyD', 2, 0, 16, $L->{$_}[2], 2) for qw(east north west south);
	my $Bo = cube($bone, "boot_$side", [$x0, 0.15, -3], [$x0 + 4, 4.15, 2], 0.15, $uvs->[1]);
	fill($Bo->{$_}, 'boot', 3) for qw(east north west south up);
	fill($Bo->{down}, 'sole', 2);
	fill($Bo->{$_}, 'sole', 2, 0, 7, $Bo->{$_}[2], 1) for qw(east north west south);
	# Toe cap, laces, the heel.
	fill($Bo->{north}, 'bootL', 3, 0, 4, 8, 3);
	for my $j (0, 2) { at($Bo->{north}, 2, $j, 'lace', 3); at($Bo->{north}, 5, $j, 'lace', 3); at($Bo->{north}, 3, $j + 1, 'lace', 3); at($Bo->{north}, 4, $j + 1, 'lace', 3); }
	fill($Bo->{up}, 'bootL', 3, 0, 0, 8, 2);
	fill($Bo->{south}, 'bootL', 2, 0, 5, 8, 2);
}
leg($rLeg, 'right', -0.1, [[0, 48], [16, 48]]);
leg($lLeg, 'left', -3.9, [[34, 48], [50, 48]]);

# ---------------------------------------------------------------- the heavy walk
my $walk = {uuid => uuid(), name => "walk", loop => "loop", override => JSON::PP::false, length => 1.2, snapping => 24,
	selected => JSON::PP::false, anim_time_update => "", blend_weight => "", start_delay => "", loop_delay => "", animators => {}};
sub keys_for {
	my ($bone, $channel, @pts) = @_;
	my $a = $walk->{animators}{$bones{$bone}{uuid}} //= {name => $bone, type => "bone", keyframes => []};
	for my $p (@pts) {
		push @{$a->{keyframes}}, {channel => $channel, data_points => [{x => "$p->[1]", y => "$p->[2]", z => "$p->[3]"}],
			uuid => uuid(), time => $p->[0], color => -1, interpolation => "catmullrom"};
	}
}
# A long, unhurried stride; the arms hardly swing (the knife hand least of all); he comes down heavily on each foot.
keys_for("right_leg", "rotation", [0, 30, 0, 0], [0.6, -30, 0, 0], [1.2, 30, 0, 0]);
keys_for("left_leg", "rotation", [0, -30, 0, 0], [0.6, 30, 0, 0], [1.2, -30, 0, 0]);
keys_for("right_arm", "rotation", [0, -5, 0, 0], [0.6, 6, 0, 0], [1.2, -5, 0, 0]);
keys_for("left_arm", "rotation", [0, 11, 0, 0], [0.6, -11, 0, 0], [1.2, 11, 0, 0]);
keys_for("body", "rotation", [0, 0, 0, 1.5], [0.6, 0, 0, -1.5], [1.2, 0, 0, 1.5]);
for my $b (qw(body head right_arm left_arm)) {
	keys_for($b, "position", [0, 0, -0.5, 0], [0.3, 0, 0.2, 0], [0.6, 0, -0.5, 0], [0.9, 0, 0.2, 0], [1.2, 0, -0.5, 0]);
}

# ---------------------------------------------------------------- the file
sub png {
	my $raw = '';
	for my $y (0 .. $TH - 1) {
		$raw .= "\0";
		$raw .= pack("C4", @{$px[$y * $TW + $_]}) for 0 .. $TW - 1;
	}
	my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
	return "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $TW, $TH, 8, 6, 0, 0, 0)) . $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
}

my $model = {
	meta => {format_version => "4.10", model_format => "modded_entity", box_uv => JSON::PP::true},
	name => "michael", model_identifier => "michael", modded_entity_entity_class => "", modded_entity_version => "Official",
	modded_entity_flip_y => JSON::PP::true, visible_box => [1, 2, 0], variable_placeholders => "", variable_placeholder_buttons => [],
	timeline_setups => [], unhandled_root_fields => {},
	resolution => {width => $UW, height => $UH},
	elements => \@elements,
	outliner => \@outliner,
	textures => [{path => "", name => "michael.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH,
		uv_width => $UW, uv_height => $UH, particle => JSON::PP::false, use_as_default => JSON::PP::false, layers_enabled => JSON::PP::false,
		sync_to_project => "", render_mode => "default", render_sides => "auto", frame_time => 1, frame_order_type => "loop",
		frame_order => "", frame_interpolate => JSON::PP::false, visible => JSON::PP::true, internal => JSON::PP::true, saved => JSON::PP::true,
		uuid => uuid(), relative_path => "", source => "data:image/png;base64," . encode_base64(png(), "")}],
	animations => [$walk],
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;
printf "%s: %d cubes, %d bones, texture %dx%d\n", $out, scalar(@elements), scalar(@outliner), $TW, $TH;
