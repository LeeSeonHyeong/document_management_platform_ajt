export function firstRelatedWiki(document) {
  return document?.relatedWikis?.[0] ?? null
}
