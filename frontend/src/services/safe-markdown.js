// [AIREVIEW-PLAN-023#7.1] Minimal safe Markdown renderer for public model output.
// [AIREVIEW-PLAN-034#3] Fence parsing hardened: indented fences, space before language, 3+ backticks, unclosed streaming fences.
function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

function safeHref(value) {
    const href = String(value ?? '').trim();
    if (/^(?:https?:|mailto:)/i.test(href) || /^(?:#|\/|\.\.?\/)/.test(href)) return href;
    return null;
}

function formatPlain(value) {
    return escapeHtml(value)
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/\*(.+?)\*/g, '<em>$1</em>');
}

function renderInline(value) {
    const source = String(value ?? '');
    const matcher = /(`[^`]+`|\[[^\]]+\]\([^)]+\))/g;
    let cursor = 0;
    let html = '';
    for (const match of source.matchAll(matcher)) {
        html += formatPlain(source.slice(cursor, match.index));
        const token = match[0];
        if (token.startsWith('`')) {
            html += `<code>${escapeHtml(token.slice(1, -1))}</code>`;
        } else {
            const parts = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(token);
            const href = safeHref(parts?.[2]);
            html += href
                ? `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer">${formatPlain(parts[1])}</a>`
                : formatPlain(parts?.[1] ?? token);
        }
        cursor = (match.index ?? 0) + token.length;
    }
    return html + formatPlain(source.slice(cursor));
}

function isTableSeparator(line) {
    return /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
}

function cells(line) {
    return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
}

export function renderSafeMarkdown(markdown) {
    const lines = String(markdown ?? '').replaceAll('\r\n', '\n').split('\n');
    const blocks = [];
    let index = 0;
    while (index < lines.length) {
        const line = lines[index];
        if (!line.trim()) { index += 1; continue; }
        const fence = /^\s*(`{3,})\s*(.*)$/.exec(line);
        if (fence) {
            const closingFence = new RegExp('^\\s*`{' + fence[1].length + ',}\\s*$');
            const code = [];
            index += 1;
            while (index < lines.length && !closingFence.test(lines[index])) code.push(lines[index++]);
            if (index < lines.length) index += 1;
            const infoToken = (fence[2].trim().split(/\s+/)[0] ?? '').replace(/[^\w+#.-]/g, '');
            const language = infoToken ? ` class="language-${escapeHtml(infoToken)}"` : '';
            blocks.push(`<pre><code${language}>${escapeHtml(code.join('\n'))}</code></pre>`);
            continue;
        }
        const heading = /^(#{1,6})\s+(.+)$/.exec(line);
        if (heading) {
            const level = heading[1].length;
            blocks.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
            index += 1;
            continue;
        }
        if (index + 1 < lines.length && line.includes('|') && isTableSeparator(lines[index + 1])) {
            const header = cells(line);
            index += 2;
            const rows = [];
            while (index < lines.length && lines[index].includes('|') && lines[index].trim()) rows.push(cells(lines[index++]));
            blocks.push(`<table><thead><tr>${header.map((cell) => `<th>${renderInline(cell)}</th>`).join('')}</tr></thead><tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${renderInline(cell)}</td>`).join('')}</tr>`).join('')}</tbody></table>`);
            continue;
        }
        if (/^\s*[-*+]\s+/.test(line)) {
            const list = [];
            while (index < lines.length && /^\s*[-*+]\s+/.test(lines[index])) list.push(lines[index++].replace(/^\s*[-*+]\s+/, ''));
            blocks.push(`<ul>${list.map((item) => `<li>${renderInline(item)}</li>`).join('')}</ul>`);
            continue;
        }
        if (/^\s*\d+[.)]\s+/.test(line)) {
            const list = [];
            while (index < lines.length && /^\s*\d+[.)]\s+/.test(lines[index])) list.push(lines[index++].replace(/^\s*\d+[.)]\s+/, ''));
            blocks.push(`<ol>${list.map((item) => `<li>${renderInline(item)}</li>`).join('')}</ol>`);
            continue;
        }
        const paragraph = [line];
        index += 1;
        while (index < lines.length && lines[index].trim()
            && !/^\s*`{3,}/.test(lines[index]) && !/^(#{1,6})\s+/.test(lines[index])
            && !/^\s*[-*+]\s+/.test(lines[index]) && !/^\s*\d+[.)]\s+/.test(lines[index])) {
            if (index + 1 < lines.length && lines[index].includes('|') && isTableSeparator(lines[index + 1])) break;
            paragraph.push(lines[index++]);
        }
        blocks.push(`<p>${paragraph.map(renderInline).join('<br>')}</p>`);
    }
    return blocks.join('');
}
