#!/usr/bin/env bash
set -e

git remote add luqman https://github.com/luqmanfadlli/NuvioMobile-Enhanced.git 2>/dev/null || true
git fetch luqman enhanced

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

# Attempt merge
MERGE_SUCCESS=true
if ! git merge --no-edit -m "chore(sync): merge upstream commits from Luqman (${LATEST_LUQMAN_SHA:0:7})" luqman/enhanced; then
  echo "⚠️ Merge conflicts detected. Adding conflict markers for manual review..."
  MERGE_SUCCESS=false
  git add -A
  git commit -m "chore(sync): merge luqman/enhanced with conflict markers (${LATEST_LUQMAN_SHA:0:7})" || true
fi

# Update tracking sha
echo "${LATEST_LUQMAN_SHA}" > "${LAST_SYNC_FILE}"
git add "${LAST_SYNC_FILE}"
git commit --amend --no-edit || true

git push origin "${BRANCH_NAME}" --force

# Check if PR already exists
EXISTING_PR="$(gh pr list --head "${BRANCH_NAME}" --state open --json number --jq '.[0].number' 2>/dev/null || true)"

PR_TITLE="Upstream Sync: New updates from Luqman (${LATEST_LUQMAN_SHA:0:7})"

STATUS_TEXT="Clean merge (No conflicts)"
if [[ "${MERGE_SUCCESS}" != "true" ]]; then
  STATUS_TEXT="Merge conflicts detected - review before merging"
fi

cat << EOF > /tmp/pr_body.md
## New Commits from Luqman Repository

\`\`\`text
${NEW_COMMITS}
\`\`\`

---

### Pre-Merge Checklist:
- [ ] Ensure **200% Digital Volume Boost** is intact.
- [ ] Ensure **CS3 (CloudStream 3) Overhaul & Bug Fixes** are intact.
- [ ] Ensure **HSV Custom Color Theme Picker** is intact.
- [ ] Ensure **14 Player Button Hide Toggles** are intact.

> **Status:** ${STATUS_TEXT}
EOF

if [[ -n "${EXISTING_PR}" ]]; then
  echo "Updating existing PR #${EXISTING_PR}..."
  gh pr edit "${EXISTING_PR}" --title "${PR_TITLE}" --body-file /tmp/pr_body.md
else
  echo "Opening new Pull Request..."
  gh pr create --base main --head "${BRANCH_NAME}" --title "${PR_TITLE}" --body-file /tmp/pr_body.md
fi
