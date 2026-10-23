#!/usr/bin/perl
# The dog of the side job (game.SideJobs) as a Blockbench model: writes a .bbmodel (box UV) that opens in Blockbench
# and that tools/bb2entity.pl turns into the game's model (client.DogRenderer moves it).
#
# A big guard dog, rottweiler and dobermann in one: black with rust-tan points (muzzle, brows, chest, legs), short
# coarse coat, old scars on the head and flank, a deep chest and tucked belly, heavy jaw and cheeks, ears cropped and
# standing, a raised ridge of hackles down the back; the lips drawn back off the teeth (long canines), amber eyes
# under a furrowed brow, a wet nose; a studded collar with the broken end of a chain hanging from it.
# Built at 16 units a block (a little over 1.1 blocks at the shoulder); the game draws it at DogRenderer.size.
#
# The animations (Blockbench's own, sampled by the game):
#   idle   standing tense, breathing, the head turning a little, the ears twitching
#   walk   a stalking walk, the diagonal legs together
#   run    a gallop, the body rocking
#   growl  laid over the others: head low, lips up, jaw working, ears back, hackles up, crouched
#   bark   laid over the others: the jaw snapping open twice, the head jerking up
#
#   perl tools/dog_model.pl art/dog.bbmodel
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;
use POSIX qw(ceil);

my $out = shift or die "usage: dog_model.pl out.bbmodel\n";
my ($UW, $UH) = (128, 72);
my $S = 6;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1978);
my $PI = 3.14159265358979;

# ---------------------------------------------------------------- colours
my %C = (
	black => [24, 21, 20], blackL => [52, 46, 42], blackD => [10, 9, 9], tan => [138, 72, 34], tanL => [176, 104, 54], tanD => [92, 46, 22],
	scar => [150, 92, 88], scarD => [96, 50, 50], skin => [62, 40, 40], gum => [70, 22, 30], gumL => [130, 52, 62], tongue => [170, 70, 82],
	tooth => [226, 214, 186], toothD => [168, 146, 104], nose => [16, 14, 15], noseL => [120, 118, 122], eye => [196, 128, 28], eyeL => [240, 190, 70],
	pupil => [8, 6, 6], leather => [44, 30, 22], leatherL => [76, 54, 38], steel => [150, 150, 156], steelD => [72, 72, 78], steelL => [214, 214, 220],
	claw => [26, 24, 24], clawL => [80, 76, 72], palate => [52, 18, 24],
);

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }

sub put {
	my ($x, $y, $c) = @_;
	return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH;
	$px[$y * $TW + $x] = [map({ clamp($_) } @$c[0 .. 2]), 255];
}

sub px_at { my ($x, $y) = @_; my $p = $px[$y * $TW + $x]; return [@$p[0 .. 2]]; }

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

sub smooth { my $t = shift; $t = $t < 0 ? 0 : $t > 1 ? 1 : $t; return $t * $t * (3 - 2 * $t); }

# ---------------------------------------------------------------- texture painting
# Coat on a face's rectangle [x, y, w, h] (texels): short coarse hair in strokes ($along 'v': running down the face,
# 'h': along it), black, tan where $tan->(u, v) says (u, v 0..1 across and down the face), soft edges between.
sub coat {
	my ($r, %o) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	my $tan = $o{tan} // sub { 0 };
	my $along = $o{along} // 'v';
	my $shade = $o{shade} // 1;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my ($u, $v) = ($w > 1 ? $i / ($w - 1) : 0.5, $h > 1 ? $j / ($h - 1) : 0.5);
			my $t = $tan->($u, $v);
			my $c = mix($C{black}, $C{tan}, $t);
			# Hair: streaks along the lie of the coat, a sheen now and then, darker roots.
			my $s = $along eq 'v' ? sin($i * 1.9 + sin($j * 0.35) * 2) : sin($j * 1.9 + sin($i * 0.35) * 2);
			if ($s > 0.75) {
				$c = mix($c, $t > 0.5 ? $C{tanL} : $C{blackL}, 0.45);
			} elsif ($s < -0.8) {
				$c = mix($c, $t > 0.5 ? $C{tanD} : $C{blackD}, 0.5);
			}
			$c = mix($c, $C{blackL}, 0.35) if rand() < 0.04;
			put($x0 + $i, $y0 + $j, jit(mul($c, $shade), 4));
		}
	}
}

