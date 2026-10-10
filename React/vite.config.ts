import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import os from 'os'

/** 本机局域网 IPv4（跳过回环与 169.254 自动地址）。 */
function lanAddresses(): string[] {
  return Object.values(os.networkInterfaces())
    .flatMap(list => list ?? [])
    .filter(item => item.family === 'IPv4' && !item.internal && !item.address.startsWith('169.254.'))
    .map(item => item.address)
}

/**
 * 局域网同步提示：dev 起来后打印插件「更多菜单 → 同步」要填的地址，以及网页端进入同一房间的链接
 * （房间固定为 sharedBoard.LOCAL_ROOM_ID，不需要共享码）。
 */
function mosaicLanHint(): Plugin {
  return {
    name: 'mosaic-lan-hint',
    apply: 'serve',
    configureServer(server) {
      server.httpServer?.once('listening', () => {
        const port = server.config.server.port
        const addresses = lanAddresses()
        console.log('\n  Mosaic 局域网同步')
        if (addresses.length === 0) console.log('    未找到局域网 IPv4，请确认电脑已连上与 Supernote 相同的网络')
        for (const address of addresses) console.log(`    插件端填写:  ${address}:${port}`)
        console.log(`    网页端打开:  http://localhost:${port}/#board=mosaic-local\n`)
      })
    },
  }
}

export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/whiteboard/' : '/',
  plugins: [react(), mosaicLanHint()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    // 监听 0.0.0.0：Supernote 经局域网连进来（/ws/board 与 /api 由这里转给 127.0.0.1:3791）。
    host: true,
    port: 3790,
    strictPort: true,
    watch: { usePolling: true, interval: 300 },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:3791',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:3791',
        ws: true,
      },
    },
  },
}))
