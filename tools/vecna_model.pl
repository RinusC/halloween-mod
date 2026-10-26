#!/usr/bin/perl
# Vecna (Stranger Things) as a Blockbench model: writes a .bbmodel (box UV) that opens in Blockbench and that
# tools/bb2entity.pl turns into the game's model (client.VecnaModel moves it).
#
# About 2.3 blocks tall (31 units; the game draws him at MichaelRenderer.TALL), gaunt and long-limbed, and he floats:
# his own skeleton (hips, waist, chest, neck, head with a jaw that opens; arms with shoulder, upper arm, forearm, hand,
# four long two-jointed fingers and a thumb; legs with thigh, shin and foot). A long bald skull, deep sockets with
# pale eyes far back in them, a ridge of brow, sharp cheekbones over sunken cheeks, no nose but the hole of it, no lips:
# rows of teeth bare in the gums. A starved body: ribs standing out, the collarbones, the hip bones, the sternum, a
# knotted spine; sinews in the neck. Over all of it the vines of the Upside Down grown into him: raised, knotted
# strands across chest, back, belly, arms and legs (each a chain of little rods), and tendrils standing out of his
# shoulders, back and forearms. The skin burnt dark crimson, cracked, and the vines black with a wet sheen.
#
# The animations (Blockbench's own, sampled by the game):
#   float   hanging in the air, feet pointed down, swaying a little, breathing, the tendrils writhing, the fingers
#           curling and uncurling, the head slowly turning
#   glide   drifting forwards through the air, leaning into it, the legs trailing
#   cast    the Grip: the left arm thrust out, the fingers spread and clawed, trembling with the strain (laid over)
#   strike  the right arm thrust out and a lash shooting from the palm (laid over)
#   jaw     the jaw from shut (0 s) to gaping (1 s)
#   twitch  his head jerking (laid over now and then)
#   throttle holding them by the throat with the left hand, the right claw raised over them
#   rend    the right claw driven into them and torn out (laid over throttle)
#   recoil  reeling back when hit or shaken off
#
#   perl tools/vecna_model.pl art/vecna.bbmodel
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;
use POSIX qw(ceil floor);

my $out = shift or die "usage: vecna_model.pl out.bbmodel [glow.png]\n";
my $glowOut = shift;
my ($UW, $UH) = (200, 100);
my $S = 6;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
# What glows in the dark (drawn over him at full brightness): the eyes, the cracks of raw red.
my @gl = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
sub glow {
	my ($x, $y, $c, $a) = @_;
	return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH;
	$gl[$y * $TW + $x] = [map({ clamp($_) } @$c[0 .. 2]), clamp($a)];
}
srand(1986);
my $PI = 3.14159265358979;

# ---------------------------------------------------------------- colours: burnt crimson flesh, black vines
my %C = (
	skin => [120, 52, 42], skinD => [70, 28, 27], skinDD => [30, 11, 12], skinL => [172, 96, 76], sheen => [204, 140, 116],
	crack => [150, 34, 30], vine => [30, 22, 26], vineD => [12, 7, 9], vineL => [96, 78, 82], vineW => [150, 130, 134],
	socket => [9, 3, 5], eye => [204, 196, 178], eyeD => [118, 108, 98], iris => [88, 96, 104], tooth => [210, 190, 146],
	toothD => [128, 98, 66], gum => [74, 18, 22], gumD => [40, 8, 12], nail => [36, 26, 24], nailL => [118, 98, 86],
	bone => [168, 118, 98],
);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }

sub put {
	my ($x, $y, $c) = @_;
	return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH;
	$px[$y * $TW + $x] = [map({ clamp($_) } @$c[0 .. 2]), 255];
}

sub px_at { my ($x, $y) = @_; return [0, 0, 0] if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH; my $p = $px[$y * $TW + $x]; return [@$p[0 .. 2]]; }
sub mix { my ($a, $b, $t) = @_; return [map { $a->[$_] + ($b->[$_] - $a->[$_]) * $t } 0 .. 2]; }
sub mul { my ($c, $k) = @_; return [map { $_ * $k } @$c]; }
sub jit { my ($c, $n) = @_; my $d = rand(2 * $n) - $n; return [map { $_ + $d } @$c]; }
sub smooth { my $t = shift; $t = 0 if $t < 0; $t = 1 if $t > 1; return $t * $t * (3 - 2 * $t); }

# Value noise (0..1) on a lattice, smoothly interpolated.
my @PERM = (0 .. 255);
for my $i (reverse 1 .. 255) { my $j = int(rand($i + 1)); @PERM[$i, $j] = @PERM[$j, $i]; }
my @RND = map { rand() } 0 .. 255;
sub lat { my ($x, $y) = @_; return $RND[$PERM[($PERM[$x & 255] + $y) & 255]]; }
sub noise {
	my ($x, $y) = @_;
	my ($xi, $yi) = (floor($x), floor($y));
	my ($fx, $fy) = ($x - $xi, $y - $yi);
	my ($u, $v) = (smooth($fx), smooth($fy));
	my $a = lat($xi, $yi) + (lat($xi + 1, $yi) - lat($xi, $yi)) * $u;
	my $b = lat($xi, $yi + 1) + (lat($xi + 1, $yi + 1) - lat($xi, $yi + 1)) * $u;
	return $a + ($b - $a) * $v;
}
sub fbm { my ($x, $y) = @_; return 0.55 * noise($x, $y) + 0.3 * noise($x * 2.1, $y * 2.1) + 0.15 * noise($x * 4.3, $y * 4.3); }

