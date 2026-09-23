<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElButton, ElTable, ElTableColumn, ElDialog, ElForm, ElFormItem, ElSelect, ElOption,
  ElMessage, ElTag, ElCard, ElDatePicker, ElMessageBox, ElAlert, ElDescriptions,
  ElDescriptionsItem, ElDrawer, ElEmpty
} from 'element-plus'
import {
  watchApi, staffApi, groundMetaApi, routeApi,
  type WatchView, type GroundStaff, type GroundMeta, type FlightRoute, type WatchUpsertDTO
} from '../api'
import PolicyNotice from '../components/PolicyNotice.vue'
import QualBlock from '../components/QualBlock.vue'

const watches = ref<WatchView[]>([])
const staff = ref<GroundStaff[]>([])
const routes = ref<FlightRoute[]>([])
const meta = ref<GroundMeta | null>(null)
const routeFilter = ref<number | null>(null)

const createDialog = ref(false)
const editingId = ref<number | null>(null)
const form = ref<WatchUpsertDTO>({
  routeId: 0,
  operatorId: 0,
  reviewerId: 0,
  plannedTakeoff: '',
  plannedEnd: ''
})
const takeoffDate = ref<Date | null>(null)
const endDate = ref<Date | null>(null)

const detailDrawer = ref(false)
const current = ref<WatchView | null>(null)

