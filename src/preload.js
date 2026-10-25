const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('api', {
  pickFolder: () => ipcRenderer.invoke('pick-folder'),
  scan: (roots) => ipcRenderer.invoke('scan', roots),
  cover: (file) => ipcRenderer.invoke('cover', file),
  onBatch: (cb) => ipcRenderer.on('scan:batch', (_, tracks) => cb(tracks)),
  pathOf: (file) => webUtils.getPathForFile(file),
});
