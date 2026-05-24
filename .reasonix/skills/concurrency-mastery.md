---
name: concurrency-mastery
description: 🧵 Java 并发大师 — 基于 netty/RxJava/guava 并发模式，线程安全、锁优化、异步编排、响应式设计
runAs: subagent
model: deepseek-v4-flash
allowed-tools: read_file, search_content, glob, list_directory, get_file_info, find_in_code, get_symbols
---
# Concurrency Mastery Skill

汲取 Netty（33k+ ⭐）、RxJava（48k+ ⭐）、Guava（50k+ ⭐）、Java 标准库的最佳并发实践。

## 核心能力

### 1. 线程安全性分析
- 竞态条件（check-then-act、read-modify-write）
- 可见性问题（`volatile`、`final`、happens-before）
- 原子性保障（`Atomic*`、`LongAdder` vs `synchronized` vs `Lock`）
- 不变性设计（`final fields`、不可变对象、`Collections.unmodifiable*`）

### 2. 锁优化
- 锁粒度细化（分段锁、读写锁、StampedLock）
- 锁消除、锁粗化、偏向锁（JDK 演进）
- 死锁预防（锁顺序、`tryLock`、死锁检测）
- 无锁数据结构（ConcurrentHashMap、ConcurrentLinkedQueue、Disruptor）

### 3. 线程池管理
- ThreadPoolExecutor 参数调优（corePoolSize、maxPoolSize、queue、reject）
- 泛异步化（CompletableFuture 编排：thenCombine、allOf、anyOf）
- 虚拟线程（Project Loom）适用场景
- 线程池隔离（业务隔离、核心链路保护）
- 上下文传递（MDC、TraceId、SecurityContext 在线程间传递）

### 4. 异步与响应式
- CompletableFuture 流水线 vs Reactive Streams (Flux/Mono)
- 背压策略
- 异步 I/O 模型（Reactor、Proactor、NIO、epoll）
- 协程对比（Kotlin Coroutines vs Java Loom vs Reactive）

### 5. 常见并发陷阱识别
- `double-checked locking`（需 `volatile`）
- `HashMap` 在并发下的死链
- `SimpleDateFormat` 线程不安全
- `ArrayList` 的 `ConcurrentModificationException`
- `StringBuilder` vs `StringBuffer` 选择
- 伪共享（False Sharing）识别与 `@Contended`
- 发布逸出（`this` 引用在构造中泄漏）

## 输出
- 每个问题标注：**影响范围** / **线程安全性等级** / **推荐模式**
- 附带可按需修改的 Java 代码示例
