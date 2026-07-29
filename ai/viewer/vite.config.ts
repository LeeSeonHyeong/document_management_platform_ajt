import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The graph API runs separately (`uv run python -m wiki_mcp.graph_api`). Proxying it
    // keeps the app on one origin so no CORS preflight is involved in dev.
    proxy: { '/graph': 'http://127.0.0.1:8000', '/scopes': 'http://127.0.0.1:8000' },
  },
})
