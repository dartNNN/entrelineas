import type { Chapter } from './chapter'
import type { Manifest } from './manifest'
// The same schemas the builder checks before writing a package and the Android tests check every test book against,
// compiled at build time (scripts/validators.mjs).
import { chapter, manifest } from './validators'

function describe(errors: typeof manifest.errors): string {
  return (errors ?? []).slice(0, 5).map((e) => `${e.instancePath || '/'} ${e.message}`).join('; ')
}

export function checkManifest(data: unknown): Manifest {
  if (!manifest(data)) throw new Error(`manifest.json не соответствует формату: ${describe(manifest.errors)}`)
  return data
}

export function checkChapter(data: unknown, file: string): Chapter {
  if (!chapter(data)) throw new Error(`${file} не соответствует формату: ${describe(chapter.errors)}`)
  return data
}
