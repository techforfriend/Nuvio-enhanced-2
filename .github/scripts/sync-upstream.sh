#!/usr/bin/env bash
set -e

git remote add luqman https://github.com/luqmanfadlli/NuvioMobile-Enhanced.git 2>/dev/null || true
git fetch -q luqman enhanced 2>&1 | grep -v 'submodule.MPVKit' || true

LATEST_LUQMAN_SHA="$(git rev-parse luqman/enhanced)"
LAST_SYNC_FILE=".github/luqman_last_sync.sha"
LAST_SYNC_SHA=""
if [[ -f "${LAST_SYNC_FILE}" ]]; then
  LAST_SYNC_SHA="$(tr -d '[:space:]' < "${LAST_SYNC_FILE}")"
fi

echo "Latest Luqman SHA: ${LATEST_LUQMAN_SHA}"
echo "Last Synced SHA:   ${LAST_SYNC_SHA}"

if [[ "${LATEST_LUQMAN_SHA}" == "${LAST_SYNC_SHA}" ]]; then
  echo "✅ Everything is already up to date with Luqman's repository."
  exit 0
fi

NEW_COMMITS="$(git log ${LAST_SYNC_SHA}..${LATEST_LUQMAN_SHA} --oneline 2>/dev/null || git log -n 10 luqman/enhanced --oneline)"
echo "Found new commits:"
echo "${NEW_COMMITS}"

BRANCH_NAME="sync/luqman-upstream"
git checkout -B "${BRANCH_NAME}"

# Merge upstream, skipping conflicted files instead of committing
# conflict markers, and never amending the previous commit.
#
# Rules, in order:
#  1. Files merged cleanly (including brand-new upstream files) are kept.
#  2. Files with a merge conflict are SKIPPED: our version is kept as-is.
#  3. Skipped files are listed in the PR body, so they can be ported later
#     by hand instead of silently disappearing.
#  4. The tracking SHA is updated in the same merge commit, not by amend.
#  5. PROTECTED paths below are never tampered with by a sync, even when
#     upstream changed them cleanly (no conflict). After the merge, every
#     protected path that the merge changed is restored to OUR version
#     and listed in the PR body as protected-skipped. Files that only
#     exist in this repo are already untouched by a merge; this rule is
#     for the shared files where this repo has its own custom work.
MERGE_STATUS="Clean merge (No conflicts)"
SKIPPED_FILES=""

# Try a normal merge first, without committing yet. If the two histories
# are unrelated, fall back to --allow-unrelated-histories; the same
# conflict-skip rules below still apply.
set +e
git merge --no-commit --no-ff luqman/enhanced
MERGE_EXIT=$?
if [[ ${MERGE_EXIT} -ne 0 ]] && git log --oneline -1 luqman/enhanced >/dev/null 2>&1; then
  # A failed merge may already be in progress; abort it cleanly before
  # retrying, otherwise the second merge refuses to start.
  git merge --abort 2>/dev/null || true
  git merge --no-commit --no-ff --allow-unrelated-histories luqman/enhanced
  MERGE_EXIT=$?
fi
set -e

if [[ ${MERGE_EXIT} -eq 0 ]]; then
  # Either a clean merge, or "Already up to date" (no merge in progress).
  # In the latter case there is nothing to commit yet; the SHA update
  # below still creates a commit so the next run does not repeat this PR.
  if git rev-parse -q --verify MERGE_HEAD >/dev/null 2>&1; then
    echo "Merge completed without conflicts."
  else
    echo "Upstream is already an ancestor; only the tracking SHA will change."
  fi
else
  echo "⚠️ Merge conflicts detected. Skipping conflicted files, keeping our version."
  MERGE_STATUS="Partial merge - conflicted files skipped (our version kept)"
  # --diff-filter=U lists only files still in conflict.
  SKIPPED_FILES="$(git diff --name-only --diff-filter=U || true)"
  echo "Skipped files:"
  echo "${SKIPPED_FILES}"
  # Keep our version for every conflicted path. For a path deleted by
  # us but modified upstream, --ours leaves it deleted, which is the
  # intended "skip" outcome; guard each step so one odd path cannot
  # abort the whole sync.
  while IFS= read -r conflicted_file; do
    [[ -z "${conflicted_file}" ]] && continue
    git checkout --ours -- "${conflicted_file}" 2>/dev/null || git rm --cached -- "${conflicted_file}" 2>/dev/null || true
    git add -- "${conflicted_file}" 2>/dev/null || true
  done <<< "${SKIPPED_FILES}"
  # Any path still unmerged after the loop above is a deleted/modified
  # edge case; remove it from the index so the merge can commit.
  REMAINING="$(git diff --name-only --diff-filter=U || true)"
  if [[ -n "${REMAINING}" ]]; then
    echo "Still-unmerged paths, removing from index:"
    echo "${REMAINING}"
    while IFS= read -r remaining_file; do
      [[ -z "${remaining_file}" ]] && continue
      git rm --cached -- "${remaining_file}" 2>/dev/null || true
    done <<< "${REMAINING}"
  fi
fi

