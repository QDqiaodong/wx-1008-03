<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElCard, ElButton, ElTable, ElTableColumn, ElTag, ElDrawer, ElDescriptions,
  ElDescriptionsItem, ElAlert, ElMessage, ElMessageBox, ElEmpty
} from 'element-plus'
import {
  incidentApi, routeApi, anchorApi, staffApi,
  type IncidentListItem, type IncidentDetail, type IncidentRevision,
  type IncidentConflict, type IncidentCreateDTO, type IncidentEditDTO,
  type FlightRoute, type Anchor, type GroundStaff
} from '../api'
import IncidentCreateDialog from '../components/incident/IncidentCreateDialog.vue'
import IncidentEditDialog from '../components/incident/IncidentEditDialog.vue'
import IncidentConflictDialog from '../components/incident/IncidentConflictDialog.vue'
import IncidentRevisionsDialog from '../components/incident/IncidentRevisionsDialog.vue'

const incidents = ref<IncidentListItem[]>([])
const routes = ref<FlightRoute[]>([])
const anchors = ref<Anchor[]>([])
const staff = ref<GroundStaff[]>([])

const createOpen = ref(false)
const editOpen = ref(false)
const drawerOpen = ref(false)
const revisionsOpen = ref(false)
const current = ref<IncidentDetail | null>(null)
const revisions = ref<IncidentRevision[]>([])
const conflict = ref<IncidentConflict | null>(null)

const statusType = (s: string): any =>
  ({ DRAFT: 'info', INVESTIGATING: 'warning', PENDING_SEAL: 'warning', SEALED: 'success', REOPENED: 'danger' } as Record<string, string>)[s]
const severityType = (s: string): any =>
  ({ MINOR: 'info', MAJOR: 'warning', CRITICAL: 'danger' } as Record<string, string>)[s]

onMounted(async () => {
  const [rs, ans, ss] = await Promise.all([routeApi.list(), anchorApi.list(), staffApi.active()])
  routes.value = rs
  anchors.value = ans
  staff.value = ss
  await load()
})

async function load() {
  incidents.value = await incidentApi.list()
}

async function refreshCurrent() {
  if (!current.value) return
  current.value = await incidentApi.get(current.value.id)
  await load()
}

