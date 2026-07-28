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
  '/admin/departments': '부서 관리',
  '/admin/inquiries': '문의 관리',
  '/admin/documents': '문서 관리',
  '/admin/schedules': '일정 관리',
}
