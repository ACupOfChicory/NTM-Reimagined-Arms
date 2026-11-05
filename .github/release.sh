#!/usr/bin/env bash
# Builds one branch of NTM Reimagined Arms and releases it when its CHANGELOG has a new heading
# (.github/workflows/build.yml runs `release.sh ci`). The same file is on every branch; the branch picks the target.
#
#   release.sh target <branch>                 the target a branch builds (26.2, 1.7.10, 1.12.2, 1.21.1-de) or nothing
#   release.sh deps <target> <tree>            fetches the jars of .github/deps.tsv into the tree, checked by sha256
#   release.sh build <target> <tree>          builds the tree with Gradle, then prints the mod's jar
#   release.sh heading <target>                the newest release heading of CHANGELOG.md for the target, or nothing
#   release.sh commit <heading>                the first commit (first parents) whose CHANGELOG.md has that heading
#   release.sh notes <target> <heading> <commit>   the release text
#   release.sh ci                              all of it, in the workflow
#
# A release is a new heading "## X<build>" at the top of CHANGELOG.md (main) or "## X<B>.<P>" (a port; the headings
# main's merges bring along have no dot and are skipped there). It is released from the commit that brought the
# heading in, and the version Gradle counts there must be the heading, or the run fails. A tag that is already there
# means it was released: nothing to do.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_URL="https://github.com/${GITHUB_REPOSITORY:-ACupOfChicory/NTM-Reimagined-Arms}"

die() { echo "::error::$*" >&2; exit 1; }

# the settings of a target: where it builds, what its jar and tags are called
conf() {
    case "$1" in
        26.2)
            DIR=.; ARGS=(build -PnoAssets); LIBS=versions/26.2/build/libs; SUFFIX=-mc26.2; HEAD_RE='X[0-9]+'
            BRANCH=main; TITLE="26.2 NeoForge"; MC="Minecraft 26.2 (NeoForge)"; PORT= ;;
        1.7.10)
            DIR=port/1.7.10; ARGS=(build); LIBS=port/1.7.10/build/libs; SUFFIX=-mc1.7.10-forge; HEAD_RE='X[0-9]+\.[0-9]+'
            BRANCH=port/1.7.10-forge; TITLE="1.7.10 Forge"; MC="Minecraft 1.7.10 (Forge)"; PORT=1 ;;
        1.12.2)
            DIR=port/1.12.2; ARGS=(build); LIBS=port/1.12.2/build/libs; SUFFIX=-mc1.12.2-ce; HEAD_RE='X[0-9]+\.[0-9]+'
            BRANCH=port/1.12.2-ce; TITLE="1.12.2 Forge and Cleanroom"; MC="Minecraft 1.12.2 (Forge and Cleanroom)"; PORT=1 ;;
        1.21.1-de)
            DIR=port/1.21.1-de; ARGS=(build); LIBS=port/1.21.1-de/build/libs; SUFFIX=-mc1.21.1-de; HEAD_RE='X[0-9]+\.[0-9]+'
            BRANCH=port/1.21.1-de; TITLE="1.21.1 NeoForge, NTM: Decentralized Edition"
            MC="Minecraft 1.21.1 (NeoForge) with NTM: Decentralized Edition"; PORT=1 ;;
        *) die "unknown target '$1'" ;;
    esac
}

# a branch and the ci/ branch that tries the workflow out for it
target() {
    case "$1" in
        main|ci/releases) echo 26.2 ;;
        port/1.7.10-forge|ci/releases-1.7.10) echo 1.7.10 ;;
        port/1.12.2-ce|ci/releases-1.12.2) echo 1.12.2 ;;
        port/1.21.1-de|ci/releases-1.21.1-de) echo 1.21.1-de ;;
        *) ;;
    esac
}

sha() { sha256sum "$1" | cut -d' ' -f1; }