async function openDetail(row: IncidentListItem) {
  try {
    current.value = await incidentApi.get(row.id)
    drawerOpen.value = true
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

/* ---------- 建立 ---------- */

async function onCreate(dto: IncidentCreateDTO) {
  try {
    const detail = await incidentApi.create(dto)
    createOpen.value = false
    ElMessage.success(`事件 ${detail.incidentNo} 已建立（草稿），关联资料快照已冻结`)
    current.value = detail
    drawerOpen.value = true
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

/* ---------- 编辑/更正（统一 409 冲突处理） ---------- */

function handleConflict(e: any): boolean {
  if (e?.status === 409 && e?.data?.conflictFields) {
    conflict.value = e.data as IncidentConflict
    return true
  }
  return false
}

async function onEditSubmit(dto: IncidentEditDTO) {
  if (!current.value) return
  try {
    current.value = await incidentApi.edit(current.value.id, dto)
    editOpen.value = false
    ElMessage.success(current.value.status === 'SEALED' ? '更正已作为新版本保留，旧版未覆盖' : '修订已保存并留痕')
    await load()
  } catch (e: any) {
    if (!handleConflict(e)) ElMessage.error(e.message)
  }
}

function onConflictRebase(latest: IncidentDetail) {
  current.value = latest
  conflict.value = null
  // 先关闭再打开，确保编辑表单以最新版本内容与版本号重新填充（不做字段自动合并）
  editOpen.value = false
  setTimeout(() => {
    editOpen.value = true
    ElMessage.info('已载入最新版本内容，请在此基础上重新编辑后再保存')
  }, 0)
}

function onConflictDiscard() {
  conflict.value = null
  if (current.value) refreshCurrent()
}

/* ---------- 状态流转 ---------- */

function isCancel(e: unknown) {
  return e === 'cancel' || (e instanceof Error && e.message === 'cancel')
}

async function startInvestigation() {
  if (!current.value) return
  try {
    current.value = await incidentApi.startInvestigation(current.value.id, current.value.version)
    ElMessage.success('已开始调查，此后每次修订都会保留操作者、时间与前后内容')
    await load()
  } catch (e: any) {
    if (!handleConflict(e)) ElMessage.error(e.message)
  }
}

async function submitSeal() {
  if (!current.value) return
  try {
    current.value = await incidentApi.submitSeal(current.value.id, current.value.version)
    ElMessage.success('已提交封存，等待安全主管封存')
    await load()
  } catch (e: any) {
    if (!handleConflict(e)) ElMessage.error(e.message)
  }
}

async function promptReason(title: string, tip: string): Promise<string | null> {
  const res = await ElMessageBox.prompt(tip, title, {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    inputType: 'textarea',
    inputValidator: (v: string) => (!!v && !!v.trim()) || '必须填写'
  })
  return res.value
}

async function backInvestigating() {
  if (!current.value) return
  let reason: string | null
  try {
    reason = await promptReason('退回调查', '请填写退回原因（将写入修订记录）')
  } catch (e) {
    if (isCancel(e)) return
    throw e
  }
  try {
    current.value = await incidentApi.backInvestigating(current.value.id, current.value.version, reason!)
    ElMessage.success('已退回调查中')
    await load()
  } catch (e: any) {
    if (!handleConflict(e)) ElMessage.error(e.message)
  }
}

async function seal() {
  if (!current.value) return
  try {
    current.value = await incidentApi.seal(current.value.id, current.value.version)
    ElMessage.success('事件已封存；此后正文与原证据不可直接覆盖，更正只能产生带理由的新版本')
    await load()
  } catch (e: any) {
    if (!handleConflict(e)) ElMessage.error(e.message)
  }
}

async function reopen() {
  if (!current.value) return
  let reason: string | null
  try {
    reason = await promptReason('重新开启', '请写清重新开启的依据（新证据 / 新事实 / 纠错原因）')
  } catch (e) {
    if (isCancel(e)) return
    throw e
  }
  try {
    current.value = await incidentApi.reopen(current.value.id, current.value.version, reason!)
    ElMessage.success('事件已重新开启；再次封存时可从修订记录看到两次封存之间的全部动作')
    await load()
  } catch (e: any) {
    if (!handleConflict(e)) ElMessage.error(e.message)
  }
}

async function openRevisions() {
  if (!current.value) return
  revisions.value = await incidentApi.revisions(current.value.id)
  revisionsOpen.value = true
}
</script>

<template>
  <div class="space-y-4">
    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <span class="text-base font-semibold">飞行异常事件复盘</span>
          <div class="flex gap-3">
            <ElButton @click="load">刷新</ElButton>
            <ElButton type="primary" @click="createOpen = true">建立异常事件</ElButton>
          </div>
        </div>
      </template>

      <ElAlert type="info" :closable="false" class="mb-3"
               title="并发口径：整份事件版本冲突（贯穿详情、修订记录与后端持久化）。保存时必须携带当前版本号，两人同改时后到者会收到冲突字段与最新版本，不会静默覆盖；系统不做按字段自动合并。" />

      <ElTable :data="incidents" stripe>
        <ElTableColumn label="事件编号 / 标题" min-width="240">
          <template #default="{ row }: { row: any }">
            <div class="font-medium">{{ row.incidentNo }}</div>
            <div class="text-sm text-gray-600">{{ row.title }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="事发航线" min-width="180">
          <template #default="{ row }: { row: any }">
            <div>{{ row.routeCode }} {{ row.routeName }}</div>
            <div class="text-xs text-gray-400" v-if="row.watchFlightDate">值守飞行日 {{ row.watchFlightDate }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发现时间" width="150">
          <template #default="{ row }: { row: any }">
            {{ row.foundTime?.replace('T', ' ') }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="严重级别" width="90">
          <template #default="{ row }: { row: any }">
            <ElTag :type="severityType(row.severity)">{{ row.severityLabel }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态 / 版本" width="140">
          <template #default="{ row }: { row: any }">
            <ElTag :type="statusType(row.status)">{{ row.statusLabel }}</ElTag>
            <div class="text-xs text-gray-400 mt-1">
              v{{ row.version }}<span v-if="row.sealedCount > 0"> · 已封存{{ row.sealedCount }}次</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="报告人 / 负责人" min-width="140">
          <template #default="{ row }: { row: any }">
            <div>{{ row.reporterName }}</div>
            <div class="text-xs text-gray-400">{{ row.ownerName ?? '未指定负责人' }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="100">
          <template #default="{ row }: { row: any }">
            <ElButton size="small" @click="openDetail(row)">详情</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!incidents.length" description="没有可见事件（普通值班员只能看到自己报告、负责或参与值守的事件）" />
    </ElCard>

    <IncidentCreateDialog v-model="createOpen" :routes="routes" :anchors="anchors" @submit="onCreate" />
    <IncidentEditDialog v-model="editOpen" :detail="current" :staff="staff" @submit="onEditSubmit" />
    <IncidentConflictDialog :conflict="conflict" @discard="onConflictDiscard" @rebase="onConflictRebase" @close="conflict = null" />
    <IncidentRevisionsDialog v-model="revisionsOpen" :revisions="revisions" />

    <!-- 事件详情 -->
    <ElDrawer v-model="drawerOpen" title="异常事件详情" size="640px">
      <div v-if="current" class="px-2 space-y-4">
        <!-- 版本与状态抬头 -->
        <ElAlert
          :type="current.status === 'SEALED' ? 'success' : 'info'"
          :closable="false"
          :title="`${current.incidentNo}｜当前状态：${current.statusLabel}｜版本 v${current.version}${current.sealedCount ? `｜封存 ${current.sealedCount} 次` : ''}`"
        />
        <div class="text-xs text-gray-500 leading-5">
          <div>{{ current.concurrencyPolicy }}</div>
          <div class="mt-1">{{ current.fieldMergeRejectedReason }}</div>
        </div>

        <!-- 当前正文 -->
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="标题">{{ current.title }}</ElDescriptionsItem>
          <ElDescriptionsItem label="报告人">{{ current.reporterName }}</ElDescriptionsItem>
          <ElDescriptionsItem label="发现时间">{{ current.foundTime?.replace('T', ' ') }}</ElDescriptionsItem>
          <ElDescriptionsItem label="严重级别">
            <ElTag :type="severityType(current.severity)">{{ current.severityLabel }}</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="现场经过" class="whitespace-pre-wrap">{{ current.incidentNote }}</ElDescriptionsItem>
          <ElDescriptionsItem label="处置动作" class="whitespace-pre-wrap">{{ current.handlingAction ?? '（未填写）' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="证据说明" class="whitespace-pre-wrap">{{ current.evidenceDesc ?? '（未填写）' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="原因结论" class="whitespace-pre-wrap">
            <span :class="current.rootCause ? '' : 'text-gray-400'">{{ current.rootCause ?? '（未填写，封存前必填）' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="纠正措施" class="whitespace-pre-wrap">
            <span :class="current.correctiveAction ? '' : 'text-gray-400'">{{ current.correctiveAction ?? '（未填写，封存前必填）' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="整改负责人">
            <span :class="current.ownerName ? '' : 'text-gray-400'">{{ current.ownerName ?? '（未指定，封存前必填）' }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="整改期限">
            <span :class="current.dueDate ? '' : 'text-gray-400'">{{ current.dueDate ?? '（未填写，封存前必填）' }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <!-- 事发快照（冻结） vs 当前资料 -->
        <ElCard shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span>事发时快照（建立时冻结，永不变化）</span>
              <div class="flex gap-2">
                <ElTag v-if="current.routeChanged" type="danger">航线资料已有差异</ElTag>
                <ElTag v-if="current.staffChanged" type="danger">人员档案已有差异</ElTag>
                <ElTag v-if="!current.routeChanged && !current.staffChanged" type="success">关联资料暂无变化</ElTag>
              </div>
            </div>
          </template>

          <table class="w-full text-xs border-collapse mb-2">
            <thead>
              <tr class="bg-gray-100">
                <th class="border p-2 w-24 text-left">对象</th>
                <th class="border p-2 text-left">事发时（冻结）</th>
                <th class="border p-2 text-left">当前资料（实时）</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td class="border p-2 font-medium">航线</td>
                <td class="border p-2">
                  {{ current.snapshotRouteCode }} {{ current.snapshotRouteName }}
                  <ElTag size="small" type="warning" class="ml-1">{{ current.snapshotRouteWindLevel }}</ElTag>
                  <span class="text-gray-400">（{{ current.snapshotRouteGroup }}）</span>
                </td>
                <td class="border p-2">
                  <template v-if="current.currentRouteExists">
                    <router-link class="text-blue-600 underline" :to="`/route`">
                      {{ current.currentRouteCode }} {{ current.currentRouteName }}
                    </router-link>
                    <ElTag size="small" type="warning" class="ml-1">{{ current.currentRouteWindLevel }}</ElTag>
                    <ElTag size="small" :type="current.currentRouteStatus === '启用' ? 'success' : 'danger'" class="ml-1">{{ current.currentRouteStatus }}</ElTag>
                  </template>
                  <span v-else class="text-red-600">航线档案已删除</span>
                </td>
              </tr>
              <tr v-if="current.watchId">
                <td class="border p-2 font-medium">值守人员</td>
                <td class="border p-2">
                  飞行日 {{ current.snapshotWatchFlightDate }}，起飞 {{ current.snapshotWatchTakeoff }}<br />
                  操作员：{{ current.snapshotOperatorName }}｜复核员：{{ current.snapshotReviewerName }}
                </td>
                <td class="border p-2">
                  <template v-if="current.currentWatchExists">
                    <router-link class="text-blue-600 underline" :to="`/watch`">值守状态：{{ current.currentWatchStatus }}</router-link><br />
                    操作员：{{ current.currentOperatorName }}｜复核员：{{ current.currentReviewerName }}
                  </template>
                  <span v-else class="text-red-600">值守记录已删除</span>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="text-sm font-medium mb-1">涉及锚点（{{ current.anchors.length }}）</div>
          <table class="w-full text-xs border-collapse">
            <thead>
              <tr class="bg-gray-100">
                <th class="border p-2 text-left">锚点</th>
                <th class="border p-2 text-left">事发状态/区域/承重</th>
                <th class="border p-2 text-left">当前资料（可跳转）</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in current.anchors" :key="a.anchorId">
                <td class="border p-2">
                  {{ a.anchorCode }}
                  <router-link class="text-blue-600 underline ml-1" to="/anchor">查看当前</router-link>
                  <div class="text-gray-400">{{ a.locationDesc }}</div>
                </td>
                <td class="border p-2">
                  <ElTag size="small" :type="a.statusSnapshot === '启用' ? 'success' : 'danger'">{{ a.statusSnapshot }}</ElTag>
                  <ElTag size="small" type="warning" class="ml-1">{{ a.anchorZone ?? '未分区' }}</ElTag>
                  <span class="ml-1">{{ a.maxWeight }}kg</span>
                </td>
                <td class="border p-2">
                  <template v-if="a.currentExists">
                    <ElTag size="small" :type="a.currentStatus === '启用' ? 'success' : 'danger'">{{ a.currentStatus }}</ElTag>
                    <ElTag size="small" type="warning" class="ml-1">{{ a.currentAnchorZone ?? '未分区' }}</ElTag>
                    <span class="ml-1">{{ a.currentMaxWeight }}kg</span>
                    <ElTag v-if="a.changed" size="small" type="danger" class="ml-1">与事发时不同</ElTag>
                  </template>
                  <span v-else class="text-red-600">锚点档案已删除</span>
                </td>
              </tr>
              <tr v-if="!current.anchors.length">
                <td colspan="3" class="border p-2 text-gray-400">建立时未关联锚点</td>
              </tr>
            </tbody>
          </table>
        </ElCard>

        <!-- 封存信息 -->
        <ElAlert v-if="current.status === 'SEALED'" type="success" :closable="false"
                 :title="`最近封存：${current.lastSealTime?.replace('T', ' ')} 由 ${current.sealedByName} 执行（第 ${current.sealedCount} 次封存）。正文与原证据已锁定，更正只能产生带理由的新修订。`" />

        <!-- 操作区（按钮显隐以后端 canXxx 为准；后端独立强制，直接请求同样拦截） -->
        <div class="flex flex-wrap gap-2">
          <ElButton v-if="current.canStartInvestigation" type="primary" @click="startInvestigation">开始调查</ElButton>
          <ElButton v-if="current.canEdit" @click="editOpen = true">
            {{ current.status === 'DRAFT' ? '补充草稿' : '修订正文' }}
          </ElButton>
          <ElButton v-if="current.canSubmitSeal" type="warning" @click="submitSeal">
            提交封存
          </ElButton>
          <ElButton v-if="current.canBackToInvestigating" @click="backInvestigating">退回调查</ElButton>
          <ElButton v-if="current.canSeal" type="success" @click="seal">封存（安全主管）</ElButton>
          <ElButton v-if="current.canReopen" type="danger" plain @click="reopen">重新开启（安全主管）</ElButton>
          <ElButton v-if="current.canCorrect" type="warning" plain @click="editOpen = true">封存后更正</ElButton>
          <ElButton @click="openRevisions">修订记录（{{ current.version }} 版）</ElButton>
        </div>

        <ElAlert v-if="current.status === 'PENDING_SEAL' && current.sealBlockReason" type="warning" :closable="false"
                 :title="current.sealBlockReason" />
        <div class="text-xs text-gray-400">
          状态链：草稿 → 调查中 → 待封存 → 已封存；已封存可由安全主管写明依据重新开启，再次封存后修订记录能完整呈现两次封存之间发生的事情。
          所有按钮的显隐只是提示，封存/重新开启/越权访问在服务端强制执行，直接调用后端接口同样被拒。
        </div>
      </div>
    </ElDrawer>
  </div>
</template>
