# 多格式文件知识库存储 — 功能接入指南

> 适用项目：SuperBizAgent  
> 目标：支持 PDF、Word(docx)、HTML、CSV、JSON 等多种格式文件上传并自动向量化  
> 难度：中级（面向新手，每一步都会详细解释，但涉及的文件较多）

---

## 目录

1. [现状分析：现在只能处理什么](#1-现状分析现在只能处理什么)
2. [改造目标：我们要支持什么](#2-改造目标我们要支持什么)
3. [核心概念：解析 vs 切片](#3-核心概念解析-vs-切片)
4. [切片哲学：不同文件怎么切](#4-切片哲学不同文件怎么切)
5. [代码改动清单](#5-代码改动清单)
6. [步骤一：添加 Maven 依赖](#6-步骤一添加-maven-依赖)
7. [步骤二：新增 FileParserService（文件解析）](#7-步骤二新增-fileparserservice文件解析)
8. [步骤三：增强 DocumentChunkService（智能切片）](#8-步骤三增强-documentchunkservice智能切片)
9. [步骤四：修改 VectorIndexService（串联管道）](#9-步骤四修改-vectorindexservice串联管道)
10. [步骤五：修改配置与上传校验](#10-步骤五修改配置与上传校验)
11. [步骤六：验证与测试](#11-步骤六验证与测试)
12. [常见问题 FAQ](#12-常见问题-faq)

---

## 1. 现状分析：现在只能处理什么

### 1.1 当前的文件处理管道

```
FileUploadController.upload()
    │ 校验文件后缀 → 只允许 "txt, md"
    │ 保存文件到 ./uploads
    ▼
VectorIndexService.indexSingleFile()
    │ Files.readString(path) → 读文件（纯文本）
    ▼
DocumentChunkService.chunkDocument(content, filePath)
    │ Markdown 标题分割 → 段落分割 → 800字分片 + 100字重叠
    ▼
VectorEmbeddingService.generateEmbedding() → Milvus 存储
```

### 1.2 三个瓶颈点

| 瓶颈 | 位置 | 问题 |
|------|------|------|
| **上传校验** | `FileUploadConfig.allowedExtensions` | 配置写死只有 `txt,md`，其他格式直接拒绝 |
| **文件读取** | `VectorIndexService.java:135` | `Files.readString()` 只能读纯文本，PDF/DOCX 是二进制格式，读出来是乱码 |
| **切片策略** | `DocumentChunkService` | 只有一套逻辑（Markdown标题 + 段落），对所有文件无差别处理 |

### 1.3 文件过滤也写死了

`VectorIndexService.java:73-74`：
```java
File[] files = directory.listFiles((dir, name) -> 
    name.endsWith(".txt") || name.endsWith(".md")
);
```

批量索引目录时也只认两种格式。

---

## 2. 改造目标：我们要支持什么

| 格式 | 后缀 | 难度 | 需要的库 | 
|------|------|------|---------|
| 纯文本 | `.txt` | 已有 | 无 |
| Markdown | `.md` | 已有 | 无 |
| **PDF** | `.pdf` | 🟡 中 | **Apache PDFBox** |
| **Word** | `.docx` | 🟡 中 | **Apache POI** |
| **HTML** | `.html` `.htm` | 🟢 低 | **Jsoup** |
| **CSV** | `.csv` | 🟢 低 | 无（Java 自带） |
| **JSON** | `.json` | 🟢 低 | 无（Jackson 已有） |

### 2.1 新管道架构

```
FileUploadController
    │ 校验后缀 → 扩展到 7 种格式
    ▼
VectorIndexService.indexSingleFile()
    │
    ├── FileParserService.parse(filePath)        ← 新增：根据后缀自动选解析器
    │   ├── .txt  .md  → 直接读文本
    │   ├── .pdf       → PDFBox 提取文字
    │   ├── .docx      → POI 提取文字
    │   ├── .html      → Jsoup 提取文字
    │   ├── .csv       → 读文本 + 按行结构化
    │   └── .json      → 读文本 + 保留层级信息
    │
    ├── DocumentChunkService.chunkDocument(content, filePath)  ← 增强：按后缀选策略
    │   ├── .md  .html → 按标题/标签分
    │   ├── .csv       → 按行分
    │   ├── .json      → 按顶层 key 分
    │   └── 其他        → 按段落分（默认）
    │
    └── VectorEmbeddingService.generateEmbedding()  ← 不变
```

---

## 3. 核心概念：解析 vs 切片

在做任何改动之前，先理解这两个概念的区别。很多新手会搞混。

### 解析（Parse）

**把文件变成纯文本字符串。**

不同格式的文件的本质不同：

| 格式 | 本质 | 解析做什么 |
|------|------|-----------|
| `.txt` | 纯文本 | 什么都不用做，直接读 |
| `.md` | 纯文本 | 什么都不用做，直接读 |
| `.pdf` | 二进制（页面+字体+坐标） | 从二进制中提取文字序列 |
| `.docx` | ZIP 压缩的 XML | 解压并提取 XML 中的文字 |
| `.html` | 纯文本（带标签） | 去掉 `<div>` `<p>` 等标签，只留文字 |
| `.csv` | 纯文本（逗号分隔） | 直接读，但保留行列信息 |
| `.json` | 纯文本（键值对） | 直接读，但保留键值结构 |

### 切片（Chunk）

**把长文本切成小段，每段是一个独立的知识单元。**

这是 `DocumentChunkService` 负责的事，不同的文件结构应该用不同的切法。

### 一句话总结

```
文件(二进制/结构化) ──解析──→ 纯文本字符串 ──切片──→ 多个短字符串(chunks) ──向量化──→ Milvus
     ↑                              ↑                              ↑
  FileParserService            DocumentChunkService          VectorEmbeddingService
```

---

## 4. 切片哲学：不同文件怎么切

这是本次改动最核心的思想部分。请先理解"为什么这样切"，再看代码。

### 4.1 通用原则：从结构到语义到暴力

```
尝试 1: 按文档原生的结构切（标题、章节、行...）
    ↓ 如果不行
尝试 2: 按自然语言的语义边界切（段落、句子）
    ↓ 如果还不行
尝试 3: 按固定字符数硬切（800字一刀 + 100字重叠）
```

### 4.2 各格式的切片策略详解

#### Markdown（`.md`）— 标题结构切分

```
原文:
# 第一章 概述                          ← 第1个chunk的标题
这是第一章的内容...

## 1.1 背景                           ← 第2个chunk的标题
背景说明文字...

# 第二章 安装                          ← 第3个chunk的标题
安装步骤...
```

**切法**：先在 `#` / `##` 处断开，每节内容若超过800字，再按段落切。

**为什么**：Markdown 的 `#` 标题本身就代表了作者对内容的组织逻辑，按标题切最能保持语义完整。

#### HTML（`.html`）— 标签结构切分

```html
<h1>产品手册</h1>          ← chunk 边界
<p>这是一段介绍...</p>

<h2>安装步骤</h2>          ← chunk 边界
<p>第一步...</p>
<p>第二步...</p>
```

**切法**：在 `<h1>`~`<h6>`、`<section>`、`<article>` 处断开。

**为什么**：和 Markdown 类似，HTML 的标题标签就是天然的章节标记。Jsoup 先提取纯文本，但我们会保留标题信息用于切分逻辑。

#### PDF（`.pdf`）— 页边界 + 段落切分

PDF 没有"标题"的明确概念（标题对 PDF 来说只是更大的字）。所以切法不同：

**切法**：
1. 先按页（page）作为大段
2. 每页内用空行（\n\n+）切段落
3. 段落超过 800 字再硬切

**为什么**：PDF 的页是最自然的物理边界，一页通常对应一个相对完整的话题。

#### Word（`.docx`）— 标题样式 + 段落切分

Word 文档有"标题样式"（Heading 1, Heading 2...），这和 Markdown 的 `#` 是同样概念。

**切法**：
1. 用 POI 读取时标记段落样式（Heading / Normal）
2. 遇到 Heading 样式 → 新 chunk 开始
3. Normal 段落按段落累积，超 800 字断开

**为什么**：Word 的标题样式是最可靠的语义分割信号。

#### CSV（`.csv`）— 行切分

```
name,age,city
张三,25,北京
李四,30,上海
```

**切法**：第一行是表头（保留在上下文里），之后每组 N 行数据（默认5行）合并为一个 chunk，格式化为可读文本：

```
表头: name, age, city
数据:
- 张三, 25, 北京
- 李四, 30, 上海
```

**为什么**：CSV 的语义单位是"行"。每行是一个记录，但单行信息太少，所以几行合成一块。表头重复带上是为了让 LLM 理解列含义。

#### JSON（`.json`）— 顶层键切分

```json
{
  "product_name": "智能音箱",
  "description": "一款支持语音控制的智能设备...",
  "specifications": {
    "color": "白色",
    "weight": "500g"
  },
  "user_manual": "请勿在潮湿环境中使用..."
}
```

**切法**：每个顶层 key 独立成一个 chunk，格式化成：

```
[product_name]
智能音箱

[description]
一款支持语音控制的智能设备...
```

**为什么**：JSON 的顶层 key 本身就是信息的分类方式，每个 key-value 是一个独立的知识点。

#### 纯文本（`.txt`）— 段落切分（默认策略）

没有标题、没有标签、没有行列结构。最朴素的切法：

**切法**：按空行（\n\n）切段落 → 段落超 800 字硬切。

**为什么**：段落是自然语言最基本的语义单元。没有结构信息时，段落是唯一可靠的边界。

---

## 5. 代码改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | **添加依赖** | 增加 PDFBox、POI、Jsoup 三个库 |
| `src/main/java/org/example/service/parser/FileParserService.java` | **新增** | 文件解析服务，根据后缀自动选解析器 |
| `src/main/java/org/example/service/DocumentChunkService.java` | **增强** | 增加格式感知的切片策略 |
| `src/main/java/org/example/service/VectorIndexService.java` | **修改** | 替换 `Files.readString()` → `FileParserService.parse()` |
| `src/main/resources/application.yml` | **修改** | 扩展白名单后缀、增加 CSV 行数配置 |

---

## 6. 步骤一：添加 Maven 依赖

### 6.1 修改 pom.xml

**文件路径**：`pom.xml`

在 `<dependencies>` 标签内部的末尾（`</dependencies>` 之前）添加以下三个依赖：

```xml
        <!-- ==================== 文件解析相关依赖 ==================== -->

        <!-- Apache PDFBox - 从 PDF 提取文字 -->
        <!-- 官网: https://pdfbox.apache.org/ -->
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>3.0.3</version>
        </dependency>

        <!-- Apache POI - 从 Word(docx) 提取文字 -->
        <!-- 官网: https://poi.apache.org/ -->
        <dependency>
            <groupId>org.apache.poi</groupId>
            <artifactId>poi-ooxml</artifactId>
            <version>5.3.0</version>
        </dependency>

        <!-- Jsoup - 从 HTML 提取纯文本 -->
        <!-- 官网: https://jsoup.org/ -->
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.18.1</version>
        </dependency>
```

### 6.2 三个库的选型理由

| 库 | 为什么选它 |
|----|-----------|
| **PDFBox** | Apache 顶级项目，Java 生态中 PDF 处理的事实标准，纯 Java 实现无需安装额外软件 |
| **POI** | Apache 顶级项目，处理 Microsoft Office 文档的唯一靠谱选择，支持 docx/xlsx/pptx |
| **Jsoup** | 极简的 HTML 解析器，一行代码就能提取纯文本，比正则表达式可靠得多 |

---

## 7. 步骤二：新增 FileParserService（文件解析）

### 7.1 文件的作用

**一句话**：它的唯一工作就是"把任何格式的文件变成纯文本字符串"。之后的切片、向量化都只认字符串，不关心原始格式。

### 7.2 创建新文件

**文件路径**：`src/main/java/org/example/service/parser/FileParserService.java`

> 新建 `parser` 目录，放在 `src/main/java/org/example/service/parser/` 下。

**完整代码**：

```java
package org.example.service.parser;

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
```

### 7.3 代码讲解

#### 这个类的核心思想

```
FileParserService.parse(path)
    │
    ├── 看后缀 .pdf  →  parsePdf()     用 PDFBox 提取文字
    ├── 看后缀 .docx →  parseDocx()    用 POI 提取文字（并保留标题结构）
    ├── 看后缀 .html →  parseHtml()    用 Jsoup 提取文字
    └── 其他后缀     →  parsePlainText()  直接读文件内容
```

#### 为什么 DOCX 解析时加了 `###`

注意 `parseDocx()` 方法里的这段逻辑：

```java
if (styleLower.contains("heading")) {
    text.append("#".repeat(hashCount)).append(" ");
}
```

Word 文档里的"标题1"、"标题2"样式，被转换成了 Markdown 的 `#`、`##`。这样做的目的是：**让 Docx 文件解析之后，现有的 `DocumentChunkService`（它只认识 Markdown 的 `#` 标题）可以直接按标题切分，不需要改任何代码。**

这是"向后兼容"的设计思想——让新格式输出兼容旧格式的处理逻辑。

#### 关于 PDF 扫描件

如果 PDF 是扫描件（图片转的 PDF），PDFBox 提取不出文字，返回的文本是空的。这种情况下我们在 `parsePdf()` 里会返回一段提示文字（而不是空字符串），这样至少知道发生了什么。

---

## 8. 步骤三：增强 DocumentChunkService（智能切片）

### 8.1 修改思路

当前的 `DocumentChunkService.chunkDocument(content, filePath)` 已经接受了 `filePath` 参数，只需要在里面加上"根据后缀分发到不同策略"的逻辑。

### 8.2 修改文件

**文件路径**：`src/main/java/org/example/service/DocumentChunkService.java`

#### 改动点 1：在类顶部添加新常量

在 `logger` 字段后面添加：

```java
    /** CSV 每组行数（一组数据合并为一个 chunk） */
    @Value("${document.chunk.csv-rows-per-chunk:5}")
    private int csvRowsPerChunk;
```

#### 改动点 2：修改 `chunkDocument` 入口方法

找到 `chunkDocument` 方法（约第 35 行），将方法体改为：

```java
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return List.of();
        }

        // 根据文件后缀选择不同的切片策略
        String extension = getFileExtension(filePath);
        logger.info("开始文档分片: {}, 格式: {}, 内容长度: {}", filePath, extension, content.length());

        return switch (extension) {
            case "md"   -> chunkByHeadings(content, filePath);    // Markdown 标题切分
            case "html", "htm" -> chunkByHeadings(content, filePath);  // HTML 也用标题逻辑
            case "csv"  -> chunkCsv(content, filePath);           // CSV 按行切分
            case "json" -> chunkJson(content, filePath);          // JSON 按键切分
            default     -> chunkByParagraphs(content, filePath);  // 默认：段落切分（txt/pdf/docx等）
        };
    }
```

#### 改动点 3：把原来的 `chunkDocument` 逻辑搬到 `chunkByHeadings`

原来的 `chunkDocument` 方法体（按 Markdown 标题分割 → 段落分割）其实就是 `chunkByHeadings` 策略。重命名即可。

**原来的方法签名 `public List<DocumentChunk> chunkDocument(...)` 改为：**

```java
    /**
     * 按标题结构切片（适用于 .md, .html）
     * 
     * 策略：先按 # 标题切章节 → 每章超过800字再按段落切 → 还太大就硬切
     */
    private List<DocumentChunk> chunkByHeadings(String content, String filePath) {
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
```

然后把原来的 `chunkByHeadings` 的逻辑用一个新的公共入口包裹，原来的方法体不变。

> 实际上，你不需要重命名旧方法。原来的 `chunkDocument` 内部调用了 `splitByHeadings` 和 `chunkSection`。你只需要：
> 1. 把 `chunkDocument` 改为分发入口
> 2. 添加一个新方法 `chunkByParagraphs` 包装段落切分逻辑
> 3. 原来的 `splitByHeadings` 和 `chunkSection` 保持不动

**更简洁的操作方案（推荐）**：

把第 35~56 行的 `chunkDocument` 方法**替换为**：

```java
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
```

然后在文件末尾（第 228 行 `}` 之前）依次添加以下新方法。

#### 改动点 4：新增 `chunkWithHeadings`（包装原来的逻辑）

```java
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
```

> 注意：这个方法`不是`把 `chunkDocument` 改名，而是把 `chunkDocument` 原来的 36~56 行逻辑抽成一个独立的 `chunkWithHeadings` 方法。`splitByHeadings` 和 `chunkSection` 这两个现有的 `private` 方法不需要任何修改，继续留在原地即可。

#### 改动点 5：新增 CSV 切片策略

```java
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
```

#### 改动点 6：新增 JSON 切片策略

```java
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
```

#### 改动点 7：新增工具方法 `getFileExtension`

```java
    /**
     * 从文件路径提取扩展名（小写）
     */
    private String getFileExtension(String filePath) {
        if (filePath == null) return "";
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filePath.length() - 1) return "";
        return filePath.substring(dotIndex + 1).toLowerCase();
    }
```

#### 改动点 8：添加 import（文件顶部）

确保文件头部有以下 import：

```java
import org.springframework.beans.factory.annotation.Value;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
```

---

## 9. 步骤四：修改 VectorIndexService（串联管道）

**文件路径**：`src/main/java/org/example/service/VectorIndexService.java`

### 改动点 1：注入 FileParserService

在类顶部的字段区域添加：

```java
    @Autowired
    private FileParserService fileParserService;
```

### 改动点 2：替换 `indexSingleFile` 中的文件读取方式

找到第 135 行：

```java
        String content = Files.readString(path);
```

**替换为**：

```java
        // 使用 FileParserService 解析文件（支持 PDF/DOCX/HTML 等多种格式）
        String content = fileParserService.parse(path);
        if (content == null || content.trim().isEmpty()) {
            logger.warn("文件解析结果为空: {}", filePath);
            throw new IllegalArgumentException("文件内容为空或无法解析: " + filePath);
        }
```

### 改动点 3：更新 `indexDirectory` 的文件过滤器

找到第 73~74 行：

```java
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );
```

**替换为**：

```java
            // 获取所有支持格式的文件
            File[] files = directory.listFiles((dir, name) -> {
                String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
                return fileParserService.supports(ext);
            });
```

### 改动点 4：清理不再需要的 import

`Files` 和 `Path` 和 `Paths` 的 import 仍然需要（其他地方在用），但可以删除 `java.nio.file.Files` 中只用于 `readString` 的依赖——不过实际上 `Files` 在其他地方（上传路径检查）也用了，所以保留即可。

---

## 10. 步骤五：修改配置与上传校验

### 10.1 修改 application.yml

**文件路径**：`src/main/resources/application.yml`

找到第 9~12 行：

```yaml
file:
  upload:
    path: ./uploads
    allowed-extensions: txt,md
```

**替换为**：

```yaml
file:
  upload:
    path: ./uploads
    allowed-extensions: txt,md,pdf,docx,html,htm,csv,json
```

找到第 60~62 行：

```yaml
document:
  chunk:
    max-size: 800
    overlap: 100
```

**替换为**：

```yaml
document:
  chunk:
    max-size: 800   # 每个分片最大字符数
    overlap: 100    # 分片之间的重叠字符数
    csv-rows-per-chunk: 5  # CSV文件每组行数（一组数据合并为一个chunk）
```

### 10.2 你不需要改 FileUploadController

`FileUploadController` 校验后缀时读的就是 `FileUploadConfig.getAllowedExtensions()`，而 `FileUploadConfig` 的值来自 `application.yml` 的 `file.upload.allowed-extensions`。你只改 yml 就够了，控制器代码不用动。

---

## 11. 步骤六：验证与测试

### 11.1 编译检查

```bash
cd SuperBizAgent-release
mvn compile
```

### 11.2 准备测试文件

在 `uploads` 目录下放几个测试文件（或者通过 API 上传）：

```bash
# 通过 API 上传测试
curl -X POST http://localhost:9900/api/upload -F "file=@test.pdf"
curl -X POST http://localhost:9900/api/upload -F "file=@test.docx"
curl -X POST http://localhost:9900/api/upload -F "file=@test.html"
curl -X POST http://localhost:9900/api/upload -F "file=@test.csv"
curl -X POST http://localhost:9900/api/upload -F "file=@test.json"
```

### 11.3 观察日志

正常情况下的日志输出：

```
开始解析文件: test.pdf, 格式: pdf
PDF 解析完成: test.pdf, 页数: 3, 文字长度: 2534
读取文件: D:\OncallAgent\uploads\test.pdf, 内容长度: 2534 字符
开始文档分片: D:\OncallAgent\uploads\test.pdf, 格式: pdf, 内容长度: 2534
标题结构切片完成: D:\OncallAgent\uploads\test.pdf -> 5 个分片
分片 1/5 索引成功
...
文件索引完成: D:\OncallAgent\uploads\test.pdf, 共 5 个分片
```

### 11.4 验证每种格式的切片效果

| 格式 | 期望行为 |
|------|---------|
| `.pdf` | 日志出现 `PDF 解析完成`，分片按段落边界 |
| `.docx` | 日志出现 `DOCX 解析完成`，Word 标题样式被转成 `#` 标记 |
| `.html` | 日志出现 `HTML 解析完成`，`<script>` `<style>` 内容被剔除 |
| `.csv` | 日志出现 `CSV 切片完成`，每 5 行一个 chunk |
| `.json` | 日志出现 `JSON 切片完成`，每个顶层 key 一个 chunk |

---

## 12. 常见问题 FAQ

### Q1：为什么不支持 `.doc`（旧版 Word）？

旧版 `.doc` 是二进制格式，和 `.docx`（ZIP+XML）完全不同。Apache POI 对 `.doc` 的支持有限且不稳定。建议用户将 `.doc` 转换为 `.docx` 后再上传。

### Q2：PDF 里的表格怎么办？

PDFBox 的 `PDFTextStripper` 按阅读顺序提取文字，表格的内容会被提取出来，但行列结构会丢失。如果表格是重要信息，建议同时上传 CSV 或 Excel 版本。

### Q3：如果我的 JSON 嵌套很深怎么办？

当前实现只切顶层 key。嵌套结构（`specifications.color`, `specifications.weight`）会被序列化为 JSON 字符串放在一个 chunk 里。对于 LLM 来说，读一小段 JSON 比读破碎的路径片段更容易理解。

### Q4：CSV 的编码问题怎么处理？

`Files.readString(path)` 默认使用 UTF-8。如果 CSV 文件是 GBK/GB2312 编码，读出来会乱码。解决方案：用 `Files.readString(path, Charset.forName("GBK"))`。你可以把这个作为后续增强方向。

### Q5：能不能支持 Excel（.xlsx）？

可以。Apache POI 本身就支持 `.xlsx`，逻辑和 CSV 类似（有行列结构）。如果你想加，步骤是：
1. 在 `FileParserService` 里增加 `parseXlsx()` 方法
2. 在 `DocumentChunkService` 里增加 `chunkXlsx()` 方法
3. 配置里加 `xlsx` 白名单

本指南为保持篇幅，先跳过 Excel。如果你需要，我可以单独出一份。

### Q6：解析 PDF 时内存会炸吗？

PDFBox 的 `Loader.loadPDF()` 会把整个 PDF 加载到内存。对于几百页的大 PDF（>50MB），可能会 OOM。建议在 `parsePdf()` 开头加一个文件大小检查：

```java
long fileSize = Files.size(filePath);
if (fileSize > 50 * 1024 * 1024) {  // 50MB
    logger.warn("PDF 文件过大({}MB), 跳过", fileSize / 1024 / 1024);
    return "[文件过大，超过50MB限制]";
}
```

### Q7：为什么 DOCX 的标题要转成 Markdown 的 `###`？

这是为了让现有的 `DocumentChunkService.splitByHeadings()` 能够直接识别。`splitByHeadings` 用正则 `^(#{1,6})\\s+(.+)$` 匹配标题。DOCX 转换后的 `### 标题文字` 刚好符合这个模式，零成本复用。

### Q8：上传了不支持的格式会怎样？

`FileUploadController.isAllowedExtension()` 会拦截，返回 HTTP 400 + 错误消息，告诉你只支持哪些格式。

---

## 附录 A：完整的新文件处理管道流程图

```
用户上传文件 (任意格式)
    │
    ▼
FileUploadController      ← 校验后缀是否在白名单
    │
    ▼
VectorIndexService.indexSingleFile()
    │
    ├──(1) FileParserService.parse(filePath)
    │       │
    │       ├── .txt .md .csv .json → Files.readString() 直接读
    │       ├── .pdf                → PDFBox 提取文字
    │       ├── .docx               → POI 提取文字 + 保留标题样式
    │       └── .html .htm          → Jsoup 提取纯文本
    │
    ├──(2) DocumentChunkService.chunkDocument(text, filePath)
    │       │
    │       ├── .md .html       → chunkWithHeadings (标题切分)
    │       ├── .csv            → chunkCsv (按行切分，每N行一组)
    │       ├── .json           → chunkJson (按顶层key切分)
    │       └── .txt .pdf .docx → chunkWithHeadings (尝试标题，没有则段落切分)
    │
    ├──(3) VectorEmbeddingService.generateEmbedding()
    │
    └──(4) Milvus 存储
```

## 附录 B：改完后的 configuration 验证清单

| 检查项 | 操作 |
|--------|------|
| `pom.xml` 新增 3 个依赖 | `mvn dependency:tree` 确认 PDFBox/POI/Jsoup 存在 |
| `application.yml` 白名单更新 | 上传一个 PDF，看是否返回 400 |
| `FileParserService` 正常注入 | 看启动日志有没有 Error |
| CSV 切行数可配 | 改 `csv-rows-per-chunk: 3` → 重启 → 上传 CSV → 看 chunk 数变化 |

---

**如果遇到编译问题，检查这三点：**
1. Maven 依赖是否下载成功（网络问题可能导致 PDFBox/POI 下载失败）
2. import 语句是否全部添加（IDEA 通常自动提示，但手动操作容易漏）
3. 新建的 `parser` 目录包名是否和代码中的 `package` 声明一致
