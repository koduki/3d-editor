/**
 * Zero-dependency Unified Diff (LCS) calculator & DOM renderer.
 * Designed for vertical-only readability within narrow XR FOVs (e.g. XREAL AURA 70°).
 */

const MAX_LCS_CELLS = 350_000;

function splitLines(text) {
  if (text === null || text === undefined || text === '') return [];
  const normalized = String(text).replace(/\r\n/g, '\n');
  const lines = normalized.split('\n');
  // Avoid counting trailing empty string after final newline as an extra line
  if (lines.length > 0 && lines[lines.length - 1] === '') {
    lines.pop();
  }
  return lines;
}

function computeLineOps(oldLines, newLines) {
  let prefix = 0;
  const minLen = Math.min(oldLines.length, newLines.length);
  while (prefix < minLen && oldLines[prefix] === newLines[prefix]) {
    prefix++;
  }

  let suffix = 0;
  while (
    suffix < oldLines.length - prefix &&
    suffix < newLines.length - prefix &&
    oldLines[oldLines.length - 1 - suffix] === newLines[newLines.length - 1 - suffix]
  ) {
    suffix++;
  }

  const ops = [];
  for (let i = 0; i < prefix; i++) {
    ops.push({ type: 'ctx', text: oldLines[i], oldLine: i + 1, newLine: i + 1 });
  }

  const midOld = oldLines.slice(prefix, oldLines.length - suffix);
  const midNew = newLines.slice(prefix, newLines.length - suffix);

  const m = midOld.length;
  const n = midNew.length;

  if (m === 0 && n === 0) {
    // identical
  } else if (m === 0) {
    for (let j = 0; j < n; j++) {
      ops.push({ type: 'add', text: midNew[j], oldLine: null, newLine: prefix + j + 1 });
    }
  } else if (n === 0) {
    for (let i = 0; i < m; i++) {
      ops.push({ type: 'del', text: midOld[i], oldLine: prefix + i + 1, newLine: null });
    }
  } else if (m * n > MAX_LCS_CELLS) {
    // Safe fallback for very large diffs so UI never stalls
    for (let i = 0; i < m; i++) {
      ops.push({ type: 'del', text: midOld[i], oldLine: prefix + i + 1, newLine: null });
    }
    for (let j = 0; j < n; j++) {
      ops.push({ type: 'add', text: midNew[j], oldLine: null, newLine: prefix + j + 1 });
    }
  } else {
    // Standard LCS dynamic programming table (flat Uint32Array for speed & low GC)
    const cols = n + 1;
    const dp = new Uint32Array((m + 1) * cols);

    for (let i = m - 1; i >= 0; i--) {
      const rowOffset = i * cols;
      const nextRowOffset = (i + 1) * cols;
      for (let j = n - 1; j >= 0; j--) {
        if (midOld[i] === midNew[j]) {
          dp[rowOffset + j] = dp[nextRowOffset + j + 1] + 1;
        } else {
          const down = dp[nextRowOffset + j];
          const right = dp[rowOffset + j + 1];
          dp[rowOffset + j] = down >= right ? down : right;
        }
      }
    }

    let i = 0;
    let j = 0;
    while (i < m && j < n) {
      if (midOld[i] === midNew[j]) {
        ops.push({
          type: 'ctx',
          text: midOld[i],
          oldLine: prefix + i + 1,
          newLine: prefix + j + 1,
        });
        i++;
        j++;
      } else if (dp[(i + 1) * cols + j] >= dp[i * cols + j + 1]) {
        ops.push({ type: 'del', text: midOld[i], oldLine: prefix + i + 1, newLine: null });
        i++;
      } else {
        ops.push({ type: 'add', text: midNew[j], oldLine: null, newLine: prefix + j + 1 });
        j++;
      }
    }
    while (i < m) {
      ops.push({ type: 'del', text: midOld[i], oldLine: prefix + i + 1, newLine: null });
      i++;
    }
    while (j < n) {
      ops.push({ type: 'add', text: midNew[j], oldLine: null, newLine: prefix + j + 1 });
      j++;
    }
  }

  const oldSuffixStart = oldLines.length - suffix;
  const newSuffixStart = newLines.length - suffix;
  for (let k = 0; k < suffix; k++) {
    ops.push({
      type: 'ctx',
      text: oldLines[oldSuffixStart + k],
      oldLine: oldSuffixStart + k + 1,
      newLine: newSuffixStart + k + 1,
    });
  }

  return ops;
}

/**
 * Compute Unified Diff hunks with surrounding context lines.
 */
