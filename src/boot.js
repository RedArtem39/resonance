// Entry point. `Resonance.exe --uninstall` (the command registered in "Apps & features") runs the uninstaller instead of the player.
if (process.argv.includes('--uninstall')) require('./uninstall.js');
else require('./main.js');
