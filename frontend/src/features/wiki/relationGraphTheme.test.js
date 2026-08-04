import { describe, expect, it } from 'vitest'
import { CENTER_FILL, WIKI_FILL, fillOf, nodeShape, showsNodeIcon, strokeOf } from './relationGraphTheme'

describe('relation graph node visual language', () => {
  it('uses circles for every graph node', () => {
    expect(nodeShape('center')).toBe('circle')
    expect(nodeShape('wiki')).toBe('circle')
    expect(nodeShape('document')).toBe('circle')
  })

  it('uses icons for wiki and evidence-document nodes', () => {
    expect(showsNodeIcon('center')).toBe(true)
    expect(showsNodeIcon('document')).toBe(true)
    expect(showsNodeIcon('wiki')).toBe(true)
  })

  it('keeps the current wiki visually stronger than related wikis', () => {
    expect(CENTER_FILL).toBe('#2563eb')
    expect(WIKI_FILL).toBe('#3b82f6')
  })

  it('uses a white surface and no outline for evidence documents', () => {
    expect(fillOf('document')).toBe('#ffffff')
    expect(strokeOf('document')).toBe('transparent')
  })
})
