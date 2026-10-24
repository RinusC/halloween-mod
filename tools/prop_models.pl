#!/usr/bin/perl
# What survivors pull down in a chase (game.PropBlock, game.Obstacles): block models and their textures.
#   perl tools/prop_models.pl src/main/resources/assets/halloween
#
# Standing (block "prop", front to the north, as models go):
#   bookcase_lower, bookcase_upper  a tall walnut bookcase two blocks high, four shelves of books, its back to the wall
#   bin                             a galvanized street trash can with its lid
# Only ever drawn by block displays as they fall and lie (block "debris", see DebrisBlock.Piece):
#   bin_body, bin_lid               the can and its lid apart (the lid flies off as it goes over)
#   book_a, book_b, book_open       books that spill out from under a bookcase
#   papers, bag, trash              what spills out of a can
#   planks                          what is left of a bookcase he kicked apart
# The pieces of a bookcase and a can keep the standing models' places, so Obstacles turns them over a pivot and
# they lie exactly where they fall. Textures are 2 px a unit (books 4), painted here.
use strict;
use warnings;
use JSON::PP;
use Compress::Zlib;

my $root = shift or die "usage: prop_models.pl <assets/halloween>\n";
my $tex = "$root/textures/block/prop";
my $mod = "$root/models/block/prop";
mkdir $_ for grep { !-d } ($tex, $mod);
srand(1978);

# ---------------------------------------------------------------- painting
sub clamp { my $v = shift; $v < 0 ? 0 : $v > 255 ? 255 : int($v + 0.5) }
sub canvas { my ($w, $h, $c) = @_; return {w => $w, h => $h, px => [map { [@$c, 255] } 1 .. $w * $h]}; }
sub put { my ($cv, $x, $y, $c, $a) = @_; return if $x < 0 || $y < 0 || $x >= $cv->{w} || $y >= $cv->{h};
	my $p = $cv->{px}[$y * $cv->{w} + $x]; $a //= 1;
	$p->[$_] = clamp($p->[$_] * (1 - $a) + $c->[$_] * $a) for 0 .. 2; $p->[3] = 255; }
sub get { my ($cv, $x, $y) = @_; return $cv->{px}[($y % $cv->{h}) * $cv->{w} + ($x % $cv->{w})]; }
sub shade { my ($cv, $x, $y, $k) = @_; my $p = get($cv, $x, $y); $p->[$_] = clamp($p->[$_] * $k) for 0 .. 2; }
sub rect { my ($cv, $x0, $y0, $x1, $y1, $c, $a) = @_; for my $y ($y0 .. $y1 - 1) { put($cv, $_, $y, $c, $a) for $x0 .. $x1 - 1; } }
sub jitter { my ($cv, $amt) = @_; for my $p (@{$cv->{px}}) { my $k = 1 + (rand() * 2 - 1) * $amt; $p->[$_] = clamp($p->[$_] * $k) for 0 .. 2; } }
sub mixc { my ($a, $b, $t) = @_; return [map { $a->[$_] * (1 - $t) + $b->[$_] * $t } 0 .. 2]; }

sub save {
	my ($cv, $name) = @_;
	my $raw = '';
	for my $y (0 .. $cv->{h} - 1) {
		$raw .= "\0" . join('', map { pack("C4", @{$cv->{px}[$y * $cv->{w} + $_]}) } 0 .. $cv->{w} - 1);
	}
	my $chunk = sub { my ($t, $d) = @_; return pack("N", length $d) . $t . $d . pack("N", crc32($t . $d)); };
	open my $f, ">:raw", "$tex/$name.png" or die "$name: $!";
	print $f "\x89PNG\r\n\x1a\n" . $chunk->("IHDR", pack("NNCCCCC", $cv->{w}, $cv->{h}, 8, 6, 0, 0, 0))
		. $chunk->("IDAT", compress($raw)) . $chunk->("IEND", "");
	close $f;
}