# ---------------------------------------------------------------- texture painting
# Burnt flesh on a face [x, y, w, h] (texels): mottled, with creases running along it ($along 'v' or 'h'), cracks of
# raw red, and flat vines grown over it (the raised ones are cubes of their own).
sub skin {
	my ($r, %o) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	return if $w <= 0 || $h <= 0;
	my $base = $C{$o{base} // 'skin'};
	my $along = $o{along} // 'v';
	my $ox = rand(100);
	my $oy = rand(100);
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my ($a, $b) = $along eq 'v' ? ($i, $j) : ($j, $i);
			my $n = fbm(($i + $ox) / 9, ($j + $oy) / 9);
			my $c = mix($C{skinD}, $base, smooth($n * 1.4 - 0.1));
			$c = mix($c, $C{skinL}, smooth(($n - 0.62) * 4)) if $n > 0.62;
			# Creases: thin dark furrows across the limb, a lit edge under each.
			my $cr = sin($b / 3.1 + sin($a / 7.0 + $ox) * 1.6 + noise($a / 5, $b / 5) * 2);
			$c = mix($c, $C{skinDD}, 0.55) if $cr > 0.86;
			$c = mix($c, $C{sheen}, 0.25) if $cr < -0.93;
			# Its form: darker towards the edges of the face (a rounded limb, not a box), lit from above.
			my $u = $w > 1 ? $i / ($w - 1) : 0.5;
			my $form = 1 - 0.34 * (abs($u - 0.5) * 2)**2.4;
			$form *= 1.07 - 0.2 * ($h > 1 ? $j / ($h - 1) : 0.5) if $o{lit} // 1;
			$c = mul($c, ($o{shade} // 1) * $form);
			put($x0 + $i, $y0 + $j, jit($c, 2.5));
		}
	}
	# Cracks of raw red.
	for (1 .. int($w * $h / 450) + ($o{cracks} // 0)) {
		my ($x, $y) = (rand($w), rand($h));
		my $ang = rand(2 * $PI);
		for (0 .. 6 + int(rand(14))) {
			$ang += (rand(2) - 1) * 0.8;
			$x += cos($ang);
			$y += sin($ang);
			last if $x < 0 || $y < 0 || $x >= $w || $y >= $h;
			put($x0 + int($x), $y0 + int($y), jit(mix(px_at($x0 + int($x), $y0 + int($y)), $C{crack}, 0.75), 6));
			glow($x0 + int($x), $y0 + int($y), [235, 52, 28], 150 + rand(100));
		}
	}
	flatvines($r, $o{vines} // int($w * $h / 260) + 1, $along);
}

# Vines painted on a face: meandering black strands, two or three texels wide, a wet highlight on one side, a shadow on
# the other, forking now and then.
sub flatvines {
	my ($r, $n, $along) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for (1 .. $n) {
		my ($x, $y) = (rand($w), rand($h));
		my $ang = ($along eq 'v' ? $PI / 2 : 0) + (rand(2) - 1) * 0.7 + (rand() < 0.5 ? $PI : 0);
		strand($r, $x, $y, $ang, 10 + int(rand(30)), 1.3 + rand(1.2), 1);
	}
}

sub strand {
	my ($r, $x, $y, $ang, $len, $wd, $depth) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $s (0 .. $len) {
		$ang += (rand(2) - 1) * 0.35;
		$x += cos($ang) * 0.8;
		$y += sin($ang) * 0.8;
		last if $x < -2 || $y < -2 || $x > $w + 2 || $y > $h + 2;
		my $ww = $wd * (1 - 0.5 * $s / ($len + 1));
		my ($nx, $ny) = (-sin($ang), cos($ang));
		for (my $d = -$ww; $d <= $ww; $d += 0.5) {
			my ($px, $py) = (int($x + $nx * $d), int($y + $ny * $d));
			next if $px < 0 || $py < 0 || $px >= $w || $py >= $h;
			my $t = ($d + $ww) / (2 * $ww + 1e-6);
			my $c = $t < 0.25 ? $C{vineL} : $t > 0.8 ? $C{vineD} : $C{vine};
			$c = mix($c, $C{vineW}, 0.5) if $t < 0.12 && rand() < 0.5;
			put($x0 + $px, $y0 + $py, jit($c, 5));
		}
		if ($depth < 3 && rand() < 0.04) {
			strand($r, $x, $y, $ang + (rand() < 0.5 ? 1 : -1) * (0.5 + rand(0.6)), int($len * 0.5), $ww * 0.7, $depth + 1);
		}
	}
}

# A raised vine's own faces: black and glossy along its length.
sub vinepaint {
	my ($F) = @_;
	for my $f (qw(north south east west up down)) {
		my ($x0, $y0, $w, $h) = @{$F->{$f}};
		for my $j (0 .. $h - 1) {
			for my $i (0 .. $w - 1) {
				my $t = $w > 1 ? $i / ($w - 1) : 0.5;
				# Round and wet: lit on one side, a sharp gleam along it, falling into black on the other.
				my $c = $t < 0.45 ? mix($C{vineL}, $C{vine}, smooth($t / 0.45)) : mix($C{vine}, $C{vineD}, smooth(($t - 0.45) / 0.55));
				$c = mix($c, $C{vineW}, 0.55 * (1 - abs($t - 0.22) / 0.12)) if abs($t - 0.22) < 0.12;
				$c = mix($c, $C{vineW}, 0.15) if rand() < 0.05;
				$c = mix($c, $C{crack}, 0.35) if rand() < 0.015;
				$c = mul($c, 0.7) if $f eq 'down';
				put($x0 + $i, $y0 + $j, jit($c, 3));
			}
		}
	}
}

sub solid {
	my ($r, $name, $n) = @_;
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) { put($r->[0] + $i, $r->[1] + $j, jit($C{$name}, $n // 4)); }
	}
}

sub gradient {
	my ($r, $a, $b, $dir) = @_;
	for my $j (0 .. $r->[3] - 1) {
		for my $i (0 .. $r->[2] - 1) {
			my $t = ($dir // 'v') eq 'v' ? ($r->[3] > 1 ? $j / ($r->[3] - 1) : 0.5) : ($r->[2] > 1 ? $i / ($r->[2] - 1) : 0.5);
			put($r->[0] + $i, $r->[1] + $j, jit(mix($C{$a}, $C{$b}, $t), 4));
		}
	}
}

# An eye far back in its socket: milky, a dull grey iris looking out, wet glints; dark round the rim.
sub eyepaint {
	my ($r) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	my ($cx, $cy) = (($w - 1) / 2, ($h - 1) / 2);
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $d = sqrt((($i - $cx) / ($w / 2)) ** 2 + (($j - $cy) / ($h / 2)) ** 2);
			my $c = mix($C{eye}, $C{eyeD}, smooth($d * 1.1));
			$c = mix($c, $C{iris}, 0.35) if $d < 0.45;
			$c = mix($c, $C{socket}, 0.9) if abs($i - $cx) < 0.8 && abs($j - $cy) < 0.8;
			$c = mix($c, $C{crack}, 0.5) if $d > 0.75;
			$c = mix($c, [255, 250, 240], 0.7) if abs($i - $cx + $w * 0.18) < 1 && abs($j - $cy + $h * 0.2) < 1;
			$c = mix($c, $C{gum}, 0.4) if $d > 0.8 && rand() < 0.4;
			put($x0 + $i, $y0 + $j, jit($c, 3));
			glow($x0 + $i, $y0 + $j, mix([255, 250, 236], [210, 60, 40], smooth($d)), 255 * (1 - smooth($d * 0.8))) if $d < 1.1;
		}
	}
}

# Teeth: pale, long, yellowed, dark gaps between them, rooted in red gums.
sub teethpaint {
	my ($r, $gumAt) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $t = $h > 1 ? $j / ($h - 1) : 0.5;
			$t = 1 - $t if ($gumAt // 'up') eq 'down';
			my $gap = ($i % 3) == 0;
			my $c = $t < 0.25 ? $C{gum} : $gap ? $C{gumD} : mix($C{toothD}, $C{tooth}, smooth(($t - 0.2) * 2));
			put($x0 + $i, $y0 + $j, jit($c, 5));
		}
	}
}

sub nailpaint {
	my ($r) = @_;
	gradient($r, 'nail', 'nailL', 'v');
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

my (@elements, @outliner, %bones, %parentOf);
# The body broader than drawn (x and z times this, the head left as it is): starved, but a big man grown thick with vines.
my $WS = 1.3;
my %noscale;
our $RAW = 0;
sub scaled { my ($bname) = @_; return !$RAW && !$noscale{$bname}; }
sub sxz { my ($v) = @_; return [$v->[0] * $WS, $v->[1], $v->[2] * $WS]; }

sub bone {
	my ($name, $parent, $origin, $rotation) = @_;
	die "bone $name twice" if $bones{$name};
	$noscale{$name} = 1 if $name eq "head" || defined $parent && $noscale{$parent};
	$origin = sxz($origin) if !$RAW && !$noscale{$name};
	my $b = {name => $name, origin => $origin, rotation => $rotation // [0, 0, 0], color => 0, uuid => uuid(), export => JSON::PP::true,
		mirror_uv => JSON::PP::false, isOpen => JSON::PP::false, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0,
		children => []};
	$bones{$name} = $b;
	$parentOf{$name} = $parent;
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
	if (scaled($bname)) {
		($from, $to) = (sxz($from), sxz($to));
	}
	my ($w, $h, $d) = map { $to->[$_] - $from->[$_] } 0 .. 2;
	my $uv = alloc(ceil(2 * ($d + $w) - 1e-6), ceil($d + $h - 1e-6));
	my ($u, $v) = @$uv;
	push @elements, {
		name => $name, box_uv => JSON::PP::true, rescale => JSON::PP::false, locked => JSON::PP::false, render_order => "default",
		allow_mirror_modeling => JSON::PP::true, from => [map { 0 + sprintf("%.4f", $_) } @$from], to => [map { 0 + sprintf("%.4f", $_) } @$to],
		autouv => 0, color => 0, inflate => $inflate // 0, origin => $bone->{origin}, uv_offset => $uv, type => "cube", uuid => uuid(),
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

# Flesh on every face of a cube, the ends darker.
sub fleshy {
	my ($F, %o) = @_;
	skin($F->{$_}, %o) for qw(north south east west);
	skin($F->{$_}, %o, shade => ($o{shade} // 1) * 0.78, vines => 0, lit => 0) for qw(up down);
}

# ---------------------------------------------------------------- vines and tendrils (cubes on little bones)
my $vineN = 0;
my $F;

# A raised vine over a face: a chain of short rods, each on a bone of its own turned to run from one point to the
# next. $plane 'z': points [x, y] on a face at depth z = $at (front or back); 'x': points [z, y] on a side at x = $at.
sub vine {
	my ($parent, $plane, $at, $pts, $th) = @_;
	if (!$noscale{$parent}) {
		$at *= $WS;
		$pts = [map { [$_->[0] * $WS, $_->[1]] } @$pts];
	}
	local $RAW = 1;
	for my $i (0 .. $#$pts - 1) {
		my ($a, $b) = ($pts->[$i], $pts->[$i + 1]);
		my ($du, $dy) = ($b->[0] - $a->[0], $b->[1] - $a->[1]);
		my $len = sqrt($du * $du + $dy * $dy);
		next if $len < 0.05;
		my $t = $th * (1 - 0.3 * $i / max(1, $#$pts));
		my $ang = atan2($du, $dy) * 180 / $PI;
		my $name = "vine" . ++$vineN;
		if ($plane eq 'z') {
			# Turned about z: from straight up towards +x by $ang (a right-handed turn the other way).
			bone($name, $parent, [$a->[0], $a->[1], $at], [0, 0, -$ang]);
			$F = cube($name, $name, [$a->[0] - $t / 2, $a->[1] - $t * 0.4, $at - $t / 2], [$a->[0] + $t / 2, $a->[1] + $len + $t * 0.4, $at + $t / 2]);
		} else {
			# Turned about x: from straight up towards +z.
			bone($name, $parent, [$at, $a->[1], $a->[0]], [$ang, 0, 0]);
			$F = cube($name, $name, [$at - $t / 2, $a->[1] - $t * 0.4, $a->[0] - $t / 2], [$at + $t / 2, $a->[1] + $len + $t * 0.4, $a->[0] + $t / 2]);
		}
		vinepaint($F);
		# A knot here and there.
		if (rand() < 0.22) {
			my $k = $t * 1.5;
			my $name2 = "vineknot" . $vineN;
			if ($plane eq 'z') {
				$F = cube($name, $name2, [$a->[0] - $k / 2, $a->[1] + $len * 0.5 - $k / 2, $at - $k / 2], [$a->[0] + $k / 2, $a->[1] + $len * 0.5 + $k / 2, $at + $k / 2]);
			} else {
				$F = cube($name, $name2, [$at - $k / 2, $a->[1] + $len * 0.5 - $k / 2, $a->[0] - $k / 2], [$at + $k / 2, $a->[1] + $len * 0.5 + $k / 2, $a->[0] + $k / 2]);
			}
			vinepaint($F);
		}
	}
}

# A meandering path over a face's rectangle [u0, u1] x [y0, y1]: from (u, y) heading $ang (degrees from +y towards
# +u), $n steps of about $step.
sub path {
	my ($u, $y, $ang, $n, $step, $u0, $u1, $y0, $y1) = @_;
	my @p = ([$u, $y]);
	for (1 .. $n) {
		$ang += (rand(2) - 1) * 32;
		my $a = $ang * $PI / 180;
		$u += sin($a) * $step;
		$y += cos($a) * $step;
		if ($u < $u0 || $u > $u1) { $ang = -$ang; $u = $u < $u0 ? $u0 : $u1; }
		if ($y < $y0 || $y > $y1) { $ang = 180 - $ang; $y = $y < $y0 ? $y0 : $y1; }
		push @p, [$u, $y];
	}
	return \@p;
}

sub max { my ($a, $b) = @_; return $a > $b ? $a : $b; }

# A tendril standing out of him: $segs tapering, curling rods from $o along $dir (unit [x, y, z]), each on a bone
# (named $name0, $name1, ...) so they can writhe.
my @TENDRILS;
sub tendril {
	my ($parent, $name, $o, $dir, $segs, $len, $th, $curl) = @_;
	$o = sxz($o) unless $noscale{$parent};
	local $RAW = 1;
	my $par = $parent;
	my @at = @$o;
	my ($dx, $dy, $dz) = @$dir;
	for my $k (0 .. $segs - 1) {
		my $n = sqrt($dx * $dx + $dy * $dy + $dz * $dz);
		($dx, $dy, $dz) = ($dx / $n, $dy / $n, $dz / $n);
		# Local turns: the parent's already applied, so each later segment only adds its curl.
		my ($rx, $rz);
		if ($k == 0) {
			$rx = asin_d($dz);
			$rz = -atan2($dx, $dy) * 180 / $PI;
		} else {
			($rx, $rz) = ($curl->[0], $curl->[1]);
		}
		my $bn = "${name}_$k";
		bone($bn, $par, [@at], [$rx, 0, $rz]);
		my $t = $th * (1 - 0.28 * $k);
		my $l = $len * (1 - 0.12 * $k);
		$F = cube($bn, $bn, [$at[0] - $t / 2, $at[1] - $t * 0.3, $at[2] - $t / 2], [$at[0] + $t / 2, $at[1] + $l, $at[2] + $t / 2]);
		vinepaint($F);
		push @TENDRILS, $bn;
		$par = $bn;
		# The next starts at this one's end (in the bone's own frame: straight up from its origin).
		$at[1] += $l;
	}
}

sub asin_d { my $x = shift; $x = 1 if $x > 1; $x = -1 if $x < -1; return atan2($x, sqrt(1 - $x * $x)) * 180 / $PI; }

# ---------------------------------------------------------------- the body: gaunt, starved, standing straight
bone("hips", undef, [0, 16, 0.3]);
$F = cube("hips", "pelvis", [-2.3, 14.6, -1.1], [2.3, 17.4, 1.7]);
fleshy($F, along => 'h');
# The hip bones jutting at the front, either side.
for my $sx (1, -1) {
	$F = cube("hips", "iliac" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 1.3 : -2.5, 16.4, -1.35], [$sx > 0 ? 2.5 : -1.3, 17.6, -0.4]);
	fleshy($F, base => 'skinL', vines => 0);
}
$F = cube("hips", "groin", [-1.2, 14.2, -1.0], [1.2, 15.2, 0.6]);
fleshy($F, base => 'skinD');
$F = cube("hips", "buttocks", [-2.1, 14.4, 1.0], [2.1, 16.6, 2.0], -0.05);
fleshy($F);
vine("hips", 'z', -1.25, path(-1.5, 15.0, 30, 4, 0.9, -2.1, 2.1, 14.8, 17.3), 0.45);
vine("hips", 'z', -1.25, path(1.6, 17.2, 200, 4, 0.9, -2.1, 2.1, 14.8, 17.3), 0.42);

bone("waist", "hips", [0, 17.4, 0.4]);
# A waist pinched in by starvation, the belly sunk under the ribs.
$F = cube("waist", "waist", [-1.7, 17.4, -0.7], [1.7, 21.2, 1.5]);
fleshy($F);
$F = cube("waist", "belly", [-1.3, 17.6, -0.95], [1.3, 20.6, -0.5]);
fleshy($F, base => 'skinD');
for my $sx (1, -1) {
	$F = cube("waist", "oblique" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 1.4 : -2.0, 17.6, -0.6], [$sx > 0 ? 2.0 : -1.4, 20.8, 1.2], -0.05);
	fleshy($F, along => 'h');
}
# The spine standing out down the back.
for my $k (0 .. 2) {
	my $y = 17.7 + $k * 1.2;
	$F = cube("waist", "lumbar$k", [-0.4, $y, 1.45], [0.4, $y + 0.9, 1.95]);
	fleshy($F, base => 'bone', vines => 0);
}
vine("waist", 'z', -1.05, path(-1.2, 17.8, 20, 5, 0.85, -1.6, 1.6, 17.6, 21.1), 0.45);
vine("waist", 'z', -1.05, path(1.1, 21.0, 170, 5, 0.85, -1.6, 1.6, 17.6, 21.1), 0.4);
vine("waist", 'x', 1.75, path(0.0, 17.7, 15, 4, 0.9, -0.6, 1.3, 17.6, 21.1), 0.42);
vine("waist", 'x', -1.75, path(0.4, 21.0, 190, 4, 0.9, -0.6, 1.3, 17.6, 21.1), 0.42);

bone("chest", "waist", [0, 21.2, 0.4]);
$F = cube("chest", "ribcage", [-2.8, 21.2, -1.15], [2.8, 26.4, 1.9]);
fleshy($F, along => 'h');
# Ribs standing out of the starved chest: arcs across the front and round the sides.
for my $k (0 .. 4) {
	my $y = 21.6 + $k * 0.92;
	my $half = 2.0 + $k * 0.13;
	for my $sx (1, -1) {
		my ($x0, $x1) = $sx > 0 ? (0.55, $half + 0.6) : (-$half - 0.6, -0.55);
		$F = cube("chest", "rib${k}" . ($sx > 0 ? "_r" : "_l"), [$x0, $y, -1.4], [$x1, $y + 0.42, -1.0]);
		fleshy($F, base => 'bone', vines => 0, along => 'h');
		$F = cube("chest", "ribside${k}" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 2.75 : -3.0, $y, -1.0], [$sx > 0 ? 3.0 : -2.75, $y + 0.42, 1.4]);
		fleshy($F, base => 'bone', vines => 0, along => 'h');
	}
}
$F = cube("chest", "sternum", [-0.45, 21.9, -1.45], [0.45, 26.0, -0.95]);
fleshy($F, base => 'bone', vines => 0);
for my $sx (1, -1) {
	# Wasted pectorals and the collarbones over them, the hollow above.
	$F = cube("chest", "pectoral" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.4 : -2.7, 24.6, -1.45], [$sx > 0 ? 2.7 : -0.4, 26.2, -0.9], -0.05);
	fleshy($F, along => 'h');
	$F = cube("chest", "clavicle" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.3 : -3.4, 26.15, -1.25], [$sx > 0 ? 3.4 : -0.3, 26.65, -0.6]);
	fleshy($F, base => 'bone', vines => 0, along => 'h');
	$F = cube("chest", "trapezius" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.6 : -2.9, 26.2, -0.4], [$sx > 0 ? 2.9 : -0.6, 27.0, 1.6], -0.05);
	fleshy($F, along => 'h');
	$F = cube("chest", "scapula" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.7 : -2.6, 23.4, 1.8], [$sx > 0 ? 2.6 : -0.7, 26.0, 2.35]);
	fleshy($F, base => 'skinL');
}
for my $k (0 .. 3) {
	my $y = 21.5 + $k * 1.25;
	$F = cube("chest", "thoracic$k", [-0.4, $y, 1.8], [0.4, $y + 1.0, 2.4]);
	fleshy($F, base => 'bone', vines => 0);
}
# Vines over the chest, down the sides, over the back.
vine("chest", 'z', -1.55, path(-2.4, 26.0, 160, 7, 0.9, -2.6, 2.6, 21.3, 26.3), 0.5);
vine("chest", 'z', -1.55, path(2.3, 26.1, 200, 7, 0.9, -2.6, 2.6, 21.3, 26.3), 0.5);
vine("chest", 'z', -1.55, path(-0.9, 21.4, 25, 6, 0.85, -2.6, 2.6, 21.3, 26.3), 0.45);
vine("chest", 'z', -1.55, path(1.2, 21.3, -20, 6, 0.85, -2.6, 2.6, 21.3, 26.3), 0.45);
vine("chest", 'z', -1.55, path(0.0, 25.5, 100, 5, 0.8, -2.6, 2.6, 21.3, 26.3), 0.4);
vine("chest", 'z', 2.45, path(-2.2, 21.5, 25, 7, 0.9, -2.6, 2.6, 21.3, 26.3), 0.5);
vine("chest", 'z', 2.45, path(2.0, 26.0, 200, 7, 0.9, -2.6, 2.6, 21.3, 26.3), 0.5);
vine("chest", 'z', 2.45, path(0.4, 21.4, -10, 5, 0.9, -2.6, 2.6, 21.3, 26.3), 0.45);
vine("chest", 'x', 3.05, path(-0.6, 21.4, 20, 6, 0.85, -0.9, 1.6, 21.3, 26.3), 0.45);
vine("chest", 'x', -3.05, path(1.2, 26.2, 190, 6, 0.85, -0.9, 1.6, 21.3, 26.3), 0.45);

bone("neck", "chest", [0, 26.8, 0.3]);
$F = cube("neck", "neck", [-0.85, 26.6, -0.5], [0.85, 29.0, 1.1]);
fleshy($F);
# The sinews standing out of the neck, the windpipe.
for my $sx (1, -1) {
	vine("neck", 'z', -0.65, [[0.75 * $sx, 26.8], [0.55 * $sx, 27.8], [0.3 * $sx, 28.9]], 0.42);
}
$F = cube("neck", "windpipe", [-0.3, 26.9, -0.75], [0.3, 28.3, -0.45]);
fleshy($F, base => 'skinL', vines => 0);
vine("neck", 'x', 0.9, [[0.9, 26.7], [0.4, 27.6], [-0.1, 28.8]], 0.35);
vine("neck", 'x', -0.9, [[-0.2, 26.7], [0.3, 27.7], [0.8, 28.8]], 0.35);

# ---------------------------------------------------------------- the head: a long bald skull, the face of a corpse
bone("head", "neck", [0, 28.8, 0.3]);
# The cranium (its face recessed: the brow, cheekbones and jaw stand out of it), drawn long towards the back.
$F = cube("head", "cranium", [-1.75, 29.4, -0.95], [1.75, 33.6, 2.1]);
fleshy($F, along => 'h', vines => 3);
# The sockets: the cranium's face under the brow is dark, hollow.
{
	my $r = $F->{north};
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $yy = 33.6 - ($j + 0.5) / $S;    # model y of this texel
			my $xx = 1.75 - ($i + 0.5) / $S;    # model x (north face: his right on the image's left)
			my $socket = $yy > 30.55 && $yy < 31.75 && abs(abs($xx) - 0.95) < 0.62;
			my $nose = $yy > 29.9 && $yy < 30.85 && abs($xx) < 0.32;
			if ($socket || $nose) {
				my $d = $socket ? (abs(abs($xx) - 0.95) / 0.62) ** 2 + (($yy - 31.15) / 0.6) ** 2 : 0.5;
				put($x0 + $i, $y0 + $j, jit(mix($C{socket}, $C{skinDD}, smooth($d) * 0.6), 2));
			} else {
				# Wrinkles: crow's feet fanning out from the sockets, furrows across the brow, folds down the cheeks.
				my $c = px_at($x0 + $i, $y0 + $j);
				my ($ex, $ey) = (abs($xx) - 0.95, $yy - 31.15);
				my $de = sqrt($ex * $ex + $ey * $ey);
				my $ang = atan2($ey, $ex);
				if ($de > 0.62 && $de < 1.25 && sin($ang * 9 + $de * 3) > 0.82) {
					$c = mix($c, $C{skinDD}, 0.6);
				} elsif ($yy > 32.1 && sin($yy * 9.5 + sin($xx * 2) * 0.8) > 0.8) {
					$c = mix($c, $C{skinDD}, 0.55);
				} elsif ($yy > 32.1 && sin($yy * 9.5 + sin($xx * 2) * 0.8) < -0.9) {
					$c = mix($c, $C{sheen}, 0.35);
				} elsif ($yy < 30.5 && abs(abs($xx) - 1.25) < 0.12) {
					$c = mix($c, $C{skinDD}, 0.65);
				}
				put($x0 + $i, $y0 + $j, $c);
			}
		}
	}
}
$F = cube("head", "occiput", [-1.55, 30.2, 1.9], [1.55, 33.2, 3.0]);
fleshy($F, along => 'h', vines => 2);
$F = cube("head", "crown", [-1.45, 33.4, -0.7], [1.45, 34.1, 2.6]);
fleshy($F, along => 'h', vines => 2, base => 'skinL');
$F = cube("head", "temples", [-1.95, 30.6, -0.4], [1.95, 33.0, 1.6], -0.1);
fleshy($F, along => 'h', vines => 1);
# The brow: a hard ridge over the sockets, the forehead above it.
$F = cube("head", "forehead", [-1.7, 31.9, -1.35], [1.7, 33.5, -0.9]);
fleshy($F, along => 'h', base => 'skinL', vines => 2);
$F = cube("head", "brow", [-1.8, 31.65, -1.6], [1.8, 32.2, -1.05]);
fleshy($F, along => 'h', base => 'skinL', vines => 0);
# Cheekbones, sharp under the sockets; the hollows of the cheeks under them.
for my $sx (1, -1) {
	$F = cube("head", "cheekbone" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.5 : -1.85, 30.15, -1.45], [$sx > 0 ? 1.85 : -0.5, 30.6, -0.85]);
	fleshy($F, along => 'h', base => 'bone', vines => 0);
	$F = cube("head", "cheek" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 1.0 : -1.6, 29.2, -0.95], [$sx > 0 ? 1.6 : -1.0, 30.2, 0.9]);
	fleshy($F, base => 'skinD', vines => 1);
	# The eye, far back in the socket.
	$F = cube("head", "eye" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.68 : -1.22, 30.92, -1.0], [$sx > 0 ? 1.22 : -0.68, 31.3, -0.9]);
	eyepaint($F->{north});
	solid($F->{$_}, 'socket', 2) for qw(east west up down south);
	# The lids: thin, dark, drawn back.
	$F = cube("head", "lid" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.45 : -1.45, 31.4, -1.25], [$sx > 0 ? 1.45 : -0.45, 31.65, -0.95]);
	fleshy($F, base => 'skinD', vines => 0, along => 'h');
}
# The nose: only a ridge of bone between the sockets and the hole under it.
$F = cube("head", "nasal_ridge", [-0.28, 30.85, -1.55], [0.28, 31.7, -1.05]);
fleshy($F, base => 'bone', vines => 0);
$F = cube("head", "nostril_rim", [-0.5, 29.85, -1.35], [0.5, 30.1, -0.9]);
fleshy($F, base => 'skinD', vines => 0);
# The upper jaw: gums drawn back from long teeth.
$F = cube("head", "maxilla", [-1.2, 29.25, -1.4], [1.2, 29.9, -0.4]);
fleshy($F, along => 'h', base => 'skinD', vines => 0);
solid($F->{down}, 'gumD', 3);
# The upper teeth: a crowded row bared to the roots, long canines either side, the side teeth set back.
$F = cube("head", "teeth_up", [-0.75, 28.8, -1.47], [0.75, 29.4, -1.2]);
teethpaint($F->{$_}, 'up') for qw(north south east west);
solid($F->{down}, 'tooth', 4);
for my $sx (1, -1) {
	$F = cube("head", "teeth_up_side" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.7 : -1.1, 28.85, -1.35], [$sx > 0 ? 1.1 : -0.7, 29.4, -0.7]);
	teethpaint($F->{$_}, 'up') for qw(north south east west);
	$F = cube("head", "canine" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.55 : -0.8, 28.45, -1.5], [$sx > 0 ? 0.8 : -0.55, 29.4, -1.22]);
	teethpaint($F->{$_}, 'up') for qw(north south east west);
	solid($F->{down}, 'tooth', 4);
}
# The lower jaw, on a bone of its own so it can gape; the chin pointed.
bone("jaw", "head", [0, 29.3, 0.8]);
$F = cube("jaw", "mandible", [-1.15, 28.0, -1.3], [1.15, 28.75, 1.0]);
fleshy($F, along => 'h', base => 'skinD', vines => 1);
$F = cube("jaw", "chin", [-0.55, 27.75, -1.45], [0.55, 28.4, -0.9]);
fleshy($F, base => 'bone', vines => 0);
for my $sx (1, -1) {
	$F = cube("jaw", "jaw_hinge" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 1.0 : -1.45, 28.2, 0.2], [$sx > 0 ? 1.45 : -1.0, 29.6, 1.0]);
	fleshy($F, base => 'skinD', vines => 0);
}
$F = cube("jaw", "lower_gum", [-1.05, 28.6, -1.3], [1.05, 28.85, -0.5]);
solid($F->{$_}, 'gum', 4) for qw(north south east west up down);
$F = cube("jaw", "teeth_down", [-0.7, 28.7, -1.38], [0.7, 29.2, -1.12]);
teethpaint($F->{$_}, 'down') for qw(north south east west);
solid($F->{up}, 'tooth', 4);
for my $sx (1, -1) {
	$F = cube("jaw", "teeth_down_side" . ($sx > 0 ? "_r" : "_l"), [$sx > 0 ? 0.65 : -1.0, 28.7, -1.25], [$sx > 0 ? 1.0 : -0.65, 29.15, -0.65]);
	teethpaint($F->{$_}, 'down') for qw(north south east west);
}
# Vines over the skull: up over the crown and down the back of the head, across the temples.
vine("head", 'x', 1.98, path(-0.2, 30.8, 20, 5, 0.75, -0.3, 2.6, 29.6, 33.4), 0.36);
vine("head", 'x', -1.98, path(2.4, 30.9, -20, 5, 0.75, -0.3, 2.6, 29.6, 33.4), 0.36);
vine("head", 'z', 3.05, path(-1.2, 30.4, 15, 4, 0.8, -1.4, 1.4, 30.3, 33.1), 0.36);
vine("head", 'z', 3.05, path(1.1, 33.0, 190, 4, 0.8, -1.4, 1.4, 30.3, 33.1), 0.34);
vine("head", 'z', -1.4, path(-1.3, 33.3, 160, 3, 0.6, -1.6, 1.6, 32.3, 33.4), 0.3);

