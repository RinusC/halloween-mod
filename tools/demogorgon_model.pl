#!/usr/bin/perl
# The Demogorgon (Stranger Things) as a Blockbench model: writes a .bbmodel (box UV) that opens in Blockbench and
# that tools/bb2entity.pl turns into the game's model (client.DemogorgonModel moves it).
#
# 2.6 blocks tall standing (38-39 units here; the game draws him at MichaelRenderer.TALL), thin and long-limbed:
# a skeleton of his own (not a player's): hips, waist, chest, a long neck, the head (a closed bud of five petals,
# each in two parts, lined with teeth inside; teeth round the throat under them); arms with shoulder, upper arm,
# forearm, hand, three long two-jointed fingers with claws and a thumb; legs with thigh, shin, foot and three
# clawed toes. Wet mauve skin, muscle running along every limb, ribs, a ridged spine, veins.
#
# The animations (Blockbench's own, sampled by the game):
#   idle         standing, breathing, the head slowly searching, fingers flexing
#   walk         upright stalking walk, feet planted (legs placed by inverse kinematics)
#   fours_idle   on all fours, low and breathing, the head scanning
#   crawl        on all fours, diagonal pairs, hands and feet planted, the spine rolling
#   charge       coiled on all fours before a lunge, trembling
#   lunge        stretched out flying forwards, claws reaching
#   strike       standing: both claws up over him and raking down (laid over the stance)
#   strike_fours on all fours: up on the hind legs and a swipe (laid over the stance)
#   mouth        the petals from closed (0 s) to flung open and curled back (1 s)
#   twitch       spasms of the head and neck (laid over everything now and then)
# idle, walk, fours_idle, crawl, charge and lunge are whole poses (blended by weight); the others are laid on top.
#
#   perl tools/demogorgon_model.pl art/demogorgon.bbmodel
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;
use POSIX qw(ceil);

my $out = shift or die "usage: demogorgon_model.pl out.bbmodel\n";
my ($UW, $UH) = (128, 72);
my $S = 6;
# Texture detail was drawn for 2 texels a unit: $K times that now.
my $K = $S / 2;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1983);
my $PI = 3.14159265358979;

# ---------------------------------------------------------------- colours (wet mauve flesh, red mouth)
my %C = (
	skin => [146, 108, 114], skinD => [110, 78, 88], skinDD => [70, 46, 58], skinL => [180, 144, 146], wet => [222, 198, 196],
	vein => [112, 46, 60], red => [150, 30, 38], redD => [90, 12, 22], redL => [200, 70, 72], throat => [26, 6, 10],
	tooth => [232, 222, 200], toothD => [176, 160, 132], claw => [52, 38, 40], clawL => [214, 202, 176],
);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }

sub put {
	my ($x, $y, $c) = @_;
	return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH;
	$px[$y * $TW + $x] = [map({ clamp($_) } @$c[0 .. 2]), 255];
}

sub mix {
	my ($a, $b, $t) = @_;
	return [map { $a->[$_] + ($b->[$_] - $a->[$_]) * $t } 0 .. 2];
}

sub mul { my ($c, $k) = @_; return [map { $_ * $k } @$c]; }

sub jit {
	my ($c, $n) = @_;
	my $d = rand(2 * $n) - $n;
	return [map { $_ + $d } @$c];
}

# ---------------------------------------------------------------- texture painting
# A face's rectangle: [x, y, w, h] in texels. $along: 'v' when the limb runs down the face (muscle fibres
# vertical), 'h' when across it.
sub flesh {
	my ($r, %o) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	my $base = $C{$o{base} // 'skin'};
	my $ph = rand(20);
	my $along = $o{along} // 'v';
	my $freq = $o{freq} // 1.7;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my ($a, $b) = $along eq "v" ? ($i / $K, $j / $K) : ($j / $K, $i / $K);
			my $len = ($along eq "v" ? $h : $w) / $K;
			my $t = $len > 1 ? $b / ($len - 1) : 0.5;
			# Darker towards the joints at either end.
			my $shade = 1 - 0.22 * (abs($t - 0.45) * 2) ** 2;
			$shade *= $o{shade} // 1;
			my $st = sin(($a + $ph) * $freq + sin($b * 0.33 + $ph) * 1.4);
			my $c = mul($base, $shade);
			if ($st > 0.8) {
				$c = mix($c, $C{skinDD}, 0.55);
			} elsif ($st > 0.55) {
				$c = mix($c, $C{skinD}, 0.4);
			} elsif ($st < -0.9) {
				$c = mix($c, $C{wet}, 0.45);
			} elsif ($st < -0.7) {
				$c = mix($c, $C{skinL}, 0.4);
			}
			$c = mix($c, $C{vein}, 0.6) if rand() < 0.025;
			$c = mix($c, $C{wet}, 0.5) if rand() < 0.02;
			put($x0 + $i, $y0 + $j, jit($c, 5));
		}
	}
	# A vein or two wandering along it, a texel or two wide, a little raised (a highlight beside it).
	for (1 .. ($o{veins} // 1)) {
		next if $w < 3 * $K || $h < 3 * $K;
		my $vertical = $along eq 'v';
		my ($n, $m) = $vertical ? ($h, $w) : ($w, $h);
		my $x = rand($m);
		for my $j (0 .. $n - 1) {
			$x += (rand(2) - 1) * 0.6;
			$x = 1 if $x < 1;
			$x = $m - 2 if $x > $m - 2;
			my $xi = int($x);
			my @at = $vertical ? ([$x0 + $xi, $y0 + $j], [$x0 + $xi + 1, $y0 + $j], [$x0 + $xi + 2, $y0 + $j])
				: ([$x0 + $j, $y0 + $xi], [$x0 + $j, $y0 + $xi + 1], [$x0 + $j, $y0 + $xi + 2]);
			put(@{$at[0]}, jit(mix(px_at(@{$at[0]}), $C{vein}, 0.55), 4));
			put(@{$at[1]}, jit(mix(px_at(@{$at[1]}), $C{vein}, 0.4), 4));
			put(@{$at[2]}, jit(mix(px_at(@{$at[2]}), $C{wet}, 0.25), 4)) if $at[2][0] < $x0 + $w && $at[2][1] < $y0 + $h;
		}
	}
}

sub px_at { my ($x, $y) = @_; my $p = $px[$y * $TW + $x]; return [@$p[0 .. 2]]; }

# A dark groove across a face with a wet highlight under it (ribs, the belly's segments). Places in the old
# 2-texels-a-unit grid; drawn $K times finer.
sub groove {
	my ($r, $j, $i0, $i1) = @_;
	my $y = int($j * $K);
	for my $i (int($i0 * $K) .. int(($i1 + 1) * $K) - 1) {
		next if $i < 0 || $i >= $r->[2];
		for my $d (0 .. $K - 1) {
			my $yy = $y + $d;
			next if $yy >= $r->[3];
			my $k = $d < $K / 2 ? 0.8 : 0.5;
			put($r->[0] + $i, $r->[1] + $yy, jit(mix(px_at($r->[0] + $i, $r->[1] + $yy), $C{skinDD}, $k), 3));
		}
		my $yh = $y + $K;
		put($r->[0] + $i, $r->[1] + $yh, jit(mix(px_at($r->[0] + $i, $r->[1] + $yh), $C{wet}, 0.45), 3)) if $yh < $r->[3];
	}
}

