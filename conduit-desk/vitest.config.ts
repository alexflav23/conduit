import { defineConfig } from 'vitest/config';

// Vitest unit layer (CLAUDE.md / doc 29 F): jsdom, globals, tests under src/**/__tests__. Kept separate
// from vite.config.ts so the StyleX build plugins don't run under the unit tests.
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/__tests__/**/*.{test,spec}.{ts,tsx}'],
  },
});
