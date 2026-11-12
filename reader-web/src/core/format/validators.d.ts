/** Standalone validators generated from format/*.schema.json by scripts/validators.mjs. */
import type { ErrorObject } from 'ajv'
import type { Chapter } from './chapter'
import type { Manifest } from './manifest'

interface Validate<T> { (data: unknown): data is T; errors?: ErrorObject[] | null }
export const manifest: Validate<Manifest>
export const chapter: Validate<Chapter>