sub vgroove {
	my ($r, $i, $j0, $j1) = @_;
	my $x = int($i * $K + $K / 2);
	for my $j (int($j0 * $K) .. int(($j1 + 1) * $K) - 1) {
		next if $j < 0 || $j >= $r->[3];
		put($r->[0] + $x, $r->[1] + $j, jit(mix(px_at($r->[0] + $x, $r->[1] + $j), $C{skinDD}, 0.8), 3));
		put($r->[0] + $x - 1, $r->[1] + $j, jit(mix(px_at($r->[0] + $x - 1, $r->[1] + $j), $C{skinD}, 0.5), 3)) if $x > 0;
		put($r->[0] + $x + 1, $r->[1] + $j, jit(mix(px_at($r->[0] + $x + 1, $r->[1] + $j), $C{wet}, 0.35), 3)) if $x + 1 < $r->[2];
	}
}

sub solid {
	my ($r, $name, $n) = @_;
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) { put($r->[0] + $i, $r->[1] + $j, jit($C{$name}, $n // 5)); }
	}
}

# Claws: dark at the root, bone-pale to the point.
sub clawpaint {
	my ($r, $tipAt) = @_;
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			my $t = $r->[3] > 1 ? $j / ($r->[3] - 1) : 0.5;
			$t = 1 - $t if ($tipAt // 'down') eq 'up';
			put($r->[0] + $i, $r->[1] + $j, jit(mix($C{claw}, $C{clawL}, $t ** 0.8), 6));
		}
	}
}

# Red flesh lined with rows of small hooked teeth (pale points, darker roots), wet folds between.
sub maw {
	my ($r, %o) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $fold = sin($i / $K * 1.7 + sin($j / $K) * 1.3);
			my $c = $fold > 0.7 ? $C{redD} : $fold < -0.8 ? $C{redL} : $C{red};
			$c = mix($c, $C{wet}, 0.35) if rand() < 0.03;
			put($x0 + $i, $y0 + $j, jit($c, 10));
		}
	}
	# A tooth: a small triangle, point towards the face's middle.
	my $tooth = sub {
		my ($cx, $cy, $dir) = @_;
		my $len = int(1.3 * $K);
		for my $d (0 .. $len - 1) {
			my $half = int(($len - $d) * 0.35);
			for my $e (-$half .. $half) {
				my ($x, $y) = $dir eq 'down' ? ($cx + $e, $cy + $d) : $dir eq 'up' ? ($cx + $e, $cy - $d) : $dir eq 'right' ? ($cx + $d, $cy + $e) : ($cx - $d, $cy + $e);
				next if $x < $x0 || $y < $y0 || $x >= $x0 + $w || $y >= $y0 + $h;
				put($x, $y, jit(mix($C{redD}, mix($C{toothD}, $C{tooth}, $d / $len), 0.35 + 0.65 * $d / $len), 6));
			}
		}
	};
	my $step = int(2.2 * $K);
	for (my $jj = int($K); $jj < $h - $K; $jj += int(3.6 * $K)) {
		for (my $ii = int($K / 2) + ($jj / $K % 2 ? $step / 2 : 0); $ii < $w; $ii += $step) {
			$tooth->($x0 + $ii, $y0 + $jj, 'down');
		}
	}
	if ($o{edge}) {
		for (my $ii = int($K / 2); $ii < $w; $ii += $step) { $tooth->($x0 + $ii, $y0, 'down'); }
		for (my $jj = int($K / 2); $jj < $h; $jj += $step) {
			$tooth->($x0, $y0 + $jj, 'right');
			$tooth->($x0 + $w - 1, $y0 + $jj, 'left');
		}
	}
}

# ---------------------------------------------------------------- the atlas: shelves of box-UV footprints
my ($shelfX, $shelfY, $shelfH) = (0, 0, 0);

sub alloc {
	my ($w, $h) = @_;
	if ($shelfX + $w > $UW) {
		$shelfY += $shelfH;
		($shelfX, $shelfH) = (0, 0);
	}
	die "atlas full at $w x $h\n" if $shelfY + $h > $UH;
	my @at = ($shelfX, $shelfY);
	$shelfX += $w;
	$shelfH = $h if $h > $shelfH;
	return \@at;
}

# ---------------------------------------------------------------- bones and cubes
my $uuidN = 0;
sub uuid { return sprintf("%08x-%04x-4%03x-a%03x-%012x", int(rand(0xffffffff)), int(rand(0xffff)), int(rand(0xfff)), int(rand(0xfff)), ++$uuidN); }

my (@elements, @outliner, %bones, %parentOf, %pivot);

sub bone {
	my ($name, $parent, $origin, $rotation) = @_;
	my $b = {name => $name, origin => $origin, rotation => $rotation // [0, 0, 0], color => 0, uuid => uuid(), export => JSON::PP::true,
		mirror_uv => JSON::PP::false, isOpen => JSON::PP::false, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0,
		children => []};
	$bones{$name} = $b;
	$parentOf{$name} = $parent;
	$pivot{$name} = $origin;
	if (defined $parent) {
		push @{$bones{$parent}{children}}, $b;
	} else {
		push @outliner, $b;
	}
	return $b;
}

