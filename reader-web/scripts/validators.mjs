// Compiles the package schemas (format/*.schema.json) into standalone validators at build time, so the reader ships
// plain functions: no schema compiler in the bundle and no code generated at run time (new Function), which a strict
// Content Security Policy forbids. Run by `npm run types`.
import Ajv2020 from 'ajv/dist/2020.js'
import standaloneCode from 'ajv/dist/standalone/index.js'
import { readFileSync, writeFileSync } from 'node:fs'

const schema = (name) => JSON.parse(readFileSync(new URL(`../../format/${name}.schema.json`, import.meta.url), 'utf8'))
const ajv = new Ajv2020({ allErrors: true, strict: false, code: { source: true, esm: true } })
ajv.addSchema(schema('manifest'), 'manifest').addSchema(schema('chapter'), 'chapter')
// The generated code requires ajv's string-length helper (CommonJS); the same function inline keeps the file plain ESM.
const UCS2LENGTH = 'function ucs2length(str) { let length = 0, pos = 0; while (pos < str.length) { length++; const c = str.charCodeAt(pos++); if (c >= 0xd800 && c <= 0xdbff && pos < str.length && (str.charCodeAt(pos) & 0xfc00) === 0xdc00) pos++ } return length }'
const code = standaloneCode(ajv, { manifest: 'manifest', chapter: 'chapter' }).replaceAll('require("ajv/dist/runtime/ucs2length").default', 'ucs2length')
if (code.includes('require(')) throw new Error('standalone validators still require a module')
writeFileSync(new URL('../src/core/format/validators.js', import.meta.url),
  `// Generated from format/*.schema.json by npm run types; do not edit.\n// @ts-nocheck\n${code}\n${UCS2LENGTH}\n`)
