package org.example.service.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 文件解析服务
 *
 * 职责：把各种格式的文件（.pdf, .docx, .html, .csv, .json, .txt, .md）
 *      统一解析为纯文本字符串，供后续切片和向量化使用。
 *
 * 大白话：不管用户上传的是什么文件，最终都变成一段文字。
 *
 * 支持的格式：
 * - .txt .md  → 直接读（本身就是纯文本）
 * - .pdf       → Apache PDFBox 提取文字
 * - .docx      → Apache POI 提取文字
 * - .html .htm → Jsoup 提取纯文本
 * - .csv       → 直接读（是纯文本，切片时特殊处理）
 * - .json      → 直接读（是纯文本，切片时特殊处理）
 */
@Service
public class FileParserService {

    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);

    /** 所有支持的扩展名 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "pdf", "docx", "html", "htm", "csv", "json"
    );

    /**
     * 判断是否支持某个文件格式
     */
    public boolean supports(String extension) {
        return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * 获取所有支持的扩展名（用于提示用户）
     */
    public String getSupportedExtensions() {
        return String.join(", ", SUPPORTED_EXTENSIONS);
    }

    /**
     * 解析文件 —— 核心入口
     *
     * 根据文件后缀自动选择解析方式，返回纯文本字符串。
     *
     * @param filePath 文件路径
     * @return 文件中的纯文本内容
     */
    public String parse(Path filePath) throws Exception {
        String fileName = filePath.getFileName().toString();
        String extension = getExtension(fileName);

        logger.info("开始解析文件: {}, 格式: {}", fileName, extension);

        return switch (extension) {
            case "pdf"  -> parsePdf(filePath);
            case "docx" -> parseDocx(filePath);
            case "html", "htm" -> parseHtml(filePath);
            // txt, md, csv, json 都是纯文本，直接读即可
            default     -> parsePlainText(filePath);
        };
    }

    // ============================================================
    // 各格式的具体解析方法
    // ============================================================

    /**
     * 纯文本文件（.txt, .md, .csv, .json）
     * 直接读取，不用任何库
     */
    private String parsePlainText(Path filePath) throws IOException {
        logger.debug("读取纯文本文件: {}", filePath.getFileName());
        return Files.readString(filePath);
    }

    /**
     * PDF 文件（.pdf）
     *
     * 使用 Apache PDFBox：
     * 1. Loader.loadPDF() 加载文件
     * 2. PDFTextStripper 提取文字（按阅读顺序）
     * 3. 关闭文档释放资源
     *
     * 注意：扫描型 PDF（图片 PDF）无法提取文字，
     * 这种情况会返回空字符串，由上层处理。
     */
    private String parsePdf(Path filePath) throws IOException {
        logger.debug("解析 PDF 文件: {}", filePath.getFileName());

        // PDDocument 实现了 AutoCloseable，用 try-with-resources 自动关闭
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {

            // 检查是否加密
            if (document.isEncrypted()) {
                logger.warn("PDF 文件已加密，无法提取文字: {}", filePath.getFileName());
                return "[加密的PDF文件，无法提取文字内容]";
            }

            PDFTextStripper stripper = new PDFTextStripper();
            // setSortByPosition(true): 按文字在页面上的位置排序（从左到右，从上到下）
            stripper.setSortByPosition(true);

            String text = stripper.getText(document);

            if (text.isBlank()) {
                logger.warn("PDF 未提取到文字（可能是扫描件/图片PDF）: {}", filePath.getFileName());
                return "[此PDF为扫描件，未包含可提取的文字内容]";
            }

            logger.info("PDF 解析完成: {}, 页数: {}, 文字长度: {}",
                    filePath.getFileName(), document.getNumberOfPages(), text.length());
            return text;
        }
    }

    /**
     * Word 文件（.docx）
     *
     * 使用 Apache POI：
     * 1. XWPFDocument 加载 docx（本质是解压 ZIP + 解析 XML）
     * 2. 遍历每个段落，获取文字
     * 3. 同时获取段落样式（Heading 1/2/3...），用 ### 标记标题层级
     *
     * 注意：只支持 .docx（Office 2007+），不支持旧版 .doc 格式。
     */
    private String parseDocx(Path filePath) throws IOException {
        logger.debug("解析 DOCX 文件: {}", filePath.getFileName());

        StringBuilder text = new StringBuilder();

        // XWPFDocument 也实现了 AutoCloseable
        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String style = paragraph.getStyle();
                String paragraphText = paragraph.getText();

                if (paragraphText.isBlank()) {
                    text.append("\n");
                    continue;
                }

                // 如果是标题样式，在前面加 Markdown 风格的 #
                // 这样后续 DocumentChunkService 的标题切分逻辑可以直接复用
                if (style != null) {
                    String styleLower = style.toLowerCase();
                    if (styleLower.contains("heading")) {
                        // 提取标题级别数字（Heading1 → #, Heading2 → ##）
                        String level = styleLower.replaceAll("[^0-9]", "");
                        int hashCount = level.isEmpty() ? 1 : Math.min(Integer.parseInt(level), 6);
                        text.append("#".repeat(hashCount)).append(" ");
                    }
                }

                text.append(paragraphText).append("\n\n");
            }
        }

        String result = text.toString().trim();
        logger.info("DOCX 解析完成: {}, 段落数: {}, 文字长度: {}",
                filePath.getFileName(),
                result.split("\n\n").length,
                result.length());
        return result;
    }

    /**
     * HTML 文件（.html, .htm）
     *
     * 使用 Jsoup：
     * 1. Jsoup.parse() 解析 HTML
     * 2. .text() 提取纯文本（自动去掉所有标签）
     * 3. 保留换行结构
     *
     * Jsoup 的 .text() 会自动：
     * - 去掉 <script> <style> 标签内容
     * - 去掉所有 HTML 标签
     * - 多个空格合并为一个
     */
    private String parseHtml(Path filePath) throws IOException {
        logger.debug("解析 HTML 文件: {}", filePath.getFileName());

        // charsetName(null) 让 Jsoup 自动检测编码
        Document doc = Jsoup.parse(filePath.toFile(), null);

        // wholeText() 比 text() 保留更多换行结构
        String text = doc.wholeText();

        // 清理多余的连续空行（连续3个以上换行 → 2个换行）
        text = text.replaceAll("\n{3,}", "\n\n");

        logger.info("HTML 解析完成: {}, 文字长度: {}",
                filePath.getFileName(), text.length());
        return text.trim();
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 从文件名提取扩展名（小写）
     */
    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }
}