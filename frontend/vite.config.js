import { defineConfig, normalizePath } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import path from 'node:path'
import { createRequire } from 'node:module'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { viteStaticCopy } from 'vite-plugin-static-copy'

// PDF 미리보기(react-pdf/pdf.js)용 정적 자산.
//  - cmaps: CJK 인코딩 PDF의 글리프 매핑. 없으면 한글 PDF 본문이 빈칸/깨짐으로 렌더된다.
//  - standard_fonts: 폰트를 내장하지 않은 PDF의 기본 폰트 대체본.
// 워커(pdf.worker.min.mjs)는 PdfPreview 에서 import.meta.url 로 직접 참조하므로 복사 대상이 아니다.
const require = createRequire(import.meta.url)
const pdfjsDistPath = path.dirname(require.resolve('pdfjs-dist/package.json'))

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    viteStaticCopy({
      targets: [
        // stripBase: node_modules 경로 구조를 벗기고 dist/cmaps, dist/standard_fonts 로 평탄하게 복사한다.
        {
          src: `${normalizePath(path.join(pdfjsDistPath, 'cmaps'))}/*`,
          dest: 'cmaps',
          rename: { stripBase: true },
        },
        {
          src: `${normalizePath(path.join(pdfjsDistPath, 'standard_fonts'))}/*`,
          dest: 'standard_fonts',
          rename: { stripBase: true },
        },
      ],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 개발 중 CORS 없이 백엔드로 프록시. 실제 배포 시에는 VITE_API_BASE_URL 사용
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
