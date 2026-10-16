import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const spark = path.resolve(frontend, '../Pro/spark-main');
const three = path.join(spark, 'node_modules/three');
const output = path.join(frontend, 'public/gaussian-viewer');
const version = directory => JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8')).version;
const useLocalSpark = fs.existsSync(path.join(spark, 'package.json'))
  && fs.existsSync(path.join(three, 'package.json'));
if (useLocalSpark && (version(spark) !== '2.2.0' || version(three) !== '0.180.0')) {
  throw new Error('高斯渲染依赖需要 Pro/spark-main 2.2.0 及其 three 0.180.0；不修改主应用的 Three.js。');
}
const vendorFiles = [
  [path.join(spark, 'dist/spark.module.js'), 'vendor/spark.module.js'],
  [path.join(spark, 'LICENSE'), 'vendor/SPARK-LICENSE'],
  [path.join(three, 'LICENSE'), 'vendor/THREE-LICENSE'],
  [path.join(three, 'build/three.module.js'), 'vendor/three/three.module.js'],
  [path.join(three, 'build/three.core.js'), 'vendor/three/three.core.js'],
  [path.join(three, 'examples/jsm/controls/OrbitControls.js'), 'vendor/three/addons/controls/OrbitControls.js'],
  [path.join(three, 'examples/jsm/postprocessing/Pass.js'), 'vendor/three/addons/postprocessing/Pass.js'],
];
if (!useLocalSpark) {
  for (const [, relative] of vendorFiles) {
    if (!fs.existsSync(path.join(output, relative))) {
      throw new Error(`Missing bundled Gaussian viewer dependency: ${relative}`);
    }
  }
}
const files = [
  ...(useLocalSpark ? vendorFiles : []),
  [path.join(frontend, 'src/utils/keyboardRoaming.js'), 'keyboardRoaming.js'],
  [path.join(frontend, 'gaussian-viewer/index.html'), 'index.html'],
  [path.join(frontend, 'gaussian-viewer/viewer.js'), 'viewer.js'],
];
for (const [source, relative] of files) {
  const destination = path.join(output, relative);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.copyFileSync(source, destination);
}
console.log(`高斯渲染入口已准备：Spark 2.2.0 / Three.js 0.180.0（${useLocalSpark ? '本地依赖' : '仓库内置依赖'}）`);
