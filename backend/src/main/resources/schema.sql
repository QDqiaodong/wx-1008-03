SET NAMES utf8mb4;
SET character_set_client = utf8mb4;

CREATE TABLE IF NOT EXISTS anchor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    anchor_code VARCHAR(50) UNIQUE NOT NULL COMMENT '锚点编号',
    max_weight DECIMAL(10,2) NOT NULL COMMENT '最大承重(kg)',
    min_wind_speed DECIMAL(5,2) NOT NULL COMMENT '适配气流下限(m/s)',
    max_wind_speed DECIMAL(5,2) NOT NULL COMMENT '适配气流上限(m/s)',
    location_desc VARCHAR(200) COMMENT '位置描述',
    status TINYINT DEFAULT 1 COMMENT '状态:0-停用,1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_anchor_code (anchor_code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地面固定锚点表';

CREATE TABLE IF NOT EXISTS flight_route (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_code VARCHAR(50) UNIQUE NOT NULL COMMENT '航线编号',
    route_name VARCHAR(100) NOT NULL COMMENT '航线名称',
    route_group VARCHAR(50) NOT NULL COMMENT '航线分组',
    wind_speed DECIMAL(5,2) NOT NULL COMMENT '当前气流强度(m/s)',
    wind_level VARCHAR(20) COMMENT '气流等级',
    description VARCHAR(500) COMMENT '航线描述',
    status TINYINT DEFAULT 1 COMMENT '状态:0-停用,1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_route_code (route_code),
    INDEX idx_route_group (route_group),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动力伞航线表';

CREATE TABLE IF NOT EXISTS route_anchor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL COMMENT '航线ID',
    anchor_id BIGINT NOT NULL COMMENT '锚点ID',
    bind_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    unbind_time DATETIME COMMENT '解绑时间',
    status TINYINT DEFAULT 1 COMMENT '状态:0-解绑,1-绑定',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_route_id (route_id),
    INDEX idx_anchor_id (anchor_id),
    INDEX idx_status (status),
    UNIQUE KEY uk_route_anchor (route_id, anchor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='航线锚点绑定表';

-- 锚点唯一主占表：anchor_id 作主键，从数据库层强制"一个锚点同一时间只服役一条启用航线"
CREATE TABLE IF NOT EXISTS anchor_occupancy (
    anchor_id BIGINT PRIMARY KEY COMMENT '锚点ID(主键即唯一占用约束)',
    route_id BIGINT NOT NULL COMMENT '当前服役航线ID',
    route_code VARCHAR(50) NOT NULL COMMENT '当前服役航线编号',
    bind_id BIGINT COMMENT '对应绑定关系ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
    INDEX idx_occ_route_id (route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='锚点唯一主占表';

CREATE TABLE IF NOT EXISTS adapt_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL COMMENT '航线ID',
    route_code VARCHAR(50) NOT NULL COMMENT '航线编号',
    anchor_id BIGINT NOT NULL COMMENT '锚点ID',
    anchor_code VARCHAR(50) NOT NULL COMMENT '锚点编号',
    operation_type VARCHAR(20) NOT NULL COMMENT '操作类型:BIND/UNBIND/REBIND',
    before_wind_speed DECIMAL(5,2) COMMENT '操作前气流强度',
    after_wind_speed DECIMAL(5,2) COMMENT '操作后气流强度',
    before_weight DECIMAL(10,2) COMMENT '操作前锚点承重',
    after_weight DECIMAL(10,2) COMMENT '操作后锚点承重',
    reason VARCHAR(500) COMMENT '操作原因',
    operator VARCHAR(50) COMMENT '操作人',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_route_id (route_id),
    INDEX idx_anchor_id (anchor_id),
    INDEX idx_operation_type (operation_type),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='适配调整流水表';

-- ==================== 地勤资质与开航值守 ====================
-- 说明：anchor 表新增的 anchor_zone 列由 JPA ddl-auto=update（默认/dev/local）自动添加；
-- 生产(ddl-auto=validate, sql.init.mode=never)由 DBA 按下述建表 DDL 预先建表/加列：
--   ALTER TABLE anchor ADD COLUMN anchor_zone VARCHAR(50) NULL COMMENT '所属锚点区域' AFTER location_desc;

CREATE TABLE IF NOT EXISTS ground_staff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_code VARCHAR(50) UNIQUE NOT NULL COMMENT '人员编号',
    staff_name VARCHAR(50) NOT NULL COMMENT '姓名',
    staff_role VARCHAR(20) NOT NULL COMMENT '角色:STATION_OFFICER-普通值班员,SAFETY_OFFICER-安全主管',
    status TINYINT DEFAULT 1 COMMENT '状态:0-停用,1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_staff_role (staff_role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地勤人员表';

CREATE TABLE IF NOT EXISTS ground_cert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cert_no VARCHAR(50) UNIQUE NOT NULL COMMENT '资质证编号',
    staff_id BIGINT NOT NULL COMMENT '持证人员ID',
    wind_levels VARCHAR(200) NOT NULL COMMENT '适用风级(逗号分隔)',
    anchor_zones VARCHAR(500) NOT NULL COMMENT '可负责锚点区域(逗号分隔)',
    effective_date DATE NOT NULL COMMENT '生效日',
    expiry_date DATE NOT NULL COMMENT '到期日',
    revoked TINYINT DEFAULT 0 COMMENT '是否吊销:0-否,1-是(终态)',
    revoke_time DATETIME COMMENT '吊销时间',
    revoke_reason VARCHAR(300) COMMENT '吊销原因',
    revoked_by_id BIGINT COMMENT '吊销操作人ID',
    revoked_by_name VARCHAR(50) COMMENT '吊销操作人姓名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_cert_staff (staff_id),
    INDEX idx_cert_revoked (revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地勤资质证表';

CREATE TABLE IF NOT EXISTS flight_watch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL COMMENT '航线ID',
    route_code VARCHAR(50) COMMENT '航线编号(冗余)',
    flight_date DATE NOT NULL COMMENT '飞行日(取起飞时刻所在自然日)',
    planned_takeoff DATETIME NOT NULL COMMENT '预计起飞时刻(跨午夜判定锚点)',
    planned_end DATETIME COMMENT '预计结束时刻(可跨午夜,不参与证书判定)',
    operator_id BIGINT NOT NULL COMMENT '操作员ID',
    operator_name VARCHAR(50) COMMENT '操作员姓名(排班当期)',
    reviewer_id BIGINT NOT NULL COMMENT '复核员ID',
    reviewer_name VARCHAR(50) COMMENT '复核员姓名(排班当期)',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态:DRAFT/PENDING_REVIEW/READY/CANCELLED',
    operator_arrived TINYINT DEFAULT 0 COMMENT '操作员是否已确认到位',
    arrival_time DATETIME COMMENT '操作员到位时间',
    reviewer_arrived TINYINT DEFAULT 0 COMMENT '复核员到位标记',
    reviewer_arrival_time DATETIME COMMENT '复核员到位时间',
    ready_time DATETIME COMMENT '就绪时间',
    readied_by_id BIGINT COMMENT '就绪确认人ID',
    readied_by_name VARCHAR(50) COMMENT '就绪确认人姓名',
    cancel_time DATETIME COMMENT '取消时间',
    cancelled_by_id BIGINT COMMENT '取消人ID',
    cancelled_by_name VARCHAR(50) COMMENT '取消人姓名',
    cancel_reason VARCHAR(300) COMMENT '取消原因',
    requalify_reason VARCHAR(1000) COMMENT '最近重新判定打回原因',
    snapshot_takeoff DATETIME COMMENT '快照-起飞时刻',
    snapshot_end DATETIME COMMENT '快照-结束时刻',
    snapshot_route_wind_level VARCHAR(20) COMMENT '快照-航线风级',
    snapshot_required_zones VARCHAR(500) COMMENT '快照-要求区域',
    operator_snapshot_name VARCHAR(50) COMMENT '快照-操作员姓名',
    operator_snapshot_cert_no VARCHAR(50) COMMENT '快照-操作员证书编号',
    operator_snapshot_scope VARCHAR(1000) COMMENT '快照-操作员证书适用范围',
    reviewer_snapshot_name VARCHAR(50) COMMENT '快照-复核员姓名',
    reviewer_snapshot_cert_no VARCHAR(50) COMMENT '快照-复核员证书编号',
    reviewer_snapshot_scope VARCHAR(1000) COMMENT '快照-复核员证书适用范围',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_watch_route (route_id),
    INDEX idx_watch_date (flight_date),
    INDEX idx_watch_status (status),
    INDEX idx_watch_operator (operator_id),
    INDEX idx_watch_reviewer (reviewer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='开航值守安排表';

-- ==================== 飞行异常事件复盘 ====================
-- 口径：整份事件版本冲突（乐观锁）。incident.version 每产生一次修订 +1，
-- 编辑必须携带所依据的 expectedVersion，不一致返回 409 与冲突字段，绝不静默覆盖。
-- 修订只追加（incident_revision），永不更新/删除；状态变更与修订插入同一事务。

CREATE TABLE IF NOT EXISTS incident (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_no VARCHAR(50) UNIQUE NOT NULL COMMENT '事件编号',
    title VARCHAR(200) NOT NULL COMMENT '事件标题',
    watch_id BIGINT COMMENT '关联值守安排ID',
    route_id BIGINT NOT NULL COMMENT '关联航线ID(仅跳转当前资料用)',
    found_time DATETIME NOT NULL COMMENT '异常发现时间',
    severity VARCHAR(20) NOT NULL COMMENT '严重级别:MINOR-一般/MAJOR-较大/CRITICAL-严重',
    incident_note VARCHAR(4000) NOT NULL COMMENT '现场经过',
    handling_action VARCHAR(4000) COMMENT '处置动作',
    evidence_desc VARCHAR(2000) COMMENT '证据说明',
    root_cause VARCHAR(4000) COMMENT '原因结论(封存前必填)',
    corrective_action VARCHAR(4000) COMMENT '纠正措施(封存前必填)',
    owner_id BIGINT COMMENT '整改负责人ID(封存前必填)',
    owner_name VARCHAR(50) COMMENT '整改负责人姓名(冗余)',
    due_date DATE COMMENT '整改期限(封存前必填)',
    reporter_id BIGINT NOT NULL COMMENT '报告人ID',
    reporter_name VARCHAR(50) NOT NULL COMMENT '报告人姓名(建事件时冻结)',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/INVESTIGATING/PENDING_SEAL/SEALED/REOPENED',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本：每次修订+1，编辑必须携带expectedVersion',
    sealed_count INT NOT NULL DEFAULT 0 COMMENT '封存次数(区分首次封存与重新开启后的再次封存)',
    current_revision_id BIGINT COMMENT '当前正文对应的修订版本ID',
    first_seal_time DATETIME COMMENT '首次封存时间',
    last_seal_time DATETIME COMMENT '最近一次封存时间',
    sealed_by_id BIGINT COMMENT '最近封存人ID',
    sealed_by_name VARCHAR(50) COMMENT '最近封存人姓名',
    snapshot_route_code VARCHAR(50) COMMENT '快照-事发航线编号',
    snapshot_route_name VARCHAR(100) COMMENT '快照-事发航线名称',
    snapshot_route_wind_level VARCHAR(20) COMMENT '快照-事发航线风级',
    snapshot_route_group VARCHAR(50) COMMENT '快照-事发航线分组',
    snapshot_watch_flight_date VARCHAR(20) COMMENT '快照-值守飞行日',
    snapshot_watch_takeoff VARCHAR(30) COMMENT '快照-值守预计起飞时刻',
    snapshot_operator_name VARCHAR(50) COMMENT '快照-值守操作员姓名(事发时)',
    snapshot_reviewer_name VARCHAR(50) COMMENT '快照-值守复核员姓名(事发时)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_incident_watch (watch_id),
    INDEX idx_incident_route (route_id),
    INDEX idx_incident_reporter (reporter_id),
    INDEX idx_incident_owner (owner_id),
    INDEX idx_incident_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='飞行异常事件主表';

-- 事件关联锚点（多对多快照行：锚点关键状态在建事件时冻结）
CREATE TABLE IF NOT EXISTS incident_anchor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL COMMENT '事件ID',
    anchor_id BIGINT NOT NULL COMMENT '锚点ID(仅跳转当前资料用)',
    anchor_code VARCHAR(50) NOT NULL COMMENT '锚点编号(快照)',
    location_desc VARCHAR(200) COMMENT '位置描述(快照)',
    anchor_zone VARCHAR(50) COMMENT '所属区域(快照)',
    status_snapshot VARCHAR(20) NOT NULL COMMENT '事发时锚点状态:启用/停用(快照)',
    max_weight DECIMAL(10,2) COMMENT '事发时最大承重(快照)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_ia_incident (incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件关联锚点快照表';

-- 不可变修订流水：只追加。封存后正文/原证据不能覆盖，更正只能新增带理由的修订。
CREATE TABLE IF NOT EXISTS incident_revision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL COMMENT '事件ID',
    revision_no INT NOT NULL COMMENT '版本序号(从1起，与incident.version一致)',
    change_type VARCHAR(30) NOT NULL COMMENT 'CREATE/EDIT/SEAL/CORRECTION/REOPEN',
    change_reason VARCHAR(1000) COMMENT '修订理由(封存后更正与重新开启必填)',
    -- 该版本完整正文（before/after 由相邻两行天然构成，行本身不可变）
    title VARCHAR(200) NOT NULL,
    found_time DATETIME NOT NULL,
    severity VARCHAR(20) NOT NULL,
    incident_note VARCHAR(4000) NOT NULL,
    handling_action VARCHAR(4000),
    evidence_desc VARCHAR(2000),
    root_cause VARCHAR(4000),
    corrective_action VARCHAR(4000),
    owner_id BIGINT,
    owner_name VARCHAR(50),
    due_date DATE,
    status_before VARCHAR(20) COMMENT '修订前状态',
    status_after VARCHAR(20) COMMENT '修订后状态',
    operator_id BIGINT NOT NULL COMMENT '操作者ID',
    operator_name VARCHAR(50) NOT NULL COMMENT '操作者姓名',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '修订时间',
    UNIQUE KEY uk_rev_incident_no (incident_id, revision_no),
    INDEX idx_rev_incident (incident_id),
    INDEX idx_rev_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件不可变修订流水表';
