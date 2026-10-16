#!/usr/bin/perl
# The Demogorgon's rift as a Blockbench model (box UV): a wound torn in the ground and a membrane standing out of
# it, as in Stranger Things. Writes the .bbmodel (opens in Blockbench) and a second texture for what glows
# (client.RiftRenderer draws the model twice: the flesh as lit, the glow at full brightness).
#
# Parts (ground at y 0, the membrane across x, facing -z):
#   hole            a black-red pit, its glowing heart in the middle
#   lip0..lip7      flaps of turf and flesh round it, peeling back outwards as it opens
#   crack0..crack5  thin glowing cracks running out over the ground (they grow out, scale along their length)
#   membrane        four layers one behind the other, each in three ragged slices (low, mid, high): 3 blocks tall,
#                   see-through dark red with fibres; seam0/seam1 the white-hot split down the middle
#   tentacle0..7    eight tentacles round the edge, six segments each, tapering
# Animations: open (0.8 s, once: the ground splits, the lips peel back, the cracks run out, the membrane rises
# out of the slit and widens, the tentacles shoot up), idle (2.4 s loop: the membrane breathes, the tentacles
# writhe, each its own way), close (0.6 s, once: tentacles drawn back down, the membrane sinking, the lips shut).
#
#   perl tools/rift_model.pl art/rift.bbmodel src/main/resources/assets/halloween/textures/entity/rift_glow.png
use strict;
use warnings;
use JSON::PP;
use MIME::Base64;
use Compress::Zlib;
use POSIX qw(ceil);

my ($out, $glowOut) = @ARGV;
die "usage: rift_model.pl out.bbmodel glow.png\n" unless $glowOut;
my ($UW, $UH) = (256, 192);
my $S = 4;
my ($TW, $TH) = ($UW * $S, $UH * $S);
my @px = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
my @glow = map { [0, 0, 0, 0] } 1 .. ($TW * $TH);
srand(1983);
my $PI = 3.14159265358979;

sub clamp { my $v = shift; return $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5); }
sub mix { my ($a, $b, $t) = @_; return [map { $a->[$_] + ($b->[$_] - $a->[$_]) * $t } 0 .. 2]; }

my %C = (
	flesh => [70, 14, 20], fleshD => [34, 5, 9], fleshL => [120, 34, 38], wet => [190, 110, 110],
	turf => [40, 52, 30], dirt => [52, 38, 28], pit => [12, 0, 2], mem => [130, 16, 22], fibre => [60, 4, 10],
	hot => [255, 236, 214], red => [255, 54, 30], orange => [255, 120, 50],
);

sub put { my ($x, $y, $c, $a) = @_; return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH; $px[$y * $TW + $x] = [map({ clamp($_) } @$c), clamp($a // 255)]; }
sub glowAt { my ($x, $y, $c, $a) = @_; return if $x < 0 || $y < 0 || $x >= $TW || $y >= $TH; $glow[$y * $TW + $x] = [map({ clamp($_) } @$c), clamp($a)]; }
sub jit { my ($c, $n) = @_; my $d = rand(2 * $n) - $n; return [map { $_ + $d } @$c]; }

# Painters for a face rectangle [x, y, w, h] (texels).
sub flesh {
	my ($r, $base, %o) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	my $ph = rand(9);
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $st = sin($i * 0.5 + sin($j * 0.25 + $ph) * 1.6 + $ph);
			my $c = $C{$base};
			$c = mix($c, $C{fleshD}, 0.55) if $st > 0.7;
			$c = mix($c, $C{fleshL}, 0.5) if $st < -0.8;
			$c = mix($c, $C{wet}, 0.5) if rand() < 0.03;
			put($x0 + $i, $y0 + $j, jit($c, 6), $o{alpha} // 255);
		}
	}
}

sub membrane {
	my ($r) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $f = sin($i * 0.35 + $j * 0.12) * sin($j * 0.21 - $i * 0.07);
			my $c = $f > 0.55 ? $C{fibre} : $C{mem};
			# Ragged: holes in it here and there.
			my $a = rand() < 0.06 ? 0 : $f > 0.55 ? 190 : 80 + rand(50);
			put($x0 + $i, $y0 + $j, jit($c, 10), $a);
			glowAt($x0 + $i, $y0 + $j, $C{red}, 60) if $f < -0.75;
		}
	}
}

