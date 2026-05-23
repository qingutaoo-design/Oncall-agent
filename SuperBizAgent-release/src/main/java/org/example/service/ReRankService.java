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