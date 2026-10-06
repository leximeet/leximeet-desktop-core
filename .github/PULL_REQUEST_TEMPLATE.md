## 问题与改动

说明触发场景和改后行为；协议或数据结构变化请明确指出。

## 验证

- [ ] `mvn clean verify`（仓内公开 settings 与独立 `.runtime/m2`）
- [ ] `python scripts/verify-docs.py`
- [ ] `python scripts/verify-release.py`
- [ ] 相关失败/重复/事务边界回归；涉及宿主行为时验证相同 Core 的 Desktop/Browser 链路
- [ ] 已更新当前 docs、CHANGELOG 与许可/版本边界，无私人资料或原始日志

列出未验证平台和人工门槛。