# ---------------------------------------------------------------- arms: long, wasted, the hands long-fingered
sub arm {
	my ($p, $sx) = @_;    # $sx: +1 his right (east), -1 his left
	my $X = 3.35 * $sx;
	bone("${p}_shoulder", "chest", [3.0 * $sx, 26.0, 0.35]);
	$F = cube("${p}_shoulder", "${p}_deltoid", [$sx > 0 ? 2.6 : -4.3, 23.9, -0.6], [$sx > 0 ? 4.3 : -2.6, 26.6, 1.4], -0.05);
	fleshy($F, vines => 2);
	$F = cube("${p}_shoulder", "${p}_acromion", [$sx > 0 ? 2.9 : -3.9, 26.3, -0.3], [$sx > 0 ? 3.9 : -2.9, 26.8, 1.1]);
	fleshy($F, base => 'bone', vines => 0);
	vine("${p}_shoulder", 'z', -0.75, path(3.0 * $sx, 26.4, 180 + 15 * $sx, 3, 0.8, $sx > 0 ? 2.7 : -4.2, $sx > 0 ? 4.2 : -2.7, 24.0, 26.5), 0.45);

	bone("${p}_upper_arm", "${p}_shoulder", [$X, 25.6, 0.35]);
	$F = cube("${p}_upper_arm", "${p}_humerus", [$X - 0.8, 19.0, -0.45], [$X + 0.8, 25.6, 1.15]);
	fleshy($F, vines => 3);
	$F = cube("${p}_upper_arm", "${p}_biceps", [$X - 0.65, 20.6, -0.7], [$X + 0.65, 24.2, -0.2], -0.05);
	fleshy($F);
	$F = cube("${p}_upper_arm", "${p}_triceps", [$X - 0.7, 20.2, 1.0], [$X + 0.7, 24.6, 1.45], -0.05);
	fleshy($F);
	vine("${p}_upper_arm", 'z', -0.82, path($X - 0.3 * $sx, 25.2, 180, 6, 0.95, $X - 0.7, $X + 0.7, 19.2, 25.4), 0.42);
	vine("${p}_upper_arm", 'x', $X + 0.88 * $sx, path(0.3, 25.0, 175, 6, 0.95, -0.4, 1.1, 19.2, 25.3), 0.42);
	vine("${p}_upper_arm", 'z', 1.5, path($X + 0.3 * $sx, 24.8, 185, 5, 0.95, $X - 0.7, $X + 0.7, 19.2, 25.4), 0.38);

	bone("${p}_forearm", "${p}_upper_arm", [$X, 19.0, 0.35]);
	$F = cube("${p}_forearm", "${p}_ulna", [$X - 0.7, 12.4, -0.4], [$X + 0.7, 19.0, 1.05]);
	fleshy($F, vines => 3);
	$F = cube("${p}_forearm", "${p}_elbow", [$X - 0.55, 18.2, 0.9], [$X + 0.55, 19.7, 1.5]);
	fleshy($F, base => 'bone', vines => 0);
	$F = cube("${p}_forearm", "${p}_forearm_muscle", [$X - 0.85, 15.6, -0.55], [$X + 0.85, 18.6, 0.95], -0.08);
	fleshy($F);
	$F = cube("${p}_forearm", "${p}_wrist", [$X - 0.55, 12.2, -0.3], [$X + 0.55, 13.2, 0.95]);
	fleshy($F, base => 'skinL', vines => 0);
	vine("${p}_forearm", 'z', -0.68, path($X, 18.8, 180, 6, 1.0, $X - 0.6, $X + 0.6, 12.6, 18.9), 0.4);
	vine("${p}_forearm", 'x', $X + 0.78 * $sx, path(0.6, 18.6, 185, 6, 1.0, -0.3, 1.0, 12.6, 18.8), 0.4);
	vine("${p}_forearm", 'x', $X - 0.78 * $sx, path(0.1, 18.4, 175, 5, 1.0, -0.3, 1.0, 12.6, 18.8), 0.36);

	# The hand: long and bony, palm to his thigh, four long fingers along it and the thumb at the front.
	bone("${p}_hand", "${p}_forearm", [$X, 12.3, 0.35]);
	$F = cube("${p}_hand", "${p}_palm", [$X - 0.32, 10.4, -0.55], [$X + 0.32, 12.4, 1.15]);
	fleshy($F, vines => 1, base => 'skinD');
	$F = cube("${p}_hand", "${p}_knuckles", [$X - 0.4, 10.2, -0.6], [$X + 0.4, 10.75, 1.2], -0.05);
	fleshy($F, base => 'bone', vines => 0);
	for my $k (0 .. 3) {
		my $z = -0.4 + $k * 0.48;
		my $len = $k == 1 || $k == 2 ? 1.7 : 1.45;
		bone("${p}_finger$k", "${p}_hand", [$X, 10.35, $z]);
		$F = cube("${p}_finger$k", "${p}_finger${k}_base", [$X - 0.16, 10.35 - $len, $z - 0.17], [$X + 0.16, 10.45, $z + 0.17]);
		fleshy($F, base => 'skinD', vines => 0);
		bone("${p}_finger${k}_tip", "${p}_finger$k", [$X, 10.35 - $len, $z]);
		$F = cube("${p}_finger${k}_tip", "${p}_finger${k}_end", [$X - 0.14, 10.35 - $len * 1.85, $z - 0.15], [$X + 0.14, 10.4 - $len, $z + 0.15]);
		fleshy($F, base => 'skinD', vines => 0);
		$F = cube("${p}_finger${k}_tip", "${p}_nail$k", [$X - 0.15, 10.35 - $len * 2.05, $z - 0.12], [$X + 0.15, 10.35 - $len * 1.75, $z + 0.12]);
		nailpaint($F->{$_}) for qw(north south east west up down);
		$F = cube("${p}_finger$k", "${p}_knuckle$k", [$X - 0.2, 10.35 - $len - 0.2, $z - 0.2], [$X + 0.2, 10.35 - $len + 0.2, $z + 0.2]);
		fleshy($F, base => 'bone', vines => 0);
	}
	bone("${p}_thumb", "${p}_hand", [$X, 11.6, -0.55]);
	$F = cube("${p}_thumb", "${p}_thumb_bone", [$X - 0.17, 10.0, -0.75], [$X + 0.17, 11.7, -0.4]);
	fleshy($F, base => 'skinD', vines => 0);
	$F = cube("${p}_thumb", "${p}_thumb_nail", [$X - 0.15, 9.75, -0.72], [$X + 0.15, 10.1, -0.43]);
	nailpaint($F->{$_}) for qw(north south east west up down);
}
arm("right", 1);
arm("left", -1);

