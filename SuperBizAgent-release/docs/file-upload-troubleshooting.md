# 文件上传 400 报错：完整排查与修复教程

## 你遇到的问题

尝试上传 PDF（或其他格式文件）到知识库时，后端返回：

```
HTTP 400 Bad Request
Required part 'file' is not present.
```

翻译成大白话：后端说"我没收到你传的文件"。

---

## 踩坑全景：四道关卡，四次排查

可以把一次文件上传想象成**寄快递**：

```
浏览器 → 前端门卫 → 网络传输 → 后端过滤链 → 业务代码
```

一条链路上有四道关卡挡住了你的 PDF，每一道都是试探性修复后发现下一道：

| 序号 | 锁的名字 | 现象 | 它拦什么 |
|------|---------|------|---------|
| ① | 前端文件类型白名单 | 前端连文件都选不到 | PDF 不在 `accept` / `validateFileType` 名单里 |
| ② | Spring Boot 默认 1MB 限制 | 小文件能传，大文件 400 | 超过 1MB 直接被拒绝 |
| ③ | `@RequestParam` + MultipartResolver 链路问题 | Apifox 正确请求也 400 | Spring Boot 3.2 的过滤器链与 Spring 封装层打架 |
| ④ | 最终方案：绕过 Spring 封装层 | — | 直接走 Servlet API，不走 `@RequestParam` |

---

## ① 第一道关：前端文件类型白名单

### 锁在哪里

前端两处代码限制了只能 `.txt` 和 `.md`：

**`index.html`：**

```html
<input type="file" accept=".txt,.md,.markdown" style="display: none;">
```

**`app.js`（有两处校验调用！）：**

```javascript
// 位置1：handleFileSelect 里
if (!this.validateFileType(file)) {
    this.showNotification('只支持上传 TXT 或 Markdown (.md) 格式的文件', 'error');
    return;
}

// 位置2：uploadFile 里（双重保险）
if (!this.validateFileType(file)) {
    this.showNotification('只支持上传 TXT 或 Markdown (.md) 格式的文件', 'error');
    return;
}

// 校验函数本身
validateFileType(file) {
    const allowedExtensions = ['.txt', '.md', '.markdown'];
    return allowedExtensions.some(ext => fileName.endsWith(ext));
}
```

### 修复

**`index.html`：**

```html
<input type="file" accept=".txt,.md,.markdown,.pdf,.docx,.html,.htm,.csv,.json" style="display: none;">
```

**`app.js` — 改三处（校验函数 + 两处错误提示）：**

```javascript
// 错误提示（两处都改）
this.showNotification('不支持的文件格式，支持: txt, md, pdf, docx, html, htm, csv, json', 'error');

// 校验函数
const allowedExtensions = ['.txt', '.md', '.markdown', '.pdf', '.docx', '.html', '.htm', '.csv', '.json'];
```

> **坑**：修改后浏览器可能缓存了旧 `app.js`，需要 **Ctrl+Shift+R** 硬刷新。

---

## ② 第二道关：Spring Boot 默认 1MB 限制

Spring Boot 框架默认最大上传文件 **1MB**。PDF/DOCX 动辄几 MB，一上传就被拒。

**修复 — `application.yml`：**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

> 50MB 与前端 `app.js` 的校验值保持一致。

---

## ③ 第三道关：`@RequestParam` 与 Spring 封装层

### 这个坑最难排查

前两关修好后，用 Apifox 发送正确的 `multipart/form-data` 请求，依旧返回 `400 MissingServletRequestPartException`，说明是纯后端问题。

### 失败的尝试

以下是经过验证**没有解决问题**的方案，记录下来是为了让读者不走弯路：

| 尝试 | 结果 | 原因 |
|------|------|------|
| 移除 `consumes = "multipart/form-data"` | 无效 | `consumes` 检查不影响 multipart 解析 |
| 添加 `MultipartFilter`（Filter 层优先解析） | 无效 | `MultipartFilter.isMultipart()` 返回 false，进 else 分支 |
| 用 `FilterRegistrationBean` 禁用 `FormContentFilter` | **启动报错** | 空壳 `FilterRegistrationBean` 导致 `Filter must not be null` |
| 用 `@RequestPart` 替代 `@RequestParam` | 未单独验证 | 最终方案已绕过了这个问题 |

### 根因分析

Spring Boot 3.2 的文件上传经过了太多层抽象：

```
请求到达
  → FormContentFilter（可能影响请求体）
    → DispatcherServlet.checkMultipart()
      → MultipartResolver.resolveMultipart()
        → StandardMultipartHttpServletRequest 包装
          → Controller @RequestParam("file")
            → MultipartResolutionDelegate.resolveMultipartArgument()
              → 取文件
```

任何一个环节出错（比如 `FormContentFilter` 与 `MultipartResolver` 的执行顺序、`MultipartResolver` 的 Bean 注册时机、`Resolver` 与 `ArgumentResolver` 的协作），都会导致"文件不见了"。

## ④ 最终方案：甩开 Spring 封装，直接走 Servlet API

### 思路