# Walnut: dark, the grain running down the panel (wavy streaks, a knot or two), worn lighter at the edges.
sub wood {
	my ($base, $dark, $light, $seed) = @_;
	srand($seed);
	my $cv = canvas(32, 32, $base);
	for my $x (0 .. 31) {
		my $k = 0.9 + rand() * 0.2;
		my $ph = rand(6.28);
		for my $y (0 .. 31) {
			my $w = sin($y * 0.35 + $ph) * 0.8;
			my $xx = int($x + $w) % 32;
			shade($cv, $xx, $y, $k);
		}
	}
	for (1 .. 9) {
		my $x = int(rand(32));
		my $ph = rand(6.28);
		for my $y (0 .. 31) { put($cv, int($x + sin($y * 0.3 + $ph) * 1.2) % 32, $y, $dark, 0.55); }
	}
	for (1 .. 5) {
		my $x = int(rand(32));
		for my $y (0 .. 31) { put($cv, ($x + int($y / 9)) % 32, $y, $light, 0.25); }
	}
	my ($kx, $ky) = (int(rand(24)) + 4, int(rand(24)) + 4);
	for my $dy (-2 .. 2) { for my $dx (-1 .. 1) { put($cv, $kx + $dx, $ky + $dy, $dark, 0.6 - 0.12 * abs($dy)); } }
	jitter($cv, 0.05);
	return $cv;
}

my $walnut = wood([78, 49, 30], [44, 26, 15], [112, 74, 44], 11);
save($walnut, "walnut");
# The inside: the same wood in the shadow of the shelves, and the plywood back seen between the books.
my $back = wood([46, 30, 19], [28, 17, 10], [62, 42, 26], 12);
save($back, "walnut_back");
# The back of the case (on top once it lies face down): rough plywood, a stencilled maker's mark, nail heads.
{
	srand(13);
	my $cv = canvas(32, 32, [122, 92, 60]);
	for my $y (0 .. 31) { my $k = 0.92 + 0.1 * sin($y * 0.9 + rand(0.6)); shade($cv, $_, $y, $k) for 0 .. 31; }
	for (1 .. 6) { my $y = int(rand(32)); put($cv, $_, $y, [96, 70, 44], 0.4) for 0 .. 31; }
	rect($cv, 0, 15, 32, 17, [84, 60, 38]);
	for my $x (2, 10, 21, 29) { put($cv, $x, 1, [60, 60, 64]); put($cv, $x, 30, [60, 60, 64]); put($cv, $x, 16, [70, 70, 74]); }
	for my $x (11 .. 20) { put($cv, $x, 7, [70, 46, 28], 0.6); put($cv, $x, 9, [70, 46, 28], 0.6); }
	put($cv, $_, 8, [70, 46, 28], 0.4) for (12, 14, 15, 17, 19);
	jitter($cv, 0.04);
	save($cv, "plywood");
}

# Books: 64x64 (4 px a unit). Columns of 1 unit, sixteen books: the spines (v 0..8: bands, a title, a label),
# the page edges (v 8..12) and each book's cover cloth (v 12..16).
my @cloth = ([120, 28, 30], [32, 58, 92], [36, 84, 52], [142, 108, 54], [92, 30, 64], [40, 40, 44], [168, 140, 98], [70, 44, 28],
	[150, 60, 36], [28, 90, 96], [110, 112, 60], [86, 22, 22], [44, 54, 120], [190, 176, 150], [64, 80, 40], [120, 76, 100]);
{
	srand(14);
	my $cv = canvas(64, 64, [0, 0, 0]);
	my $gold = [200, 168, 88];
	for my $c (0 .. 15) {
		my $col = $cloth[$c];
		my $x0 = $c * 4;
		rect($cv, $x0, 0, $x0 + 4, 32, $col);
		# Rounded spine: lit in the middle, dark at both edges.
		for my $y (0 .. 31) { shade($cv, $x0, $y, 0.7); shade($cv, $x0 + 3, $y, 0.75); shade($cv, $x0 + 1, $y, 1.08); }
		my $style = $c % 4;
		my $band = $style == 3 ? [24, 20, 18] : $gold;
		rect($cv, $x0, 2, $x0 + 4, 3, $band, 0.85);
		rect($cv, $x0, 28, $x0 + 4, 29, $band, 0.85);
		if ($style == 0) { rect($cv, $x0, 4, $x0 + 4, 5, $band, 0.8); rect($cv, $x0, 26, $x0 + 4, 27, $band, 0.8); }
		if ($style == 1) { rect($cv, $x0, 6, $x0 + 4, 12, [24, 22, 20], 0.85); }
		if ($style == 2) { rect($cv, $x0 + 1, 18, $x0 + 3, 22, [226, 214, 182]); put($cv, $x0 + 1, 19, [60, 50, 40]); put($cv, $x0 + 2, 20, [60, 50, 40]); }
		# The title: a short gilt line, letters as dots.
		for my $y (8 .. 8 + 6 + int(rand(6))) { put($cv, $x0 + 1 + ($y % 2), $y, $gold, 0.75) if rand() < 0.7; }
		# Pages: cream, ruled with the leaves; the cover's edge either side.
		rect($cv, $x0, 32, $x0 + 4, 48, [222, 210, 178]);
		for my $y (32 .. 47) { put($cv, $x0 + 1 + ($y % 2), $y, [190, 176, 140], 0.5); }
		rect($cv, $x0, 32, $x0 + 4, 33, $col);
		rect($cv, $x0, 47, $x0 + 4, 48, $col);
		# Cover cloth, worn at the corners.
		rect($cv, $x0, 48, $x0 + 4, 64, $col);
		for my $y (48 .. 63) { shade($cv, $x0 + int(rand(4)), $y, 0.85); }
		put($cv, $x0, 48, mixc($col, [230, 220, 200], 0.4));
		put($cv, $x0 + 3, 63, mixc($col, [230, 220, 200], 0.4));
	}
	jitter($cv, 0.04);
	save($cv, "books");
}

