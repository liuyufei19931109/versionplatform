import { tUi } from './i18n'
// Pure helpers for the service log viewer. Network and DOM code lives in ContainerLogsView.vue.
export type LogLevel = 'error' | 'warn' | 'info' | 'debug' | ''
export type LogSource = 'application' | 'stdout'
export interface RawLogLine { ts: string | null; text: string }
export interface LogEntry { id: number; ts: string | null; text: string; level: LogLevel; continuation: boolean }
export interface LogContainer { id: string; name: string; state: string }
export interface LogFile { id: string; name: string }
export interface LogHeader {
  serviceName: string; environmentName: string; serverName: string
  containers: LogContainer[]; containerId: string; containerName: string
  source: LogSource; logDirectory: string; files: LogFile[]; fileId: string; fallbackReason: string | null
}
export interface LogSnapshot { header: LogHeader; lines: RawLogLine[]; truncated: boolean; fetchedAt: string }

export const TAIL_OPTIONS = [200, 500, 1000, 2000, 5000]
export const SINCE_OPTIONS = [
  { value: '', get label() { return tUi('不限时间') } }, { value: '5m', get label() { return tUi('最近 5 分钟') } }, { value: '15m', get label() { return tUi('最近 15 分钟') } },
  { value: '1h', get label() { return tUi('最近 1 小时') } }, { value: '6h', get label() { return tUi('最近 6 小时') } }, { value: '24h', get label() { return tUi('最近 24 小时') } },
]
export const MAX_ENTRIES = 20000

const LEVEL_WORD = /(?:^|[\s[\]|(:"'=])(FATAL|SEVERE|CRITICAL|ERROR|ERR|WARNING|WARN|INFO|DEBUG|TRACE)(?=$|[\s\]|):"',])/
const JSON_LEVEL = /(?:"(?:level|severity|lvl)"\s*:\s*"|\b(?:level|lvl)=)(\w+)/i
// Stack frames, "Caused by", "... 12 more" and bare exception headlines belong to the entry above them.
const CONTINUATION = /^(?:\s+\S|\s*at\s|\s*Caused by:|\s*Suppressed:|\s*\.\.\. \d+ (?:more|common frames omitted)|[\w$.]+(?:Exception|Error|Throwable)(?::\s.*)?$)/

export function levelOf(text: string): LogLevel {
  const json = JSON_LEVEL.exec(text)?.[1] ?? LEVEL_WORD.exec(text.slice(0, 200))?.[1]
  switch ((json || '').toUpperCase()) {
    case 'FATAL': case 'SEVERE': case 'CRITICAL': case 'ERROR': case 'ERR': return 'error'
    case 'WARNING': case 'WARN': return 'warn'
    case 'INFO': return 'info'
    case 'DEBUG': case 'TRACE': return 'debug'
    default: return ''
  }
}

/** Continuation lines inherit the level of the entry they belong to, so "errors only" keeps whole stack traces. */
export function toEntries(lines: RawLogLine[], nextId: number, previous?: LogEntry): LogEntry[] {
  const result: LogEntry[] = []
  let parent = previous
  for (const line of lines) {
    const text = String(line?.text ?? '')
    const continuation = Boolean(parent) && text.length > 0 && CONTINUATION.test(text) && !LEVEL_WORD.test(text.slice(0, 200))
    const entry: LogEntry = { id: nextId++, ts: line?.ts || null, text, level: continuation ? parent!.level : levelOf(text), continuation }
    result.push(entry)
    parent = entry
  }
  return result
}

/** Drop lines a reconnect or "load earlier" request returns again at the boundary timestamp. */
export function withoutOverlap(incoming: RawLogLine[], boundary: LogEntry[], side: 'after' | 'before'): RawLogLine[] {
  const edge = side === 'after' ? boundary[boundary.length - 1]?.ts : boundary[0]?.ts
  if (!edge) return incoming
  const seen = new Set(boundary.filter(entry => entry.ts && compareTime(entry.ts, edge) === 0).map(entry => entry.text))
  return incoming.filter(line => {
    if (!line.ts) return true
    const order = compareTime(line.ts, edge)
    if (side === 'after' ? order < 0 : order > 0) return false
    return order !== 0 || !seen.has(line.text)
  })
}

function compareTime(left: string, right: string) {
  const difference = Date.parse(left) - Date.parse(right)
  if (Number.isFinite(difference)) {
    if (difference) return difference
    // Application timestamps may omit zero fractions; Docker timestamps retain nanoseconds.
    const fraction = (value: string) => (/\.(\d+)/.exec(value)?.[1] || '').padEnd(9, '0')
    const a = fraction(left), b = fraction(right)
    return a === b ? 0 : a < b ? -1 : 1
  }
  return left === right ? 0 : left < right ? -1 : 1
}

export function logTime(ts: string | null) {
  if (!ts) return ''
  const fraction = /\.(\d{1,3})/.exec(ts)?.[1]?.padEnd(3, '0') || '000'
  const date = new Date(ts)
  if (Number.isNaN(date.getTime())) return ts
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${fraction}`
}

export function matches(entry: LogEntry, query: string) {
  return !query || entry.text.toLowerCase().includes(query.toLowerCase())
}

export function segments(text: string, query: string): { text: string; hit: boolean }[] {
  if (!query) return [{ text, hit: false }]
  const lower = text.toLowerCase(), needle = query.toLowerCase(), parts: { text: string; hit: boolean }[] = []
  let index = 0
  while (index < text.length) {
    const found = lower.indexOf(needle, index)
    if (found < 0) { parts.push({ text: text.slice(index), hit: false }); break }
    if (found > index) parts.push({ text: text.slice(index, found), hit: false })
    parts.push({ text: text.slice(found, found + needle.length), hit: true })
    index = found + needle.length
  }
  return parts.length ? parts : [{ text, hit: false }]
}

export function visibleEntries(entries: LogEntry[], options: { query: string; onlyMatches: boolean; level: 'all' | 'warn' | 'error' }) {
  return entries.filter(entry => {
    if (options.level === 'error' && entry.level !== 'error') return false
    if (options.level === 'warn' && entry.level !== 'error' && entry.level !== 'warn') return false
    return !options.onlyMatches || matches(entry, options.query)
  })
}

export function downloadText(entries: LogEntry[], source: LogSource = 'stdout') {
  return entries.map(entry => source === 'stdout' && entry.ts ? `${entry.ts} ${entry.text}` : entry.text).join('\n') + (entries.length ? '\n' : '')
}

export function downloadName(parts: (string | undefined)[], now = new Date()) {
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  const name = parts.filter(Boolean).map(part => String(part).replace(/[^\w.一-龥-]+/g, '_')).join('-')
  return `${name || 'container'}-${stamp}.log`
}

export interface SseEvent { event: string; data: string }
/** Incremental text/event-stream parser; returns complete events and keeps the unfinished remainder. */
export function parseSse(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = []
  const normalized = buffer.replace(/\r\n?/g, '\n')
  const blocks = normalized.split('\n\n')
  const rest = blocks.pop() ?? ''
  for (const block of blocks) {
    let event = 'message'; const data: string[] = []
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) continue
      const colon = line.indexOf(':'), field = colon < 0 ? line : line.slice(0, colon)
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'event') event = value
      else if (field === 'data') data.push(value)
    }
    if (data.length) events.push({ event, data: data.join('\n') })
  }
  return { events, rest }
}
