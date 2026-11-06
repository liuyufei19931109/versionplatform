import { ref } from 'vue'
import management from './locales/management.json'
import operations from './locales/operations.json'
import workspace from './locales/workspace.json'
import feedback from './locales/feedback.json'
import status from './locales/status.json'
import shared from './locales/shared.json'

export type Language = 'zh-CN' | 'en' | 'es'
const storageKey = 'release_ui_language'
const messages: Record<string, string[]> = { ...workspace, ...operations, ...management, ...feedback, ...status, ...shared }

export function resolveLanguage(preferences: readonly string[]): Language {
  for (const tag of preferences) {
    switch (tag.trim().toLowerCase().split(/[-_]/)[0]) {
      case 'zh': return 'zh-CN'
      case 'es': return 'es'
      case 'en': return 'en'
    }
  }
  return 'zh-CN'
}

function initialLanguage(): Language {
  const preferences: string[] = []
  try {
    const saved = localStorage.getItem(storageKey)
    if (saved) preferences.push(saved)
  } catch { /* Browser storage can be blocked. */ }
  if (typeof window !== 'undefined' && typeof navigator !== 'undefined') preferences.push(...(navigator.languages || [navigator.language]))
  return resolveLanguage(preferences)
}

export const language = ref<Language>(initialLanguage())
export const currentLocale = (): Language => language.value

export function selectLanguage(value: Language) {
  language.value = value
  try { localStorage.setItem(storageKey, value) } catch { /* The current tab still updates. */ }
}

export function tUi(source: string, ...values: unknown[]): string {
  const locale = language.value
  const pair = Object.prototype.hasOwnProperty.call(messages, source) ? messages[source] : undefined
  const text = locale === 'zh-CN' || !pair ? source : pair[locale === 'en' ? 0 : 1]
  return values.length ? text.replace(/\{(\d+)\}/g, (token, index) => Number(index) < values.length ? String(values[Number(index)] ?? '') : token) : text
}

export function syncPageLanguage() {
  const sync = (event: StorageEvent) => {
    if (event.key === storageKey || event.key === null) language.value = initialLanguage()
  }
  window.addEventListener('storage', sync)
}
