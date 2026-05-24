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

import javax.naming.directory.SearchResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ReRankService reRankService;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled; // 是否启用重排，默认启用

    @Value("${rag.recall-k:20}")
    private int recallK; // 重排时的宽召回数量，固定为 20 条（可以调整，但不宜过大）
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
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
}