export function computeUnifiedDiff(oldText, newText, contextLines = 3) {
  const oldLines = splitLines(oldText);
  const newLines = splitLines(newText);
  const ops = computeLineOps(oldLines, newLines);

  let additions = 0;
  let deletions = 0;
  const changeIndices = [];
  for (let idx = 0; idx < ops.length; idx++) {
    if (ops[idx].type === 'add') {
      additions++;
      changeIndices.push(idx);
    } else if (ops[idx].type === 'del') {
      deletions++;
      changeIndices.push(idx);
    }
  }

  if (changeIndices.length === 0) {
    return { hunks: [], additions: 0, deletions: 0, isNewFile: oldText == null };
  }

  // Merge ranges [idx - contextLines, idx + contextLines]
  const ranges = [];
  for (const idx of changeIndices) {
    const start = Math.max(0, idx - contextLines);
    const end = Math.min(ops.length - 1, idx + contextLines);
    if (ranges.length > 0 && start <= ranges[ranges.length - 1].end + 1) {
      ranges[ranges.length - 1].end = Math.max(ranges[ranges.length - 1].end, end);
    } else {
      ranges.push({ start, end });
    }
  }

  const hunks = ranges.map(({ start, end }) => {
    const slice = ops.slice(start, end + 1);
    const firstOld = slice.find((l) => l.oldLine !== null)?.oldLine ?? 0;
    const firstNew = slice.find((l) => l.newLine !== null)?.newLine ?? 0;
    const oldCount = slice.filter((l) => l.oldLine !== null).length;
    const newCount = slice.filter((l) => l.newLine !== null).length;

    return {
      header: `@@ -${firstOld},${oldCount} +${firstNew},${newCount} @@`,
      lines: slice,
    };
  });

  return {
    hunks,
    additions,
    deletions,
    isNewFile: oldText == null,
  };
}

/**
 * Render a vertical Unified Diff DOM element.
 */
export function renderUnifiedDiffElement({ path: filePath, oldText, newText }, options = {}) {
  const { contextLines = 3, maxLines = 400 } = options;
  const diff = computeUnifiedDiff(oldText, newText, contextLines);

  const container = document.createElement('div');
  container.className = 'diff-block';

  const header = document.createElement('div');
  header.className = 'diff-header';

  const pathEl = document.createElement('span');
  pathEl.className = 'diff-path';
  pathEl.textContent = filePath || '(unknown file)';
  pathEl.title = filePath || '';

  const statsEl = document.createElement('span');
  statsEl.className = 'diff-stats';
  if (diff.isNewFile) {
    const badge = document.createElement('span');
    badge.className = 'diff-badge-new';
    badge.textContent = 'NEW';
    statsEl.appendChild(badge);
  }
  const addSpan = document.createElement('span');
  addSpan.className = 'diff-stat-add';
  addSpan.textContent = `+${diff.additions}`;
  const delSpan = document.createElement('span');
  delSpan.className = 'diff-stat-del';
  delSpan.textContent = `-${diff.deletions}`;
  statsEl.append(addSpan, delSpan);

  header.append(pathEl, statsEl);
  container.appendChild(header);

  const body = document.createElement('div');
  body.className = 'diff-body';

  if (diff.hunks.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'diff-empty';
    empty.textContent = '差分はありません';
    body.appendChild(empty);
    container.appendChild(body);
    return container;
  }

  let renderedCount = 0;
  let truncated = false;

  for (const hunk of diff.hunks) {
    if (renderedCount >= maxLines) {
      truncated = true;
      break;
    }

    const hunkHeader = document.createElement('div');
    hunkHeader.className = 'diff-hunk-header';
    hunkHeader.textContent = hunk.header;
    body.appendChild(hunkHeader);

    for (const line of hunk.lines) {
      if (renderedCount >= maxLines) {
        truncated = true;
        break;
      }
      renderedCount++;

      const row = document.createElement('div');
      row.className = `diff-line diff-line-${line.type}`;

      const oldNo = document.createElement('span');
      oldNo.className = 'diff-ln';
      oldNo.textContent = line.oldLine ?? '';

      const newNo = document.createElement('span');
      newNo.className = 'diff-ln';
      newNo.textContent = line.newLine ?? '';

      const sign = document.createElement('span');
      sign.className = 'diff-sign';
      sign.textContent = line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' ';

      const code = document.createElement('span');
      code.className = 'diff-code';
      code.textContent = line.text;

      row.append(oldNo, newNo, sign, code);
      body.appendChild(row);
    }
  }

  if (truncated) {
    const note = document.createElement('div');
    note.className = 'diff-truncated';
    note.textContent = `… 表示上限（${maxLines}行）のため残りを省略しました`;
    body.appendChild(note);
  }

  container.appendChild(body);
  return container;
}