# Galvanized steel: ribbed bands round the can, a dull zinc sheen, dents, rust weeping down from the rims, grime low down.
{
	srand(15);
	my $cv = canvas(32, 32, [150, 154, 156]);
	for my $y (0 .. 31) {
		for my $x (0 .. 31) {
			my $sheen = 0.88 + 0.16 * sin($x * 0.45 + 1.2) + (rand() - 0.5) * 0.06;
			shade($cv, $x, $y, $sheen);
		}
	}
	for my $band (5, 6, 14, 15, 23, 24) { for my $x (0 .. 31) { shade($cv, $x, $band, $band % 2 ? 0.72 : 1.18); } }
	for (1 .. 18) { my ($x, $y) = (int(rand(32)), int(rand(32))); put($cv, $x, $y, [210, 214, 216], 0.5); }
	for (1 .. 4) {
		my ($x, $y) = (int(rand(28)) + 2, int(rand(22)) + 4);
		for my $dy (-2 .. 2) { for my $dx (-2 .. 2) { next if abs($dx) + abs($dy) > 3; shade($cv, $x + $dx, $y + $dy, 0.8 + 0.05 * (abs($dx) + abs($dy))); } }
		put($cv, $x - 2, $y - 2, [220, 224, 226], 0.6);
	}
	for (1 .. 7) {
		my $x = int(rand(32));
		my $len = 3 + int(rand(9));
		my $y0 = (0, 7, 16)[int(rand(3))];
		for my $y ($y0 .. $y0 + $len) { put($cv, $x, $y, [126, 72, 40], 0.55 * (1 - ($y - $y0) / ($len + 1))); }
	}
	for my $y (24 .. 31) { for my $x (0 .. 31) { put($cv, $x, $y, [60, 52, 40], 0.06 * ($y - 23) + rand() * 0.1); } }
	save($cv, "bin_side");
	# The lid from above: rings pressed into it, a raised middle, the same zinc.
	my $lid = canvas(32, 32, [156, 160, 162]);
	for my $y (0 .. 31) {
		for my $x (0 .. 31) {
			my $r = sqrt(($x - 15.5)**2 + ($y - 15.5)**2);
			my $k = 0.95 + 0.06 * sin($x * 0.3) + (rand() - 0.5) * 0.06;
			$k *= 0.78 if abs($r - 9) < 0.8 || abs($r - 13.5) < 0.7;
			$k *= 1.15 if abs($r - 10) < 0.6 || abs($r - 5) < 0.6;
			$k *= 0.7 if $r > 15.2;
			shade($lid, $x, $y, $k);
		}
	}
	for (1 .. 3) { my ($x, $y) = (int(rand(20)) + 6, int(rand(20)) + 6); put($lid, $x, $y, [120, 70, 40], 0.4); put($lid, $x + 1, $y, [120, 70, 40], 0.3); }
	save($lid, "bin_lid");
	# Inside: dark, wet, filthy.
	my $in = canvas(32, 32, [36, 34, 30]);
	for (1 .. 60) { put($in, int(rand(32)), int(rand(32)), [60, 54, 40], 0.5); }
	jitter($in, 0.15);
	save($in, "bin_inside");
}

