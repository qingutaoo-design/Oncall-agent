# ⚡ Embedding 多级缓存：减少重复 API 调用

> ⏱ 日期：2026-05-24
> 🎯 目标：相同的文档内容不要反复调 Embedding API，省成本、提速度
> 📉 效果：重复内容 Embedding 缓存命中率 30%～80%，节省 API 费用

---

## 🤔 为什么需要 Embedding 缓存？

### 问题场景

想象一下这个流程：

```
用户上传 10 篇文档
  → 每篇文档被切分成 20 个 chunk（分片）
  → 每个 chunk 都要调 DashScope Embedding API 生成向量
  → 一共 200 次 API 调用
```

**还没完。如果用户：**
- 上传了**和之前重复的文档**
- 把同一份文档**删掉重新索引**
- 问了一个和之前**一模一样的 query**

**Embedding API 又会被调用一次。** 每次调用都要花钱（虽然单价低，但积少成多）、每次都要等几百毫秒。

### 一句话解释缓存

> **缓存 = 记住上次计算结果，下次直接用**

就像你做完一道数学题，把答案记在小本子上——下次遇到一样的题，直接抄答案，不用再算一遍。

---

## 🏗️ 改造思路

```
改造前：每次都调 API
  用户请求 → DashScope API → 返回向量
             ↑ 每次都调，重复的内容也调
  
改造后：先查缓存
  用户请求 → 查缓存（key=内容原文）
             ├─ 命中了 → 直接返回向量（0 元，0 毫秒）
             └─ 没命中 → DashScope API → 存缓存 → 返回向量
```

**缓存 key 用「内容原文」本身：**
- 同一份文档的不同 chunk，内容不会完全相同
- 用户问相同问题时，query 向量直接从缓存拿
- Java 的 `String` 会自动缓存 `hashCode`，所以当 key 无性能开销

---

## 🔧 改了哪些文件？

| 文件 | 改动内容 | 难度 |
|------|----------|------|
| `pom.xml` | 加一行 Caffeine 依赖 | ⭐ |
| `application.yml` | 加 3 行缓存配置 | ⭐ |
| `VectorEmbeddingService.java` | 加缓存 + 修改 `generateEmbedding()` 和 `generateEmbeddings()` | ⭐⭐⭐ |
| 本文档 | 记录全过程 | ⭐ |

---

## 🧩 逐文件详解

### 1️⃣ pom.xml — 加缓存依赖

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

**Caffeine** 是 Java 业界最流行的内存缓存库（Spring Boot 自带版本管理），特点：
- 线程安全（多个请求同时读写不冲突）
- LRU 淘汰（最近最少使用的缓存条目自动删除）
- 自动过期（写入后超过指定时间自动清理）
- 命中率统计（可以看缓存好不好用）

没有指定版本号——`spring-boot-starter-parent:3.2.0` 会帮我们选一个兼容的版本。

---

### 2️⃣ application.yml — 加缓存配置

```yaml
dashscope:
  embedding:
    model: text-embedding-v4
    cache:                           # ← 新增
      max-size: 500                  #   最多缓存 500 条
      expire-after-write: 24h        #   写入后 24 小时过期
```

**这两个参数决定了缓存的行为：**

| 参数 | 默认值 | 设太小 | 设太大 |
|------|--------|--------|--------|
| `max-size` | 500 | 频繁淘汰，命中率低 | 占用内存多 |
| `expire-after-write` | 24h | 过早过期，浪费 API | 向量可能过时 |

**什么场景下需要调这些参数？**
- 你的服务有大量重复 query（比如监控系统每秒查同样的指标）→ **增大 max-size**
- 你的文档每天更新，旧 embedding 不需要了 → **减小 expire-after-write**
- 你的服务器内存紧张 → **减小 max-size**

---

### 3️⃣ VectorEmbeddingService.java — 核心改动

#### （A）新增字段

```java
// 缓存配置
@Value("${dashscope.embedding.cache.max-size:500}")
private int cacheMaxSize;

@Value("${dashscope.embedding.cache.expire-after-write:24h}")
private String cacheExpireAfterWrite;

// Caffeine 缓存实例（key=内容原文, value=向量）
private Cache<String, List<Float>> embeddingCache;
```

#### （B）初始化缓存

```java
// 在 init() 方法里，创建 TextEmbedding 实例之前
embeddingCache = Caffeine.newBuilder()
        .maximumSize(cacheMaxSize)                    // 最大条目数
        .expireAfterWrite(expireMillis, TimeUnit.MILLISECONDS)  // 写入后过期
        .recordStats()                                // 开启命中率统计
        .build();
```

`recordStats()` 是一个小开关，打开后可以通过 `embeddingCache.stats()` 查看：
- `hitRate()` — 缓存命中率（越高说明缓存越有效）
- `missCount()` — 未命中次数
- `evictionCount()` — 被淘汰的条目数

#### （C）单条生成 `generateEmbedding()` — 三步走

