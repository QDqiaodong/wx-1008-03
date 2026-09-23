<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog, ElTag, ElSelect, ElOption, ElEmpty } from 'element-plus'
import type { IncidentRevision } from '../../api'

/**
 * 不可变修订记录：选择一个版本 N，左侧并排显示 N-1 旧版、右侧显示 N 新版，
 * 差异字段在顶部标出。封存后的“封存后更正”与旧版可直接并排查看。
 */
const props = defineProps<{
  modelValue: boolean
  revisions: IncidentRevision[]
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const selectedNo = ref<number | null>(null)

watch(() => props.modelValue, (open) => {
  if (open) {
    selectedNo.value = props.revisions.length ? props.revisions[props.revisions.length - 1].revisionNo : null
  }
})

const selected = computed(() => props.revisions.find(r => r.revisionNo === selectedNo.value) ?? null)
const previous = computed(() => {
  if (!selected.value) return null
  const idx = props.revisions.findIndex(r => r.revisionNo === selected.value!.revisionNo)
  return idx > 0 ? props.revisions[idx - 1] : null
})

const tagType = (t: string): any =>
  ({ CREATE: 'info', EDIT: '', SEAL: 'success', CORRECTION: 'warning', REOPEN: 'danger' } as Record<string, string>)[t] ?? ''

interface Row { label: string; old: string | null; new: string }
const rows = computed<Row[]>(() => {
  const n = selected.value
  if (!n) return []
  return [
    { label: '状态', old: previous.value ? `${previous.value.statusAfterLabel ?? '—'}` : null, new: `${n.statusBeforeLabel ? n.statusBeforeLabel + ' → ' : ''}${n.statusAfterLabel}` },
    { label: '标题', old: previous.value?.title ?? null, new: n.title },
    { label: '发现时间', old: previous.value?.foundTime?.replace('T', ' ') ?? null, new: n.foundTime.replace('T', ' ') },
    { label: '严重级别', old: previous.value?.severityLabel ?? null, new: n.severityLabel },
    { label: '现场经过', old: previous.value?.incidentNote ?? null, new: n.incidentNote },
    { label: '处置动作', old: previous.value?.handlingAction ?? null, new: n.handlingAction ?? '' },
    { label: '证据说明', old: previous.value?.evidenceDesc ?? null, new: n.evidenceDesc ?? '' },
    { label: '原因结论', old: previous.value?.rootCause ?? null, new: n.rootCause ?? '' },
    { label: '纠正措施', old: previous.value?.correctiveAction ?? null, new: n.correctiveAction ?? '' },
    { label: '整改负责人', old: previous.value?.ownerName ?? null, new: n.ownerName ?? '' },
    { label: '整改期限', old: previous.value?.dueDate ?? null, new: n.dueDate ?? '' }
  ]
})

function isDiff(row: Row): boolean {
  return (row.old ?? '') !== (row.new ?? '')
}
</script>

<template>
  <ElDialog :model-value="modelValue" title="修订记录（只追加，旧版本永久保留）" width="900px"
            @update:model-value="emit('update:modelValue', $event)">
    <div v-if="revisions.length" class="flex items-center gap-3 mb-3">
      <span class="text-sm text-gray-600">查看版本：</span>
      <ElSelect v-model="selectedNo" style="width: 360px">
        <ElOption
          v-for="r in [...revisions].reverse()" :key="r.revisionNo"
          :label="`v${r.revisionNo} ${r.changeTypeLabel}（${r.operatorName} ${r.createTime.replace('T', ' ')}）`"
          :value="r.revisionNo"
        />
      </ElSelect>
      <ElTag v-if="selected" :type="tagType(selected.changeType)">{{ selected.changeTypeLabel }}</ElTag>
    </div>

    <ElEmpty v-if="!revisions.length" description="暂无修订" :image-size="60" />

    <template v-else-if="selected">
      <div class="text-sm mb-2 text-gray-600">
        v{{ selected.revisionNo }} 由 <b>{{ selected.operatorName }}</b> 于 {{ selected.createTime.replace('T', ' ') }} 产生
        <span v-if="selected.changeReason" class="block mt-1 text-orange-700">理由/依据：{{ selected.changeReason }}</span>
      </div>
      <div class="mb-3">
        <span class="text-sm text-gray-600 mr-2">本次变更字段：</span>
        <ElTag v-for="f in selected.changedFields" :key="f" size="small" type="warning" class="mr-1">{{ f }}</ElTag>
        <span v-if="!selected.changedFields.length" class="text-xs text-gray-400">无正文变化（仅状态流转）</span>
      </div>

      <table class="w-full text-xs border-collapse">
        <thead>
          <tr class="bg-gray-100">
            <th class="border p-2 w-24 text-left">字段</th>
            <th class="border p-2 text-left">
              旧版 v{{ previous?.revisionNo ?? '—' }}
              <span v-if="previous" class="text-gray-400 font-normal">（{{ previous.operatorName }} {{ previous.createTime.replace('T', ' ') }}）</span>
            </th>
            <th class="border p-2 text-left">新版 v{{ selected.revisionNo }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.label" :class="isDiff(row) ? 'bg-orange-50' : ''">
            <td class="border p-2 align-top font-medium">{{ row.label }}</td>
            <td class="border p-2 align-top text-gray-500 whitespace-pre-wrap break-all">
              <template v-if="previous">{{ row.old || '(空)' }}</template>
              <template v-else><span class="text-gray-300">建立事件，无旧版</span></template>
            </td>
            <td class="border p-2 align-top whitespace-pre-wrap break-all" :class="isDiff(row) ? 'font-medium text-gray-900' : 'text-gray-600'">
              {{ row.new || '(空)' }}
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </ElDialog>
</template>