# A black garbage bag: glossy folds.
{
	srand(16);
	my $cv = canvas(32, 32, [22, 22, 25]);
	for my $y (0 .. 31) { for my $x (0 .. 31) { my $f = sin($x * 0.5 + sin($y * 0.3) * 2) * 0.5 + 0.5; put($cv, $x, $y, [70, 72, 80], $f**6 * 0.8); } }
	jitter($cv, 0.1);
	save($cv, "bag");
}

# Rubbish: crumpled paper, newsprint, a red soda can, a green bottle, a cardboard scrap (an atlas in quarters).
{
	srand(17);
	my $cv = canvas(32, 32, [230, 226, 214]);
	# Top left: crumpled white paper.
	for my $y (0 .. 15) { for my $x (0 .. 15) { shade($cv, $x, $y, 0.82 + rand() * 0.22); } }
	for (1 .. 10) { my ($x, $y) = (int(rand(15)), int(rand(15))); put($cv, $x, $y, [170, 166, 156], 0.6); put($cv, $x + 1, $y + 1, [250, 250, 246], 0.6); }
	# Top right: newsprint, columns of grey lines and a photo block.
	rect($cv, 16, 0, 32, 16, [206, 200, 184]);
	for my $y (1 .. 14) { next if $y % 2; for my $x (17 .. 30) { put($cv, $x, $y, [90, 88, 84], 0.7) if rand() < 0.75 && $x != 23 && $x != 24; } }
	rect($cv, 25, 2, 30, 7, [60, 58, 56], 0.85);
	# Bottom left: the soda can, red with a white swirl and a silver rim.
	rect($cv, 0, 16, 16, 32, [176, 26, 30]);
	for my $x (0 .. 15) { put($cv, $x, 22 + int(2 * sin($x * 0.6)), [240, 236, 230]); }
	rect($cv, 0, 16, 16, 17, [190, 192, 196]);
	rect($cv, 0, 31, 16, 32, [170, 172, 176]);
	for my $y (16 .. 31) { shade($cv, 0, $y, 0.7); shade($cv, 1, $y, 0.85); shade($cv, 6, $y, 1.2); }
	# Bottom right: green bottle glass and a scrap of cardboard.
	rect($cv, 16, 16, 32, 24, [44, 110, 58]);
	for my $x (16 .. 31) { put($cv, $x, 18, [140, 210, 150], 0.5); }
	rect($cv, 16, 24, 32, 32, [168, 128, 82]);
	for my $y (24 .. 31) { put($cv, $_, $y, [140, 104, 64], 0.5) for grep { $_ % 3 == 0 } 16 .. 31; }
	jitter($cv, 0.04);
	save($cv, "trash");
}

# Splintered boards: pale broken ends.
{
	my $cv = wood([96, 62, 38], [52, 32, 18], [150, 112, 72], 18);
	for my $y (0 .. 31) { put($cv, 0, $y, [190, 160, 120], 0.8); put($cv, 31, $y, [190, 160, 120], 0.8); }
	save($cv, "planks");
}

# ---------------------------------------------------------------- models
my @els;
sub faces {
	my ($t, %o) = @_;
	my %f;
	for my $d (qw(north south east west up down)) {
		next if $o{skip} && $o{skip} =~ /\b$d\b/;
		my $tx = $o{$d} // $t;
		$f{$d} = ref $tx ? {%$tx} : {texture => "#$tx"};
	}
	return \%f;
}
sub box {
	my ($from, $to, $faces, $rot) = @_;
	my $e = {from => [map { 0 + sprintf("%.3f", $_) } @$from], to => [map { 0 + sprintf("%.3f", $_) } @$to], faces => $faces};
	$e->{rotation} = $rot if $rot;
	push @els, $e;
}
sub model {
	my ($name, $textures) = @_;
	my $m = {parent => "minecraft:block/block", textures => $textures, elements => [@els]};
	@els = ();
	open my $f, ">", "$mod/$name.json" or die "$name: $!";
	print $f JSON::PP->new->canonical->pretty->indent_length(1)->encode($m);
	close $f;
}

# ---- the bookcase. Absolute heights 0..32 (lower 0..16, upper 16..32), depth z 6..16, front at z 6.
# Shelves (top faces) at 3, 10, 17, 24; the cap 30..32; rows of books on each.
my $bt = {wood => "halloween:block/prop/walnut", inside => "halloween:block/prop/walnut_back", ply => "halloween:block/prop/plywood",
	books => "halloween:block/prop/books", particle => "halloween:block/prop/walnut"};