# ---------------------------------------------------------------- legs: thin, sinewy, the feet long and bare
sub leg {
	my ($p, $sx) = @_;
	my $X = 1.25 * $sx;
	bone("${p}_thigh", "hips", [$X, 15.0, 0.3]);
	$F = cube("${p}_thigh", "${p}_femur", [$X - 0.9, 8.4, -0.55], [$X + 0.9, 15.2, 1.15]);
	fleshy($F, vines => 3);
	$F = cube("${p}_thigh", "${p}_quad", [$X - 0.75, 9.6, -0.8], [$X + 0.75, 13.8, -0.3], -0.05);
	fleshy($F);
	vine("${p}_thigh", 'z', -0.92, path($X + 0.3 * $sx, 15.0, 185, 7, 1.0, $X - 0.8, $X + 0.8, 8.6, 15.0), 0.45);
	vine("${p}_thigh", 'x', $X + 0.98 * $sx, path(0.5, 14.8, 175, 7, 1.0, -0.4, 1.0, 8.6, 15.0), 0.45);
	vine("${p}_thigh", 'z', 1.22, path($X, 14.6, 180, 6, 1.0, $X - 0.8, $X + 0.8, 8.6, 15.0), 0.4);

	bone("${p}_shin", "${p}_thigh", [$X, 8.4, 0.3]);
	$F = cube("${p}_shin", "${p}_tibia", [$X - 0.7, 1.3, -0.45], [$X + 0.7, 8.4, 1.0]);
	fleshy($F, vines => 3);
	$F = cube("${p}_shin", "${p}_kneecap", [$X - 0.6, 7.6, -0.75], [$X + 0.6, 8.9, -0.3]);
	fleshy($F, base => 'bone', vines => 0);
	$F = cube("${p}_shin", "${p}_calf", [$X - 0.6, 4.6, 0.8], [$X + 0.6, 7.6, 1.35], -0.05);
	fleshy($F);
	vine("${p}_shin", 'z', -0.62, path($X, 7.4, 180, 6, 1.0, $X - 0.6, $X + 0.6, 1.5, 7.5), 0.42);
	vine("${p}_shin", 'x', $X + 0.78 * $sx, path(0.4, 8.2, 185, 7, 1.0, -0.3, 0.9, 1.5, 8.2), 0.4);

	bone("${p}_foot", "${p}_shin", [$X, 1.3, 0.3]);
	$F = cube("${p}_foot", "${p}_ankle", [$X - 0.6, 0.6, -0.3], [$X + 0.6, 1.6, 0.9]);
	fleshy($F, base => 'skinL', vines => 0);
	$F = cube("${p}_foot", "${p}_sole", [$X - 0.62, 0, -1.8], [$X + 0.62, 0.75, 0.95]);
	fleshy($F, along => 'h', vines => 1);
	$F = cube("${p}_foot", "${p}_instep", [$X - 0.5, 0.6, -1.4], [$X + 0.5, 1.0, -0.2]);
	fleshy($F, base => 'bone', vines => 0);
	for my $k (0 .. 3) {
		my $x = $X + (-0.42 + $k * 0.28) * $sx;
		$F = cube("${p}_foot", "${p}_toe$k", [$x - 0.12, 0, -2.25 + ($k > 1 ? 0.15 * ($k - 1) : 0)], [$x + 0.12, 0.3, -1.75]);
		fleshy($F, base => 'skinD', vines => 0);
	}
}
leg("right", 1);
leg("left", -1);

