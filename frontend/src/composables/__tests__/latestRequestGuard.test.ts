import { describe, expect, it } from 'vitest'
import { latestRequestGuard } from '../latestRequestGuard'

/**
 * 流水页并发读取的核心正确性：同一用户连续发起不同航线查询时，
 * 只允许最后一次结果更新页面——哪怕旧请求（航线甲）更晚返回。
 */
describe('latestRequestGuard', () => {
  it('乱序返回时只承认最后一次发起的请求', () => {
    const guard = latestRequestGuard()

    const tokenA = guard.begin() // 先选航线甲
    const tokenB = guard.begin() // 立刻改选航线乙（最后一次选择）

    // 甲的慢请求晚于乙返回
    const applyB = guard.isLatest(tokenB)
    const applyA = guard.isLatest(tokenA)

    expect(applyB).toBe(true)   // 乙可以更新页面
    expect(applyA).toBe(false)  // 甲是旧响应，必须丢弃，不能覆盖乙
  })

  it('连续翻页/刷新时只有最新一次生效', () => {
    const guard = latestRequestGuard()
    const t1 = guard.begin()
    const t2 = guard.begin()
    const t3 = guard.begin()

    expect(guard.isLatest(t1)).toBe(false)
    expect(guard.isLatest(t2)).toBe(false)
    expect(guard.isLatest(t3)).toBe(true)
  })

  it('连续导出只下载最后一次确认筛选对应的文件', () => {
    const guard = latestRequestGuard()
    const exportAll = guard.begin()     // 第一次导出：全部航线
    const exportRoute = guard.begin()   // 立刻改成航线乙再导出

    expect(guard.isLatest(exportAll)).toBe(false) // 旧导出结果丢弃
    expect(guard.isLatest(exportRoute)).toBe(true)
  })

  it('旧请求失败返回也不能覆盖较新请求的加载状态', () => {
    const guard = latestRequestGuard()
    const oldToken = guard.begin()
    const newToken = guard.begin()

    // 模拟 Logs.vue 的 catch/finally：旧请求失败
    let pageOverwritten = false
    if (guard.isLatest(oldToken)) pageOverwritten = true
    expect(pageOverwritten).toBe(false)

    // 新请求成功
    let applied = false
    if (guard.isLatest(newToken)) applied = true
    expect(applied).toBe(true)
  })
})
