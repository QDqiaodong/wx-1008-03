<script setup lang="ts">
import { ElAlert } from 'element-plus'

defineProps<{
  policy: string
  rejectedReason: string
}>()
</script>

<template>
  <div class="space-y-2">
    <ElAlert type="success" :closable="false" show-icon>
      <template #title>
        <span class="font-medium">跨午夜证书有效性口径（已拍板，全系统一致）：</span>
        <span>{{ policy }}</span>
      </template>
    </ElAlert>
    <ElAlert type="info" :closable="false" show-icon>
      <template #title>
        <span class="font-medium">为什么不采用“覆盖整个预计飞行区间”：</span>
        <span>{{ rejectedReason }}</span>
      </template>
    </ElAlert>
  </div>
</template>
