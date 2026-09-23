<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { ElContainer, ElAside, ElMenu, ElMenuItem, ElHeader, ElSelect, ElOption, ElTag } from 'element-plus'
import { useCurrentStaff } from './composables/useCurrentStaff'

const menuItems = [
  { path: '/anchor', label: '锚点管理' },
  { path: '/route', label: '航线管理' },
  { path: '/adapt', label: '适配校验' },
  { path: '/group-binding', label: '成组配桩预演' },
  { path: '/ground', label: '地勤资质' },
  { path: '/watch', label: '开航值守' },
  { path: '/route-entry', label: '航线入口' },
  { path: '/logs', label: '流水记录' }
]

const { staff, currentId, load, select, current } = useCurrentStaff()

onMounted(() => load(true))

const currentStaff = computed(() => current())
const isSafety = computed(() => currentStaff.value?.staffRole === 'SAFETY_OFFICER')
</script>

<template>
  <ElContainer class="h-screen">
    <ElAside width="200px" class="bg-slate-800 text-white">
      <div class="p-4 border-b border-slate-700">
        <h1 class="text-lg font-semibold text-white">动力伞基地系统</h1>
      </div>
      <ElMenu router class="h-full bg-slate-800 text-white">
        <ElMenuItem v-for="item in menuItems" :key="item.path" :index="item.path">
          {{ item.label }}
        </ElMenuItem>
      </ElMenu>
    </ElAside>
    <ElContainer>
      <ElHeader class="bg-white border-b border-gray-200 flex items-center justify-between px-6">
        <h2 class="text-lg font-semibold text-gray-800">{{ $route.name }}</h2>
        <div class="flex items-center gap-3">
          <span class="text-sm text-gray-500">当前操作人（服务端鉴权）</span>
          <ElSelect
            :model-value="currentId"
            placeholder="选择身份后操作"
            style="width: 220px"
            @change="(v: number | null) => select(v)"
          >
            <ElOption v-for="s in staff" :key="s.id" :label="`${s.staffName}（${s.staffRole === 'SAFETY_OFFICER' ? '安全主管' : '普通值班员'}）`" :value="s.id" />
          </ElSelect>
          <ElTag v-if="currentStaff" :type="isSafety ? 'danger' : 'info'">
            {{ isSafety ? '安全主管' : '普通值班员' }}
          </ElTag>
          <ElTag v-else type="warning">未登录（只读）</ElTag>
        </div>
      </ElHeader>
      <div class="p-6">
        <RouterView />
      </div>
    </ElContainer>
  </ElContainer>
</template>

<style scoped>
.el-menu {
  border-right: none;
}
</style>
