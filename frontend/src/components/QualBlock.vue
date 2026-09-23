<script setup lang="ts">
import { ElCard, ElTag } from 'element-plus'
import type { Qualification } from '../api'

defineProps<{
  title: string
  name: string
  q: Qualification | null
}>()

const statusLabel: Record<string, string> = {
  VALID: '有效',
  PENDING: '待生效',
  EXPIRED: '已过期',
  REVOKED: '已吊销',
  NONE: '无证书'
}
</script>

<template>
  <ElCard shadow="never" class="mb-2">
    <template #header>
      <div class="flex items-center gap-2 flex-wrap">
        <span class="font-medium">{{ title }}：{{ name }}</span>
        <ElTag :type="q?.qualified ? 'success' : 'danger'">
          {{ q?.qualified ? '资质满足' : '资质不满足' }}
        </ElTag>
        <span v-if="q?.activeCertNo" class="text-xs text-gray-500">
          代表证书 <span class="font-mono">{{ q.activeCertNo }}</span>
          （{{ statusLabel[q.activeCertStatus] ?? q?.activeCertStatus }}）
        </span>
      </div>
    </template>
    <div class="text-sm space-y-1">
      <div v-if="q?.coveredWindLevels.length">证书适用风级：{{ q.coveredWindLevels.join('、') }}</div>
      <div>证书覆盖区域：{{ q?.coveredZones.length ? q.coveredZones.join('、') : '无' }}</div>
      <div v-for="(m, i) in q?.detailMessages ?? []" :key="i" class="text-xs text-red-600">
        · {{ m }}
      </div>
    </div>
  </ElCard>
</template>
