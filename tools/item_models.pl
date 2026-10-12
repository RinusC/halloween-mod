#!/usr/bin/perl
# 3D models for the mod's items (Minecraft item models: boxes with a texture each face), written into the
# resource pack together with their texture: one atlas of materials (wood, steel, rust, paint, rubber, paper...)
# and decals (the red cross on the med kit, a can's label, the note with the safe's code...). In the inventory
# every item keeps its flat icon (its own, or the vanilla one it used to borrow); everywhere else (in the hand,
# on the ground, flying through the air, in a frame, on a shelf) it is the 3D model.
#
# Tools are built upright (the grip low, about y 1.5, where a vanilla sword's grip is) and the whole model is
# laid over at 45 degrees like a vanilla handheld sprite, so they take vanilla's handheld display and sit in the
# hand where swords do. Everything else is built in the frame its icon is drawn in (what the icon shows lies in
# the x-y plane, the depth along z) and takes vanilla's display for flat items. The flare gun is built like the
# revolver (muzzle towards -z, grip down at the back) and takes its display.
#
#   perl tools/item_models.pl src/main/resources/assets/halloween
use strict;
use warnings;
use JSON::PP;
use Compress::Zlib;

my $res = shift or die "usage: item_models.pl <assets/halloween>\n";
srand(31);

# ---------------------------------------------------------------- the atlas: 16 x 16 tiles of 16 x 16 texels
my $T = 256;
my @px = map { [0, 0, 0, 0] } 1 .. ($T * $T);
my %tile;
my %decal;
my $next = 0;

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v); }
sub put { my ($x, $y, $c) = @_; $px[$y * $T + $x] = [clamp($c->[0]), clamp($c->[1]), clamp($c->[2]), 255]; }
sub shade { my ($c, $d) = @_; return [map { $_ + $d } @$c]; }
sub mix { my ($a, $b, $t) = @_; return [map { $a->[$_] * (1 - $t) + $b->[$_] * $t } 0 .. 2]; }
sub noise { my $n = shift; return int(rand(2 * $n + 1)) - $n; }

# A new tile: its painter gets (i, j) in 0..15 and returns a colour.
sub tile {
	my ($name, $paint, $isDecal) = @_;
	my ($tx, $ty) = ($next % 16, int($next / 16));
	$next++;
	for my $j (0 .. 15) {
		for my $i (0 .. 15) {
			put($tx * 16 + $i, $ty * 16 + $j, $paint->($i, $j));
		}
	}
	($isDecal ? \%decal : \%tile)->{$name} = [$tx, $ty];
}

sub plain {
	my ($name, $c, $n) = @_;
	tile($name, sub { shade($c, noise($n)) });
}

# Materials.
sub grain {
	my ($name, $c) = @_;
	my @off = map { noise(10) } 0 .. 15;
	tile($name, sub {
		my ($i, $j) = @_;
		my $line = (($i + int(sin($j * 0.5 + $i) * 1.5)) % 5 == 0) ? -22 : 0;
		shade($c, $off[$i] * 0.6 + $line + noise(4));
	});
}
grain('wood_light', [178, 134, 86]);
grain('wood_dark', [96, 62, 38]);
tile('steel', sub { my ($i, $j) = @_; shade([166, 170, 176], noise(5) + (($j * 7 + $i) % 11 == 0 ? 18 : 0)); });
plain('steel_dark', [86, 90, 97], 5);
tile('rust', sub {
	my ($i, $j) = @_;
	my $r = rand();
	return $r < 0.5 ? shade([122, 62, 34], noise(8)) : $r < 0.8 ? shade([152, 92, 48], noise(8)) : shade([70, 40, 26], noise(6));
});
plain('iron_black', [42, 42, 46], 5);
tile('brass', sub { my ($i, $j) = @_; shade([198, 160, 70], noise(8) + ($i == $j ? 25 : 0)); });
tile('gold', sub { my ($i, $j) = @_; shade([226, 186, 60], noise(8) + (($i + $j) % 7 == 0 ? 25 : 0)); });
plain('copper', [186, 110, 70], 7);
tile('red_paint', sub { rand() < 0.04 ? shade([86, 90, 97], noise(6)) : shade([172, 34, 30], noise(6)); });
plain('orange', [232, 112, 30], 6);
plain('yellow', [228, 192, 44], 6);
plain('black_rubber', [30, 30, 32], 3);
plain('black_plastic', [38, 38, 42], 2);
plain('white_plastic', [228, 228, 224], 3);
plain('cardboard', [172, 132, 82], 7);
plain('paper', [238, 232, 214], 3);
tile('clay', sub { rand() < 0.1 ? shade([110, 50, 36], noise(6)) : shade([160, 76, 52], noise(9)); });
tile('stone', sub { my ($i, $j) = @_; shade([120, 118, 112], noise(14) + (rand() < 0.08 ? -30 : 0)); });
plain('leather', [112, 72, 42], 7);
tile('twine', sub { my ($i, $j) = @_; shade([168, 146, 102], (($i + $j) % 3 == 0 ? -25 : 0) + noise(5)); });
tile('glass', sub { my ($i, $j) = @_; ($i - $j == 3 || $i - $j == 4) ? [236, 246, 250] : shade([188, 214, 226], noise(3)); });
tile('grip_tape', sub { my ($i, $j) = @_; shade([28, 28, 30], (($i + $j * 2) % 6 < 1 ? 22 : 0) + noise(3)); });
tile('red_rubber', sub { my ($i, $j) = @_; shade([150, 30, 30], ($j % 3 == 0 ? -25 : 0) + noise(4)); });
tile('shell_red', sub { my ($i, $j) = @_; shade([178, 28, 26], ($i % 3 == 0 ? -20 : 0) + noise(4)); });
plain('ceramic', [236, 232, 220], 3);
plain('tin', [190, 192, 196], 6);
plain('green_paint', [62, 112, 60], 6);