```java
public List<Float> generateEmbedding(String content) {
    // ── 第 1 步：查缓存 ──
    List<Float> cached = embeddingCache.getIfPresent(content);
    if (cached != null) {
        logger.debug("缓存命中! 直接返回");
        return cached;  // 0 元，0 毫秒
    }

    // ── 第 2 步：没命中 → 调 API ──
    TextEmbeddingParam param = TextEmbeddingParam.builder()
            .model(model)
            .texts(Collections.singletonList(content))
            .build();
    TextEmbeddingResult result = textEmbedding.call(param);
    List<Float> floatEmbedding = getFloats(result);

    // ── 第 3 步：存缓存（注意：存不可变副本，防篡改） ──
    List<Float> safeEmbedding = Collections.unmodifiableList(new ArrayList<>(floatEmbedding));
    embeddingCache.put(content, safeEmbedding);
    
    return safeEmbedding;
}
```

**★ 为什么用 `Collections.unmodifiableList()`？**

因为缓存是共享的。如果调用方拿到缓存里的 `List` 后改了它（比如 `.add(0f)`），那么**缓存里的数据也被污染了**——下一次另一个用户来查同样的内容，拿到的是被改坏的数据。

`unmodifiableList()` 给 List 包了一层"防写盔甲"——任何修改操作都会抛出异常：

```java
List<Float> cached = embeddingCache.getIfPresent("hello");
cached.add(1.0f);  // ❌ 抛出 UnsupportedOperationException
```

**★ 为什么 `new ArrayList<>(floatEmbedding)` 再包裹？**

因为 `Collections.unmodifiableList(floatEmbedding)` 只是"包了一层"，但原始的 `floatEmbedding` 对象还在方法里——如果后续代码意外修改了它，缓存还是会被污染。`new ArrayList<>(copy)` 创建了一个**完全独立的副本**，原始对象用完就被 GC 回收了，再也没有别的引用指向它。

#### （D）批量生成 `generateEmbeddings()` — 五步走

```java
public List<List<Float>> generateEmbeddings(List<String> contents) {
    // ── 第 1 步：分离缓存命中与未命中 ──
    // 遍历每个内容，查缓存，按命中情况分类
    List<String> uncachedContents = new ArrayList<>();
    Map<String, List<Float>> cacheHitMap = new HashMap<>();
    for (String content : contents) {
        List<Float> cached = embeddingCache.getIfPresent(content);
        if (cached != null) {
            cacheHitMap.put(content, cached);   // 命中 → 记下来
        } else {
            uncachedContents.add(content);      // 未命中 → 等会调 API
        }
    }

    // ── 第 2 步：如果全部命中缓存，直接组装结果返回 ──
    // 这一步是最大省钱的场景——批量 API 调用省掉了
    if (uncachedContents.isEmpty()) {
        List<List<Float>> allResults = new ArrayList<>();
        for (String content : contents) {
            allResults.add(cacheHitMap.get(content));  // 按原始顺序组装
        }
        return allResults;
    }

    // ── 第 3 步：调 API 只给未命中的内容生成向量 ──
    TextEmbeddingParam param = TextEmbeddingParam.builder()
            .model(model)
            .texts(uncachedContents)  // 只传未缓存的，不传全部
            .build();
    TextEmbeddingResult result = textEmbedding.call(param);

    // ── 第 4 步：缓存新结果 ──
    for (int i = 0; i < uncachedContents.size(); i++) {
        String content = uncachedContents.get(i);
        List<Float> embedding = parseEmbedding(result, i);
        
        // 同样用不可变副本存入缓存
        List<Float> safeEmbedding = Collections.unmodifiableList(new ArrayList<>(embedding));
        embeddingCache.put(content, safeEmbedding);
        cacheHitMap.put(content, safeEmbedding);
    }

    // ── 第 5 步：按原始顺序组装完整结果 ──
    List<List<Float>> allResults = new ArrayList<>();
    for (String content : contents) {
        allResults.add(cacheHitMap.get(content));  // 保持调用方期望的顺序
    }
    
    logger.info("批量完成: 总数={}, 缓存命中={}, API调用={}",
        contents.size(), contents.size() - uncachedContents.size(), uncachedContents.size());
    return allResults;
}
```

**批量方法比单条方法省更多的原因：**

1. 如果 10 条内容中有 3 条命中缓存，只调 API 生成 7 条
2. DashScope 的批量 API 本身就比 7 次单条调用快
3. 两者叠加，既省 API 费又省时间

---

## 📊 效果评估

### 你能省多少？

这取决于你的业务场景：

| 场景 | 命中率 | 效果 |
|------|--------|------|
| 每次都是不同的用户 query | 0%～5% | 缓存帮不上忙（但也不会坏事） |
| 常见的固定 query（如"帮我查一下告警"） | 30% | 省 30% 的 API 费 |
| 文档反复上传/索引 | 50% | 省一半的 Embedding 开销 |
| 短时间内大量相同 query | 80%+ | 几乎不用调 API |

### 内存占用

500 条缓存 × 每条向量 ~6KB（1536 维 × 4 字节）≈ **3MB**。完全不用担心内存。

---

## ⚠️ 容错设计

