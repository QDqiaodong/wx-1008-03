import axios, { type AxiosError, type AxiosResponse } from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/**
 * 每个写请求都带上“当前操作人”（顶栏选择，存 localStorage）。
 * 注意：只传人员ID，角色由后端按库中档案判定，前端改不了权限。
 */
request.interceptors.request.use((config) => {
  try {
    const raw = localStorage.getItem('px-current-staff-id')
    if (raw) {
      config.headers = config.headers ?? {}
      config.headers['X-Staff-Id'] = raw
    }
  } catch { /* ignore */ }
  return config
})

request.interceptors.response.use(
  (response: AxiosResponse) => {
    // 文件下载（导出）：直接放行完整响应，由调用方读取 header 与 Blob
    if (response.config.responseType === 'blob') {
      return response
    }
    const res = response.data
    if (res.code !== 200) {
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return res.data
  },
  async (error: AxiosError) => {
    // 导出失败时后端返回 JSON 错误体（在 Blob 里），解析出来给统一错误提示
    const errData = error.response?.data
    if (errData instanceof Blob && errData.type.includes('application/json')) {
      try {
        const parsed = JSON.parse(await errData.text()) as { message?: string }
        return Promise.reject(new Error(parsed.message || '导出失败'))
      } catch { /* fall through */ }
    }
    const res = error.response?.data as { message?: string; data?: unknown } | undefined
    if (res?.message) {
      // 事件版本冲突 409：携带结构化 payload（冲突字段 + 最新事件）
      const err = new Error(res.message) as Error & { conflict?: unknown; status?: number }
      if (error.response?.status === 409 && res.data) {
        err.conflict = res.data
      }
      err.status = error.response?.status
      return Promise.reject(err)
    }
    return Promise.reject(error)
  }
)

const get = <T>(url: string) => request.get<T>(url) as unknown as Promise<T>
const post = <T>(url: string, data?: unknown) => request.post<T>(url, data) as unknown as Promise<T>
const put = <T>(url: string, data?: unknown) => request.put<T>(url, data) as unknown as Promise<T>
const del = (url: string) => request.delete(url) as unknown as Promise<void>

export interface Anchor {
  id: number
  anchorCode: string
  maxWeight: number
  minWindSpeed: number
  maxWindSpeed: number
  locationDesc: string
  anchorZone?: string
  status: number
  createTime: string
  updateTime: string
}

export interface AnchorDTO {
  anchorCode: string
  maxWeight: number
  minWindSpeed: number
  maxWindSpeed: number
  locationDesc: string
  anchorZone?: string
}

export interface FlightRoute {
  id: number
  routeCode: string
  routeName: string
  routeGroup: string
  windSpeed: number
  windLevel: string
  description: string
  status: number
  createTime: string
  updateTime: string
}

export interface RouteDTO {
  routeCode: string
  routeName: string
  routeGroup: string
  windSpeed: number
  description: string
}

export interface AdaptResult {
  valid: boolean
  routeId: number
  anchorId: number
  bindId: number
  reason: string
  rebindCount: number
  unbindCount: number
  logIds: number[]
}

export interface RouteAnchor {
  id: number
  routeId: number
  anchorId: number
  bindTime: string
  unbindTime: string
  status: number
}

export interface AdaptLog {
  id: number
  routeId: number
  routeCode: string
  anchorId: number
  anchorCode: string
  operationType: string
  beforeWindSpeed: number
  afterWindSpeed: number
  beforeWeight: number
  afterWeight: number
  reason: string
  operator: string
  createTime: string
}

/**
 * 服务端返回的流水快照：total / pageNo / records 与 snapshotTime、snapshotMaxId
 * 对应同一个查询时刻。前端只负责展示，不得自行 sort、不得拿旧数组导出。
 */
export interface AdaptLogSnapshot {
  filter: { routeId: number | null }
  routeCode: string | null
  snapshotMaxId: number
  snapshotTime: string
  total: number
  pageNo: number
  pageSize: number
  totalPages: number
  sort: string
  records: AdaptLog[]
}

/** 导出文件服务端元信息（文件内容内，可离线复核） */
export interface AdaptLogExportMeta {
  exportedAt: string
  snapshotTime: string
  snapshotMaxId: number
  total: number
  sort: string
  note: string
}

export interface AdaptLogExportFile {
  /** OK-有命中；NO_MATCH-筛选有效但没有命中（与请求失败明确区分） */
  resultState: 'OK' | 'NO_MATCH'
  meta: AdaptLogExportMeta
  filter: { routeId: number | null; routeCode: string; description: string }
  records: AdaptLog[]
}

export interface GroupAnchorResult {
  anchorId: number
  anchorCode: string
  maxWeight: number
  minWindSpeed: number
  maxWindSpeed: number
  eligible: boolean
  failedChecks: string[]
  reasons: string[]
  occupiedByRouteCode: string | null
}

export interface GroupRehearseResult {
  routeId: number
  routeCode: string
  routeName: string
  routeWindSpeed: number
  windLevel: string
  requiredMinWeight: number
  totalCount: number
  eligibleCount: number
  rejectedCount: number
  eligibleTotalWeight: number
  requiredTotalWeight: number
  totalWeightBudgetOk: boolean
  groupValid: boolean
  policy: string
  policyNotice: string
  anchorResults: GroupAnchorResult[]
  weightRuleTable: Record<string, number>
}

export interface GroupSubmitResult {
  routeId: number
  routeCode: string
  committed: boolean
  bindIds: number[]
  submittedCount: number
  rehearsal: GroupRehearseResult
  message: string
  logIds: number[]
}

export const anchorApi = {
  list: () => get<Anchor[]>('/anchor'),
  get: (id: number) => get<Anchor>(`/anchor/${id}`),
  create: (data: AnchorDTO) => post<Anchor>('/anchor', data),
  update: (id: number, data: AnchorDTO) => put<Anchor>(`/anchor/${id}`, data),
  delete: (id: number) => del(`/anchor/${id}`),
  filter: (minWind: number, maxWind: number) => get<Anchor[]>(`/anchor/filter?minWind=${minWind}&maxWind=${maxWind}`)
}

export const routeApi = {
  list: () => get<FlightRoute[]>('/route'),
  get: (id: number) => get<FlightRoute>(`/route/${id}`),
  create: (data: RouteDTO) => post<FlightRoute>('/route', data),
  update: (id: number, data: RouteDTO) => put<FlightRoute>(`/route/${id}`, data),
  delete: (id: number) => del(`/route/${id}`),
  groups: () => get<string[]>('/route/groups')
}

export const adaptApi = {
  bind: (routeId: number, anchorId: number) => post<AdaptResult>('/adapt/bind', { routeId, anchorId }),
  unbind: (routeId: number, anchorId: number) => post<AdaptResult>('/adapt/unbind', { routeId, anchorId }),
  check: (routeId: number, anchorId: number) => get<AdaptResult>(`/adapt/check?routeId=${routeId}&anchorId=${anchorId}`),
  recheck: (routeId: number) => post<AdaptResult>(`/adapt/recheck/${routeId}`),
  /**
   * 流水分页快照。页码/总数/记录顺序由服务端在同一查询时刻冻结，
   * 前端不做排序。返回值带 snapshotTime / snapshotMaxId 供复核。
   */
  logsPage: (params: { routeId?: number; pageNo: number; pageSize: number }) => {
    const qs = new URLSearchParams()
    if (params.routeId) qs.set('routeId', String(params.routeId))
    qs.set('pageNo', String(params.pageNo))
    qs.set('pageSize', String(params.pageSize))
    return get<AdaptLogSnapshot>(`/adapt/logs?${qs.toString()}`)
  },
  /**
   * 服务端导出：必须传当前确认的筛选条件，由后端冻结快照并生成排序后的文件。
   * 返回完整响应（含 Content-Disposition 文件名与 Blob），前端不再拼旧数组。
   */
  exportLogs: (routeId?: number) => {
    const qs = new URLSearchParams()
    if (routeId) qs.set('routeId', String(routeId))
    const suffix = qs.toString()
    return request.get(`/adapt/logs/export${suffix ? `?${suffix}` : ''}`, { responseType: 'blob' })
      .then((res) => res as unknown as AxiosResponse<Blob>)
  },
  bound: (routeId: number) => get<RouteAnchor[]>(`/adapt/bound/${routeId}`)
}

export const groupBindingApi = {
  rules: () => get<Record<string, number>>('/group-binding/rules'),
  rehearse: (routeId: number, anchorIds: number[], operator?: string) =>
    post<GroupRehearseResult>('/group-binding/rehearse', { routeId, anchorIds, operator }),
  submit: (routeId: number, anchorIds: number[], operator?: string) =>
    post<GroupSubmitResult>('/group-binding/submit', { routeId, anchorIds, operator })
}

/* ==================== 地勤资质与开航值守 ==================== */

export type StaffRole = 'STATION_OFFICER' | 'SAFETY_OFFICER'
export type CertStatus = 'PENDING' | 'VALID' | 'EXPIRED' | 'REVOKED'
export type WatchStatus = 'DRAFT' | 'PENDING_REVIEW' | 'READY' | 'CANCELLED'

export interface GroundStaff {
  id: number
  staffCode: string
  staffName: string
  staffRole: StaffRole
  status: number
}

export interface GroundCert {
  id: number
  certNo: string
  staffId: number
  windLevels: string
  anchorZones: string
  effectiveDate: string
  expiryDate: string
  revoked: number
  revokeTime?: string
  revokeReason?: string
  revokedByName?: string
}

export interface CertView {
  id: number
  certNo: string
  staffId: number
  staffName: string
  windLevels: string[]
  anchorZones: string[]
  effectiveDate: string
  expiryDate: string
  status: CertStatus
  referenceTime: string
  revokeTime?: string
  revokeReason?: string
  revokedByName?: string
}

export interface Qualification {
  staffId: number
  staffName: string
  qualified: boolean
  activeCertNo: string | null
  activeCertStatus: 'VALID' | 'PENDING' | 'EXPIRED' | 'REVOKED' | 'NONE'
  coveredWindLevels: string[]
  coveredZones: string[]
  missingWindLevels: string[]
  missingZones: string[]
  detailMessages: string[]
}

export interface StaffView {
  id: number
  staffCode: string
  staffName: string
  staffRole: StaffRole
  staffRoleLabel: string
  status: number
  referenceTime: string
  certs: CertView[]
  qualification: Qualification | null
}

export interface GroundCertDTO {
  certNo: string
  staffId: number
  windLevels: string[]
  anchorZones: string[]
  effectiveDate: string
  expiryDate: string
}

export interface WatchUpsertDTO {
  routeId: number
  operatorId: number
  reviewerId: number
  /** ISO 本地日期时间，如 2026-09-23T23:30:00 */
  plannedTakeoff: string
  plannedEnd?: string | null
}

export interface WatchView {
  id: number
  routeId: number
  routeCode: string
  routeName: string
  flightDate: string
  plannedTakeoff: string
  plannedEnd: string | null
  status: WatchStatus
  statusLabel: string
  operatorArrived: number
  arrivalTime: string | null
  reviewerArrived: number
  reviewerArrivalTime: string | null
  readyTime: string | null
  readiedByName: string | null
  cancelTime: string | null
  cancelledByName: string | null
  cancelReason: string | null
  requalifyReason: string | null

  operatorId: number
  operatorName: string
  reviewerId: number
  reviewerName: string

  operatorQualification: Qualification | null
  reviewerQualification: Qualification | null
  requiredWindLevel: string | null
  requiredZones: string[] | null

  distinctPeople: boolean
  canReady: boolean
  past: boolean

  hasSnapshot: boolean
  snapshotTakeoff: string | null
  snapshotEnd: string | null
  snapshotRouteWindLevel: string | null
  snapshotRequiredZones: string[]
  operatorSnapshotName: string | null
  operatorSnapshotCertNo: string | null
  operatorSnapshotScope: string | null
  reviewerSnapshotName: string | null
  reviewerSnapshotCertNo: string | null
  reviewerSnapshotScope: string | null

  policy: string
}

export interface RouteWatchEntry {
  routeId: number
  routeCode: string
  routeName: string
  windLevel: string
  windSpeed: number
  status: number
  activeAnchorCodes: string[]
  requiredZones: string[]
  watchId: number | null
  watchStatus: WatchStatus | null
  watchStatusLabel: string | null
  flightDate: string | null
  operatorId: number | null
  operatorName: string | null
  reviewerId: number | null
  reviewerName: string | null
  operatorQualified: boolean
  reviewerQualified: boolean
  distinctPeople: boolean
  operatorArrived: number | null
  ready: boolean
  gapMessages: string[] | null
  policy: string
}

export interface GroundMeta {
  windLevels: string[]
  anchorZones: string[]
  policy: string
  rejectedReason: string
}

export const groundMetaApi = {
  get: () => get<GroundMeta>('/ground/meta')
}

export const staffApi = {
  list: (routeId?: number, takeoff?: string) => {
    const params = new URLSearchParams()
    if (routeId) params.set('routeId', String(routeId))
    if (takeoff) params.set('takeoff', takeoff)
    const qs = params.toString()
    return get<StaffView[]>(`/ground/staff${qs ? `?${qs}` : ''}`)
  },
  active: () => get<GroundStaff[]>('/ground/staff/active'),
  create: (data: { staffCode: string; staffName: string; staffRole: StaffRole }) =>
    post<GroundStaff>('/ground/staff', data),
  update: (id: number, data: { staffCode: string; staffName: string; staffRole: StaffRole }) =>
    put<GroundStaff>(`/ground/staff/${id}`, data),
  disable: (id: number) => del(`/ground/staff/${id}`),
  policy: () => get<{ policy: string; rejectedReason: string }>('/ground/staff/policy')
}

export const certApi = {
  create: (data: GroundCertDTO) => post<GroundCert>('/ground/cert', data),
  revoke: (id: number, reason: string) =>
    post<{ requalifiedWatches: number }>(`/ground/cert/${id}/revoke`, { reason })
}

export const watchApi = {
  list: (routeId?: number) => get<WatchView[]>(`/watch${routeId ? `?routeId=${routeId}` : ''}`),
  get: (id: number) => get<WatchView>(`/watch/${id}`),
  routeEntries: (takeoff?: string) =>
    get<RouteWatchEntry[]>(`/watch/route-entries${takeoff ? `?takeoff=${encodeURIComponent(takeoff)}` : ''}`),
  create: (data: WatchUpsertDTO) => post<FlightWatchEntity>('/watch', data),
  update: (id: number, data: WatchUpsertDTO) => put<FlightWatchEntity>(`/watch/${id}`, data),
  operatorArrive: (id: number) => post<FlightWatchEntity>(`/watch/${id}/operator-arrive`),
  operatorWithdraw: (id: number) => post<FlightWatchEntity>(`/watch/${id}/operator-withdraw`),
  ready: (id: number) => post<FlightWatchEntity>(`/watch/${id}/ready`),
  cancel: (id: number, reason: string) => post<FlightWatchEntity>(`/watch/${id}/cancel`, { reason })
}

export interface FlightWatchEntity {
  id: number
  status: WatchStatus
}

/* ==================== 飞行异常事件复盘 ==================== */

export type IncidentStatus = 'DRAFT' | 'INVESTIGATING' | 'PENDING_SEAL' | 'SEALED' | 'REOPENED'
export type IncidentSeverity = 'MINOR' | 'MAJOR' | 'CRITICAL'

/** 事件整份正文——修订记录 before/after 也是这个结构（后端存 JSON 原文） */
export interface IncidentContent {
  title: string
  severity: IncidentSeverity
  /** ISO 本地时间 2026-09-23T14:30:00 */
  foundTime: string
  sceneNarrative: string | null
  handlingActions: string | null
  evidenceNote: string | null
  causeConclusion: string | null
  correctiveAction: string | null
  ownerId: number | null
  dueDate: string | null
}

export interface IncidentCreateDTO {
  title: string
  severity: IncidentSeverity
  foundTime: string
  routeId: number
  watchId?: number | null
  anchorIds?: number[]
  involvedStaffIds?: number[]
  sceneNarrative?: string
  handlingActions?: string
  evidenceNote?: string
  causeConclusion?: string
  correctiveAction?: string
  ownerId?: number | null
  dueDate?: string | null
}

export interface IncidentEditDTO {
  expectedVersion: number
  reason?: string
  content: IncidentContent
}

export interface IncidentTransitionDTO {
  expectedVersion: number
  basis?: string
}

export interface IncidentAnchorView {
  anchorId: number
  anchorCode: string
  locationDesc: string | null
  anchorZone: string | null
  anchorStatus: number | null
  anchorStatusLabel: string
  maxWeight: number | null
  minWindSpeed: number | null
  maxWindSpeed: number | null
  currentExists: boolean
  currentAnchorCode?: string
  currentLocationDesc?: string | null
  currentAnchorZone?: string | null
  currentAnchorStatus?: number | null
  currentAnchorStatusLabel?: string
  currentMaxWeight?: number | null
  currentMinWindSpeed?: number | null
  currentMaxWindSpeed?: number | null
  diffFields: string[]
}

export interface IncidentRevisionView {
  id: number
  seq: number
  revisionType: string
  revisionTypeLabel: string
  fromStatus: string | null
  fromStatusLabel: string | null
  toStatus: string | null
  toStatusLabel: string | null
  operatorId: number
  operatorName: string
  operatorRole: string
  operatorRoleLabel: string
  reason: string | null
  beforeContent: string | null
  afterContent: string | null
  changedFields: string[]
  sealRound: number
  operateTime: string
}

export interface IncidentSealRoundView {
  roundNo: number
  sealTime: string
  sealedById: number
  sealedByName: string
  sealedVersion: number
  reopenTime: string | null
  reopenedById: number | null
  reopenedByName: string | null
  reopenBasis: string | null
  reopenVersion: number | null
  reopened: boolean
}

export interface IncidentView {
  id: number
  incidentCode: string
  title: string
  status: IncidentStatus
  statusLabel: string
  version: number
  severity: IncidentSeverity
  severityLabel: string
  foundTime: string
  sceneNarrative: string | null
  handlingActions: string | null
  evidenceNote: string | null
  causeConclusion: string | null
  correctiveAction: string | null
  ownerId: number | null
  ownerName: string | null
  dueDate: string | null

  routeId: number
  snapRouteCode: string
  snapRouteName: string
  snapRouteWindLevel: string | null
  snapRouteWindSpeed: number | null
  currentRouteExists: boolean
  currentRouteCode?: string
  currentRouteName?: string
  currentRouteWindLevel?: string | null
  currentRouteWindSpeed?: number | null
  currentRouteStatus?: number
  routeDiffFields: string[]

  watchId: number | null
  snapWatchTakeoff: string | null
  snapWatchEnd: string | null
  snapWatchOperatorId: number | null
  snapWatchOperatorName: string | null
  snapWatchReviewerId: number | null
  snapWatchReviewerName: string | null
  currentWatchExists: boolean
  currentWatchStatus?: string
  currentWatchStatusLabel?: string
  currentWatchOperatorName?: string | null
  currentWatchReviewerName?: string | null
  watchDiffFields: string[]

  anchorSnapshots: IncidentAnchorView[]

  reporterId: number
  reporterName: string
  involvedStaffIds: number[]
  involvedStaffNames: string | null

  currentSealRound: number
  revisions: IncidentRevisionView[]
  sealRounds: IncidentSealRoundView[]

  canEditContent: boolean
  canEnterInvestigating: boolean
  canRequestSeal: boolean
  canBackToInvestigating: boolean
  canSeal: boolean
  canReopen: boolean
  sealed: boolean

  createTime: string
  updateTime: string
  policy: string
  rejectedAlternative: string
}

export interface IncidentListItem {
  id: number
  incidentCode: string
  title: string
  status: IncidentStatus
  statusLabel: string
  version: number
  severity: IncidentSeverity
  severityLabel: string
  foundTime: string
  snapRouteCode: string
  snapRouteName: string
  snapRouteWindLevel: string | null
  reporterName: string
  involvedStaffNames: string | null
  currentSealRound: number
  revisionCount: number
  createTime: string
  updateTime: string
}

/** 409 冲突响应 data */
export interface IncidentConflict {
  incidentId: number
  incidentCode: string
  expectedVersion: number
  latestVersion: number
  latestStatus: IncidentStatus
  latestStatusLabel: string
  conflictFields: string[]
  latest: IncidentView
}

export interface ApiError extends Error {
  conflict?: IncidentConflict
  status?: number
}

export const incidentApi = {
  list: () => get<IncidentListItem[]>('/incident'),
  get: (id: number) => get<IncidentView>(`/incident/${id}`),
  create: (data: IncidentCreateDTO) => post<IncidentView>('/incident', data),
  edit: (id: number, data: IncidentEditDTO) => post<IncidentView>(`/incident/${id}/edit`, data),
  enterInvestigating: (id: number, data: IncidentTransitionDTO) =>
    post<IncidentView>(`/incident/${id}/enter-investigating`, data),
  requestSeal: (id: number, data: IncidentTransitionDTO) =>
    post<IncidentView>(`/incident/${id}/request-seal`, data),
  backInvestigating: (id: number, data: IncidentTransitionDTO) =>
    post<IncidentView>(`/incident/${id}/back-investigating`, data),
  seal: (id: number, data: IncidentTransitionDTO) =>
    post<IncidentView>(`/incident/${id}/seal`, data),
  reopen: (id: number, data: IncidentTransitionDTO) =>
    post<IncidentView>(`/incident/${id}/reopen`, data)
}
