// Production only accepts an artifact that already deployed successfully to pre-production,
// or one that already ran in production (rollback). The backend enforces the same rule.
export interface ProductionReadiness {
  artifactId: number
  preTaskId?: number | null
  preVerifiedAt?: string | null
  preVerifiedBy?: string | null
  prodTaskId?: number | null
  prodReleasedAt?: string | null
}

export const PRODUCTION_GATE_MESSAGE = '生产只能发布已在预生产发布成功的制品'

export function readinessMap(rows: ProductionReadiness[]) {
  const map = new Map<string, ProductionReadiness>()
  for (const row of rows) if (row && row.artifactId != null) map.set(String(row.artifactId), row)
  return map
}

export function productionEligible(readiness?: ProductionReadiness) {
  return Boolean(readiness && (readiness.preTaskId || readiness.prodTaskId))
}

export function readinessBadge(readiness?: ProductionReadiness): { text: string; type: 'success' | 'info' | 'warning' } {
  if (readiness?.preTaskId) return { text: '预生产已通过', type: 'success' }
  if (readiness?.prodTaskId) return { text: '曾发布生产', type: 'info' }
  return { text: '未经预生产', type: 'warning' }
}