# A cube on a bone; returns its faces' rectangles in the texture (texels). Facing north (-z) as the model does,
# east (+x) is his right.
sub cube {
	my ($bname, $name, $from, $to, $inflate) = @_;
	my $bone = $bones{$bname} or die "no bone $bname";
	my ($w, $h, $d) = map { $to->[$_] - $from->[$_] } 0 .. 2;
	my $uv = alloc(ceil(2 * ($d + $w) - 1e-6), ceil($d + $h - 1e-6));
	my ($u, $v) = @$uv;
	push @elements, {
		name => $name, box_uv => JSON::PP::true, rescale => JSON::PP::false, locked => JSON::PP::false, render_order => "default",
		allow_mirror_modeling => JSON::PP::true, from => $from, to => $to, autouv => 0, color => 0, inflate => $inflate // 0,
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
	my $R = sub { return [map { int($_ * $S + 0.5) } @_]; };
	return {
		up => $R->($u + $d, $v, $w, $d),
		down => $R->($u + $d + $w, $v, $w, $d),
		east => $R->($u, $v + $d, $d, $h),
		north => $R->($u + $d, $v + $d, $w, $h),
		west => $R->($u + $d + $w, $v + $d, $d, $h),
		south => $R->($u + 2 * $d + $w, $v + $d, $w, $h),
	};
}

# Flesh on every face of a limb-like cube (muscle running down it); the ends darker.
sub fleshy {
	my ($F, %o) = @_;
	flesh($F->{$_}, %o) for qw(north south east west);
	flesh($F->{$_}, %o, shade => 0.8, veins => 0) for qw(up down);
}

# ---------------------------------------------------------------- the body
bone("hips", undef, [0, 19, 0.5]);
my $F;
$F = cube("hips", "pelvis", [-2.5, 16.5, -1], [2.5, 20.5, 2]);
fleshy($F, along => 'h', freq => 1.2);
groove($F->{north}, 5, 1, 8);
$F = cube("hips", "hip_bones", [-3, 18.5, -0.5], [3, 20, 1.5], -0.1);
fleshy($F, along => 'h', base => 'skinL');

bone("waist", "hips", [0, 20.5, 0.6]);
$F = cube("waist", "waist", [-1.5, 20.5, -0.5], [1.5, 24.5, 1.5]);
fleshy($F, freq => 2.2);
$F = cube("waist", "belly", [-1.5, 20.5, -1], [1.5, 24.5, 0], 0.1);
flesh($F->{north}, base => 'skinL', along => 'h');
groove($F->{north}, $_, 0, 5) for (1, 3, 5);
vgroove($F->{north}, 2, 0, 7);
flesh($F->{$_}, along => 'v') for qw(east west up down south);

bone("chest", "waist", [0, 24.5, 0.6]);
$F = cube("chest", "ribcage", [-3, 24.5, -1], [3, 30, 2.5]);
fleshy($F, along => 'h', freq => 1.4);
# Ribs on the front and sides, the breastbone down the middle.
for my $j (2, 4, 6, 8) {
	groove($F->{north}, $j, 0, 4);
	groove($F->{north}, $j, 7, 11);
	groove($F->{east}, $j, 0, 6);
	groove($F->{west}, $j, 0, 6);
}
vgroove($F->{north}, 5, 0, 10);
$F = cube("chest", "pectorals", [-3, 27.5, -1.5], [3, 30.5, 0.5], -0.05);
fleshy($F, along => 'h', freq => 1.1);
groove($F->{north}, 5, 0, 11);
vgroove($F->{north}, 5, 0, 5);
$F = cube("chest", "collar", [-3.5, 29.5, -0.5], [3.5, 31, 2], -0.1);
fleshy($F, along => 'h', base => 'skinL');
# The spine: a ridge of knobs down the back.
for my $k (0 .. 4) {
	my $y = 24.8 + $k * 1.2;
	$F = cube("chest", "spine$k", [-0.5, $y, 2.4], [0.5, $y + 1, 3.1], -0.05);
	fleshy($F, base => 'skinL');
	solid($F->{up}, 'wet', 8);
}
$F = cube("chest", "shoulder_blades", [-2.5, 26.5, 2], [2.5, 29.5, 3], -0.15);
fleshy($F, along => 'h');
vgroove($F->{south}, 4, 0, 5);

bone("neck", "chest", [0, 30.5, 0.4]);
$F = cube("neck", "neck", [-1, 30.5, -0.6], [1, 33.5, 1.4]);
fleshy($F, freq => 2.4, veins => 2);
$F = cube("neck", "throat", [-0.5, 30.5, -1], [0.5, 33, 0], 0.05);
fleshy($F, base => 'skinL');
$F = cube("neck", "neck_sinew", [-1.5, 30.5, 0], [1.5, 32, 1.5], -0.2);
fleshy($F, base => 'skinD');

# ---------------------------------------------------------------- the head: a bud of five petals round a throat of teeth
bone("head", "neck", [0, 33.5, 0.4]);
$F = cube("head", "skull", [-1.5, 33.5, -1.1], [1.5, 36.5, 1.9]);
fleshy($F, freq => 2.0);
# Under the petals the top is the mouth: a dark throat ringed with teeth.
{
	my $r = $F->{up};
	my ($cx, $cy) = (($r->[2] - 1) / 2, ($r->[3] - 1) / 2);
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			my $d = sqrt(($i - $cx) ** 2 + ($j - $cy) ** 2) / ($r->[2] / 2);
			my $c = $d < 0.45 ? $C{throat} : $d < 0.7 ? $C{redD} : (int(($i + $j) / $K) % 2 ? $C{tooth} : $C{red});
			put($r->[0] + $i, $r->[1] + $j, jit($c, 8));
		}
	}
}
$F = cube("head", "jaw_ring", [-1.8, 35.5, -1.4], [1.8, 36.6, 2.2], -0.1);
fleshy($F, along => 'h', base => 'skinD');
maw($F->{up}, edge => 1);
# Teeth standing round the throat.
for my $k (0 .. 7) {
	my $a = $k / 8 * 2 * $PI;
	my ($x, $z) = (sin($a) * 1.0, 0.4 - cos($a) * 1.0);
	$F = cube("head", "tooth$k", [$x - 0.5, 36.2, $z - 0.5], [$x + 0.5, 37.4, $z + 0.5], -0.32);
	clawpaint($F->{$_}, 'up') for qw(north south east west);
	solid($F->{up}, 'tooth', 4);
	solid($F->{down}, 'toothD', 4);
}
for my $k (0 .. 4) {
	my $ring = bone("petal${k}_ring", "head", [0, 35, 0.4], [0, $k * 72, 0]);
	# Closed: leaning in over the head, tips together.
	bone("petal$k", "petal${k}_ring", [0, 35, -1.2], [16, 0, 0]);
	$F = cube("petal$k", "petal${k}_base", [-1.75, 35, -1.7], [1.75, 38.5, -0.7], -0.12);
	flesh($F->{north}, along => 'v', freq => 1.3);
	vgroove($F->{north}, 2, 0, 5);
	maw($F->{south});
	flesh($F->{$_}, base => 'skinD', veins => 0) for qw(east west down);
	solid($F->{up}, 'redD', 6);
	bone("petal${k}_tip", "petal$k", [0, 38.5, -1.2], [14, 0, 0]);
	$F = cube("petal${k}_tip", "petal${k}_leaf", [-1.5, 38.5, -1.7], [1.5, 41.5, -0.7], -0.14);
	flesh($F->{north}, along => 'v', freq => 1.3);
	vgroove($F->{north}, 2, 0, 4);
	maw($F->{south}, edge => 1);
	flesh($F->{$_}, base => 'skinD', veins => 0) for qw(east west);
	solid($F->{up}, 'redL', 8);
	# Teeth on the inside, seen when it opens.
	for my $t (0 .. 1) {
		my $x = $t ? 0.55 : -0.55;
		my $y = 38.9 + $t * 1.1;
		$F = cube("petal${k}_tip", "petal${k}_tooth$t", [$x - 0.5, $y, -0.9], [$x + 0.5, $y + 1, 0.1], -0.33);
		solid($F->{$_}, 'tooth', 6) for qw(north south east west up down);
	}
}

