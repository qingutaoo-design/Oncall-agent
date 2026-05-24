package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 向量嵌入服务
 * 使用阿里云 DashScope Text Embedding API
 */
@Service
public class VectorEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    @Value("${dashscope.embedding.cache.max-size:500}")
    private int cacheMaxSize;

    @Value("${dashscope.embedding.cache.expire-after-write:24h}")
    private String cacheExpireAfterWrite;

    /** Embedding 多级缓存：key=内容原文，value=向量 */
    private Cache<String, List<Float>> embeddingCache;

    private TextEmbedding textEmbedding;

    @PostConstruct
    public void init() {
        // ── 验证 API Key ──
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            log.error("API Key 未正确配置！当前值: {}", apiKey);
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY 或在 application.yml 中配置正确的 API Key");
        }
        
        // 打印 API Key 前缀用于调试（不打印完整 Key 保证安全）
        String maskedKey = apiKey.length() > 8 ? 
            apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4) : 
            "***";
        log.info("API Key 已加载: {}", maskedKey);
        
        // 设置全局 API Key（确保设置成功）
        Constants.apiKey = apiKey;
        
        // 验证 API Key 是否设置成功
        if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
            log.error("Constants.apiKey 设置失败！");
            throw new IllegalStateException("API Key 设置到 Constants 失败");
        }
        
        log.info("Constants.apiKey 已设置: {}", Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "...");
        
        // ── 初始化 Embedding 多级缓存 ──
        // 解析过期时间配置（例如 "24h" → 24 小时）
        long expireMillis = parseExpireConfig(cacheExpireAfterWrite);
        
        embeddingCache = Caffeine.newBuilder()
                .maximumSize(cacheMaxSize)
                .expireAfterWrite(expireMillis, TimeUnit.MILLISECONDS)
                .recordStats()  // 开启统计，便于监控缓存命中率
                .build();
        
        log.info("Embedding 缓存初始化完成: maxSize={}, expireAfterWrite={}",
                cacheMaxSize, cacheExpireAfterWrite);
        
        // ── 创建 TextEmbedding 实例 ──
        textEmbedding = new TextEmbedding();
        
        log.info("阿里云 DashScope Embedding 服务初始化完成，模型: {}", model);
    }

    /**
     * 解析缓存过期时间配置
     * 支持格式：24h（小时）, 60m（分钟）, 3600s（秒）
     */
    private long parseExpireConfig(String config) {
        if (config == null || config.isEmpty()) {
            return TimeUnit.HOURS.toMillis(24);  // 默认 24 小时
        }
        String trimmed = config.trim().toLowerCase();
        try {
            if (trimmed.endsWith("h")) {
                // 去掉最后一个字符 'h'，取前面的数字部分
                String numPart = trimmed.substring(0, trimmed.length() - 1);
                return TimeUnit.HOURS.toMillis(Long.parseLong(numPart));
            } else if (trimmed.endsWith("m")) {
                String numPart = trimmed.substring(0, trimmed.length() - 1);
                return TimeUnit.MINUTES.toMillis(Long.parseLong(numPart));
            } else if (trimmed.endsWith("s")) {
                String numPart = trimmed.substring(0, trimmed.length() - 1);
                return TimeUnit.SECONDS.toMillis(Long.parseLong(numPart));
            } else {
                return Long.parseLong(trimmed);  // 无后缀，当作毫秒
            }
        } catch (NumberFormatException e) {
            log.warn("缓存过期时间配置格式无法解析: '{}'，使用默认 24h", config);
            return TimeUnit.HOURS.toMillis(24);
        }
    }

    /**
     * 生成向量嵌入
     * 调用阿里云 DashScope Text Embedding API
     * 
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                log.warn("内容为空，无法生成向量");
                throw new IllegalArgumentException("内容不能为空");
            }

            log.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());

            // ── 第 1 步：检查缓存 ──
            List<Float> cached = embeddingCache.getIfPresent(content);
            if (cached != null) {
                log.info("Embedding 缓存命中: 内容长度 {} 字符", content.length());
                return cached;
            }
            log.debug("Embedding 缓存未命中，即将调用 API: 内容长度 {} 字符", content.length());

            // ── 第 2 步：调用 API ──
            // 确保 API Key 已设置（防止被其他地方覆盖）
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                log.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }
            
            log.debug("调用 API 前 Constants.apiKey: {}",
                Constants.apiKey != null ? Constants.apiKey.substring(0, Math.min(8, Constants.apiKey.length())) + "..." : "null");

            // 构建请求参数
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(Collections.singletonList(content))
                    .build();

            // 调用 API
            TextEmbeddingResult result = textEmbedding.call(param);

            // 检查结果
            List<Float> floatEmbedding = getFloats(result);

            log.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}",
                content.length(), floatEmbedding.size());

            // ── 第 3 步：存入缓存（存不可变副本，防止调用方意外修改导致缓存污染） ──
            List<Float> safeEmbedding = Collections.unmodifiableList(new ArrayList<>(floatEmbedding));
            embeddingCache.put(content, safeEmbedding);
            log.info("Embedding 已缓存: 内容长度 {} 字符", content.length());

            return safeEmbedding;

        } catch (NoApiKeyException e) {
            log.error("API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            log.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static List<Float> getFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope API 返回空结果");
        }

        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> embeddings = output.getEmbeddings();

        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope API 返回空向量列表");
        }

        // 获取第一个文本的向量
        List<Double> embeddingDoubles = embeddings.get(0).getEmbedding();

        // 转换为 List<Float>
        List<Float> floatEmbedding = new ArrayList<>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            floatEmbedding.add(value.floatValue());
        }
        return floatEmbedding;
    }

    /**
     * 批量生成向量嵌入
     * 
     * @param contents 文本内容列表
     * @return 向量嵌入列表
     */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                log.warn("内容列表为空，无法生成向量");
                return Collections.emptyList();
            }

            log.info("开始批量生成向量嵌入, 总量: {}, 将利用缓存过滤重复内容", contents.size());

            // ── 第 1 步：分离缓存命中与未命中 ──
            // key=原文内容 → value=对应向量，用于保持原始顺序
            List<String> uncachedContents = new ArrayList<>();
            Map<String, List<Float>> cacheHitMap = new HashMap<>();

            for (String content : contents) {
                List<Float> cached = embeddingCache.getIfPresent(content);
                if (cached != null) {
                    cacheHitMap.put(content, cached);
                } else {
                    uncachedContents.add(content);
                }
            }

            // ── 第 2 步：如果全部命中缓存，直接返回 ──
            if (uncachedContents.isEmpty()) {
                log.info("批量 Embedding 全部命中缓存: {} 条", contents.size());
                List<List<Float>> allResults = new ArrayList<>();
                for (String content : contents) {
                    allResults.add(cacheHitMap.get(content));
                }
                return allResults;
            }

            log.info("批量 Embedding: 缓存命中 {} 条, 待调用 API: {} 条",
                cacheHitMap.size(), uncachedContents.size());
            
            // ── 第 3 步：调用 API 为未命中的内容生成向量 ──
            // 确保 API Key 已设置
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                log.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }

            // 构建请求参数 - 只传未缓存的内容
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(uncachedContents)
                    .build();

            // 调用 API
            TextEmbeddingResult result = textEmbedding.call(param);

            // 检查结果
            if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
                throw new RuntimeException("批量 DashScope API 返回空结果");
            }

            List<TextEmbeddingResultItem> embeddingItems = result.getOutput().getEmbeddings();
            
            if (embeddingItems.isEmpty()) {
                throw new RuntimeException("批量 DashScope API 返回空向量列表");
            }

            // ── 第 4 步：缓存新结果，构建完整结果集 ──
            // 先把 API 返回的结果缓存起来（存不可变副本，防缓存污染）
            for (int i = 0; i < uncachedContents.size(); i++) {
                String content = uncachedContents.get(i);
                TextEmbeddingResultItem item = embeddingItems.get(i);
                
                List<Double> embeddingDoubles = item.getEmbedding();
                List<Float> embedding = new ArrayList<>(embeddingDoubles.size());
                for (Double value : embeddingDoubles) {
                    embedding.add(value.floatValue());
                }
                
                // 存入缓存（不可变包装）
                List<Float> safeEmbedding = Collections.unmodifiableList(new ArrayList<>(embedding));
                embeddingCache.put(content, safeEmbedding);
                // 同时放入 resultByContent，方便后续按原始顺序组装
                cacheHitMap.put(content, safeEmbedding);
            }

            // ── 第 5 步：按原始顺序组装结果 ──
            List<List<Float>> allResults = new ArrayList<>();
            for (String content : contents) {
                allResults.add(cacheHitMap.get(content));
            }

            log.info("成功批量生成向量嵌入, 总数: {}, 缓存命中: {}, 实际 API 调用: {}",
                contents.size(), cacheHitMap.size() - uncachedContents.size(), uncachedContents.size());

            return allResults;

        } catch (NoApiKeyException e) {
            log.error("批量调用时 API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            log.error("批量生成向量嵌入失败", e);
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     * 
     * @param query 查询文本
     * @return 向量嵌入
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    /**
     * 计算两个向量的余弦相似度
     * 
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 余弦相似度 [-1, 1]
     */
    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
