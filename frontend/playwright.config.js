import { defineConfig } from '@playwright/test';

/**
 * [AIREVIEW-PLAN-012#1.10] Runs the deterministic workbench smoke flow against Vite.
 * [AIREVIEW-PLAN-023#9.3] Allows local verification with an installed browser channel.
 */
export default defineConfig({
    testDir: './tests',
    testMatch: '**/*.e2e.js',
    use: {
        baseURL: 'http://127.0.0.1:4173',
        headless: true,
        ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
        // [AIREVIEW-PLAN-024#方案7] Allows deterministic E2E verification with a locally cached
        // browser when the Playwright-managed revision cannot be downloaded in the current environment.
        ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH
            ? { launchOptions: { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } }
            : {})
    },
    webServer: {
        command: 'npm run dev -- --port 4173',
        url: 'http://127.0.0.1:4173',
        reuseExistingServer: !process.env.CI
    }
});
