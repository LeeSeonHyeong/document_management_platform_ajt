# frontend — React SPA

공통 규칙(Git·REST API 컨벤션, 요구사항)은 루트 `../docs/`가 기준이다. 루트 `CLAUDE.md` 참조.

## 스택

- React 19 + Vite 8, Tailwind CSS 4
- TanStack Query 5 (서버 상태), axios, react-router-dom 7
- 린트: oxlint

## 명령

```bash
npm run dev       # 개발 서버
npm run build     # 프로덕션 빌드
npm run lint      # oxlint
```

## 규칙

- API 호출은 `../docs/conventions/rest-api-convention.md`와 `../docs/api/` 계약 기준. 백엔드 응답 형식을 임의로 가정하지 않는다.
- 인증·CSRF 처리 방식은 `../docs/api/README.md`와 REST API 컨벤션 기준. 토큰을 JS에서 저장·조작하지 않는다.
- 디렉토리: `src/api`(통신), `src/pages`, `src/components`, `src/hooks`, `src/routes`, `src/context`, `src/constants`, `src/lib`
