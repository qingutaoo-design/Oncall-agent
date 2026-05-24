---
name: architecture-review
description: 🏗️ 架构设计与审查 — 基于 java-design-patterns (90k⭐) 与 DDD/整洁架构/微服务设计原则
runAs: subagent
model: deepseek-v4-flash
allowed-tools: read_file, search_content, glob, list_directory, get_file_info, find_in_code, get_symbols
---
# Architecture Review Skill

汲取 iluwatar/java-design-patterns（90k+ ⭐）、clean architecture、DDD（Domain-Driven Design）、微服务架构精粹。

## 审查维度

### 1. 模块化与分层
- 包结构风格（按层 vs 按功能 vs 按领域 vs 六边形架构）
- 依赖方向（内聚方向、依赖倒置）
- 循环依赖检测与消除
- 模块间通信（同步调用 vs 事件驱动 vs 消息队列）
- 模块粒度（微服务拆分、Bounded Context 边界）

### 2. 设计模式应用
- 创建型：工厂方法 vs 抽象工厂 vs Builder（复杂对象）、Singleton（全局状态陷阱）
- 结构型：适配器（系统集成）、代理（AOP）、门面（统一入口）、装饰器（扩展）
- 行为型：策略（算法族切换）、模板方法（骨架）、观察者（事件通知）、责任链（Pipeline）
- 架构级：CQRS、Event Sourcing、Saga、Strangler Fig

### 3. 领域驱动设计
- Entity vs Value Object 区分
- Aggregate 设计（聚合根、一致性边界）
- Repository 模式（集合风格的持久化抽象）
- Domain Event 设计
- Application Service vs Domain Service 职责

### 4. 微服务架构
- 服务拆分原则（业务能力、子域、DDD 限界上下文）
- 服务间通信（REST vs gRPC vs 消息队列）
- 服务发现与配置管理（Nacos/Eureka/Consul）
- 容错模式（断路器、隔舱、超时、重试、限流）
- 可观测性（Logging/Tracing/Metrics 三驾马车）
- 数据一致性（最终一致性、Eventual Consistency、Saga）

### 5. 整洁架构/六边形架构
- 依赖规则（内层不依赖外层、接口适配器模式）
- Use Case（Interactor）设计
- 端口与适配器分离
- 框架无关性（框架在边界之外）

## 审查输出
```
## [⭕ CRITICAL / ⚠️ MAJOR / ℹ️ MINOR] 问题
### 现状描述
### 架构原则违反（引用具体原则）
### 推荐方案
### 重构成本（L/M/H）
### 参考模式链接
```
