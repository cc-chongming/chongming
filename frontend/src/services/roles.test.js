import { describe, expect, it } from 'vitest';
import {
    DEFAULT_REGISTRATION_ROLE,
    REGISTRABLE_ROLES,
    ROLE_LABELS,
    canCreateRequirements,
    roleLabel
} from './roles';

describe('roles', () => {
    it('exposes Chinese labels for the canonical platform roles', () => {
        expect(ROLE_LABELS.ADMIN).toBe('管理员');
        expect(ROLE_LABELS.PRODUCT_MANAGER).toBe('产品经理');
        expect(ROLE_LABELS.PROJECT_MANAGER).toBe('项目经理');
        expect(ROLE_LABELS.DEVELOPER).toBe('开发');
        expect(ROLE_LABELS.USER).toBe('普通用户');
    });

    it('falls back to the legacy user label for unknown or missing roles', () => {
        expect(roleLabel('ADMIN')).toBe('管理员');
        expect(roleLabel('USER')).toBe('普通用户');
        expect(roleLabel('SOMETHING_ELSE')).toBe('普通用户');
        expect(roleLabel(undefined)).toBe('普通用户');
        expect(roleLabel(null)).toBe('普通用户');
    });

    it.each([
        ['ADMIN', true],
        ['PRODUCT_MANAGER', true],
        ['PROJECT_MANAGER', true],
        ['DEVELOPER', false],
        ['USER', false],
        ['PRODUCT', false],
        ['admin', false],
        ['', false],
        [undefined, false],
        [null, false]
    ])('grants requirement creation to %s -> %s', (role, expected) => {
        expect(canCreateRequirements(role)).toBe(expected);
    });

    it('lists registrable roles without ADMIN and defaults to DEVELOPER', () => {
        expect(REGISTRABLE_ROLES.map((option) => option.value))
            .toEqual(['DEVELOPER', 'PRODUCT_MANAGER', 'PROJECT_MANAGER']);
        expect(REGISTRABLE_ROLES.map((option) => option.label))
            .toEqual(['开发', '产品经理', '项目经理']);
        expect(DEFAULT_REGISTRATION_ROLE).toBe('DEVELOPER');
        expect(REGISTRABLE_ROLES.some((option) => option.value === 'ADMIN')).toBe(false);
    });
});