# An old scar across a face: a pale pink seam with darker edges and the hair gone round it.
sub scar {
	my ($r, $fx, $fy, $tx, $ty) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	my $n = int(sqrt((($tx - $fx) * $w) ** 2 + (($ty - $fy) * $h) ** 2)) + 1;
	for my $k (0 .. $n) {
		my $t = $k / $n;
		my $x = $x0 + int(($fx + ($tx - $fx) * $t) * ($w - 1) + sin($t * 9) * 1.2);
		my $y = $y0 + int(($fy + ($ty - $fy) * $t) * ($h - 1));
		put($x, $y, jit($C{scar}, 6));
		put($x + 1, $y, jit($C{scarD}, 6));
		put($x - 1, $y, jit(mix(px_at($x - 1, $y), $C{skin}, 0.6), 4)) if $x > $x0;
		put($x, $y + 1, jit(mix(px_at($x, $y + 1), $C{skin}, 0.5), 4)) if $y + 1 < $y0 + $h;
	}
}

sub solid {
	my ($r, $c, $n) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			put($x0 + $i, $y0 + $j, jit($c, $n // 4));
		}
	}
}

# A row of teeth along a face: pale, a dark gap between each, darker at the gum ($gumAt 'top' or 'bottom').
sub teeth {
	my ($r, $gumAt) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	my $tw = $S * 0.55;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $gap = ($i % int($tw)) == 0;
			my $v = $h > 1 ? $j / ($h - 1) : 0.5;
			my $root = $gumAt eq 'top' ? $v : 1 - $v;
			my $c = $gap ? $C{gum} : mix($C{toothD}, $C{tooth}, smooth($root * 1.4));
			$c = $C{gum} if $root < 0.15;
			put($x0 + $i, $y0 + $j, jit($c, 5));
		}
	}
}

sub fang {
	my ($r) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $v = $h > 1 ? $j / ($h - 1) : 0.5;
			my $edge = abs($i - ($w - 1) / 2) / (($w + 1) / 2);
			put($x0 + $i, $y0 + $j, jit(mix(mix($C{tooth}, $C{toothD}, $v * 0.6), $C{toothD}, $edge * 0.5), 4));
		}
	}
}

sub claws { my ($F) = @_; for my $f (values %$F) { my ($x0, $y0, $w, $h) = @$f; for my $j (0 .. $h - 1) { for my $i (0 .. $w - 1) { put($x0 + $i, $y0 + $j, jit($i % 3 == 0 ? $C{clawL} : $C{claw}, 4)); } } } }

