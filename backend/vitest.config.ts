import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    globalSetup: ['test/setup/global-setup.ts'],
    testTimeout: 30_000,
    hookTimeout: 60_000,
    // Each test file gets its own database on the shared in-memory mongod (see global-setup),
    // so files can run in parallel safely.
    fileParallelism: true,
    env: {
      NODE_ENV: 'test',
    },
  },
});
