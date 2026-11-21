/**
 * Lightweight, XSS-safe Markdown renderer for streaming ACP responses.
 * Zero external dependencies.
 */

export function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function sanitizeUrl(rawUrl) {
  const trimmed = rawUrl.trim();
  if (/^(https?:|mailto:|\/|#)/i.test(trimmed)) {
    return trimmed;
  }
  return '';
}

function renderInline(text) {
  // Protect inline code spans first
  const codeSpans = [];
  let escaped = escapeHtml(text).replace(/`([^`\n]+)`/g, (_, code) => {
    const idx = codeSpans.length;
    codeSpans.push(`<code>${code}</code>`);
    return `\u0000CODE${idx}\u0000`;
  });

  // Links [label](url)
  escaped = escaped.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) => {
    const safe = sanitizeUrl(url.replace(/&amp;/g, '&'));
    if (!safe) return label;
    return `<a href="${escapeHtml(safe)}" target="_blank" rel="noopener noreferrer">${label}</a>`;
  });

  // Bold **text** or __text__
  escaped = escaped.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
  // Strikethrough ~~text~~
  escaped = escaped.replace(/~~([^~\n]+)~~/g, '<del>$1</del>');
  // Italic *text*
  escaped = escaped.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>');

  // Restore inline code spans
  escaped = escaped.replace(/\u0000CODE(\d+)\u0000/g, (_, i) => codeSpans[Number(i)]);
  return escaped;
}

export function renderMarkdown(markdown) {
  if (!markdown) return '';
  const lines = String(markdown).replace(/\r\n/g, '\n').split('\n');
  const html = [];

  let inCodeBlock = false;
  let codeLang = '';
  let codeLines = [];
  let listType = null; // 'ul' | 'ol'
  let paragraphLines = [];

  function flushParagraph() {
    if (paragraphLines.length === 0) return;
    html.push(`<p>${paragraphLines.map(renderInline).join('<br>')}</p>`);
    paragraphLines = [];
  }

  function flushList() {
    if (!listType) return;
    html.push(`</${listType}>`);
    listType = null;
  }

  function flushCodeBlock() {
    const codeText = codeLines.join('\n');
    const langLabel = codeLang ? escapeHtml(codeLang) : 'code';
    html.push(
      `<div class="md-code-wrap">` +
        `<div class="md-code-bar">` +
        `<span class="md-code-lang">${langLabel}</span>` +
        `<button type="button" class="md-copy-btn" data-copy-code>コピー</button>` +
        `</div>` +
        `<pre><code>${escapeHtml(codeText)}</code></pre>` +
        `</div>`,
    );
    inCodeBlock = false;
    codeLang = '';
    codeLines = [];
  }

  for (const line of lines) {
    const fenceMatch = line.match(/^```([a-zA-Z0-9_+-]*)\s*$/);
    if (fenceMatch) {
      if (inCodeBlock) {
        flushCodeBlock();
      } else {
        flushParagraph();
        flushList();
        inCodeBlock = true;
        codeLang = fenceMatch[1] || '';
        codeLines = [];
      }
      continue;
    }

    if (inCodeBlock) {
      codeLines.push(line);
      continue;
    }

    if (line.trim() === '') {
      flushParagraph();
      flushList();
      continue;
    }

    // Horizontal rule
    if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line.trim())) {
      flushParagraph();
      flushList();
      html.push('<hr>');
      continue;
    }

    // Headings
    const headingMatch = line.match(/^(#{1,4})\s+(.+)$/);
    if (headingMatch) {
      flushParagraph();
      flushList();
      const level = headingMatch[1].length;
      html.push(`<h${level}>${renderInline(headingMatch[2])}</h${level}>`);
      continue;
    }

    // Blockquote
    const quoteMatch = line.match(/^>\s?(.*)$/);
    if (quoteMatch) {
      flushParagraph();
      flushList();
      html.push(`<blockquote>${renderInline(quoteMatch[1])}</blockquote>`);
      continue;
    }

    // Unordered list
    const ulMatch = line.match(/^\s*[-*+]\s+(.+)$/);
    if (ulMatch) {
      flushParagraph();
      if (listType !== 'ul') {
        flushList();
        listType = 'ul';
        html.push('<ul>');
      }
      html.push(`<li>${renderInline(ulMatch[1])}</li>`);
      continue;
    }

    // Ordered list
    const olMatch = line.match(/^\s*\d+\.\s+(.+)$/);
    if (olMatch) {
      flushParagraph();
      if (listType !== 'ol') {
        flushList();
        listType = 'ol';
        html.push('<ol>');
      }
      html.push(`<li>${renderInline(olMatch[1])}</li>`);
      continue;
    }

    flushList();
    paragraphLines.push(line);
  }

  if (inCodeBlock) {
    flushCodeBlock();
  }
  flushParagraph();
  flushList();

  return html.join('\n');
}
