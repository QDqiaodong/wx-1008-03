<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElButton, ElTable, ElTableColumn, ElDialog, ElForm, ElFormItem, ElSelect, ElOption,
  ElMessage, ElTag, ElCard, ElDatePicker, ElMessageBox, ElAlert, ElDescriptions,
  ElDescriptionsItem, ElDrawer, ElInput, ElTimeline, ElTimelineItem,
  ElRadioGroup, ElRadio, ElCollapse, ElCollapseItem, ElBadge
} from 'element-plus'
import {
  incidentApi, watchApi, staffApi, routeApi, adaptApi, anchorApi,
  type IncidentListItem, type IncidentView, type IncidentConflict, type IncidentContent,
  type IncidentCreateDTO, type IncidentEditDTO, type IncidentRevisionView, type ApiError,
  type GroundStaff, type FlightRoute, type WatchView
} from '../api'

const incidents = ref<IncidentListItem[]>([])
const routes = ref<FlightRoute[]>([])
const staff = ref<GroundStaff[]>([])
const watches = ref<WatchView[]>([])

const createDialog = ref(false)
const submitting = ref(false)

/* ---------------- 建档表单 ---------------- */
const createForm = ref<IncidentCreateDTO>({
  title: '', severity: 'MINOR', foundTime: '',
  routeId: 0, watchId: null, anchorIds: [], involvedStaffIds: [],
  sceneNarrative: '', handlingActions: '', evidenceNote: '',
  causeConclusion: '', correctiveAction: '', ownerId: null, dueDate: null
})
const foundDate = ref<Date | null>(null)
const dueDateVal = ref<string>('')

const activeAnchors = ref<{ id: number; anchorCode: string; anchorZone?: string; locationDesc?: string }[]>([])

async function openCreate() {
  createForm.value = {
    title: '', severity: 'MINOR', foundTime: '',
    routeId: routes.value[0]?.id ?? 0, watchId: null, anchorIds: [], involvedStaffIds: [],
    sceneNarrative: '', handlingActions: '', evidenceNote: '',
    causeConclusion: '', correctiveAction: '', ownerId: null, dueDate: null
  }
  foundDate.value = new Date()
  dueDateVal.value = ''
  activeAnchors.value = []
  await onRouteChange(createForm.value.routeId)
  createDialog.value = true
}

async function onRouteChange(routeId: number) {
  createForm.value.watchId = null
  if (!routeId) {
    watches.value = []
    activeAnchors.value = []
    return
  }
  watches.value = await watchApi.list(routeId)
  const bound = await adaptApi.bound(routeId)
  const all = await anchorApi.list()
  const boundIds = new Set(bound.filter(b => b.status === 1).map(b => b.anchorId))
  activeAnchors.value = all
    .filter(a => boundIds.has(a.id))
    .map(a => ({ id: a.id, anchorCode: a.anchorCode, anchorZone: a.anchorZone, locationDesc: a.locationDesc }))
  createForm.value.anchorIds = []
}

function onWatchChange(watchId: number | null) {
  // 选中值守后，值守操作员/复核员自动并入涉及人员（后端同样会取并集）
  if (watchId == null) return
  const w = watches.value.find(x => x.id === watchId)
  if (!w) return
  const set = new Set(createForm.value.involvedStaffIds ?? [])
  set.add(w.operatorId)
  set.add(w.reviewerId)
  createForm.value.involvedStaffIds = [...set]
}

function pad(n: number) { return String(n).padStart(2, '0') }
function isoDateTime(d: Date | null): string {
  if (!d) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}