| 故障场景 | 表现 | 影响 |
|----------|------|------|
| 缓存初始化失败 | Spring 启动报错，服务起不来 | ❌ 不会静默失败 |
| 缓存满了 | Caffeine 自动淘汰最久未使用的条目 | 命中率下降，但功能正常 |
| 调用方修改返回的 List | `UnsupportedOperationException` 抛出 | 立即发现 bug，不会静默污染缓存 |
| 网络断开、API 报错 | 和之前一样抛出异常 | 缓存不写入，不影响数据一致性 |

---

## 🧪 测试方法

### 方法一：看日志

启动项目后，先用 API 生成一次 embedding，再发同样的内容：

```
第一次（缓存未命中）:
[DEBUG] Embedding 缓存未命中，即将调用 API: 内容长度 156 字符
[INFO]  成功生成向量嵌入, 内容长度: 156 字符, 向量维度: 1536
[DEBUG] Embedding 已缓存: 内容长度 156 字符

第二次（同样的内容，缓存命中）:
[DEBUG] Embedding 缓存命中: 内容长度 156 字符
```

第二次没有 `调用 API` 的日志，直接返回——证明缓存生效。

### 方法二：测试缓存统计

在代码里（比如 `MilvusCheckController` 中）加一个临时端点查看缓存状态：

```java
@GetMapping("/embedding/cache-stats")
public String cacheStats() {
    CacheStats stats = vectorEmbeddingService.getEmbeddingCacheStats();
    return String.format("命中率: %.1f%% | 命中: %d | 未命中: %d | 淘汰: %d",
        stats.hitRate() * 100, stats.hitCount(), stats.missCount(), stats.evictionCount());
}
```

返回示例：

```
命中率: 45.2% | 命中: 113 | 未命中: 137 | 淘汰: 12
```

### 方法三：压力测试（模拟批量重复）

写一个简单循环，发 100 次相同的 query：

```java
String query = "什么是向量数据库？";
long start = System.currentTimeMillis();
for (int i = 0; i < 100; i++) {
    embeddingService.generateQueryVector(query);
}
long end = System.currentTimeMillis();
// 第 1 次：API 调用（~300ms）
// 后 99 次：缓存命中（~0.01ms）
// 总时间应该约等于一次 API 的时间
```

---

## 📝 完整代码结构一览（最终版）

```
VectorEmbeddingService
├── 字段
│   ├── apiKey              // DashScope API Key
│   ├── model               // embedding 模型名
│   ├── cacheMaxSize        // 缓存最大条数（配置）
│   ├── cacheExpireAfterWrite // 缓存过期时间（配置）
│   ├── embeddingCache      // Caffeine Cache 实例 ★ 新增
│   └── textEmbedding       // DashScope SDK 客户端
│
├── init()
│   ├── 验证 API Key
│   ├── 初始化 Caffeine 缓存 ★ 新增
│   └── 创建 TextEmbedding
│
├── generateEmbedding(content)
│   ├── 第 1 步：查缓存    ★ 新增
│   ├── 第 2 步：调 API    （已有逻辑）
│   └── 第 3 步：存缓存    ★ 新增
│
├── generateEmbeddings(contents)
│   ├── 第 1 步：分离缓存命中/未命中  ★ 新增
│   ├── 第 2 步：全命中则直接返回     ★ 新增
│   ├── 第 3 步：调 API 只生成未命中的（已有逻辑改造）
│   ├── 第 4 步：缓存未命中的结果     ★ 新增
│   └── 第 5 步：按原始顺序组装返回   ★ 新增
│
├── generateQueryVector(query)
│   └── 委托 generateEmbedding()（自动获得缓存能力）
│
├── calculateCosineSimilarity(v1, v2)
│   └── 不变
│
├── parseExpireConfig(config)    ★ 新增
│   └── "24h" → 24*3600*1000 毫秒
│
└── getFloats(result) (private static)
    └── 不变
```

---

## 🤔 为什么选 Caffeine 而不是 Redis？

你可能会问：**"缓存不是应该用 Redis 吗？"**

这个问题我单独写了篇文档，从安装成本、读取速度、数据量、业务发展阶段四个角度详细拆解了 Caffeine vs Redis 的选型决策，附带决策表帮你判断什么条件下该换。

👉 **[点这里看完整分析 → 技术选型：Caffeine vs Redis](caffeine-vs-redis.md)**

简单总结就是一句话：**你现在单实例、数据才 3MB、重启丢了也无感，Caffeine 正好，Redis 太重。**

---

## 💡 总结

| 问题 | 解决方式 |
|------|----------|
| 相同内容反复调 Embedding API | Caffeine 内存缓存，key=内容原文 |
| 缓存数据被意外篡改 | `Collections.unmodifiableList()` + `new ArrayList<>(copy)` |
| 缓存无限增长 | `maximumSize(500)` LRU 自动淘汰 |
| 向量过时 | `expireAfterWrite(24h)` 自动过期 |
| 不知道缓存好不好用 | `recordStats()` 打开命中率统计 |
| 批量方法效率 | 分离命中/未命中，只调 API 处理未命中的 |

**一句话总结：加了一层缓存，20 行核心代码，省 30%～80% 的 Embedding API 费用。**
