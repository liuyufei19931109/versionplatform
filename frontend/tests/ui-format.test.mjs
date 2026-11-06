import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

const source = await readFile(new URL('../src/ui.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { activityTime, completionTime, shortCommit, timeParts } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('short display preserves the full revision held by callers and tolerates legacy missing values', () => {
  const original = '12345678' + 'ab'.repeat(16)
  assert.equal(shortCommit(original), '12345678')
  assert.equal(original.length, 40)
  assert.equal(shortCommit(undefined), '—')
  assert.equal(shortCommit(' abc123 '), 'abc123')
})

test('legacy successful records never label a start or creation timestamp as completion', () => {
  assert.deepEqual(completionTime({ finishedAt: '2026-10-11T12:35:10', startedAt: '2026-10-11T12:30:00' }), { value: '2026-10-11T12:35:10', label: '发布完成' })
  assert.deepEqual(completionTime({ startedAt: '2026-10-11T12:30:00' }), { value: '2026-10-11T12:30:00', label: '开始于' })
  assert.equal(completionTime({ createdAt: '2026-10-11T12:00:00' }).label, '创建于')
})

test('displayed history time follows server sorting without substituting a later metadata update for completion', () => {
  const task = { status: 'SUCCESS', createdAt: '2026-10-11T12:00:00', startedAt: '2026-10-11T12:02:00', finishedAt: '2026-10-11T12:03:00', updatedAt: '2026-10-11T18:00:00' }
  assert.equal(activityTime(task).value, task.finishedAt)
  assert.deepEqual(activityTime({ ...task, status: 'RUNNING' }), { value: task.updatedAt, label: '更新' })
  assert.deepEqual(activityTime({ status: 'PENDING', createdAt: task.createdAt, updatedAt: task.createdAt }), { value: task.createdAt, label: '创建' })
})

test('time layout retains exact seconds in the accessible full timestamp', () => {
  assert.deepEqual(timeParts('2026-10-11T08:05:09'), { date: '2026-10-11', time: '08:05', full: '2026-10-11 08:05:09' })
  assert.equal(timeParts(null).date, '—')
  assert.equal(timeParts('legacy-value').full, 'legacy-value')
})
