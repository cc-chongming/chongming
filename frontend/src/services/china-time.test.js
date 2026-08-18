import { describe, expect, it } from 'vitest';
import { formatChinaClock, formatChinaTime } from './china-time';

describe('china-time [AIREVIEW-PLAN-025]', () => {
    it('converts ISO UTC strings to China time', () => {
        // 2026-08-17T07:53:27Z is 15:53:27 in Asia/Shanghai.
        expect(formatChinaTime('2026-08-17T07:53:27Z')).toBe('2026-08-17 15:53:27');
    });

    it('passes backend-rendered China-time strings through untouched', () => {
        expect(formatChinaTime('2026-08-18 10:35:20')).toBe('2026-08-18 10:35:20');
    });

    it('returns empty or original values for missing or unparseable input', () => {
        expect(formatChinaTime(null)).toBe('');
        expect(formatChinaTime('')).toBe('');
        expect(formatChinaTime('not-a-date')).toBe('not-a-date');
    });

    it('formats clock-only values in China time', () => {
        expect(formatChinaClock('2026-08-17T07:53:27Z')).toBe('15:53:27');
        expect(formatChinaClock('2026-08-18 10:35:20')).toBe('10:35:20');
        expect(formatChinaClock(null)).toBe('');
    });
});
