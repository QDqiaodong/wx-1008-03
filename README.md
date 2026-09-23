# wx-1008 动力伞航线锚点适配系统

## 项目简介

动力伞航线锚点适配系统，包含 Spring Boot 后端、Vue/Vite 前端、MySQL 和 Redis。项目已统一为 UTF-8 编码，并通过 Docker Compose 固定端口交付。

## 端口

- 前端: http://localhost:3208 / http://127.0.0.1:3208
- 后端 API: http://localhost:3308/api
- MySQL: 127.0.0.1:3408
- Redis: 127.0.0.1:6508

## 构建与启动

```bash
cd /Users/Admin/Desktop/solo-0601/wx-0701/wx-组1/wx-1008
cd backend && mvn compile -q
cd ../frontend && npm install && npm run build
cd .. && docker compose up -d --build
```

也可以执行：

```bash
./start.sh
```

## Docker 构建缓存

- 后端 Dockerfile 先复制 `pom.xml` 和 `settings.xml` 并下载 Maven 依赖，再复制 `src` 编译。
- 前端 Dockerfile 先复制 `package.json` 并安装 npm 依赖，再复制源码执行构建。
- `.dockerignore` 排除了 `node_modules`、`dist`、`target`、日志、临时文件、截图和 IDE 配置。

## 飞行异常事件复盘（/incidents）

针对“飞行结束后的异常靠聊天记录追溯”的问题，新增事件复盘能力：

- **建事件即冻结快照**：关联已发生飞行的航线、开航值守（操作员/复核员）与涉及锚点，服务端在建立时冻结
  航线名称/风级、人员姓名、锚点编号/区域/状态/承重；之后修改人员档案、航线或锚点，事件仍显示事发时内容，
  详情中并排列出“当前资料”，可跳转管理页查看差异。
- **状态链**：草稿 → 调查中 → 待封存 → 已封存；已封存可由安全主管写明依据重新开启，再次封存后
  修订记录能完整呈现两次封存之间发生的动作。封存要求原因结论、纠正措施、负责人、期限四项齐备。
- **不可变修订**：进入调查中后的每次修订（操作者、时间、前后内容）只追加到 `incident_revision`；
  封存后的正文与原证据不能直接覆盖，更正必须带理由并生成新版本，修订记录支持新旧版本并排查看。
  状态变更与修订插入在同一事务，同生共死。
- **并发口径：整份事件版本冲突**（乐观版本号 + 行锁）。所有写请求必须携带 `expectedVersion`，
  两人同改时后到者收到 409，响应带冲突字段（你的值/最新值/最新改动人）与最新版本，由人决定放弃或
  基于最新内容重新编辑，绝不静默覆盖。不采用按字段自动合并的原因：事件正文是互为前提的完整调查报告，
  自动拼接会产生无人审阅过的语义撕裂版本，且会使封存整体闸门与“更正留痕”失效。
- **权限（服务端强制，直接请求后端同样受限）**：普通值班员只能查看自己报告、负责或作为操作员/复核员
  参与值守的事件；封存与重新开启仅安全主管。

接口前缀：`/api/incidents`（列表/详情/修订/建立/修订/start-investigation/submit-seal/back-investigating/seal/reopen）。
