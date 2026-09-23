/**
 * 串行化“最后一次请求生效”的并发守卫。
 *
 * 场景：流水页快速从航线甲改选航线乙、翻页、刷新，或者连续点击导出。
 * 请求可能乱序返回（甲比乙慢），若谁先返回谁就更新页面，甲的旧结果会覆盖
 * 乙的新筛选。这里用单调递增序号保证：
 *  - begin() 取号；响应回来时用 isLatest(token) 判断，落后的旧响应一律丢弃；
 *  - 这是数据正确性的最后一道闸，不依赖按钮 disabled / loading 状态
 *   （页面禁用只是体验，禁用按钮挡不住直接调用和竞态返回）。
 */
export function latestRequestGuard() {
  let seq = 0

  const begin = (): number => {
    seq += 1
    return seq
  }

  const isLatest = (token: number): boolean => token === seq

  /** 当前已发起但尚未全部作废的序号（测试/调试用） */
  const current = (): number => seq

  return { begin, isLatest, current }
}