sub steel {
	my ($F) = @_;
	for my $f (values %$F) {
		my ($x0, $y0, $w, $h) = @$f;
		for my $j (0 .. $h - 1) {
			for my $i (0 .. $w - 1) {
				my $v = $h > 1 ? $j / ($h - 1) : 0.5;
				my $c = mix($C{steelL}, $C{steelD}, $v);
				$c = mix($c, [92, 60, 40], 0.5) if rand() < 0.08;
				put($x0 + $i, $y0 + $j, jit($c, 6));
			}
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

my (@elements, @outliner, %bones);

sub bone {
	my ($name, $parent, $origin, $rotation) = @_;
	my $b = {name => $name, origin => $origin, rotation => $rotation // [0, 0, 0], color => 0, uuid => uuid(), export => JSON::PP::true,
		mirror_uv => JSON::PP::false, isOpen => JSON::PP::false, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0,
		children => []};
	$bones{$name} = $b;
	if (defined $parent) {
		push @{$bones{$parent}{children}}, $b;
	} else {
		push @outliner, $b;
	}
	return $b;
}

# A cube on a bone; returns its faces' rectangles in the texture (texels). The dog faces north (-z); east (+x) is its
# right.
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

# Coat on every face of a cube; $tan per face (or one for all).
sub coated {
	my ($F, %o) = @_;
	for my $f (qw(north south east west up down)) {
		my $t = ref $o{tan} eq 'HASH' ? $o{tan}{$f} : $o{tan};
		coat($F->{$f}, tan => $t, along => $o{along}{$f} // 'v', shade => $f eq 'down' ? 0.75 : $f eq 'up' ? 1.08 : 1);
	}
}

my $none = sub { 0 };
my $low = sub { my $k = shift; return sub { my ($u, $v) = @_; return smooth(($v - $k) / 0.18); }; };

# ---------------------------------------------------------------- the body
bone("body", undef, [0, 14, 0]);
{
	# The chest: deep, the tan bib low on its front.
	my $F = cube("body", "chest", [-4, 10.5, -8.5], [4, 17.5, -2]);
	coated($F, tan => {north => sub { my ($u, $v) = @_; my $bib = $v > 0.55 && abs($u - 0.5) < 0.42 - ($v - 0.55) * 0.3 ? 1 : 0; return $bib * smooth(($v - 0.55) / 0.12); },
		down => sub { my ($u, $v) = @_; return $v < 0.3 ? smooth((0.3 - $v) / 0.2) * 0.8 : 0; }});
	scar($F->{east}, 0.2, 0.3, 0.75, 0.55);
	$F = cube("body", "brisket", [-2.6, 9.4, -8.2], [2.6, 10.5, -3.5]);
	coated($F, tan => sub { 0.65 });
	$F = cube("body", "waist", [-3.2, 11.9, -2], [3.2, 16.7, 3]);
	coated($F, along => {east => 'h', west => 'h', up => 'h'});
	scar($F->{west}, 0.1, 0.25, 0.9, 0.45);
	scar($F->{west}, 0.3, 0.55, 0.7, 0.7);
	$F = cube("body", "hips", [-3.7, 11.6, 3], [3.7, 17.1, 7.6]);
	coated($F, tan => {south => sub { my ($u, $v) = @_; return $v > 0.75 && abs($u - 0.5) < 0.18 ? 0.8 : 0; }});
}
# The hackles: a ridge of coarse hair down the back that stands up when it growls.
bone("hackles", "body", [0, 17.4, -2]);
{
	my $F = cube("hackles", "hackles", [-1.0, 17.25, -6.2], [1.0, 17.75, 2.4]);
	coated($F, along => {east => 'h', west => 'h', up => 'h', down => 'h'});
	# Tips paler, standing out of the black.
	for my $f (qw(up east west)) {
		my ($x0, $y0, $w, $h) = @{$F->{$f}};
		for (1 .. $w * $h / 9) { put($x0 + int(rand($w)), $y0 + int(rand($h)), jit($C{blackL}, 8)); }
	}
}

# ---------------------------------------------------------------- neck, collar, chain
bone("neck", "body", [0, 16, -7.5], [28, 0, 0]);
{
	my $F = cube("neck", "neck", [-2.9, 13.6, -12.2], [2.9, 19.4, -7.2]);
	coated($F, tan => {down => sub { 0.15 }}, along => {up => 'h'});
	# The collar: black leather, stitched, studded.
	$F = cube("neck", "collar", [-3.15, 13.3, -10.3], [3.15, 19.65, -8.8]);
	for my $f (values %$F) {
		my ($x0, $y0, $w, $h) = @$f;
		for my $j (0 .. $h - 1) {
			for my $i (0 .. $w - 1) {
				my $c = mix($C{leather}, $C{leatherL}, 0.3 + 0.3 * sin($i * 0.7));
				$c = $C{leatherL} if $j == 1 || $j == $h - 2;
				put($x0 + $i, $y0 + $j, jit($c, 4));
			}
		}
	}
	my @spikes = ([-0.35, 19.6, -9.85, 0.35, 20.7, -9.15], [2.8, 18.7, -9.85, 3.5, 19.5, -9.15], [-3.5, 18.7, -9.85, -2.8, 19.5, -9.15],
		[3.1, 16.2, -9.85, 4.0, 16.9, -9.15], [-4.0, 16.2, -9.85, -3.1, 16.9, -9.15], [2.0, 13, -10.6, 2.7, 13.6, -9.9], [-2.7, 13, -10.6, -2.0, 13.6, -9.9]);
	my $n = 0;
	for my $s (@spikes) {
		steel(cube("neck", "spike" . $n++, [@$s[0 .. 2]], [@$s[3 .. 5]]));
	}
}
bone("chain", "neck", [0, 13.3, -9.6]);
{
	steel(cube("chain", "link0", [-0.55, 11.7, -9.95], [0.55, 13.3, -9.35]));
	steel(cube("chain", "link1", [-0.2, 10.2, -10.2], [0.2, 11.9, -9.1]));
	steel(cube("chain", "link2", [-0.55, 8.6, -9.95], [0.55, 10.4, -9.35]));
	steel(cube("chain", "link3", [-0.2, 7.4, -10.15], [0.2, 8.8, -9.2]));
}

# ---------------------------------------------------------------- the head
bone("head", "neck", [0, 17.4, -11.6], [-24, 0, 0]);
{
	my $F = cube("head", "skull", [-3.1, 15.6, -16], [3.1, 20.8, -11]);
	# Tan dots over the eyes, tan cheeks low on the sides.
	coated($F, tan => {
		north => sub { my ($u, $v) = @_; my $dot = 0; for my $cx (0.24, 0.76) { my $d = sqrt((($u - $cx) / 0.12) ** 2 + (($v - 0.22) / 0.12) ** 2); $dot = 1 - smooth($d - 0.4) if $d < 1.4 && 1 - smooth($d - 0.4) > $dot; } return $dot; },
		east => sub { my ($u, $v) = @_; return smooth(($v - 0.62) / 0.2) * smooth((0.6 - $u) / 0.4); },
		west => sub { my ($u, $v) = @_; return smooth(($v - 0.62) / 0.2) * smooth(($u - 0.4) / 0.4); },
		down => sub { 0.7 }});
	scar($F->{east}, 0.15, 0.1, 0.55, 0.5);
	scar($F->{up}, 0.6, 0.2, 0.9, 0.85);
	# The cheeks: heavy jaw muscles.
	$F = cube("head", "cheeks", [-3.55, 15.4, -15.2], [3.55, 18.4, -12]);
	coated($F, tan => sub { my ($u, $v) = @_; return smooth(($v - 0.55) / 0.25) * 0.85; });
	# The brow, drawn down in a furrow over the eyes.
	$F = cube("head", "brow", [-3.25, 19.4, -16.5], [3.25, 20.6, -15.3]);
	coated($F, along => {north => 'h'});
	{
		my ($x0, $y0, $w, $h) = @{$F->{north}};
		for my $i (0 .. $w - 1) { put($x0 + $i, $y0 + $h - 1, jit($C{blackD}, 3)); }
		my $mid = int($w / 2);
		for my $j (0 .. $h - 1) { put($x0 + $mid, $y0 + $j, jit($C{blackD}, 3)); put($x0 + $mid - 1, $y0 + $j, jit($C{blackD}, 3)); }
	}
	# The eyes: amber, a black pupil, a wet glint; a dark rim round them.
	for my $side (1, -1) {
		my ($a, $b) = $side > 0 ? (1.2, 2.6) : (-2.6, -1.2);
		$F = cube("head", $side > 0 ? "eye_r" : "eye_l", [$a, 18.3, -16.12], [$b, 19.3, -15.95]);
		my ($x0, $y0, $w, $h) = @{$F->{north}};
		for my $j (0 .. $h - 1) {
			for my $i (0 .. $w - 1) {
				my ($u, $v) = ($i / ($w - 1), $j / ($h - 1));
				my $d = sqrt((($u - 0.5) / 0.5) ** 2 + (($v - 0.55) / 0.5) ** 2);
				my $c = $d > 0.95 ? $C{blackD} : $d < 0.35 ? $C{pupil} : mix($C{eyeL}, $C{eye}, $d);
				# The lid drawn low: the top of the eye in shadow.
				$c = mix($c, $C{blackD}, 0.7) if $v < 0.28;
				put($x0 + $i, $y0 + $j, jit($c, 3));
			}
		}
		put($x0 + int($w * 0.35), $y0 + int($h * 0.4), [250, 242, 221]);
		put($x0 + int($w * 0.35) + 1, $y0 + int($h * 0.4), [230, 222, 201]);
		solid($F->{$_}, $C{blackD}, 2) for qw(east west up down south);
	}
	# The stop between the brow and the muzzle.
	$F = cube("head", "stop", [-1.7, 18.2, -17.6], [1.7, 19.4, -16]);
	coated($F, tan => {east => sub { 0.3 }, west => sub { 0.3 }});
	# The muzzle: tan on its sides and under.
	$F = cube("head", "muzzle", [-1.9, 16, -20.6], [1.9, 18.5, -16]);
	coated($F, tan => {east => $low->(0.58), west => $low->(0.58), north => $low->(0.7), down => sub { 1 }}, along => {up => 'h'});
	{
		# The palate (seen with the mouth open): dark ridged red.
		my ($x0, $y0, $w, $h) = @{$F->{down}};
		for my $j (0 .. $h - 1) { for my $i (0 .. $w - 1) { put($x0 + $i, $y0 + $j, jit($j % 3 ? $C{palate} : $C{gum}, 4)); } }
	}
	# The nose: wet black leather, nostrils, a highlight.
	$F = cube("head", "nose", [-1.25, 17.6, -21.2], [1.25, 18.7, -20.5]);
	solid($F->{$_}, $C{nose}, 3) for keys %$F;
	{
		my ($x0, $y0, $w, $h) = @{$F->{north}};
		for my $nx (0.25, 0.75) { put($x0 + int($w * $nx) + $_, $y0 + int($h * 0.6), [2, 4, 3]) for -1 .. 0; }
		put($x0 + int($w * 0.4), $y0 + 1, $C{noseL});
		put($x0 + int($w * 0.4) + 1, $y0 + 1, $C{noseL});
		my ($ux, $uy, $uw, $uh) = @{$F->{up}};
		put($ux + int($uw * 0.5), $uy + int($uh * 0.5), $C{noseL});
	}
}
# The lips, drawn back off the teeth when it snarls (moved up by the growl); the upper teeth and fangs under them.
bone("lips", "head", [0, 16.4, -18]);
{
	for my $side (1, -1) {
		my ($a, $b) = $side > 0 ? (1.75, 2.15) : (-2.15, -1.75);
		my $F = cube("lips", $side > 0 ? "lip_r" : "lip_l", [$a, 15.3, -20.3], [$b, 16.8, -15.8]);
		coated($F, tan => $low->(0.68));
		# The edge of the lip: black and wet, wrinkled.
		my $face = $side > 0 ? 'east' : 'west';
		my ($x0, $y0, $w, $h) = @{$F->{$face}};
		for my $i (0 .. $w - 1) { put($x0 + $i, $y0 + $h - 1, jit($C{blackD}, 3)); put($x0 + $i, $y0 + $h - 2, jit($C{gum}, 4)) if $i % 3 == 0; }
		for my $k (1 .. 3) { my $i = int($w * $k / 4); put($x0 + $i, $y0 + $_, jit($C{blackD}, 3)) for 0 .. $h - 2; }
	}
	my $F = cube("lips", "teeth_up", [-1.7, 15.4, -20.4], [1.7, 15.95, -16.2]);
	teeth($F->{$_}, 'top') for qw(north east west);
	solid($F->{$_}, $C{gum}, 4) for qw(up down south);
	for my $side (1, -1) {
		my ($a, $b) = $side > 0 ? (1.1, 1.55) : (-1.55, -1.1);
		my $F = cube("lips", $side > 0 ? "fang_ur" : "fang_ul", [$a, 14.3, -20.15], [$b, 15.4, -19.7]);
		fang($F->{$_}) for keys %$F;
	}
}
# The lower jaw, its teeth and fangs, the tongue inside.
bone("jaw", "head", [0, 15.8, -15.6]);
{
	my $F = cube("jaw", "jaw", [-1.65, 14, -20.1], [1.65, 15.4, -15.4]);
	coated($F, tan => sub { 0.9 }, along => {down => 'h'});
	{
		my ($x0, $y0, $w, $h) = @{$F->{up}};
		for my $j (0 .. $h - 1) {
			for my $i (0 .. $w - 1) {
				my $u = $i / ($w - 1);
				my $c = abs($u - 0.5) < 0.32 ? mix($C{tongue}, $C{gumL}, 0.3 * sin($j * 0.8)) : $C{gum};
				put($x0 + $i, $y0 + $j, jit($c, 5));
			}
		}
	}
	$F = cube("jaw", "teeth_down", [-1.5, 15.4, -20], [1.5, 15.9, -16.3]);
	teeth($F->{$_}, 'bottom') for qw(north east west);
	solid($F->{$_}, $C{gum}, 4) for qw(up down south);
	for my $side (1, -1) {
		my ($a, $b) = $side > 0 ? (0.95, 1.35) : (-1.35, -0.95);
		$F = cube("jaw", $side > 0 ? "fang_dr" : "fang_dl", [$a, 15.4, -19.8], [$b, 16.2, -19.4]);
		fang($F->{$_}) for keys %$F;
	}
}
# The ears: cropped and standing, pointed.
for my $side (1, -1) {
	my $s = $side > 0 ? "r" : "l";
	my ($a, $b) = $side > 0 ? (1.25, 2.95) : (-2.95, -1.25);
	bone("ear_$s", "head", [$side * 2.1, 20.6, -13.1], [0, 0, $side * -8]);
	my $F = cube("ear_$s", "ear_$s", [$a, 20.6, -13.65], [$b, 24.2, -12.65]);
	coated($F);
	{
		# Inside the ear (the front): dark skin, fine hair.
		my ($x0, $y0, $w, $h) = @{$F->{north}};
		for my $j (1 .. $h - 1) { for my $i (1 .. $w - 2) { put($x0 + $i, $y0 + $j, jit(mix($C{skin}, $C{blackL}, rand() * 0.5), 4)); } }
	}
	my ($c, $d) = $side > 0 ? (1.6, 2.6) : (-2.6, -1.6);
	coated(cube("ear_$s", "ear_tip_$s", [$c, 24.2, -13.45], [$d, 25.4, -12.85]));
}

# ---------------------------------------------------------------- tail: docked short, held stiff
bone("tail", "body", [0, 16.2, 7.6], [-12, 0, 0]);
{
	coated(cube("tail", "tail", [-0.85, 15.3, 7.6], [0.85, 16.9, 10.8]), along => {up => 'h', east => 'h', west => 'h'});
	coated(cube("tail", "tail_tip", [-0.6, 15.5, 10.8], [0.6, 16.6, 12.4]), along => {up => 'h', east => 'h', west => 'h'});
}

# ---------------------------------------------------------------- legs: black above, tan below, black-clawed paws
my $legTan = sub { my $k = shift; return {map { $_ => $low->($k) } qw(north south east west)}; };
for my $side (1, -1) {
	my $s = $side > 0 ? "r" : "l";
	my $x = sub { my ($a, $b) = @_; return $side > 0 ? ($a, $b) : (-$b, -$a); };
	# Front: shoulder, forearm, paw.
	bone("front_$s", "body", [$side * 2.6, 14.6, -5.6]);
	my ($a, $b) = $x->(1.3, 3.9);
	coated(cube("front_$s", "upperarm_$s", [$a, 6.4, -7], [$b, 15.4, -4.1]), tan => {north => $low->(0.85)});
	bone("forearm_$s", "front_$s", [$side * 2.6, 6.8, -5.5]);
	($a, $b) = $x->(1.7, 3.5);
	coated(cube("forearm_$s", "forearm_$s", [$a, 1.1, -6.4], [$b, 6.9, -4.6]), tan => $legTan->(0.35));
	bone("frontpaw_$s", "forearm_$s", [$side * 2.6, 1.2, -5.5]);
	($a, $b) = $x->(1.45, 3.75);
	coated(cube("frontpaw_$s", "frontpaw_$s", [$a, 0, -7.4], [$b, 1.35, -4.5]), tan => sub { 0.85 });
	($a, $b) = $x->(1.6, 3.6);
	claws(cube("frontpaw_$s", "frontclaws_$s", [$a, 0, -7.95], [$b, 0.55, -7.4]));
	# Hind: thigh (knee forward), shin (back), hock, paw. Where the paw comes down after the joints' angles is worked
	# out (side view) and the hock made as long as it takes to stand it on the ground, flat.
	my ($aT, $aS, $aH) = (20, -48, 32);
	my @hip = (15.2, 5.2);
	my @knee = (7.9, 4.8);
	my @hockAt = (3.2, 4.8);
	my $turn = sub { my ($y, $z, $deg) = @_; my $r = $deg * $PI / 180; return ($y * cos($r) - $z * sin($r), $y * sin($r) + $z * cos($r)); };
	my @k = $turn->($knee[0] - $hip[0], $knee[1] - $hip[1], $aT);
	my @kw = ($hip[0] + $k[0], $hip[1] + $k[1]);
	my @h = $turn->($hockAt[0] - $knee[0], $hockAt[1] - $knee[1], $aT + $aS);
	my @hw = ($kw[0] + $h[0], $kw[1] + $h[1]);
	# The paw's pivot 0.8 over the ground: down the hock (turned by all three) as far as that takes.
	my $down = ($hw[0] - 0.8) / cos(($aT + $aS + $aH) * $PI / 180);
	my $pawY = $hockAt[0] - $down;
	bone("hind_$s", "body", [$side * 2.8, $hip[0], $hip[1]], [$aT, 0, 0]);
	($a, $b) = $x->(1.15, 4.25);
	coated(cube("hind_$s", "thigh_$s", [$a, 7.4, 2.7], [$b, 16.4, 7.9]), tan => {north => $low->(0.82)});
	bone("shin_$s", "hind_$s", [$side * 2.8, $knee[0], $knee[1]], [$aS, 0, 0]);
	($a, $b) = $x->(1.65, 3.6);
	coated(cube("shin_$s", "shin_$s", [$a, 2.8, 3.9], [$b, 8.3, 5.8]), tan => $legTan->(0.55));
	bone("hock_$s", "shin_$s", [$side * 2.8, $hockAt[0], $hockAt[1]], [$aH, 0, 0]);
	($a, $b) = $x->(1.9, 3.35);
	coated(cube("hock_$s", "hock_$s", [$a, $pawY - 0.2, 4.1], [$b, $hockAt[0] + 0.3, 5.5]), tan => sub { 0.8 });
	bone("hindpaw_$s", "hock_$s", [$side * 2.8, $pawY, 4.8], [-($aT + $aS + $aH), 0, 0]);
	($a, $b) = $x->(1.55, 3.7);
	coated(cube("hindpaw_$s", "hindpaw_$s", [$a, $pawY - 0.8, 2.8], [$b, $pawY + 0.5, 5.6]), tan => sub { 0.85 });
	($a, $b) = $x->(1.7, 3.55);
	claws(cube("hindpaw_$s", "hindclaws_$s", [$a, $pawY - 0.8, 2.25], [$b, $pawY - 0.25, 2.8]));
	printf "hind leg: knee at %.1f/%.1f, hock at %.1f/%.1f, paw pivot %.2f down the hock\n", @kw, @hw, $down if $side > 0;

}

# ---------------------------------------------------------------- the animations
my @anims;

sub anim {
	my ($name, $length, $loop, $frames, $interp) = @_;
	my %used;
	for my $f (@$frames) {
		$used{$_} = 1 for keys %{$f->[1]};
	}
	my $a = {uuid => uuid(), name => $name, loop => $loop, override => JSON::PP::false, length => $length, snapping => 60,
		selected => JSON::PP::false, anim_time_update => "", blend_weight => "", start_delay => "", loop_delay => "", animators => {}};
	for my $b (sort keys %used) {
		my $bone = $b;
		my $channel = $bone =~ s/_pos$// ? 'position' : $bone =~ s/_scale$// ? 'scale' : 'rotation';
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

sub setr {
	my ($p, $b, $x, $y, $z) = @_;
	my $r = $p->{$b} //= [0, 0, 0];
	$r->[0] += $x // 0;
	$r->[1] += $y // 0;
	$r->[2] += $z // 0;
}

# A leg's swing at phase $ph: + forward. The lift (0..1) is how far the foot is off the ground (on its way forward).
sub leg {
	my ($p, $s, $front, $ph, $amp, $bend) = @_;
	my $swing = sin($ph);
	my $lift = cos($ph) > 0 ? cos($ph) : 0;
	if ($front) {
		setr($p, "front_$s", $amp * $swing);
		setr($p, "forearm_$s", -$bend * $lift);
		setr($p, "frontpaw_$s", $bend * 0.6 * $lift);
	} else {
		setr($p, "hind_$s", $amp * $swing);
		setr($p, "shin_$s", -$bend * 0.7 * $lift);
		setr($p, "hock_$s", $bend * 0.6 * $lift);
		setr($p, "hindpaw_$s", -$bend * 0.3 * $lift);
	}
}

# idle: standing tense, breathing, the head turning a little, the ears flicking.
{
	my @f;
	my $T = 3.2;
	for my $i (0 .. 16) {
		my $t = $T * $i / 16;
		my $u = $t / $T * 2 * $PI;
		my $p = {};
		$p->{body_pos} = [0, 0.18 * sin($u * 2), 0];
		setr($p, "neck", 1.5 * sin($u * 2 + 0.5), 4 * sin($u), 0);
		setr($p, "head", 2 * sin($u * 2), 7 * sin($u + 0.8), 2 * sin($u));
		setr($p, "ear_r", ($i == 5 || $i == 6 ? -10 : 0), 0, 0);
		setr($p, "ear_l", ($i == 11 ? -12 : 0), 0, 0);
		setr($p, "tail", 0, 3 * sin($u), 0);
		push @f, [$t, $p];
	}
	anim("idle", $T, "loop", \@f);
}

# walk: diagonal legs together, the head carried low and steady, a slight roll.
{
	my @f;
	my $T = 1.0;
	for my $i (0 .. 16) {
		my $t = $T * $i / 16;
		my $u = $t / $T * 2 * $PI;
		my $p = {};
		leg($p, "r", 1, $u, 22, 40);
		leg($p, "l", 0, $u, 20, 34);
		leg($p, "l", 1, $u + $PI, 22, 40);
		leg($p, "r", 0, $u + $PI, 20, 34);
		$p->{body_pos} = [0, -0.25 * cos($u * 2), 0];
		setr($p, "body", 0, 2 * sin($u), 1.5 * sin($u));
		setr($p, "neck", -2 + 1.5 * sin($u * 2), -2 * sin($u), 0);
		setr($p, "head", -1.5 * sin($u * 2), 0, 0);
		setr($p, "tail", 0, 6 * sin($u), 0);
		push @f, [$t, $p];
	}
	anim("walk", $T, "loop", \@f);
}

# run: a gallop, the fronts together and the hinds together, the body rocking, the head reaching.
{
	my @f;
	my $T = 0.55;
	for my $i (0 .. 16) {
		my $t = $T * $i / 16;
		my $u = $t / $T * 2 * $PI;
		my $p = {};
		leg($p, "r", 1, $u, 40, 46);
		leg($p, "l", 1, $u + 0.35, 40, 46);
		leg($p, "r", 0, $u + $PI, 36, 40);
		leg($p, "l", 0, $u + $PI + 0.35, 36, 40);
		$p->{body_pos} = [0, -1.3 + 0.5 * sin($u * 2 + 0.6), 0];
		setr($p, "body", 7 * sin($u + 0.4));
		setr($p, "neck", -8 - 6 * sin($u + 0.4));
		setr($p, "head", 4 * sin($u + 0.9));
		setr($p, "ear_r", 22);
		setr($p, "ear_l", 22);
		setr($p, "tail", -10 + 8 * sin($u));
		push @f, [$t, $p];
	}
	anim("run", $T, "loop", \@f);
}

# growl (over the others): the head low and forward, lips drawn up, the jaw working, ears flat back, hackles up,
# the body crouched over its front legs.
{
	my @f;
	my $T = 1.2;
	for my $i (0 .. 24) {
		my $t = $T * $i / 24;
		my $u = $t / $T * 2 * $PI;
		my $p = {};
		my $tremble = 0.8 * sin($u * 9) + 0.5 * sin($u * 13);
		$p->{body_pos} = [0, -0.9, 0];
		setr($p, "body", -4);
		setr($p, "neck", -14);
		setr($p, "head", -6 + $tremble * 0.6, 0, $tremble * 0.4);
		$p->{lips_pos} = [0, 0.55 + 0.1 * sin($u * 3), 0.1];
		setr($p, "jaw", -9 - 4 * (0.5 + 0.5 * sin($u * 2)) + $tremble * 0.5);
		setr($p, "ear_r", 34);
		setr($p, "ear_l", 34);
		$p->{hackles_scale} = [1.15, 3.2, 1];
		$p->{hackles_pos} = [0, 0.55, 0];
		setr($p, "tail", 12);
		setr($p, "front_r", -6);
		setr($p, "front_l", -6);
		setr($p, "forearm_r", 8);
		setr($p, "forearm_l", 8);
		push @f, [$t, $p];
	}
	anim("growl", $T, "loop", \@f, "linear");
}

# bark (over the others): the jaw snapping open twice, the head jerking up with each.
{
	my @k = ([0, 0, 0], [0.06, -34, 10], [0.14, -6, 3], [0.22, -30, 9], [0.32, -4, 2], [0.45, 0, 0], [0.6, 0, 0]);
	my @f;
	for my $k (@k) {
		my ($t, $jaw, $head) = @$k;
		my $p = {};
		setr($p, "jaw", $jaw);
		setr($p, "head", $head);
		$p->{lips_pos} = [0, 0.02 * -$jaw, 0];
		push @f, [$t, $p];
	}
	anim("bark", 0.6, "loop", \@f, "linear");
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
	name => "dog", model_identifier => "dog", modded_entity_entity_class => "", modded_entity_version => "Official",
	modded_entity_flip_y => JSON::PP::true, visible_box => [1, 2, 0], variable_placeholders => "", variable_placeholder_buttons => [],
	timeline_setups => [], unhandled_root_fields => {},
	resolution => {width => $UW, height => $UH},
	elements => \@elements,
	outliner => \@outliner,
	textures => [{path => "", name => "dog.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH,
		uv_width => $UW, uv_height => $UH, particle => JSON::PP::false, use_as_default => JSON::PP::false, layers_enabled => JSON::PP::false,
		sync_to_project => "", render_mode => "default", render_sides => "auto", frame_time => 1, frame_order_type => "loop",
		frame_order => "", frame_interpolate => JSON::PP::false, visible => JSON::PP::true, internal => JSON::PP::true, saved => JSON::PP::true,
		uuid => uuid(), relative_path => "", source => "data:image/png;base64," . encode_base64(png(), "")}],
	animations => \@anims,
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;
printf "%s: %d cubes, %d bones, %d animations, atlas used to row %d of %d\n", $out, scalar(@elements), scalar(keys %bones), scalar(@anims),
	$shelfY + $shelfH, $UH;
