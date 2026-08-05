import { describe, expect, it } from 'vitest'
import { sanitizePlainName } from './sanitizePlainName'

describe('sanitizePlainName', () => {
  it('한글·영문·숫자·공백은 유지한다', () => {
    expect(sanitizePlainName('개발 Team 2')).toBe('개발 Team 2')
  })

  it('특수문자와 이모지를 제거한다', () => {
    expect(sanitizePlainName('개발팀! 🚀 @본사')).toBe('개발팀  본사')
  })
})
