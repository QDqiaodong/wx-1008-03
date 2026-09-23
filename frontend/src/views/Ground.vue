<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElButton, ElTable, ElTableColumn, ElDialog, ElForm, ElFormItem, ElInput,
  ElSelect, ElOption, ElMessage, ElTag, ElCard, ElDatePicker, ElMessageBox,
  ElRadioGroup, ElRadio, ElCollapse, ElCollapseItem, ElAlert
} from 'element-plus'
import {
  staffApi, certApi, groundMetaApi, routeApi,
  type StaffView, type CertView, type GroundMeta, type FlightRoute, type StaffRole
} from '../api'
import { useCurrentStaff } from '../composables/useCurrentStaff'
import PolicyNotice from '../components/PolicyNotice.vue'

const staffList = ref<StaffView[]>([])
const meta = ref<GroundMeta | null>(null)
const routes = ref<FlightRoute[]>([])
const loading = ref(false)

// 试算参数：航线 + 预计起飞时刻（跨午夜按起飞时刻判定）
const trialRouteId = ref<number | null>(null)
const trialTakeoff = ref<Date | null>(null)

const staffDialog = ref(false)
const staffEditing = ref<number | null>(null)
const staffForm = ref({ staffCode: '', staffName: '', staffRole: 'STATION_OFFICER' as StaffRole })

const certDialog = ref(false)
const certTarget = ref<StaffView | null>(null)
const certForm = ref({
  certNo: '',
  windLevels: [] as string[],
  anchorZones: [] as string[],
  effectiveDate: '',
  expiryDate: ''
})

const { current } = useCurrentStaff()
const me = computed(() => current())

function fmtTakeoff(d: Date | null): string | undefined {
  if (!d) return undefined
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:00`
}

async function load() {
  loading.value = true
  try {
    staffList.value = await staffApi.list(
      trialRouteId.value ?? undefined,
      fmtTakeoff(trialTakeoff.value)
    )
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const [m, rs] = await Promise.all([groundMetaApi.get(), routeApi.list()])
  meta.value = m
  routes.value = rs.filter(r => r.status === 1)
  // 试算默认：明天上午 9 点
  const d = new Date()
  d.setDate(d.getDate() + 1)
  d.setHours(9, 0, 0, 0)
  trialTakeoff.value = d
  await load()
})

function resetTrial() {
  trialRouteId.value = null
  trialTakeoff.value = null
  load()
}

const certStatusType = (s: string): any =>
  ({ VALID: 'success', PENDING: 'warning', EXPIRED: 'info', REVOKED: 'danger' } as Record<string, string>)[s]
const certStatusLabel = (s: string): string =>
  ({ VALID: '有效', PENDING: '待生效', EXPIRED: '已过期', REVOKED: '已吊销' } as Record<string, string>)[s] ?? s

function openStaffCreate() {
  staffEditing.value = null
  staffForm.value = { staffCode: '', staffName: '', staffRole: 'STATION_OFFICER' }
  staffDialog.value = true
}

function openStaffEdit(s: StaffView) {
  staffEditing.value = s.id
  staffForm.value = { staffCode: s.staffCode, staffName: s.staffName, staffRole: s.staffRole }
  staffDialog.value = true
}

async function submitStaff() {
  try {
    if (staffEditing.value) {
      await staffApi.update(staffEditing.value, staffForm.value)
      ElMessage.success('人员已更新（历史值守快照姓名不会改变）')
    } else {
      await staffApi.create(staffForm.value)
      ElMessage.success('人员已创建')
    }
    staffDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

async function disableStaff(s: StaffView) {
  try {
    await ElMessageBox.confirm(`确认停用人员「${s.staffName}」？停用后不能再被排班。`, '停用确认', { type: 'warning' })
    await staffApi.disable(s.id)
    ElMessage.success('已停用')
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

function openCertCreate(s: StaffView) {
  certTarget.value = s
  certForm.value = { certNo: '', windLevels: [], anchorZones: [], effectiveDate: '', expiryDate: '' }
  certDialog.value = true
}

async function submitCert() {
  if (!certTarget.value) return
  try {
    await certApi.create({
      certNo: certForm.value.certNo,
      staffId: certTarget.value.id,
      windLevels: certForm.value.windLevels,
      anchorZones: certForm.value.anchorZones,
      effectiveDate: certForm.value.effectiveDate,
      expiryDate: certForm.value.expiryDate
    })
    ElMessage.success('资质证已创建')
    certDialog.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

async function revokeCert(c: CertView) {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认吊销证书 ${c.certNo}？吊销为终态，该人员未来飞行日尚未就绪的值守会立刻重新判定并打回草拟；已结束/已就绪的历史不变。`,
      '安全主管吊销证书',
      { confirmButtonText: '确认吊销', cancelButtonText: '取消', type: 'warning', inputPlaceholder: '吊销原因（必填）' }
    )
    if (!value || !value.trim()) {
      ElMessage.warning('请填写吊销原因')
      return
    }
    const r = await certApi.revoke(c.id, value.trim())
    ElMessage.success(`已吊销，${r.requalifiedWatches} 条未来未就绪值守已重新判定打回草拟`)
    await load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e.message)
  }
}