既然 Spring 的层层封装会丢文件，那就**一步到位**——Controller 直接调用 Servlet 原生的 `HttpServletRequest.getPart("file")`，什么 `@RequestParam`、`MultipartResolver`、`MultipartFilter` 统统不要。

### 最终版 FileUploadController

```java
@RestController
public class FileUploadController {

    @PostMapping("/api/upload")
    public ResponseEntity<?> upload(HttpServletRequest request) {
        // 1. 检查 Content-Type
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
            return ResponseEntity.badRequest()
                .body("Content-Type 必须是 multipart/form-data，当前为: " + contentType);
        }

        // 2. 直接调 Servlet API 取文件
        Part filePart = request.getPart("file");
        if (filePart == null) {
            // 列出所有 part 帮助排查
            return ResponseEntity.badRequest()
                .body("没有找到 'file' 部件，可用部件: " + listParts(request));
        }

        String originalFilename = filePart.getSubmittedFileName();
        // ... 保存文件、调用索引服务
    }
}
```

### 为什么这样能工作

`request.getPart()` 是 **Java Servlet 3.0** 的原生 API，不经过 Spring 任何封装层。只要 `application.yml` 里配置了 `spring.servlet.multipart.*`（→ `MultipartConfigElement`），Tomcat 就会在调用 `getPart()` 时自动解析 multipart 数据。

对比一下新旧链路：

```
旧方案（不可靠）：
  请求 → Filters → Spring MultipartResolver → @RequestParam → MultipartResolutionDelegate → 文件

新方案（可靠）：
  请求 → request.getPart("file") → 文件
```

少一层封装就少一个出错点。

---

## 最终版修改文件清单

| 文件 | 改动 | 原因 |
|------|------|------|
| `index.html` | `accept` 属性添加 pdf/docx/html/htm/csv/json | 浏览器能选到新格式文件 |
| `app.js` | `validateFileType()` 白名单 + 两处错误提示 | 前端放行新格式 |
| `application.yml` | `spring.servlet.multipart.max-file-size: 50MB` | 允许大文件 |
| `FileUploadController.java` | **重写**：用 `request.getPart("file")` 替代 `@RequestParam` | 绕过 Spring 封装层，直接走 Servlet API |
| `WebMvcConfig.java` | 保持原始状态，无额外配置 | 不需要 MultipartFilter/MultipartResolver/FilterRegistrationBean |

---

## 验证方法

### 1. curl 测试（Windows PowerShell）

```powershell
curl -X POST http://localhost:9900/api/upload -F "file=@E:\你的文件.pdf"
```

成功返回：`{"code":200,"message":"success","data":{...}}`

### 2. 网页上传

1. 重启服务
2. 浏览器 **Ctrl+Shift+R** 硬刷新
3. 点击 `···` → 上传文件 → 选 PDF

### 3. Apifox 配置

- Method：`POST`
- URL：`http://localhost:9900/api/upload`
- Body 类型：`form-data`（**不是** JSON，**不是** x-www-form-urlencoded）
- 字段名：`file`（必须叫这个名字）
- 字段类型：`File`

---

## curl 命令速查

```bash
# ✅ 正确：-F 上传文件，字段名 file
curl -X POST http://localhost:9900/api/upload -F "file=@文件路径"

# ❌ 错误：用了 -d（JSON body）
curl -X POST http://localhost:9900/api/upload -d '{"file":"test.pdf"}'

# ❌ 错误：字段名不是 file
curl -X POST http://localhost:9900/api/upload -F "pdf=@test.pdf"

# ❌ 错误：方法不是 POST
curl http://localhost:9900/api/upload -F "file=@test.pdf"
```

---

## 知识点总结

| 概念 | 通俗理解 |
|------|---------|
| `multipart/form-data` | HTTP 传文件的专用格式，能携带二进制数据 |
| `request.getPart()` | Servlet 原生 API，直接从请求中取文件部件，不经过 Spring 封装 |
| `@RequestParam("file") MultipartFile` | Spring 封装方式，依赖 MultipartResolver 链，在 Boot 3.2 下不稳定 |
| `accept` 属性 | 前端文件选择框的过滤器 |
| `max-file-size` | 后端文件大小上限 |
| `-F` vs `-d` | curl 传文件用 `-F`，传 JSON 用 `-d` |

---

## 踩坑心得

```
第一次上传 PDF
  → 前端选不到文件 → 发现 accept/validateFileType 白名单 ← ①

改完能选文件了
  → 上传报 400 → 发现 Spring 默认 1MB 限制 ← ②

改完大小限制
  → Apifox 正确请求仍 400 → 尝试 MultipartFilter/FilterRegistrationBean/consumes
    → MultipartFilter 无效，FilterRegistrationBean 空壳启动报错
    → 发现 Spring 封装链路太深不可靠 ← ③

最终方案
  → 甩开 Spring 封装，直接 `request.getPart("file")` ← ④
  → curl 测试成功 ✓
```

**核心经验**：排查 Spring Boot 的 multipart 上传问题时，如果 `@RequestParam` + `MultipartResolver` 链路屡试不灵，就降级到 Servlet 原生 API——它比 Spring 的任何封装都可靠。
