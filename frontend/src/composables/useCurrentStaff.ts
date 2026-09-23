import { ref } from 'vue'
import { staffApi, type GroundStaff } from '../api'

/**
 * 全局当前操作人（单例 ref）。选择后只把人员ID存 localStorage 并随请求头带上，
 * 真正的角色/权限以后端档案为准——这里的角色标签仅用于页面提示与按钮显隐，
 * 不是安全边界。
 */
const staff = ref<GroundStaff[]>([])
const currentId = ref<number | null>(loadId())
const loaded = ref(false)

function loadId(): number | null {
  try {
    const raw = localStorage.getItem('px-current-staff-id')
    return raw ? Number(raw) : null
  } catch {
    return null
  }
}

export function useCurrentStaff() {
  async function load(force = false) {
    if (loaded.value && !force) return
    try {
      staff.value = await staffApi.active()
      loaded.value = true
      if (currentId.value && !staff.value.some(s => s.id === currentId.value)) {
        currentId.value = null
        localStorage.removeItem('px-current-staff-id')
      }
    } catch { /* 查询可匿名，失败不阻塞 */ }
  }

  function select(id: number | null) {
    currentId.value = id
    if (id == null) {
      localStorage.removeItem('px-current-staff-id')
    } else {
      localStorage.setItem('px-current-staff-id', String(id))
    }
  }

  function current(): GroundStaff | null {
    return staff.value.find(s => s.id === currentId.value) ?? null
  }

  return { staff, currentId, loaded, load, select, current }
}
