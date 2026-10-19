#!/usr/bin/perl
# A survivor as a Blockbench model (box UV), built to their own build: height, shoulders, chest or bust, waist, hips
# and seat, arms and legs (thin or muscled), hair in pieces of its own. The bones are a player's (head, body, arms,
# legs, with a player's pivots), so every pose the game gives a player carries over. Their colours (skin, hair, eyes,
# shirt, sleeves, trousers, shoes) are read from their skin (textures/entity/skin/<name>.png); the face is painted
# anew, two texels to a pixel. tools/bb2entity.pl turns the .bbmodel into the game's model.
#
#   perl tools/survivor_model.pl <name> art/survivor_<name>.bbmodel
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;

my ($name, $out) = @ARGV;
die "usage: survivor_model.pl <name> out.bbmodel\n" unless $out;

# ---------------------------------------------------------------- who is built how
# sh/wa/hi: half widths of chest, waist, hips; ch: chest depth; bust (women) or pecs (men); seat: how far the
# buttocks stand out; arm: arm thickness; mus: muscle (biceps, deltoids, calves); th: thigh thickness; hair: style.
my %BUILD = (
	laurie => {f => 1, sh => 3.5, wa => 3.0, hi => 3.5, ch => 4, bust => 1.0, seat => 0.5, arm => 3, mus => 0, th => 3.5, hair => 'shoulder', tall => 1.0},
	annie => {f => 1, sh => 3.5, wa => 3.0, hi => 4.0, ch => 4, bust => 1.5, seat => 1.0, arm => 3, mus => 0, th => 4.0, hair => 'long', tall => 1.0},
	lynda => {f => 1, sh => 3.5, wa => 3.0, hi => 3.5, ch => 4, bust => 1.5, seat => 1.0, arm => 3, mus => 0.5, th => 4.0, hair => 'ponytail', tall => 1.0},
	rachel => {f => 1, sh => 3.5, wa => 2.5, hi => 3.5, ch => 3.5, bust => 1.0, seat => 0.5, arm => 3, mus => 0, th => 3.5, hair => 'bob', tall => 1.0},
	tanya => {f => 1, sh => 3.0, wa => 2.5, hi => 3.0, ch => 3.5, bust => 0.5, seat => 0.5, arm => 3, mus => 0, th => 3.0, hair => 'pigtails', tall => 0.92},
	jennifer => {f => 1, sh => 4.0, wa => 3.5, hi => 4.5, ch => 4.5, bust => 2.0, seat => 1.5, arm => 3.5, mus => 0, th => 4.5, hair => 'curly', tall => 1.0},
	alexis => {f => 1, sh => 3.5, wa => 2.5, hi => 3.5, ch => 3.5, bust => 1.0, seat => 1.0, arm => 3, mus => 0, th => 3.5, hair => 'long', tall => 1.05},
	bob => {f => 0, sh => 4.5, wa => 4.0, hi => 4.0, ch => 4.5, pecs => 0.5, seat => 0.5, arm => 4.5, mus => 0.5, th => 4.5, hair => 'short', tall => 1.04},
	eric => {f => 0, sh => 5.0, wa => 4.0, hi => 4.0, ch => 4.5, pecs => 1.0, seat => 0.5, arm => 4.5, mus => 1.0, th => 4.5, hair => 'short', tall => 1.03},
	marcus => {f => 0, sh => 4.5, wa => 3.5, hi => 3.5, ch => 4, pecs => 0.5, seat => 0.5, arm => 4, mus => 1.0, th => 4.0, hair => 'buzz', tall => 1.02},
	thomas => {f => 0, sh => 4.0, wa => 3.0, hi => 3.5, ch => 3.5, pecs => 0, seat => 0.5, arm => 3.5, mus => 0, th => 3.5, hair => 'messy', tall => 0.97},
	richard => {f => 0, sh => 4.5, wa => 4.5, hi => 4.5, ch => 5, pecs => 0, seat => 0.5, arm => 4, mus => 0, th => 4.5, hair => 'receding', tall => 1.0, belly => 1.5},
);
my $B = $BUILD{$name} or die "unknown survivor $name\n";

