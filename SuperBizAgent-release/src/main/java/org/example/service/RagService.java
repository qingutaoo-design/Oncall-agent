package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 结合向量检索和大语言模型生成答案
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.model:qwen3-30b-a3b-thinking-2507}")
    private String model;

    private Generation generation;

    @Autowired
    private ReRankService reRankService;

    @Value("${rag.recall-k:20}")
    private int recallK;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

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

    /**
     * 流式处理用户问题（不带历史消息）
     * 
     * @param question 用户问题
     * @param callback 流式回调接口
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 流式处理用户问题（带历史消息）
     * 
     * @param question 用户问题
     * @param history 历史消息列表，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * @param callback 流式回调接口
     */
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

    /**
     * 构建上下文
     */
    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        
        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料 ").append(i + 1).append("】\n");
            context.append(result.getContent()).append("\n\n");
        }
        
        return context.toString();
    }

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

    /**
     * 构建提示词
     */
    private String buildPrompt(String question, String context) {
        return String.format(
            "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n" +
            "参考资料：\n%s\n" +
            "用户问题：%s\n\n" +
            "请基于上述参考资料给出准确、详细的回答。如果参考资料中没有相关信息，请明确说明。",
            context, question
        );
    }

    /**
     * 生成答案（流式）
     * 
     * @param prompt 当前问题的提示词
     * @param history 历史消息列表
     * @param callback 流式回调接口
     */
    private void generateAnswerStream(String prompt, List<Map<String, String>> history, StreamCallback callback) 
            throws NoApiKeyException, ApiException, InputRequiredException {
        
        // 构建消息列表：历史消息 + 当前问题
        List<Message> messages = new ArrayList<>();
        
        // 添加历史消息
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");
            
            if ("user".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build());
            } else if ("assistant".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(content)
                        .build());
            }
        }
        
        // 添加当前用户问题
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        messages.add(userMsg);
        
        logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）", 
            messages.size(), history.size());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        logger.info("开始调用AI模型流式接口...");
        
        Flowable<GenerationResult> result = generation.streamCall(param);
        
        StringBuilder reasoningContent = new StringBuilder();
        StringBuilder finalContent = new StringBuilder();
        
        logger.info("开始接收AI模型流式响应...");

        result.blockingForEach(message -> {
            if (message.getOutput() != null && 
                message.getOutput().getChoices() != null && 
                !message.getOutput().getChoices().isEmpty()) {
                
                // 获取消息内容
                // 注意：qwen3-30b-a3b-thinking-2507 模型会在 content 中返回完整内容
                // reasoning 部分可能需要通过特殊方式提取或者直接包含在 content 中
                String content = message.getOutput().getChoices().get(0).getMessage().getContent();

                if (content != null && !content.isEmpty()) {
                    logger.debug("收到AI模型内容块: {}", content);
                    
                    // 对于 thinking 模型，content 可能包含思考过程和最终答案
                    // 这里我们将所有内容都作为答案返回
                    finalContent.append(content);
                    callback.onContentChunk(content);
                    
                    logger.debug("已调用 onContentChunk 回调");
                } else {
                    logger.debug("收到空内容块，跳过");
                }
            }
        });
        
        logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());

        callback.onComplete(finalContent.toString(), reasoningContent.toString());
        logger.info("已调用 onComplete 回调");
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
