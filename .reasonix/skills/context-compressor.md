---
name: context-compressor
description: 🧹 上下文压缩器 — 将当前会话发掘的项目知识提炼为 compact memory，清理聊天历史，释放上下文窗口
runAs: subagent
model: deepseek-v4-flash
allowed-tools: read_file, search_content, glob, list_directory, get_file_info, find_in_code, get_symbols, remember
---
# Context Compressor Skill

当上下文窗口接近填满时，调用此技能做一次"上下文压缩"。

## 工作流程

### 阶段 1：知识蒸馏
审查本会话中已发现的所有项目关键信息：
- 项目结构（模块、包、关键类）
- 架构决策和技术选型
- 已发现的 Bug 和待修复项
- 配置和环境信息
- 用户偏好（编码风格、命名规范、约定）

### 阶段 2：压缩存储
对每个关键发现，用 `remember` 工具写入一条 **极简** 记忆：
- `scope: "project"` — 项目级，不会污染全局
- `priority: "high"` — 高优先级，新会话自动加载
- `content` — 高度浓缩，每条 ≤ 300 字
- 固定格式：`[发现] 一句话描述 | 具体位置 | 当前状态`

### 阶段 3：清理建议
返回一条消息告诉用户：
- 已保存 N 条上下文记忆
- 建议执行 `/new` 开始新会话
- 新会话会自动加载这些记忆，你可以继续之前的工作

## 使用时机
- 上下文剩余不足 25% 时
- 完成一个大型重构/审查后
- 用户手动调用 `/skill context-compressor`

## 输出示例
```
✅ 上下文压缩完成！
保存了 4 条高优先级项目记忆：
1. [架构] ChatService 承担了 5 种职责，计划拆分为 ChatModelFactory + PromptBuilder + AgentFactory
2. [Bug] VectorIndexService 每次操作都 loadCollection，造成 N+1 次 Milvus 调用
3. [配置] DashScope API Key 存储在 application.yml，掩码日志暴露了前 8 位
4. [约定] 团队使用 GitFlow 分支模型，commit prefix 要求 feat/fix/refactor

建议执行 /new 开始新会话，这些记忆会自动加载。
```
