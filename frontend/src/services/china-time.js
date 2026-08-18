/**
 * [AIREVIEW-PLAN-025] Unified display-time formatting: every user-facing timestamp is rendered
 * in China time (Asia/Shanghai) as `YYYY-MM-DD HH:mm:ss`, regardless of the viewer's browser
 * timezone. Backend payloads stay machine-readable ISO-8601 UTC; formatting happens here only.
 */

const formatter = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
});

// Backend display strings are already rendered in Asia/Shanghai as `yyyy-MM-dd HH:mm:ss`
// (ReviewQueryService); re-parsing them as browser-local time would shift them for viewers
// outside China, so they pass through untouched.
const ALREADY_LOCAL = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?!.*(?:Z|[+-]\d{2}:?\d{2}))/;

function toParts(value) {
    if (value === null || value === undefined || value === '') {
        return null;
    }
    if (typeof value === 'string' && ALREADY_LOCAL.test(value.trim())) {
        return value.trim();
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return value;
    }
    const parts = {};
    for (const part of formatter.formatToParts(date)) {
        parts[part.type] = part.value;
    }
    return parts;
}

/**
 * Formats one timestamp for display in China time.
 *
 * @param value ISO-8601 string (e.g. `2026-08-17T07:53:27Z`) or epoch source; backend strings
 *              already in China time are returned unchanged.
 * @returns `YYYY-MM-DD HH:mm:ss` in Asia/Shanghai, or the original value when unparseable.
 */
export function formatChinaTime(value) {
    const parts = toParts(value);
    if (parts === null) {
        return value ?? '';
    }
    if (typeof parts === 'string') {
        return parts;
    }
    // zh-CN with hour12:false may emit `24` at midnight on some engines; normalize to `00`.
    const hour = parts.hour === '24' ? '00' : parts.hour;
    return `${parts.year}-${parts.month}-${parts.day} ${hour}:${parts.minute}:${parts.second}`;
}

/**
 * Time-of-day only (`HH:mm:ss`) in Asia/Shanghai, used by conversation bubbles.
 *
 * @param value ISO-8601 string or epoch source
 * @returns `HH:mm:ss` in Asia/Shanghai, or the original value when unparseable.
 */
export function formatChinaClock(value) {
    const parts = toParts(value);
    if (parts === null) {
        return '';
    }
    if (typeof parts === 'string') {
        const match = parts.match(/(\d{2}:\d{2}:\d{2})/);
        return match ? match[1] : parts;
    }
    const hour = parts.hour === '24' ? '00' : parts.hour;
    return `${hour}:${parts.minute}:${parts.second}`;
}
