<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  ElDialog, ElForm, ElFormItem, ElInput, ElSelect, ElOption, ElDatePicker,
  ElButton, ElAlert, ElTag
} from 'element-plus'
import type {
  IncidentDetail, IncidentEditDTO, GroundStaff, IncidentSeverity
} from '../../api'

/**
 * 正文编辑表单。
 *  - 调查中/重新开启：全字段可改（含结论四项）；
 *  - 已封存：仅“带理由更正”，证据说明锁定（原证据不可覆盖），标题提示旧版本保留；
 *  - 草稿：报告人补充基本信息。
 * 提交对象始终携带 expectedVersion（整份事件版本冲突口径）。
 */
const props = defineProps<{
  modelValue: boolean
  detail: IncidentDetail | null
  staff: GroundStaff[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', dto: IncidentEditDTO): void
}>()

const form = ref<IncidentEditDTO>(blank())
const foundDate = ref<Date | null>(null)
const dueDate = ref<string | null>(null)
const submitting = ref(false)

function blank(): IncidentEditDTO {
  return {
    expectedVersion: 0, title: '', foundTime: '', severity: 'MINOR',
    incidentNote: '', handlingAction: '', evidenceDesc: '',
    rootCause: '', correctiveAction: '', ownerId: null, dueDate: null, changeReason: ''
  }
}

function pad(n: number) { return String(n).padStart(2, '0') }
function iso(d: Date | null): string {
  if (!d) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}

watch(() => props.modelValue, (open) => {
  if (open && props.detail) {
    const d = props.detail
    form.value = {
      expectedVersion: d.version,
      title: d.title,
      foundTime: d.foundTime,
      severity: d.severity,
      incidentNote: d.incidentNote,
      handlingAction: d.handlingAction ?? '',
      evidenceDesc: d.evidenceDesc ?? '',
      rootCause: d.rootCause ?? '',
      correctiveAction: d.correctiveAction ?? '',
      ownerId: d.ownerId,
      dueDate: d.dueDate,
      changeReason: ''
    }
    foundDate.value = new Date(d.foundTime)
    dueDate.value = d.dueDate
  }
})

function submit() {
  if (!form.value.title.trim() || !form.value.incidentNote.trim()) return
  const payload: IncidentEditDTO = {
    ...form.value,
    foundTime: iso(foundDate.value) || form.value.foundTime,
    dueDate: dueDate.value,
    handlingAction: form.value.handlingAction?.trim() || null,
    rootCause: form.value.rootCause?.trim() || null,
    correctiveAction: form.value.correctiveAction?.trim() || null,
    changeReason: form.value.changeReason?.trim() || null
  }
  submitting.value = true
  emit('submit', payload)
}

function stop() {
  // 父组件负责在请求结束后关闭；这里暴露重置
  submitting.value = false
}
defineExpose({ stop })

const severities: { value: IncidentSeverity; label: string }[] = [
  { value: 'MINOR', label: '一般' },
  { value: 'MAJOR', label: '较大' },
  { value: 'CRITICAL', label: '严重' }
]
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    :title="detail?.status === 'SEALED' ? '封存后更正（旧版本保留，必须写理由）' : '修订事件正文'"
    width="720px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="stop()"
  >
    <div v-if="detail" class="space-y-3">
      <ElAlert
        :type="detail.status === 'SEALED' ? 'warning' : 'info'"
        :closable="false"
        :title="`整份事件版本冲突口径：本次保存依据版本 v${detail.version}。若期间他人已改动，保存会被拒绝并提示冲突字段。`"
      />
      <div v-if="detail.status === 'SEALED'" class="text-sm text-red-700">
        事件已封存（第 {{ detail.sealedCount }} 次）。本次更正不会覆盖任何旧版本，将生成一条“封存后更正”修订；
        原证据说明不允许改动。
      </div>

      <ElForm label-width="100px">
        <ElFormItem label="标题" required>
          <ElInput v-model="form.title" maxlength="200" />
        </ElFormItem>
        <ElFormItem label="发现时间" required>
          <ElDatePicker v-model="foundDate" type="datetime" format="YYYY-MM-DD HH:mm" style="width: 100%" />
        </ElFormItem>
        <ElFormItem label="严重级别" required>
          <ElSelect v-model="form.severity" style="width: 200px">
            <ElOption v-for="s in severities" :key="s.value" :label="s.label" :value="s.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="现场经过" required>
          <ElInput v-model="form.incidentNote" type="textarea" :rows="4" maxlength="4000" show-word-limit />
        </ElFormItem>
        <ElFormItem label="处置动作">
          <ElInput v-model="form.handlingAction" type="textarea" :rows="3" maxlength="4000" />
        </ElFormItem>
        <ElFormItem label="证据说明">
          <ElInput
            v-model="form.evidenceDesc" type="textarea" :rows="2" maxlength="2000"
            :disabled="detail.status === 'SEALED'"
            placeholder="物证位置、照片编号、证人等"
          />
          <div v-if="detail.status === 'SEALED'" class="text-xs text-gray-400">
            封存后原证据不可覆盖（已锁定）；新材料请写入纠正措施或由安全主管重新开启。
          </div>
        </ElFormItem>

        <ElFormItem label="原因结论">
          <ElInput v-model="form.rootCause" type="textarea" :rows="3" maxlength="4000" />
        </ElFormItem>
        <ElFormItem label="纠正措施">
          <ElInput v-model="form.correctiveAction" type="textarea" :rows="3" maxlength="4000" />
        </ElFormItem>
        <ElFormItem label="整改负责人">
          <ElSelect v-model="form.ownerId" clearable style="width: 240px" placeholder="选择负责人">
            <ElOption v-for="s in staff" :key="s.id" :label="`${s.staffName}（${s.staffCode}）`" :value="s.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="整改期限">
          <ElDatePicker v-model="dueDate" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" style="width: 200px" />
        </ElFormItem>

        <ElFormItem v-if="detail.status === 'SEALED'" label="更正理由" required>
          <ElInput v-model="form.changeReason" type="textarea" :rows="2" maxlength="1000"
                   placeholder="必须说明更正原因与依据，该理由将随新修订永久留痕" show-word-limit />
        </ElFormItem>
      </ElForm>

      <div class="text-xs text-gray-400">
        <ElTag size="small">依据版本 v{{ form.expectedVersion }}</ElTag>
        封存前要求：原因结论 + 纠正措施 + 负责人 + 期限四项齐备，安全主管才能封存。
      </div>
    </div>

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">
        {{ detail?.status === 'SEALED' ? '提交更正（生成新版本）' : '保存修订' }}
      </ElButton>
    </template>
  </ElDialog>
</template>
