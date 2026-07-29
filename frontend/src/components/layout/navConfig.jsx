import {
  Home,
  BookOpen,
  MessageSquare,
  Users,
  Building2,
  Inbox,
  FileText,
  CalendarDays,
  Settings,
} from 'lucide-react'
import { ROLES } from '@/shared/constants/enums'

// 역할별 사이드바 메뉴. 실제 라우트는 각 화면 스토리에서 추가된다.
export const NAV_ITEMS = {
  [ROLES.EMPLOYEE]: [
    { to: '/', label: '홈', icon: Home, end: true },
    { to: '/wiki', label: '위키', icon: BookOpen },
    { to: '/inquiries', label: '문의하기', icon: MessageSquare },
  ],
  [ROLES.ADMIN]: [
    { to: '/admin/users', label: '직원 관리', icon: Users },
    { to: '/admin/departments', label: '부서 관리', icon: Building2 },
    { to: '/admin/inquiries', label: '문의 관리', icon: Inbox },
    { to: '/admin/documents', label: '문서 관리', icon: FileText },
    { to: '/admin/schedules', label: '일정 관리', icon: CalendarDays },
    { to: '/wiki', label: '위키', icon: BookOpen },
  ],
}

export const ADMIN_FOOTER_ITEMS = [{ to: '/settings', label: '설정', icon: Settings }]

// 브레드크럼/타이틀용 경로 라벨. 세그먼트 경로 → 사람이 읽는 라벨.
export const ROUTE_LABELS = {
  '/': '홈',
  '/wiki': '위키',
  '/inquiries': '문의하기',
  '/schedules': '일정',
  '/me': '내 정보',
  '/settings': '설정',
  '/admin': '관리자',
  '/admin/users': '직원 관리',
  '/admin/signup-requests': '가입 승인 관리',
  '/admin/departments': '부서 관리',
  '/admin/inquiries': '문의 관리',
  '/admin/documents': '문서 관리',
  '/admin/schedules': '일정 관리',
}

// 상단바의 큰 페이지 제목입니다.
// 동적 경로(:userId)는 실제 URL을 보고 가장 구체적인 화면명으로 변환합니다.
export function getPageTitle(pathname) {
  if (/^\/admin\/users\/[^/]+\/edit$/.test(pathname)) return '직원 정보 수정'
  if (/^\/admin\/users\/[^/]+$/.test(pathname)) return '직원 상세'
  if (/^\/admin\/inquiries\/[^/]+$/.test(pathname)) return '문의 상세'
  return ROUTE_LABELS[pathname] ?? 'AJT'
}

// 직원 관리 Figma 프레임에 표기된 상단 경로 문구를 그대로 사용합니다.
export function getPageEyebrow(pathname) {
  if (pathname === '/admin/users') return 'AJT / 조직 관리'
  if (pathname === '/admin/departments') return 'AJT / 조직 관리'
  if (pathname === '/admin/signup-requests') return 'AJT / 직원 관리'
  if (pathname === '/admin/inquiries') return 'AJT / 지원 관리'
  if (/^\/admin\/inquiries\/[^/]+$/.test(pathname)) return 'AJT / 문의 관리'
  if (/^\/admin\/users\/[^/]+\/edit$/.test(pathname)) return 'AJT / 직원 상세'
  if (/^\/admin\/users\/[^/]+$/.test(pathname)) return 'AJT / 직원 관리'
  return null
}
