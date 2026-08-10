import { describe, expect, it } from 'vitest';
import { renderSafeMarkdown } from './safe-markdown';

describe('safe markdown', () => {
    it('renders headings, lists, tables, code and safe links', () => {
        const html = renderSafeMarkdown('# 结论\n\n- 第一项\n- 第二项\n\n|项|值|\n|---|---|\n|风险|高|\n\n```js\nconst ok = true;\n```\n\n[详情](https://example.com)');

        expect(html).toContain('<h1>结论</h1>');
        expect(html).toContain('<ul>');
        expect(html).toContain('<table>');
        expect(html).toContain('<pre><code class="language-js">');
        expect(html).toContain('href="https://example.com"');
    });

    it('escapes raw html and removes dangerous link targets', () => {
        const html = renderSafeMarkdown('<img src=x onerror=alert(1)>\n\n[攻击](javascript:alert(1))');

        expect(html).toContain('&lt;img');
        expect(html).not.toContain('<img');
        expect(html).not.toContain('javascript:');
        expect(html).not.toContain('href=');
    });
});
