/** Colour roles, read from CSS custom properties so both themes swap in one place.
 *
 * Canvas cannot read `var(--x)`, so the values are resolved off the root element
 * once per theme change and handed to the renderer as plain strings.
 *
 * Hue carries **kind**, not category. A node-link graph puts any two categories
 * side by side, so its palette has to clear the all-pairs floors — and no fourth
 * categorical slot does that in both light and dark. Three does, in both. So the
 * three kinds get the three validated hues, and category is encoded by position:
 * each category is pulled toward its own centre, with a label drawn there. That
 * reads better than four hues on a 4px dot anyway.
 */

export interface Palette {
  surface: string
  ink: string
  inkSecondary: string
  muted: string
  page: string
  index: string
  source: string
  edge: string
  edgeHover: string
  edgeCite: string
  orphanRing: string
}

const ROLES: Record<keyof Palette, string> = {
  surface: '--surface',
  ink: '--ink',
  inkSecondary: '--ink-secondary',
  muted: '--muted',
  page: '--kind-page',
  index: '--kind-index',
  source: '--kind-source',
  edge: '--edge',
  edgeHover: '--edge-hover',
  edgeCite: '--edge-cite',
  orphanRing: '--orphan-ring',
}

export function readPalette(): Palette {
  const style = getComputedStyle(document.documentElement)
  const out = {} as Palette
  for (const [role, prop] of Object.entries(ROLES) as [keyof Palette, string][]) {
    out[role] = style.getPropertyValue(prop).trim()
  }
  return out
}

export function colorForKind(palette: Palette, kind: string): string {
  if (kind === 'index') return palette.index
  if (kind === 'source') return palette.source
  return palette.page
}

/** Stable per-category angle, so a category sits in the same place across runs. */
export function categoryAngle(category: string, all: string[]): number {
  const i = all.indexOf(category)
  return i < 0 ? 0 : (i / Math.max(all.length, 1)) * Math.PI * 2
}
