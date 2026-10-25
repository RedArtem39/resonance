const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('un', {
  info: () => ipcRenderer.invoke('un:info'),
  run: (purge) => ipcRenderer.invoke('un:run', purge),
  close: () => ipcRenderer.send('un:close'),
});
