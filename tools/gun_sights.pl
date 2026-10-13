#!/usr/bin/perl
# Iron sights on the long guns' 3D models (made in Blockbench; the revolver has its own): a front post near the
# muzzle and a notched rear sight, solid blocks one can line up when aiming (client.ViewArms aims along their
# tops: keep the numbers there in step). The rifle's own sights are flat planes, edge-on from behind; the shotgun
# had only a bead. Elements named "sight_*" are replaced each run, so it can be run again.
#
#   perl tools/gun_sights.pl src/main/resources/assets/halloween/models/item
use strict;
use warnings;
use JSON::PP;

my $dir = shift or die "usage: gun_sights.pl <models/item>\n";

# Per gun: a patch of dark steel in its texture (UV, model units) the sights are painted with, and the blocks: [from, to].
my %guns = (
	rifle_3d => {steel => [2.25, 3, 2.5, 3.25], blocks => [
		# Front post on a little ramp, just short of the muzzle.
		[[7.045, 10.6, 24.5], [7.445, 11.55, 24.9]],
		[[6.945, 10.5, 24.2], [7.545, 10.95, 25.2]],
		# Rear sight: a base across the barrel and two ears with the notch between them.
		[[6.795, 10.6, 7.0], [7.695, 11.15, 7.3]],
		[[6.795, 11.15, 7.0], [7.02, 11.55, 7.3]],
		[[7.47, 11.15, 7.0], [7.695, 11.55, 7.3]],
	]},
	shotgun_3d => {steel => [1.25, 0, 1.75, 0.5], blocks => [
		# A taller front post in place of the bead.
		[[7.85, 10.3, 21.45], [8.15, 12.6, 21.85]],
		# A notch on the back of the receiver.
		[[7.5, 11.7, 10.6], [8.5, 12.2, 11.0]],
		[[7.5, 12.2, 10.6], [7.75, 12.6, 11.0]],
		[[8.25, 12.2, 10.6], [8.5, 12.6, 11.0]],
	], lower => [
		# The tang at the back of the receiver (up to 12.9).
		[[7.6539, 11.2838, 2.7751], 12.2],
	]},
);

for my $name (sort keys %guns) {
	my $file = "$dir/$name.json";
	open my $in, '<', $file or die "$file: $!";
	my $json = decode_json(do { local $/; <$in> });
	close $in;
	my @kept = grep { !defined $_->{name} || $_->{name} !~ /^sight_/ } @{$json->{elements}};
	my $uv = $guns{$name}{steel};
	my $n = 0;
	for my $b (@{$guns{$name}{blocks}}) {
		my %faces = map { $_ => {texture => '#0', uv => [@$uv]} } qw(north east south west up down);
		push @kept, {name => "sight_" . $n++, from => $b->[0], to => $b->[1], faces => \%faces};
	}
	if (my $low = $guns{$name}{lower}) {
		# Parts that stand up above the line of the sights near the eye are cut down to it.
		for my $e (@kept) {
			for my $l (@$low) {
				if (abs($e->{from}[0] - $l->[0][0]) < 1e-3 && abs($e->{from}[1] - $l->[0][1]) < 1e-3 && abs($e->{from}[2] - $l->[0][2]) < 1e-3) {
					$e->{to}[1] = $l->[1];
				}
			}
		}
	}
	$json->{elements} = \@kept;
	open my $out, '>', $file or die "$file: $!";
	print $out JSON::PP->new->canonical->pretty->encode($json);
	close $out;
	printf "%s: %d sight blocks\n", $name, $n;
}
