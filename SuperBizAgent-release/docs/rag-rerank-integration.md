# RAG 检索重排（ReRank）功能接入指南

> 适用项目：SuperBizAgent  
> 目标：在向量检索后增加重排环节，提升 RAG 应答质量  
> 难度：初级（本指南面向新手，每一步都会详细解释）

---

## 目录

1. [背景：为什么需要重排](#1-背景为什么需要重排)
2. [新旧流程对比](#2-新旧流程对比)
3. [前置知识：什么是 ReRank](#3-前置知识什么是-rerank)
4. [代码改动清单](#4-代码改动清单)
5. [步骤一：新增重排服务 ReRankService](#5-步骤一新增重排服务-rerankservice)
6. [步骤二：修改向量检索服务，支持宽召回](#6-步骤二修改向量检索服务支持宽召回)
7. [步骤三：修改 RAG 服务，接入重排](#7-步骤三修改-rag-服务接入重排)
8. [步骤四：修改配置文件](#8-步骤四修改配置文件)
9. [步骤五：验证与测试](#9-步骤五验证与测试)
10. [常见问题 FAQ](#10-常见问题-faq)

---

## 1. 背景：为什么需要重排

### 当前的问题

现在的 RAG 流程是这样的：

```
用户提问 → 向量检索(Milvus) → 取 top-3 → 拼给 LLM 回答
```

这里有一个容易被忽略的问题：**向量相似度高，不等于语义上真的相关**。

举个例子，用户问："Java 中如何实现线程安全？"

Milvus 可能召回如下 3 条：

| 排名 | 内容 | 向量距离 | 实际相关性 |
|------|------|---------|-----------|
| 1 | "使用 synchronized 关键字..." | 0.12 | ✅ 高度相关 |
| 2 | "Java 是面向对象语言..." | 0.18 | ❌ 无关 |
| 3 | "线程池的7个参数详解..." | 0.22 | ✅ 相关 |

第 2 条虽然向量距离很近（因为都提到了 "Java"、"对象" 等词），但内容跟"线程安全"其实没关系。如果直接把这 3 条丢给 LLM，第 2 条就成了**噪音**，反而可能误导 LLM 的回答。

### 重排如何解决

重排（ReRank）做的事情是：**把检索回来的文档和用户问题一起，送给一个专门的"打分模型"，让模型判断每条文档到底跟问题有多大关系**。

向量检索 → 管"长得像不像"（粗糙筛选）  
ReRank →  管"意思对不对"（精细排序）

---

## 2. 新旧流程对比

### 旧流程（改动前）

```
┌──────────┐     ┌──────────────┐     ┌──────────┐     ┌──────────┐
│ 用户提问  │ ──→ │ Milvus向量检索 │ ──→ │ 取 top-3 │ ──→ │ LLM回答  │
└──────────┘     │  (返回 top-3) │     └──────────┘     └──────────┘
                 └──────────────┘
```

**问题**：检索只取 3 条，覆盖面太窄；且没有二次筛选，容易混入噪音。

### 新流程（改动后）

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────┐     ┌──────────┐
│ 用户提问  │ ──→ │ Milvus向量检索 │ ──→ │ 重排模型打分   │ ──→ │ 取 top-3 │ ──→ │ LLM回答  │
└──────────┘     │ (返回 top-20) │     │ (gte-rerank) │     └──────────┘     └──────────┘
                 └──────────────┘     └──────────────┘
                       ↑                      ↑
                   "粗筛"                  "精排"
                 覆盖面广               挑出真正相关的
```

**改进**：
1. Milvus 先召回 20 条（宽召回，覆盖面大）
2. 重排模型逐一打分（判断每条文档跟问题的语义相关性）
3. 取分数最高的 3 条交给 LLM（精准、干净）

---

## 3. 前置知识：什么是 ReRank

### 大白话解释

想象你在图书馆找书：

- **向量检索** = 图书管理员根据书脊上的标签，给你抱来一堆"可能相关"的书（比如标签上有"Java"的都抱来）
- **ReRank** = 你把每本书的目录和简介仔细看一遍，判断哪本**真正**回答你的问题，然后按有用程度排序

ReRank 模型（`gte-rerank`）是一个专门训练来评估"文档和问题的相关程度"的模型。给它一个问题 + 一堆文档，它会返回每个文档的打分（0~1 之间的浮点数，越高越相关）。

### 阿里云百炼平台的重排模型

| 项目 | 说明 |
|------|------|
| 模型名称 | `gte-rerank` |
| 提供方 | 阿里云 DashScope（百炼平台） |
| 计费方式 | 按 token 计费，价格低廉 |
| API 地址 | `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` |
| 单次最大文档数 | 建议不超过 100 条（本项目只召回 20 条，完全够用） |

---

## 4. 代码改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/org/example/service/ReRankService.java` | **新增** | 重排服务，调用 DashScope ReRank API |
| `src/main/java/org/example/service/VectorSearchService.java` | **新增方法** | 增加 `searchSimilarDocuments(query, topK, metricType)` 重载 |
| `src/main/java/org/example/service/RagService.java` | **修改** | 在检索和LLM调用之间插入重排步骤 |
| `pom.xml` | **添加依赖** | 增加 Jackson 序列化支持（间接处理，项目已有） |
| `src/main/resources/application.yml` | **修改** | 增加 recall-k 和 rerank 相关配置 |

> **不需要改动的文件**：`ChatController.java`、`ChatService.java`、`InternalDocsTools.java` 等都无需修改，因为它们通过调用 `RagService` / `VectorSearchService` 间接获得重排能力。

---

## 5. 步骤一：新增重排服务 ReRankService

### 5.1 创建新文件

**文件路径**：`src/main/java/org/example/service/ReRankService.java`

<details>
<summary><b>点击查看完整代码</b></summary>

```java
package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 重排（ReRank）服务
 * 调用阿里云 DashScope 的 gte-rerank 模型，对检索结果进行二次打分排序。
 *
 * 大白话解释：
 * 向量检索找到一堆"可能相关"的文档 → 这个服务帮我们判断到底哪些才是"真正相关"的。
 */
@Service
public class ReRankService {

    private static final Logger logger = LoggerFactory.getLogger(ReRankService.class);

    /** DashScope ReRank API 地址 */
    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    @Value("${dashscope.api.key}")
    private String apiKey;

    /** 重排模型名称 */
    @Value("${rag.rerank.model:gte-rerank}")
    private String rerankModel;

    /** 单个文档最大字符数，超过会被截断（保护 API 调用不超限） */
    @Value("${rag.rerank.max-doc-length:4000}")
    private int maxDocLength;

    private OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        logger.info("ReRank 服务初始化完成, 模型: {}, 文档最大长度: {}", rerankModel, maxDocLength);
    }

    /**
     * 对文档列表进行重排
     *
     * @param query     用户的原始问题
     * @param documents 待重排的文档内容列表（来自向量检索的粗筛结果）
     * @param topN      最终要保留几条（比如 3）
     * @return 重排后的结果列表，按相关性从高到低排序，最多 topN 条
     * @throws RuntimeException 如果 API 调用失败（不降级策略）
     */
    public List<ReRankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("文档列表为空，跳过重排");
            return List.of();
        }

        logger.info("开始重排, 查询: {}, 文档数: {}, topN: {}", query, documents.size(), topN);

        try {
            // ----- 第 1 步：构建请求体 -----
            JsonObject requestBody = buildRequestBody(query, documents, topN);
            logger.debug("重排请求体: {}", requestBody.toString());

            // ----- 第 2 步：发送 HTTP POST 请求 -----
            Request request = new Request.Builder()
                    .url(RERANK_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(
                            requestBody.toString(),
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                // ----- 第 3 步：检查 HTTP 状态码 -----
                if (!response.isSuccessful()) {
                    logger.error("重排 API 返回错误, 状态码: {}, 响应体: {}", response.code(), responseBody);
                    throw new RuntimeException(String.format(
                            "重排 API 调用失败 (HTTP %d): %s", response.code(), responseBody));
                }

                // ----- 第 4 步：解析响应，提取结果 -----
                return parseResponse(responseBody, documents);
            }

        } catch (IOException e) {
            logger.error("重排 API 网络调用失败", e);
            // 不降级：直接抛出异常
            throw new RuntimeException("重排服务调用失败，网络异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 DashScope ReRank API 请求体
     *
     * 请求格式示例：
     * {
     *   "model": "gte-rerank",
     *   "input": {
     *     "query": "什么是Java",
     *     "documents": ["文档1", "文档2", "文档3"]
     *   },
     *   "parameters": {
     *     "top_n": 3,
     *     "return_documents": true
     *   }
     * }
     */
    private JsonObject buildRequestBody(String query, List<String> documents, int topN) {
        JsonObject body = new JsonObject();
        body.addProperty("model", rerankModel);

        // input.query
        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        // input.documents - 对长文档做截断保护
        JsonArray docsArray = new JsonArray();
        for (String doc : documents) {
            String truncatedDoc = doc.length() > maxDocLength
                    ? doc.substring(0, maxDocLength) + "..."
                    : doc;
            docsArray.add(truncatedDoc);
        }
        input.add("documents", docsArray);
        body.add("input", input);

        // parameters
        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);
        body.add("parameters", parameters);

        return body;
    }

    /**
     * 解析 API 响应
     *
     * 响应格式示例：
     * {
     *   "output": {
     *     "results": [
     *       { "index": 0, "document": { "text": "..." }, "relevance_score": 0.99 },
     *       { "index": 2, "document": { "text": "..." }, "relevance_score": 0.87 }
     *     ]
     *   }
     * }
     */
    private List<ReRankResult> parseResponse(String responseBody, List<String> originalDocuments) {
        JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();

        // 检查是否有错误信息
        if (responseJson.has("code") && responseJson.has("message")) {
            String code = responseJson.get("code").getAsString();
            String message = responseJson.get("message").getAsString();
            throw new RuntimeException(String.format("重排 API 业务错误 (code=%s): %s", code, message));
        }

        // 提取 results 数组
        JsonArray results = responseJson
                .getAsJsonObject("output")
                .getAsJsonArray("results");

        List<ReRankResult> resultList = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JsonObject item = results.get(i).getAsJsonObject();
            int originalIndex = item.get("index").getAsInt();
            double score = item.get("relevance_score").getAsDouble();
            String documentText = item.has("document")
                    ? item.getAsJsonObject("document").get("text").getAsString()
                    : (originalIndex < originalDocuments.size() ? originalDocuments.get(originalIndex) : "");

            ReRankResult result = new ReRankResult();
            result.setIndex(originalIndex);
            result.setContent(documentText);
            result.setRelevanceScore(score);

            resultList.add(result);
        }

        // 按相关性分数从高到低排序（API 返回的一般已经排好序，但这里再排一次保证安全）
        resultList.sort(Comparator.comparingDouble(ReRankResult::getRelevanceScore).reversed());

        logger.info("重排完成, 返回 {} 条结果, 最高分: {}, 最低分: {}",
                resultList.size(),
                resultList.isEmpty() ? 0 : resultList.get(0).getRelevanceScore(),
                resultList.isEmpty() ? 0 : resultList.get(resultList.size() - 1).getRelevanceScore());

        return resultList;
    }

    // ==================== 数据类 ====================

    /**
     * 重排结果
     */
    public static class ReRankResult {
        /** 在原文档列表中的下标（从 0 开始） */
        private int index;
        /** 文档内容 */
        private String content;
        /** 相关性分数（0~1，越高越相关） */
        private double relevanceScore;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }

        @Override
        public String toString() {
            return String.format("ReRankResult{index=%d, score=%.4f, content=%s}",
                    index, relevanceScore,
                    content.length() > 50 ? content.substring(0, 50) + "..." : content);
        }
    }
}
```

</details>

### 5.2 代码讲解（给小白看）

这一段我们用大白话拆解上面的代码：

#### 整体结构

```
ReRankService
├── init()                          ← 启动时初始化 HTTP 客户端
├── rerank(query, documents, topN)  ← 核心方法：执行重排
│   ├── buildRequestBody()          ←   构建 API 请求的 JSON
│   ├── HTTP POST 调用              ←   发送请求到阿里云
│   └── parseResponse()             ←   解析返回的结果
└── ReRankResult                    ←   内部类：封装一条重排结果
```

#### 每个方法干了什么

| 方法 | 一句话解释 |
|------|-----------|
| `init()` | 创建一个 OkHttp 客户端，设置好超时时间（连接 30 秒、读取 60 秒） |
| `rerank()` | 入口方法。把用户问题 + 一堆文档发给阿里云，拿回排序后的结果 |
| `buildRequestBody()` | 按照阿里云要求的 JSON 格式拼请求体。如果某篇文档太长（超过 4000 字），就截断它，避免 API 报错 |
| `parseResponse()` | 把阿里云返回的 JSON 字符串解析成 Java 对象列表。如果 API 返回了错误码（比如 API Key 不对），直接抛异常 |

#### 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rerankModel` | `gte-rerank` | 重排模型名称，来自配置文件 |
| `maxDocLength` | `4000` | 单篇文档最大长度（字符），超过会被截断。这是为了防止单篇文章太长导致 API 调用超时或报错 |
| `apiKey` | 来自配置 | 阿里云 DashScope API Key |

#### "不降级"是什么意思

注意 `rerank()` 方法——如果 API 调用失败，代码直接 `throw new RuntimeException(...)`，**不会**静默地跳过重排步骤。这正是你要求的不降级策略：重排出问题就要暴露出来，而不是假装没事继续往下走。

---

## 6. 步骤二：修改向量检索服务，支持宽召回

为了让重排有足够多的文档可以挑选，我们需要从 Milvus 先多召回一些（比如 20 条而不是 3 条）。

### 6.1 修改现有文件

**文件路径**：`src/main/java/org/example/service/VectorSearchService.java`

**改动说明**：不需要改现有方法，只需要在 `searchSimilarDocuments` 方法中增加一个重载版本，支持指定度量类型。

在文件末尾（第 107 行 `}` 之前）添加以下方法：

```java
    /**
     * 搜索相似文档（宽召回模式，用于后续重排）
     * 一次召回较多文档，保证覆盖面
     *
     * @param query 查询文本
     * @param topK  返回数量（建议 20）
     * @return 搜索结果列表（未排序，给重排模型使用）
     */
    public List<SearchResult> searchSimilarDocumentsWide(String query, int topK) {
        logger.info("宽召回模式, topK: {}", topK);
        return searchSimilarDocuments(query, topK);
    }
```

> **说明**：因为现有的 `searchSimilarDocuments(query, topK)` 方法已经接受 `topK` 参数，我们只需要在 `RagService` 中调用时传入更大的值（如 20）。这个方法封装了一层，语义更清晰。
>
> 实际使用中，也可以直接在 `RagService` 里调用 `vectorSearchService.searchSimilarDocuments(question, recallK)` 而不需要新增这个方法。
>
> 如果你喜欢更简洁的方式，可以跳过这一步，直接在 RagService 中传入更大的 topK 值即可。

---

## 7. 步骤三：修改 RAG 服务，接入重排

这是最关键的改动——在检索和 LLM 调用之间插入重排步骤。

### 7.1 修改现有文件

**文件路径**：`src/main/java/org/example/service/RagService.java`

#### 改动点 1：在类顶部注入 ReRankService 和新增配置属性

找到类开头的字段声明部分（约第 33~43 行），在 `topK` 字段后面添加：

```java
    @Autowired
    private ReRankService reRankService;

    @Value("${rag.recall-k:20}")
    private int recallK;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;
```

修改后的字段区域如下：

```java
    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private ReRankService reRankService;   // ← 新增：注入重排服务

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.recall-k:20}")
    private int recallK;                    // ← 新增：宽召回数量

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;          // ← 新增：是否启用重排

    @Value("${rag.model:qwen3-30b-a3b-thinking-2507}")
    private String model;
```

#### 改动点 2：在 `init()` 方法中更新日志

找到 `init()` 方法（约第 48~57 行），修改日志输出：

```java
    @PostConstruct
    public void init() {
        // 设置 API Key 和 Base URL
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        
        // 创建 Generation 实例
        generation = new Generation();
        
        logger.info("RAG 服务初始化完成，model: {}, topK: {}, recallK: {}, rerankEnabled: {}",
                model, topK, recallK, rerankEnabled);
    }
```

#### 改动点 3：修改 `queryStream` 方法（核心改动）

找到 `queryStream(String question, List<Map<String, String>> history, StreamCallback callback)` 方法（约第 76~103 行），将整个方法替换为：

```java
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);

            // ===== 阶段 1：向量检索（宽召回） =====
            // 先多召回一些文档（recallK 条，比如 20 条），保证覆盖面
            int searchCount = rerankEnabled ? recallK : topK;
            List<VectorSearchService.SearchResult> searchResults = 
                vectorSearchService.searchSimilarDocuments(question, searchCount);
            logger.info("向量检索完成, 召回 {} 条结果 (rerankEnabled={}, searchCount={})",
                    searchResults.size(), rerankEnabled, searchCount);

            if (searchResults.isEmpty()) {
                logger.warn("未找到相关文档");
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            // ===== 阶段 2：重排（如果启用） =====
            List<String> finalDocuments;
            if (rerankEnabled && searchResults.size() > 1) {
                // 提取所有文档的内容
                List<String> docContents = new ArrayList<>();
                for (VectorSearchService.SearchResult r : searchResults) {
                    docContents.add(r.getContent());
                }

                // 调用重排模型打分排序
                logger.info("开始重排, 待重排文档数: {}", docContents.size());
                List<ReRankService.ReRankResult> reranked =
                        reRankService.rerank(question, docContents, topK);
                logger.info("重排完成, 返回 {} 条结果", reranked.size());

                // 如果重排后结果为空（极端情况），回退到原始检索结果
                if (reranked.isEmpty()) {
                    logger.warn("重排返回空结果，使用原始检索结果");
                    finalDocuments = searchResults.stream()
                            .limit(topK)
                            .map(VectorSearchService.SearchResult::getContent)
                            .toList();
                } else {
                    finalDocuments = reranked.stream()
                            .map(ReRankService.ReRankResult::getContent)
                            .toList();
                }
            } else {
                // 未启用重排，直接取前 topK 条
                finalDocuments = searchResults.stream()
                        .limit(topK)
                        .map(VectorSearchService.SearchResult::getContent)
                        .toList();
            }

            // 将结果包装为 SearchResult 格式，发送给前端
            List<VectorSearchService.SearchResult> displayResults = new ArrayList<>();
            for (int i = 0; i < finalDocuments.size(); i++) {
                VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
                result.setId("reranked-" + i);
                result.setContent(finalDocuments.get(i));
                result.setScore(0);
                displayResults.add(result);
            }
            callback.onSearchResults(displayResults);

            // ===== 阶段 3：构建上下文，调用 LLM =====
            String context = buildContextFromDocuments(finalDocuments);
            String prompt = buildPrompt(question, context);
            generateAnswerStream(prompt, history, callback);

        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }
```

#### 改动点 4：新增辅助方法 `buildContextFromDocuments`

在 `buildContext` 方法后面添加一个新方法：

```java
    /**
     * 从文档列表构建上下文（重排后使用）
     */
    private String buildContextFromDocuments(List<String> documents) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            context.append("【参考资料 ").append(i + 1).append("】\n");
            context.append(documents.get(i)).append("\n\n");
        }
        return context.toString();
    }
```

> 原来的 `buildContext` 方法可以保留，因为 `queryStream` 里已经不再直接调用它了。

---

## 8. 步骤四：修改配置文件

### 8.1 修改 application.yml

**文件路径**：`src/main/resources/application.yml`

找到 RAG 配置部分（约第 66~68 行）：

```yaml
# RAG 配置
rag:
  top-k: 3  # 检索返回的最相似文档数量
  model: "qwen3-max"  # 大语言模型名称
```

替换为：

```yaml
# RAG 配置
rag:
  top-k: 3          # 最终保留给 LLM 的最相关文档数量
  recall-k: 20      # 向量检索宽召回数量（给重排模型提供更多候选）
  model: "qwen3-max"  # 大语言模型名称
  rerank:
    enabled: true      # 是否启用重排（true=启用, false=关闭）
    model: "gte-rerank" # 重排模型名称
    max-doc-length: 4000  # 单篇文档最大字符数（超过会被截断）
```

### 8.2 配置项说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `rag.top-k` | `3` | 最终给 LLM 看的文档数。建议 3~5。 |
| `rag.recall-k` | `20` | 向量检索宽召回的数量。越多覆盖越全，但重排耗时越长。建议 15~30。 |
| `rag.rerank.enabled` | `true` | 重排开关。如果想临时关掉重排对比效果，改成 `false` 即可。 |
| `rag.rerank.model` | `gte-rerank` | 阿里云百炼平台的重排模型。不要改，除非官方出了新版本。 |
| `rag.rerank.max-doc-length` | `4000` | 单篇文档最大长度。超过会被截断再送进重排模型。这不是限制检索结果，只是保护 API 调用。 |

### 8.3 开关对比测试

你可以通过改 `rag.rerank.enabled` 来对比效果：

```yaml
# 启用重排（推荐）
rag:
  rerank:
    enabled: true

# 关闭重排（回退到旧行为）
rag:
  rerank:
    enabled: false
```

---

## 9. 步骤五：验证与测试

### 9.1 编译检查

```bash
cd SuperBizAgent-release
mvn compile
```

如果看到 `BUILD SUCCESS`，说明代码没有语法错误。

### 9.2 启动服务

```bash
# 确保先设置了环境变量
export DASHSCOPE_API_KEY=your-api-key

# 启动
mvn spring-boot:run
```

启动日志中你应该看到：

```
ReRank 服务初始化完成, 模型: gte-rerank, 文档最大长度: 4000
RAG 服务初始化完成，model: qwen3-max, topK: 3, recallK: 20, rerankEnabled: true
```

### 9.3 发送测试请求

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test-rerank","Question":"CPU使用率过高怎么排查？"}'
```

观察服务端日志，你应该看到类似：

```
收到 RAG 流式查询: CPU使用率过高怎么排查？
向量检索完成, 召回 20 条结果 (rerankEnabled=true, searchCount=20)
开始重排, 待重排文档数: 20
重排完成, 返回 3 条结果
开始调用AI模型流式接口...
```

### 9.4 验证重排效果

对比开启和关闭重排的差异：

**关闭重排测试**（临时改 `rag.rerank.enabled: false`）：
```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test-off","Question":"CPU使用率过高怎么排查？"}'
```

**开启重排测试**（`rag.rerank.enabled: true`）：
```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test-on","Question":"CPU使用率过高怎么排查？"}'
```

对比两次返回的答案质量和引用的文档内容，重排后的答案应该更聚焦、更相关。

---

## 10. 常见问题 FAQ

### Q1：为什么不直接用 `dashscope-sdk-java` 提供的 SDK 方法？

**答**：`dashscope-sdk-java:2.17.0` 是 Apache 2.0 许可证的最后一个版本。虽然它可能包含 `TextReRank` 等重排相关类，但我们选择直接用 HTTP 调用，原因是：

1. **避免许可证风险**：更高版本的 dashscope SDK 已更换为非开源许可证
2. **依赖最简**：不引入额外依赖，使用项目已有的 OkHttp
3. **API 稳定**：DashScope ReRank API 是标准的 RESTful 接口，不会频繁变动

如果你确认 2.17.0 中有相关封装，也可以改用 SDK 方式，逻辑是完全一样的。

### Q2：重排会慢多少？

**答**：重排模型的调用延迟通常在 200ms~2s 之间（取决于文档数量和长度）。对于本项目的 20 条文档，一般在 500ms 以内。相比 LLM 的推理时间（几秒到几十秒），这个开销几乎可以忽略不计。

### Q3：什么是"不降级"？失败了怎么办？

**答**：不降级的意思是——如果重排模型调用失败了（比如网络不通、API Key 错误），系统直接报错，**不会**假装什么都没发生，退回到旧的（无重排）流程。

这是你明确要求的策略。这样做的好处是：你不会在不知不觉中丢失重排功能。坏处是：重排 API 出问题时整个 RAG 流程都会中断。

如果将来你想改为"降级"策略（重排失败时自动回退到无重排模式），只需要在 `rerank()` 方法调用处包一个 `try-catch` 即可。

### Q4：可以为 AIOps 的 InternalDocsTools 也加上重排吗？

**答**：可以的。`InternalDocsTools` 内部调用了 `vectorSearchService.searchSimilarDocuments()`，如果你想在 AIOps 场景也启用重排，有两种方案：

- **方案 A（推荐）**：在 `InternalDocsTools` 中注入 `ReRankService`，在 `queryInternalDocs()` 方法中加入重排逻辑（参考 RagService 的改动）
- **方案 B**：不改 `InternalDocsTools`，因为 AIOps 的 Planner Agent 本身会对工具返回结果做判断和筛选

本指南主要针对 RAG 问答场景（`RagService`），因为这是用户直接感知到的效果。AIOps 场景的改动逻辑完全相同，你可以按需自行添加。

### Q5：recall-k 设多少合适？

**答**：

| recall-k | 适用场景 |
|----------|---------|
| 10 | 知识库小（< 100 篇文档），追求速度 |
| 20 | 通用推荐（本指南默认值） |
| 30 | 知识库大（> 500 篇文档），追求覆盖率 |
| 50+ | 不推荐（重排耗时长，且边际收益递减） |

### Q6：重排模型需要额外付费吗？

**答**：阿里云百炼平台的 `gte-rerank` 模型按 token 计费。每次重排的 token 消耗 = 输入文档的总字符数 + 查询字符数。费用很低，以本项目每次 20 篇文档、每篇 800 字的典型场景计算，单次调用成本约几分钱。

---

## 附录：完整的 RAG 检索→重排→生成 流程图

```
                          ┌─────────────────────────┐
                          │     用户提问 "Q"          │
                          └───────────┬─────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │   向量检索 (Milvus)       │
                         │   text-embedding-v4      │
                         │   召回 top-20 (recallK)  │
                         └────────────┬────────────┘
                                      │
                                      │ 20 条文档
                                      │
                         ┌────────────▼────────────┐
                         │   重排 (ReRank)           │
                         │   gte-rerank 模型         │
                         │   输入: Q + 20 条文档      │
                         │   输出: top-3 最相关文档   │
                         └────────────┬────────────┘
                                      │
                                      │ 3 条精选文档
                                      │
                         ┌────────────▼────────────┐
                         │   prompt 拼接             │
                         │   "参考资料... + Q"        │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │   LLM 生成回答            │
                         │   qwen3-max              │
                         └────────────┬────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │     返回最终答案           │
                          └─────────────────────────┘
```

---

**改动完成！如果遇到任何编译或运行问题，检查以下三点：**

1. `application.yml` 中的 `dashscope.api.key` 是否正确配置
2. 阿里云 DashScope 账户是否已开通重排模型服务（百炼控制台 → 模型广场 → 搜索 `gte-rerank` → 开通）
3. `pom.xml` 中是否有 OkHttp 依赖（已有，因为 `dashscope-sdk-java` 传递依赖了 OkHttp）
