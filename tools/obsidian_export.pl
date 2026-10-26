#!/usr/bin/perl
# Lays the project out in the Obsidian vault as short notes, so that only the one needed is read:
#   Дизайн/<тема>/<раздел>.md  every "## " section of DESIGN.md, sorted into topics by its heading;
#   Дизайн/<тема>.md            a contents note per topic (its sections, oldest first);
#   Код/<пакет>.md              each source file with the first sentence of its class comment;
#   00 Карта проекта.md         the way in: topics, code, the notes kept by hand.
# Notes written by hand (Рабочий процесс, anything outside these folders) are left alone. Run again after
# DESIGN.md or the code changes; it rewrites only what it owns.
#
#   perl tools/obsidian_export.pl D:/obsidian/obs_halloween/Halloween
use strict;
use warnings;
use utf8;
use File::Path qw(make_path remove_tree);
use File::Find;

my $vault = shift or die "usage: obsidian_export.pl <vault>\n";
binmode STDOUT, ':encoding(UTF-8)';

# ---------------------------------------------------------------- DESIGN.md by topic
my @topics = (
	['VR', qr/VR|Vivecraft/],
	['Демогоргон', qr/Демогоргон/],
	['Уродец', qr/Уродец|уродц/],
	['Майкл', qr/Майкл|Shape Jump|Поступ/],
	['Оружие', qr/Огнестрел|Оружие|револьвер|мушк/],
	['Лобби', qr/Лобби/],
	['Ивенты', qr/Ивент|Полночь|пожар|землетряс/],
	['Боты и горожане', qr/Боты|[Бб]от[ыа]? |горожан|Горожан/],
	['Матч и выходы', qr/Матч|матч|побег|выход|Машина|машин|[Пп]олиц|фургон|погреб|Halloween: The Game/],
	['Город', qr/Хэддонфилд|город|Карта|Мебель|дома|улиц/],
	['Выжившие', qr/Выносливость|[Пп]аник|[Шш]каф|захват|Толчок|Руки|Дыхание|[Пп]ерк|проверк|Проверк|ловушк/],
	['Интерфейс, звук, графика', qr/Скины|Музыка|Мини-карта|Крики|Кровь|фонарик|темнота|катсцен|интерфейс|Модели|3D|Звуки/],
);

open my $d, '<:encoding(UTF-8)', 'DESIGN.md' or die "run from the project root: $!";
my @lines = <$d>;
close $d;
my @sections;
my $cur;
for my $i (0 .. $#lines) {
	my $l = $lines[$i];
	if ($l =~ /^## (.+?)\s*$/) {
		$cur = {title => $1, start => $i + 1, body => []};
		push @sections, $cur;
	} elsif ($cur) {
		push @{$cur->{body}}, $l;
	}
}

