# AJT Frontend

AJT 사내 LLM Wiki·일정 관리 시스템의 프론트엔드입니다.
**팀원 모두 아래 기술 스택과 컨벤션을 동일하게 사용합니다.**

## 기술 스택

| 구분 | 기술 | 버전 | 비고 |
| --- | --- | --- | --- |
| 언어 | JavaScript (ESM) | — | TypeScript 미사용 |
| 프레임워크 | React | ^19.2 | |
| 빌드 도구 | Vite | ^8.1 | `@vitejs/plugin-react` |
| 라우팅 | react-router-dom | ^7.18 | `createBrowserRouter` 데이터 라우터 |
| 서버 상태 | @tanstack/react-query | ^5.101 | API 데이터 캐싱·동기화 |
| 인증 상태 | React Context | — | 별도 상태관리 라이브러리 미사용 |
| HTTP | axios | ^1.18 | 공통 인스턴스 + 인터셉터 |
| 스타일링 | Tailwind CSS | ^4.3 | `@tailwindcss/vite` 플러그인 방식 |
| 린터 | oxlint | ^1.71 | Vite 스캐폴드 기본 |

> 클라이언트 상태는 React Context(인증), 서버 상태는 TanStack Query로 **역할을 분리**합니다.
> 전역 클라이언트 상태가 많이 필요해지면 그때 Zustand 도입을 논의합니다(현재는 미사용).

## 사전 요구사항

- **Node.js 22 LTS** 권장 (Vite 8 최소 요구: `20.19+` 또는 `22.12+`)
- 패키지 매니저: **npm** (레포에 `package-lock.json` 커밋)

팀 전체가 Node 메이저 버전을 맞춰야 lockfile 충돌을 줄일 수 있습니다.

## 시작하기

```bash
cd frontend
npm install
cp .env.example .env   # 필요 시 값 수정
npm run dev            # http://localhost:5173
```

### 스크립트

| 명령 | 설명 |
| --- | --- |
| `npm run dev` | 개발 서버 (HMR) |
| `npm run build` | 프로덕션 빌드 (`dist/`) |
| `npm run preview` | 빌드 결과 미리보기 |
| `npm run lint` | oxlint 검사 |

## 환경 변수

`.env`는 gitignore 대상이며 `.env.example`만 커밋합니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api/v1` | 백엔드 공개 API base URL |

개발 중에는 Vite 프록시(`vite.config.js`)가 `/api` 요청을 `http://localhost:8080`으로 전달하므로 CORS 설정 없이 백엔드와 연동됩니다.

## 디렉터리 구조

```
src/
├── api/          # axios 인스턴스(client.js)와 도메인별 API 호출 모듈
├── components/   # 공용 컴포넌트 (layout 등)
├── constants/    # 공용 상수 (roles.js 등)
├── context/      # React Context (AuthContext / AuthProvider)
├── hooks/        # 커스텀 훅 (useAuth 등)
├── lib/          # 순수 유틸 (authStorage 등)
├── pages/        # 라우트 단위 화면
├── routes/       # 라우터 정의(index.jsx)와 가드(guards/)
├── index.css     # Tailwind 진입점
└── main.jsx      # Provider 조립 + 앱 진입점
```

## 핵심 규약 (팀 공통)

### 1. 경로 별칭
`@` = `src`. 상대경로(`../../`) 대신 절대경로를 씁니다.
```js
import { useAuth } from '@/hooks/useAuth'
```

### 2. API 호출
직접 `axios`를 쓰지 말고 **`@/api/client`의 공통 인스턴스**를 사용합니다.
- 요청 인터셉터가 `Authorization: Bearer <token>`을 자동 첨부합니다.
- 응답 인터셉터가 백엔드 오류 envelope(`{ code, message, fieldErrors, ... }`)를 정규화하고, 401은 전역 로그아웃 처리합니다.
- 도메인별 호출은 `src/api/<domain>.js`에 함수로 모읍니다(예: `api/auth.js`).

### 3. 인증 상태
`useAuth()`로 접근합니다: `{ user, role, isAuthenticated, login, logout }`.
토큰·사용자 정보는 `localStorage`에 영속되어 새로고침 후에도 복원됩니다.

### 4. 라우트 가드 (역할별 접근 제어)
`src/routes/index.jsx`에서 라우트 레벨로 접근을 제어합니다.

| 가드 | 용도 | 실패 시 |
| --- | --- | --- |
| `GuestRoute` | 비로그인 전용(로그인 화면) | 로그인 상태면 `/`로 |
| `ProtectedRoute` | 로그인 필요(모든 인증 사용자) | `/login`으로 |
| `RoleRoute` | 특정 역할 필요 | 비로그인 `/login`, 권한부족 `/403` |

역할 값은 `@/constants/roles`의 `ROLES.ADMIN`(`admin`) / `ROLES.EMPLOYEE`(`employee`)를 사용합니다(백엔드 `member.role` ENUM과 대응, JSON은 소문자).

관리자 전용 라우트 추가 예시:
```jsx
{
  element: <RoleRoute allowedRoles={[ROLES.ADMIN]} />,
  children: [{ path: 'admin', element: <AdminPage /> }],
}
```

### 5. 스타일링
Tailwind 유틸리티 클래스를 우선 사용합니다. 전역 스타일은 `src/index.css`에만 둡니다.