# Decals: a whole tile on a face.
sub decalTile { my ($name, $paint) = @_; tile($name, $paint, 1); }
decalTile('med_cross', sub {
	my ($i, $j) = @_;
	return shade([200, 200, 196], noise(2)) if $i == 0 || $j == 0 || $i == 15 || $j == 15;
	my $cross = ($i >= 6 && $i <= 9 && $j >= 3 && $j <= 12) || ($j >= 6 && $j <= 9 && $i >= 3 && $i <= 12);
	return $cross ? shade([196, 30, 30], noise(3)) : shade([232, 232, 228], noise(2));
});
decalTile('jerry', sub {
	my ($i, $j) = @_;
	# Pressed X across the side, a yellow label in the middle.
	return shade([224, 190, 40], noise(4)) if $j >= 6 && $j <= 9 && $i >= 3 && $i <= 12 && !($j == 7 && $i % 2 == 0);
	return [24, 24, 24] if $j == 7 && $i >= 3 && $i <= 12;
	my $x = abs($i - $j) <= 0 || abs($i + $j - 15) <= 0;
	return shade([172, 34, 30], ($x ? -35 : 0) + ($i == 0 || $i == 15 ? -20 : 0) + noise(5));
});
decalTile('spray_label', sub {
	my ($i, $j) = @_;
	return shade([60, 140, 70], noise(3)) if $j >= 5 && $j <= 10;
	return shade([236, 236, 232], noise(2)) if $j < 5;
	return shade([200, 202, 206], noise(4));
});
decalTile('ammo_box', sub {
	my ($i, $j) = @_;
	return [190, 40, 34] if $j >= 3 && $j <= 4;
	return ($j == 8 || $j == 10) && $i >= 4 && $i <= 11 && $i % 2 == 0 ? [30, 30, 30] : shade([238, 232, 210], noise(2)) if $j >= 6 && $j <= 12 && $i >= 2 && $i <= 13;
	return shade([172, 132, 82], noise(6));
});
decalTile('note', sub {
	my ($i, $j) = @_;
	return shade([238, 232, 214], noise(3)) if $j < 2;
	return [46, 60, 120] if $j % 4 == 0 && $i >= 2 && $i <= 13 && rand() < 0.75;
	return [140, 160, 200] if $j % 4 == 1;
	return shade([238, 232, 214], noise(3));
});
decalTile('battery', sub {
	my ($i, $j) = @_;
	return shade([186, 110, 70], noise(5)) if $j < 4;
	return shade([212, 172, 60], noise(4)) if $j == 4 || $j == 5;
	return [220, 220, 220] if ($j == 9 && $i >= 6 && $i <= 9) || ($i == 7 && $j >= 8 && $j <= 10) || ($i == 8 && $j >= 8 && $j <= 10);
	return shade([30, 30, 34], noise(3));
});
decalTile('firecracker', sub {
	my ($i, $j) = @_;
	return shade([226, 186, 60], noise(5)) if $j == 2 || $j == 13 || ($j >= 6 && $j <= 9 && ($i + $j) % 4 == 0);
	return shade([196, 30, 28], noise(5));
});
decalTile('baseball', sub {
	my ($i, $j) = @_;
	my $a = abs(($i - 7.5) ** 2 / 30 + 4 - $j);
	my $b = abs(11 - ($i - 7.5) ** 2 / 30 - $j);
	return [190, 40, 40] if ($a < 0.6 || $b < 0.6) && $i % 2 == 0;
	return shade([238, 234, 222], noise(3));
});
decalTile('tin_can', sub {
	my ($i, $j) = @_;
	return shade([166, 52, 44], noise(4)) if $j >= 5 && $j <= 10 && rand() < 0.85;
	return shade([190, 192, 196], ($j % 3 == 0 ? -20 : 0) + noise(5));
});
decalTile('fuse_label', sub {
	my ($i, $j) = @_;
	return [40, 40, 40] if ($j == 6 || $j == 9) && $i >= 3 && $i <= 12 && $i % 3 != 0;
	return shade([236, 232, 220], noise(3));
});
decalTile('lens', sub {
	my ($i, $j) = @_;
	my $d = sqrt(($i - 7.5) ** 2 + ($j - 7.5) ** 2);
	return shade([170, 170, 172], noise(4)) if $d > 7;
	return [255, 252, 220] if $i < 7 && $j < 7 && $i + $j > 6 && $i + $j < 9;
	return shade([242, 226, 150], noise(4));
});
decalTile('knife_handle', sub {
	my ($i, $j) = @_;
	return shade([210, 180, 90], noise(3)) if ($j == 4 || $j == 11) && $i >= 6 && $i <= 9;
	return shade([96, 62, 38], noise(6) + ($i % 5 == 0 ? -15 : 0));
});
decalTile('flare_label', sub {
	my ($i, $j) = @_;
	return shade([236, 236, 230], noise(2)) if $j >= 6 && $j <= 9;
	return [30, 30, 30] if $j == 7 && $i % 3 == 1;
	return shade([200, 34, 30], noise(5));
});
decalTile('cleaver_blade', sub {
	my ($i, $j) = @_;
	my $hole = sqrt(($i - 4) ** 2 + ($j - 3) ** 2) < 1.6;
	return [20, 20, 22] if $hole;
	return shade([210, 214, 220], noise(3)) if $i >= 13;
	return shade([164, 168, 174], noise(5) + ($j % 5 == 0 ? 8 : 0));
});
decalTile('sand_glass', sub {
	my ($i, $j) = @_;
	return shade([214, 182, 112], noise(8)) if $j >= 8 - ($i > 3 && $i < 12 ? 2 : 0);
	return ($i - $j == 3 || $i - $j == 4) ? [236, 246, 250] : shade([188, 214, 226], noise(3));
});
decalTile('glass_top', sub {
	my ($i, $j) = @_;
	return shade([214, 182, 112], noise(8)) if $j >= 12 && abs($i - 7.5) < 15 - $j;
	return ($i - $j == 3 || $i - $j == 4) ? [236, 246, 250] : shade([188, 214, 226], noise(3));
});
decalTile('medallion', sub {
	my ($i, $j) = @_;
	my $d = sqrt(($i - 7.5) ** 2 + ($j - 7.5) ** 2);
	return shade([150, 116, 40], noise(4)) if abs($d - 6) < 0.8 || abs($d - 3.6) < 0.6;
	return shade([226, 186, 60], noise(8) + ($i == $j ? 20 : 0));
});
decalTile('dart_fin', sub {
	my ($i, $j) = @_;
	return shade([236, 236, 232], noise(2)) if $j >= 6 && $j <= 8;
	return shade([196, 36, 32], noise(4));
});
decalTile('key_bit', sub {
	my ($i, $j) = @_;
	return [0, 0, 0] if 0;
	return shade([60, 58, 56], noise(6) + ($i % 4 == 0 ? -15 : 0));
});
decalTile('wrench_nut', sub {
	my ($i, $j) = @_;
	return shade([120, 124, 130], ($i % 2 == 0 ? -30 : 10) + noise(4));
});

