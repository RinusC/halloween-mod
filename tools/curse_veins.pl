#!/usr/bin/perl
# The curse on a survivor's screen (client.HwHud): a dark red vignette with veins creeping in from the edges.
#   perl tools/curse_veins.pl src/main/resources/assets/halloween/textures/gui
# Writes curse_veins_1.png .. curse_veins_4.png (512 x 288, straight alpha), one per stage, each reaching further in.
use strict;
use warnings;
use Compress::Zlib;

my $dir = shift or die "usage: curse_veins.pl <gui texture dir>\n";
my ($W, $H) = (512, 288);

sub png {
	my ($name, $px) = @_;
	my $raw = '';
	for my $y (0 .. $H - 1) {
		$raw .= "\0";
		for my $x (0 .. $W - 1) {
			my $p = $px->[$y * $W + $x];
			$raw .= pack 'C4', map { my $v = int($_ + 0.5); $v < 0 ? 0 : $v > 255 ? 255 : $v } @$p;
		}
	}
	my $chunk = sub { my ($t, $d) = @_; pack('N', length $d) . $t . $d . pack('N', crc32($t . $d)) };
	open my $o, '>:raw', "$dir/$name.png" or die $!;
	print $o "\x89PNG\r\n\x1a\n", $chunk->('IHDR', pack('NNCCCCC', $W, $H, 8, 6, 0, 0, 0)), $chunk->('IDAT', compress($raw)), $chunk->('IEND', '');
	close $o;
	print "$name.png\n";
}

# Veins: [x, y, angle, width, length, depth], walked in from the edge, forking. Stored as a list of dabs so each stage
# can draw the ones that reach no further than it allows.
srand(13);
my @dabs;    # [x, y, radius, reach]  reach: how far in (0 at the edge .. 1 at the middle)
sub reach {
	my ($x, $y) = @_;
	# Elliptic: 0 at the corners of an ellipse round the screen, 1 at the middle.
	my ($u, $v) = (($x / $W - 0.5) * 2, ($y / $H - 0.5) * 2);
	my $e = sqrt($u * $u * 0.7 + $v * $v * 0.7);
	return 1 - ($e > 1.2 ? 1.2 : $e) / 1.2;
}
sub vein {
	my ($x, $y, $ang, $wd, $len, $depth) = @_;
	for my $s (0 .. $len) {
		$ang += (rand(2) - 1) * 0.28;
		$x += cos($ang) * 1.6;
		$y += sin($ang) * 1.6;
		last if $x < -4 || $y < -4 || $x > $W + 4 || $y > $H + 4;
		my $r = $wd * (1 - 0.75 * $s / ($len + 1));
		push @dabs, [$x, $y, $r, reach($x, $y)];
		if ($depth < 4 && rand() < 0.035) {
			vein($x, $y, $ang + (rand() < 0.5 ? 1 : -1) * (0.4 + rand(0.7)), $r * 0.8, int($len * (0.4 + rand(0.3))), $depth + 1);
		}
	}
}
for (1 .. 26) {
	# A start somewhere on the edge, heading in towards the middle (more of them at the corners).
	my $t = rand(4);
	my ($x, $y) = $t < 1 ? (rand($W), 0) : $t < 2 ? (rand($W), $H) : $t < 3 ? (0, rand($H)) : ($W, rand($H));
	my $ang = atan2($H / 2 - $y, $W / 2 - $x) + (rand(2) - 1) * 0.6;
	vein($x, $y, $ang, 1.3 + rand(1.1), 50 + int(rand(80)), 0);
}

for my $stage (1 .. 4) {
	my $limit = (0.2, 0.32, 0.46, 0.6)[$stage - 1];
	my @a = (0) x ($W * $H);    # vein coverage 0..1
	my @core = (0) x ($W * $H);
	for my $d (@dabs) {
		my ($x, $y, $r, $re) = @$d;
		next if $re > $limit;
		my $fade = $re > $limit - 0.14 ? ($limit - $re) / 0.14 : 1;
		my $rr = $r + 1.2;
		for my $yy (int($y - $rr) .. int($y + $rr)) {
			next if $yy < 0 || $yy >= $H;
			for my $xx (int($x - $rr) .. int($x + $rr)) {
				next if $xx < 0 || $xx >= $W;
				my $dist = sqrt(($xx + 0.5 - $x)**2 + ($yy + 0.5 - $y)**2);
				my $cov = $dist < $r ? 1 : $dist < $rr ? ($rr - $dist) / 1.2 : 0;
				$cov *= $fade;
				my $i = $yy * $W + $xx;
				$a[$i] = $cov if $cov > $a[$i];
				my $c = $dist < $r * 0.35 ? $fade : 0;
				$core[$i] = $c if $c > $core[$i];
			}
		}
	}
	my @px;
	for my $y (0 .. $H - 1) {
		for my $x (0 .. $W - 1) {
			my $i = $y * $W + $x;
			my $re = reach($x, $y);
			# The vignette: dark, red-black, thickest at the edge.
			my $v = $re >= $limit * 1.4 ? 0 : (1 - $re / ($limit * 1.4))**1.8 * (0.45 + 0.1 * $stage);
			my $va = $a[$i] * (0.55 + 0.25 * $stage / 4);
			my $alpha = 1 - (1 - $v) * (1 - $va);
			my @c = (22, 2, 4);
			if ($alpha > 0) {
				my $kv = $va / $alpha;
				@c = map { $c[$_] * (1 - $kv) + (70, 5, 10)[$_] * $kv } 0 .. 2;
				@c = map { $c[$_] * (1 - $core[$i]) + (150, 16, 20)[$_] * $core[$i] } 0 .. 2;
			}
			$px[$i] = [@c, $alpha * 255];
		}
	}
	png("curse_veins_$stage", \@px);
}
