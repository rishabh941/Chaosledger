import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const silent = (proxy: any) => {
  proxy.on('error', () => {});
  proxy.on('proxyReq', (_p: any, _req: any, _res: any) => {
    _res.on('error', () => {});
  });
};

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api-n2': {
        target: 'http://127.0.0.1:8081',
        rewrite: (path) => path.replace(/^\/api-n2/, '/api'),
        configure: silent,
      },
      '/api-n3': {
        target: 'http://127.0.0.1:8082',
        rewrite: (path) => path.replace(/^\/api-n3/, '/api'),
        configure: silent,
      },
      '/api': {
        target: 'http://127.0.0.1:8080',
        configure: silent,
      },
      '/ws-n2': {
        target: 'http://127.0.0.1:8081',
        ws: true,
        rewrite: (path) => path.replace(/^\/ws-n2/, '/ws'),
        configure: silent,
      },
      '/ws-n3': {
        target: 'http://127.0.0.1:8082',
        ws: true,
        rewrite: (path) => path.replace(/^\/ws-n3/, '/ws'),
        configure: silent,
      },
      '/ws': {
        target: 'http://127.0.0.1:8080',
        ws: true,
        configure: silent,
      },
      '/chaos-agent': {
        target: 'http://127.0.0.1:8475',
        rewrite: (path) => path.replace(/^\/chaos-agent/, ''),
        configure: silent,
      },
    },
  },
});
