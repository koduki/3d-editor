#!/usr/bin/env node
/**
 * Mirrors public/ into android/app/src/main/assets/www/ so the Phase 2
 * Android 15 / Android XR native shell stays in 100% single-source sync with
 * the zero-build Web Spatial Desktop IDE.
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');
const SRC_DIR = path.join(REPO_ROOT, 'public');
const DEST_DIR = path.join(REPO_ROOT, 'android', 'app', 'src', 'main', 'assets', 'www');

export async function syncAndroidAssets({ srcDir = SRC_DIR, destDir = DEST_DIR } = {}) {
  await fs.rm(destDir, { recursive: true, force: true });
  await fs.mkdir(destDir, { recursive: true });

  let count = 0;
  async function copyRecursive(from, to) {
    const entries = await fs.readdir(from, { withFileTypes: true });
    for (const entry of entries) {
      const srcPath = path.join(from, entry.name);
      const dstPath = path.join(to, entry.name);
      if (entry.isDirectory()) {
        await fs.mkdir(dstPath, { recursive: true });
        await copyRecursive(srcPath, dstPath);
      } else if (entry.isFile()) {
        await fs.copyFile(srcPath, dstPath);
        count++;
      }
    }
  }

  await copyRecursive(srcDir, destDir);
  return { count, srcDir, destDir };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = await syncAndroidAssets();
  console.log(`[android:sync] Copied ${result.count} web asset files -> ${path.relative(REPO_ROOT, result.destDir)}`);
}