# ---------------------------------------------------------------- the boxes
sub box {
	my ($from, $to, $mat, %o) = @_;
	return {from => $from, to => $to, mat => $mat, %o};
}

# Round along an axis: two crossed boxes (reads as an octagon). $c the centre, $r the radius.
sub cyl {
	my ($axis, $c, $r, $a0, $a1, $mat, %o) = @_;
	my $k = $r * 0.62;
	my @out;
	for my $pair ([$r, $k], [$k, $r]) {
		my ($p, $q) = @$pair;
		my ($from, $to);
		if ($axis eq 'y') {
			($from, $to) = ([$c->[0] - $p, $a0, $c->[2] - $q], [$c->[0] + $p, $a1, $c->[2] + $q]);
		} elsif ($axis eq 'x') {
			($from, $to) = ([$a0, $c->[1] - $p, $c->[2] - $q], [$a1, $c->[1] + $p, $c->[2] + $q]);
		} else {
			($from, $to) = ([$c->[0] - $p, $c->[1] - $q, $a0], [$c->[0] + $p, $c->[1] + $q, $a1]);
		}
		push @out, box($from, $to, $mat, %o);
	}
	return @out;
}

# A ring in the x-y plane (a key's bow, a trap's jaws): eight boxes round a square with its corners cut.
sub ring {
	my ($x0, $y0, $x1, $y1, $t, $z0, $z1, $mat, %o) = @_;
	my $c = $t;
	return (
		box([$x0 + $c, $y1 - $t, $z0], [$x1 - $c, $y1, $z1], $mat, %o),
		box([$x0 + $c, $y0, $z0], [$x1 - $c, $y0 + $t, $z1], $mat, %o),
		box([$x0, $y0 + $c, $z0], [$x0 + $t, $y1 - $c, $z1], $mat, %o),
		box([$x1 - $t, $y0 + $c, $z0], [$x1, $y1 - $c, $z1], $mat, %o),
	);
}

# ---------------------------------------------------------------- the items
my %models;
my %icon;    # the flat icon for the inventory (defaults to halloween:item/<key>)

sub model {
	my ($key, $kind, @els) = @_;
	$models{$key} = {kind => $kind, els => \@els};
}

# Tools, upright (grip about y 1.5).
model('pocket_knife', 'tool',
	box([7.2, -1, 7.4], [8.8, 5, 8.6], 'red_paint'),
	box([7.1, -1.5, 7.3], [8.9, -1, 8.7], 'steel'),
	box([7.1, 5, 7.3], [8.9, 5.5, 8.7], 'steel'),
	box([7.5, 5.5, 7.8], [8.7, 10.5, 8.2], 'steel'),
	box([7.5, 10.5, 7.8], [8.2, 12, 8.2], 'steel'));
model('butterfly_knife', 'tool',
	box([6.4, -2, 7.5], [7.4, 5, 8.5], 'steel_dark'),
	box([8.6, -2, 7.5], [9.6, 5, 8.5], 'steel_dark'),
	box([6.3, -2.6, 7.4], [9.7, -2, 8.6], 'steel'),
	box([7.5, 4.5, 7.8], [8.6, 11.5, 8.2], 'steel'),
	box([7.5, 11.5, 7.8], [8.1, 13, 8.2], 'steel'));
model('kitchen_knife', 'tool',
	box([7.2, -2, 7.4], [8.8, 5, 8.6], 'wood_dark', faces => {north => 'knife_handle', south => 'knife_handle'}),
	box([7.05, 5, 7.5], [8.95, 5.8, 8.5], 'steel'),
	box([7.3, 5.8, 7.8], [9.6, 12.5, 8.2], 'steel'),
	box([7.3, 12.5, 7.8], [8.8, 14, 8.2], 'steel'),
	box([7.3, 14, 7.8], [8, 15, 8.2], 'steel'));
model('cleaver', 'tool',
	box([7.25, -2.5, 7.3], [8.75, 4.5, 8.7], 'wood_dark', faces => {north => 'knife_handle', south => 'knife_handle'}),
	box([7.1, 4.5, 7.5], [8.9, 5.5, 8.5], 'steel'),
	box([6.6, 5.5, 7.75], [13.6, 14.5, 8.25], 'steel', faces => {north => 'cleaver_blade', south => 'cleaver_blade'}));