# PROTECTED: restore our version for every protected path the merge
# changed, even if it merged cleanly. This is the user's custom work
# from the techforfriend-vs-Luqman comparison: CloudStream full,
# Nuvio Enhanced settings, Home Premium, player bottom-left move,
# lock position, detail fixes, and this repo's own build/sync files.
# Upstream changes to these paths are NOT applied by sync; they are
# listed in the PR body so they can be ported by hand if wanted.
PROTECTED_SKIPPED=""
is_protected_path() {
  case "$1" in
    composeApp/src/androidFull/*|composeApp/src/androidPlaystore/*|composeApp/src/iosFull/*|composeApp/src/iosAppStore/*|composeApp/src/fullCommonMain/*|third_party/*|composeApp/libs/*|composeApp/proguard-cloudstream-full.pro|composeApp/src/commonMain/kotlin/com/nuvio/app/features/cloudstream/*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/NuvioEnhanced*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/CloudStreamSettingsPage.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/HomePremium*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryEnhancedSections.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/FavoritePeople*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/MetaDetailsSeriesStats.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/DetailMetaInfo.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/details/components/TabletDetailHero.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/home/components/HomeHeroMetaRows.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/LiveTvModels.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/LiveTvSettingsPage.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/whatsnew/WhatsNewScreen.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/updater/AppUpdaterRepository.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/core/network/DnsOverHttps*|composeApp/src/commonMain/kotlin/com/nuvio/app/core/diagnostics/*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControlActions.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerControls.kt|composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerWallClock*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerDeviceStatus*|composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/AndroidSeekPreviewFrameExtractor.kt|*PlayerAudioBoostBridge.kt|.github/workflows/build-apk.yml|.github/workflows/sync-luqman-upstream.yml|.github/scripts/sync-upstream.sh|gradle.properties|androidApp/build.gradle.kts|composeApp/build.gradle.kts|README.md|.gitignore|androidApp/src/main/AndroidManifest.xml)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}
CHANGED_FILES="$( { git diff --cached --name-only; git diff --name-only; } | sort -u || true )"
while IFS= read -r changed_file; do
  [[ -z "${changed_file}" ]] && continue
  if is_protected_path "${changed_file}"; then
    echo "Protected, restoring our version: ${changed_file}"
    PROTECTED_SKIPPED="${PROTECTED_SKIPPED}${changed_file}
"
    if git cat-file -e "HEAD:${changed_file}" 2>/dev/null; then
      git checkout HEAD -- "${changed_file}" 2>/dev/null || true
    else
      # Upstream added a new file inside a protected area; do not let it in.
      git rm --cached -- "${changed_file}" 2>/dev/null || true
      rm -f -- "${changed_file}" 2>/dev/null || true
    fi
    git add -- "${changed_file}" 2>/dev/null || true
  fi
done <<< "${CHANGED_FILES}"
# Trim trailing newline handling: PROTECTED_SKIPPED is used verbatim in PR body.
if [[ -n "${PROTECTED_SKIPPED}" ]]; then
  MERGE_STATUS="Partial merge - protected files kept as ours"
  echo "Protected files skipped:"
  echo "${PROTECTED_SKIPPED}"
fi

# Update tracking SHA in the same commit as the merge (or as its own
# commit when there was no merge). Never use --amend here: amending
# rewrites the previous main commit and is why earlier sync PRs
# contained local fixes instead of upstream changes.
echo "${LATEST_LUQMAN_SHA}" > "${LAST_SYNC_FILE}"
git add "${LAST_SYNC_FILE}"

# Commit the merge if one is in progress, otherwise commit the SHA update.
if git rev-parse -q --verify MERGE_HEAD >/dev/null 2>&1; then
  git commit --no-edit -m "chore(sync): merge upstream commits from Luqman (${LATEST_LUQMAN_SHA:0:7})"
else
  git commit -m "chore(sync): update Luqman tracking SHA to ${LATEST_LUQMAN_SHA:0:7}" || true
fi

git push origin "${BRANCH_NAME}" --force

# Check if PR already exists
EXISTING_PR="$(gh pr list --head "${BRANCH_NAME}" --state open --json number --jq '.[0].number' 2>/dev/null || true)"

PR_TITLE="Upstream Sync: New updates from Luqman (${LATEST_LUQMAN_SHA:0:7})"

if [[ -n "${PROTECTED_SKIPPED}" ]]; then
  PROTECTED_SECTION="### Protected files (NOT tampered with by sync - our version kept):
\`\`\`text
${PROTECTED_SKIPPED}
\`\`\`
"
else
  PROTECTED_SECTION="### Protected files:
None changed by this sync.
"
fi

if [[ -n "${SKIPPED_FILES}" ]]; then
  SKIPPED_SECTION="### Skipped files (conflict - our version kept, port by hand if needed):
\`\`\`text
${SKIPPED_FILES}
\`\`\`
"
else
  SKIPPED_SECTION="### Skipped files:
None. All upstream changes merged cleanly.
"
fi

cat << EOF > /tmp/pr_body.md
## New Commits from Luqman Repository

\`\`\`text
${NEW_COMMITS}
\`\`\`

---

${SKIPPED_SECTION}
${PROTECTED_SECTION}
### Pre-Merge Checklist:
- [ ] Check **Files changed** in this PR - a merged badge alone does not prove upstream code arrived.
- [ ] Ensure **200% Digital Volume Boost** is intact.
- [ ] Ensure **CS3 (CloudStream 3) Overhaul & Bug Fixes** are intact.
- [ ] Ensure **HSV Custom Color Theme Picker** is intact.
- [ ] Ensure **14 Player Button Hide Toggles** are intact.
- [ ] Run the APK build and check it succeeds before merging.

> **Status:** ${MERGE_STATUS}
EOF

if [[ -n "${EXISTING_PR}" ]]; then
  echo "Updating existing PR #${EXISTING_PR}..."
  gh pr edit "${EXISTING_PR}" --title "${PR_TITLE}" --body-file /tmp/pr_body.md
else
  echo "Opening new Pull Request..."
  gh pr create --base main --head "${BRANCH_NAME}" --title "${PR_TITLE}" --body-file /tmp/pr_body.md
fi
