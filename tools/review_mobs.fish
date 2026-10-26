#!/usr/bin/fish
# Отдельное окно; оценки и кадры остаются в notes/mob-review.
set -l script_dir (dirname (status filename))
cd "$script_dir/.."; or exit 1
./gradlew runReviewMobs -PinstalledAssets="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/assets" -Porg.gradle.java.installations.paths="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-epsilon" -q