# ---------------------------------------------------------------- arms: very long, thin, clawed
sub arm {
	my ($side, $sx) = @_;    # $sx: +1 his right (east), -1 his left
	my $p = $side;
	bone("${p}_shoulder", "chest", [3.6 * $sx, 29.5, 0.6]);
	$F = cube("${p}_shoulder", "${p}_deltoid", [($sx > 0 ? 3 : -5.2), 27.6, -0.6], [($sx > 0 ? 5.2 : -3), 30.6, 1.9], -0.1);
	fleshy($F, freq => 1.5);
	bone("${p}_upper_arm", "${p}_shoulder", [4.4 * $sx, 29.5, 0.5]);
	$F = cube("${p}_upper_arm", "${p}_humerus", [4.4 * $sx - 0.9, 20.5, -0.4], [4.4 * $sx + 0.9, 29.5, 1.4]);
	fleshy($F, freq => 2.0, veins => 2);
	$F = cube("${p}_upper_arm", "${p}_biceps", [4.4 * $sx - 0.9, 23, -0.9], [4.4 * $sx + 0.9, 28, 0.6], -0.15);
	fleshy($F, freq => 1.6);
	bone("${p}_forearm", "${p}_upper_arm", [4.4 * $sx, 20.5, 0.5]);
	$F = cube("${p}_forearm", "${p}_radius", [4.4 * $sx - 0.8, 12, -0.3], [4.4 * $sx + 0.8, 20.5, 1.3]);
	fleshy($F, freq => 2.2, veins => 2);
	$F = cube("${p}_forearm", "${p}_elbow", [4.4 * $sx - 0.7, 19.6, 0.8], [4.4 * $sx + 0.7, 21.4, 2.1], -0.05);
	fleshy($F, base => 'skinL');
	$F = cube("${p}_forearm", "${p}_forearm_muscle", [4.4 * $sx - 1, 15.5, -0.5], [4.4 * $sx + 1, 20, 1], -0.2);
	fleshy($F, freq => 1.8);
	# The hand: palm to the back (it goes flat on the ground on all fours), fingers spread across.
	bone("${p}_hand", "${p}_forearm", [4.4 * $sx, 12, 0.5]);
	$F = cube("${p}_hand", "${p}_palm", [4.4 * $sx - 1.1, 9.5, -0.1], [4.4 * $sx + 1.1, 12, 1.1]);
	fleshy($F, freq => 2.6);
	solid($F->{south}, 'skinD', 6);
	for my $k (0 .. 2) {
		my $x = 4.4 * $sx + ($k - 1) * 0.75 * $sx;
		bone("${p}_finger$k", "${p}_hand", [$x, 9.5, 0.5]);
		my $len = $k == 1 ? 3.5 : 3;
		$F = cube("${p}_finger$k", "${p}_finger${k}_bone", [$x - 0.5, 9.5 - $len, 0], [$x + 0.5, 9.5, 1], -0.2);
		fleshy($F, freq => 3, veins => 0);
		bone("${p}_finger${k}_tip", "${p}_finger$k", [$x, 9.5 - $len, 0.5]);
		$F = cube("${p}_finger${k}_tip", "${p}_finger${k}_tip", [$x - 0.5, 9.5 - $len - 2.5, 0], [$x + 0.5, 9.5 - $len, 1], -0.24);
		fleshy($F, freq => 3, veins => 0, base => 'skinD');
		$F = cube("${p}_finger${k}_tip", "${p}_claw$k", [$x - 0.5, 9.5 - $len - 4.2, -0.2], [$x + 0.5, 9.5 - $len - 2.3, 0.8], -0.28);
		clawpaint($F->{$_}) for qw(north south east west);
		solid($F->{down}, 'clawL', 4);
		solid($F->{up}, 'claw', 4);
	}
	bone("${p}_thumb", "${p}_hand", [4.4 * $sx - 1.0 * $sx, 11, 0.2], [0, 0, -18 * $sx]);
	$F = cube("${p}_thumb", "${p}_thumb", [4.4 * $sx - 1.5 * $sx - 0.5, 7.8, -0.3], [4.4 * $sx - 1.5 * $sx + 0.5, 11, 0.7], -0.22);
	fleshy($F, freq => 3, veins => 0);
	$F = cube("${p}_thumb", "${p}_thumb_claw", [4.4 * $sx - 1.5 * $sx - 0.5, 6.4, -0.5], [4.4 * $sx - 1.5 * $sx + 0.5, 8, 0.5], -0.3);
	clawpaint($F->{$_}) for qw(north south east west);
}
arm("right", 1);
arm("left", -1);

# ---------------------------------------------------------------- legs: long, sinewy, clawed toes
sub leg {
	my ($side, $sx) = @_;
	my $p = $side;
	my $x = 1.6 * $sx;
	bone("${p}_thigh", "hips", [$x, 18, 0.5]);
	$F = cube("${p}_thigh", "${p}_femur", [$x - 1, 10, -0.5], [$x + 1, 18, 1.5]);
	fleshy($F, freq => 1.8, veins => 2);
	$F = cube("${p}_thigh", "${p}_quad", [$x - 1, 11.5, -1], [$x + 1, 17, 0.5], -0.15);
	fleshy($F, freq => 1.4);
	bone("${p}_shin", "${p}_thigh", [$x, 10, 0.5]);
	$F = cube("${p}_shin", "${p}_tibia", [$x - 0.8, 2, -0.2], [$x + 0.8, 10, 1.3]);
	fleshy($F, freq => 2.2, veins => 2);
	$F = cube("${p}_shin", "${p}_knee", [$x - 0.8, 9, -0.8], [$x + 0.8, 11, 0.4], -0.05);
	fleshy($F, base => 'skinL');
	$F = cube("${p}_shin", "${p}_calf", [$x - 0.9, 5, 0.6], [$x + 0.9, 9.5, 1.9], -0.15);
	fleshy($F, freq => 1.6);
	bone("${p}_foot", "${p}_shin", [$x, 2, 0.5]);
	$F = cube("${p}_foot", "${p}_heel", [$x - 0.8, 0, -1], [$x + 0.8, 2.2, 1.6]);
	fleshy($F, along => 'h', freq => 2.4);
	for my $k (0 .. 2) {
		my $tx = $x + ($k - 1) * 0.65;
		bone("${p}_toe$k", "${p}_foot", [$tx, 0.6, -0.9]);
		my $len = $k == 1 ? 3.2 : 2.6;
		$F = cube("${p}_toe$k", "${p}_toe${k}_bone", [$tx - 0.5, 0, -0.9 - $len], [$tx + 0.5, 1.2, -0.9], -0.2);
		flesh($F->{$_}, along => 'h', freq => 3, veins => 0) for qw(east west);
		flesh($F->{$_}, along => 'v', freq => 3, veins => 0) for qw(up down);
		solid($F->{north}, 'skinD', 6);
		solid($F->{south}, 'skinD', 6);
		$F = cube("${p}_toe$k", "${p}_toe${k}_claw", [$tx - 0.5, -0.1, -0.9 - $len - 1.6], [$tx + 0.5, 0.9, -0.9 - $len + 0.1], -0.28);
		clawpaint($F->{$_}) for qw(east west up down);
		solid($F->{north}, 'clawL', 4);
		solid($F->{south}, 'claw', 4);
	}
}
leg("right", 1);
leg("left", -1);

# ---------------------------------------------------------------- posing: forward kinematics in the side view
# A pose: bone => [x, y, z] degrees (Blockbench's, added to the rest rotation), and "hips_pos" => [x, y, z].
# Positive x swings a hanging bone forwards (towards -z), tips an upright one back.
sub rotYZ {
	my ($y, $z, $deg) = @_;
	my $a = $deg * $PI / 180;
	return ($y * cos($a) - $z * sin($a), $y * sin($a) + $z * cos($a));
}