// 即使当前人不是主管也保留按钮（点击会被后端 403 拒绝），证明限制不是靠隐藏实现
const canShowAdminHint = computed(() => me.value && me.value.staffRole !== 'SAFETY_OFFICER')
</script>

<template>
  <div class="space-y-4">
    <PolicyNotice
      :policy="meta?.policy ?? ''"
      :rejected-reason="meta?.rejectedReason ?? ''"
    />

    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <span class="text-base font-semibold">胜任度试算（与值守详情、航线入口同一判定）</span>
          <div class="flex items-center gap-2">
            <ElButton size="small" @click="load">重新判定</ElButton>
            <ElButton size="small" @click="resetTrial">清除试算</ElButton>
          </div>
        </div>
      </template>
      <div class="flex items-center gap-4 flex-wrap">
        <div class="flex items-center gap-2">
          <span class="text-sm text-gray-600">航线：</span>
          <ElSelect v-model="trialRouteId" placeholder="选择试算航线" clearable style="width: 220px" @change="load">
            <ElOption v-for="r in routes" :key="r.id" :label="`${r.routeCode} ${r.routeName}（${r.windLevel}）`" :value="r.id" />
          </ElSelect>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-sm text-gray-600">预计起飞时刻：</span>
          <ElDatePicker
            v-model="trialTakeoff"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            placeholder="跨午夜任务也只按此刻判定"
            style="width: 220px"
            @change="load"
          />
          <span class="text-xs text-gray-400">可选择次日凌晨时刻验证跨午夜结论</span>
        </div>
      </div>
      <ElAlert v-if="canShowAdminHint" type="warning" :closable="false" class="mt-3">
        当前操作人是普通值班员：可以看到全部数据，但吊销证书等主管操作即使点击也会被服务端拒绝（403），数据不变。
      </ElAlert>
    </ElCard>

    <ElCard>
      <template #header>
        <div class="flex items-center justify-between">
          <span class="text-base font-semibold">地勤人员与资质证</span>
          <ElButton type="primary" @click="openStaffCreate">新增人员</ElButton>
        </div>
      </template>

      <ElTable :data="staffList" stripe v-loading="loading" row-key="id">
        <ElTableColumn label="人员" min-width="160">
          <template #default="{ row }: { row: any }">
            <div class="font-medium">{{ row.staffName }}（{{ row.staffCode }}）</div>
            <ElTag size="small" :type="row.staffRole === 'SAFETY_OFFICER' ? 'danger' : 'info'">
              {{ row.staffRoleLabel }}
            </ElTag>
            <ElTag v-if="row.status !== 1" size="small" type="info" class="ml-1">已停用</ElTag>
          </template>
        </ElTableColumn>

        <ElTableColumn label="资质证（按试算起飞时刻判定状态）" min-width="420">
          <template #default="{ row }: { row: any }">
            <ElCollapse v-if="row.certs.length">
              <ElCollapseItem v-for="c in row.certs" :key="c.id">
                <template #title>
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-mono">{{ c.certNo }}</span>
                    <ElTag size="small" :type="certStatusType(c.status)">{{ certStatusLabel(c.status) }}</ElTag>
                    <span class="text-xs text-gray-500">{{ c.effectiveDate }} ~ {{ c.expiryDate }}</span>
                  </div>
                </template>
                <div class="text-sm space-y-1 pl-1">
                  <div>适用风级：<ElTag v-for="w in c.windLevels" :key="w" size="small" class="mr-1">{{ w }}</ElTag></div>
                  <div>可负责区域：
                    <ElTag v-for="z in c.anchorZones" :key="z" size="small" type="warning" class="mr-1">{{ z }}</ElTag>
                    <span v-if="!c.anchorZones.length" class="text-gray-400">无</span>
                  </div>
                  <div v-if="c.status === 'REVOKED'" class="text-red-600 text-xs">
                    吊销原因：{{ c.revokeReason }}（{{ c.revokedByName }}）
                  </div>
                  <div class="pt-1">
                    <ElButton
                      size="small" type="danger" plain
                      :disabled="c.status === 'REVOKED'"
                      @click="revokeCert(c)"
                    >
                      吊销证书（主管）
                    </ElButton>
                  </div>
                </div>
              </ElCollapseItem>
            </ElCollapse>
            <span v-else class="text-gray-400 text-sm">名下无证书</span>
          </template>
        </ElTableColumn>

        <ElTableColumn v-if="trialRouteId" label="航线胜任试算结论" min-width="320">
          <template #default="{ row }: { row: any }">
            <template v-if="row.qualification">
              <ElTag :type="row.qualification.qualified ? 'success' : 'danger'">
                {{ row.qualification.qualified ? '可值守' : '不满足' }}
              </ElTag>
              <div v-if="row.qualification.activeCertNo" class="text-xs text-gray-500 mt-1">
                代表证书：<span class="font-mono">{{ row.qualification.activeCertNo }}</span>
                （{{ certStatusLabel(row.qualification.activeCertStatus) }}）
              </div>
              <div v-for="(msg, i) in row.qualification.detailMessages" :key="i" class="text-xs text-red-600 mt-1">
                · {{ msg }}
              </div>
            </template>
            <span v-else class="text-gray-400 text-sm">—</span>
          </template>
        </ElTableColumn>

        <ElTableColumn label="操作" width="180">
          <template #default="{ row }: { row: any }">
            <ElButton size="small" @click="openCertCreate(row)">发证书</ElButton>
            <ElButton size="small" @click="openStaffEdit(row)">编辑</ElButton>
            <ElButton size="small" type="danger" plain :disabled="row.status !== 1" @click="disableStaff(row)">停用</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <!-- 人员新增/编辑 -->
    <ElDialog v-model="staffDialog" :title="staffEditing ? '编辑地勤人员' : '新增地勤人员'" width="460px">
      <ElForm :model="staffForm" label-width="90px">
        <ElFormItem label="人员编号" required>
          <ElInput v-model="staffForm.staffCode" />
        </ElFormItem>
        <ElFormItem label="姓名" required>
          <ElInput v-model="staffForm.staffName" />
        </ElFormItem>
        <ElFormItem label="角色" required>
          <ElRadioGroup v-model="staffForm.staffRole">
            <ElRadio value="STATION_OFFICER">普通值班员</ElRadio>
            <ElRadio value="SAFETY_OFFICER">安全主管</ElRadio>
          </ElRadioGroup>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="staffDialog = false">取消</ElButton>
        <ElButton type="primary" @click="submitStaff">确定</ElButton>
      </template>
    </ElDialog>

    <!-- 发证 -->
    <ElDialog v-model="certDialog" :title="`向「${certTarget?.staffName ?? ''}」颁发资质证`" width="560px">
      <ElAlert type="success" :closable="false" class="mb-3" :title="meta?.policy ?? ''" />
      <ElForm :model="certForm" label-width="110px">
        <ElFormItem label="证书编号" required>
          <ElInput v-model="certForm.certNo" placeholder="如 C-ZHANG-2026-01" />
        </ElFormItem>
        <ElFormItem label="适用风级" required>
          <ElSelect v-model="certForm.windLevels" multiple style="width: 100%" placeholder="选择可胜任风级">
            <ElOption v-for="w in meta?.windLevels ?? []" :key="w" :label="w" :value="w" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="锚点区域" required>
          <ElSelect v-model="certForm.anchorZones" multiple style="width: 100%" placeholder="选择可负责区域">
            <ElOption v-for="z in meta?.anchorZones ?? []" :key="z" :label="z" :value="z" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="生效日" required>
          <ElDatePicker v-model="certForm.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </ElFormItem>
        <ElFormItem label="到期日" required>
          <ElDatePicker v-model="certForm.expiryDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="certDialog = false">取消</ElButton>
        <ElButton type="primary" @click="submitCert">颁发</ElButton>
      </template>
    </ElDialog>
  </div>
</template>