model('pipe_wrench', 'tool',
	box([7.2, -3, 7.4], [8.8, 10.5, 8.6], 'red_paint'),
	box([7, -3.6, 7.3], [9, -3, 8.7], 'steel_dark'),
	box([6, 10.5, 7.2], [10.5, 12.5, 8.8], 'steel_dark'),
	box([6.6, 12.5, 7.5], [8.6, 14, 8.5], 'steel', faces => {north => 'wrench_nut', south => 'wrench_nut', east => 'wrench_nut', west => 'wrench_nut'}),
	box([6, 14, 7.2], [8.6, 17, 8.8], 'steel_dark'),
	box([8.6, 15.6, 7.3], [10.5, 17, 8.7], 'steel_dark'));
model('bat', 'tool',
	box([6.8, -3, 6.8], [9.2, -2, 9.2], 'wood_light'),
	cyl('y', [8, 0, 8], 0.85, -2, 3.5, 'grip_tape'),
	cyl('y', [8, 0, 8], 0.8, 3.5, 7, 'wood_light'),
	cyl('y', [8, 0, 8], 1.1, 7, 10, 'wood_light'),
	cyl('y', [8, 0, 8], 1.5, 10, 19, 'wood_light'),
	cyl('y', [8, 0, 8], 1.25, 19, 19.8, 'wood_light'));
model('fire_poker', 'tool',
	box([6.4, -3.5, 7.5], [9.6, -2.6, 8.5], 'iron_black'),
	box([6.4, -2.6, 7.5], [7.2, 0, 8.5], 'iron_black'),
	box([8.8, -2.6, 7.5], [9.6, 0, 8.5], 'iron_black'),
	box([7.2, -0.8, 7.5], [8.8, 0, 8.5], 'iron_black'),
	box([7.55, 0, 7.55], [8.45, 19, 8.45], 'iron_black'),
	box([7.7, 19, 7.7], [8.3, 21, 8.3], 'iron_black'),
	box([8.45, 15.5, 7.6], [10.4, 16.3, 8.4], 'iron_black'),
	box([9.6, 16.3, 7.6], [10.4, 18, 8.4], 'iron_black'));
model('hatchet', 'tool',
	cyl('y', [8, 0, 8], 0.75, -3, 11.5, 'wood_light'),
	box([6.6, 9, 7.2], [9.2, 12.4, 8.8], 'steel_dark'),
	box([9.2, 8.4, 7.55], [12.4, 13, 8.45], 'steel'),
	box([12.4, 8.2, 7.7], [12.9, 13.2, 8.3], 'steel'));
model('axe', 'tool',
	cyl('y', [8, 0, 8], 0.8, -5, 17, 'wood_light'),
	box([6.5, 14, 7.1], [9.2, 17.6, 8.9], 'red_paint'),
	box([5.4, 14.6, 7.35], [6.5, 17, 8.65], 'red_paint'),
	box([9.2, 13.2, 7.5], [13.4, 18.4, 8.5], 'steel'),
	box([13.4, 12.8, 7.7], [14, 18.8, 8.3], 'steel'));
model('pickaxe', 'tool',
	cyl('y', [8, 0, 8], 0.75, -4, 13.5, 'wood_light'),
	box([2.4, 12.4, 7.3], [13.6, 14.2, 8.7], 'steel_dark'),
	box([1.2, 11.4, 7.5], [2.4, 13.2, 8.5], 'steel'),
	box([13.6, 11.4, 7.5], [14.8, 13.2, 8.5], 'steel'),
	box([0.6, 10.6, 7.65], [1.2, 12.2, 8.35], 'steel'),
	box([14.8, 10.6, 7.65], [15.4, 12.2, 8.35], 'steel'));
model('pitchfork', 'tool',
	cyl('y', [8, 0, 8], 0.65, -7, 19, 'wood_light'),
	box([7.2, 19, 7.2], [8.8, 20.5, 8.8], 'steel_dark'),
	box([4.6, 20.5, 7.55], [11.4, 21.4, 8.45], 'steel_dark'),
	(map { box([$_, 21.4, 7.65], [$_ + 0.7, 28, 8.35], 'steel_dark') } (4.6, 7.65, 10.7)),
	(map { box([$_ + 0.15, 28, 7.75], [$_ + 0.55, 29, 8.25], 'steel') } (4.6, 7.65, 10.7)));
model('baton', 'tool',
	box([7, -5, 7], [9, -4, 9], 'black_rubber'),
	cyl('y', [8, 0, 8], 0.85, -4, 3, 'grip_tape'),
	box([7, 3, 7], [9, 3.7, 9], 'black_rubber'),
	cyl('y', [8, 0, 8], 0.8, 3.7, 17, 'black_plastic'),
	box([7.3, 17, 7.3], [8.7, 17.6, 8.7], 'black_plastic'));
model('bolt_cutters', 'tool',
	box([5.9, -4, 7.4], [7.3, 3, 8.6], 'red_rubber'),
	box([8.7, -4, 7.4], [10.1, 3, 8.6], 'red_rubber'),
	box([6.2, 3, 7.5], [7.4, 8.5, 8.5], 'steel_dark'),
	box([8.6, 3, 7.5], [9.8, 8.5, 8.5], 'steel_dark'),
	box([6, 8.5, 7.25], [10, 10.5, 8.75], 'steel_dark'),
	box([6.5, 10.5, 7.5], [7.95, 14, 8.5], 'steel'),
	box([8.05, 10.5, 7.5], [9.5, 14, 8.5], 'steel'),
	box([7, 14, 7.6], [7.95, 15, 8.4], 'steel'),
	box([8.05, 14, 7.6], [9, 15, 8.4], 'steel'));
