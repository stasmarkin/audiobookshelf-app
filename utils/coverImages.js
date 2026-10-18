/**
 * Loads every cover once and all at the same time, so a canvas waits for the slowest one rather than for the sum of
 * them (each may wait for its download). Resolves to id -> loaded image; covers that failed are left out.
 */
export async function loadCoverImages(ids, srcOf, createImage = () => new Image()) {
  const unique = [...new Set(ids)]
  const loaded = await Promise.all(
    unique.map(
      (id) =>
        new Promise((resolve) => {
          const img = createImage()
          img.crossOrigin = 'anonymous'
          img.addEventListener('load', () => resolve(img))
          img.addEventListener('error', () => resolve(null))
          img.src = srcOf(id)
        })
    )
  )
  return new Map(unique.map((id, i) => [id, loaded[i]]).filter(([, img]) => img))
}