# ---------------------------------------------------------------- tendrils: out of the shoulders, the back, the forearms
for my $sx (1, -1) {
	my $s = $sx > 0 ? "r" : "l";
	tendril("${\($sx > 0 ? 'right' : 'left')}_shoulder", "tendril_sh${s}0", [3.3 * $sx, 26.4, 0.5], [0.55 * $sx, 1, 0.15], 3, 1.0, 0.36, [-18, 14 * $sx]);
	tendril("${\($sx > 0 ? 'right' : 'left')}_shoulder", "tendril_sh${s}1", [3.9 * $sx, 25.6, 0.9], [1, 0.55, 0.4], 3, 0.9, 0.32, [12, -22 * $sx]);
	tendril("${\($sx > 0 ? 'right' : 'left')}_shoulder", "tendril_sh${s}2", [2.7 * $sx, 26.5, 1.2], [0.2 * $sx, 1, 0.6], 2, 0.9, 0.3, [24, 10 * $sx]);
	tendril("${\($sx > 0 ? 'right' : 'left')}_forearm", "tendril_fa${s}", [3.35 * $sx + 0.7 * $sx, 16.8, 0.6], [1 * $sx, 0.6, 0.2], 2, 0.7, 0.26, [-10, -26 * $sx]);
	tendril("${\($sx > 0 ? 'right' : 'left')}_upper_arm", "tendril_ua${s}", [3.35 * $sx + 0.8 * $sx, 23.4, 0.9], [1 * $sx, 0.4, 0.5], 2, 0.7, 0.26, [14, -20 * $sx]);
}
tendril("chest", "tendril_bk0", [-0.9, 25.6, 2.3], [-0.3, 0.8, 1], 3, 0.95, 0.34, [20, -8]);
tendril("chest", "tendril_bk1", [1.0, 24.4, 2.3], [0.4, 0.6, 1], 3, 0.9, 0.32, [22, 10]);
tendril("chest", "tendril_bk2", [0.0, 22.6, 2.3], [0, 0.4, 1], 2, 0.85, 0.3, [26, 0]);
tendril("waist", "tendril_bk3", [-0.5, 19.6, 1.9], [-0.4, 0.2, 1], 2, 0.7, 0.26, [18, -12]);

# The lash: a tentacle that shoots out of his right palm when he strikes (hidden, scaled to nothing, the rest of the
# time: client.VecnaModel grows it). Six segments down from the palm, along the hand.
tendril("right_hand", "lash", [3.35, 10.6, 0.35], [0, -1, 0.05], 6, 2.8, 1.4, [4, 0]);
# (Not one of the writhing tendrils: it only moves when he strikes.)
splice @TENDRILS, -6;

# ---------------------------------------------------------------- posing
sub setr {
	my ($pose, $b, $x, $y, $z) = @_;
	my $r = $pose->{$b} //= [0, 0, 0];
	$r->[0] += $x // 0;
	$r->[1] += $y // 0;
	$r->[2] += $z // 0;
}