# ---------------------------------------------------------------- their colours, from their skin
my $root = $0;
$root =~ s{tools[/\\][^/\\]+$}{};
my $skinFile = "${root}src/main/resources/assets/halloween/textures/entity/skin/$name.png";
my ($SW, $SHt, @skin) = readPng($skinFile);

sub readPng {
	my $f = shift;
	open my $fh, '<:raw', $f or die "$f: $!";
	local $/;
	my $d = <$fh>;
	close $fh;
	my ($w, $h, $ct, $idat) = (0, 0, 0, '');
	my $p = 8;
	while ($p < length $d) {
		my $len = unpack("N", substr($d, $p, 4));
		my $type = substr($d, $p + 4, 4);
		my $data = substr($d, $p + 8, $len);
		if ($type eq 'IHDR') {
			($w, $h) = unpack("NN", $data);
			$ct = ord(substr($data, 9, 1));
		} elsif ($type eq 'IDAT') {
			$idat .= $data;
		}
		$p += 12 + $len;
	}
	my $raw = uncompress($idat);
	my $bpp = $ct == 6 ? 4 : 3;
	my @rows;
	my $prev = "\0" x ($w * $bpp);
	for my $y (0 .. $h - 1) {
		my $line = substr($raw, $y * ($w * $bpp + 1), $w * $bpp + 1);
		my $ft = ord(substr($line, 0, 1));
		my @c = unpack("C*", substr($line, 1));
		my @pv = unpack("C*", $prev);
		for my $i (0 .. $#c) {
			my $a = $i >= $bpp ? $c[$i - $bpp] : 0;
			my $b = $pv[$i];
			my $cc = $i >= $bpp ? $pv[$i - $bpp] : 0;
			if ($ft == 1) { $c[$i] = ($c[$i] + $a) & 255; }
			elsif ($ft == 2) { $c[$i] = ($c[$i] + $b) & 255; }
			elsif ($ft == 3) { $c[$i] = ($c[$i] + int(($a + $b) / 2)) & 255; }
			elsif ($ft == 4) {
				my $pp = $a + $b - $cc;
				my ($pa, $pb, $pc) = (abs($pp - $a), abs($pp - $b), abs($pp - $cc));
				$c[$i] = ($c[$i] + ($pa <= $pb && $pa <= $pc ? $a : $pb <= $pc ? $b : $cc)) & 255;
			}
		}
		$prev = pack("C*", @c);
		push @rows, [@c];
	}
	my @px;
	for my $y (0 .. $h - 1) {
		for my $x (0 .. $w - 1) {
			my @v = @{$rows[$y]}[$x * $bpp .. $x * $bpp + $bpp - 1];
			push @v, 255 if $bpp == 3;
			push @px, \@v;
		}
	}
	return ($w, $h, @px);
}

# The most common colour in a region of the skin (opaque pixels), a little rounded so near shades count together.
sub sample {
	my ($x0, $y0, $w, $h) = @_;
	my %n;
	my %sum;
	for my $y ($y0 .. $y0 + $h - 1) {
		for my $x ($x0 .. $x0 + $w - 1) {
			my $p = $skin[$y * $SW + $x];
			next unless $p && $p->[3] > 128;
			my $k = join(",", map { int($_ / 24) } @$p[0 .. 2]);
			$n{$k}++;
			$sum{$k}[$_] += $p->[$_] for 0 .. 2;
		}
	}
	my ($best) = sort { $n{$b} <=> $n{$a} } keys %n;
	return [128, 128, 128] unless $best;
	return [map { int($sum{$best}[$_] / $n{$best}) } 0 .. 2];
}

sub darker { my ($c, $k) = @_; return [map { int($_ * $k) } @$c]; }
sub lighter { my ($c, $k) = @_; return [map { my $v = $_ + (255 - $_) * $k; int($v) } @$c]; }
sub near { my ($a, $b) = @_; my $d = 0; $d += abs($a->[$_] - $b->[$_]) for 0 .. 2; return $d < 60; }