$icon{bolt_cutters} = 'minecraft:item/shears';
model('dart', 'tool',
	box([5.6, -3, 7.8], [10.4, 1, 8.2], 'red_paint', faces => {north => 'dart_fin', south => 'dart_fin'}),
	box([7.8, -3, 5.6], [8.2, 1, 10.4], 'red_paint', faces => {east => 'dart_fin', west => 'dart_fin'}),
	cyl('y', [8, 0, 8], 1, 1, 6, 'yellow'),
	box([7.6, 6, 7.6], [8.4, 14.5, 8.4], 'steel'),
	box([7.8, 14.5, 7.8], [8.2, 16, 8.2], 'steel'));
$icon{dart} = 'minecraft:item/arrow';
model('flashlight', 'tool',
	cyl('y', [8, 0, 8], 1.4, -2, 9, 'black_rubber'),
	box([8, 4, 7.4], [9.7, 5.5, 8.6], 'steel'),
	cyl('y', [8, 0, 8], 2.1, 9, 12.5, 'black_plastic'),
	box([6.2, 12.5, 6.2], [9.8, 13, 9.8], 'steel', faces => {up => 'lens'}));
model('road_flare', 'tool',
	cyl('y', [8, 0, 8], 1.2, -2, 11, 'red_paint', faces => {north => 'flare_label', south => 'flare_label'}),
	cyl('y', [8, 0, 8], 1.3, 11, 13, 'white_plastic'),
	box([7.6, 13, 7.6], [8.4, 13.6, 8.4], 'black_plastic'));
$icon{$_->[0]} = $_->[1] for (['kitchen_knife', 'minecraft:item/stone_sword'], ['hatchet', 'minecraft:item/stone_axe'], ['axe', 'minecraft:item/iron_axe'],
	['pickaxe', 'minecraft:item/iron_pickaxe'], ['pitchfork', 'minecraft:item/trident'], ['baton', 'minecraft:item/breeze_rod']);

# Things, in their icon's frame.
model('gas_can', 'object',
	box([3, 1, 6], [13, 12.5, 10], 'red_paint', faces => {north => 'jerry', south => 'jerry'}),
	box([5, 13.5, 7.5], [11, 14.5, 8.5], 'red_paint'),
	box([5, 12.5, 7.5], [6, 13.5, 8.5], 'red_paint'),
	box([10, 12.5, 7.5], [11, 13.5, 8.5], 'red_paint'),
	cyl('y', [11.8, 0, 8], 0.9, 12.5, 14.8, 'iron_black'));
model('fuse', 'object',
	cyl('y', [8, 0, 8], 1.9, 4, 12, 'ceramic', faces => {north => 'fuse_label', south => 'fuse_label'}),
	cyl('y', [8, 0, 8], 2.3, 2.6, 4, 'brass'),
	cyl('y', [8, 0, 8], 2.3, 12, 13.4, 'brass'),
	box([7.4, 0.6, 7.75], [8.6, 2.6, 8.25], 'brass'),
	box([7.4, 13.4, 7.75], [8.6, 15.4, 8.25], 'brass'));
model('gate_key', 'object',
	ring(3.5, 9, 10.5, 15.5, 1.4, 7.3, 8.7, 'iron_black'),
	box([6.3, 2, 7.3], [7.7, 9, 8.7], 'iron_black'),
	box([7.7, 2, 7.4], [10.6, 5.2, 8.6], 'iron_black', faces => {north => 'key_bit', south => 'key_bit'}),
	box([7.7, 3.2, 7.2], [9, 4, 8.8], 'rust'),
	box([5.8, 8.2, 7.1], [8.2, 9.4, 8.9], 'rust'));
model('padlock_key', 'object',
	ring(5, 9.5, 11, 14.5, 1.2, 7.5, 8.5, 'brass'),
	box([7.4, 3, 7.55], [8.6, 9.5, 8.45], 'brass'),
	box([8.6, 3, 7.6], [10.2, 4.4, 8.4], 'brass'),
	box([8.6, 5.2, 7.6], [9.8, 6.4, 8.4], 'brass'));
$icon{padlock_key} = 'minecraft:item/trial_key';
model('sedan_keys', 'object',
	ring(2.5, 9.5, 7.5, 14.5, 0.8, 7.6, 8.4, 'steel'),
	box([6.5, 6, 7.1], [10.5, 10, 8.9], 'black_plastic'),
	box([7.9, 1, 7.7], [9.1, 6, 8.3], 'steel'),
	box([9.1, 2, 7.75], [9.6, 4.5, 8.25], 'steel'),
	box([3.5, 5.5, 7.7], [5.5, 9.5, 8.3], 'leather'));
$icon{sedan_keys} = 'minecraft:item/ominous_trial_key';
model('repair_kit', 'object',
	box([2, 2, 5], [14, 9, 11], 'red_paint'),
	box([1.8, 9, 4.8], [14.2, 10.4, 11.2], 'red_paint'),
	box([7.2, 7.4, 4.6], [8.8, 9.6, 5], 'steel'),
	box([5, 11.4, 7.5], [11, 12.4, 8.5], 'black_rubber'),
	box([5, 10.4, 7.5], [6, 11.4, 8.5], 'steel_dark'),
	box([10, 10.4, 7.5], [11, 11.4, 8.5], 'steel_dark'));
$icon{repair_kit} = 'minecraft:item/bundle';
model('bear_trap', 'object',
	box([2, 7.2, 7.1], [14, 8.8, 8.9], 'steel_dark'),
	box([0.4, 6.4, 6.6], [3, 9.6, 9.4], 'rust'),
	box([13, 6.4, 6.6], [15.6, 9.6, 9.4], 'rust'),
	ring(3, 3, 13, 13, 1.1, 7.4, 8.6, 'rust'),
	(map { box([$_, 10.9, 7.6], [$_ + 0.8, 11.9, 8.4], 'steel') } (5.2, 7.6, 10)),
	(map { box([$_, 4.1, 7.6], [$_ + 0.8, 5.1, 8.4], 'steel') } (6, 8.4)),
	box([6.6, 6.6, 6.9], [9.4, 9.4, 9.1], 'steel'));
