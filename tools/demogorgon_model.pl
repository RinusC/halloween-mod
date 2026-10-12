#!/usr/bin/perl
# The Demogorgon (Stranger Things) as a Blockbench model: writes a .bbmodel (box UV) that opens in Blockbench and
# that tools/bb2entity.pl turns into the game's model. A player's bones (head, body, arms, legs, with a player's
# pivots), so every pose the game gives a player carries over; on them a tall, thin, slick grey-brown body, long
# arms with three claws a hand, clawed feet, and for a head a closed bud of five petals (bones "petal0".."petal4",
# children of the head, each turned a fifth round): the game folds them open when he strikes. The texture is
# painted here, two texels to a pixel. The animation "walk" is his stalking gait.
#
#   perl tools/demogorgon_model.pl art/demogorgon.bbmodel
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;

my $out = shift or die "usage: demogorgon_model.pl out.bbmodel\n";
my ($UW, $UH) = (128, 64);
my $S = 2;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1983);

# ---------------------------------------------------------------- colours
my %C = (
	skin => [104, 88, 80], skinD => [78, 64, 58], skinDD => [52, 42, 38], skinL => [132, 114, 104], wet => [150, 136, 128],
	vein => [92, 58, 56], red => [140, 26, 30], redD => [88, 12, 16], redL => [176, 52, 52], tooth => [226, 214, 190],
	claw => [46, 40, 36], clawL => [84, 76, 70],
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


# Slick skin: grey-brown, uneven, darker veins and a wet sheen here and there.
sub skin {
	my ($r, $base) = @_;
	$base //= 'skin';
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			my $c = rand() < 0.07 ? 'skinD' : rand() < 0.04 ? 'wet' : rand() < 0.03 ? 'vein' : $base;
			at($r, $i, $j, $c, 5);
		}
	}
}

# A vein: a wandering dark line down a face.
sub vein {
	my ($r, $x) = @_;
	for my $j (0 .. $r->[3] - 1) {
		$x += int(rand(3)) - 1;
		$x = 0 if $x < 0;
		$x = $r->[2] - 1 if $x >= $r->[2];
		at($r, $x, $j, rand() < 0.5 ? 'vein' : 'skinDD', 3);
	}
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

# A bone inside another (its pivot, its rest rotation in degrees).
sub child {
	my ($parent, $name, $origin, $rotation) = @_;
	my $b = {name => $name, origin => $origin, rotation => $rotation, color => 0, uuid => uuid(), export => JSON::PP::true,
		mirror_uv => JSON::PP::false, isOpen => JSON::PP::true, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0, children => []};
	$bones{$name} = $b;
	push @{$parent->{children}}, $b;
	return $b;
}

# The bones where a player has them (Blockbench units: feet at 0, the head's top at 32, the front towards -z).
my $body = bone("body", [0, 24, 0]);
my $head = bone("head", [0, 24, 0]);
my $rArm = bone("right_arm", [5, 22, 0]);
my $lArm = bone("left_arm", [-5, 22, 0]);
my $rLeg = bone("right_leg", [1.9, 12, 0]);
my $lLeg = bone("left_leg", [-1.9, 12, 0]);

# ---------------------------------------------------------------- the head: a neck, the bud, five petals
my $N = cube($head, "neck", [-1.5, 24, -1.5], [1.5, 26, 1.5], 0, [40, 0]);
skin($N->{$_}, 'skinD') for qw(east north west south up down);
my $B = cube($head, "bud", [-2.5, 26, -2.5], [2.5, 31, 2.5], 0, [0, 0]);
# Inside the bud (seen when the petals open): raw red, rows of teeth.
for my $f (qw(east north west south up)) {
	my $r = $B->{$f};
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			at($r, $i, $j, rand() < 0.2 ? 'redD' : 'red', 8);
		}
	}
	for my $j (grep { $_ % 3 == 1 } 0 .. $r->[3] - 1) {
		for my $i (grep { $_ % 2 == 0 } 0 .. $r->[2] - 1) { at($r, $i, $j, 'tooth', 6); }
	}
}
fill($B->{down}, 'skinD', 4);
for my $k (0 .. 4) {
	my $P = child($head, "petal$k", [0, 27, 0], [0, $k * 72, 0]);
	my $L = cube($P, "petal${k}_leaf", [-2, 27, -3.5], [2, 33, -2.5], 0, [56 + $k * 10, 0]);
	# Outside: his skin, a ridge down the middle, the edge darker.
	skin($L->{north});
	for my $j (0 .. $L->{north}[3] - 1) { at($L->{north}, 3, $j, 'skinL', 4); at($L->{north}, 4, $j, 'skinL', 4); }
	fill($L->{north}, 'skinDD', 3, 0, 0, 8, 1);
	skin($L->{$_}, 'skinD') for qw(east west up down);
	# Inside: red flesh lined with small teeth along the edges.
	my $in = $L->{south};
	for my $j (0 .. $in->[3] - 1) {
		for my $i (0 .. $in->[2] - 1) { at($in, $i, $j, rand() < 0.25 ? 'redL' : 'red', 8); }
	}
	for my $j (grep { $_ % 2 == 0 } 0 .. $in->[3] - 1) { at($in, 0, $j, 'tooth', 6); at($in, $in->[2] - 1, $j, 'tooth', 6); }
	for my $i (grep { $_ % 2 == 1 } 0 .. $in->[2] - 1) { at($in, $i, 0, 'tooth', 6); }
}

