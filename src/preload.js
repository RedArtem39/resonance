const { contextBridge, ipcRenderer, webUtils } = require('electron');

const on = (ch, cb) => ipcRenderer.on(ch, (_, v) => cb(v));

contextBridge.exposeInMainWorld('api', {
  pickFolder: () => ipcRenderer.invoke('pick-folder'),
  downloadDir: () => ipcRenderer.invoke('download-dir'),
  openDir: (p) => ipcRenderer.invoke('open-dir', p),
  scan: (roots) => ipcRenderer.invoke('scan', roots),
  cover: (file) => ipcRenderer.invoke('cover', file),
  lyrics: (q) => ipcRenderer.invoke('lyrics', q),
  ytSearch: (q) => ipcRenderer.invoke('yt:search', q),
  ytDownload: (v) => ipcRenderer.invoke('yt:download', v),
  ytStatus: () => ipcRenderer.invoke('yt:status'),
  ytUpdate: () => ipcRenderer.invoke('yt:update'),
  onBatch: (cb) => on('scan:batch', cb),
  onYtProgress: (cb) => on('yt:progress', cb),
  onYtStatus: (cb) => on('yt:note', cb),
  winMin: () => ipcRenderer.send('win:min'),
  winMax: () => ipcRenderer.send('win:max'),
  winClose: () => ipcRenderer.send('win:close'),
  winIsMax: () => ipcRenderer.invoke('win:is-max'),
  onWinState: (cb) => on('win:state', cb),
  pathOf: (file) => webUtils.getPathForFile(file),
});
