#!/usr/bin/perl
# The Demogorgon (Stranger Things) as a Blockbench model, on all fours: writes a .bbmodel (box UV) that opens in
# Blockbench and that tools/bb2entity.pl turns into the game's model. Not a player's bones: a creature of its own
# that the game moves itself (client.DemogorgonModel): "torso" (pivot at the hips) with "neck" -> "head" (a bud of
# five long petals, "petal0".."petal4", lined inside with teeth round a red throat) at the front, the long insect
# legs "front_right"/"front_left" (each with a "..._lower" forearm and three claws) and "hind_right"/"hind_left"
# (with a "..._lower" shin and two claws), a ridge of spines down the back, ribs and veins under wet dark skin.
# The texture is painted here, two texels to a pixel, its pieces packed by the script.
#
#   perl tools/demogorgon_model.pl art/demogorgon.bbmodel
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;

my $out = shift or die "usage: michael_model.pl out.bbmodel\n";
my ($UW, $UH) = (128, 128);
my $S = 2;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1983);

# ---------------------------------------------------------------- colours
my %C = (
	skin => [66, 54, 52], skinD => [44, 35, 34], skinDD => [26, 20, 20], skinL => [96, 82, 78], wet => [138, 124, 120],
	vein => [118, 30, 36], veinD => [74, 16, 20], red => [168, 22, 30], redD => [96, 8, 14], redL => [206, 60, 62],
	throat => [30, 2, 6], tooth => [236, 226, 200], toothD => [196, 182, 150], claw => [24, 20, 18], clawL => [72, 64, 58],
	pink => [150, 84, 88],
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


# Wet dark skin: blotches, red veins, a sheen.
sub skin {
	my ($r, $base) = @_;
	$base //= 'skin';
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			my $c = rand() < 0.09 ? 'skinD' : rand() < 0.05 ? 'wet' : rand() < 0.04 ? 'vein' : $base;
			at($r, $i, $j, $c, 5);
		}
	}
}

sub vein {
	my ($r, $x) = @_;
	for my $j (0 .. $r->[3] - 1) {
		$x += int(rand(3)) - 1;
		$x = 0 if $x < 0;
		$x = $r->[2] - 1 if $x >= $r->[2];
		at($r, $x, $j, rand() < 0.6 ? 'vein' : 'veinD', 6);
	}
}

# Red flesh with rows of teeth.
sub maw {
	my ($r, $rows) = @_;
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) { at($r, $i, $j, rand() < 0.25 ? 'redD' : rand() < 0.1 ? 'redL' : 'red', 8); }
	}
	for my $j (grep { $_ % $rows == 0 } 0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) { at($r, $i, $j, $i % 2 ? 'toothD' : 'tooth', 5) if rand() < 0.75; }
	}
}

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


# Box UV places packed in rows over the texture (units).
my ($packX, $packY, $rowH) = (0, 0, 0);
sub place {
	my ($w, $h, $d) = @_;
	my ($pw, $ph) = (2 * ($w + $d), $d + $h);
	if ($packX + $pw > $UW) {
		$packX = 0;
		$packY += $rowH;
		$rowH = 0;
	}
	die "texture full" if $packY + $ph > $UH;
	my @uv = ($packX, $packY);
	$packX += $pw;
	$rowH = $ph if $ph > $rowH;
	return \@uv;
}

