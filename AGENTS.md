# Java Core 开发约定

- 中文文档和关键注释；禁止读取 docs/.chat，日常在 main，不自动 push / PR / tag / Release。
- 现行业务与路线以 docs/README.md 为入口；先核代码和固定版本，不从外部历史原型恢复旧规则。
- Core 独占 SQLite 事务和学习写入；JDK 21、Spring Boot 非 Web 装配、FSRS-6，公共词典只读。Main 负责 OS 能力，Browser 连接封存 A 并使用桌面 B，不导入/合并；2.0.0 才做登录/LMSP/云同步。
- schema 100 直接初始化，拒绝预发布测试库，不迁移或自动删除。未发布实验接口、旧基础 CRUD 和标签写能力已删除；个人编辑仅笔记+bookIds，正式领域与 LMCP 不携带个人释义或用户标签占位。结构变动必须单独审查。
- 运行隔离 Maven clean verify、python scripts/verify-docs.py、python scripts/verify-release.py；纯文档改动核路径和命令即可。宿主真 UI/Native/Browser 链路由 Desktop 在相同 Core 提交上验证，不冒称远端 CI 已通过。
- Spotless 使用 pom 中固定 Google 两空格规则；不格式化冻结 LMCP/domain 资源和黄金向量，不启用自动语义重构。
- 冻结协议只从协议仓验证后的规范同步，保留逐文件摘要；不单改消费者 wire 语义或伪造 runtimeImplemented。
- 每次交付更新 CHANGELOG.md、中文 git-commit-message.txt，实际本地提交且明确路径暂存。Core 先提交，Desktop 再同步子模块 main 与 gitlink。
- 本轮资料、缓存、端口与进程必须隔离；只清理明确自有资料，不按名称杀进程，不记录 token/私人正文，不抢键鼠或修改 OS 时间。
- 公开结果、使用、设计和开发文档全部维护在本仓 docs；过程日志/对照放工作区私有记录，不把绝对机器路径和讨论搬进公开 Git。