my @case;    # [from, to, faces] in absolute heights
sub cbox { push @case, [@_]; }
cbox([0, 0, 6], [1, 32, 16], faces("wood", south => "ply"));
cbox([15, 0, 6], [16, 32, 16], faces("wood", south => "ply"));
cbox([1, 0, 15], [15, 32, 16], faces("inside", south => "ply", skip => "east west"));
cbox([1, 30, 6], [15, 32, 15], faces("wood", skip => "east west south"));
cbox([1, 0, 6], [15, 2, 7], faces("wood", skip => "east west"));
cbox([1, 2, 6], [15, 3, 15], faces("wood", skip => "east west south"));
cbox([1, 9, 6], [15, 10, 15], faces("wood", skip => "east west south"));
cbox([1, 15, 6], [15, 17, 15], faces("wood", skip => "east west south"));
cbox([1, 23, 6], [15, 24, 15], faces("wood", skip => "east west south"));
for my $row ([3, 9], [10, 15], [17, 23], [24, 30]) {
	my ($y0, $y1) = @$row;
	my $x = 1;
	while ($x < 14.6) {
		my $w = (0.75, 1, 1, 1.25, 0.75, 1.5)[int(rand(6))];
		$w = 15 - $x if $x + $w > 15;
		last if $w < 0.5;
		if (rand() < 0.07 && $x > 2 && $x < 13) { $x += 1; next; }    # a gap where a book is missing
		my $h = ($y1 - $y0) - (0, 0.5, 0.75, 1, 1.5)[int(rand(5))];
		my $c = int(rand(16));
		my $d = 7 + (0, 0, 0.5, 1)[int(rand(4))];
		cbox([$x, $y0, $d], [$x + $w, $y0 + $h, 15], {
			north => {texture => "#books", uv => [$c, 0, $c + 1, 8]},
			up => {texture => "#books", uv => [$c, 8, $c + 1, 12]},
			east => {texture => "#books", uv => [$c, 12, $c + 1, 16]},
			west => {texture => "#books", uv => [$c, 12, $c + 1, 16]},
		});
		$x += $w;
	}
}
# Split at 16 into the two halves (a box across the line goes in both, cut; its uv cut to match where given).
for my $half (0, 1) {
	my ($lo, $hi) = $half ? (16, 32) : (0, 16);
	for my $c (@case) {
		my ($f, $t, $fc) = @$c;
		next if $t->[1] <= $lo || $f->[1] >= $hi;
		my $y0 = $f->[1] < $lo ? $lo : $f->[1];
		my $y1 = $t->[1] > $hi ? $hi : $t->[1];
		my %faces = %{JSON::PP->new->decode(JSON::PP->new->encode($fc))};
		if ($f->[1] < $lo || $t->[1] > $hi) {
			delete $faces{down} if $f->[1] < $lo;
			delete $faces{up} if $t->[1] > $hi;
			for my $d (qw(north south east west)) {
				next unless $faces{$d} && $faces{$d}{uv};
				my $uv = $faces{$d}{uv};
				my $span = $t->[1] - $f->[1];
				my ($v0, $v1) = ($uv->[1], $uv->[3]);
				$uv->[1] = $v0 + ($v1 - $v0) * ($t->[1] - $y1) / $span;
				$uv->[3] = $v0 + ($v1 - $v0) * ($t->[1] - $y0) / $span;
			}
		}
		box([$f->[0], $y0 - $lo, $f->[2]], [$t->[0], $y1 - $lo, $t->[2]], \%faces);
	}
	model($half ? "bookcase_upper" : "bookcase_lower", $bt);
}

# ---- the can: an eight-sided drum (a cross of two boxes and one turned 45 degrees), rims, handles; the lid apart.
my $ct = {side => "halloween:block/prop/bin_side", lid => "halloween:block/prop/bin_lid", inside => "halloween:block/prop/bin_inside",
	particle => "halloween:block/prop/bin_side"};