# The whole creature shifted back so that it stands centred on the entity (head forwards, -z).
my $Z = 7;
sub box {
	my ($bone, $name, $from, $to, $inflate) = @_;
	my @f = ($from->[0], $from->[1], $from->[2] + $Z);
	my @t = ($to->[0], $to->[1], $to->[2] + $Z);
	return cube($bone, $name, \@f, \@t, $inflate // 0, place(map { $t[$_] - $f[$_] } 0 .. 2));
}
sub pv { my ($x, $y, $z) = @_; return [$x, $y, $z + $Z]; }

sub child {
	my ($parent, $name, $origin, $rotation) = @_;
	my $b = {name => $name, origin => $origin, rotation => $rotation // [0, 0, 0], color => 0, uuid => uuid(), export => JSON::PP::true,
		mirror_uv => JSON::PP::false, isOpen => JSON::PP::true, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0, children => []};
	$bones{$name} = $b;
	push @{$parent->{children}}, $b;
	return $b;
}

# ---------------------------------------------------------------- the torso: chest, abdomen, spines
my $torso = bone("torso", pv(0, 15, 7));
my $Ch = box($torso, "chest", [-4, 11, -11], [4, 19, 0]);
skin($Ch->{$_}) for qw(east west up south north);
# Ribs along the flanks and under the chest.
for my $face (qw(east west)) {
	for my $j (1 .. 14) { for my $i (grep { $_ % 4 == 1 } 0 .. 21) { at($Ch->{$face}, $i, $j, 'skinDD', 3); } }
}
skin($Ch->{down}, 'skinD');
for my $i (grep { $_ % 3 == 0 } 0 .. 15) { for my $j (0 .. 21) { at($Ch->{down}, $i, $j, 'skinDD', 3); } }
vein($Ch->{up}, 4);
vein($Ch->{up}, 11);
my $Ab = box($torso, "abdomen", [-3, 12, 0], [3, 17, 9]);
skin($Ab->{$_}) for qw(east west up down south north);
vein($Ab->{up}, 5);
for my $face (qw(east west)) { for my $j (2 .. 7) { at($Ab->{$face}, $_, $j, 'skinDD', 3) for grep { $_ % 3 == 0 } 0 .. 17; } }
for my $k (0 .. 4) {
	my $S = box($torso, "spine_c$k", [-0.5, 19, -10 + 2 * $k], [0.5, 22 - ($k % 2), -9 + 2 * $k]);
	fill($S->{$_}, 'skinDD', 3) for qw(east west north south up down);
	fill($S->{$_}, 'clawL', 3, 0, 0, 2, 2) for qw(north east west);
}
for my $k (0 .. 3) {
	my $S = box($torso, "spine_a$k", [-0.5, 17, 1 + 2 * $k], [0.5, 19, 2 + 2 * $k]);
	fill($S->{$_}, 'skinDD', 3) for qw(east west north south up down);
}

# ---------------------------------------------------------------- neck and head: the bud of petals
my $neck = child($torso, "neck", pv(0, 16.5, -11));
my $Ne = box($neck, "neck", [-1.5, 14.5, -16], [1.5, 18, -11]);
skin($Ne->{$_}, 'skinD') for qw(east west up down north south);
vein($Ne->{up}, 2);
my $head = child($neck, "head", pv(0, 16.5, -16));
my $Bu = box($head, "bud", [-2, 14, -21], [2, 19, -16]);
skin($Bu->{$_}) for qw(east west up down south);
maw($Bu->{north}, 2);
my $Mw = box($head, "throat", [-1.5, 15, -24], [1.5, 18, -21]);
maw($Mw->{$_}, 2) for qw(east west up down);
fill($Mw->{north}, 'throat', 3);
for my $k (0 .. 4) {
	my $P = child($head, "petal$k", pv(0, 16.5, -18), [0, 0, $k * 72]);
	my $L = box($P, "petal${k}_leaf", [-2, 18, -28], [2, 19, -18]);
	# Outside: his skin, a ridge, pink edges; inside (towards the throat): flesh and teeth.
	skin($L->{up});
	for my $j (0 .. $L->{up}[3] - 1) { at($L->{up}, 3, $j, 'skinL', 4); at($L->{up}, 4, $j, 'skinL', 4); }
	for my $j (0 .. $L->{up}[3] - 1) { at($L->{up}, 0, $j, 'pink', 6); at($L->{up}, 7, $j, 'pink', 6); }
	maw($L->{down}, 3);
	for my $j (0 .. $L->{down}[3] - 1) { at($L->{down}, 0, $j, 'tooth', 5); at($L->{down}, 7, $j, 'tooth', 5); }
	fill($L->{$_}, 'pink', 6) for qw(east west north south);
}

# ---------------------------------------------------------------- legs: long, jointed, clawed
# Spider legs: from the shoulder up over the back to a knuckle, out, then a long shank down to the claws. "front_$side"
# (pivot at the shoulder) swings the whole leg; "front_${side}_lower" (pivot at the knuckle) lifts the shank.
sub leg {
	my ($kind, $side, $sx, $sy, $sz, $up, $out, $claws, $clawLen) = @_;
	my $s = $side eq 'right' ? 1 : -1;
	my $root = child($torso, "${kind}_$side", pv($sx * $s, $sy, $sz));
	my ($a, $b) = $s > 0 ? ($sx, $sx + 2) : (-$sx - 2, -$sx);
	my $U = box($root, "${kind}_${side}_upper", [$a, $sy, $sz - 1], [$b, $up, $sz + 1]);
	skin($U->{$_}) for qw(east west north south up down);
	vein($U->{north}, 1);
	my ($ka, $kb) = $s > 0 ? ($sx + 2, $sx + 2 + $out) : (-$sx - 2 - $out, -$sx - 2);
	my $K = box($root, "${kind}_${side}_knuckle", [$ka, $up - 2, $sz - 1], [$kb, $up + 1, $sz + 1]);
	skin($K->{$_}, 'skinD') for qw(east west north south up down);
	fill($K->{up}, 'skinDD', 3, 0, 0, 2 * $out, 2);
	my $kx = $s > 0 ? $kb - 1 : $ka + 1;
	my $lo = child($root, "${kind}_${side}_lower", pv($kx, $up - 0.5, $sz));
	my ($la, $lb) = $s > 0 ? ($kb - 2, $kb) : ($ka, $ka + 2);
	my $L = box($lo, "${kind}_${side}_shank", [$la, 1, $sz - 1], [$lb, $up - 2, $sz + 1]);
	skin($L->{$_}, 'skinD') for qw(east west north south up down);
	vein($L->{$s > 0 ? 'east' : 'west'}, 2);
	for my $j (grep { $_ % 6 == 3 } 0 .. $L->{north}[3] - 1) { fill($L->{north}, 'skinDD', 3, 0, $j, 4, 1); }
	for my $k (0 .. $claws - 1) {
		my $cx = ($la + $lb) / 2 - $claws / 2 + $k;
		my $C = box($lo, "${kind}_${side}_claw$k", [$cx, 0, $sz - $clawLen - ($k == 1 ? 1 : 0)], [$cx + 1, 1, $sz + 1]);
		fill($C->{$_}, 'claw', 3) for qw(east west north south up down);
		fill($C->{up}, 'clawL', 3, 0, 0, 2, 3);
	}
}
leg('front', 'right', 3.5, 16, -8, 24, 4, 3, 5);
leg('front', 'left', 3.5, 16, -8, 24, 4, 3, 5);
leg('hind', 'right', 2.5, 15, 6, 22, 4, 2, 4);
leg('hind', 'left', 2.5, 15, 6, 22, 4, 2, 4);

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
	animations => [],
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;
printf "%s: %d cubes, %d bones, texture %dx%d\n", $out, scalar(@elements), scalar(@outliner), $TW, $TH;