model('tin_cans', 'object',
	cyl('y', [4.6, 0, 8], 2, 1, 7, 'tin', faces => {north => 'tin_can', south => 'tin_can'}),
	cyl('y', [11.2, 0, 8], 2, 1.5, 7.5, 'tin', faces => {north => 'tin_can', south => 'tin_can'}),
	cyl('y', [7.8, 0, 8], 2, 8.5, 14.5, 'tin', faces => {north => 'tin_can', south => 'tin_can'}),
	box([4.4, 7, 7.9], [4.8, 9, 8.1], 'twine'),
	box([11, 7.5, 7.9], [11.4, 9, 8.1], 'twine'),
	box([4.4, 8.6, 7.9], [11.4, 9, 8.1], 'twine'));
model('med_kit', 'object',
	box([2, 3, 5], [14, 12, 11], 'white_plastic', faces => {north => 'med_cross', south => 'med_cross'}),
	box([6, 13, 7.5], [10, 14, 8.5], 'black_plastic'),
	box([6, 12, 7.5], [7, 13, 8.5], 'black_plastic'),
	box([9, 12, 7.5], [10, 13, 8.5], 'black_plastic'),
	box([3.5, 10.4, 4.8], [5, 11.4, 5], 'steel'),
	box([11, 10.4, 4.8], [12.5, 11.4, 5], 'steel'));
model('healing_spray', 'object',
	cyl('y', [8, 0, 8], 2.4, 1, 11.5, 'tin', faces => {north => 'spray_label', south => 'spray_label'}),
	cyl('y', [8, 0, 8], 1.9, 11.5, 13.8, 'white_plastic'),
	box([7.4, 13.8, 7.4], [8.6, 14.6, 8.6], 'white_plastic'),
	box([7.6, 14, 6.6], [8.4, 14.5, 7.4], 'black_plastic'));
model('amulet', 'object',
	box([4.5, 2.5, 7.5], [11.5, 9.5, 8.5], 'gold', faces => {north => 'medallion', south => 'medallion'}),
	box([5.5, 1.5, 7.6], [10.5, 10.5, 8.4], 'gold'),
	box([3.5, 3.5, 7.6], [12.5, 8.5, 8.4], 'gold'),
	box([7, 5, 7.2], [9, 7, 8.8], 'red_paint'),
	box([7.2, 10.5, 7.7], [8.8, 11.8, 8.3], 'gold'),
	box([7.6, 11.3, 7.85], [8.4, 16.5, 8.15], 'gold', rot => ['z', 22.5, [8, 11.5, 8]]),
	box([7.6, 11.3, 7.85], [8.4, 16.5, 8.15], 'gold', rot => ['z', -22.5, [8, 11.5, 8]]));
model('hourglass', 'object',
	box([3, 1, 5], [13, 2.5, 11], 'wood_dark'),
	box([3, 13.5, 5], [13, 15, 11], 'wood_dark'),
	(map { box([$_->[0], 2.5, $_->[1]], [$_->[0] + 1, 13.5, $_->[1] + 1], 'wood_dark') } ([3.5, 5.5], [11.5, 5.5], [3.5, 9.5], [11.5, 9.5])),
	box([5.2, 2.5, 6.2], [10.8, 7, 9.8], 'glass', faces => {map { $_ => 'sand_glass' } qw(north south east west)}),
	box([7, 7, 7], [9, 8.5, 9], 'glass'),
	box([5.2, 8.5, 6.2], [10.8, 13.5, 9.8], 'glass', faces => {map { $_ => 'glass_top' } qw(north south east west)}));
model('revolver_ammo', 'object',
	box([4, 2, 6], [12, 7, 10], 'cardboard', faces => {north => 'ammo_box', south => 'ammo_box'}),
	(map { (cyl('y', [$_, 0, 8], 0.55, 7, 9, 'brass'), box([$_ - 0.35, 9, 7.65], [$_ + 0.35, 9.7, 8.35], 'copper')) } (5.5, 7.2, 8.9, 10.6)));
model('rifle_ammo', 'object',
	box([3.2, 1.5, 7.3], [12.8, 2.3, 8.7], 'steel_dark'),
	(map { (cyl('y', [$_, 0, 8], 0.7, 2.3, 10, 'brass'), box([$_ - 0.4, 10, 7.6], [$_ + 0.4, 11.2, 8.4], 'copper'), box([$_ - 0.25, 11.2, 7.75], [$_ + 0.25, 12, 8.25], 'copper')) } (4.2, 6.1, 8, 9.9, 11.8)));
model('shotgun_shells', 'object',
	(map { my ($x, $y) = @$_; (cyl('y', [$x, 0, 8], 1.3, $y + 2, $y + 9, 'shell_red'), cyl('y', [$x, 0, 8], 1.4, $y, $y + 2, 'brass')) } ([4.6, 1], [8, 3], [11.4, 1.5])));
model('safe_code', 'object',
	box([3, 2, 7.8], [13, 14, 8.2], 'paper', faces => {north => 'note', south => 'note'}));
model('battery', 'object',
	cyl('y', [8, 0, 8], 2.4, 2, 13, 'black_plastic', faces => {north => 'battery', south => 'battery'}),
	cyl('y', [8, 0, 8], 0.7, 13, 14, 'steel'),
	cyl('y', [8, 0, 8], 2.2, 1.4, 2, 'steel'));
$icon{battery} = 'minecraft:item/iron_nugget';
model('brick', 'object',
	box([2, 5, 5.5], [14, 10, 10.5], 'clay'),
	box([2.4, 9.6, 6], [5, 10.3, 7.5], 'ceramic'));