sub drum {
	my ($y0, $y1, $r, $tex, %o) = @_;
	my $s = $r * 0.4142;    # half a side of the octagon
	my $f = faces($tex, %o);
	box([8 - $s, $y0, 8 - $r], [8 + $s, $y1, 8 + $r], $f);
	box([8 - $r, $y0, 8 - $s], [8 + $r, $y1, 8 + $s], $f);
	box([8 - $s, $y0, 8 - $r], [8 + $s, $y1, 8 + $r], $f, {angle => 45, axis => "y", origin => [8, 8, 8]});
}
sub can_body {
	drum(0, 14, 6, "side", up => "inside", down => "inside");
	drum(0, 1, 6.3, "side", skip => "up");
	drum(6.5, 7.5, 6.3, "side", skip => "up down");
	drum(13, 14, 6.4, "side", up => "inside");
	box([1, 10, 7], [2.2, 11, 9], faces("side"));
	box([13.8, 10, 7], [15, 11, 9], faces("side"));
}
sub can_lid {
	drum(14, 15, 6.6, "lid", north => "side", south => "side", east => "side", west => "side");
	drum(15, 15.5, 4.5, "lid", north => "side", south => "side", east => "side", west => "side");
	box([6, 15.5, 7.5], [10, 16.5, 8.5], faces("side"));
}
can_body(); can_lid(); model("bin", $ct);
can_body(); model("bin_body", $ct);
can_lid(); model("bin_lid", $ct);

# ---- loose books (lying flat, about the middle of the block so Obstacles can turn them about it).
my $lt = {books => "halloween:block/prop/books", particle => "halloween:block/prop/books"};
sub flat_book {
	my ($c, $x0, $z0, $w, $d, $t, $rot, $y0) = @_;
	$y0 //= 0;
	box([$x0, $y0, $z0], [$x0 + $w, $y0 + $t, $z0 + $d], {
		up => {texture => "#books", uv => [$c, 12, $c + 1, 16]},
		down => {texture => "#books", uv => [$c, 12, $c + 1, 16]},
		north => {texture => "#books", uv => [$c, 8, $c + 1, 12]},
		south => {texture => "#books", uv => [$c, 8, $c + 1, 12]},
		east => {texture => "#books", uv => [$c, 8, $c + 1, 12]},
		west => {texture => "#books", uv => [$c, 0, $c + 1, 8], rotation => 90},
	}, $rot);
}
flat_book(0, 5.5, 5, 5, 6.5, 1.25);
flat_book(9, 6, 5.5, 4, 5.5, 1, {angle => 22.5, axis => "y", origin => [8, 0, 8]}, 1.25);
model("book_a", $lt);
flat_book(1, 5, 4.5, 5.5, 7, 1.5, {angle => -22.5, axis => "y", origin => [8, 0, 8]});
model("book_b", $lt);
# Open, face down: a tent of two covers, pages showing at the ends.
box([4, 0, 5], [8.2, 0.75, 11], {
	up => {texture => "#books", uv => [4, 12, 5, 16]}, down => {texture => "#books", uv => [4, 8, 5, 12]},
	north => {texture => "#books", uv => [4, 8, 5, 12]}, south => {texture => "#books", uv => [4, 8, 5, 12]},
	west => {texture => "#books", uv => [4, 12, 5, 16]}, east => {texture => "#books", uv => [4, 8, 5, 12]},
}, {angle => 22.5, axis => "z", origin => [8, 0, 8]});
box([7.8, 0, 5], [12, 0.75, 11], {
	up => {texture => "#books", uv => [4, 12, 5, 16]}, down => {texture => "#books", uv => [4, 8, 5, 12]},
	north => {texture => "#books", uv => [4, 8, 5, 12]}, south => {texture => "#books", uv => [4, 8, 5, 12]},
	east => {texture => "#books", uv => [4, 12, 5, 16]}, west => {texture => "#books", uv => [4, 8, 5, 12]},
}, {angle => -22.5, axis => "z", origin => [8, 0, 8]});
model("book_open", $lt);