remove_tree("$vault/Дизайн");
my %byTopic;
my @vrElsewhere;    # sections of other topics that also touch VR, listed in the VR contents note
my $n = 0;
for my $s (@sections) {
	$n++;
	my $title = $s->{title};
	my $done = $title =~ s/\s*\(готово\)\s*$//;
	my $topic = $n <= 4 ? 'Основа' : 'Правки и прочее';
	if ($n > 4) {
		for my $t (@topics) {
			if ($title =~ $t->[1]) {
				$topic = $t->[0];
				last;
			}
		}
	}
	(my $file = $title) =~ s/[\\\/:*?"<>|`#^\[\]]/ /g;
	$file =~ s/\s+/ /g;
	$file =~ s/^\s+|\s+$//g;
	$file = substr($file, 0, 90);
	$file = sprintf("%02d %s", $n, $file);
	my $body = join('', @{$s->{body}});
	$body =~ s/^\s+//;
	$body =~ s/\s+$//;
	make_path("$vault/Дизайн/$topic");
	open my $o, '>:encoding(UTF-8)', "$vault/Дизайн/$topic/$file.md" or die "$file: $!";
	print $o "---\ntopic: \"$topic\"\nsource: \"DESIGN.md:$s->{start}\"\norder: $n\n" . ($done ? "status: готово\n" : '') . "---\n";
	print $o "# $title\n\nТема: [[Дизайн/$topic|$topic]]\n\n$body\n";
	close $o;
	push @{$byTopic{$topic}}, $file;
	push @vrElsewhere, "$topic/$file" if $topic ne 'VR' && $body =~ /\bVR\b|Vivecraft/;
}
for my $topic (sort keys %byTopic) {
	open my $o, '>:encoding(UTF-8)', "$vault/Дизайн/$topic.md" or die;
	print $o "# $topic\n\nРазделы дизайна по порядку (старые сверху; поздние правят ранние).\n\n";
	print $o "- [[Дизайн/$topic/$_|$_]]\n" for @{$byTopic{$topic}};
	if ($topic eq 'VR') {
		print $o "\n## VR в разделах других тем\n";
		print $o "- [[Дизайн/$_|" . (split m{/}, $_)[1] . "]]\n" for @vrElsewhere;
	}
	close $o;
}

# ---------------------------------------------------------------- the code, file by file
my %pkg;
find(sub {
	return unless /\.java$/;
	my $path = $File::Find::name;
	open my $j, '<:encoding(UTF-8)', $_ or return;
	my $src = do { local $/; <$j> };
	close $j;
	my ($pk) = $src =~ /^package\s+([\w.]+);/m;
	my $doc = '';
	if ($src =~ m{/\*\*(.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+|final\s+|abstract\s+)*(?:class|interface|record|enum)\s}s) {
		($doc = $1) =~ s/^\s*\*\s?//mg;
		$doc =~ s/\{\@(?:link|code)\s+([^}]+)\}/$1/g;
		$doc =~ s/<[^>]+>//g;
		$doc =~ s/\s+/ /g;
		$doc =~ s/^\s+//;
		$doc = $1 if $doc =~ /^(.*?[.!?])(?:\s|$)/;
		$doc = substr($doc, 0, 217) . '...' if length($doc) > 220;
	}
	my $lines = () = $src =~ /\n/g;
	(my $rel = $path) =~ s{^\./}{};
	push @{$pkg{$pk // '?'}}, [$rel, $lines, $doc];
}, 'src');
remove_tree("$vault/Код");
make_path("$vault/Код");
for my $p (sort keys %pkg) {
	open my $o, '>:encoding(UTF-8)', "$vault/Код/$p.md" or die;
	print $o "# $p\n\nФайл — строк — о чём (первая фраза комментария класса). Обновляется tools/obsidian_export.pl.\n\n";
	for my $f (sort { $a->[0] cmp $b->[0] } @{$pkg{$p}}) {
		my ($name) = $f->[0] =~ m{([^/]+)\.java$};
		print $o "- **$name** (`$f->[0]`, $f->[1]) — $f->[2]\n";
	}
	close $o;
}

# the VR topic also gathers its code and the shader notes (hand-written, they plan for the headset too)
if ($byTopic{VR}) {
	open my $o, '>>:encoding(UTF-8)', "$vault/Дизайн/VR.md" or die;
	print $o "\n## Код VR\n";
	for my $p (sort keys %pkg) {
		for my $f (sort { $a->[0] cmp $b->[0] } @{$pkg{$p}}) {
			my ($name) = $f->[0] =~ m{([^/]+)\.java$};
			print $o "- **$name** ([[Код/$p|$p]]) — $f->[2]\n" if $name =~ /^Vr|Vr(?:[A-Z]|$)/;
		}
	}
	print $o "\n## Заметки\n";
	print $o "- [[$_]]\n" for grep { -e "$vault/$_.md" } 'shaders-plan', 'shaders-recon';
	close $o;
}

# ---------------------------------------------------------------- the way in
open my $m, '>:encoding(UTF-8)', "$vault/00 Карта проекта.md" or die;
print $m <<"EOF";
# Halloween — карта проекта

Приватный Fabric-мод для Minecraft 26.2 (папка `C:\\Users\\RinusC\\Desktop\\dbd`, GitHub `RinusC/halloween-mod`).
Игра по мотивам Halloween (1978): выжившие убегают из города, маньяк — Майкл (бот или игрок) или Демогоргон.

- [[Рабочий процесс]] — сборка, установка, проверки, коммит, правила (вести вручную)
- [[Журнал]] — что сделано по датам (вести вручную)

## Дизайн по темам
EOF
print $m "- [[Дизайн/$_|$_]] (" . scalar(@{$byTopic{$_}}) . ")\n" for sort keys %byTopic;
print $m "\n## Код по пакетам\n";
print $m "- [[Код/$_|$_]] (" . scalar(@{$pkg{$_}}) . " файлов)\n" for sort keys %pkg;
print $m "\nРазделы `Дизайн/` и `Код/` пишет `tools/obsidian_export.pl` из DESIGN.md и исходников — их руками не править.\n";
close $m;
printf "%d sections in %d topics, %d packages\n", scalar(@sections), scalar(keys %byTopic), scalar(keys %pkg);
