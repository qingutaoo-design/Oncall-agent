package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.io.Files.getFileExtension;

/**
 * 文档分片服务
 * 负责将长文档切分为多个有语义完整性的小片段
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    @Autowired
    private DocumentChunkConfig chunkConfig;

    /** CSV 每组行数（一组数据合并为一个 chunk） */
    @Value("${document.chunk.csv-rows-per-chunk:5}")
    private int csvRowsPerChunk;// CSV 每组行数，默认 5 行

    /**
     * 文档分片入口 —— 根据文件后缀自动选择切片策略
     *
     * @param content  文件解析后的纯文本内容
     * @param filePath 原始文件路径（用于判断后缀）
     * @return 分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return List.of();
        }

        String extension = getFileExtension(filePath);
        logger.info("开始文档分片: {}, 格式: {}, 内容长度: {}", filePath, extension, content.length());

        return switch (extension) {
            case "md"   -> chunkWithHeadings(content, filePath);
            case "html", "htm" -> chunkWithHeadings(content, filePath);
            case "csv"  -> chunkCsv(content, filePath);
            case "json" -> chunkJson(content, filePath);
            default     -> chunkWithHeadings(content, filePath);  // txt/pdf/docx也尝试找标题
        };
    }

    /**
     * 按标题结构切片（适用于 .md, .html, .docx, 以及任何包含标题标记的文本）
     *
     * 策略：先按 # 标题切章节 → 每章超过 maxSize 再按段落切 → 还太大就硬切
     */
    private List<DocumentChunk> chunkWithHeadings(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        // 1. 首先尝试按标题分割
        List<Section> sections = splitByHeadings(content);

        // 2. 对每个章节进行进一步分片
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        logger.info("标题结构切片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    /**
     * CSV 文件切片
     *
     * 策略：
     * 1. 第一行作为"表头"（列名）
     * 2. 之后每 N 行数据合成一个 chunk（N = csvRowsPerChunk，默认5）
     * 3. 每个 chunk 都带上表头，让 LLM 能理解列的含义
     *
     * 示例输入:
     *   name,age,city
     *   张三,25,北京
     *   李四,30,上海
     *   王五,28,深圳
     *
     * 示例输出 (chunk):
     *   [CSV数据] 列: name, age, city
     *   1. 张三, 25, 北京
     *   2. 李四, 30, 上海
     */
    private List<DocumentChunk> chunkCsv(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        // 按行分割（兼容 Windows 的 \r\n 和 Unix 的 \n）
        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) {
            return chunks;
        }

        // 第一行是表头
        String header = lines[0].trim();
        if (header.isEmpty()) {
            // 没有表头，当普通文本处理
            return chunkWithHeadings(content, filePath);
        }

        // 数据行分组
        int chunkIndex = 0;
        for (int i = 1; i < lines.length; i += csvRowsPerChunk) {
            StringBuilder chunkContent = new StringBuilder();
            chunkContent.append("[CSV数据] 列: ").append(header).append("\n");

            int end = Math.min(i + csvRowsPerChunk, lines.length);
            for (int j = i; j < end; j++) {
                String line = lines[j].trim();
                if (!line.isEmpty()) {
                    chunkContent.append(lines[j].trim()).append("\n");
                }
            }

            String chunkText = chunkContent.toString().trim();
            if (!chunkText.isEmpty()) {
                DocumentChunk chunk = new DocumentChunk(
                        chunkText, i, end, chunkIndex++
                );
                chunk.setTitle("CSV行 " + i + "-" + (end - 1));
                chunks.add(chunk);
            }
        }

        logger.info("CSV 切片完成: {} -> {} 行数据, {} 个分片",
                filePath, lines.length - 1, chunks.size());
        return chunks;
    }

    /**
     * JSON 文件切片
     *
     * 策略：按顶层 key 切分，每个 key 的内容独立成一个 chunk。
     *
     * 示例输入:
     *   {
     *     "name": "产品A",
     *     "description": "这是一款智能设备...",
     *     "price": 299
     *   }
     *
     * 输出: 3个chunk
     *   [name] 产品A
     *   [description] 这是一款智能设备...
     *   [price] 299
     */
    private List<DocumentChunk> chunkJson(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        try {
            com.google.gson.JsonObject jsonObject =
                    com.google.gson.JsonParser.parseString(content).getAsJsonObject();

            int chunkIndex = 0;
            for (String key : jsonObject.keySet()) {
                String value;
                var element = jsonObject.get(key);

                if (element.isJsonPrimitive()) {
                    value = element.getAsString();
                } else if (element.isJsonArray() || element.isJsonObject()) {
                    // 嵌套结构：保留 JSON 格式，便于 LLM 理解
                    value = element.toString();
                } else {
                    value = element.toString();
                }

                // 构建 chunk 内容
                String chunkText = "[" + key + "]\n" + value;

                DocumentChunk chunk = new DocumentChunk(
                        chunkText, 0, chunkText.length(), chunkIndex++
                );
                chunk.setTitle(key);
                chunks.add(chunk);
            }

            logger.info("JSON 切片完成: {} -> {} 个顶层key, {} 个分片",
                    filePath, jsonObject.keySet().size(), chunks.size());

        } catch (Exception e) {
            // JSON 解析失败（可能不是标准 JSON），退回到默认切法
            logger.warn("JSON 解析失败，使用默认切片策略: {}", e.getMessage());
            return chunkWithHeadings(content, filePath);
        }

        return chunks;
    }

    /**
     * 从文件路径提取扩展名（小写）
     */
    private String getFileExtension(String filePath) {
        if (filePath == null) return "";
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filePath.length() - 1) return "";
        return filePath.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * 按照 Markdown 标题分割文档
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        
        // 匹配 Markdown 标题：# 标题, ## 标题, ### 标题等
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);

        int lastEnd = 0;
        String currentTitle = null;

        while (matcher.find()) {
            // 保存上一个章节
            if (lastEnd < matcher.start()) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(new Section(currentTitle, sectionContent, lastEnd));
                }
            }

            // 更新当前标题
            currentTitle = matcher.group(2).trim();
            lastEnd = matcher.start();
        }

        // 添加最后一个章节
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(new Section(currentTitle, sectionContent, lastEnd));
            }
        }

        // 如果没有找到任何标题，将整个文档作为一个章节
        if (sections.isEmpty()) {
            sections.add(new Section(null, content, 0));
        }

        return sections;
    }

    /**
     * 对单个章节进行分片
     */
    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = section.content;
        String title = section.title;

        // 如果章节内容小于最大尺寸，直接作为一个分片
        if (content.length() <= chunkConfig.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(
                content, 
                section.startIndex, 
                section.startIndex + content.length(), 
                startChunkIndex
            );
            chunk.setTitle(title);
            chunks.add(chunk);
            return chunks;
        }

        // 章节内容较长，需要进一步分片
        // 优先在段落边界分割
        List<String> paragraphs = splitByParagraphs(content);
        
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;

        for (String paragraph : paragraphs) {
            // 如果当前分片加上新段落超过最大尺寸
            if (currentChunk.length() > 0 && 
                currentChunk.length() + paragraph.length() > chunkConfig.getMaxSize()) {
                
                // 保存当前分片
                String chunkContent = currentChunk.toString().trim();
                DocumentChunk chunk = new DocumentChunk(
                    chunkContent,
                    currentStartIndex,
                    currentStartIndex + chunkContent.length(),
                    chunkIndex++
                );
                chunk.setTitle(title);
                chunks.add(chunk);

                // 开始新分片，包含重叠部分
                String overlap = getOverlapText(chunkContent);
                currentChunk = new StringBuilder(overlap);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
            }

            currentChunk.append(paragraph).append("\n\n");
        }

        // 保存最后一个分片
        if (currentChunk.length() > 0) {
            String chunkContent = currentChunk.toString().trim();
            DocumentChunk chunk = new DocumentChunk(
                chunkContent,
                currentStartIndex,
                currentStartIndex + chunkContent.length(),
                chunkIndex
            );
            chunk.setTitle(title);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 按段落分割文本
     */
    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        
        // 按双换行符分割段落
        String[] parts = content.split("\n\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }

        return paragraphs;
    }

    /**
     * 获取重叠文本
     * 从文本末尾提取指定长度的内容作为下一个分片的开头
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }

        // 从末尾提取重叠内容
        String overlap = text.substring(text.length() - overlapSize);
        
        // 尝试在句子边界截断（查找最后一个句号、问号、感叹号）
        int lastSentenceEnd = Math.max(
            overlap.lastIndexOf('。'),
            Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
        );
        
        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }

        return overlap.trim();
    }

    /**
     * 章节数据类
     */
    private static class Section {
        String title;
        String content;
        int startIndex;

        Section(String title, String content, int startIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
        }
    }
}
