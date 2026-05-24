---
name: data-design
description: 💾 数据库设计与 SQL 优化 — 基于 shardingsphere/druid/mybatis 最佳实践，索引策略、分库分表、连接池调优
runAs: subagent
model: deepseek-v4-flash
allowed-tools: read_file, search_content, glob, list_directory, get_file_info, find_in_code, get_symbols, run_command
---
# Data Design Skill

融合 Apache ShardingSphere（44k+ ⭐）、Druid（28k+ ⭐）、MyBatis、HikariCP 等生态的高 star 项目最佳实践，专注于数据层设计与优化。

## 分析领域

### 1. 数据库表设计
- 范式化与反范式化平衡
- 字段类型选择（数值 vs 字符串、char vs varchar、datetime vs timestamp）
- 主键策略（自增、雪花ID、UUID、雪花算法对比）
- 索引设计（复合索引顺序、覆盖索引、函数索引、部分索引）
- 大表拆分策略（垂直拆分、水平拆分、冷热分离）
- 约束设计（NOT NULL、默认值、CHECK 约束）

### 2. SQL 优化
- 执行计划分析（`EXPLAIN` 关注 type、rows、Extra）
- 索引下推、索引覆盖、MRR、BKA 等优化技术
- JOIN 策略（Nested Loop vs Hash Join vs Merge Join）
- 子查询优化（EXISTS vs IN、派生表优化）
- 分页优化（延迟关联、游标分页、Offset 深分页）
- 批量操作优化（batch insert/update、rewriteBatchedStatements）
- 悲观锁与乐观锁使用场景

### 3. 连接池与事务
- HikariCP 参数调优（maximumPoolSize、connectionTimeout、idleTimeout）
- 事务边界设计（@Transactional 正确使用、传播行为）
- 分布式事务方案（XA、TCC、SAGA、Seata）
- 读写分离与主从延迟处理

### 4. 分库分表
- 分片键选择（避免跨分片 JOIN、分布式事务）
- 分片算法（取模、Hash、Range、时间分片）
- 分布式 ID 生成（Snowflake、Segment、Leaf）
- 跨分片查询与聚合
- 数据迁移与扩容方案

### 5. NoSQL 决策
- Redis 缓存策略（缓存穿透、击穿、雪崩、bigkey、热key）
- Elasticsearch 搜索场景 vs MySQL 全文索引
- MongoDB 文档模型设计

## 输出格式
按严重度列出每个问题：影响分析 → 推荐方案 → 代码/DDL 示例 → 性能预期提升
