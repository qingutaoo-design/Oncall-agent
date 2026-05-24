package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeModel.ChatModel.QWEN_MAX.getValue())
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史摘要 + 近期对话）
     *
     * 采用"摘要+滑动窗口"策略控制上下文长度：
     * - 远期的对话会先被 LLM 压缩成摘要（保留语义，减少 token）
     * - 近期对话保持原始形式（保证对当前问题的准确理解）
     *
     * @param recentHistory 近期对话（原始消息列表）
     * @param summary 历史对话摘要（由 LLM 压缩生成），null 表示无摘要
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> recentHistory, String summary) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // ── 基础系统提示 ──
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n\n");
        
        // ── 历史对话摘要（压缩后的远古对话） ──
        if (summary != null && !summary.isEmpty()) {
            systemPromptBuilder.append("--- 历史对话摘要 ---\n");
            systemPromptBuilder.append("以下是之前对话的核心要点，请基于此理解对话上下文：\n");
            systemPromptBuilder.append(summary).append("\n");
            systemPromptBuilder.append("--- 摘要结束 ---\n\n");
        }
        
        // ── 近期对话（原始消息，保留细节） ──
        if (recentHistory != null && !recentHistory.isEmpty()) {
            systemPromptBuilder.append("--- 近期对话 ---\n");
            for (Map<String, String> msg : recentHistory) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 近期对话结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上历史对话摘要和近期对话，回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 压缩历史对话 —— 调 LLM 把旧消息浓缩成一句话摘要
     *
     * 这是"对话摘要+滑动窗口"机制的核心：
     * 当会话历史超过阈值时，把最旧的几对消息发给 LLM 做摘要，
     * 用一段精炼的文字保留其核心语义，替代原始冗长的消息。
     *
     * @param historyPairs 需要压缩的原始消息对列表
     * @return 压缩后的摘要文本；如果压缩失败返回 null
     */
    public String compressHistory(List<Map<String, String>> historyPairs) {
        if (historyPairs == null || historyPairs.isEmpty()) {
            return null;
        }

        try {
            // ── 构建摘要提示词 ──
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("你是一个对话摘要专家。请用一两句简明扼要的话概括以下对话的核心内容。");
            promptBuilder.append("保留最关键的问题和答案要点，忽略客套话和重复信息。\n\n");

            for (Map<String, String> msg : historyPairs) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    promptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    promptBuilder.append("助手: ").append(content).append("\n");
                }
            }

            promptBuilder.append("\n请直接输出概括，不要多余的说明：");

            logger.info("正在压缩 {} 对历史对话...", historyPairs.size() / 2);

            // ── 创建模型实例（和主对话同样的模型配置，但温度更低、输出更短） ──
            DashScopeApi dashScopeApi = createDashScopeApi();
            DashScopeChatModel summaryModel = DashScopeChatModel.builder()
                    .dashScopeApi(dashScopeApi)
                    .defaultOptions(DashScopeChatOptions.builder()
                            .withModel(DashScopeModel.ChatModel.QWEN_MAX.getValue())
                            .withTemperature(0.3)       // 低温度 = 事实准确不发挥
                            .withMaxToken(500)           // 摘要很短
                            .withTopP(0.9)
                            .build())
                    .build();

            // 调用模型（不使用 Agent，只需纯文本生成）
            Prompt prompt = new Prompt(new UserMessage(promptBuilder.toString()));
            var response = summaryModel.call(prompt);
            String summary = response.getResult().getOutput().getText();

            if (summary != null && !summary.trim().isEmpty()) {
                logger.info("历史压缩完成: {} 对 → {} 字符", historyPairs.size() / 2, summary.length());
                return summary.trim();
            } else {
                logger.warn("压缩结果为空");
                return null;
            }

        } catch (Exception e) {
            // 压缩失败不影响主流程，记个日志下次再试
            logger.error("历史压缩失败（不影响本次对话）", e);
            return null;
        }
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