deps() {
    local t=$1 tree=$2 cache=${DEPS_CACHE:-$HOME/.cache/ntm-arms-deps} target path src want
    mkdir -p "$cache"
    while IFS=$'\t' read -r target path src want; do
        [[ -z "$target" || "$target" == \#* || "$target" != "$t" ]] && continue
        mkdir -p "$(dirname "$tree/$path")"
        if [[ "$src" == jar:* ]]; then
            local inner=${src#jar:}
            unzip -p "$tree/${inner%%!/*}" "${inner#*!/}" > "$tree/$path" || die "$path: no ${inner#*!/} in ${inner%%!/*}"
        elif [[ -f "$cache/$want" && "$(sha "$cache/$want")" == "$want" ]]; then
            cp "$cache/$want" "$tree/$path"
        else
            curl -fsSL --retry 3 --retry-delay 5 -A "NTM-Reimagined-Arms-CI" -o "$cache/$want.part" "$src" || die "$path: could not fetch $src"
            mv "$cache/$want.part" "$cache/$want"
            cp "$cache/$want" "$tree/$path"
        fi
        local got; got=$(sha "$tree/$path")
        [[ "$got" == "$want" ]] || die "$path: sha256 $got, expected $want (from $src)"
        echo "dep ok: $path"
    done < "$HERE/deps.tsv"
}

# Gradle output goes to stderr, the jar's path to stdout
build() {
    local t=$1 tree=$2 java=()
    conf "$t"
    # the JDKs setup-java put in: Gradle does not look in the runner's tool cache by itself
    local paths; paths=$(printf '%s,' ${JAVA_HOME_8_X64:-} ${JAVA_HOME_21_X64:-} ${JAVA_HOME_25_X64:-}); paths=${paths%,}
    [[ -n "$paths" ]] && java=("-Porg.gradle.java.installations.paths=$paths")
    (cd "$tree/$DIR" && ./gradlew "${ARGS[@]}" ${java[@]+"${java[@]}"} --console=plain) >&2
    tests "$tree/$DIR" >&2
    local jars; jars=$(find "$tree/$LIBS" -maxdepth 1 -type f -regextype posix-extended \
        -regex ".*/reimagined-arms-X[0-9]+(\.[0-9]+)?${SUFFIX//./\\.}\.jar")
    [[ $(printf '%s\n' "$jars" | grep -c .) -eq 1 ]] || die "expected one reimagined-arms-X*$SUFFIX.jar in $LIBS, found: ${jars:-none}"
    echo "$jars"
}

# what the tests did, from their reports: totals per task and the names of the skipped ones
tests() {
    python3 - "$1" <<'PY' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
import glob, os, sys, xml.etree.ElementTree as E
root = sys.argv[1]
for task in sorted(glob.glob(os.path.join(root, "**/build/test-results/*/"), recursive=True)):
    n = s = f = 0; skipped = []
    for x in glob.glob(task + "*.xml"):
        r = E.parse(x).getroot()
        n += int(r.get("tests", 0)); s += int(r.get("skipped", 0))
        f += int(r.get("failures", 0)) + int(r.get("errors", 0))
        skipped += [r.get("name", "").rsplit(".", 1)[-1] + "." + c.get("name") for c in r.iter("testcase") if c.find("skipped") is not None]
    print(f"Tests {os.path.relpath(task, root)}: {n} run, {s} skipped, {f} failed" + (": skipped " + ", ".join(sorted(skipped)) if skipped else ""))
PY
}

heading() {
    conf "$1"
    grep -m1 -oE "^## ${HEAD_RE}[[:space:]]*$" CHANGELOG.md | awk '{print $2}' || true
}

commit() {
    local h=$1 re found=
    re="^## ${h//./\\.}[[:space:]]*$"
    for c in $(git rev-list --first-parent HEAD); do
        git show "$c:CHANGELOG.md" 2>/dev/null | grep -qE "$re" || break
        found=$c
    done
    [[ -n "$found" ]] || die "no commit has '## $h' in CHANGELOG.md"
    echo "$found"
}

# the heading's part of CHANGELOG.md at that commit
section() {
    git show "$2:CHANGELOG.md" | awk -v h="## $1" '
        $0 ~ /^## / { if (on) exit; if ($1 " " $2 == h) { on = 1; next } }
        on { print }' | sed -e '/./,$!d'
}

notes() {
    local t=$1 h=$2 c=$3
    conf "$t"
    local port=""
    [[ -n "$PORT" ]] && port=" The port doesn't cover every feature of the 26.2 version yet."
    echo "**Alpha.** Build $h of NTM Reimagined Arms for $MC.$port Expect bugs; back up your world."
    echo
    echo "### Requirements"
    cat "$HERE/requirements/$t.md"
    echo
    echo "### Changes"
    printf '%s\n' "$(section "$h" "$c")"
    echo
    echo "Source: [\`${c:0:7}\`]($REPO_URL/tree/$c) on branch \`$BRANCH\`. Full history: [CHANGELOG.md]($REPO_URL/blob/$BRANCH/CHANGELOG.md)"
}

summary() { echo "$*"; [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && echo "$*" >> "$GITHUB_STEP_SUMMARY"; return 0; }

ci() {
    local branch=${GITHUB_REF_NAME:?} root=${GITHUB_WORKSPACE:-$PWD} out=${RUNNER_TEMP:-/tmp}/ntm-arms t
    t=$(target "$branch")
    [[ -n "$t" ]] || { summary "Branch $branch has no build."; return 0; }
    conf "$t"
    mkdir -p "$out/jar"

    deps "$t" "$root"
    local jar; jar=$(build "$t" "$root")
    cp "$jar" "$out/jar/"
    summary "Built $(basename "$jar") ($t) from ${GITHUB_SHA:0:7}."

    local h; h=$(heading "$t")
    [[ -n "$h" ]] || { summary "No release heading ($HEAD_RE) in CHANGELOG.md: nothing to release."; return 0; }
    local tag=$h$SUFFIX
    # a ci/ branch: what would be released, or a test release under ci-test- that is deleted after
    local mode=release
    if [[ "$branch" != "$BRANCH" ]]; then
        if [[ "${TEST_RELEASE:-false}" == true ]]; then mode=test; tag=ci-test-$tag; else mode=dry; fi
    fi
    if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null; then
        summary "$tag is released already."
        return 0
    fi

    local c; c=$(commit "$h")
    local rel=$jar
    if [[ "$c" != "$(git rev-parse HEAD)" ]]; then
        # the heading came in an earlier commit: that commit is the release
        local tree="$out/release-tree"
        git worktree add --detach "$tree" "$c" >&2
        deps "$t" "$tree"
        rel=$(build "$t" "$tree")
    fi
    [[ "$(basename "$rel")" == "reimagined-arms-$h$SUFFIX.jar" ]] ||
        die "CHANGELOG.md says $h at ${c:0:7}, but the build there is $(basename "$rel"): no release"

    notes "$t" "$h" "$c" > "$out/notes.md"
    local title="$h alpha ($TITLE)"
    if [[ "$mode" == dry ]]; then
        summary "Dry run: would release $tag from ${c:0:7}: \"$title\", $(basename "$rel")."
        cat "$out/notes.md"
        [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && { echo; cat "$out/notes.md"; } >> "$GITHUB_STEP_SUMMARY"
        return 0
    fi
    gh release create "$tag" "$rel" --target "$c" --title "$title" --notes-file "$out/notes.md" --prerelease
    summary "Released $tag from ${c:0:7}${mode/release/}."
}

cmd=${1:-}; shift || true
case "$cmd" in
    target) target "$@" ;;
    deps) deps "$@" ;;
    build) build "$@" ;;
    heading) heading "$@" ;;
    commit) commit "$@" ;;
    notes) notes "$@" ;;
    ci) ci ;;
    *) sed -n '2,12p' "$0"; exit 2 ;;
esac