# ---------------------------------------------------------------- the body: thin, ribs showing, a spine
my $T = cube($body, "torso", [-3.5, 12, -2], [3.5, 24, 2], 0, [0, 16]);
skin($T->{$_}) for qw(east north west south up down);
for my $j (grep { $_ % 4 == 2 } 4 .. 16) {
	# Ribs across the chest.
	for my $i (1 .. 12) { at($T->{north}, $i, $j, 'skinDD', 3) unless $i == 6 || $i == 7; }
}
vein($T->{north}, 4);
vein($T->{north}, 10);
for my $j (0 .. $T->{south}[3] - 1) { at($T->{south}, 6, $j, 'skinL', 4); at($T->{south}, 7, $j, 'skinDD', 3); }
fill($T->{north}, 'skinD', 3, 0, 22, 14, 2);

# ---------------------------------------------------------------- arms: long, thin, three claws a hand
sub arm {
	my ($bone, $side, $x0, $uv, $claws) = @_;
	my $A = cube($bone, "arm_$side", [$x0, 6, -1.5], [$x0 + 3, 24, 1.5], 0, $uv);
	skin($A->{$_}) for qw(east north west south up);
	fill($A->{down}, 'skinD', 4);
	vein($A->{north}, 2);
	vein($A->{$side eq 'right' ? 'east' : 'west'}, 3);
	# Knobbly elbow, long fingers (darker towards the claws).
	fill($A->{north}, 'skinDD', 3, 1, 16, 4, 2);
	fill($A->{$_}, 'skinD', 4, 0, 30, 6, 6) for qw(north south east west);
	for my $k (0 .. 2) {
		my $x = $x0 + $k;
		my $C = cube($bone, "claw_${side}_$k", [$x, 2, -1.5], [$x + 1, 6, -0.5], -0.05, $claws->[$k]);
		fill($C->{$_}, 'claw', 3) for qw(east north west south up);
		fill($C->{$_}, 'clawL', 3, 0, 1, 2, 2) for qw(north east);
		fill($C->{down}, 'clawL', 2);
	}
}
arm($rArm, 'right', 4, [24, 16], [[48, 16], [52, 16], [56, 16]]);
arm($lArm, 'left', -7, [36, 16], [[60, 16], [64, 16], [68, 16]]);

# ---------------------------------------------------------------- legs: thin, knotted, clawed toes
sub leg {
	my ($bone, $side, $x0, $uv, $toes) = @_;
	my $L = cube($bone, "leg_$side", [$x0, 0, -1.5], [$x0 + 3, 12, 1.5], 0, $uv);
	skin($L->{$_}) for qw(east north west south up down);
	vein($L->{north}, 2);
	fill($L->{north}, 'skinDD', 3, 1, 10, 4, 2);
	for my $k (0 .. 1) {
		my $x = $x0 + 0.5 + $k * 1.0;
		my $C = cube($bone, "toe_${side}_$k", [$x, 0, -3.5], [$x + 1, 1, -1.5], -0.05, $toes->[$k]);
		fill($C->{$_}, 'claw', 3) for qw(east north west south up down);
	}
}
leg($rLeg, 'right', 0.4, [0, 40], [[24, 40], [30, 40]]);
leg($lLeg, 'left', -3.4, [12, 40], [[36, 40], [42, 40]]);

# ---------------------------------------------------------------- the walk: a stalking, loping gait
my $walk = {uuid => uuid(), name => "walk", loop => "loop", override => JSON::PP::false, length => 1.0, snapping => 24,
	selected => JSON::PP::false, anim_time_update => "", blend_weight => "", start_delay => "", loop_delay => "", animators => {}};
sub keys_for {
	my ($bone, $channel, @pts) = @_;
	my $a = $walk->{animators}{$bones{$bone}{uuid}} //= {name => $bone, type => "bone", keyframes => []};
	for my $p (@pts) {
		push @{$a->{keyframes}}, {channel => $channel, data_points => [{x => "$p->[1]", y => "$p->[2]", z => "$p->[3]"}],
			uuid => uuid(), time => $p->[0], color => -1, interpolation => "catmullrom"};
	}
}
# Long strides; the arms swing low and loose, out of step with the legs; head forward, swaying; the body hunched.
keys_for("right_leg", "rotation", [0, 35, 0, 0], [0.5, -35, 0, 0], [1.0, 35, 0, 0]);
keys_for("left_leg", "rotation", [0, -35, 0, 0], [0.5, 35, 0, 0], [1.0, -35, 0, 0]);
keys_for("right_arm", "rotation", [0, -20, 0, 6], [0.5, 22, 0, 4], [1.0, -20, 0, 6]);
keys_for("left_arm", "rotation", [0, 22, 0, -4], [0.5, -20, 0, -6], [1.0, 22, 0, -4]);
keys_for("body", "rotation", [0, 10, 0, 2], [0.5, 10, 0, -2], [1.0, 10, 0, 2]);
keys_for("head", "rotation", [0, 8, 6, 0], [0.5, 8, -6, 0], [1.0, 8, 6, 0]);
for my $b (qw(body head right_arm left_arm)) {
	keys_for($b, "position", [0, 0, -0.6, 0], [0.25, 0, 0.2, 0], [0.5, 0, -0.6, 0], [0.75, 0, 0.2, 0], [1.0, 0, -0.6, 0]);
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
	name => "demogorgon", model_identifier => "demogorgon", modded_entity_entity_class => "", modded_entity_version => "Official",
	modded_entity_flip_y => JSON::PP::true, visible_box => [1, 2, 0], variable_placeholders => "", variable_placeholder_buttons => [],
	timeline_setups => [], unhandled_root_fields => {},
	resolution => {width => $UW, height => $UH},
	elements => \@elements,
	outliner => \@outliner,
	textures => [{path => "", name => "demogorgon.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH,
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

