import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import stylexRollup from '@stylexjs/rollup-plugin';
import { transformAsync } from '@babel/core';
import stylexBabelPlugin from '@stylexjs/babel-plugin';
import path from 'path';

// Mirrors hyperstore's StyleX setup: a dev Babel transform, a rollup plugin for the build.
function stylexDevPlugin() {
  const stylexConfig = [
    stylexBabelPlugin,
    {
      dev: true,
      runtimeInjection: true,
      genConditionalClasses: true,
      treeshakeCompensation: true,
      unstable_moduleResolution: { type: 'commonJS', rootDir: path.resolve(__dirname) },
    },
  ];
  return {
    name: 'stylex-dev',
    enforce: 'pre' as const,
    async transform(code: string, id: string) {
      if (!/\.[mc]?[jt]sx?$/.test(id)) return null;
      if (!code.includes('stylex')) return null;
      if (id.includes('node_modules')) return null;
      const result = await transformAsync(code, {
        babelrc: false,
        filename: id,
        plugins: [
          stylexConfig,
          ['@babel/plugin-syntax-typescript', { isTSX: id.endsWith('.tsx') }],
          '@babel/plugin-syntax-jsx',
        ],
      });
      if (!result?.code) return null;
      return { code: result.code, map: result.map };
    },
  };
}

export default defineConfig(({ command }) => ({
  plugins:
    command === 'serve'
      ? [stylexDevPlugin(), react()]
      : [react(), stylexRollup({ fileName: 'stylex.css', dev: false, unstable_moduleResolution: { type: 'commonJS', rootDir: path.resolve(__dirname) } })],
  server: {
    port: 3060,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
}));