$icon{brick} = 'minecraft:item/brick';
model('rock', 'object',
	box([4, 4, 5], [11, 10, 11], 'stone', rot => ['z', 22.5, [8, 7, 8]]),
	box([6, 3, 6], [12, 8, 10.5], 'stone', rot => ['y', -22.5, [9, 5, 8]]),
	box([5, 7, 6.5], [10, 11.5, 9.5], 'stone', rot => ['x', 22.5, [7, 9, 8]]));
$icon{rock} = 'minecraft:item/flint';
model('baseball', 'object',
	box([4.5, 5, 5], [11.5, 11, 11], 'white_plastic', faces => {map { $_ => 'baseball' } qw(north south east west up down)}),
	box([5, 4.5, 5], [11, 11.5, 11], 'white_plastic', faces => {map { $_ => 'baseball' } qw(north south east west up down)}),
	box([5, 5, 4.5], [11, 11, 11.5], 'white_plastic', faces => {map { $_ => 'baseball' } qw(north south east west up down)}));
$icon{baseball} = 'minecraft:item/snowball';
model('firecrackers', 'object',
	(map { box([$_, 2, 7], [$_ + 1.6, 10, 8.8], 'red_paint', faces => {north => 'firecracker', south => 'firecracker'}) } (3, 4.8, 6.6, 8.4, 10.2, 12)),
	box([2.8, 5.4, 6.8], [13.8, 6.4, 9], 'brass'),
	(map { box([$_ + 0.6, 10, 7.7], [$_ + 1, 11.2, 8.1], 'twine') } (3, 4.8, 6.6, 8.4, 10.2, 12)),
	box([3.6, 11.2, 7.7], [13, 11.6, 8.1], 'twine'),
	box([8.1, 11.6, 7.7], [8.5, 15, 8.1], 'twine'));
$icon{firecrackers} = 'minecraft:item/firework_rocket';

# Guns, built as the revolver: the barrel along -z, the grip down at the back.
model('flare_gun', 'gun',
	cyl('z', [8, 8.8, 0], 1.15, 3, 9.6, 'orange'),
	box([6.9, 7.8, 2.6], [9.1, 9.8, 3.2], 'orange'),
	box([7.1, 7.5, 9.6], [8.9, 10, 11.4], 'orange'),
	box([7.25, 4, 10.4], [8.75, 7.5, 12.6], 'orange'),
	box([7.15, 3.6, 10.3], [8.85, 4, 12.8], 'black_plastic'),
	box([7.6, 6, 8.4], [8.4, 7.2, 9.4], 'black_plastic'),
	box([7.3, 5.6, 8], [8.7, 6, 10.2], 'orange'),
	box([7.6, 10.2, 10.4], [8.4, 11.2, 11.2], 'black_plastic'));

# How a thing is held in a hand seen from outside: upright, gripped this far above its middle (a handle, a key's
# bow, an amulet's chain: it hangs from the fist), at this size.
my %hold = (
	gas_can => [6, 0.7], med_kit => [5.5, 0.65], repair_kit => [4, 0.7], tin_cans => [1, 0.6], amulet => [8, 0.5],
	gate_key => [4, 0.55], padlock_key => [4, 0.55], sedan_keys => [4, 0.55], bear_trap => [0, 0.6], hourglass => [0, 0.5],
);

# The flashlight switched on: held like a gun, the lens forward where one looks (the item carries a flag, see
# HwItems.setLit).
model('flashlight_on', 'gun',
	cyl('z', [8, 8.4, 0], 1.4, 5, 14, 'black_rubber'),
	box([8, 9.4, 9], [8.6, 10.2, 10.5], 'steel'),
	cyl('z', [8, 8.4, 0], 2.1, 2, 5, 'black_plastic'),
	box([6.2, 6.6, 1.6], [9.8, 10.2, 2], 'steel', faces => {north => 'lens'}));

# ---------------------------------------------------------------- writing it out
my %display = (
	tool => {
		thirdperson_righthand => {rotation => [0, -90, 55], translation => [0, 4, 0.5], scale => [0.85, 0.85, 0.85]},
		thirdperson_lefthand => {rotation => [0, 90, -55], translation => [0, 4, 0.5], scale => [0.85, 0.85, 0.85]},
		firstperson_righthand => {rotation => [0, -90, 25], translation => [1.13, 3.2, 1.13], scale => [0.68, 0.68, 0.68]},
		firstperson_lefthand => {rotation => [0, 90, -25], translation => [1.13, 3.2, 1.13], scale => [0.68, 0.68, 0.68]},
		ground => {rotation => [0, 0, 0], translation => [0, 2, 0], scale => [0.5, 0.5, 0.5]},
		head => {rotation => [0, 180, 0], translation => [0, 13, 7], scale => [1, 1, 1]},
		fixed => {rotation => [0, 180, 0], translation => [0, 0, 0], scale => [0.9, 0.9, 0.9]},
		on_shelf => {rotation => [0, 180, 0], translation => [0, 0, 0], scale => [0.8, 0.8, 0.8]},
	},
	object => {
		thirdperson_righthand => {rotation => [0, 0, 0], translation => [0, 3, 1], scale => [0.55, 0.55, 0.55]},
		thirdperson_lefthand => {rotation => [0, 0, 0], translation => [0, 3, 1], scale => [0.55, 0.55, 0.55]},
		# Smaller than a flat item (it has depth), turned to show its face.
		firstperson_righthand => {rotation => [0, -55, 10], translation => [1.13, 2.6, 1.13], scale => [0.42, 0.42, 0.42]},
		firstperson_lefthand => {rotation => [0, 55, -10], translation => [1.13, 2.6, 1.13], scale => [0.42, 0.42, 0.42]},
		ground => {rotation => [0, 0, 0], translation => [0, 2, 0], scale => [0.5, 0.5, 0.5]},
		head => {rotation => [0, 180, 0], translation => [0, 13, 7], scale => [1, 1, 1]},
		fixed => {rotation => [0, 180, 0], translation => [0, 0, 0], scale => [1, 1, 1]},
		on_shelf => {rotation => [0, 180, 0], translation => [0, 0, 0], scale => [0.9, 0.9, 0.9]},
	},
	gun => {
		thirdperson_righthand => {rotation => [90, 0, 0], translation => [0, 3, 1], scale => [0.6, 0.6, 0.6]},
		thirdperson_lefthand => {rotation => [90, 0, 0], translation => [0, 3, 1], scale => [0.6, 0.6, 0.6]},
		firstperson_righthand => {rotation => [0, 0, 0], translation => [1, 2.5, -1], scale => [0.8, 0.8, 0.8]},
		firstperson_lefthand => {rotation => [0, 0, 0], translation => [1, 2.5, -1], scale => [0.8, 0.8, 0.8]},
		ground => {rotation => [0, 0, 0], translation => [0, 2, 0], scale => [0.5, 0.5, 0.5]},
		head => {rotation => [0, 180, 0], translation => [0, 13, 7], scale => [1, 1, 1]},
		fixed => {rotation => [0, 90, 0], translation => [0, 0, 0], scale => [0.8, 0.8, 0.8]},
		on_shelf => {rotation => [0, 90, 0], translation => [0, 0, 0], scale => [0.7, 0.7, 0.7]},
	},
);

