import { createContext } from 'react'

// 인증 컨텍스트.
// value: { user, role, isAuthenticated, initializing, login, logout }
export const AuthContext = createContext(null)
