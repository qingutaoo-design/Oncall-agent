# Agent 工具路线 — 重排（ReRank）功能接入指南

> 适用项目：SuperBizAgent  
> 目标：在 Agent 调用 `queryInternalDocs` 工具检索知识库时，加入重排环节  
> 难度：初级（面向新手，每一步都会详细解释）  
> 
> **本次改动的目标路线**：  
> `ReactAgent` → `InternalDocsTools.queryInternalDocs()` → `VectorSearchService` → **插入 ReRank** → 返回给 Agent

---

## 目录

1. [背景：当前项目谁在使用知识库检索](#1-背景当前项目谁在使用知识库检索)
2. [为什么需要重排](#2-为什么需要重排)
3. [新旧流程对比](#3-新旧流程对比)
4. [前置知识：什么是 ReRank](#4-前置知识什么是-rerank)
5. [代码改动清单](#5-代码改动清单)
6. [步骤一：新增 ReRankService](#6-步骤一新增重排服务-rerankservice)
7. [步骤二：修改 VectorSearchService 支持宽召回](#7-步骤二修改向量检索服务支持宽召回)
8. [步骤三：修改 InternalDocsTools 接入重排（核心）](#8-步骤三修改-internaldocstools-接入重排核心)
9. [步骤四：修改配置文件](#9-步骤四修改配置文件)
10. [步骤五：验证与测试](#10-步骤五验证与测试)
11. [常见问题 FAQ](#10-常见问题-faq)

---

## 1. 背景：当前项目谁在使用知识库检索

在你开始改代码之前，先搞清楚一件事：**知识库检索到底是谁调用的？**

### 不是 RagService

虽然项目里有一个 `RagService.java`，但它**没有被任何代码调用**——是早期遗留的死代码。

### 真正的调用者是 InternalDocsTools

实际调用链路：

```
用户提问 POST /api/chat
    │
    ▼
ChatController.chat()
    │
    ▼
ReactAgent.call(question)          ← Spring AI 框架自动运行 ReAct 循环
    │
    │  Agent 读到自己 systemPrompt 里的这句：
    │  "当用户需要查询公司内部文档...时，使用 queryInternalDocs 工具"
    │
    │  → Agent 决定：我需要调用 queryInternalDocs 工具
    │
    ▼
InternalDocsTools.queryInternalDocs(query)   ← @Tool 注解的方法
    │
    ▼
VectorSearchService.searchSimilarDocuments(query, topK=3)
    │
    ▼
Milvus 向量检索 → 返回 top-3 → 序列化为 JSON → 还给 Agent
    │
    ▼
Agent 把工具返回的内容拼到 prompt → 呼叫 LLM → 生成最终答案
```

所以，**你要改的入口是 `InternalDocsTools.queryInternalDocs()` 方法**，不是 `RagService`。

---

## 2. 为什么需要重排

### 当前的问题

`InternalDocsTools` 现在的做法：

```
用户问题 → 向量检索(Milvus) → 取 top-3 → JSON 返回给 Agent
```

这里有一个容易被忽略的问题：**向量相似度高，不等于语义上真的相关**。

举个例子，用户问："Java 中如何实现线程安全？"

Milvus 可能召回如下 3 条：

| 排名 | 内容 | 向量距离 | 实际相关性 |
|------|------|---------|-----------|
| 1 | "使用 synchronized 关键字..." | 0.12 | ✅ 高度相关 |
| 2 | "Java 是面向对象语言..." | 0.18 | ❌ 无关 |
| 3 | "线程池的7个参数详解..." | 0.22 | ✅ 相关 |

第 2 条虽然向量距离很近（因为都提到了 "Java"、"对象" 等词），但内容跟"线程安全"其实没关系。Agent 拿到这 3 条给 LLM，第 2 条就成了**噪音**，可能误导答案。

### 重排如何解决

```
向量检索 → 管"长得像不像"（粗糙筛选，多召一些）
ReRank   → 管"意思对不对"（精细打分，挑出真正相关的）
```

---

## 3. 新旧流程对比

### 旧流程（改动前）

```
Agent 调用 queryInternalDocs
    │
    ▼
向量检索(Milvus) → top-3 → JSON 返回给 Agent
```

**问题**：只取 3 条，覆盖面窄；没有二次筛选，容易混入噪音。

### 新流程（改动后）

```
Agent 调用 queryInternalDocs
    │
    ▼
向量检索(Milvus) → top-20（宽召回）
    │
    ▼
ReRank 模型打分（gte-rerank）
    │
    ▼
取分数最高的 3 条 → JSON 返回给 Agent
```

**改进**：
1. Milvus 先召回 20 条（覆盖广）
2. gte-rerank 模型逐一打分（判断语义相关性）
3. 只把最相关的 3 条交给 Agent（干净）

---

## 4. 前置知识：什么是 ReRank

### 大白话比喻

想象你在图书馆找书：

- **向量检索** = 图书管理员根据书脊上的标签，给你抱来一堆"可能相关"的书（标签上有"Java"的都抱来）
- **ReRank** = 你把每本书的目录简介仔细看一遍，判断哪本**真正**回答你的问题，然后按有用程度排序

### 阿里云百炼平台的重排模型

| 项目 | 说明 |
|------|------|
| 模型名称 | `gte-rerank` |
| 提供方 | 阿里云 DashScope（百炼平台） |
| API 地址 | `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` |
| 计费 | 按 token 计费，价格低廉 |
| 单次最大文档数 | 建议不超过 100 条（本项目每次 20 条） |

---

## 5. 代码改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/org/example/service/ReRankService.java` | **新增** | 重排核心服务，调用 DashScope ReRank API |
| `src/main/java/org/example/service/VectorSearchService.java` | **新增方法** | 增加 `searchSimilarDocumentsWide()` 语义封装 |
| `src/main/java/org/example/agent/tool/InternalDocsTools.java` | **修改** | 在工具方法中加入重排逻辑 |
| `src/main/resources/application.yml` | **修改** | 增加 recall-k 和 rerank 配置 |

> **不需要改动的文件**：`ChatController.java`、`ChatService.java`、`RagService.java` 等都不需要动。重排逻辑全部收敛在 `InternalDocsTools` 内部，对外接口不变。

---

## 6. 步骤一：新增重排服务 ReRankService

### 6.1 为什么要新建这个文件

重排模型的 API 是一个独立的 HTTP 接口（不属于 DashScope 的聊天接口或向量接口），所以我们需要一个专门的服务类来封装这个调用逻辑。

### 6.2 创建文件

**文件路径**：`src/main/java/org/example/service/ReRankService.java`

把以下完整代码**全部复制**，创建这个新文件：

```java
package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 重排（ReRank）服务
 * 调用阿里云 DashScope 的 gte-rerank 模型，对检索结果进行二次打分排序。
 *
 * 简单理解：
 * 向量检索找到了 20 篇"可能相关"的文档 → 这个服务帮 Agent 挑出其中最相关的 3 篇。
 */
@Service
public class ReRankService {

    private static final Logger logger = LoggerFactory.getLogger(ReRankService.class);

    /** DashScope ReRank API 地址（不要改） */
    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    @Value("${dashscope.api.key}")
    private String apiKey;

    /** 重排模型名称 */
    @Value("${rag.rerank.model:gte-rerank}")
    private String rerankModel;

    /** 单篇文档最大字符数，超过会被截断（保护 API 调用不超限） */
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
     * 核心方法：对文档列表进行重排
     *
     * 把用户问题 + 一堆文档一起发给阿里云的重排模型，
     * 模型会逐一打分（0~1 之间的浮点数，越高越相关），
     * 最后返回分数最高的 topN 条。
     *
     * @param query     用户问题（Agent 传给工具的 query 参数）
     * @param documents 向量检索粗筛出来的文档内容列表
     * @param topN      最终保留几条（比如 3）
     * @return 重排后的结果列表，按相关性从高到低排序
     * @throws RuntimeException 如果 API 调用失败（不降级）
     */
    public List<ReRankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("文档列表为空，跳过重排");
            return List.of();
        }

        logger.info("开始重排, 查询: {}, 文档数: {}, topN: {}", query, documents.size(), topN);

        try {
            // 第 1 步：按照阿里云要求的格式，构建请求 JSON
            JsonObject requestBody = buildRequestBody(query, documents, topN);

            // 第 2 步：发送 HTTP POST 请求到阿里云
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

                if (!response.isSuccessful()) {
                    logger.error("重排 API 返回错误, 状态码: {}, 响应: {}", response.code(), responseBody);
                    throw new RuntimeException(String.format(
                            "重排 API 调用失败 (HTTP %d): %s", response.code(), responseBody));
                }

                // 第 3 步：解析阿里云返回的 JSON，提取打分结果
                return parseResponse(responseBody, documents);
            }

        } catch (IOException e) {
            logger.error("重排 API 网络调用失败", e);
            throw new RuntimeException("重排服务调用失败，网络异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构建请求体 JSON
     *
     * 发给阿里云的 JSON 大概长这样：
     * {
     *   "model": "gte-rerank",
     *   "input": {
     *     "query": "CPU过高怎么排查",
     *     "documents": ["文档1内容...", "文档2内容...", ...]
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

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray docsArray = new JsonArray();
        for (String doc : documents) {
            // 如果某篇文档太长，截断它，避免 API 报错
            String safeDoc = doc.length() > maxDocLength
                    ? doc.substring(0, maxDocLength) + "..."
                    : doc;
            docsArray.add(safeDoc);
        }
        input.add("documents", docsArray);
        body.add("input", input);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);
        body.add("parameters", parameters);

        return body;
    }

    /**
     * 解析阿里云返回的响应 JSON
     *
     * 阿里云返回的 JSON 大概长这样：
     * {
     *   "output": {
     *     "results": [
     *       { "index": 0, "document": { "text": "..." }, "relevance_score": 0.99 },
     *       { "index": 2, "document": { "text": "..." }, "relevance_score": 0.87 },
     *       { "index": 1, "document": { "text": "..." }, "relevance_score": 0.45 }
     *     ]
     *   }
     * }
     *
     * index: 这条文档在原文档列表中的位置（第 0 篇、第 1 篇...）
     * relevance_score: 相关性分数，0~1，越大越相关
     */
    private List<ReRankResult> parseResponse(String responseBody, List<String> originalDocuments) {
        JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();

        // 如果返回了错误码，直接抛异常
        if (responseJson.has("code") && responseJson.has("message")) {
            String code = responseJson.get("code").getAsString();
            String message = responseJson.get("message").getAsString();
            throw new RuntimeException(String.format("重排 API 业务错误 (code=%s): %s", code, message));
        }

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
                    : (originalIndex < originalDocuments.size()
                            ? originalDocuments.get(originalIndex) : "");

            ReRankResult result = new ReRankResult();
            result.setIndex(originalIndex);
            result.setContent(documentText);
            result.setRelevanceScore(score);
            resultList.add(result);
        }

        // 按分数从高到低排序（API 一般已排好，但再排一次确保安全）
        resultList.sort(Comparator.comparingDouble(ReRankResult::getRelevanceScore).reversed());

        logger.info("重排完成, 返回 {} 条结果, 最高分: {:.4f}, 最低分: {:.4f}",
                resultList.size(),
                resultList.isEmpty() ? 0 : resultList.get(0).getRelevanceScore(),
                resultList.isEmpty() ? 0 : resultList.get(resultList.size() - 1).getRelevanceScore());

        return resultList;
    }

    // ==================== 数据类 ====================

    /**
     * 一条重排结果
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
    }
}
```

### 6.3 代码讲解

这段代码就做一件事：把用户问题和一堆文档发给阿里云重排模型，拿回排序后的结果。

#### 调用链

```
rerank(query, documents, topN)
  ├── buildRequestBody()     拼 JSON 请求
  ├── httpClient 发 HTTP POST
  └── parseResponse()        解析 JSON 响应
```

#### 每个参数什么意思

| 参数 | 来源 | 含义 |
|------|------|------|
| `query` | Agent 传给工具的查询字符串 | 用户想问什么 |
| `documents` | 向量检索返回的文档列表 | 待重排的候选文档 |
| `topN` | application.yml 的 `rag.top-k` | 最终保留几条 |
| `rerankModel` | application.yml 的 `rag.rerank.model` | 用哪个模型 |
| `maxDocLength` | application.yml 的 `rag.rerank.max-doc-length` | 单篇文档最长多少字 |

#### "不降级"的解释

代码里有注释 "不降级"。意思是：如果重排 API 调用失败（网络断了、API Key 错了、阿里云那边出问题了），`rerank()` 方法会**直接抛出异常**，不会假装什么都没发生、静默退回到旧的流程。

你之前要求的就是这个行为——有问题就要暴露出来。

---

## 7. 步骤二：修改向量检索服务，支持宽召回

### 7.1 为什么要改这里

为了让重排模型有足够多的文档可以挑选，我们需要从 Milvus 多召回一些（比如 20 条而不是 3 条）。

现有的 `searchSimilarDocuments(query, topK)` 方法本身已经支持传任意 `topK`，所以我们只需要给它传一个更大的值就行。但为了方便阅读，我们加一个语义更清晰的方法名。

### 7.2 修改文件

**文件路径**：`src/main/java/org/example/service/VectorSearchService.java`

在 **第 107 行**（类的结束大括号 `}` 之前）插入以下方法：

```java
    /**
     * 宽召回模式 —— 为 ReRank 准备更多候选文档
     *
     * 和 searchSimilarDocuments 的逻辑完全一样，只是名字更直观。
     * 
     * @param query 查询文本
     * @param topK  召回数量（建议 20）
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocumentsWide(String query, int topK) {
        logger.info("宽召回模式, 查询: {}, topK: {}", query, topK);
        return searchSimilarDocuments(query, topK);
    }
```

> **这一步可以跳过**——直接在 `InternalDocsTools` 里调用 `vectorSearchService.searchSimilarDocuments(query, recallK)` 效果一样。加这个方法只是为了让代码读起来更清楚。

---

## 8. 步骤三：修改 InternalDocsTools 接入重排（核心）

这是最关键的一步。我们需要在 `InternalDocsTools.queryInternalDocs()` 方法中，把原来的"检索 → 返回"改为"检索(20条) → 重排 → 精选(3条) → 返回"。

### 8.1 改动前后对比

**改动前**（现在的代码）：
```java
// 1. 检索 3 条
List<SearchResult> results = vectorSearchService.searchSimilarDocuments(query, topK);
// 2. 转成 JSON 直接返回
return objectMapper.writeValueAsString(results);
```

**改动后**（新代码）：
```java
// 1. 宽召回 20 条
List<SearchResult> results = vectorSearchService.searchSimilarDocuments(query, recallK);
// 2. 提取文档内容，送给重排模型打分
List<String> docContents = ...;
List<ReRankResult> reranked = reRankService.rerank(query, docContents, topK);
// 3. 只把最相关的几条转成 JSON 返回
return objectMapper.writeValueAsString(reranked);
```

### 8.2 完整修改

**文件路径**：`src/main/java/org/example/agent/tool/InternalDocsTools.java`

#### 改动点 1：在类顶部注入 ReRankService，新增 recallK 和 rerankEnabled 属性

找到类的字段声明（约第 29~32 行），在 `topK` 后面添加：

```java
    private final VectorSearchService vectorSearchService;
    
    // ↓↓↓ 新增：注入重排服务 ↓↓↓
    private final ReRankService reRankService;

    @Value("${rag.top-k:3}")
    private int topK = 3;

    // ↓↓↓ 新增两个配置属性 ↓↓↓
    @Value("${rag.recall-k:20}")
    private int recallK = 20;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled = true;
```

#### 改动点 2：修改构造函数，增加 ReRankService 参数

找到构造函数（约第 38~41 行），改为：

```java
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService,
                             ReRankService reRankService) {
        this.vectorSearchService = vectorSearchService;
        this.reRankService = reRankService;
    }
```

#### 改动点 3：修改 `queryInternalDocs` 方法（核心）

将整个 `queryInternalDocs` 方法（约第 53~78 行）**替换为**：

```java
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        
        try {
            // ===== 阶段 1：向量检索（宽召回） =====
            // 如果启用了重排，就多召回一些（recallK 条，比如 20）；否则按原来的方式只召回 topK 条
            int searchCount = rerankEnabled ? recallK : topK;
            List<VectorSearchService.SearchResult> searchResults = 
                    vectorSearchService.searchSimilarDocuments(query, searchCount);
            
            logger.info("[queryInternalDocs] 检索完成, 查询: {}, 召回: {} 条 (rerankEnabled={})", 
                    query, searchResults.size(), rerankEnabled);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            // ===== 阶段 2：重排（如果启用且结果数 > 1） =====
            List<String> finalDocuments;
            if (rerankEnabled && searchResults.size() > 1) {
                // 提取所有文档的纯文本内容
                List<String> docContents = new ArrayList<>();
                for (VectorSearchService.SearchResult r : searchResults) {
                    docContents.add(r.getContent());
                }

                // 呼叫重排模型，对文档逐一打分，取 topK 条最相关的
                logger.info("[queryInternalDocs] 开始重排, 待重排文档数: {}", docContents.size());
                List<ReRankService.ReRankResult> reranked =
                        reRankService.rerank(query, docContents, topK);
                logger.info("[queryInternalDocs] 重排完成, 精选出 {} 条", reranked.size());

                if (reranked.isEmpty()) {
                    // 极端情况：重排返回空，回退到原始结果的前 topK 条
                    logger.warn("[queryInternalDocs] 重排返回空结果，使用原始检索结果");
                    finalDocuments = searchResults.stream()
                            .limit(topK)
                            .map(VectorSearchService.SearchResult::getContent)
                            .toList();
                } else {
                    // 正常情况：使用重排后的结果
                    finalDocuments = reranked.stream()
                            .map(ReRankService.ReRankResult::getContent)
                            .toList();
                }
            } else {
                // 未启用重排（或者只有 1 条结果，没有重排的必要）
                finalDocuments = searchResults.stream()
                        .limit(topK)
                        .map(VectorSearchService.SearchResult::getContent)
                        .toList();
            }

            // ===== 阶段 3：格式化返回给 Agent =====
            // 将精选后的文档列表转为 JSON，Agent（LLM）能读懂 JSON 格式
            String resultJson = objectMapper.writeValueAsString(
                    finalDocuments.stream()
                            .map(doc -> {
                                try {
                                    Map<String, Object> item = new HashMap<>();
                                    item.put("content", doc);
                                    return item;
                                } catch (Exception e) {
                                    return Map.of("content", doc);
                                }
                            })
                            .toList()
            );

            return resultJson;

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }
```

#### 改动点 4：添加新的 import

在文件顶部的 import 区域，确保有以下 import（缺少的就加上）：

```java
import org.example.service.ReRankService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
```

### 8.3 修改后的完整文件

<details>
<summary><b>点击查看 InternalDocsTools.java 改完后的全貌</b></summary>

```java
package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ReRankService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 * 支持向量检索 + ReRank 重排二级筛选
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;
    private final ReRankService reRankService;
    
    @Value("${rag.top-k:3}")
    private int topK = 3;

    @Value("${rag.recall-k:20}")
    private int recallK = 20;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled = true;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService,
                             ReRankService reRankService) {
        this.vectorSearchService = vectorSearchService;
        this.reRankService = reRankService;
    }

    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {

        try {
            int searchCount = rerankEnabled ? recallK : topK;
            List<VectorSearchService.SearchResult> searchResults = 
                    vectorSearchService.searchSimilarDocuments(query, searchCount);
            
            logger.info("[queryInternalDocs] 检索完成, 查询: {}, 召回: {} 条 (rerankEnabled={})", 
                    query, searchResults.size(), rerankEnabled);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            List<String> finalDocuments;
            if (rerankEnabled && searchResults.size() > 1) {
                List<String> docContents = new ArrayList<>();
                for (VectorSearchService.SearchResult r : searchResults) {
                    docContents.add(r.getContent());
                }

                logger.info("[queryInternalDocs] 开始重排, 待重排文档数: {}", docContents.size());
                List<ReRankService.ReRankResult> reranked =
                        reRankService.rerank(query, docContents, topK);
                logger.info("[queryInternalDocs] 重排完成, 精选出 {} 条", reranked.size());

                if (reranked.isEmpty()) {
                    logger.warn("[queryInternalDocs] 重排返回空结果，使用原始检索结果");
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
                finalDocuments = searchResults.stream()
                        .limit(topK)
                        .map(VectorSearchService.SearchResult::getContent)
                        .toList();
            }

            String resultJson = objectMapper.writeValueAsString(
                    finalDocuments.stream()
                            .map(doc -> {
                                Map<String, Object> item = new HashMap<>();
                                item.put("content", doc);
                                return item;
                            })
                            .toList()
            );

            return resultJson;

        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }
}
```

</details>

### 8.4 关键逻辑解释

#### 为什么有一个 `rerankEnabled` 开关

这是一个"安全阀"。如果哪天重排模型出了问题，你只需要在配置文件里把 `rag.rerank.enabled` 改成 `false`，代码就会退回到旧的行为（直接检索 topK 条返回），不需要改任何代码。

#### 返回格式的变化

| | 改动前 | 改动后 |
|---|---|---|
| 格式 | `[{"id":"...","content":"...","score":0.8}]` | `[{"content":"..."},{"content":"..."}]` |
| 内容来源 | Milvus 原始结果 | ReRank 精选结果 |
| 字段 | 包含 id、score、metadata | 只保留 content |

Agent（LLM）读 JSON 只看 `content` 字段，其他字段（id、score 等）只是噪音。精简为 `[{"content":"..."}]` 格式让 Agent 解析起来更快、更不容易出错。

---

## 9. 步骤四：修改配置文件

### 9.1 修改 application.yml

**文件路径**：`src/main/resources/application.yml`

找到 RAG 配置部分（当前是第 66~68 行）：

```yaml
# RAG 配置
rag:
  top-k: 3
  model: "qwen3-max"
```

**替换为**：

```yaml
# RAG 配置
rag:
  top-k: 3              # 最终给 LLM 看的文档数
  recall-k: 20          # 向量检索宽召回数量（给重排模型提供更多候选）
  model: "qwen3-max"    # 大语言模型名称
  rerank:
    enabled: true          # 重排总开关（true=启用, false=跳过）
    model: "gte-rerank"    # 重排模型名称（阿里云百炼平台）
    max-doc-length: 4000   # 单篇文档最大字符数（保护 API 调用）
```

### 9.2 每个配置项的含义

| 配置项 | 值 | 干什么用 |
|--------|-----|---------|
| `rag.top-k` | `3` | 最终交给 LLM 的文档数量。3~5 比较合适。 |
| `rag.recall-k` | `20` | 向量检索宽召回数量。越大覆盖越全，但重排耗时也会增加。建议 15~30。 |
| `rag.rerank.enabled` | `true` | **总开关**。改成 `false` 就退回到旧行为（无重排）。 |
| `rag.rerank.model` | `gte-rerank` | 阿里云的重排模型名。不要改。 |
| `rag.rerank.max-doc-length` | `4000` | 单篇文档最大长度。超过的部分会被截掉，防止 API 调用超时。 |

### 9.3 如何对比效果

把 `enabled` 改来改去就可以对比：

```yaml
# 开（有重排）
rag.rerank.enabled: true

# 关（无重排，和改之前完全一样）
rag.rerank.enabled: false
```

改完配置重启服务即可，不用改代码。

---

## 10. 步骤五：验证与测试

### 10.1 编译检查

```bash
cd SuperBizAgent-release
mvn compile
```

看到 `BUILD SUCCESS` 就说明代码没问题。

### 10.2 启动服务

```bash
# 设置 API Key（如果还没设的话）
$env:DASHSCOPE_API_KEY = "your-api-key"

# 启动
mvn spring-boot:run
```

启动日志中你应该看到：

```
ReRank 服务初始化完成, 模型: gte-rerank, 文档最大长度: 4000
```

### 10.3 发一条测试请求

```bash
curl -X POST http://localhost:9900/api/chat `
  -H "Content-Type: application/json" `
  -d '{"Id":"test-1","Question":"CPU利用率过高怎么排查？"}'
```

观察服务端日志，应该出现类似输出：

```
[queryInternalDocs] 检索完成, 查询: CPU利用率过高怎么排查？, 召回: 20 条 (rerankEnabled=true)
[queryInternalDocs] 开始重排, 待重排文档数: 20
开始重排, 查询: CPU利用率过高怎么排查？, 文档数: 20, topN: 3
重排完成, 返回 3 条结果, 最高分: XX, 最低分: XX
[queryInternalDocs] 重排完成, 精选出 3 条
```

### 10.4 验证重排有没有效果

**测试 1：关了重排**
- 改 `rag.rerank.enabled: false`，重启
- 问同一个问题，记录答案

**测试 2：开了重排**
- 改 `rag.rerank.enabled: true`，重启
- 问同一个问题，记录答案

对比两个答案：
- 重排后的答案应该**更聚焦**在问题本身
- 重排后的引用内容应该**更相关**

---

## 11. 常见问题 FAQ

### Q1：整个调用链路是怎么走的？

```
用户问"CPU过高怎么办"
  → ChatController
    → ReactAgent 读取 systemPrompt："需要查文档时用 queryInternalDocs 工具"
      → Agent 自主决定：调 queryInternalDocs("CPU过高怎么办")
        → InternalDocsTools.queryInternalDocs()
          → 1. VectorSearchService 粗筛 20 条
          → 2. ReRankService 精排，取 top-3
          → 3. 返回 JSON [{"content":"..."},{"content":"..."},{"content":"..."}]
        ← Agent 收到 JSON
      → Agent 把 JSON 拼到 prompt："参考资料如下...请回答用户 CPU过高怎么办"
      → LLM 生成最终答案
    ← 返回答案给用户
```

### Q2：为什么不把重排放到 VectorSearchService 里？

因为重排逻辑和向量检索是两个独立职责：
- `VectorSearchService` 只管"去 Milvus 查"
- `ReRankService` 只管"去阿里云重排"

放在 `InternalDocsTools` 里编排这两个步骤，职责更清晰，也方便将来开关或替换。

### Q3：重排会变慢多少？

重排模型的调用延迟通常在 200ms~2s（取决文档数量和长度）。本项目 20 条文档一般在 500ms 以内，相比 LLM 推理时间（秒级），几乎感觉不到。

### Q4：AIOps 的 Planner/Executor Agent 也会用到 queryInternalDocs，会自动获得重排能力吗？

**会**。因为 `AiOpsService.buildMethodToolsArray()` 里也包含了 `internalDocsTools`：

```java
// AiOpsService.java:134
return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
```

Planner 或 Executor 调用 `queryInternalDocs` 时，走的是同一个 `InternalDocsTools` 实例，自然会经过重排。**一次改动，两处受益。**

### Q5：重排失败怎么办？

"不降级"策略：重排 API 调用失败时直接抛异常，Agent 会收到一个 error JSON：

```json
{"status": "error", "message": "Failed to query internal docs: ..."}
```

Agent（LLM）看到这个错误后，会诚实告知用户"查询文档时出错了"，而不是编造答案。

### Q6：如果知识库很小（比如只有 5 篇文档），recall-k=20 怎么办？

不用担心。`VectorSearchService.searchSimilarDocuments(query, 20)` 如果 Milvus 里只有 5 篇，它就只返回 5 篇。`topK` 参数是"最多返回几条"，不是"必须返回几条"。

### Q7：重排模型要额外付费吗？

按 token 计费。每次 20 篇文档、每篇约 800 字的典型场景，一次调用成本大约几分钱。

---

**改完以后如果要验证改动是否生效，看这三点：**

1. 启动日志出现 `ReRank 服务初始化完成`
2. 提问后日志出现 `开始重排` 和 `重排完成`
3. 答案质量比之前更聚焦、引用更精准