sub r4 { return 0 + sprintf("%.4f", shift); }

# A face's UV: a decal stretched over it whole; a material at one texel to a unit, from a random spot of its tile.
sub uv {
	my ($mat, $w, $h) = @_;
	if (my $d = $decal{$mat}) {
		return [map { r4($_) } ($d->[0], $d->[1], $d->[0] + 1, $d->[1] + 1)];
	}
	my $t = $tile{$mat} or die "no material $mat";
	my $uw = (abs($w) > 16 ? 16 : abs($w)) / 16;
	my $vh = (abs($h) > 16 ? 16 : abs($h)) / 16;
	$uw = 1 / 16 if $uw < 1 / 16;
	$vh = 1 / 16 if $vh < 1 / 16;
	my $u = $t->[0] + rand(1 - $uw);
	my $v = $t->[1] + rand(1 - $vh);
	return [map { r4($_) } ($u, $v, $u + $uw, $v + $vh)];
}

sub write_json {
	my ($path, $data) = @_;
	open my $o, ">", $path or die "$path: $!";
	print $o JSON::PP->new->canonical->pretty->indent_length(1)->encode($data);
	close $o;
}

my @written;
for my $key (sort keys %models) {
	my $m = $models{$key};
	my @elements;
	for my $b (@{$m->{els}}) {
		my ($f, $t) = ($b->{from}, $b->{to});
		for my $i (0 .. 2) {
			die "$key: box out of bounds" if $f->[$i] < -16 || $t->[$i] > 32 || $f->[$i] >= $t->[$i];
		}
		my ($w, $h, $d) = map { $t->[$_] - $f->[$_] } 0 .. 2;
		my %size = (north => [$w, $h], south => [$w, $h], east => [$d, $h], west => [$d, $h], up => [$w, $d], down => [$w, $d]);
		my %faces;
		for my $dir (qw(north south east west up down)) {
			my $mat = $b->{faces}{$dir} // $b->{mat};
			$faces{$dir} = {uv => uv($mat, @{$size{$dir}}), texture => "#0"};
		}
		my $e = {from => [map { r4($_) } @$f], to => [map { r4($_) } @$t], faces => \%faces};
		if ($m->{kind} eq 'tool') {
			die "$key: a tool's box can't turn on its own" if $b->{rot};
			$e->{rotation} = {origin => [8, 8, 8], axis => "z", angle => -45};
		} elsif ($b->{rot}) {
			$e->{rotation} = {origin => $b->{rot}[2], axis => $b->{rot}[0], angle => $b->{rot}[1]};
		}
		push @elements, $e;
	}
	my %disp = %{$display{$m->{kind}}};
	if ($m->{kind} eq 'object') {
		my ($h, $s) = @{$hold{$key} // [0, 0.55]};
		for my $hand (qw(thirdperson_righthand thirdperson_lefthand)) {
			$disp{$hand} = {rotation => [90, 0, 0], translation => [0, 2, r4(1 - $h * $s)], scale => [$s, $s, $s]};
		}
	}
	write_json("$res/models/item/${key}_3d.json", {
		textures => {"0" => "halloween:item/props", particle => "halloween:item/props"},
		elements => \@elements,
		display => \%disp,
	});
	# Variants have no item of their own.
	next if $key eq 'flashlight_on';
	my $iconModel = $icon{$key} // "halloween:item/$key";
	my $held = {type => "minecraft:model", model => "halloween:item/${key}_3d"};
	if ($key eq 'flashlight') {
		$held = {type => "minecraft:condition", property => "minecraft:custom_model_data", index => 0,
			on_true => {type => "minecraft:model", model => "halloween:item/flashlight_on_3d"}, on_false => $held};
	}
	write_json("$res/items/$key.json", {model => {
		type => "minecraft:select", property => "minecraft:display_context",
		cases => [{when => ["gui"], model => {type => "minecraft:model", model => $iconModel}}],
		fallback => $held,
	}});
	push @written, $key;
}

# The texture.
my $raw = '';
for my $y (0 .. $T - 1) {
	$raw .= "\0";
	$raw .= pack("C4", @{$px[$y * $T + $_]}) for 0 .. $T - 1;
}
my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
open my $p, ">:raw", "$res/textures/item/props.png" or die $!;
print $p "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $T, $T, 8, 6, 0, 0, 0)) . $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
close $p;
printf "%d models: %s\n%d tiles used\n", scalar @written, join(" ", @written), $next;
