<script setup lang="ts">
import { ElDialog, ElButton, ElTag, ElAlert } from 'element-plus'
import type { IncidentConflict } from '../../api'

/**
 * 整份事件版本冲突对话框（唯一并发口径）：
 * 展示冲突字段（你的值 vs 最新值、最新改动人），用户二选一：
 *  - 放弃：保留自己的页面不动；
 *  - 基于最新版本重新编辑：把最新正文回填到表单（不自动合并字段，合并决策交还给人）。
 */
defineProps<{
  conflict: IncidentConflict | null
}>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'discard'): void
  (e: 'rebase', latest: IncidentConflict['latest']): void
}>()
</script>

<template>
  <ElDialog
    :model-value="conflict !== null"
    title="版本冲突：事件已被他人更新"
    width="760px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <div v-if="conflict" class="space-y-3">
      <ElAlert type="error" :closable="false" :title="conflict.message" />
      <ElAlert
        type="info"
        :closable="false"
        title="本系统采用「整份事件版本冲突」口径，不做字段级自动合并：事件正文是互为前提的完整调查报告，自动拼接会产生无人审阅过的版本。请逐字段对照后，自己决定如何把修改并入最新内容。"
      />

      <table class="w-full text-sm border-collapse">
        <thead>
          <tr class="bg-gray-100">
            <th class="border p-2 text-left w-28">冲突字段</th>
            <th class="border p-2 text-left">你提交的内容（依据第 {{ conflict.expectedVersion }} 版）</th>
            <th class="border p-2 text-left">最新内容（第 {{ conflict.latestVersion }} 版）</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in conflict.conflictFields" :key="f.field">
            <td class="border p-2 align-top font-medium">
              {{ f.fieldLabel }}
              <div v-if="f.latestChangedBy" class="text-xs text-gray-400 mt-1">
                {{ f.latestChangeType }} · {{ f.latestChangedBy }}
              </div>
            </td>
            <td class="border p-2 align-top text-red-700 whitespace-pre-wrap break-all">{{ f.yourValue || '(空)' }}</td>
            <td class="border p-2 align-top text-green-700 whitespace-pre-wrap break-all">{{ f.latestValue || '(空)' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="text-xs text-gray-500">
        当前最新状态：<ElTag size="small">{{ conflict.latest.statusLabel }}</ElTag>
        版本号 v{{ conflict.latest.version }}
      </div>
    </div>

    <template #footer>
      <ElButton @click="emit('discard')">放弃我的修改（保留最新版）</ElButton>
      <ElButton type="primary" @click="conflict && emit('rebase', conflict.latest)">
        基于最新版本重新编辑
      </ElButton>
    </template>
  </ElDialog>
</template>
