'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const archiver = require('archiver');
const packageJson = require('../package.json');

const root = path.resolve(__dirname, '..');
const distDirectory = path.join(root, 'dist');
const version = packageJson.version;
const archivePath = path.join(distDirectory, `wenku8-epub-studio-v${version}.zip`);
const included = [
  '.gitattributes',
  '.gitignore',
  'LICENSE',
  'package.json',
  'package-lock.json',
  'server.js',
  'start.bat',
  'README.md',
  'CHANGELOG.md',
  'docs',
  'public',
  'scripts',
  'src',
  'test',
];
const excludedNames = new Set(['node_modules', '.git', 'data', 'output', 'dist', 'work', 'outputs', 'fixtures']);

async function collectFiles(relativePath) {
  const absolutePath = path.join(root, relativePath);
  const stat = await fsp.stat(absolutePath);
  if (stat.isFile()) return [absolutePath];
  const entries = await fsp.readdir(absolutePath, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    if (excludedNames.has(entry.name) || entry.name.endsWith('.epub') || entry.name.endsWith('.tmp')) continue;
    const child = path.join(relativePath, entry.name);
    files.push(...await collectFiles(child));
  }
  return files;
}

async function main() {
  await fsp.mkdir(distDirectory, { recursive: true });
  await fsp.rm(archivePath, { force: true });
  await fsp.rm(`${archivePath}.sha256`, { force: true });
  const files = (await Promise.all(included.map(collectFiles))).flat().sort();
  await new Promise((resolve, reject) => {
    const output = fs.createWriteStream(archivePath);
    const archive = archiver('zip', { zlib: { level: 9 } });
    output.on('close', resolve);
    output.on('error', reject);
    archive.on('warning', reject);
    archive.on('error', reject);
    archive.pipe(output);
    for (const file of files) {
      archive.file(file, { name: path.relative(root, file).split(path.sep).join('/') });
    }
    archive.finalize().catch(reject);
  });
  const digest = crypto.createHash('sha256').update(await fsp.readFile(archivePath)).digest('hex');
  await fsp.writeFile(`${archivePath}.sha256`, `${digest}  ${path.basename(archivePath)}\n`, 'utf8');
  console.log(JSON.stringify({ archive: archivePath, bytes: (await fsp.stat(archivePath)).size, sha256: digest, files: files.length }, null, 2));
}

main().catch((error) => {
  console.error(`发布包生成失败：${error.message}`);
  process.exitCode = 1;
});
