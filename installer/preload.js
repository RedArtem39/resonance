const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('setup', {
  info: () => ipcRenderer.invoke('setup:info'),
  browse: (current) => ipcRenderer.invoke('setup:browse', current),
  install: (opts) => ipcRenderer.invoke('setup:install', opts),
  launch: (exe) => ipcRenderer.invoke('setup:launch', exe),
  close: () => ipcRenderer.send('setup:close'),
  minimize: () => ipcRenderer.send('setup:minimize'),
  onProgress: (cb) => ipcRenderer.on('setup:progress', (_, v) => cb(v)),
});
