import { build } from 'esbuild';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const coreEntry = path.join(root, 'mobile', 'core.ts');
const output = path.join(root, 'public', 'mobile-core.js');
const indexPath = path.join(root, 'public', 'index.html');

await build({
  entryPoints: [coreEntry],
  bundle: true,
  format: 'iife',
  globalName: 'Wenku8CoreBundle',
  platform: 'browser',
  target: ['es2020'],
  outfile: output,
  legalComments: 'none',
  sourcemap: false,
  minify: false,
});

const html = await readFile(indexPath, 'utf8');
if (!html.includes('mobile-core.js')) {
  const marker = '<script src="/app.js" defer></script>';
  const replacement = '<script src="/mobile-core.js" defer></script>\n  ' + marker;
  if (!html.includes(marker)) throw new Error('public/index.html 中找不到 app.js 引用');
  await writeFile(indexPath, html.replace(marker, replacement), 'utf8');
}

console.log(`Android web bundle written to ${output}`);
