<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElCard, ElTable, ElTableColumn, ElTag, ElButton, ElDatePicker, ElAlert
} from 'element-plus'
import { watchApi, type RouteWatchEntry } from '../api'
import PolicyNotice from '../components/PolicyNotice.vue'
import { groundMetaApi } from '../api'
import type { GroundMeta } from '../api'
import { useRouter } from 'vue-router'

const entries = ref<RouteWatchEntry[]>([])
const meta = ref<GroundMeta | null>(null)
const takeoff = ref<Date>(defaultTodayNoon())
const router = useRouter()

function defaultTodayNoon(): Date {
  const d = new Date()
  d.setHours(12, 0, 0, 0)
  return d
}

function pad(n: number) { return String(n).padStart(2, '0') }
function iso(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

async function load() {
  entries.value = await watchApi.routeEntries(iso(takeoff.value))
}

onMounted(async () => {
  meta.value = await groundMetaApi.get()
  await load()
})

const statusType = (s: string | null): any =>
  ({ DRAFT: 'info', PENDING_REVIEW: 'warning', READY: 'success', CANCELLED: 'danger' } as Record<string, string>)[s ?? '']

function goWatch() {
  router.push('/watch')
}
</script>

<template>
  <div class="space-y-4">
    <PolicyNotice :policy="meta?.policy ?? ''" :rejected-reason="meta?.rejectedReason ?? ''" />

    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <span class="text-base font-semibold">航线入口 · 当日开航就绪状态</span>
          <div class="flex items-center gap-3">
            <span class="text-sm text-gray-600">查看日期/起飞时刻：</span>
            <ElDatePicker v-model="takeoff" type="datetime" format="YYYY-MM-DD HH:mm"
                          style="width: 220px" @change="load" />
            <ElButton @click="load">刷新</ElButton>
            <ElButton type="primary" @click="goWatch">前往值守安排</ElButton>
          </div>
        </div>
      </template>

      <ElAlert type="info" :closable="false" class="mb-3"
               title="同一日期、同一条航线的资格结论在本页、开航值守详情、地勤人员列表三处完全一致（同一判定引擎、同一起飞时刻口径）。" />

      <ElTable :data="entries" stripe>
        <ElTableColumn label="航线" min-width="180">
          <template #default="{ row }: { row: any }">
            <div class="font-medium">{{ row.routeCode }} {{ row.routeName }}</div>
            <div class="text-xs text-gray-500" v-if="row.status !== 1">（航线已停用）</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="当前风级" width="100">
          <template #default="{ row }: { row: any }">
            <ElTag size="small">{{ row.windLevel }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="在用锚点 / 区域" min-width="220">
          <template #default="{ row }: { row: any }">
            <div class="text-xs text-gray-600">
              {{ row.activeAnchorCodes.length ? row.activeAnchorCodes.join('、') : '（无在用锚点）' }}
            </div>
            <div class="mt-1">
              <ElTag v-for="z in row.requiredZones" :key="z" size="small" type="warning" class="mr-1">{{ z }}</ElTag>
              <span v-if="!row.requiredZones.length" class="text-xs text-gray-400">无区域要求</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="`${takeoff.getFullYear()}-${pad(takeoff.getMonth()+1)}-${pad(takeoff.getDate())} 值守`" min-width="380">
          <template #default="{ row }: { row: any }">
            <template v-if="!row.watchId">
              <ElTag type="info">未安排</ElTag>
            </template>
            <template v-else>
              <div class="flex items-center gap-2 flex-wrap">
                <ElTag :type="statusType(row.watchStatus)">{{ row.watchStatusLabel }}</ElTag>
                <span class="text-sm">操作员：{{ row.operatorName }}</span>
                <ElTag size="small" :type="row.operatorQualified ? 'success' : 'danger'">
                  {{ row.operatorQualified ? '资质满足' : '资质缺口' }}
                </ElTag>
              </div>
              <div class="flex items-center gap-2 flex-wrap mt-1">
                <span class="text-sm">复核员：{{ row.reviewerName }}</span>
                <ElTag size="small" :type="row.reviewerQualified ? 'success' : 'danger'">
                  {{ row.reviewerQualified ? '资质满足' : '资质缺口' }}
                </ElTag>
                <ElTag v-if="!row.distinctPeople" size="small" type="danger">同一人</ElTag>
                <ElTag v-if="row.operatorArrived === 1" size="small" type="success">操作员已到位</ElTag>
              </div>
              <div v-for="(g, i) in row.gapMessages ?? []" :key="i" class="text-xs text-red-600 mt-1">
                · {{ g }}
              </div>
            </template>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>