# World (y, z) of every bone's pivot and its world angle about x, for a pose.
sub fk {
	my ($pose) = @_;
	my (%W, %A);
	my @order = sort { depth($a) <=> depth($b) } keys %pivot;
	for my $b (@order) {
		my $rest = $bones{$b}{rotation}[0];
		my $rx = $rest + (($pose->{$b} // [0])->[0] // 0);
		my $par = $parentOf{$b};
		if (!defined $par) {
			my $off = $pose->{hips_pos} // [0, 0, 0];
			$W{$b} = [$pivot{$b}[1] + $off->[1], $pivot{$b}[2] + $off->[2]];
			$A{$b} = $rx;
		} else {
			my ($dy, $dz) = ($pivot{$b}[1] - $pivot{$par}[1], $pivot{$b}[2] - $pivot{$par}[2]);
			my ($ry, $rz) = rotYZ($dy, $dz, $A{$par});
			$W{$b} = [$W{$par}[0] + $ry, $W{$par}[1] + $rz];
			$A{$b} = $A{$par} + $rx;
		}
	}
	return (\%W, \%A);
}

my %depthC;
sub depth {
	my $b = shift;
	return $depthC{$b} //= defined $parentOf{$b} ? 1 + depth($parentOf{$b}) : 0;
}

sub atan2d { return atan2($_[0], $_[1]) * 180 / $PI; }

# Two bones hanging from $base (rest pointing straight down): the turns (degrees, local) that put the end of the
# second on $target. $bend: 'fwd' puts the middle joint forwards (knees), 'back' behind (elbows on all fours).
sub ik2 {
	my ($S0, $parentAng, $L1, $L2, $T, $bend) = @_;
	my ($dy, $dz) = ($T->[0] - $S0->[0], $T->[1] - $S0->[1]);
	my $d = sqrt($dy * $dy + $dz * $dz);
	my $max = $L1 + $L2 - 0.02;
	my $min = abs($L1 - $L2) + 0.02;
	$d = $max if $d > $max;
	$d = $min if $d < $min;
	my $base = atan2d(-$dz, -$dy);
	my $c = ($L1 * $L1 + $d * $d - $L2 * $L2) / (2 * $L1 * $d);
	$c = 1 if $c > 1;
	$c = -1 if $c < -1;
	my $a = acos($c) * 180 / $PI;
	my @best;
	for my $sg (1, -1) {
		my $a1 = $base + $sg * $a;
		my $E = [$S0->[0] - $L1 * cos($a1 * $PI / 180), $S0->[1] - $L1 * sin($a1 * $PI / 180)];
		my $score = $bend eq 'fwd' ? -$E->[1] : $E->[1];
		@best = ($score, $a1, $E) if !@best || $score > $best[0];
	}
	my ($a1, $E) = @best[1, 2];
	# The end may fall short (out of reach): aim the second bone at the target anyway.
	my $a2 = atan2d(-($T->[1] - $E->[1]), -($T->[0] - $E->[0]));
	return ($a1 - $parentAng, $a2 - $a1, $a2);
}

sub acos { my $x = shift; return atan2(sqrt(1 - $x * $x), $x); }

# Places the limbs of a pose: the arms' wrists and the legs' ankles on targets (side view), the hands and feet at
# world angles; returns the pose with them.
sub place {
	my ($pose, %t) = @_;
	for my $side (qw(right left)) {
		if (my $T0 = $t{"${side}_hand"}) {
			my ($W, $A) = fk($pose);
			# Splayed out sideways the arm reaches less far down: aimed lower in the side view to make up for it.
			my $S0 = $W->{"${side}_upper_arm"};
			my $T = [$S0->[0] - ($S0->[0] - $T0->[0]) / cos(($t{abd_arm} // 0) * $PI / 180), $T0->[1]];
			my ($l1, $l2, $w2) = ik2($W->{"${side}_upper_arm"}, $A->{"${side}_shoulder"}, 9, 8.5, $T, $t{arm_bend} // 'back');
			$pose->{"${side}_upper_arm"}[0] = $l1;
			$pose->{"${side}_forearm"}[0] = $l2;
			$pose->{"${side}_hand"}[0] = ($t{"${side}_hand_ang"} // 20) - $w2;
		}
		if (my $T0 = $t{"${side}_foot"}) {
			my ($W, $A) = fk($pose);
			my $S0 = $W->{"${side}_thigh"};
			my $T = [$S0->[0] - ($S0->[0] - $T0->[0]) / cos(($t{abd_leg} // 0) * $PI / 180), $T0->[1]];
			my ($l1, $l2, $w2) = ik2($W->{"${side}_thigh"}, $A->{hips}, 8, 8, $T, 'fwd');
			$pose->{"${side}_thigh"}[0] = $l1;
			$pose->{"${side}_shin"}[0] = $l2;
			$pose->{"${side}_foot"}[0] = ($t{"${side}_foot_ang"} // 0) - $w2;
		}
	}
	return $pose;
}

sub setr {
	my ($pose, $b, $x, $y, $z) = @_;
	my $r = $pose->{$b} //= [0, 0, 0];
	$r->[0] += $x // 0;
	$r->[1] += $y // 0;
	$r->[2] += $z // 0;
}

# Fingers: $curl degrees bent in at each joint, $spread fanned apart.
sub fingers {
	my ($pose, $side, $curl, $spread, $tip) = @_;
	my $sx = $side eq 'right' ? 1 : -1;
	for my $k (0 .. 2) {
		setr($pose, "${side}_finger$k", $curl, 0, ($k - 1) * $spread * $sx);
		setr($pose, "${side}_finger${k}_tip", $tip // $curl * 0.8);
	}
	setr($pose, "${side}_thumb", $curl * 0.6, 0, $spread * 0.5 * $sx);
}

sub smooth { my $t = shift; return $t * $t * (3 - 2 * $t); }

sub max { my ($a, $b) = @_; return $a > $b ? $a : $b; }

# ---------------------------------------------------------------- the stances
# Standing: hunched a little, the long arms hanging slightly forward.
sub stand {
	my %o = @_;
	my $p = {hips_pos => [0, $o{drop} // -0.4, 0]};
	setr($p, "hips", -4);
	setr($p, "waist", -3);
	setr($p, "chest", -6);
	setr($p, "neck", 8);
	setr($p, "head", 3);
	for my $s (qw(right left)) {
		my $sx = $s eq 'right' ? 1 : -1;
		setr($p, "${s}_shoulder", 0, 0, 3 * $sx);
		setr($p, "${s}_upper_arm", 9, 0, 2 * $sx);
		setr($p, "${s}_forearm", 10);
		setr($p, "${s}_hand", 6);
		fingers($p, $s, 8, 6);
	}
	return $p;
}

# On all fours: the back near level, hips high, the neck up so the head (the bud) points forwards.
my ($ABD_ARM, $ABD_LEG) = (20, 15);

sub fours {
	my %o = @_;
	my $p = {hips_pos => [0, $o{drop} // -4.6, $o{back} // 5.4]};
	setr($p, "hips", $o{hips} // -66);
	setr($p, "waist", $o{waist} // -9);
	setr($p, "chest", $o{chest} // -8);
	setr($p, "neck", $o{neck} // 46);
	setr($p, "head", $o{head} // -34);
	for my $s (qw(right left)) {
		my $sx = $s eq 'right' ? 1 : -1;
		# Arms and legs splayed out wide: turned about the forward axis (on all fours his parents' y axes point
		# forwards), outwards.
		setr($p, "${s}_upper_arm", 0, -($o{abd_arm} // $ABD_ARM) * $sx, 0);
		setr($p, "${s}_thigh", 0, -($o{abd_leg} // $ABD_LEG) * $sx, 0);
	}
	return $p;
}

# Where, under him, hands and feet go on all fours (side view, from the pose's own shoulders and hips).
sub ground {
	my ($p) = @_;
	my ($W) = fk($p);
	return ($W->{right_upper_arm}[1], $W->{right_thigh}[1]);
}

# ---------------------------------------------------------------- the animations
my @anims;

sub anim {
	my ($name, $length, $loop, $frames, $interp) = @_;
	# $frames: [time, pose] in order; every bone any frame moves gets keys in all of them.
	my %used;
	for my $f (@$frames) {
		for my $b (keys %{$f->[1]}) {
			$used{$b} = 1;
		}
	}
	my $a = {uuid => uuid(), name => $name, loop => $loop, override => JSON::PP::false, length => $length, snapping => 60,
		selected => JSON::PP::false, anim_time_update => "", blend_weight => "", start_delay => "", loop_delay => "", animators => {}};
	for my $b (sort keys %used) {
		my $bone = $b eq 'hips_pos' ? 'hips' : $b;
		my $channel = $b eq 'hips_pos' ? 'position' : $b =~ /_scale$/ ? 'scale' : 'rotation';
		$bone =~ s/_scale$//;
		my $an = $a->{animators}{$bones{$bone}{uuid}} //= {name => $bone, type => "bone", keyframes => []};
		for my $f (@$frames) {
			my $v = $f->[1]{$b} // ($channel eq 'scale' ? [1, 1, 1] : [0, 0, 0]);
			push @{$an->{keyframes}}, {channel => $channel,
				data_points => [{x => sprintf("%.3f", $v->[0] // 0), y => sprintf("%.3f", $v->[1] // 0), z => sprintf("%.3f", $v->[2] // 0)}],
				uuid => uuid(), time => 0 + sprintf("%.4f", $f->[0]), color => -1, interpolation => $interp // "catmullrom"};
		}
	}
	push @anims, $a;
}

# Feet where they stand at rest (side view): the ankles.
my @ANKLE = (2, 0.5);

# idle: standing, breathing, the head searching about, the fingers flexing in turn.
{
	my @f;
	my $T = 4.0;
	for my $i (0 .. 16) {
		my $t = $T * $i / 16;
		my $u = $t / $T * 2 * $PI;
		my $p = stand(drop => -0.5 + 0.15 * sin($u * 2));
		setr($p, "chest", 1.4 * sin($u * 2));
		$p->{chest_scale} = [1 + 0.025 * (1 + sin($u * 2)) / 2, 1, 1 + 0.03 * (1 + sin($u * 2)) / 2];
		setr($p, "neck", 2 * sin($u), 9 * sin($u + 0.6), 0);
		setr($p, "head", 3 * sin($u * 2 + 1), 12 * sin($u + 1.2), 7 * sin($u * 3 + 0.4));
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			my $ph = $sx > 0 ? 0 : $PI;
			setr($p, "${s}_shoulder", 0, 0, 1.5 * sin($u * 2) * $sx);
			setr($p, "${s}_upper_arm", 3 * sin($u + $ph));
			setr($p, "${s}_forearm", 4 * sin($u + $ph + 0.7));
			for my $k (0 .. 2) {
				setr($p, "${s}_finger$k", 10 * (1 + sin($u * 2 + $k * 1.3 + $ph)) / 2);
				setr($p, "${s}_finger${k}_tip", 14 * (1 + sin($u * 2 + $k * 1.3 + $ph + 0.8)) / 2);
			}
		}
		place($p, right_foot => [@ANKLE], left_foot => [@ANKLE]);
		push @f, [$t, $p];
	}
	anim("idle", $T, "loop", \@f);
}

# walk: a long, stalking, upright gait; stance 60%, feet planted.
{
	my @f;
	my $T = 1.2;
	my $N = 16;
	my $stride = 3.6;
	for my $i (0 .. $N) {
		my $t = $T * $i / $N;
		my $u = $i / $N;
		my $p = stand(drop => -1.6 + 0.45 * cos($u * 4 * $PI));
		my %tg;
		for my $s (qw(right left)) {
			my $ph = $s eq 'right' ? 0 : 0.5;
			my $lu = $u + $ph - int($u + $ph);
			my ($z, $lift, $ang);
			if ($lu < 0.6) {
				$z = -$stride + 2 * $stride * ($lu / 0.6);
				$lift = 0;
				$ang = $lu > 0.45 ? -18 * ($lu - 0.45) / 0.15 : 0;
			} else {
				my $k = ($lu - 0.6) / 0.4;
				$z = $stride - 2 * $stride * smooth($k);
				$lift = 2.6 * sin($PI * $k);
				$ang = -18 * (1 - $k) + 6 * sin($PI * $k);
			}
			$tg{"${s}_foot"} = [$ANKLE[0] + $lift, $ANKLE[1] + $z];
			$tg{"${s}_foot_ang"} = $ang;
			for my $k (0 .. 2) { setr($p, "${s}_toe$k", $lu >= 0.6 ? -14 * sin($PI * ($lu - 0.6) / 0.4) : 0); }
			# The arm swings against the leg, loose, the forearm trailing.
			my $sw = cos(2 * $PI * $lu);
			setr($p, "${s}_upper_arm", -13 * $sw);
			setr($p, "${s}_forearm", 9 - 9 * cos(2 * $PI * $lu - 0.9));
			setr($p, "${s}_hand", -8 * cos(2 * $PI * $lu - 1.6));
			for my $k (0 .. 2) { setr($p, "${s}_finger${k}_tip", 10 * (1 - cos(2 * $PI * $lu - 2.0))); }
		}
		# Twisting at the waist, the head steady and swaying, a stoop forwards.
		setr($p, "hips", -3, 7 * cos(2 * $PI * $u), 2.5 * sin(2 * $PI * $u));
		setr($p, "waist", 1.5 * cos(4 * $PI * $u), -4 * cos(2 * $PI * $u));
		setr($p, "chest", -3, -6 * cos(2 * $PI * $u), -2 * sin(2 * $PI * $u));
		setr($p, "neck", 3, 3 * cos(2 * $PI * $u), 0);
		setr($p, "head", -2 * cos(4 * $PI * $u), 0, 4 * sin(2 * $PI * $u + 0.5));
		place($p, %tg);
		push @f, [$t, $p];
	}
	anim("walk", $T, "loop", \@f);
}

# On all fours: hands under the shoulders, feet under the hips (from the pose's own geometry).
sub on_fours {
	my ($p, %o) = @_;
	my ($zs, $zh) = ground($p);
	my %tg;
	for my $s (qw(right left)) {
		my $dzH = $o{"${s}_hand_dz"} // 0;
		my $dzF = $o{"${s}_foot_dz"} // 0;
		$tg{"${s}_hand"} = [2.9 + ($o{"${s}_hand_lift"} // 0), $zs - 1.8 + ($o{hand_reach} // 0) + $dzH];
		$tg{"${s}_hand_ang"} = $o{"${s}_hand_ang"} // 28;
		$tg{"${s}_foot"} = [$ANKLE[0] + ($o{"${s}_foot_lift"} // 0), $zh + 1.2 + $dzF];
		$tg{"${s}_foot_ang"} = $o{"${s}_foot_ang"} // 0;
	}
	place($p, %tg, abd_arm => $ABD_ARM, abd_leg => $ABD_LEG);
	# Fingers flat on the ground ahead of the hand (or curled, lifted).
	my ($W, $A) = fk($p);
	for my $s (qw(right left)) {
		my $flat = 90 - $A->{"${s}_hand"} + ($o{"${s}_finger"} // 0);
		for my $k (0 .. 2) {
			setr($p, "${s}_finger$k", $flat);
			setr($p, "${s}_finger${k}_tip", $o{"${s}_tip"} // -12);
		}
		setr($p, "${s}_thumb", $flat * 0.6);
	}
	return $p;
}

# fours_idle: low, breathing, the head scanning from side to side, a finger tapping.
{
	my @f;
	my $T = 3.0;
	for my $i (0 .. 12) {
		my $t = $T * $i / 12;
		my $u = $t / $T * 2 * $PI;
		my $p = fours(drop => -4.6 + 0.25 * sin($u * 2));
		setr($p, "chest", 1.2 * sin($u * 2));
		$p->{chest_scale} = [1 + 0.03 * (1 + sin($u * 2)) / 2, 1, 1 + 0.035 * (1 + sin($u * 2)) / 2];
		setr($p, "neck", 4 * sin($u + 0.3), 0, -10 * sin($u));
		setr($p, "head", 3 * sin($u * 2), 0, -12 * sin($u - 0.5));
		setr($p, "hips", 0, 0, 2 * sin($u));
		my $tap = $t > 1.6 && $t < 2.4 ? sin(($t - 1.6) / 0.8 * $PI) : 0;
		on_fours($p, right_finger => -25 * $tap, right_tip => -12 - 20 * $tap);
		push @f, [$t, $p];
	}
	anim("fours_idle", $T, "loop", \@f);
}

# crawl: diagonal pairs (front right with hind left), stance 62%: a sure, swaggering prowl, long strides, the
# body rolling and swaying from side to side over the planted limbs, the shoulders rolling, the head held steady.
{
	my @f;
	my $T = 1.0;
	my $N = 20;
	my ($sH, $sF) = (4.8, 4.4);
	for my $i (0 .. $N) {
		my $t = $T * $i / $N;
		my $u = $i / $N;
		my $p = fours(drop => -4.9 + 0.5 * cos($u * 4 * $PI));
		$p->{hips_pos}[0] = 0.9 * sin(2 * $PI * ($u + 0.12));
		my %o;
		for my $limb (['right_hand', 0], ['left_foot', 0.08], ['left_hand', 0.5], ['right_foot', 0.58]) {
			my ($name, $ph) = @$limb;
			my $lu = $u + $ph - int($u + $ph);
			my $st = $name =~ /hand/ ? $sH : $sF;
			my ($z, $lift, $k) = (0, 0, -1);
			if ($lu < 0.62) {
				$z = -$st + 2 * $st * ($lu / 0.62);
			} else {
				$k = ($lu - 0.62) / 0.38;
				$z = $st - 2 * $st * smooth($k);
				$lift = ($name =~ /hand/ ? 3.6 : 2.8) * sin($PI * $k);
			}
			my ($side, $what) = split /_/, $name;
			$o{"${side}_${what}_dz"} = $z;
			$o{"${side}_${what}_lift"} = $lift;
			if ($what eq 'hand') {
				# Lifted, the hand curls; set down, it spreads flat again.
				$o{"${side}_hand_ang"} = $k < 0 ? 22 + 16 * ($lu / 0.62) : 38 - 30 * sin($PI * $k);
				$o{"${side}_finger"} = $k < 0 ? 0 : -40 * sin($PI * $k);
				$o{"${side}_tip"} = $k < 0 ? -12 : -12 - 30 * sin($PI * $k);
			} else {
				$o{"${side}_foot_ang"} = $k < 0 ? ($lu > 0.5 ? -20 * ($lu - 0.5) / 0.12 : 0) : -20 * (1 - $k) + 10 * sin($PI * $k);
			}
		}
		# Hips: turning (y) and rolling (z) with the hind legs; the chest the other way with the front ones (its
		# parent bent forwards: y rolls it, z turns it).
		setr($p, "hips", 0, 9 * cos(2 * $PI * $u), 7 * sin(2 * $PI * $u));
		setr($p, "waist", 2.5 * sin(4 * $PI * $u), -5 * cos(2 * $PI * $u), 0);
		setr($p, "chest", -2 * sin(4 * $PI * $u), 8 * sin(2 * $PI * $u + 0.4), -7 * cos(2 * $PI * $u + 0.4));
		for my $s (qw(right left)) {
			# The shoulder rides up as its arm swings through.
			my $sx = $s eq 'right' ? 1 : -1;
			setr($p, "${s}_shoulder", 0, 0, 5 * $sx * sin(2 * $PI * $u + ($s eq 'right' ? 0 : $PI)));
		}
		# The head held steady against all of it, a slow bob.
		setr($p, "neck", 2 * sin(4 * $PI * $u + 1), -4 * sin(2 * $PI * $u + 0.4), 6 * cos(2 * $PI * $u + 0.4));
		setr($p, "head", 1.5 * sin(4 * $PI * $u + 2), 0, 3 * sin(2 * $PI * $u + 0.5));
		on_fours($p, %o);
		push @f, [$t, $p];
	}
	anim("crawl", $T, "loop", \@f);
}

# charge: coiled, the hind legs bunched under him, the head low and forward, shaking.
{
	my @f;
	my $T = 0.4;
	my @shake = (0, 1, -0.6, 0.8, -1, 0.4, -0.2, 0.9, 0);
	for my $i (0 .. 8) {
		my $t = $T * $i / 8;
		my $q = $shake[$i];
		my $p = fours(drop => -6.6 + 0.3 * $q, back => 6.6, hips => -58, waist => -12, chest => -14, neck => 58, head => -42);
		setr($p, "chest", 1.5 * $q, 2 * $q, 0);
		setr($p, "neck", -1.5 * $q, 0, 3 * $q);
		setr($p, "head", 2 * $q, 0, -4 * $q);
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			setr($p, "${s}_shoulder", 0, 0, (3 + 1.5 * $q) * $sx);
		}
		on_fours($p, right_hand_dz => -1.2, left_hand_dz => -0.6, right_foot_dz => -2.2, left_foot_dz => -1.6,
			right_finger => 18, left_finger => 18, right_tip => -45, left_tip => -45, right_hand_ang => 42, left_hand_ang => 42);
		push @f, [$t, $p];
	}
	anim("charge", $T, "loop", \@f);
}

# lunge: flung forwards, stretched out, arms reaching with the claws spread, the hind legs kicking out behind.
{
	my @f;
	for my $kf ([0, 0], [0.12, 0.75], [0.25, 1], [0.4, 1]) {
		my ($t, $k) = @$kf;
		my $p = fours(drop => -6.6 - 0.4 * $k, back => 6.6 + 0.5 * $k, hips => -58 - 26 * $k, waist => -12 + 8 * $k, chest => -14 + 8 * $k,
			neck => 58 - 10 * $k, head => -42 + 2 * $k);
		on_fours($p, right_hand_dz => -1.2, left_hand_dz => -0.6, right_foot_dz => -2.2, left_foot_dz => -1.6,
			right_finger => 18, left_finger => 18, right_tip => -45, left_tip => -45, right_hand_ang => 42, left_hand_ang => 42);
		# From the coiled stance to outstretched: each limb turned towards a direction in the world (side view;
		# hanging: 0 down, 90 straight forwards, -90 straight back).
		my ($W, $A) = fk($p);
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			my $r = $s eq 'right' ? 1 : 0;
			my $toward = sub {
				my ($bone, $parentWorld, $world) = @_;
				my $local = $world - $parentWorld;
				$p->{$bone}[0] += ($local - $p->{$bone}[0]) * $k;
			};
			$toward->("${s}_upper_arm", $A->{"${s}_shoulder"}, 55 + 15 * $r);
			$toward->("${s}_forearm", 55 + 15 * $r, 75 + 10 * $r);
			$toward->("${s}_hand", 75 + 10 * $r, 88 + 6 * $r);
			$p->{"${s}_upper_arm"}[2] += (16 * $sx - $p->{"${s}_upper_arm"}[2]) * $k;
			for my $f (0 .. 2) {
				$p->{"${s}_finger$f"}[0] += ((-10) - $p->{"${s}_finger$f"}[0]) * $k;
				$p->{"${s}_finger$f"}[2] += ((($f - 1) * 20 * $sx) - $p->{"${s}_finger$f"}[2]) * $k;
				$p->{"${s}_finger${f}_tip"}[0] += ((18) - $p->{"${s}_finger${f}_tip"}[0]) * $k;
			}
			$toward->("${s}_thigh", $A->{hips}, -40 + 12 * $r);
			$toward->("${s}_shin", -40 + 12 * $r, -70 + 10 * $r);
			$toward->("${s}_foot", -70 + 10 * $r, -100);
		}
		push @f, [$t, $p];
	}
	anim("lunge", 0.4, "hold_on_last_frame", \@f);
}

# devour: over someone thrown down on their back, his body thrust forwards over them, the claws pinning their
# shoulders, the head plunging into them twice a second and tearing side to side (the mouth: DemogorgonModel).
{
	my @f;
	my $T = 1.0;
	my $N = 20;
	for my $i (0 .. $N) {
		my $t = $T * $i / $N;
		my $u = $i / $N;
		# Two plunges: down fast, a worrying shake, back up slower.
		my $plunge = 0;
		for my $at (0.05, 0.55) {
			my $d = $u - $at;
			$d += 1 if $d < 0;
			$plunge = max($plunge, $d < 0.1 ? $d / 0.1 : $d < 0.3 ? 1 : $d < 0.45 ? 1 - ($d - 0.3) / 0.15 : 0);
		}
		my $shake = $plunge > 0.9 ? sin($u * 2 * $PI * 7) : 0;
		my $p = fours(drop => -6.2 + 0.3 * sin(2 * $PI * $u), back => -9, hips => -74, waist => -8, chest => -12 + 5 * $plunge,
			neck => 20 - 26 * $plunge, head => -40 - 18 * $plunge);
		setr($p, "head", 0, 14 * $shake, 10 * $shake);
		setr($p, "neck", 0, -6 * $shake, 0);
		setr($p, "hips", 0, 3 * sin(2 * $PI * $u), 0);
		on_fours($p, hand_reach => -3.5, right_hand_dz => -0.5 * $plunge, left_hand_dz => 0.4 * $plunge, right_foot_dz => 1.5, left_foot_dz => 0.5,
			right_finger => 30, left_finger => 30, right_tip => -50, left_tip => -50, right_hand_ang => 55, left_hand_ang => 55);
		push @f, [$t, $p];
	}
	anim("devour", $T, "loop", \@f);
}

# bite (laid over devour, each blow of the kill): the head driven down and wrenched back up.
{
	my @f;
	for my $kf ([0, 0, 0], [0.08, 1, 0], [0.18, 1, 1], [0.3, 0.6, -1], [0.4, 0, 0]) {
		my ($t, $k, $sh) = @$kf;
		my $p = {};
		setr($p, "neck", -18 * $k, 8 * $sh, 0);
		setr($p, "head", -22 * $k, 12 * $sh, 14 * $sh);
		setr($p, "chest", 8 * $k);
		push @f, [$t, $p];
	}
	anim("bite", 0.4, "once", \@f);
}

# strike (laid over the standing pose): both claws up over him, the chest drawn back; then raking down hard,
# leaning in, the head thrown forwards.
{
	my @f;
	for my $kf ([0, 0, 0], [0.15, 1, 0], [0.28, 1, 1], [0.38, 0.2, 1.1], [0.5, 0, 0]) {
		my ($t, $up, $down) = @$kf;
		my $p = {};
		my $arm = $up * (1 - $down) * 165 + $down * 40 * ($t < 0.45 ? 1 : 0);
		setr($p, "chest", 10 * $up * (1 - $down) - 14 * $down);
		setr($p, "waist", 4 * $up * (1 - $down) - 6 * $down);
		setr($p, "neck", -18 * $down);
		setr($p, "head", -38 * $down + 10 * $up * (1 - $down));
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			setr($p, "${s}_shoulder", 0, 0, 6 * $up * $sx);
			setr($p, "${s}_upper_arm", $arm, 0, 14 * $up * (1 - $down) * $sx);
			setr($p, "${s}_forearm", 30 * $up * (1 - $down) + 10 * $down);
			for my $k (0 .. 2) {
				setr($p, "${s}_finger$k", -16 * $up * (1 - $down) + 26 * $down, 0, ($k - 1) * 18 * $up * $sx);
				setr($p, "${s}_finger${k}_tip", 20 * $down);
			}
		}
		push @f, [$t, $p];
	}
	anim("strike", 0.5, "once", \@f);
}

# strike_fours (laid over the stance on all fours): rearing back on the hind legs, a sweeping right claw.
{
	my @f;
	for my $kf ([0, 0, 0], [0.15, 1, 0], [0.3, 1, 1], [0.5, 0, 0]) {
		my ($t, $up, $down) = @$kf;
		my $p = {hips_pos => [0, 2.5 * $up, 0]};
		setr($p, "hips", 28 * $up);
		setr($p, "neck", -14 * $up);
		setr($p, "head", -10 * $down);
		setr($p, "right_upper_arm", 70 * $up * (1 - $down) - 40 * $down, 0, 25 * $up * (1 - $down));
		setr($p, "right_forearm", 25 * $up);
		setr($p, "left_upper_arm", 25 * $up, 0, -10 * $up);
		setr($p, "left_forearm", -10 * $up);
		for my $k (0 .. 2) {
			setr($p, "right_finger$k", -50 * $up * (1 - $down) + 30 * $down, 0, ($k - 1) * 18 * $up);
		}
		setr($p, "right_thigh", -24 * $up);
		setr($p, "left_thigh", -24 * $up);
		setr($p, "right_shin", 10 * $up);
		setr($p, "left_shin", 10 * $up);
		push @f, [$t, $p];
	}
	anim("strike_fours", 0.5, "once", \@f);
}

# mouth: the petals fold back from closed to a wide flower, the tips curling back, teeth bared.
{
	my @f;
	for my $kf ([0, 0], [0.35, 0.3], [0.7, 0.8], [1.0, 1]) {
		my ($t, $k) = @$kf;
		my $p = {};
		for my $n (0 .. 4) {
			setr($p, "petal$n", -118 * $k + 6 * sin($n * 2.1) * $k);
			setr($p, "petal${n}_tip", -36 * $k);
		}
		setr($p, "head", 0, 0, 0);
		push @f, [$t, $p];
	}
	anim("mouth", 1.0, "hold_on_last_frame", \@f);
}

# twitch: a spasm, the head jerking from side to side, the neck snapping.
{
	my @f;
	my @k = ([0, 0, 0, 0], [0.08, 22, -8, 6], [0.16, -14, 10, -4], [0.24, 16, -4, 8], [0.36, -8, 12, -6], [0.5, 10, 0, 3], [0.64, -4, -6, 0], [0.8, 0, 0, 0],
		[1.6, 0, 0, 0]);
	for my $kf (@k) {
		my ($t, $z, $y, $x) = @$kf;
		my $p = {};
		setr($p, "head", $x, $y, $z);
		setr($p, "neck", -$x * 0.5, -$y * 0.6, -$z * 0.4);
		for my $n (0 .. 4) { setr($p, "petal$n", -abs($z) * 0.6); }
		push @f, [$t, $p];
	}
	anim("twitch", 1.6, "loop", \@f, "linear");
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
	modded_entity_flip_y => JSON::PP::true, visible_box => [1, 3, 0], variable_placeholders => "", variable_placeholder_buttons => [],
	timeline_setups => [], unhandled_root_fields => {},
	resolution => {width => $UW, height => $UH},
	elements => \@elements,
	outliner => \@outliner,
	textures => [{path => "", name => "demogorgon.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH,
		uv_width => $UW, uv_height => $UH, particle => JSON::PP::false, use_as_default => JSON::PP::false, layers_enabled => JSON::PP::false,
		sync_to_project => "", render_mode => "default", render_sides => "auto", frame_time => 1, frame_order_type => "loop",
		frame_order => "", frame_interpolate => JSON::PP::false, visible => JSON::PP::true, internal => JSON::PP::true, saved => JSON::PP::true,
		uuid => uuid(), relative_path => "", source => "data:image/png;base64," . encode_base64(png(), "")}],
	animations => \@anims,
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;

# A look at the stances' geometry (side view: y up, z back).
my ($W) = fk(on_fours(fours()));
printf "on all fours: hips %.1f/%.1f, shoulders %.1f/%.1f, head %.1f/%.1f\n", @{$W->{hips}}, @{$W->{right_upper_arm}}, @{$W->{head}};
printf "%s: %d cubes, %d bones, %d animations, atlas used to row %d of %d\n", $out, scalar(@elements), scalar(keys %bones), scalar(@anims),
	$shelfY + $shelfH, $UH;