my $hairC = sample(8, 0, 8, 8);
my $skinC = sample(9, 11, 6, 4);
my $shirtC = sample(20, 20, 8, 8);
my $sleeveC = sample(44, 20, 4, 4);
my $lowArmC = sample(44, 28, 4, 3);
my $pantsC = sample(4, 20, 4, 6);
my $shinC = sample(4, 27, 4, 3);
my $shoeC = sample(4, 30, 4, 2);
my $eyeC = [70, 90, 110];
# Their eyes: the colour on the face's eye row that is neither skin nor white.
for my $x (9 .. 14) {
	my $p = $skin[12 * $SW + $x];
	if ($p && !near($p, $skinC) && $p->[0] + $p->[1] + $p->[2] < 600) {
		$eyeC = [@$p[0 .. 2]];
		last;
	}
}
my $shortSleeves = near($lowArmC, $skinC);
my $shorts = near($shinC, $skinC);

my ($UW, $UH) = (128, 128);
my $S = 2;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1978 + length($name) * 31 + ord($name));

my %C = (
	skin => $skinC, skinD => darker($skinC, 0.85), skinDD => darker($skinC, 0.7), skinL => lighter($skinC, 0.12),
	hair => $hairC, hairD => darker($hairC, 0.75), hairL => lighter($hairC, 0.15),
	shirt => $shirtC, shirtD => darker($shirtC, 0.8), shirtL => lighter($shirtC, 0.1),
	sleeve => $sleeveC, sleeveD => darker($sleeveC, 0.8),
	pants => $pantsC, pantsD => darker($pantsC, 0.78), pantsL => lighter($pantsC, 0.1),
	shoe => $shoeC, shoeD => darker($shoeC, 0.6), sole => [30, 28, 26],
	eye => $eyeC, white => [232, 230, 224], pupil => [20, 18, 18], brow => darker($hairC, 0.7),
	lip => $B->{f} ? [176, 78, 82] : darker($skinC, 0.78), lipD => $B->{f} ? [140, 56, 60] : darker($skinC, 0.68), blush => [214, 140, 128],
);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v); }
sub put {
	my ($x, $y, $c, $a) = @_;
	return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH;
	$px[$y * $TW + $x] = [@$c[0 .. 2], $a // 255];
}
sub jit { my ($c, $n) = @_; my $d = int(rand(2 * $n + 1)) - $n; return [map { clamp($_ + $d) } @$c]; }
sub at { my ($r, $i, $j, $c, $n) = @_; return if $i < 0 || $j < 0 || $i >= $r->[2] || $j >= $r->[3]; put($r->[0] + $i, $r->[1] + $j, $n ? jit($C{$c}, $n) : $C{$c}); }
sub fill {
	my ($r, $c, $n, $x0, $y0, $w, $h) = @_;
	($x0, $y0, $w, $h) = (0, 0, $r->[2], $r->[3]) unless defined $x0;
	for my $j (0 .. $h - 1) { for my $i (0 .. $w - 1) { at($r, $x0 + $i, $y0 + $j, $c, $n); } }
}
sub clear { my ($r, $i, $j) = @_; put($r->[0] + $i, $r->[1] + $j, [0, 0, 0], 0); }
# Cloth or skin over a whole cube, a little uneven.
sub paint { my ($R, $c, $n) = @_; fill($R->{$_}, $c, $n // 3) for qw(east north west south up down); }

my %strand;
sub hairpx {
	my ($r, $i, $j) = @_;
	my $k = "$r->[0],$r->[1],$i";
	$strand{$k} //= int(rand(13)) - 6;
	my $c = rand() < 0.14 ? 'hairD' : rand() < 0.1 ? 'hairL' : 'hair';
	put($r->[0] + $i, $r->[1] + $j, [map { clamp($_ + $strand{$k}) } @{jit($C{$c}, 3)}]);
}
sub hairAll { my $R = shift; for my $f (qw(east north west south up down)) { my $r = $R->{$f}; for my $j (0 .. $r->[3] - 1) { hairpx($r, $_, $j) for 0 .. $r->[2] - 1; } } }

# ---------------------------------------------------------------- the model
my $uuidN = 0;
sub uuid { return sprintf("%08x-%04x-4%03x-a%03x-%012x", int(rand(0xffffffff)), int(rand(0xffff)), int(rand(0xfff)), int(rand(0xfff)), ++$uuidN); }
my (@elements, @outliner, %bones);
sub bone {
	my ($bn, $origin) = @_;
	my $b = {name => $bn, origin => $origin, color => 0, uuid => uuid(), export => JSON::PP::true, mirror_uv => JSON::PP::false,
		isOpen => JSON::PP::true, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0, children => []};
	$bones{$bn} = $b;
	push @outliner, $b;
	return $b;
}

# Box UV, packed on shelves as they come (every size a multiple of half a unit: whole texels at two to a unit).
my ($packX, $packY, $shelf) = (0, 0, 0);
sub place {
	my ($w, $h) = @_;
	if ($packX + $w > $UW) {
		$packX = 0;
		$packY += $shelf;
		$shelf = 0;
	}
	die "texture full\n" if $packY + $h > $UH;
	my @at = ($packX, $packY);
	$packX += $w + 0.5;
	$shelf = $h + 0.5 if $h + 0.5 > $shelf;
	return \@at;
}

sub cube {
	my ($bone, $cn, $from, $to, $inflate) = @_;
	my ($w, $h, $d) = map { $to->[$_] - $from->[$_] } 0 .. 2;
	my $uv = place(2 * $d + 2 * $w, $d + $h);
	my ($u, $v) = @$uv;
	push @elements, {
		name => $cn, box_uv => JSON::PP::true, rescale => JSON::PP::false, locked => JSON::PP::false, render_order => "default",
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
	my $r = sub { my ($x, $y, $ww, $hh) = @_; return [int($x * $S + 0.5), int($y * $S + 0.5), int($ww * $S + 0.5), int($hh * $S + 0.5)]; };
	return {
		up => $r->($u + $d, $v, $w, $d), down => $r->($u + $d + $w, $v, $w, $d),
		east => $r->($u, $v + $d, $d, $h), north => $r->($u + $d, $v + $d, $w, $h),
		west => $r->($u + $d + $w, $v + $d, $d, $h), south => $r->($u + 2 * $d + $w, $v + $d, $w, $h),
	};
}

my $body = bone("body", [0, 24, 0]);
my $head = bone("head", [0, 24, 0]);
my $rArm = bone("right_arm", [5, 22, 0]);
my $lArm = bone("left_arm", [-5, 22, 0]);
my $rLeg = bone("right_leg", [1.9, 12, 0]);
my $lLeg = bone("left_leg", [-1.9, 12, 0]);
my $f = $B->{f};

# ---------------------------------------------------------------- the head and face
my $Hd = cube($head, "head", [-3.5, 24, -3.5], [3.5, 31.5, 3.5]);
paint($Hd, 'skin', 3);
fill($Hd->{down}, 'skinD', 2);
{
	# The face, 14 x 15 texels: brows, eyes (white, iris, a pupil, lid shadow), the nose's shading, cheeks, mouth.
	my $fc = $Hd->{north};
	my $ey = 6;
	for my $side (0, 1) {
		my $x = $side ? 8 : 2;
		fill($fc, 'brow', 2, $x, $ey - 2, 4, 1);
		at($fc, $side ? $x + 4 : $x - 1, $ey - 2 + ($f ? 1 : 0), 'brow', 2) if !$f;
		fill($fc, 'white', 2, $x, $ey, 4, 2);
		fill($fc, 'eye', 4, $x + ($side ? 1 : 1), $ey, 2, 2);
		at($fc, $x + ($side ? 1 : 2), $ey, 'pupil', 2);
		fill($fc, 'skinD', 2, $x, $ey - 1, 4, 1);
		if ($f) {
			# Lashes.
			at($fc, $side ? $x + 3 : $x, $ey - 1, 'pupil', 0);
			at($fc, $side ? $x + 4 : $x - 1, $ey, 'pupil', 0);
		}
		fill($fc, 'skinD', 2, $x, $ey + 2, 4, 1);
	}
	# The nose between the eyes and down, shadowed under.
	fill($fc, 'skinD', 2, 6, $ey + 1, 2, 3);
	fill($fc, 'skinDD', 1, 6, $ey + 4, 2, 1);
	if ($f) {
		fill($fc, 'blush', 3, 2, $ey + 3, 2, 1);
		fill($fc, 'blush', 3, 10, $ey + 3, 2, 1);
	}
	# The mouth.
	fill($fc, 'lip', 3, 5, $ey + 6, 4, 1);
	fill($fc, 'lipD', 2, 5, $ey + 7, 4, 1) if $f;
	at($fc, 4, $ey + 6, 'skinDD', 1);
	at($fc, 9, $ey + 6, 'skinDD', 1);
	# The jaw, darker towards the neck (stubble on the men).
	fill($fc, $f ? 'skinD' : 'skinDD', $f ? 2 : 5, 2, 13, 10, 2) ;
	fill($fc, 'skinD', 2, 0, 0, 1, 15);
	fill($fc, 'skinD', 2, 13, 0, 1, 15);
}
for my $side (qw(east west)) {
	# The ear.
	fill($Hd->{$side}, 'skinD', 2, 6, 5, 3, 5);
	fill($Hd->{$side}, 'skinDD', 1, 7, 6, 1, 3);
}
my $Ns = cube($head, "nose", [-0.5, 26.5, -4], [0.5, 28, -3.5]);
paint($Ns, 'skin', 2);
fill($Ns->{down}, 'skinDD', 1);
my $Nk = cube($head, "neck", [-1.5, 23, -1.5], [1.5, 24.5, 1.5]);
paint($Nk, 'skinD', 2);

# ---------------------------------------------------------------- hair
my $style = $B->{hair};
# The cap over the head: the crown, the back, the sides down to the ears, a hairline at the front.
my $Hc = cube($head, "hair", [-3.5, 24, -3.5], [3.5, 31.5, 3.5], $style eq 'buzz' ? 0.1 : 0.35);
{
	my $r;
	$r = $Hc->{up};
	for my $j (0 .. $r->[3] - 1) { for my $i (0 .. $r->[2] - 1) { $style eq 'receding' && $j < 6 && $i > 3 && $i < 10 ? clear($r, $i, $j) : hairpx($r, $i, $j); } }
	$r = $Hc->{north};
	my $line = $style eq 'receding' ? 0 : $style eq 'buzz' ? 1 : $style eq 'messy' || $style eq 'curly' ? 4 : 2;
	for my $i (0 .. $r->[2] - 1) {
		my $len = $line + (rand() < 0.4 ? 1 : 0);
		for my $j (0 .. $r->[3] - 1) { $j < $len ? hairpx($r, $i, $j) : clear($r, $i, $j); }
	}
	for my $sd (qw(east west)) {
		$r = $Hc->{$sd};
		for my $i (0 .. $r->[2] - 1) {
			my $front = $sd eq 'east' ? $i / ($r->[2] - 1) : 1 - $i / ($r->[2] - 1);
			my $len = $style eq 'buzz' ? ($front > 0.7 ? 3 : 7) : $front > 0.75 ? 4 : $front > 0.45 ? 7 : 11;
			$len = 15 if $f && $style ne 'bob' && $style ne 'pigtails';
			for my $j (0 .. $r->[3] - 1) { $j < $len ? hairpx($r, $i, $j) : clear($r, $i, $j); }
		}
	}
	$r = $Hc->{south};
	for my $i (0 .. $r->[2] - 1) { for my $j (0 .. $r->[3] - 1) { $j < ($style eq 'buzz' ? 10 : 14) ? hairpx($r, $i, $j) : clear($r, $i, $j); } }
	$r = $Hc->{down};
	clear($r, $_ % $r->[2], int($_ / $r->[2])) for 0 .. $r->[2] * $r->[3] - 1;
}
if ($style eq 'shoulder' || $style eq 'long') {
	# Down the back to the shoulders (long: past them), the sides framing the face, a fringe swept aside.
	my $low = $style eq 'long' ? 17.5 : 20.5;
	hairAll(cube($head, "hair_back", [-4, $low, 2.5], [4, 30, 4.5]));
	hairAll(cube($head, "hair_side_r", [3.5, $low + 1, -2.5], [4.5, 29.5, 3]));
	hairAll(cube($head, "hair_side_l", [-4.5, $low + 1, -2.5], [-3.5, 29.5, 3]));
	hairAll(cube($head, "fringe", [-3.5, 29.5, -4.5], [1.5, 31, -3.5]));
} elsif ($style eq 'ponytail') {
	hairAll(cube($head, "tie", [-1, 28, 3.5], [1, 30, 4.5]));
	hairAll(cube($head, "ponytail", [-1, 20.5, 4], [1, 28.5, 5.5]));
	hairAll(cube($head, "fringe", [-3.5, 30, -4.5], [3.5, 31, -3.5]));
} elsif ($style eq 'bob') {
	hairAll(cube($head, "bob_back", [-4, 25, 2], [4, 31, 4.5]));
	hairAll(cube($head, "bob_r", [3.5, 25, -3], [4.5, 30.5, 3]));
	hairAll(cube($head, "bob_l", [-4.5, 25, -3], [-3.5, 30.5, 3]));
	hairAll(cube($head, "fringe", [-3.5, 29.5, -4.5], [3.5, 31, -3.5]));
} elsif ($style eq 'pigtails') {
	for my $s (1, -1) {
		hairAll(cube($head, "pigtail_" . ($s > 0 ? 'r' : 'l'), [$s > 0 ? 3.5 : -5, 23, 0], [$s > 0 ? 5 : -3.5, 28.5, 1.5]));
	}
	hairAll(cube($head, "fringe", [-3.5, 30, -4.5], [3.5, 31, -3.5]));
} elsif ($style eq 'curly') {
	hairAll(cube($head, "curls_top", [-4.5, 30, -4], [4.5, 33, 4.5]));
	hairAll(cube($head, "curls_back", [-5, 20.5, 1], [5, 31, 5]));
	hairAll(cube($head, "curls_r", [3.5, 22, -3], [5, 31, 1.5]));
	hairAll(cube($head, "curls_l", [-5, 22, -3], [-3.5, 31, 1.5]));
} elsif ($style eq 'short') {
	hairAll(cube($head, "quiff", [-3.5, 31, -3.5], [3.5, 32.5, 2.5]));
} elsif ($style eq 'messy') {
	hairAll(cube($head, "mop", [-4, 30.5, -4], [4, 33, 4]));
	hairAll(cube($head, "fringe", [-3.5, 28.5, -4.5], [2, 30.5, -3.5]));
}

# ---------------------------------------------------------------- the body
my ($sh, $wa, $hi, $ch) = @$B{qw(sh wa hi ch)};
my $chest = cube($body, "chest", [-$sh, 18.5, -$ch / 2], [$sh, 24, $ch / 2]);
my $waist = cube($body, "waist", [-$wa, 15.5, -($ch - 0.5) / 2 - ($B->{belly} // 0)], [$wa, 18.5, ($ch - 0.5) / 2]);
my $hips = cube($body, "hips", [-$hi, 12, -$ch / 2], [$hi, 15.5, $ch / 2]);
paint($chest, 'shirt', 3);
paint($waist, 'shirt', 3);
paint($hips, 'pants', 3);
# The collar at the neck, a seam down the sides, the shirt's hem over the waistband, the waistband and its belt.
fill($chest->{north}, 'skin', 2, int($chest->{north}[2] / 2) - 2, 0, 4, 2);
fill($chest->{north}, 'shirtD', 2, int($chest->{north}[2] / 2) - 3, 2, 6, 1);
fill($waist->{$_}, 'shirtD', 2, 0, $waist->{$_}[3] - 1, $waist->{$_}[2], 1) for qw(north south east west);
fill($hips->{$_}, 'pantsD', 2, 0, 0, $hips->{$_}[2], 2) for qw(north south east west);
fill($hips->{north}, 'pantsD', 2, int($hips->{north}[2] / 2), 2, 1, 5) unless $f;
if ($f && $B->{bust}) {
	# The bust, two rounded shapes under the shirt.
	my $bd = $B->{bust};
	for my $s (1, -1) {
		my $x0 = $s > 0 ? 0.25 : -3.25;
		my $Bu = cube($body, "bust_" . ($s > 0 ? 'r' : 'l'), [$x0, 19, -$ch / 2 - $bd], [$x0 + 3, 22, -$ch / 2]);
		paint($Bu, 'shirt', 3);
		fill($Bu->{down}, 'shirtD', 2);
		my $Bl = cube($body, "bust_low_" . ($s > 0 ? 'r' : 'l'), [$x0 + 0.5, 18.5, -$ch / 2 - $bd + 0.5], [$x0 + 2.5, 19, -$ch / 2]);
		paint($Bl, 'shirtD', 2);
	}
} elsif (!$f && $B->{pecs}) {
	my $Pe = cube($body, "pecs", [-$sh + 0.5, 20, -$ch / 2 - $B->{pecs}], [$sh - 0.5, 23, -$ch / 2]);
	paint($Pe, 'shirt', 3);
	fill($Pe->{north}, 'shirtD', 2, int($Pe->{north}[2] / 2), 0, 1, $Pe->{north}[3]);
	fill($Pe->{down}, 'shirtD', 2);
}
if ($B->{belly}) {
	my $Bl = cube($body, "belly", [-$wa + 0.5, 14.5, -$ch / 2 - $B->{belly}], [$wa - 0.5, 18.5, -$ch / 2 + 0.5]);
	paint($Bl, 'shirt', 3);
	fill($Bl->{down}, 'shirtD', 2);
}

# ---------------------------------------------------------------- arms
my $aw = $B->{arm};
sub arm {
	my ($bone, $side) = @_;
	my $s = $side eq 'right' ? 1 : -1;
	# Out from the shoulder's edge (wide shoulders put the arm further out).
	my $x0 = $s > 0 ? $sh : -$sh - $aw;
	my $x1 = $x0 + $aw;
	my $up = cube($bone, "upper_arm_$side", [$x0, 18, -$aw / 2], [$x1, 24, $aw / 2]);
	my $fo = cube($bone, "forearm_$side", [$x0 + 0.25, 14.5, -$aw / 2 + 0.25], [$x1 - 0.25, 18, $aw / 2 - 0.25]);
	my $ha = cube($bone, "hand_$side", [$x0 + 0.25, 12, -$aw / 2 + 0.25], [$x1 - 0.25, 14.5, $aw / 2 - 0.25]);
	paint($up, 'sleeve', 3);
	fill($up->{$_}, 'sleeveD', 2, 0, $up->{$_}[3] - 1, $up->{$_}[2], 1) for qw(north south east west);
	if ($shortSleeves) {
		# Bare from just above the elbow.
		fill($up->{$_}, 'skin', 3, 0, int($up->{$_}[3] * 0.55), $up->{$_}[2], $up->{$_}[3]) for qw(north south east west);
		fill($up->{down}, 'skin', 3);
		paint($fo, 'skin', 3);
	} else {
		paint($fo, 'sleeve', 3);
		fill($fo->{$_}, 'sleeveD', 2, 0, $fo->{$_}[3] - 1, $fo->{$_}[2], 1) for qw(north south east west);
	}
	paint($ha, 'skin', 3);
	fill($ha->{down}, 'skinD', 2);
	for my $i (1, 3, 5) { at($ha->{north}, $i, $_, 'skinD', 2) for 2 .. 4; }
	if ($B->{mus}) {
		# Deltoid over the shoulder, the biceps in front.
		my $m = $B->{mus};
		my $De = cube($bone, "deltoid_$side", [$x0 - 0.25, 21.5, -$aw / 2 - 0.25], [$x1 + 0.25, 24.5, $aw / 2 + 0.25]);
		paint($De, $shortSleeves ? 'sleeve' : 'sleeve', 3);
		my $Bi = cube($bone, "biceps_$side", [$x0 + 0.5, 18.5, -$aw / 2 - $m * 0.5], [$x1 - 0.5, 21.5, -$aw / 2]);
		paint($Bi, $shortSleeves ? 'skin' : 'sleeve', 3);
	}
}
arm($rArm, 'right');
arm($lArm, 'left');

# ---------------------------------------------------------------- legs
my $tw = $B->{th};
sub leg {
	my ($bone, $side) = @_;
	my $s = $side eq 'right' ? 1 : -1;
	# Thighs side by side under the hips, a little gap between; narrower down to the knee and the calf.
	my $xa = $s > 0 ? $hi - $tw : -$hi;
	my $xb = $xa + $tw;
	my $th = cube($bone, "thigh_$side", [$xa, 6.5, -$tw / 2], [$xb, 12, $tw / 2]);
	my $cw = $tw - 0.5;
	my $cx = ($xa + $xb) / 2;
	my $ca = cube($bone, "calf_$side", [$cx - $cw / 2, 1.5, -$cw / 2], [$cx + $cw / 2, 6.5, $cw / 2]);
	my $sw = $cw;
	my $shoe = cube($bone, "shoe_$side", [$cx - $sw / 2, 0, -$sw / 2 - 1], [$cx + $sw / 2, 1.5, $sw / 2]);
	paint($th, 'pants', 3);
	fill($th->{$_}, 'pantsD', 2, 0, 0, $th->{$_}[2], 1) for qw(north south east west);
	if ($shorts) {
		fill($th->{$_}, 'skin', 3, 0, int($th->{$_}[3] * 0.5), $th->{$_}[2], $th->{$_}[3]) for qw(north south east west);
		paint($ca, 'skin', 3);
	} else {
		paint($ca, 'pants', 3);
		fill($ca->{$_}, 'pantsD', 2, 0, 0, $ca->{$_}[2], 1) for qw(north south east west);
	}
	paint($shoe, 'shoe', 3);
	fill($shoe->{$_}, 'sole', 2, 0, $shoe->{$_}[3] - 1, $shoe->{$_}[2], 1) for qw(north south east west);
	fill($shoe->{down}, 'sole', 2);
	fill($shoe->{up}, 'shoeD', 2, 0, 0, $shoe->{up}[2], 2);
	if ($B->{seat}) {
		# The seat: rounded out behind the top of the thigh.
		my $sd = $B->{seat};
		my $Gl = cube($bone, "seat_$side", [$xa + 0.25, 8.5, $tw / 2], [$xb - 0.25, 12.5, $tw / 2 + $sd]);
		paint($Gl, 'pants', 3);
		fill($Gl->{down}, 'pantsD', 2);
	}
	if ($B->{mus}) {
		my $m = $B->{mus};
		my $Cm = cube($bone, "calf_muscle_$side", [$cx - $cw / 2 + 0.5, 3, $cw / 2], [$cx + $cw / 2 - 0.5, 5.5, $cw / 2 + $m * 0.5]);
		paint($Cm, $shorts ? 'skin' : 'pants', 3);
	}
}
leg($rLeg, 'right');
leg($lLeg, 'left');

# ---------------------------------------------------------------- the file
sub png {
	my $raw = '';
	for my $y (0 .. $TH - 1) { $raw .= "\0"; $raw .= pack("C4", @{$px[$y * $TW + $_]}) for 0 .. $TW - 1; }
	my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
	return "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $TW, $TH, 8, 6, 0, 0, 0)) . $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
}
my $model = {
	meta => {format_version => "4.10", model_format => "modded_entity", box_uv => JSON::PP::true},
	name => $name, model_identifier => $name, modded_entity_entity_class => "", modded_entity_version => "Official",
	modded_entity_flip_y => JSON::PP::true, visible_box => [1, 2, 0], variable_placeholders => "", variable_placeholder_buttons => [],
	timeline_setups => [], unhandled_root_fields => {}, resolution => {width => $UW, height => $UH},
	elements => \@elements, outliner => \@outliner,
	textures => [{path => "", name => "$name.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH,
		uv_width => $UW, uv_height => $UH, particle => JSON::PP::false, use_as_default => JSON::PP::false, layers_enabled => JSON::PP::false,
		sync_to_project => "", render_mode => "default", render_sides => "auto", frame_time => 1, frame_order_type => "loop",
		frame_order => "", frame_interpolate => JSON::PP::false, visible => JSON::PP::true, internal => JSON::PP::true, saved => JSON::PP::true,
		uuid => uuid(), relative_path => "", source => "data:image/png;base64," . encode_base64(png(), "")}],
	animations => [],
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;
printf "%s: %d cubes, sleeves %s, %s; hair %s, skin %s, shirt %s, trousers %s, eyes %s\n", $out, scalar(@elements),
	$shortSleeves ? "short" : "long", $shorts ? "shorts" : "trousers", join(",", @$hairC), join(",", @$skinC), join(",", @$shirtC), join(",", @$pantsC), join(",", @$eyeC);