# Fingers curled $curl degrees in towards the palm at each joint; spread apart by $spread.
sub fingers {
	my ($pose, $side, $curl, $spread, $tip) = @_;
	my $sx = $side eq 'right' ? 1 : -1;
	for my $k (0 .. 3) {
		# Curling towards the palm (towards his body: -x for the right hand) is a turn about z.
		setr($pose, "${side}_finger$k", ($k - 1.5) * $spread, 0, -$curl * $sx);
		setr($pose, "${side}_finger${k}_tip", 0, 0, -($tip // $curl * 1.2) * $sx);
	}
	setr($pose, "${side}_thumb", -$curl * 0.6, 0, -$curl * 0.3 * $sx);
}

# The tendrils writhing at phase $u (radians), by $amp degrees.
sub writhe {
	my ($pose, $u, $amp) = @_;
	my $i = 0;
	for my $t (@TENDRILS) {
		my $ph = $i++ * 1.7;
		setr($pose, $t, $amp * sin($u + $ph), 0, $amp * 0.8 * cos($u * 1.3 + $ph));
	}
}

# Floating: off the ground by $lift, the legs hanging loose, feet pointed, the arms a little out from him.
sub floating {
	my ($lift) = @_;
	my $p = {hips_pos => [0, $lift, 0]};
	setr($p, "chest", -4);
	setr($p, "neck", 4);
	setr($p, "head", -6);
	for my $s (qw(right left)) {
		my $sx = $s eq 'right' ? 1 : -1;
		setr($p, "${s}_thigh", 9, 0, 3 * $sx);
		setr($p, "${s}_shin", -16);
		setr($p, "${s}_foot", -42);
		setr($p, "${s}_upper_arm", 6, 0, 9 * $sx);
		setr($p, "${s}_forearm", 12);
		setr($p, "${s}_hand", 4);
		fingers($p, $s, 14, 4);
	}
	return $p;
}

my @anims;
sub anim {
	my ($name, $length, $loop, $frames, $interp) = @_;
	my %used;
	for my $f (@$frames) { $used{$_} = 1 for keys %{$f->[1]}; }
	my $a = {uuid => uuid(), name => $name, loop => $loop, override => JSON::PP::false, length => $length, snapping => 60,
		selected => JSON::PP::false, anim_time_update => "", blend_weight => "", start_delay => "", loop_delay => "", animators => {}};
	for my $b (sort keys %used) {
		my $bone = $b eq 'hips_pos' ? 'hips' : $b;
		my $channel = $b eq 'hips_pos' ? 'position' : $b =~ /_scale$/ ? 'scale' : 'rotation';
		$bone =~ s/_scale$//;
		die "anim $name: no bone $bone" unless $bones{$bone};
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

my $LIFT = 5;

# float: hanging in the air, bobbing, breathing, the head slowly turning, the fingers curling, the tendrils writhing.
{
	my @f;
	my $T = 4.0;
	for my $i (0 .. 16) {
		my $t = $T * $i / 16;
		my $u = $t / $T * 2 * $PI;
		my $p = floating($LIFT + 0.6 * sin($u));
		$p->{chest_scale} = [1 + 0.02 * (1 + sin($u * 2)) / 2, 1, 1 + 0.03 * (1 + sin($u * 2)) / 2];
		setr($p, "hips", 2 * sin($u + 0.5), 4 * sin($u * 0.5), 1.5 * sin($u));
		setr($p, "chest", 1.5 * sin($u * 2));
		setr($p, "neck", 2 * sin($u), 8 * sin($u + 0.7));
		setr($p, "head", 3 * sin($u * 2 + 1), 12 * sin($u + 1.2), 6 * sin($u * 3 + 0.4));
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			my $ph = $sx > 0 ? 0 : $PI;
			setr($p, "${s}_upper_arm", 4 * sin($u + $ph), 0, 3 * sin($u * 2 + $ph) * $sx);
			setr($p, "${s}_forearm", 5 * sin($u + $ph + 0.7));
			for my $k (0 .. 3) {
				setr($p, "${s}_finger$k", 0, 0, -12 * (1 + sin($u * 2 + $k * 0.9 + $ph)) / 2 * $sx);
				setr($p, "${s}_finger${k}_tip", 0, 0, -16 * (1 + sin($u * 2 + $k * 0.9 + $ph + 0.8)) / 2 * $sx);
			}
			# The legs drift, one and then the other.
			setr($p, "${s}_thigh", 5 * sin($u + $ph * 0.5));
			setr($p, "${s}_shin", -5 * sin($u + $ph * 0.5 + 0.8));
			setr($p, "${s}_foot", 6 * sin($u + $ph * 0.5 + 1.4));
		}
		setr($p, "jaw", 3 + 2 * sin($u * 2));
		writhe($p, $u * 2, 16);
		push @f, [$t, $p];
	}
	anim("float", $T, "loop", \@f);
}

# glide: drifting forwards through the air, leaning into it, the legs trailing behind, the arms swept back a little.
{
	my @f;
	my $T = 2.0;
	for my $i (0 .. 12) {
		my $t = $T * $i / 12;
		my $u = $t / $T * 2 * $PI;
		my $p = floating($LIFT + 0.8 + 0.4 * sin($u * 2));
		setr($p, "hips", -3, 3 * sin($u), 2 * sin($u));
		setr($p, "chest", -2 + 1.5 * sin($u * 2));
		setr($p, "head", 7, 5 * sin($u));
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			my $ph = $sx > 0 ? 0 : $PI;
			setr($p, "${s}_thigh", -8 + 5 * sin($u + $ph));
			setr($p, "${s}_shin", -18 + 8 * sin($u + $ph + 0.9));
			setr($p, "${s}_foot", -12);
			setr($p, "${s}_upper_arm", -14 + 5 * sin($u + $ph), 0, 6 * $sx);
			setr($p, "${s}_forearm", 8);
		}
		writhe($p, $u * 3, 24);
		push @f, [$t, $p];
	}
	anim("glide", $T, "loop", \@f);
}

# cast (laid over): the Grip. The left arm thrust out before him at shoulder height, the fingers spread and clawed,
# trembling with the strain; the right arm drawn back a little, the head leaning in, the jaw open.
{
	my @f;
	my $T = 1.0;
	for my $i (0 .. 10) {
		my $t = $T * $i / 10;
		my $u = $t / $T * 2 * $PI;
		my $sh = sin($u * 5) * 2.5 + sin($u * 9) * 1.2;
		my $p = {};
		setr($p, "left_shoulder", -4, 0, 6);
		setr($p, "left_upper_arm", 84 + $sh, -12, -4);
		setr($p, "left_forearm", 2 - $sh * 0.5);
		setr($p, "left_hand", 18 + $sh);
		for my $k (0 .. 3) {
			setr($p, "left_finger$k", ($k - 1.5) * 14, 0, 26 + $sh);
			setr($p, "left_finger${k}_tip", 0, 0, 34);
		}
		setr($p, "left_thumb", -30, 0, 10);
		setr($p, "right_upper_arm", -10, 0, 12);
		setr($p, "right_forearm", 14);
		setr($p, "chest", -6, -8, 0);
		setr($p, "neck", -4);
		setr($p, "head", -6 + $sh * 0.3, 6, 0);
		setr($p, "jaw", 14 + $sh);
		writhe($p, $u * 4, 30);
		push @f, [$t, $p];
	}
	anim("cast", $T, "loop", \@f, "linear");
}

# strike (laid over): the right arm thrust out at them, the hand open, and the lash out of the palm whipping across
# (client.VecnaModel grows and shrinks it), then drawn back.
{
	my @f;
	for my $i (0 .. 11) {
		my $t = 0.55 * $i / 11;
		my $out = $t < 0.1 ? $t / 0.1 : $t < 0.38 ? 1 : $t < 0.55 ? 1 - ($t - 0.38) / 0.17 : 0;
		$out = $out * $out * (3 - 2 * $out);
		my $p = {};
		setr($p, "right_shoulder", -4 * $out, 0, -4 * $out);
		setr($p, "right_upper_arm", 88 * $out, -10 * $out, 6 * $out);
		setr($p, "right_forearm", -6 * $out);
		setr($p, "right_hand", 10 * $out);
		fingers($p, 'right', -18 * $out, 16 * $out, -10 * $out);
		setr($p, "chest", -4 * $out, 14 * $out, 0);
		setr($p, "head", -6 * $out);
		setr($p, "jaw", 22 * $out);
		# The whip: a wave running down the lash, side to side.
		my $ph = $t / 0.55 * 2 * $PI * 1.5;
		for my $k (1 .. 5) {
			setr($p, "lash_$k", 5 * sin($ph - $k * 0.9) * $out, 0, 14 * sin($ph * 1.3 - $k * 0.8) * $out);
		}
		push @f, [$t, $p];
	}
	anim("strike", 0.55, "once", \@f, "linear");
}

# jaw: shut (0 s) to gaping (1 s).
anim("jaw", 1.0, "hold_on_last_frame", [[0, {jaw => [0, 0, 0]}], [1.0, {jaw => [38, 0, 0]}]], "linear");

# twitch: the head jerking, the neck snapping round.
{
	my @f;
	for my $kf ([0, 0, 0, 0], [0.07, 18, -10, 8], [0.15, -12, 12, -5], [0.24, 14, -6, 9], [0.36, -6, 10, -4], [0.5, 0, 0, 0], [1.4, 0, 0, 0]) {
		my ($t, $z, $y, $x) = @$kf;
		my $p = {};
		setr($p, "head", $x, $y, $z);
		setr($p, "neck", -$x * 0.4, -$y * 0.5, -$z * 0.4);
		setr($p, "jaw", abs($z) * 0.8);
		push @f, [$t, $p];
	}
	anim("twitch", 1.4, "loop", \@f, "linear");
}

# throttle: holding them by the throat. The left arm reaching down and forward to their neck, the hand clenched round
# it and squeezing in pulses; the right arm raised out to the side, claws spread over them; leaning in, head cocked,
# the jaw slowly working.
{
	my @f;
	my $T = 1.6;
	for my $i (0 .. 12) {
		my $t = $T * $i / 12;
		my $u = $t / $T * 2 * $PI;
		my $sq = (1 + sin($u * 2)) / 2;
		my $sh = sin($u * 7) * 1.5;
		my $p = {};
		setr($p, "left_shoulder", -6, 0, 4);
		setr($p, "left_upper_arm", 52 + $sh, -14, -6);
		setr($p, "left_forearm", -14 - $sh * 0.5);
		setr($p, "left_hand", 24 + 4 * $sq);
		fingers($p, 'left', 58 + 14 * $sq, 6, 46 + 10 * $sq);
		setr($p, "right_shoulder", -4, 0, -6);
		setr($p, "right_upper_arm", 46 + 4 * sin($u), 6, 38 + 3 * sin($u + 1));
		setr($p, "right_forearm", 58 + 5 * sin($u + 0.6));
		setr($p, "right_hand", -12);
		fingers($p, 'right', 22 + 8 * sin($u + 1.4), 16, 30);
		setr($p, "chest", -10 - 2 * $sq, 6, 0);
		setr($p, "neck", -6);
		setr($p, "head", -12 + $sh * 0.4, 8, 14 + 3 * sin($u));
		setr($p, "jaw", 10 + 8 * $sq);
		writhe($p, $u * 2, 26);
		push @f, [$t, $p];
	}
	anim("throttle", $T, "loop", \@f);
}

# rend (laid over throttle): the right claw drawn back and driven into them, then torn out.
{
	my @f;
	for my $kf ([0, 0, 0, 0], [0.18, 1, 0, 0], [0.27, 0, 1, 0], [0.4, 0, 1, 1], [0.6, 0, 0, 0]) {
		my ($t, $back, $in, $out) = @$kf;
		my $p = {};
		setr($p, "right_upper_arm", 70 * $back + 30 * $in - 10 * $out, 0, -18 * $back - 40 * $in);
		setr($p, "right_forearm", 30 * $back - 55 * $in + 20 * $out);
		setr($p, "right_hand", 12 * $in);
		for my $k (0 .. 3) {
			setr($p, "right_finger$k", 0, 0, 20 * $in - 40 * $out);
			setr($p, "right_finger${k}_tip", 0, 0, 25 * $in - 40 * $out);
		}
		setr($p, "chest", -6 * $in, 22 * $back - 26 * $in, 0);
		setr($p, "head", -8 * $in);
		setr($p, "jaw", 18 * $in + 10 * $out);
		push @f, [$t, $p];
	}
	anim("rend", 0.6, "once", \@f);
}

# recoil: hit or shaken off, he reels back, arms flung wide, head thrown back, jaw gaping, and gathers himself.
{
	my @f;
	for my $kf ([0, 0], [0.12, 1], [0.35, 0.8], [0.8, 0]) {
		my ($t, $k) = @$kf;
		my $p = {};
		setr($p, "chest", 16 * $k, 0, 0);
		setr($p, "neck", 10 * $k);
		setr($p, "head", 26 * $k, 0, -10 * $k);
		setr($p, "jaw", 34 * $k);
		for my $s (qw(right left)) {
			my $sx = $s eq 'right' ? 1 : -1;
			setr($p, "${s}_upper_arm", 30 * $k, 0, 50 * $k * $sx);
			setr($p, "${s}_forearm", 30 * $k);
			fingers($p, $s, -10 * $k, 18 * $k, -10 * $k);
		}
		writhe($p, $t * 20, 40 * $k);
		push @f, [$t, $p];
	}
	anim("recoil", 0.8, "once", \@f);
}

# ---------------------------------------------------------------- the kills (game.VecnaKill; laid over float, timed to
# the scene's ticks: a second is 20 of them). Vines out of his back and palm are client.VecnaVines', not bones.

# Held by the throat as throttle has it (at rest, mid-squeeze): where every kill from a grab starts.
sub throttled {
	my $p = {};
	setr($p, "left_shoulder", -6, 0, 4);
	setr($p, "left_upper_arm", 52, -14, -6);
	setr($p, "left_forearm", -14);
	setr($p, "left_hand", 26);
	fingers($p, 'left', 65, 6, 51);
	setr($p, "right_shoulder", -4, 0, -6);
	setr($p, "right_upper_arm", 46, 6, 38);
	setr($p, "right_forearm", 58);
	setr($p, "right_hand", -12);
	fingers($p, 'right', 22, 16, 30);
	setr($p, "chest", -11, 6, 0);
	setr($p, "neck", -6);
	setr($p, "head", -12, 8, 14);
	setr($p, "jaw", 14);
	return $p;
}

# Builds a pose from a list of [bone, x, y, z] over $base (a copy).
sub posed {
	my ($base, @set) = @_;
	my $p = {map { $_ => [@{$base->{$_}}] } keys %$base};
	for my $s (@set) {
		my ($b, $x, $y, $z) = @$s;
		$p->{$b} = [$x // 0, $y // 0, $z // 0];
	}
	return $p;
}

# Fingers of one hand: curled $curl (negative: splayed open), spread $spread, as a list for posed().
sub hand {
	my ($side, $curl, $spread, $tip) = @_;
	my $p = {};
	fingers($p, $side, $curl, $spread, $tip);
	return map { [$_, @{$p->{$_}}] } sort keys %$p;
}

# ascend (5 s): he lifts them overhead by the throat; lets go and they hang in the air, his arms spread as if
# holding them up; rears back and drives his right hand at them (the vines run them through, from his back and his
# palm); his fingers close slowly into a fist, a twist of the wrist (the neck breaks), he tears his arm back (the
# vines out) and lowers his arms, looking down at them.
{
	my $a0 = throttled();
	my $lift = posed($a0,
		["left_shoulder", -10, 0, 2], ["left_upper_arm", 152, -10, -6], ["left_forearm", -12], ["left_hand", 18], hand('left', 72, 4, 56),
		["right_upper_arm", 24, 0, 30], ["right_forearm", 30], ["right_hand", 0], hand('right', 18, 10, 20),
		["chest", 6, 4, 0], ["neck", 8], ["head", 18, 6, 4], ["jaw", 18]);
	my $spread = posed($a0,
		["left_shoulder", -6, 0, -4], ["left_upper_arm", 112, -6, -36], ["left_forearm", 8], ["left_hand", -16], hand('left', -22, 20, -12),
		["right_shoulder", -6, 0, 4], ["right_upper_arm", 112, 6, 36], ["right_forearm", 8], ["right_hand", -16], hand('right', -22, 20, -12),
		["chest", 6, 0, 0], ["neck", 6], ["head", 14, 0, 0], ["jaw", 22]);
	my $rear = posed($spread,
		["left_upper_arm", 84, -6, -58], ["right_upper_arm", 84, 6, 58], ["right_forearm", 26], ["left_forearm", 26],
		["chest", 12, 0, 0], ["neck", 8], ["head", 22, 0, 0], ["jaw", 34]);
	my $thrust = posed($spread,
		["right_shoulder", -8, 0, -2], ["right_upper_arm", 124, 4, 6], ["right_forearm", -6], ["right_hand", 14], hand('right', -20, 22, -14),
		["left_shoulder", -2, 0, -4], ["left_upper_arm", 58, -8, -56], ["left_forearm", 22], ["left_hand", 0], hand('left', 30, 10, 30),
		["chest", -12, -10, 0], ["neck", -2], ["head", 10, -4, 0], ["jaw", 30]);
	my $squeeze = sub {
		my ($k, $sh) = @_;    # $k: 0 splayed .. 1 a fist; $sh: tremble
		return posed($thrust, ["right_upper_arm", 124 + $sh, 4, 6 - $sh * 0.5], ["right_forearm", -6 - $sh], ["right_hand", 14 + $sh * 0.5],
			hand('right', -20 + 95 * $k, 22 * (1 - $k), -14 + 100 * $k), ["chest", -12 + $sh * 0.4, -10, 0], ["jaw", 30 + 10 * $k]);
	};
	my $snap = posed($squeeze->(1, 0), ["right_hand", 14, -48, 10], ["right_forearm", -6, -14, 0], ["chest", -14, -22, 0], ["head", 6, -10, 8]);
	my $rip = posed($snap,
		["right_shoulder", -2, 0, -8], ["right_upper_arm", 36, 0, 52], ["right_forearm", 76], ["right_hand", -10, 0, 0], hand('right', 70, 6, 80),
		["chest", 10, 8, 0], ["neck", 6], ["head", 16, 0, 0], ["jaw", 38]);
	my $rest = posed({},
		["left_upper_arm", 10, 0, -14], ["left_forearm", 16], hand('left', 26, 6, 30),
		["right_upper_arm", 10, 0, 14], ["right_forearm", 16], hand('right', 34, 6, 40),
		["chest", -6], ["neck", -6], ["head", -14, 0, 6], ["jaw", 8]);
	my @f = ([0, $a0], [0.5, posed($lift, ["left_upper_arm", 100, -12, -6], ["chest", -2, 4, 0], ["head", 4, 6, 8])], [1.0, $lift],
		[1.1, posed($lift, ["left_upper_arm", 158, -10, -6])], [1.35, posed($spread, ["head", 18, 0, 0])], [1.6, $spread], [1.75, $rear], [1.95, $thrust],
		[2.0, posed($thrust, ["chest", -16, -10, 0])], [2.2, $squeeze->(0, 0)]);
	my $i = 0;
	for (my $t = 2.4; $t < 3.39; $t += 0.2) {
		push @f, [$t, $squeeze->(($t - 2.2) / 1.2, ($i++ % 2 ? 3 : -3))];
	}
	push @f, [3.4, $squeeze->(1, 0)], [3.45, $snap], [3.65, $snap], [3.75, $rip], [4.0, posed($rip, ["chest", 4, 4, 0])], [4.4, $rest], [5.0, $rest];
	anim("ascend", 5.0, "once", \@f);
}

# drag (5.6 s): holding them by the throat he raises his right claw and slams it down at the ground (it splits open
# under them); lets go of their throat and holds them down with both hands spread over them while the vines from
# the pit drag them in, his arms jerking down with each heave; bends down low over them, takes their head in both
# hands and wrenches it round; straightens, his right hand sweeping up (the vines heave the body out); rests.
{
	my $a0 = throttled();
	my $raise = posed($a0, ["right_shoulder", -8, 0, -2], ["right_upper_arm", 166, 0, 18], ["right_forearm", 22], ["right_hand", -10], hand('right', -16, 22, -10),
		["chest", 6, 4, 0], ["head", 6, 6, 6], ["jaw", 24]);
	my $slam = posed($a0, ["right_shoulder", 4, 0, -2], ["right_upper_arm", 38, 0, 10], ["right_forearm", -4], ["right_hand", -24], hand('right', -24, 24, -14),
		["chest", -20, 6, 0], ["neck", -8], ["head", -16, 4, 6], ["jaw", 34]);
	my $press = sub {
		my ($down, $curl) = @_;    # $down: how hard the arms are thrust down (0..1); $curl: fingers
		return posed({},
			["left_shoulder", 2, 0, 2], ["left_upper_arm", 60 - 28 * $down, -6, -22], ["left_forearm", -6], ["left_hand", -20], hand('left', $curl, 18, $curl * 0.8),
			["right_shoulder", 2, 0, -2], ["right_upper_arm", 60 - 28 * $down, 6, 22], ["right_forearm", -6], ["right_hand", -20], hand('right', $curl, 18, $curl * 0.8),
			["chest", -16 - 8 * $down, 0, 0], ["neck", -6], ["head", -14 - 4 * $down, 0, 0], ["jaw", 20 + 16 * $down]);
	};
	my $bend = posed({}, ["hips_pos", 0, -5, 0], ["hips", -12], ["waist", -18], ["chest", -34], ["neck", -6], ["head", -8, 0, 0], ["jaw", 26],
		["left_upper_arm", 62, -18, -10], ["left_forearm", -18], ["left_hand", 10], hand('left', 44, 6, 40),
		["right_upper_arm", 62, 18, 10], ["right_forearm", -18], ["right_hand", 10], hand('right', 44, 6, 40));
	my $wrench = posed($bend, ["left_hand", 10, 46, 0], ["right_hand", 10, 46, 0], ["left_upper_arm", 62, -4, -28], ["right_upper_arm", 62, 34, 4],
		["chest", -34, 20, 0], ["head", -4, 10, 0], ["jaw", 36]);
	my $sweep = posed({}, ["right_upper_arm", 104, 10, 26], ["right_forearm", 14], ["right_hand", -18], hand('right', -18, 20, -10),
		["left_upper_arm", 14, 0, -16], ["left_forearm", 18], hand('left', 30, 6, 30), ["chest", 2, 10, 0], ["head", -4, 6, 0], ["jaw", 18]);
	my $rest = posed({}, ["left_upper_arm", 10, 0, -14], ["left_forearm", 16], hand('left', 26, 6, 30),
		["right_upper_arm", 12, 0, 14], ["right_forearm", 16], hand('right', 34, 6, 40), ["chest", -6], ["neck", -4], ["head", -16, 0, 4], ["jaw", 8]);
	my @f = ([0, $a0], [0.35, posed($raise, ["right_upper_arm", 120, 0, 24])], [0.52, $raise], [0.6, $slam], [0.75, posed($slam, ["chest", -16, 6, 0])],
		[1.25, posed($slam, ["left_upper_arm", 52, -14, -6])], [1.4, $press->(0, 10)]);
	# The heaves: at 1.5, 2.2, 2.8 s the arms jerk down hard and ease back.
	for my $h (1.5, 2.2, 2.8) {
		push @f, [$h - 0.08, $press->(0, 30)], [$h + 0.04, $press->(1, 60)], [$h + 0.3, $press->(0.35, 20)];
	}
	push @f, [3.2, $press->(0.2, 10)], [3.55, $bend], [3.75, posed($bend, ["left_hand", 10, -8, 0], ["right_hand", 10, -8, 0])], [3.8, $wrench], [3.95, $wrench],
		[4.15, posed($bend, ["chest", -20], ["waist", -10], ["hips_pos", 0, -2, 0])], [4.3, posed($sweep, ["right_upper_arm", 40, 10, 26])], [4.6, $sweep],
		[4.9, posed($sweep, ["right_upper_arm", 120, 10, 30])], [5.25, $rest], [5.6, $rest];
	anim("drag", 5.6, "once", \@f);
}

# locker_call (2.2 s): at the wardrobe. His right hand raised to it, palm out, fingers splayed, trembling; the
# fingers slowly close as if on something inside; a fist, and he yanks it back and down (the doors burst, the vines
# drag them out); then the left hand comes up and holds them there (as locker_hold).
my $lockerHold;
{
	my $h = {};
	setr($h, "left_shoulder", -4, 0, 6);
	setr($h, "left_upper_arm", 74, -12, -4);
	setr($h, "left_forearm", 4);
	setr($h, "left_hand", 8);
	fingers($h, 'left', 62, 8, 50);
	setr($h, "right_upper_arm", -6, 0, 16);
	setr($h, "right_forearm", 22);
	fingers($h, 'right', 30, 8, 30);
	setr($h, "chest", -8, -10, 0);
	setr($h, "neck", -4);
	setr($h, "head", -4, 8, 10);
	setr($h, "jaw", 16);
	$lockerHold = $h;
	my $reach = posed({}, ["right_shoulder", -6, 0, -2], ["right_upper_arm", 96, 4, 8], ["right_forearm", 0], ["right_hand", -22], hand('right', -18, 22, -12),
		["left_upper_arm", 8, 0, -18], ["left_forearm", 14], ["chest", -6, 8, 0], ["head", -2, 4, 12], ["jaw", 12]);
	my $grasp = sub {
		my ($k, $sh) = @_;
		return posed($reach, ["right_upper_arm", 96 + $sh, 4, 8], ["right_hand", -22 + $sh], hand('right', -18 + 70 * $k, 22 * (1 - $k), -12 + 70 * $k),
			["chest", -6 - 4 * $k, 8, 0], ["jaw", 12 + 14 * $k]);
	};
	my $yank = posed($reach, ["right_shoulder", 0, 0, -6], ["right_upper_arm", 28, 0, 30], ["right_forearm", 84], ["right_hand", 0], hand('right', 75, 6, 85),
		["chest", 12, -6, 0], ["neck", 6], ["head", 10, 0, 4], ["jaw", 34]);
	my @f = ([0, {}], [0.25, $reach]);
	my $i = 0;
	for (my $t = 0.4; $t < 1.0; $t += 0.1) {
		push @f, [$t, $grasp->(($t - 0.25) / 0.8, ($i++ % 2 ? 2.5 : -2.5))];
	}
	push @f, [1.02, posed($grasp->(1, 0), ["right_upper_arm", 120, 4, 8])], [1.12, $yank], [1.35, posed($yank, ["chest", 6, -6, 0])], [1.6, posed($lockerHold, ["left_upper_arm", 90, -12, -4])],
		[2.2, $lockerHold];
	anim("locker_call", 2.2, "once", \@f);
}

# locker_hold (loop): holding them pinned to the wardrobe by the vines, his left fist out at them, trembling with
# the strain, the head cocked, the jaw working.
{
	my @f;
	my $T = 1.6;
	for my $i (0 .. 8) {
		my $t = $T * $i / 8;
		my $u = $t / $T * 2 * $PI;
		my $sh = sin($u * 5) * 2 + sin($u * 9) * 1;
		push @f, [$t, posed($lockerHold, ["left_upper_arm", 74 + $sh, -12, -4 + $sh * 0.5], ["left_hand", 8 + $sh], ["jaw", 16 + 6 * sin($u * 2)],
			["head", -4 + $sh * 0.3, 8, 10 + 3 * sin($u)])];
	}
	anim("locker_hold", $T, "loop", \@f, "linear");
}

# locker_kill (4.5 s): the right claw closes over their face, the left arm drawn back; three times the vines go
# through them into the door (he jolts forward with each); he squeezes; flings them back into the wardrobe with a
# shove of the hand; straightens and waits; the head cocks as the doors creak open again.
{
	my $grip = posed({}, ["right_shoulder", -6, 0, 0], ["right_upper_arm", 92, -6, -6], ["right_forearm", 10], ["right_hand", 14], hand('right', 34, 14, 40),
		["left_shoulder", -4, 0, 6], ["left_upper_arm", 60, -10, -52], ["left_forearm", 34], ["left_hand", -10], hand('left', -14, 20, -10),
		["chest", -8, 6, 0], ["neck", -4], ["head", -8, 6, 6], ["jaw", 20]);
	my $jolt = posed($grip, ["chest", -14, 6, 0], ["jaw", 36], ["head", -4, 6, 6]);
	my $crush = sub {
		my ($k, $sh) = @_;
		return posed($grip, ["right_upper_arm", 92 + $sh, -6, -6], ["right_hand", 14 + $sh], hand('right', 34 + 40 * $k, 14 * (1 - $k), 40 + 40 * $k),
			["chest", -8 - 4 * $k, 6, 0], ["jaw", 20 + 12 * $k]);
	};
	my $shove = posed($grip, ["right_upper_arm", 118, 0, 0], ["right_forearm", -18], ["right_hand", -24], hand('right', -24, 24, -14),
		["chest", -16, 0, 0], ["head", -6, 0, 0], ["jaw", 30]);
	my $wait = posed({}, ["left_upper_arm", 10, 0, -14], ["left_forearm", 16], hand('left', 26, 6, 30), ["right_upper_arm", 14, 0, 14], ["right_forearm", 20],
		hand('right', 36, 6, 40), ["chest", -4], ["head", -6, 0, 0], ["jaw", 6]);
	my @f = ([0, $lockerHold], [0.6, $grip]);
	for my $hit (1.1, 1.4, 1.7) {
		push @f, [$hit - 0.06, $grip], [$hit, $jolt], [$hit + 0.12, $grip];
	}
	my $i = 0;
	for (my $t = 1.95; $t < 2.7; $t += 0.15) {
		push @f, [$t, $crush->(($t - 1.85) / 0.85, ($i++ % 2 ? 3 : -3))];
	}
	push @f, [2.72, $crush->(1, 0)], [2.8, $shove], [3.0, posed($shove, ["right_upper_arm", 100, 0, 0])], [3.3, $wait], [3.85, $wait],
		[4.0, posed($wait, ["head", -4, 10, 18], ["neck", -2, 6, 0])], [4.5, posed($wait, ["head", -10, 4, 14])];
	anim("locker_kill", 4.5, "once", \@f);
}

# ---------------------------------------------------------------- the file
sub png {
	my ($buf) = @_;
	$buf //= \@px;
	my $raw = '';
	for my $y (0 .. $TH - 1) {
		$raw .= "\0";
		$raw .= pack("C4", @{$buf->[$y * $TW + $_]}) for 0 .. $TW - 1;
	}
	my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
	return "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $TW, $TH, 8, 6, 0, 0, 0)) . $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
}

my $model = {
	meta => {format_version => "4.10", model_format => "modded_entity", box_uv => JSON::PP::true},
	name => "vecna", model_identifier => "vecna", modded_entity_entity_class => "", modded_entity_version => "Official",
	modded_entity_flip_y => JSON::PP::true, visible_box => [1, 3, 0], variable_placeholders => "", variable_placeholder_buttons => [],
	timeline_setups => [], unhandled_root_fields => {},
	resolution => {width => $UW, height => $UH},
	elements => \@elements,
	outliner => \@outliner,
	textures => [{path => "", name => "vecna.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH,
		uv_width => $UW, uv_height => $UH, particle => JSON::PP::false, use_as_default => JSON::PP::false, layers_enabled => JSON::PP::false,
		sync_to_project => "", render_mode => "default", render_sides => "auto", frame_time => 1, frame_order_type => "loop",
		frame_order => "", frame_interpolate => JSON::PP::false, visible => JSON::PP::true, internal => JSON::PP::true, saved => JSON::PP::true,
		uuid => uuid(), relative_path => "", source => "data:image/png;base64," . encode_base64(png(), "")}],
	animations => \@anims,
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;
if ($glowOut) {
	open my $g, ">:raw", $glowOut or die "$glowOut: $!";
	print $g png(\@gl);
	close $g;
}
printf "%s: %d cubes, %d bones (%d vine rods, %d tendril segments), %d animations, atlas used to row %d of %d\n", $out, scalar(@elements),
	scalar(keys %bones), $vineN, scalar(@TENDRILS), scalar(@anims), $shelfY + $shelfH, $UH;