function pad(n: number) { return String(n).padStart(2, '0') }
function iso(d: Date | null, endOfDay = false): string {
  if (!d) return ''
  const date = new Date(d)
  if (endOfDay && date.getHours() === 0 && date.getMinutes() === 0) {
    date.setHours(23, 59, 0, 0)
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

async function load() {
  watches.value = await watchApi.list(routeFilter.value ?? undefined)
  if (current.value) {
    const fresh = watches.value.find(w => w.id === current.value?.id)
    if (fresh) current.value = fresh
  }
}

onMounted(async () => {
  const [m, ss, rs] = await Promise.all([groundMetaApi.get(), staffApi.active(), routeApi.list()])
  meta.value = m
  staff.value = ss
  routes.value = rs.filter(r => r.status === 1)
  await load()
})

async function onFilter() {
  await load()
}

const statusType = (s: string): any =>
  ({ DRAFT: 'info', PENDING_REVIEW: 'warning', READY: 'success', CANCELLED: 'danger' } as Record<string, string>)[s]

function openCreate() {
  editingId.value = null
  const d = new Date()
  d.setDate(d.getDate() + 1); d.setHours(9, 0, 0, 0)
  takeoffDate.value = d
  const e = new Date(d); e.setHours(11, 0, 0, 0)
  endDate.value = e
  form.value = { routeId: routes.value[0]?.id ?? 0, operatorId: 0, reviewerId: 0, plannedTakeoff: iso(d), plannedEnd: iso(e) }
  createDialog.value = true
}

function openEdit(w: WatchView) {
  editingId.value = w.id
  takeoffDate.value = new Date(w.plannedTakeoff)
  endDate.value = w.plannedEnd ? new Date(w.plannedEnd) : null
  form.value = {
    routeId: w.routeId,
    operatorId: w.operatorId,
    reviewerId: w.reviewerId,
    plannedTakeoff: w.plannedTakeoff,
    plannedEnd: w.plannedEnd
  }
  createDialog.value = true
}

async function submitWatch() {
  if (form.value.operatorId === form.value.reviewerId) {
    ElMessage.error('操作员与复核员必须是不同人员')
    return
  }
  const dto: WatchUpsertDTO = {
    ...form.value,
    plannedTakeoff: iso(takeoffDate.value),
    plannedEnd: endDate.value ? iso(endDate.value) : null
  }
  try {
    if (editingId.value) {
      await watchApi.update(editingId.value, dto)
      ElMessage.success('值守已改派')
    } else {
      await watchApi.create(dto)
      ElMessage.success('值守已创建（草拟）')
    }
    createDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

async function refreshOne(id: number) {
  current.value = await watchApi.get(id)
  await load()
}

async function arrive(w: WatchView) {
  try {
    await watchApi.operatorArrive(w.id)
    ElMessage.success('已确认现场到位，进入待复核')
    await refreshOne(current.value?.id === w.id ? w.id : w.id)
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

async function withdraw(w: WatchView) {
  try {
    await watchApi.operatorWithdraw(w.id)
    ElMessage.success('已撤回到位，回到草拟')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

async function ready(w: WatchView) {
  try {
    await watchApi.ready(w.id)
    ElMessage.success('已确认就绪，证书快照已冻结')
    await refreshOne(w.id)
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

async function cancelWatch(w: WatchView) {
  try {
    const { value } = await ElMessageBox.prompt(
      w.status === 'READY'
        ? '该值守已就绪。取消已就绪值守仅安全主管可执行，确认继续？'
        : '确认取消该未就绪值守？',
      '取消值守',
      { confirmButtonText: '确认取消', cancelButtonText: '返回', type: 'warning', inputPlaceholder: '取消原因' }
    )
    await watchApi.cancel(w.id, value || '')
    ElMessage.success('值守已取消（终态）')
    await refreshOne(w.id)
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

async function openDetail(w: WatchView) {
  current.value = await watchApi.get(w.id)
  detailDrawer.value = true
}

const isTerminal = (w: WatchView) => w.status === 'READY' || w.status === 'CANCELLED'
</script>

<template>
  <div class="space-y-4">
    <PolicyNotice :policy="meta?.policy ?? ''" :rejected-reason="meta?.rejectedReason ?? ''" />

    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <span class="text-base font-semibold">开航值守安排</span>
          <div class="flex items-center gap-3">
            <ElSelect v-model="routeFilter" placeholder="全部航线" clearable style="width: 220px" @change="onFilter">
              <ElOption v-for="r in routes" :key="r.id" :label="`${r.routeCode} ${r.routeName}`" :value="r.id" />
            </ElSelect>
            <ElButton @click="load">刷新/重新判定</ElButton>
            <ElButton type="primary" @click="openCreate">安排值守</ElButton>
          </div>
        </div>
      </template>

      <ElTable :data="watches" stripe>
        <ElTableColumn label="航线 / 飞行日" min-width="190">
          <template #default="{ row }: { row: any }">
            <div class="font-medium">{{ row.routeCode }} {{ row.routeName }}</div>
            <div class="text-xs text-gray-500">
              飞行日 {{ row.flightDate }} ｜ 起飞 {{ row.plannedTakeoff?.replace('T', ' ') }}
              <span v-if="row.plannedEnd"> ｜ 结束 {{ row.plannedEnd.replace('T', ' ') }}</span>
              <ElTag v-if="row.plannedEnd && row.plannedEnd.slice(0,10) !== row.flightDate" size="small" type="warning" class="ml-1">跨午夜</ElTag>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作员" min-width="120">
          <template #default="{ row }: { row: any }">
            <div>{{ row.operatorName }}</div>
            <ElTag size="small" :type="row.operatorArrived ? 'success' : 'info'">
              {{ row.operatorArrived ? '已到位' : '未到位' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="复核员" min-width="120">
          <template #default="{ row }: { row: any }">
            <div>{{ row.reviewerName }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }: { row: any }">
            <ElTag :type="statusType(row.status)">{{ row.statusLabel }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="实时资格（非终态）" min-width="220">
          <template #default="{ row }: { row: any }">
            <template v-if="!isTerminal(row)">
              <div class="text-xs">
                <ElTag size="small" :type="row.operatorQualification?.qualified ? 'success' : 'danger'">操作员</ElTag>
                <ElTag size="small" :type="row.reviewerQualification?.qualified ? 'success' : 'danger'" class="ml-1">复核员</ElTag>
              </div>
              <div v-if="row.requalifyReason" class="text-xs text-orange-600 mt-1">↩ {{ row.requalifyReason }}</div>
            </template>
            <template v-else>
              <span class="text-xs text-gray-500">终态，显示就绪/取消时快照</span>
            </template>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="280">
          <template #default="{ row }: { row: any }">
            <ElButton size="small" @click="openDetail(row)">详情</ElButton>
            <template v-if="!isTerminal(row)">
              <ElButton size="small" @click="openEdit(row)">改派</ElButton>
            </template>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <!-- 排班对话框 -->
    <ElDialog v-model="createDialog" :title="editingId ? '改派值守（仅草拟）' : '安排开航值守'" width="600px">
      <ElAlert type="success" :closable="false" class="mb-3" :title="meta?.policy ?? ''" />
      <ElForm :model="form" label-width="120px">
        <ElFormItem label="航线" required>
          <ElSelect v-model="form.routeId" style="width: 100%" :disabled="editingId !== null">
            <ElOption v-for="r in routes" :key="r.id"
                      :label="`${r.routeCode} ${r.routeName}（${r.windLevel}）`" :value="r.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="操作员" required>
          <ElSelect v-model="form.operatorId" style="width: 100%" placeholder="选择操作员">
            <ElOption v-for="s in staff" :key="s.id" :label="`${s.staffName}（${s.staffCode}）`" :value="s.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="复核员" required>
          <ElSelect v-model="form.reviewerId" style="width: 100%" placeholder="必须与操作员不同">
            <ElOption v-for="s in staff" :key="s.id" :label="`${s.staffName}（${s.staffCode}）`" :value="s.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="预计起飞时刻" required>
          <ElDatePicker v-model="takeoffDate" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
        </ElFormItem>
        <ElFormItem label="预计结束时刻">
          <ElDatePicker v-model="endDate" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
          <div class="text-xs text-gray-400">可跨到次日凌晨；证书只按起飞时刻判定，结束时刻仅展示</div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createDialog = false">取消</ElButton>
        <ElButton type="primary" @click="submitWatch">保存</ElButton>
      </template>
    </ElDialog>

    <ElDrawer v-model="detailDrawer" title="值守详情" size="520px">
      <div v-if="current" class="space-y-4 px-2">
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="航线">{{ current.routeCode }} {{ current.routeName }}</ElDescriptionsItem>
          <ElDescriptionsItem label="飞行日">{{ current.flightDate }}</ElDescriptionsItem>
          <ElDescriptionsItem label="预计起飞 / 结束">
            {{ current.plannedTakeoff?.replace('T', ' ') }}
            <span v-if="current.plannedEnd"> ~ {{ current.plannedEnd.replace('T', ' ') }}</span>
            <ElTag v-if="current.plannedEnd && current.plannedEnd.slice(0,10) !== current.flightDate" size="small" type="warning" class="ml-2">跨午夜（按起飞时刻判定）</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="状态">
            <ElTag :type="statusType(current.status)">{{ current.statusLabel }}</ElTag>
            <span v-if="current.past" class="ml-2 text-xs text-gray-400">已结束（历史）</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="current.readyTime" label="就绪时间">{{ current.readyTime?.replace('T', ' ') }}（{{ current.readiedByName }}）</ElDescriptionsItem>
          <ElDescriptionsItem v-if="current.cancelTime" label="取消">{{ current.cancelTime?.replace('T', ' ') }} {{ current.cancelledByName }}：{{ current.cancelReason }}</ElDescriptionsItem>
        </ElDescriptions>

        <!-- 非终态：实时资质链 -->
        <template v-if="!isTerminal(current)">
          <ElAlert type="info" :closable="false" :title="current.policy" />
          <ElAlert v-if="current.requalifyReason" type="warning" :closable="false"
                   :title="'重新判定提示：' + current.requalifyReason" />

          <ElCard shadow="never" header="实时资格判定（航线当前风级与在用锚点区域）">
            <div class="text-sm mb-2">
              要求风级：<ElTag size="small">{{ current.requiredWindLevel }}</ElTag>
              要求区域：
              <ElTag v-for="z in current.requiredZones" :key="z" size="small" type="warning" class="mr-1">{{ z }}</ElTag>
              <span v-if="!current.requiredZones?.length" class="text-gray-400">当前无在用锚点</span>
            </div>
            <QualBlock title="操作员" :name="current.operatorName" :q="current.operatorQualification" />
            <QualBlock title="复核员" :name="current.reviewerName" :q="current.reviewerQualification" />
            <ElAlert v-if="!current.distinctPeople" type="error" :closable="false"
                     title="操作员与复核员为同一人，该安排永远不能就绪，必须改派" class="mt-2" />
          </ElCard>

          <div class="flex flex-wrap gap-2">
            <ElButton type="primary"
                      :disabled="current.operatorArrived === 1"
                      @click="arrive(current)">
              操作员确认现场到位
            </ElButton>
            <ElButton v-if="current.operatorArrived === 1" @click="withdraw(current)">撤回到位</ElButton>
            <ElButton type="success" :disabled="!current.canReady" @click="ready(current)">
              复核员确认就绪
            </ElButton>
            <ElButton type="danger" plain @click="cancelWatch(current)">取消值守</ElButton>
          </div>
          <div class="text-xs text-gray-500">
            状态链：草拟 →（操作员到位）待复核 →（资质实时均覆盖）就绪。只有操作员本人能确认到位；
            操作员到位前复核员无法就绪；已就绪值守仅安全主管可取消。以上全部由服务端强制执行。
          </div>
        </template>

        <!-- 终态：历史快照 -->
        <template v-else>
          <ElAlert v-if="current.status === 'READY'" type="success" :closable="false"
                   title="该值守已就绪并结束。以下为就绪当时冻结的证书快照，不随后续人员改名/证书吊销而变化。" />
          <ElAlert v-if="current.status === 'CANCELLED'" type="error" :closable="false" title="该值守已取消（终态）。" />
          <ElCard v-if="current.hasSnapshot" shadow="never" header="就绪证书快照">
            <ElDescriptions :column="1" border>
              <ElDescriptionsItem label="判定时刻">
                起飞 {{ current.snapshotTakeoff?.replace('T', ' ') }}
                <span v-if="current.snapshotEnd"> ～ {{ current.snapshotEnd.replace('T', ' ') }}</span>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="当时航线风级">{{ current.snapshotRouteWindLevel }}</ElDescriptionsItem>
              <ElDescriptionsItem label="当时要求区域">
                <ElTag v-for="z in current.snapshotRequiredZones" :key="z" size="small" type="warning" class="mr-1">{{ z }}</ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="操作员（快照姓名）">
                {{ current.operatorSnapshotName }}｜证号 <span class="font-mono">{{ current.operatorSnapshotCertNo }}</span>
                <div class="text-xs text-gray-600">{{ current.operatorSnapshotScope }}</div>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="复核员（快照姓名）">
                {{ current.reviewerSnapshotName }}｜证号 <span class="font-mono">{{ current.reviewerSnapshotCertNo }}</span>
                <div class="text-xs text-gray-600">{{ current.reviewerSnapshotScope }}</div>
              </ElDescriptionsItem>
            </ElDescriptions>
          </ElCard>
          <ElEmpty v-else description="取消前未就绪，无证书快照" :image-size="60" />
        </template>
      </div>
    </ElDrawer>
  </div>
</template>
