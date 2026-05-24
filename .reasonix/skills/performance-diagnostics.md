---
name: performance-diagnostics
description: 🔥 生产级 JVM 性能诊断 — Arthas/async-profiler/JMC 方法论，线程栈分析、内存泄漏、GC 调优、热点定位
runAs: subagent
model: deepseek-v4-flash
allowed-tools: read_file, search_content, glob, list_directory, get_file_info, find_in_code, get_symbols, run_command
---
# Performance Diagnostics Skill

以阿里巴巴 Arthas（36k+ ⭐）、async-profiler、JMC 等主流诊断工具的方法论为基础，对 Java 后端应用进行系统性性能诊断。

## 诊断维度

### 1. CPU 热点
- 火焰图分析（on-CPU / off-CPU）
- 线程 CPU 占用排名
- 热点方法定位（`thread -n 3`、`profiler start`）
- 锁竞争导致的 CPU 飙升识别

### 2. 内存问题
- 堆内存泄漏分析（`heap dump` → MAT/JProfiler 分析）
- GC 频率和停顿分析（`vmoption`、GC 日志）
- 元空间 / 堆外内存泄漏
- 大对象 / 线程本地分配缓冲（TLAB）问题

### 3. 线程问题
- 死锁检测（`thread -b`）
- 线程池诊断（活跃线程、队列积压、拒绝策略）
- 阻塞分析（`thread --state BLOCKED`）
- 虚假唤醒 / 信号丢失

### 4. 数据库层
- N+1 查询发现
- 慢 SQL 定位（连接池监控、SQL 审计）
- 连接池耗尽诊断（HikariCP 监控）
- 事务过长 / 锁超时

### 5. I/O 与网络
- 文件 I/O 吞吐瓶颈
- Socket 连接泄漏
- HTTP 调用延迟分布

## 输出模板
```
## [严重度: CRITICAL/MAJOR/MINOR] 问题标题
### 症状
### 根因分析
### 验证命令（Arthas / jstack / jstat 等）
### 修复建议
### 预防措施
```

## 参考工具
- Arthas: `trace`, `watch`, `tt`, `monitor`, `thread`, `profiler`, `heapdump`, `vmtool`
- async-profiler: CPU / Alloc / Lock 火焰图
- JDK 自带: `jstat`, `jstack`, `jmap`, `jcmd`, `jhsdb`, `jfr`
- 可视工具: JMC, VisualVM, MAT, GCeasy