function isoDate(d: Date | null): string {
  if (!d) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function submitCreate() {
  const f = createForm.value
  if (!f.title.trim()) return ElMessage.error('请填写事件标题')
  if (!f.routeId) return ElMessage.error('请选择关联航线')
  if (!foundDate.value) return ElMessage.error('请选择发现时间')
  f.foundTime = isoDateTime(foundDate.value)
  f.dueDate = dueDateVal.value || null
  submitting.value = true
  try {
    const view = await incidentApi.create(f)
    ElMessage.success(`事件已建立（草稿）：${view.incidentCode}，关联对象快照已冻结`)
    createDialog.value = false
    await load()
    await openDetail(view.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

/* ---------------- 列表 ---------------- */
async function load() {
  incidents.value = await incidentApi.list()
}

const statusType = (s: string): any =>
  ({ DRAFT: 'info', INVESTIGATING: 'warning', PENDING_SEAL: 'primary', SEALED: 'success', REOPENED: 'danger' } as Record<string, string>)[s] ?? 'info'
const severityType = (s: string): any =>
  ({ MINOR: 'info', MAJOR: 'warning', CRITICAL: 'danger' } as Record<string, string>)[s] ?? 'info'

/* ---------------- 详情抽屉 ---------------- */
const detailVisible = ref(false)
const detail = ref<IncidentView | null>(null)

async function openDetail(id: number) {
  detail.value = await incidentApi.get(id)
  editForm.value = toEditForm(detail.value)
  editBaseVersion.value = detail.value.version
  detailVisible.value = true
}

async function refreshDetail() {
  if (!detail.value) return
  detail.value = await incidentApi.get(detail.value.id)
}

/* ---------------- 正文编辑（整份版本冲突口径） ---------------- */
const editDialog = ref(false)
const editForm = ref<IncidentContent | null>(null)
/** 打开编辑时确认的基准版本；冲突后用最新版本回填 */
const editBaseVersion = ref(0)
const editFoundDate = ref<Date | null>(null)
const editDueDate = ref<Date | null>(null)
const editReason = ref('')

const isReopenCorrect = computed(() => detail.value?.status === 'REOPENED')

function toEditForm(v: IncidentView): IncidentContent {
  return {
    title: v.title,
    severity: v.severity,
    foundTime: v.foundTime,
    sceneNarrative: v.sceneNarrative ?? '',
    handlingActions: v.handlingActions ?? '',
    evidenceNote: v.evidenceNote ?? '',
    causeConclusion: v.causeConclusion ?? '',
    correctiveAction: v.correctiveAction ?? '',
    ownerId: v.ownerId,
    dueDate: v.dueDate
  }
}

function openEdit() {
  if (!detail.value) return
  editForm.value = toEditForm(detail.value)
  editBaseVersion.value = detail.value.version
  editFoundDate.value = new Date(detail.value.foundTime)
  editDueDate.value = detail.value.dueDate ? new Date(detail.value.dueDate) : null
  editReason.value = ''
  editDialog.value = true
}

async function submitEdit() {
  if (!detail.value || !editForm.value) return
  if (!editForm.value.title.trim()) return ElMessage.error('标题不能为空')
  editForm.value.foundTime = isoDateTime(editFoundDate.value)
  editForm.value.dueDate = editDueDate.value ? isoDate(editDueDate.value) : null
  if (isReopenCorrect.value && !editReason.value.trim()) {
    return ElMessage.error('封存后更正必须填写更正理由')
  }
  const dto: IncidentEditDTO = {
    expectedVersion: editBaseVersion.value,
    reason: editReason.value || undefined,
    content: editForm.value
  }
  try {
    const view = await incidentApi.edit(detail.value.id, dto)
    detail.value = view
    ElMessage.success(`修订已保存（新版本 v${view.version}），旧版本在修订记录中完整保留`)
    editDialog.value = false
    await load()
  } catch (e) {
    await handleConflict(e as ApiError)
  }
}

/* ---------------- 状态流转 ---------------- */
async function transition(action: 'enter' | 'request' | 'back' | 'seal' | 'reopen') {
  if (!detail.value) return
  const v = detail.value
  const needBasis = action === 'reopen'
  let basis = ''
  try {
    if (needBasis) {
      const { value } = await ElMessageBox.prompt(
        '重新开启必须写清依据（新证据、认定错误、整改需要等）。该依据会永久保留在修订记录与封存轮次中。',
        `重新开启事件 ${v.incidentCode}（当前 v${v.version}）`,
        { confirmButtonText: '确认重新开启', cancelButtonText: '取消', inputType: 'textarea',
          inputPlaceholder: '重新开启依据（必填）' })
      basis = value
    } else if (action === 'seal') {
      await ElMessageBox.confirm(
        `确认由安全主管封存事件「${v.title}」？封存后正文与证据不可覆盖，只能通过重新开启形成带理由的更正。`,
        `封存（第 ${v.currentSealRound + 1} 轮，v${v.version}）`,
        { confirmButtonText: '确认封存', cancelButtonText: '取消', type: 'warning' })
    }
    const payload = { expectedVersion: v.version, basis: basis || undefined }
    let view: IncidentView
    if (action === 'enter') view = await incidentApi.enterInvestigating(v.id, payload)
    else if (action === 'request') view = await incidentApi.requestSeal(v.id, payload)
    else if (action === 'back') view = await incidentApi.backInvestigating(v.id, payload)
    else if (action === 'seal') view = await incidentApi.seal(v.id, payload)
    else view = await incidentApi.reopen(v.id, payload)
    detail.value = view
    ElMessage.success(`操作成功，当前状态「${view.statusLabel}」v${view.version}`)
    await load()
  } catch (e) {
    if (e === 'cancel') return
    await handleConflict(e as ApiError)
  }
}

/* ---------------- 整份版本冲突处理 ---------------- */
const conflictDialog = ref(false)
const conflict = ref<IncidentConflict | null>(null)
/** 冲突后选择“基于最新内容重新编辑”时回到编辑弹窗 */
async function handleConflict(e: ApiError) {
  if (e?.status === 409 && e.conflict) {
    conflict.value = e.conflict
    conflictDialog.value = true
    // 详情抽屉立即同步到最新版本（不丢第一次修改：第一次修改已在服务端成为新版本）
    detail.value = e.conflict.latest
    await load()
  } else {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function conflictDiscard() {
  conflictDialog.value = false
  editDialog.value = false
  ElMessage.info('已放弃你的修改，页面展示的是最新版本')
}

async function conflictReedit() {
  if (!conflict.value) return
  conflictDialog.value = false
  // 以最新内容为基准重新打开编辑；基准版本更新为 latestVersion
  editForm.value = toEditForm(conflict.value.latest)
  editBaseVersion.value = conflict.value.latestVersion
  editFoundDate.value = new Date(conflict.value.latest.foundTime)
  editDueDate.value = conflict.value.latest.dueDate ? new Date(conflict.value.latest.dueDate) : null
  editReason.value = ''
  editDialog.value = true
  ElMessage.warning(`请基于最新 v${conflict.value.latestVersion} 重新核对后再提交`)
}

/* ---------------- 修订记录与并排对比 ---------------- */
const compareDialog = ref(false)
const compareLeft = ref<IncidentRevisionView | null>(null)
const compareRight = ref<IncidentRevisionView | null>(null)
const compareLeftSeq = ref(0)
const compareRightSeq = ref(0)

function openCompare(list: IncidentRevisionView[], rightSeq: number) {
  const leftSeq = Math.max(0, rightSeq - 1)
  compareLeftSeq.value = leftSeq
  compareRightSeq.value = rightSeq
  compareLeft.value = list.find(r => r.seq === leftSeq) ?? null
  compareRight.value = list.find(r => r.seq === rightSeq) ?? null
  compareDialog.value = true
}

function onComparePick() {
  if (!detail.value) return
  const list = detail.value.revisions
  compareLeft.value = list.find(r => r.seq === compareLeftSeq.value) ?? null
  compareRight.value = list.find(r => r.seq === compareRightSeq.value) ?? null
}

function parseContent(json: string | null): Partial<IncidentContent> {
  if (!json) return {}
  try { return JSON.parse(json) as IncidentContent } catch { return {} }
}

const compareRows = computed(() => {
  const l = parseContent(compareLeft.value?.afterContent ?? compareLeft.value?.beforeContent ?? null)
  const r = parseContent(compareRight.value?.afterContent ?? null)
  const rows: { label: string; key: keyof IncidentContent; kind?: 'text' }[] = [
    { label: '标题', key: 'title' },
    { label: '严重级别', key: 'severity' },
    { label: '发现时间', key: 'foundTime' },
    { label: '现场经过', key: 'sceneNarrative' },
    { label: '处置动作', key: 'handlingActions' },
    { label: '证据说明', key: 'evidenceNote' },
    { label: '原因结论', key: 'causeConclusion' },
    { label: '纠正措施', key: 'correctiveAction' },
    { label: '负责人ID', key: 'ownerId' },
    { label: '整改期限', key: 'dueDate' }
  ]
  return rows.map(row => {
    const lv = fmtVal(l[row.key])
    const rv = fmtVal(r[row.key])
    return { ...row, left: lv, right: rv, changed: lv !== rv }
  })
})

function fmtVal(v: unknown): string {
  if (v === null || v === undefined || v === '') return '（空）'
  return String(v)
}

function fmtTime(s: string | null | undefined): string {
  return s ? s.replace('T', ' ').slice(0, 16) : '—'
}

onMounted(async () => {
  const [rs, ss] = await Promise.all([routeApi.list(), staffApi.active()])
  routes.value = rs
  staff.value = ss
  await load()
})
</script>

<template>
  <div class="space-y-4">
    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <div>
            <span class="text-base font-semibold">飞行异常事件复盘</span>
            <span class="ml-3 text-xs text-gray-500">
              普通值班员只能看到自己报告或参与的事件；安全主管可查看全部并执行封存/重新开启（服务端强制）
            </span>
          </div>
          <div class="flex gap-2">
            <ElButton @click="load">刷新</ElButton>
            <ElButton type="primary" @click="openCreate">建立异常事件</ElButton>
          </div>
        </div>
      </template>

      <ElAlert type="info" :closable="false" class="mb-3"
        title="并发口径：整份事件版本冲突（非按字段合并）"
        description="每次保存/流转都要带着打开时的版本号确认；别人先改后，你的整份提交会被拒绝并显示冲突字段与最新版本，不会静默合并或覆盖。" />

      <ElTable :data="incidents" stripe @row-click="(r: IncidentListItem) => openDetail(r.id)">
        <ElTableColumn label="事件" min-width="200">
          <template #default="{ row }: { row: any }">
            <div class="font-medium">{{ row.title }}</div>
            <div class="text-xs text-gray-500 font-mono">{{ row.incidentCode }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态 / 版本" width="150">
          <template #default="{ row }: { row: any }">
            <ElTag :type="statusType(row.status)">{{ row.statusLabel }}</ElTag>
            <div class="text-xs text-gray-400 mt-1">v{{ row.version }} ｜ 修订 {{ row.revisionCount }} 条</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="严重级别" width="90">
          <template #default="{ row }: { row: any }">
            <ElTag :type="severityType(row.severity)">{{ row.severityLabel }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发现时间" width="140">
          <template #default="{ row }: { row: any }">{{ fmtTime(row.foundTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="事发航线（快照）" min-width="170">
          <template #default="{ row }: { row: any }">
            <div>{{ row.snapRouteCode }} {{ row.snapRouteName }}</div>
            <div class="text-xs text-gray-500">事发风级：{{ row.snapRouteWindLevel ?? '—' }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="报告人 / 涉及人员" min-width="160">
          <template #default="{ row }: { row: any }">
            <div>报告：{{ row.reporterName }}</div>
            <div class="text-xs text-gray-500">{{ row.involvedStaffNames || '无其他涉及人员' }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="封存轮次" width="90">
          <template #default="{ row }: { row: any }">
            <ElBadge v-if="row.currentSealRound > 0" :value="`第${row.currentSealRound}轮`" type="success" />
            <span v-else class="text-xs text-gray-400">未封存</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="90">
          <template #default="{ row }: { row: any }">
            <ElButton size="small" @click.stop="openDetail(row.id)">详情</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <!-- ========== 建档对话框 ========== -->
    <ElDialog v-model="createDialog" title="建立飞行异常事件（关联对象建档时冻结快照）" width="760px">
      <ElAlert type="warning" :closable="false" class="mb-3"
        title="航线、值守人员与锚点的关键名称/风级/状态在建档时复制为事发快照；此后修改基础资料，历史事件仍显示事发时内容。" />
      <ElForm :model="createForm" label-width="110px">
        <ElFormItem label="事件标题" required>
          <ElInput v-model="createForm.title" maxlength="200" placeholder="如：R-GALE 侧风超标迫降未遂" />
        </ElFormItem>
        <ElFormItem label="严重级别" required>
          <ElRadioGroup v-model="createForm.severity">
            <ElRadio value="MINOR">一般</ElRadio>
            <ElRadio value="MAJOR">较大</ElRadio>
            <ElRadio value="CRITICAL">重大</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem label="发现时间" required>
          <ElDatePicker v-model="foundDate" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
        </ElFormItem>
        <ElFormItem label="关联航线" required>
          <ElSelect :model-value="createForm.routeId" style="width: 100%"
                    @change="(v: number) => onRouteChange(v)">
            <ElOption v-for="r in routes" :key="r.id"
                      :label="`${r.routeCode} ${r.routeName}（当前${r.windLevel} ${r.windSpeed}m/s）`" :value="r.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="关联值守安排">
          <ElSelect v-model="createForm.watchId" clearable style="width: 100%" placeholder="选择当时的开航值守（可空）"
                    @change="(v: number | null) => onWatchChange(v)">
            <ElOption v-for="w in watches" :key="w.id"
                      :label="`${w.flightDate} ${w.statusLabel}｜操作${w.operatorName}/复核${w.reviewerName}`" :value="w.id" />
          </ElSelect>
          <div class="text-xs text-gray-400">选中值守后，其操作员与复核员自动加入涉及人员</div>
        </ElFormItem>
        <ElFormItem label="涉及锚点">
          <ElSelect v-model="createForm.anchorIds" multiple collapse-tags style="width: 100%"
                    placeholder="该航线在用锚点，可多选；建档时冻结锚点状态/承重/风区">
            <ElOption v-for="a in activeAnchors" :key="a.id"
                      :label="`${a.anchorCode}（${a.anchorZone || '未分区'}）`" :value="a.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="涉及人员">
          <ElSelect v-model="createForm.involvedStaffIds" multiple collapse-tags style="width: 100%"
                    placeholder="现场涉及的其他值班员（可多选）">
            <ElOption v-for="s in staff" :key="s.id"
                      :label="`${s.staffName}（${s.staffCode}）`" :value="s.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="现场经过">
          <ElInput v-model="createForm.sceneNarrative" type="textarea" :rows="3" />
        </ElFormItem>
        <ElFormItem label="处置动作">
          <ElInput v-model="createForm.handlingActions" type="textarea" :rows="2" />
        </ElFormItem>
        <ElFormItem label="证据说明">
          <ElInput v-model="createForm.evidenceNote" type="textarea" :rows="2"
                   placeholder="照片/视频编号、记录仪片段、见证人等（封存后不可覆盖）" />
        </ElFormItem>
        <ElFormItem label="原因结论">
          <ElInput v-model="createForm.causeConclusion" type="textarea" :rows="2" placeholder="草稿可后补，封存前必填" />
        </ElFormItem>
        <ElFormItem label="纠正措施">
          <ElInput v-model="createForm.correctiveAction" type="textarea" :rows="2" placeholder="封存前必填" />
        </ElFormItem>
        <ElFormItem label="负责人">
          <ElSelect v-model="createForm.ownerId" clearable style="width: 100%" placeholder="整改负责人（封存前必填）">
            <ElOption v-for="s in staff" :key="s.id" :label="`${s.staffName}（${s.staffCode}）`" :value="s.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="整改期限">
          <ElDatePicker v-model="dueDateVal" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD"
                        placeholder="封存前必填" style="width: 100%" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createDialog = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitCreate">建立事件（草稿）</ElButton>
      </template>
    </ElDialog>

    <!-- ========== 详情抽屉 ========== -->
    <ElDrawer v-model="detailVisible" :title="detail ? `${detail.incidentCode} 异常事件详情` : ''" size="72%">
      <div v-if="detail" class="px-2 space-y-4">
        <!-- 头部：状态、版本、操作 -->
        <ElCard shadow="never">
          <div class="flex items-start justify-between">
            <div>
              <div class="text-lg font-semibold">{{ detail.title }}</div>
              <div class="mt-2 flex flex-wrap items-center gap-2">
                <ElTag :type="statusType(detail.status)" size="large">{{ detail.statusLabel }}</ElTag>
                <ElTag :type="severityType(detail.severity)">{{ detail.severityLabel }}</ElTag>
                <ElTag type="info">版本 v{{ detail.version }}</ElTag>
                <ElTag v-if="detail.currentSealRound > 0" type="success">第 {{ detail.currentSealRound }} 轮封存</ElTag>
                <span class="text-xs text-gray-400">发现时间 {{ fmtTime(detail.foundTime) }}</span>
              </div>
            </div>
            <div class="flex flex-wrap gap-2 justify-end max-w-[50%]">
              <ElButton v-if="detail.canEditContent" type="primary" plain @click="openEdit">
                {{ detail.status === 'REOPENED' ? '封存后更正（需理由）' : (detail.status === 'DRAFT' ? '补充 / 修订正文' : '修订正文') }}
              </ElButton>
              <ElButton v-if="detail.canEnterInvestigating" type="warning" @click="transition('enter')">进入调查</ElButton>
              <ElButton v-if="detail.canRequestSeal" type="primary" @click="transition('request')">
                {{ detail.status === 'REOPENED' ? '更正后提交封存' : '提交待封存' }}
              </ElButton>
              <ElButton v-if="detail.canBackToInvestigating" @click="transition('back')">退回调查</ElButton>
              <ElButton v-if="detail.canSeal" type="success" @click="transition('seal')">安全主管封存</ElButton>
              <ElButton v-if="detail.canReopen" type="danger" plain @click="transition('reopen')">重新开启</ElButton>
              <ElButton size="small" @click="refreshDetail()">刷新</ElButton>
            </div>
          </div>
          <ElAlert v-if="detail.sealed" type="success" :closable="false" class="mt-3"
            title="事件已封存：以下正文与证据为封存版本，不可直接覆盖。需要更正时，须由安全主管重新开启，再提交带理由的更正修订（旧版保留）。" />
          <ElAlert v-if="detail.status === 'PENDING_SEAL'" type="info" :closable="false" class="mt-3"
            title="待封存：原因结论、纠正措施、负责人、期限已具备，等待安全主管封存。封存前如需补查可退回调查。" />
        </ElCard>

        <!-- 正文 -->
        <ElCard shadow="never">
          <template #header><span class="font-semibold">事件正文（当前 v{{ detail.version }}）</span></template>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="现场经过" :span="2">{{ detail.sceneNarrative || '（未填写）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="处置动作" :span="2">{{ detail.handlingActions || '（未填写）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="证据说明" :span="2">{{ detail.evidenceNote || '（未填写）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="原因结论" :span="2">{{ detail.causeConclusion || '（未填写，封存前必填）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="纠正措施" :span="2">{{ detail.correctiveAction || '（未填写，封存前必填）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="负责人">{{ detail.ownerName || '（未指定）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="整改期限">{{ detail.dueDate || '（未指定）' }}</ElDescriptionsItem>
          </ElDescriptions>
        </ElCard>

        <!-- 事发快照 + 当前对比 -->
        <ElCard shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-semibold">事发快照（建档时冻结，不随后续资料修改变化）</span>
              <span class="text-xs text-gray-400">右侧“当前”实时值仅用于对比差异</span>
            </div>
          </template>

          <div class="mb-3">
            <div class="text-sm font-medium mb-1">航线</div>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="事发时（快照）">
                <span class="font-mono">{{ detail.snapRouteCode }}</span> {{ detail.snapRouteName }}
                ｜风级 <b>{{ detail.snapRouteWindLevel || '—' }}</b>
                ｜{{ detail.snapRouteWindSpeed }} m/s
              </ElDescriptionsItem>
              <ElDescriptionsItem label="当前资料">
                <template v-if="detail.currentRouteExists">
                  <span class="font-mono">{{ detail.currentRouteCode }}</span> {{ detail.currentRouteName }}
                  ｜风级 {{ detail.currentRouteWindLevel || '—' }}｜{{ detail.currentRouteWindSpeed }} m/s
                  ｜{{ detail.currentRouteStatus === 1 ? '启用' : '停用' }}
                  <ElTag v-for="d in detail.routeDiffFields" :key="d" size="small" type="danger" class="ml-1">差异：{{ d }}</ElTag>
                </template>
                <ElTag type="danger" v-else>当前航线档案已删除</ElTag>
              </ElDescriptionsItem>
            </ElDescriptions>
          </div>

          <div v-if="detail.watchId" class="mb-3">
            <div class="text-sm font-medium mb-1">开航值守（id={{ detail.watchId }}）</div>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="事发时（快照）">
                起飞 {{ fmtTime(detail.snapWatchTakeoff) }} ～ {{ fmtTime(detail.snapWatchEnd) }}<br />
                操作员：{{ detail.snapWatchOperatorName }}<br />
                复核员：{{ detail.snapWatchReviewerName }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="当前资料">
                <template v-if="detail.currentWatchExists">
                  状态：{{ detail.currentWatchStatusLabel }}<br />
                  操作员：{{ detail.currentWatchOperatorName }}<br />
                  复核员：{{ detail.currentWatchReviewerName }}
                  <ElTag v-for="d in detail.watchDiffFields" :key="d" size="small" type="danger" class="ml-1">差异：{{ d }}</ElTag>
                </template>
                <ElTag type="danger" v-else>当前值守档案已删除</ElTag>
              </ElDescriptionsItem>
            </ElDescriptions>
          </div>

          <div>
            <div class="text-sm font-medium mb-1">涉及锚点（{{ detail.anchorSnapshots.length }}）</div>
            <ElTable :data="detail.anchorSnapshots" size="small" border>
              <ElTableColumn label="锚点编号" width="120">
                <template #default="{ row }: any">
                  <span class="font-mono">{{ row.anchorCode }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="事发快照（冻结）" min-width="260">
                <template #default="{ row }: any">
                  区域 {{ row.anchorZone || '—' }} ｜状态
                  <ElTag size="small" :type="row.anchorStatus === 1 ? 'success' : 'info'">{{ row.anchorStatusLabel }}</ElTag>
                  ｜承重 {{ row.maxWeight }}kg ｜风区 {{ row.minWindSpeed }}~{{ row.maxWindSpeed }}m/s
                  <div class="text-xs text-gray-400">{{ row.locationDesc }}</div>
                </template>
              </ElTableColumn>
              <ElTableColumn label="当前资料" min-width="220">
                <template #default="{ row }: any">
                  <template v-if="row.currentExists">
                    区域 {{ row.currentAnchorZone || '—' }} ｜状态
                    <ElTag size="small" :type="row.currentAnchorStatus === 1 ? 'success' : 'info'">{{ row.currentAnchorStatusLabel }}</ElTag>
                    ｜承重 {{ row.currentMaxWeight }}kg｜风区 {{ row.currentMinWindSpeed }}~{{ row.currentMaxWindSpeed }}
                    <div>
                      <ElTag v-for="d in row.diffFields" :key="d" size="small" type="danger" class="mr-1 mt-1">差异：{{ d }}</ElTag>
                      <span v-if="!row.diffFields.length" class="text-xs text-green-600">与事发时一致</span>
                    </div>
                  </template>
                  <ElTag type="danger" v-else>档案已删除（快照保留）</ElTag>
                </template>
              </ElTableColumn>
            </ElTable>
          </div>

          <div class="mt-3 text-sm">
            报告人：<b>{{ detail.reporterName }}</b> ｜ 涉及人员：{{ detail.involvedStaffNames || '无' }}
          </div>
        </ElCard>

        <!-- 封存轮次 -->
        <ElCard v-if="detail.sealRounds.length" shadow="never">
          <template #header><span class="font-semibold">封存轮次（两次封存之间的更正与依据）</span></template>
          <ElTimeline>
            <ElTimelineItem v-for="r in detail.sealRounds" :key="r.roundNo"
              :timestamp="`封存 ${fmtTime(r.sealTime)}（v${r.sealedVersion}） by ${r.sealedByName}`"
              :type="r.reopened ? 'primary' : 'success'">
              <div>第 {{ r.roundNo }} 轮封存</div>
              <div v-if="r.reopened" class="text-sm text-orange-600">
                已于 {{ fmtTime(r.reopenTime) }}（v{{ r.reopenVersion }}）由 {{ r.reopenedByName }} 重新开启。
                依据：{{ r.reopenBasis }}
              </div>
            </ElTimelineItem>
          </ElTimeline>
        </ElCard>

        <!-- 修订记录（不可变） -->
        <ElCard shadow="never">
          <template #header>
            <span class="font-semibold">修订记录（只追加、不可变，按顺序 v0 起）</span>
          </template>
          <ElTable :data="[...detail.revisions].reverse()" size="small" border>
            <ElTableColumn label="序" prop="seq" width="50" />
            <ElTableColumn label="类型" width="110">
              <template #default="{ row }: { row: any }">
                <ElTag size="small" :type="row.revisionType === 'SEAL' ? 'success' : row.revisionType === 'REOPEN' || row.revisionType === 'CORRECT' ? 'danger' : 'info'">
                  {{ row.revisionTypeLabel }}
                </ElTag>
                <div v-if="row.sealRound > 0" class="text-xs text-gray-400">第{{ row.sealRound }}轮</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态变化" width="150">
              <template #default="{ row }: { row: any }">
                {{ row.fromStatusLabel || '—' }} → <b>{{ row.toStatusLabel || '—' }}</b>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作者 / 时间" width="180">
              <template #default="{ row }: { row: any }">
                {{ row.operatorName }}（{{ row.operatorRoleLabel }}）
                <div class="text-xs text-gray-400">{{ fmtTime(row.operateTime) }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="改动字段 / 理由" min-width="220">
              <template #default="{ row }: { row: any }">
                <div v-if="row.changedFields.length" class="text-xs">
                  <ElTag v-for="f in row.changedFields" :key="f" size="small" class="mr-1 mb-1">{{ f }}</ElTag>
                </div>
                <div v-else class="text-xs text-gray-400">仅状态流转，正文未变</div>
                <div v-if="row.reason" class="text-xs mt-1">理由/依据：{{ row.reason }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="对比" width="100">
              <template #default="{ row }: { row: any }">
                <ElButton size="small" :disabled="row.seq === 0" @click="openCompare(detail!.revisions, row.seq)">
                  与上版并排
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElCard>

        <!-- 口径说明 -->
        <ElCollapse>
          <ElCollapseItem name="why" title="为什么采用整份事件版本冲突，而不采用按字段合并？">
            <div class="text-sm text-gray-600 leading-6">{{ detail.rejectedAlternative }}</div>
          </ElCollapseItem>
        </ElCollapse>
      </div>
    </ElDrawer>

    <!-- ========== 正文编辑对话框 ========== -->
    <ElDialog v-model="editDialog"
      :title="`修订正文（基于 v${editBaseVersion}）${isReopenCorrect ? '｜封存后更正，理由必填' : ''}`"
      width="720px">
      <ElAlert type="warning" :closable="false" class="mb-3"
        :title="`你正在基于 v${editBaseVersion} 整份提交。若期间别人已保存，系统会拒绝并提示冲突字段，不会覆盖对方内容。`" />
      <ElForm v-if="editForm" label-width="100px">
        <ElFormItem label="标题" required>
          <ElInput v-model="editForm.title" />
        </ElFormItem>
        <ElFormItem label="严重级别">
          <ElRadioGroup v-model="editForm.severity">
            <ElRadio value="MINOR">一般</ElRadio>
            <ElRadio value="MAJOR">较大</ElRadio>
            <ElRadio value="CRITICAL">重大</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem label="发现时间">
          <ElDatePicker v-model="editFoundDate" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
        </ElFormItem>
        <ElFormItem label="现场经过">
          <ElInput v-model="editForm.sceneNarrative" type="textarea" :rows="3" />
        </ElFormItem>
        <ElFormItem label="处置动作">
          <ElInput v-model="editForm.handlingActions" type="textarea" :rows="2" />
        </ElFormItem>
        <ElFormItem label="证据说明">
          <ElInput v-model="editForm.evidenceNote" type="textarea" :rows="2" />
        </ElFormItem>
        <ElFormItem label="原因结论">
          <ElInput v-model="editForm.causeConclusion" type="textarea" :rows="2" />
        </ElFormItem>
        <ElFormItem label="纠正措施">
          <ElInput v-model="editForm.correctiveAction" type="textarea" :rows="2" />
        </ElFormItem>
        <ElFormItem label="负责人">
          <ElSelect v-model="editForm.ownerId" clearable style="width: 100%">
            <ElOption v-for="s in staff" :key="s.id" :label="`${s.staffName}（${s.staffCode}）`" :value="s.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="整改期限">
          <ElDatePicker v-model="editDueDate" type="date" format="YYYY-MM-DD" style="width: 100%" />
        </ElFormItem>
        <ElFormItem v-if="isReopenCorrect" label="更正理由" required>
          <ElInput v-model="editReason" type="textarea" :rows="2"
                   placeholder="封存后的更正必须说明理由，将随修订永久保留" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editDialog = false">取消</ElButton>
        <ElButton type="primary" @click="submitEdit">确认修订（v{{ editBaseVersion }}）</ElButton>
      </template>
    </ElDialog>

    <!-- ========== 版本冲突对话框 ========== -->
    <ElDialog v-model="conflictDialog" title="整份版本冲突：该事件已被其他人更新" width="720px"
              :close-on-click-modal="false">
      <template v-if="conflict">
        <ElAlert type="error" :closable="false" class="mb-3"
          :title="`你的提交基于 v${conflict.expectedVersion}，服务端已是 v${conflict.latestVersion}（${conflict.latestStatusLabel}）。本次提交已整份拒绝，未写入任何字段。`" />
        <div class="mb-2 text-sm">冲突字段：
          <ElTag v-for="f in conflict.conflictFields" :key="f" type="danger" class="mr-1 mt-1">{{ f }}</ElTag>
        </div>
        <ElCard shadow="never" header="服务端最新版本（第一次修改已保留在此版本中）">
          <ElDescriptions :column="1" border size="small">
            <ElDescriptionsItem label="状态 / 版本">
              {{ conflict.latestStatusLabel }} v{{ conflict.latestVersion }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="标题">{{ conflict.latest.title }}</ElDescriptionsItem>
            <ElDescriptionsItem label="现场经过">{{ conflict.latest.sceneNarrative || '（空）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="处置动作">{{ conflict.latest.handlingActions || '（空）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="证据说明">{{ conflict.latest.evidenceNote || '（空）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="原因结论">{{ conflict.latest.causeConclusion || '（空）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="纠正措施">{{ conflict.latest.correctiveAction || '（空）' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="负责人 / 期限">
              {{ conflict.latest.ownerName || '（空）' }} ｜ {{ conflict.latest.dueDate || '（空）' }}
            </ElDescriptionsItem>
          </ElDescriptions>
        </ElCard>
      </template>
      <template #footer>
        <ElButton @click="conflictDiscard">放弃我的修改</ElButton>
        <ElButton type="primary" @click="conflictReedit">基于最新 v{{ conflict?.latestVersion }} 重新编辑</ElButton>
      </template>
    </ElDialog>

    <!-- ========== 版本并排对比 ========== -->
    <ElDialog v-model="compareDialog" title="修订版本并排对比（旧版不可变）" width="80%">
      <div v-if="detail" class="mb-3 flex items-center gap-3 text-sm">
        <span>左版（旧）：</span>
        <ElSelect v-model="compareLeftSeq" style="width: 220px" @change="onComparePick">
          <ElOption v-for="r in detail.revisions" :key="'l'+r.seq"
                    :label="`v${r.seq} ${r.revisionTypeLabel} ${fmtTime(r.operateTime)}`" :value="r.seq" />
        </ElSelect>
        <span>右版（新）：</span>
        <ElSelect v-model="compareRightSeq" style="width: 220px" @change="onComparePick">
          <ElOption v-for="r in detail.revisions" :key="'r'+r.seq"
                    :label="`v${r.seq} ${r.revisionTypeLabel} ${fmtTime(r.operateTime)}`" :value="r.seq" />
        </ElSelect>
      </div>
      <div v-if="compareLeft || compareRight" class="mb-3 text-xs text-gray-500">
        左：{{ compareLeft?.operatorName }}（{{ compareLeft?.operatorRoleLabel }}）{{ fmtTime(compareLeft?.operateTime) }}
        {{ compareLeft?.reason ? '｜理由：' + compareLeft.reason : '' }}
        <br />
        右：{{ compareRight?.operatorName }}（{{ compareRight?.operatorRoleLabel }}）{{ fmtTime(compareRight?.operateTime) }}
        {{ compareRight?.reason ? '｜理由：' + compareRight.reason : '' }}
      </div>
      <ElTable :data="compareRows" size="small" border>
        <ElTableColumn label="字段" prop="label" width="110" />
        <ElTableColumn label="左版内容" min-width="300">
          <template #default="{ row }: any">
            <div :class="row.changed ? 'bg-red-50 whitespace-pre-wrap' : 'whitespace-pre-wrap'">{{ row.left }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="右版内容" min-width="300">
          <template #default="{ row }: any">
            <div :class="row.changed ? 'bg-green-50 whitespace-pre-wrap' : 'whitespace-pre-wrap'">{{ row.right }}</div>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDialog>
  </div>
</template>
