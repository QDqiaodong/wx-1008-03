<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  ElTable, ElTableColumn, ElSelect, ElOption, ElButton, ElMessage,
  ElTag, ElPagination, ElEmpty
} from 'element-plus'
import type { AdaptLog, AdaptLogSnapshot, FlightRoute } from '../api'
import { adaptApi, routeApi } from '../api'
import { latestRequestGuard } from '../composables/latestRequestGuard'

const PAGE_SIZE = 10

const logs = ref<AdaptLog[]>([])
const routes = ref<FlightRoute[]>([])
/** 当前筛选航线ID；0 表示全部航线 */
const selectedRouteId = ref<number>(0)
const pageNo = ref(1)
const total = ref(0)
const totalPages = ref(0)

const loading = ref(false)
/** 加载失败信息；非空时页面明确提示“请求失败”，与空命中区分开 */
const loadError = ref('')
/** 最近一次快照元信息，页面与导出共用同一口径 */
const snapshotTime = ref('')
const snapshotMaxId = ref<number | null>(null)

/**
 * 读取并发守卫：每次发起读取取号，旧响应返回时若序号已落后则丢弃。
 * 快速从航线甲改选航线乙、翻页、刷新并发时，只允许最后一次结果更新页面。
 */
const listGuard = latestRequestGuard()
/**
 * 导出并发守卫：连续点击/切换航线时，只下载最后一次确认筛选对应的文件。
 * 文件由服务端按当前筛选重新冻结快照生成，不读取页面上的旧数组。
 * 切换航线时也会取号作废在途导出。
 */
const exportGuard = latestRequestGuard()

const currentFilter = () => selectedRouteId.value || undefined

const loadLogs = async (resetPage = false) => {
  if (resetPage) pageNo.value = 1
  const token = listGuard.begin()
  loading.value = true
  loadError.value = ''
  try {
    const snapshot: AdaptLogSnapshot = await adaptApi.logsPage({
      routeId: currentFilter(),
      pageNo: pageNo.value,
      pageSize: PAGE_SIZE
    })
    // 旧响应（航线甲/旧页）晚返回：序号落后，直接丢弃，不能覆盖更新的筛选结果
    if (!listGuard.isLatest(token)) return
    logs.value = snapshot.records
    total.value = snapshot.total
    totalPages.value = snapshot.totalPages
    // 服务端把越界页码收敛到最后一页时，同步回真实页码
    pageNo.value = snapshot.pageNo
    snapshotTime.value = snapshot.snapshotTime
    snapshotMaxId.value = snapshot.snapshotMaxId
  } catch (e) {
    if (!listGuard.isLatest(token)) return
    logs.value = []
    total.value = 0
    totalPages.value = 0
    loadError.value = e instanceof Error ? e.message : '流水查询失败'
  } finally {
    if (listGuard.isLatest(token)) loading.value = false
  }
}

const loadRoutes = async () => {
  try {
    routes.value = await routeApi.list()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '航线列表加载失败')
  }
}

const handleFilterChange = () => {
  // 切换航线会作废在途导出：导出必须对应用户最后一次确认的筛选
  exportGuard.begin()
  loadLogs(true)
}

const handlePageChange = (p: number) => {
  pageNo.value = p
  loadLogs()
}

const handleRefresh = () => {
  loadLogs()
}

/** 从 Content-Disposition 取服务端文件名（优先 filename* 的 UTF-8 值） */
const parseFileName = (disposition: string | undefined, fallback: string) => {
  if (!disposition) return fallback
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star?.[1]) return decodeURIComponent(star[1])
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] || fallback
}

const exporting = ref(false)
const handleExport = async () => {
  const token = exportGuard.begin()
  exporting.value = true
  const routeLabel = currentFilter()
    ? (routes.value.find(r => r.id === selectedRouteId.value)?.routeCode ?? `航线${selectedRouteId.value}`)
    : '全部航线'
  try {
    const res = await adaptApi.exportLogs(currentFilter())
    // 期间又点了一次导出或改了筛选：丢弃旧文件，只保留最后一次确认的结果
    if (!exportGuard.isLatest(token)) return

    const blob = res.data
    const fileName = parseFileName(
      res.headers['content-disposition'] as string | undefined,
      `adapt-logs-${new Date().toISOString().slice(0, 10)}.json`
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = fileName
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`导出成功（${routeLabel}，以服务端快照为准）`)
  } catch (e) {
    if (!exportGuard.isLatest(token)) return
    // 请求失败与“空命中”区分：空命中会正常下载文件，走到这里才是真失败
    ElMessage.error(e instanceof Error ? e.message : '导出失败，请重试')
  } finally {
    if (exportGuard.isLatest(token)) exporting.value = false
  }
}

const getOperationTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    BIND: '绑定',
    UNBIND: '解绑',
    REBIND: '重新绑定',
    REJECT: '拒绝',
    OCCUPY_CONFLICT: '占用冲突'
  }
  return map[type] || type
}

const getOperationTypeColor = (type: string): 'success' | 'danger' | 'warning' | 'info' => {
  const map: Record<string, 'success' | 'danger' | 'warning' | 'info'> = {
    BIND: 'success',
    UNBIND: 'warning',
    REBIND: 'warning',
    REJECT: 'danger',
    OCCUPY_CONFLICT: 'danger'
  }
  return map[type] || 'info'
}

const formatTime = (raw: string) => {
  if (!raw) return '-'
  return raw.replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  loadLogs()
  loadRoutes()
})
</script>

<template>
  <div class="bg-white rounded-lg shadow p-6">
    <div class="flex justify-between items-center mb-6">
      <h2 class="text-xl font-semibold">适配调整流水记录</h2>
      <div class="flex items-center gap-3">
        <ElButton :loading="loading" @click="handleRefresh">刷新</ElButton>
        <ElButton type="primary" :loading="exporting" @click="handleExport">导出记录</ElButton>
      </div>
    </div>

    <div class="bg-gray-50 rounded-lg p-4 mb-4">
      <div class="flex flex-wrap items-center gap-4">
        <span class="text-gray-600">筛选航线:</span>
        <ElSelect
          v-model="selectedRouteId"
          placeholder="全部航线"
          style="width: 300px;"
          :disabled="loading"
          @change="handleFilterChange"
        >
          <ElOption label="全部航线" :value="0" />
          <ElOption
            v-for="route in routes"
            :key="route.id"
            :label="`${route.routeCode} - ${route.routeName}`"
            :value="route.id"
          />
        </ElSelect>
        <span v-if="snapshotTime" class="text-xs text-gray-400">
          查询时刻 {{ formatTime(snapshotTime) }}<template v-if="snapshotMaxId !== null"> · 快照高水位ID #{{ snapshotMaxId }}</template>
        </span>
      </div>
    </div>

    <!-- 请求失败：明确报错并保留重试，绝不伪装成“没有数据” -->
    <div v-if="loadError" class="mb-4 p-4 rounded-lg bg-red-50 text-red-600 flex items-center justify-between">
      <span>流水加载失败：{{ loadError }}</span>
      <ElButton size="small" type="danger" plain @click="handleRefresh">重试</ElButton>
    </div>

    <ElTable v-loading="loading" :data="logs" stripe>
      <ElTableColumn prop="id" label="ID" width="80" />
      <ElTableColumn prop="routeCode" label="航线编号" />
      <ElTableColumn prop="anchorCode" label="锚点编号" />
      <ElTableColumn prop="operationType" label="操作类型">
        <template #default="scope">
          <ElTag :type="getOperationTypeColor(scope.row.operationType)">
            {{ getOperationTypeLabel(scope.row.operationType) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="气流强度变化(m/s)">
        <template #default="scope">
          <span v-if="scope.row.beforeWindSpeed !== null">
            {{ scope.row.beforeWindSpeed }} → {{ scope.row.afterWindSpeed }}
          </span>
          <span v-else-if="scope.row.afterWindSpeed !== null">{{ scope.row.afterWindSpeed }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="锚点承重(kg)">
        <template #default="scope">
          <span v-if="scope.row.afterWeight !== null">{{ scope.row.afterWeight }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="reason" label="操作原因" min-width="200" show-overflow-tooltip />
      <ElTableColumn prop="operator" label="操作人" />
      <ElTableColumn prop="createTime" label="操作时间" width="180">
        <template #default="scope">{{ formatTime(scope.row.createTime) }}</template>
      </ElTableColumn>

      <!-- 空命中：请求成功但筛选下没有记录，与上面的“请求失败”互斥 -->
      <template #empty>
        <ElEmpty v-if="!loadError" description="当前筛选条件下没有命中流水记录（并非请求失败）" />
        <ElEmpty v-else description="加载失败，请点击重试" />
      </template>
    </ElTable>

    <div class="flex justify-end mt-4">
      <ElPagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="PAGE_SIZE"
        :current-page="pageNo"
        :page-count="totalPages"
        :disabled="loading"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>
