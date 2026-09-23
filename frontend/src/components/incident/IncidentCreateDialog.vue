<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import {
  ElDialog, ElForm, ElFormItem, ElInput, ElSelect, ElOption, ElDatePicker,
  ElButton, ElAlert, ElTag
} from 'element-plus'
import {
  type IncidentCreateDTO, type FlightRoute,
  type Anchor, adaptApi, watchApi, type WatchView
} from '../../api'

/**
 * 建立异常事件：选择已发生飞行对应的航线、值守安排、涉及锚点。
 * 提交后服务端冻结关联对象的关键名称/风级/人员/锚点状态（前端传不传文本都不作数）。
 */
const props = defineProps<{
  modelValue: boolean
  routes: FlightRoute[]
  anchors: Anchor[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', dto: IncidentCreateDTO): void
}>()

const watches = ref<WatchView[]>([])
const boundAnchorIds = ref<number[]>([])
const routeId = ref<number | null>(null)
const watchId = ref<number | null>(null)
const anchorIds = ref<number[]>([])
const title = ref('')
const foundDate = ref<Date | null>(null)
const severity = ref<IncidentCreateDTO['severity']>('MINOR')
const incidentNote = ref('')
const handlingAction = ref('')
const evidenceDesc = ref('')

function pad(n: number) { return String(n).padStart(2, '0') }
function iso(d: Date | null): string {
  if (!d) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

async function onRouteChange() {
  watchId.value = null
  boundAnchorIds.value = []
  anchorIds.value = []
  if (routeId.value) {
    try {
      const bound = await adaptApi.bound(routeId.value)
      boundAnchorIds.value = bound.map(b => b.anchorId)
    } catch { /* 未绑定锚点时允许从全部锚点手选 */ }
  }
}

watch(() => props.modelValue, async (open) => {
  if (open) {
    routeId.value = props.routes[0]?.id ?? null
    watchId.value = null
    anchorIds.value = []
    title.value = ''
    const d = new Date(); d.setHours(d.getHours() - 1, 0, 0, 0)
    foundDate.value = d
    severity.value = 'MINOR'
    incidentNote.value = ''
    handlingAction.value = ''
    evidenceDesc.value = ''
    watches.value = await watchApi.list()
    await onRouteChange()
  }
})

const watchesOfRoute = computed(() =>
  watches.value.filter(w => w.routeId === routeId.value && w.past))

/** 优先列该航线在用锚点；若该航线未绑定任何锚点，则退回全部锚点供手选 */
const selectableAnchors = computed<Anchor[]>(() => {
  const bound = props.anchors.filter(a => boundAnchorIds.value.includes(a.id))
  return bound.length ? bound : props.anchors
})

const selectedWatch = computed(() => watches.value.find(w => w.id === watchId.value) ?? null)
const selectedRoute = computed(() => props.routes.find(r => r.id === routeId.value) ?? null)

function submit() {
  if (!routeId.value || !title.value.trim() || !foundDate.value || !incidentNote.value.trim()) return
  emit('submit', {
    title: title.value.trim(),
    watchId: watchId.value,
    routeId: routeId.value,
    anchorIds: anchorIds.value,
    foundTime: iso(foundDate.value),
    severity: severity.value,
    incidentNote: incidentNote.value.trim(),
    handlingAction: handlingAction.value.trim() || null,
    evidenceDesc: evidenceDesc.value.trim() || null
  })
}
</script>

<template>
  <ElDialog :model-value="modelValue" title="建立飞行异常事件" width="720px"
            :close-on-click-modal="false"
            @update:model-value="emit('update:modelValue', $event)">
    <ElAlert type="warning" :closable="false" class="mb-3"
             title="事件建立时将冻结：航线名称与风级、值守操作员/复核员、涉及锚点的编号/区域/状态/承重。之后修改人员、航线或锚点档案，本事件仍显示事发时内容，并可在详情中对比当前资料差异。" />
    <ElForm label-width="100px">
      <ElFormItem label="事发航线" required>
        <ElSelect v-model="routeId" style="width: 100%" @change="onRouteChange">
          <ElOption v-for="r in routes" :key="r.id"
                    :label="`${r.routeCode} ${r.routeName}（${r.windLevel}）`" :value="r.id" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="值守安排">
        <ElSelect v-model="watchId" clearable filterable style="width: 100%"
                  placeholder="选择当时的开航值守（只列出该航线已结束的飞行；可不选）">
          <ElOption v-for="w in watchesOfRoute" :key="w.id"
                    :label="`飞行日 ${w.flightDate} ${w.plannedTakeoff?.replace('T', ' ')}｜${w.operatorName} / ${w.reviewerName}｜${w.statusLabel}`"
                    :value="w.id" />
        </ElSelect>
        <div v-if="selectedWatch" class="text-xs text-gray-500 mt-1">
          将冻结操作员「{{ selectedWatch.operatorName }}」、复核员「{{ selectedWatch.reviewerName }}」为事发人员
        </div>
      </ElFormItem>
      <ElFormItem label="涉及锚点">
          <ElSelect v-model="anchorIds" multiple collapse-tags collapse-tags-tooltip style="width: 100%"
                  :placeholder="boundAnchorIds.length ? '默认该航线在用锚点，可增删（将冻结其当时状态）' : '该航线暂无绑定锚点，可从全部锚点手选涉事锚点'">
          <ElOption v-for="a in selectableAnchors" :key="a.id"
                    :label="`${a.anchorCode} ${a.locationDesc ?? ''}（${a.anchorZone ?? '未分区'} · ${a.status === 1 ? '启用' : '停用'}）`"
                    :value="a.id" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="事件标题" required>
        <ElInput v-model="title" maxlength="200" placeholder="如：R-GALE 飞行中 A-GALE2600 锚点松动" />
      </ElFormItem>
      <ElFormItem label="发现时间" required>
        <ElDatePicker v-model="foundDate" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
      </ElFormItem>
      <ElFormItem label="严重级别" required>
        <ElSelect v-model="severity" style="width: 200px">
          <ElOption label="一般" value="MINOR" />
          <ElOption label="较大" value="MAJOR" />
          <ElOption label="严重" value="CRITICAL" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="现场经过" required>
        <ElInput v-model="incidentNote" type="textarea" :rows="4" maxlength="4000" show-word-limit />
      </ElFormItem>
      <ElFormItem label="处置动作">
        <ElInput v-model="handlingAction" type="textarea" :rows="2" maxlength="4000" />
      </ElFormItem>
      <ElFormItem label="证据说明">
        <ElInput v-model="evidenceDesc" type="textarea" :rows="2" maxlength="2000"
                 placeholder="物证位置、照片/视频编号、证人等" />
      </ElFormItem>
    </ElForm>
    <div v-if="selectedRoute" class="text-xs text-gray-400">
      建立后将以当前口径冻结：
      <ElTag size="small">{{ selectedRoute.routeCode }}</ElTag>
      <ElTag size="small" type="info">{{ selectedRoute.routeName }}</ElTag>
      <ElTag size="small" type="warning">{{ selectedRoute.windLevel }}</ElTag>
    </div>

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" @click="submit">建立事件（草稿，写入第 1 版）</ElButton>
    </template>
  </ElDialog>
</template>