# ---- what spills from the can.
my $tt = {trash => "halloween:block/prop/trash", bag => "halloween:block/prop/bag", particle => "halloween:block/prop/trash"};
sub sheet {
	my ($x0, $z0, $w, $d, $uv, $rot, $y) = @_;
	$y //= 0.05;
	box([$x0, $y, $z0], [$x0 + $w, $y + 0.1, $z0 + $d], {up => {texture => "#trash", uv => $uv}, down => {texture => "#trash", uv => $uv}}, $rot);
}
sheet(3, 3, 6, 7, [8, 0, 16, 8], {angle => 22.5, axis => "y", origin => [6, 0, 6]});
sheet(8, 7, 5, 6, [0, 0, 8, 8], {angle => -45, axis => "y", origin => [10, 0, 10]}, 0.2);
sheet(5, 9, 4, 4, [10, 2, 14, 6], undef, 0.35);
model("papers", $tt);
# A bag: a fat lump and its knotted neck.
box([4, 0, 4], [12, 5, 11], faces("bag"), {angle => 22.5, axis => "y", origin => [8, 0, 8]});
box([5, 4.5, 5], [11, 6.5, 10], faces("bag"), {angle => 22.5, axis => "y", origin => [8, 0, 8]});
box([7, 6.5, 6.5], [9, 8, 8.5], faces("bag"));
model("bag", $tt);
# Rubbish: crumpled balls, the can on its side, the bottle, a cardboard scrap, newsprint.
my $paper = faces("trash", map { $_ => {texture => "#trash", uv => [1, 1, 6, 6]} } qw(north south east west up down));
box([3, 0, 3], [5, 2, 5], $paper);
box([10, 0, 4], [11.5, 1.5, 5.5], $paper, {angle => 45, axis => "y", origin => [10.75, 0, 4.75]});
box([6, 0, 11], [8, 2, 13], $paper, {angle => 22.5, axis => "y", origin => [7, 0, 12]});
box([6, 0, 6], [7.5, 1.5, 9], {map { $_ => {texture => "#trash", uv => [0, 8, 8, 16]} } qw(north south east west up down)}, {angle => -22.5, axis => "y", origin => [7, 0, 7.5]});
box([10, 0, 9], [11, 1, 13], {map { $_ => {texture => "#trash", uv => [8, 8, 16, 12]} } qw(north south east west up down)}, {angle => 22.5, axis => "y", origin => [10.5, 0, 11]});
box([10.6, 0.25, 8], [10.4 + 0.8, 0.75, 9], {map { $_ => {texture => "#trash", uv => [8, 8, 9, 9]} } qw(north south east west up down)}, {angle => 22.5, axis => "y", origin => [10.5, 0, 11]});
sheet(2, 8, 4, 4, [8, 12, 16, 16]);
sheet(11, 1, 4, 3, [8, 0, 16, 6], {angle => 22.5, axis => "y", origin => [13, 0, 2.5]});
model("trash", $tt);

# ---- the wreck of a kicked bookcase: broken boards lying crossed, splintered.
my $pt = {planks => "halloween:block/prop/planks", wood => "halloween:block/prop/walnut", particle => "halloween:block/prop/walnut"};
box([1, 0, 3], [15, 1, 5], faces("planks"), {angle => 22.5, axis => "y", origin => [8, 0, 4]});
box([2, 0.6, 7], [13, 1.6, 9], faces("wood"), {angle => -22.5, axis => "y", origin => [7.5, 0, 8]});
box([4, 0, 11], [14, 1, 13], faces("planks"), {angle => 45, axis => "y", origin => [9, 0, 12]});
box([3, 1.2, 9.5], [9, 2, 11], faces("wood"), {angle => 22.5, axis => "y", origin => [6, 1, 10]});
model("planks", $pt);

# ---------------------------------------------------------------- block states
sub write_json { my ($path, $v) = @_; open my $f, ">", $path or die "$path: $!"; print $f JSON::PP->new->canonical->pretty->indent_length(1)->encode($v); close $f; }
my %rotY = (north => 0, east => 90, south => 180, west => 270);
my %pv;
for my $facing (sort keys %rotY) {
	for my $half (qw(lower upper)) {
		for my $kind (qw(bookcase bin)) {
			my $m = $kind eq "bin" ? ($half eq "lower" ? "bin" : "bookcase_upper") : "bookcase_$half";
			my $v = {model => "halloween:block/prop/$m"};
			$v->{y} = $rotY{$facing} if $rotY{$facing};
			$pv{"facing=$facing,half=$half,kind=$kind"} = $v;
		}
	}
}
write_json("$root/blockstates/prop.json", {variants => \%pv});
my %dv;
for my $piece (qw(cell bin_body bin_lid book_a book_b book_open papers bag trash planks)) {
	$dv{"piece=$piece"} = {model => "halloween:block/prop/" . ($piece eq "cell" ? "cell" : $piece)};
}
write_json("$root/blockstates/debris.json", {variants => \%dv});
# Where something lies across the way: drawn by its displays, the block itself is only there to be bumped into.
write_json("$mod/cell.json", {textures => {particle => "halloween:block/prop/walnut"}});
print "props written to $mod and $tex\n";