sub hot {
	my ($r, $core) = @_;
	my ($x0, $y0, $w, $h) = @$r;
	for my $j (0 .. $h - 1) {
		for my $i (0 .. $w - 1) {
			my $d = abs(($i + 0.5) / $w - 0.5) * 2;
			my $c = $d < 0.35 && $core ? $C{hot} : $d < 0.7 ? $C{orange} : $C{red};
			put($x0 + $i, $y0 + $j, $c, 255);
			glowAt($x0 + $i, $y0 + $j, $c, 255);
		}
	}
}

sub solid { my ($r, $c, $a) = @_; for my $j (0 .. $r->[3] - 1) { for my $i (0 .. $r->[2] - 1) { put($r->[0] + $i, $r->[1] + $j, jit($C{$c}, 6), $a // 255); } } }

# ---------------------------------------------------------------- atlas and cubes
my ($shelfX, $shelfY, $shelfH) = (0, 0, 0);
sub alloc {
	my ($w, $h) = @_;
	if ($shelfX + $w > $UW) { $shelfY += $shelfH; ($shelfX, $shelfH) = (0, 0); }
	die "atlas full at $w x $h\n" if $shelfY + $h > $UH;
	my @at = ($shelfX, $shelfY);
	$shelfX += $w;
	$shelfH = $h if $h > $shelfH;
	return \@at;
}

my $uuidN = 0;
sub uuid { return sprintf("%08x-%04x-4%03x-a%03x-%012x", int(rand(0xffffffff)), int(rand(0xffff)), int(rand(0xfff)), int(rand(0xfff)), ++$uuidN); }
my (@elements, @outliner, %bones);

sub bone {
	my ($name, $parent, $origin, $rotation) = @_;
	my $b = {name => $name, origin => $origin, rotation => $rotation // [0, 0, 0], color => 0, uuid => uuid(), export => JSON::PP::true,
		mirror_uv => JSON::PP::false, isOpen => JSON::PP::false, locked => JSON::PP::false, visibility => JSON::PP::true, autouv => 0, children => []};
	$bones{$name} = $b;
	if (defined $parent) { push @{$bones{$parent}{children}}, $b; } else { push @outliner, $b; }
	return $b;
}

sub cube {
	my ($bname, $name, $from, $to, $inflate) = @_;
	my $bone = $bones{$bname};
	my ($w, $h, $d) = map { $to->[$_] - $from->[$_] } 0 .. 2;
	my $uv = alloc(ceil(2 * ($d + $w) - 1e-6), ceil($d + $h - 1e-6));
	my ($u, $v) = @$uv;
	push @elements, {name => $name, box_uv => JSON::PP::true, rescale => JSON::PP::false, locked => JSON::PP::false, render_order => "default",
		allow_mirror_modeling => JSON::PP::true, from => $from, to => $to, autouv => 0, color => 0, inflate => $inflate // 0,
		origin => $bone->{origin}, uv_offset => $uv, type => "cube", uuid => uuid(),
		faces => {
			north => {uv => [$u + $d, $v + $d, $u + $d + $w, $v + $d + $h], texture => 0},
			east => {uv => [$u, $v + $d, $u + $d, $v + $d + $h], texture => 0},
			south => {uv => [$u + 2 * $d + $w, $v + $d, $u + 2 * $d + 2 * $w, $v + $d + $h], texture => 0},
			west => {uv => [$u + $d + $w, $v + $d, $u + 2 * $d + $w, $v + $d + $h], texture => 0},
			up => {uv => [$u + $d + $w, $v + $d, $u + $d, $v], texture => 0},
			down => {uv => [$u + $d + 2 * $w, $v, $u + $d + $w, $v + $d], texture => 0}}};
	push @{$bone->{children}}, $elements[-1]{uuid};
	my $R = sub { return [map { int($_ * $S + 0.5) } @_]; };
	# The whole footprint (for painting all of it at once) and the faces.
	return {all => $R->($u, $v, 2 * ($d + $w), $d + $h), up => $R->($u + $d, $v, $w, $d), north => $R->($u + $d, $v + $d, $w, $h),
		south => $R->($u + 2 * $d + $w, $v + $d, $w, $h)};
}

# ---------------------------------------------------------------- the parts
bone("rift", undef, [0, 0, 0]);
bone("hole", "rift", [0, 0, 0]);
my $F;
for my $k (0 .. 2) {
	my ($hx, $hz) = (12 - $k * 3, 3 + $k * 1.5);
	$F = cube("hole", "pit$k", [-$hx / 2, 0.02 + $k * 0.02, -$hz / 2], [$hx / 2, 0.12 + $k * 0.02, $hz / 2]);
	solid($F->{all}, 'pit');
}
$F = cube("hole", "pit_heart", [-4, 0.1, -0.5], [4, 0.22, 0.5]);
hot($F->{all}, 1);

# Lips round the pit (an ellipse 9 x 5), each a flap of turf on top, flesh under.
for my $k (0 .. 7) {
	my $a = $k / 8 * 2 * $PI;
	my ($x, $z) = (sin($a) * 7.5, -cos($a) * 3.8);
	my $yaw = -$a * 180 / $PI;
	bone("lip$k", "rift", [$x, 0, $z], [0, $yaw, 0]);
	$F = cube("lip$k", "lip${k}_flap", [$x - 2.5, 0, $z - 3.5], [$x + 2.5, 1, $z + 0.2], -0.05);
	flesh($F->{all}, 'flesh');
	my $t = $F->{up};
	for my $j (0 .. $t->[3] - 1) { for my $i (0 .. $t->[2] - 1) { put($t->[0] + $i, $t->[1] + $j, jit(rand() < 0.5 ? $C{turf} : $C{dirt}, 8)); } }
}

# Cracks running out over the ground (glowing), each grown along its length (z) from the pit's edge.
for my $k (0 .. 5) {
	my $a = ($k + 0.5) / 6 * 2 * $PI + (rand() - 0.5) * 0.4;
	my ($x, $z) = (sin($a) * 6, -cos($a) * 3);
	my $len = 18 + rand(12);
	bone("crack$k", "rift", [$x, 0.05, $z], [0, -$a * 180 / $PI + 180, 0]);
	$F = cube("crack$k", "crack${k}_line", [$x - 0.35, 0.03, $z], [$x + 0.35, 0.13, $z + $len]);
	hot($F->{all}, 0);
}

# The membrane: four layers, each torn into strips standing side by side, of ragged heights (tallest in the
# middle, shorter towards the edges, like a flame), each strip leaning and turned a little; the layers fanned.
bone("membrane", "rift", [0, 0, 0]);
for my $l (0 .. 3) {
	my $zl = -1.8 + $l * 1.2;
	bone("membrane$l", "membrane", [0, 0, $zl], [0, ($l - 1.5) * 9, 0]);
	my $strips = 8;
	my $span = 30 - abs($l - 1.5) * 4;
	for my $s (0 .. $strips - 1) {
		my $c = ($s + 0.5) / $strips - 0.5;
		my $x = $c * $span;
		my $w = $span / $strips + 0.6;
		my $h = int(46 * (1 - $c * $c * 3.2) * (0.8 + rand() * 0.25)) + 4;
		my $y0 = int(rand(3));
		my $name = "membrane${l}_$s";
		bone($name, "membrane$l", [$x, $y0, $zl], [(rand() - 0.5) * 8, (rand() - 0.5) * 20, -$c * 14]);
		$F = cube($name, "${name}_strip", [$x - $w / 2, $y0, $zl - 0.2], [$x + $w / 2, $y0 + $h, $zl + 0.2]);
		membrane($F->{all});
	}
}
# The split down the middle: white-hot, a second jag beside it.
bone("seam", "membrane", [0, 0, 0]);
$F = cube("seam", "seam0", [-0.75, 1, -0.4], [0.75, 44, 0.4]);
hot($F->{all}, 1);
$F = cube("seam", "seam1", [0.4, 10, -0.6], [1.6, 30, 0.6], -0.1);
hot($F->{all}, 0);

# Tentacles round the edge: six segments, 4 long, thinning.
my @TENT;
for my $k (0 .. 7) {
	my $a = ($k + 0.25) / 8 * 2 * $PI;
	my ($x, $z) = (sin($a) * 8.5, -cos($a) * 4.5);
	my $out = $a * 180 / $PI;
	bone("tentacle$k", "rift", [$x, 0, $z], [0, -$out, 0]);
	my $parent = "tentacle$k";
	my $y = 0;
	for my $s (0 .. 5) {
		my $r = 1.3 - $s * 0.17;
		my $name = "tentacle${k}_$s";
		bone($name, $parent, [$x, $y, $z]);
		$F = cube($name, "${name}_flesh", [$x - $r, $y, $z - $r], [$x + $r, $y + 4.2, $z + $r]);
		flesh($F->{all}, $s % 2 ? 'flesh' : 'fleshL');
		$parent = $name;
		$y += 4;
	}
	push @TENT, $k;
}

# ---------------------------------------------------------------- animations
my @anims;
sub anim {
	my ($name, $length, $loop, $frames, $interp) = @_;
	my %used;
	for my $f (@$frames) { $used{$_} = 1 for keys %{$f->[1]}; }
	my $a = {uuid => uuid(), name => $name, loop => $loop, override => JSON::PP::false, length => $length, snapping => 60,
		selected => JSON::PP::false, anim_time_update => "", blend_weight => "", start_delay => "", loop_delay => "", animators => {}};
	for my $key (sort keys %used) {
		my ($bone, $channel) = split /:/, $key;
		my $an = $a->{animators}{$bones{$bone}{uuid}} //= {name => $bone, type => "bone", keyframes => []};
		for my $f (@$frames) {
			my $v = $f->[1]{$key} // ($channel eq 'scale' ? [1, 1, 1] : [0, 0, 0]);
			push @{$an->{keyframes}}, {channel => $channel,
				data_points => [{x => sprintf("%.3f", $v->[0]), y => sprintf("%.3f", $v->[1]), z => sprintf("%.3f", $v->[2])}],
				uuid => uuid(), time => 0 + sprintf("%.4f", $f->[0]), color => -1, interpolation => $interp // "catmullrom"};
		}
	}
	push @anims, $a;
}

sub smooth { my $t = shift; $t = $t < 0 ? 0 : $t > 1 ? 1 : $t; return $t * $t * (3 - 2 * $t); }

# A pose of the whole thing: how open (0..1), the tentacles at time t (writhing) with how much of it.
sub pose {
	my ($open, $t, $writhe, %o) = @_;
	my %p;
	my $lips = smooth($o{lips} // $open);
	for my $k (0 .. 7) {
		# Flaps peel back outwards (about their outer edge: tipping up and over).
		$p{"lip$k:rotation"} = [55 * $lips, 0, 0];
	}
	my $crack = smooth($o{cracks} // $open);
	for my $k (0 .. 5) {
		$p{"crack$k:scale"} = [1, 1, 0.02 + 0.98 * $crack];
	}
	my $rise = smooth($o{rise} // $open);
	my $breath = 1 + 0.05 * sin($t * 2 * $PI / 2.4) * $writhe;
	$p{"membrane:scale"} = [(0.15 + 0.85 * $rise) * $breath, 0.02 + 0.98 * $rise, 1];
	$p{"hole:scale"} = [0.3 + 0.7 * smooth($open * 1.5), 1, 0.3 + 0.7 * smooth($open * 1.5)];
	my $reach = smooth($o{tentacles} // $open);
	for my $k (0 .. 7) {
		# Down in the pit, curled, till they shoot up; then each writhes its own way.
		$p{"tentacle$k:scale"} = [0.05 + 0.95 * $reach, 0.05 + 0.95 * $reach, 0.05 + 0.95 * $reach];
		$p{"tentacle$k:rotation"} = [-35 - 55 * (1 - $reach), 0, 0];
		my $ph = $k * 0.83;
		for my $s (0 .. 5) {
			my $w = $writhe * (0.4 + $s * 0.18);
			my $bend = 14 * sin(2 * $PI * $t / 2.4 * (1 + ($k % 3) * 0.25) + $ph + $s * 0.7) * $w;
			my $side = 11 * sin(2 * $PI * $t / 2.4 * (1 + ($k % 2) * 0.5) + $ph * 1.7 + $s * 0.9) * $w;
			my $curl = (1 - $reach) * 30 + 6;
			$p{"tentacle${k}_$s:rotation"} = [$bend + $curl, 0, $side];
		}
	}
	return \%p;
}

# open: 0.8 s (cracks first, then the lips and the slit, the membrane rising, the tentacles bursting up).
{
	my @f;
	for my $i (0 .. 16) {
		my $t = 0.8 * $i / 16;
		my $u = $t / 0.8;
		push @f, [$t, pose($u, $t, smooth(($u - 0.6) / 0.4), cracks => $u * 2, lips => ($u - 0.15) / 0.5, rise => ($u - 0.2) / 0.7,
			tentacles => ($u - 0.35) / 0.5)];
	}
	anim("open", 0.8, "hold_on_last_frame", \@f);
}
# idle: 2.4 s loop, fully open, breathing and writhing.
{
	my @f;
	for my $i (0 .. 24) {
		my $t = 2.4 * $i / 24;
		push @f, [$t, pose(1, $t, 1)];
	}
	anim("idle", 2.4, "loop", \@f);
}
# close: 0.6 s (tentacles drawn back first, the membrane sinking, the lips shutting, the cracks fading back).
{
	my @f;
	for my $i (0 .. 12) {
		my $t = 0.6 * $i / 12;
		my $u = 1 - $t / 0.6;
		push @f, [$t, pose($u, $t, $u, tentacles => ($u - 0.4) / 0.6, rise => ($u - 0.15) / 0.7, lips => $u / 0.6, cracks => $u)];
	}
	anim("close", 0.6, "hold_on_last_frame", \@f);
}

# ---------------------------------------------------------------- files
sub png {
	my ($pix) = @_;
	my $raw = '';
	for my $y (0 .. $TH - 1) { $raw .= "\0"; $raw .= pack("C4", @{$pix->[$y * $TW + $_]}) for 0 .. $TW - 1; }
	my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
	return "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $TW, $TH, 8, 6, 0, 0, 0)) . $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
}
my $model = {
	meta => {format_version => "4.10", model_format => "modded_entity", box_uv => JSON::PP::true},
	name => "rift", model_identifier => "rift", modded_entity_entity_class => "", modded_entity_version => "Official", modded_entity_flip_y => JSON::PP::true,
	visible_box => [2, 3, 0], variable_placeholders => "", variable_placeholder_buttons => [], timeline_setups => [], unhandled_root_fields => {},
	resolution => {width => $UW, height => $UH}, elements => \@elements, outliner => \@outliner,
	textures => [{path => "", name => "rift.png", folder => "", namespace => "", id => "0", group => "", width => $TW, height => $TH, uv_width => $UW,
		uv_height => $UH, particle => JSON::PP::false, use_as_default => JSON::PP::false, layers_enabled => JSON::PP::false, sync_to_project => "",
		render_mode => "default", render_sides => "auto", frame_time => 1, frame_order_type => "loop", frame_order => "", frame_interpolate => JSON::PP::false,
		visible => JSON::PP::true, internal => JSON::PP::true, saved => JSON::PP::true, uuid => uuid(), relative_path => "",
		source => "data:image/png;base64," . encode_base64(png(\@px), "")}],
	animations => \@anims,
};
open my $o, ">", $out or die "$out: $!";
print $o JSON::PP->new->canonical->pretty->encode($model);
close $o;
open my $g, ">:raw", $glowOut or die "$glowOut: $!";
print $g png(\@glow);
close $g;
printf "%s: %d cubes, %d bones, %d animations, atlas to row %d of %d\n", $out, scalar(@elements), scalar(keys %bones), scalar(@anims), $shelfY + $shelfH, $UH;
