// Builds the custom installer:  node installer/build.js   (or: npm run installer)
//   1. packs the player (electron-builder, "dir" target) to get app.asar
//   2. copies it into installer/payload together with the license and icon
//   3. packs the installer app as a single self-extracting exe (electron-builder, "portable" target) -> dist-installer/
// electron-builder is used purely as a packager for the installer shell; the installer itself is the Electron app in installer/.

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const payload = path.join(__dirname, 'payload');
const run = (cmd) => execSync(cmd, { cwd: root, stdio: 'inherit' });

const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
console.log(`\n== Resonance ${pkg.version} ==\n`);

for (const f of ['build/icon.ico', 'build/icon.png', 'LICENSE']) {
  if (!fs.existsSync(path.join(root, f))) throw new Error(`missing ${f} — run "npm run icons" first / check the repo`);
}

console.log('1/3 packing the player...');
run('npx electron-builder --win dir');

console.log('2/3 preparing the payload...');
fs.rmSync(payload, { recursive: true, force: true });
fs.mkdirSync(payload, { recursive: true });
fs.copyFileSync(path.join(root, 'dist', 'win-unpacked', 'resources', 'app.asar'), path.join(payload, 'app.bin')); // .bin: keeps Electron's asar layer from treating it as an archive
fs.copyFileSync(path.join(root, 'LICENSE'), path.join(payload, 'LICENSE.txt'));
fs.copyFileSync(path.join(root, 'build', 'icon.ico'), path.join(payload, 'icon.ico'));
fs.copyFileSync(path.join(root, 'build', 'icon.png'), path.join(__dirname, 'ui', 'icon.png'));

const sp = path.join(__dirname, 'package.json');
const setup = JSON.parse(fs.readFileSync(sp, 'utf8'));
setup.version = pkg.version; // installer and player always share a version
fs.writeFileSync(sp, JSON.stringify(setup, null, 2) + '\n');

console.log('3/3 packing the installer...');
run('npx electron-builder --win portable --projectDir installer');
console.log(`\nDone: dist-installer/Resonance-Setup-${pkg.version}.exe`);
