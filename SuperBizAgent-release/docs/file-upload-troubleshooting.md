# 文件上传 400 报错排查与修复教程

## 你遇到的问题

当你尝试上传 PDF（或其他格式文件）到知识库时，后端返回了这样一个错误：

```
HTTP 400 Bad Request
Required part 'file' is not present.
```

**翻译成大白话**：后端说"我没收到你传的文件"。

---

## 为什么会这样？—— 三条路上的三把锁

可以把一次文件上传想象成**寄快递**，整个过程有三道关卡：

```
你（浏览器） → 前端的门卫检查 → 网络传输 → 后端门卫1(过滤器) → 后端门卫2(尺寸) → 业务处理
```

你的 PDF 没能送到后端，是因为**三道关卡各有一把锁**挡住了它：

| 锁的位置 | 锁的名字 | 它拦什么 |
|---------|---------|---------|
| 第一把锁 | 前端文件类型白名单 | PDF 不在名单里，前端直接说"格式不对" |
| 第二把锁 | Spring Boot 文件大小限制（默认 1MB） | 文件稍微大一点，后端直接拒绝开门 |
| 第三把锁 | FormContentFilter 偷吃请求体 | 后端门卫把快递拆开看了，后面的 MultipartResolver 拿到空盒子 |

下面逐一拆解每把锁是怎么拦住你的，以及怎么打开它。

---

## 第一把锁：前端文件类型白名单

### 锁在哪里

前端有两个地方限制了只能上传 `.txt` 和 `.md` 文件：

**位置 1：`index.html` — 文件选择框**

```html
<input type="file" accept=".txt,.md,.markdown" style="display: none;">
```

`accept` 属性告诉浏览器：打开文件选择窗口时，只显示这些后缀名的文件。你的 PDF 文件压根不会出现在选择窗口里。

**位置 2：`app.js` — 文件类型校验函数**

```javascript
validateFileType(file) {
    const fileName = file.name.toLowerCase();
    const allowedExtensions = ['.txt', '.md', '.markdown'];  // ← 这里只有三种
    return allowedExtensions.some(ext => fileName.endsWith(ext));
}
```

即使你绕过了浏览器的 `accept` 限制（比如手动输入文件名），这个 JS 校验也会在点击上传前拦截，弹出提示"只支持上传 TXT 或 Markdown 格式的文件"，然后请求根本不会发出去。

### 为什么会这样写

因为这个项目最初只支持 Markdown 和纯文本文档。后来后端升级了（加了 PDF、Word 等格式支持），但前端没跟着改——就像大门拓宽了，但门卫手里还是旧的通行名单。

### 怎么修

把新支持的格式加到白名单里：

**`index.html` 修改：**

```html
<!-- 修改前 -->
<input type="file" accept=".txt,.md,.markdown" style="display: none;">

<!-- 修改后 -->
<input type="file" accept=".txt,.md,.markdown,.pdf,.docx,.html,.htm,.csv,.json" style="display: none;">
```

**`app.js` 修改：**

```javascript
// 修改前
const allowedExtensions = ['.txt', '.md', '.markdown'];

// 修改后
const allowedExtensions = ['.txt', '.md', '.markdown', '.pdf', '.docx', '.html', '.htm', '.csv', '.json'];
```

同时把错误提示也改一下，让用户知道现在支持哪些格式：

```javascript
// 修改前
this.showNotification('只支持上传 TXT 或 Markdown (.md) 格式的文件', 'error');

// 修改后
this.showNotification('不支持的文件格式，支持: txt, md, pdf, docx, html, htm, csv, json', 'error');
```

---

## 第二把锁：Spring Boot 文件大小限制

### 锁在哪里

Spring Boot 框架默认有一个**最大上传文件大小限制：1MB**。

这个配置写在框架代码里，你在项目里找不到——它就像小区大门的默认门禁规则："超过 1MB 的包裹不准进门"。

### 为什么 1MB 不够

| 文件类型 | 典型大小 |
|---------|---------|
| `.txt` 纯文本 | 几 KB ~ 几十 KB |
| `.md` Markdown | 几 KB ~ 几十 KB |
| `.pdf` PDF 文档 | **几百 KB ~ 几十 MB** |
| `.docx` Word 文档 | **几十 KB ~ 几十 MB** |

纯文本文件几乎不会超过 1MB，所以默认限制一直没暴露出问题。但 PDF 和 Word 文档动辄几 MB 甚至几十 MB，一上传就直接被后端拒之门外。

### 怎么修

在 `application.yml` 里显式声明更大的限制：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB      # 单个文件最大 50MB
      max-request-size: 50MB   # 整个请求体最大 50MB
```

> **为什么要设 50MB？**
> - 前端 `app.js` 里也有一个 50MB 的校验（`const maxSize = 50 * 1024 * 1024`），前后端保持一致
> - 足以覆盖大部分 PDF 和 Word 文件
> - 再大的话向量化会很慢，用户体验不好

---

## 第三把锁：FormContentFilter 偷吃请求体

### 这个问题最隐蔽

> **这是你遇到的 400 错误的真正原因。** 前两把锁修好之后，你发现文件已经能在前端选到了，但上传还是报同样的 400 错误——因为请求体在半路被"偷吃"了。

### 锁在哪里

Spring Boot 3.2 引入了一个叫 `FormContentFilter` 的新过滤器。它的本职工作是把表单数据（`application/x-www-form-urlencoded`）包装一下，让后端代码更方便读取。

但 Spring Framework 6.1 版本的 `FormContentFilter` 有一个兼容性缺陷：**它有时会错误地读取 multipart 请求的 body**（也就是你上传的文件内容），读完之后就把 InputStream 关掉了。

等到后面真正负责解析文件的 `MultipartResolver` 去读请求体时，发现**盒子已经被拆开，里面的内容不见了**——于是报出 "Required part 'file' is not present"。

### 通俗比喻

想象一个仓库收货流程：

```
快递员(浏览器) → 门卫A(FormContentFilter) → 门卫B(MultipartResolver) → 仓库(Controller)
```

门卫 A 的职责是处理"普通信件"（表单数据），门卫 B 的职责是处理"包裹"（文件上传）。但门卫 A 不够聪明，看到一个快递盒子上写着"包裹"（multipart/form-data），还是拆开看了一下。虽然他只是看了看就放下了，但是——**盒子已经被打开了，里面的填充物散了**。等到门卫 B 去收货时，盒子是空的。

### 怎么修

**修改 1：移除 Controller 上多余的 `consumes` 限制**

`FileUploadController.java` 原来的写法：

```java
@PostMapping(value = "/api/upload", consumes = "multipart/form-data")
public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
```

`consumes` 在 Spring Boot 3.2 + FormContentFilter 的组合下有时会干扰请求处理流程。直接去掉：

```java
@PostMapping("/api/upload")
public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
```

**修改 2：在 WebMvcConfig 中显式禁用 FormContentFilter + 注册 MultipartResolver**

`WebMvcConfig.java` 中新增两个 Bean：

```java
/**
 * 显式注册 MultipartResolver，确保 Spring MVC 能正确解析 multipart 请求
 */
@Bean
public MultipartResolver multipartResolver() {
    return new StandardServletMultipartResolver();
}

/**
 * 禁用 FormContentFilter，防止它错误消费 multipart 请求体
 */
@Bean
public FilterRegistrationBean<FormContentFilter> formContentFilterRegistration() {
    FilterRegistrationBean<FormContentFilter> registration = new FilterRegistrationBean<>();
    registration.setEnabled(false);
    return registration;
}
```

**为什么要这样做？**

| 措施 | 作用 |
|------|------|
| 移除 `consumes` | 避免 FormContentFilter + consumes 组合触发请求体的错误读取 |
| 显式注册 `MultipartResolver` | 确保文件解析器一定会生效，不会因为自动配置被跳过 |
| 禁用 `FormContentFilter` | 直接关掉这个可能偷吃请求体的过滤器 |

> **安全性说明**：禁用 `FormContentFilter` 只会影响 `application/x-www-form-urlencoded` 类型的 PUT/PATCH/DELETE 请求的表单数据读取。你的项目只用 POST 传文件，不受影响。

---

## 完整的修改文件清单

| 文件 | 改了什么 | 原因 |
|------|---------|------|
| `src/main/resources/static/index.html` | `<input>` 的 `accept` 属性加入新格式 | 让浏览器允许选择 PDF/DOCX 等文件 |
| `src/main/resources/static/app.js` | `validateFileType()` 白名单扩展 | 让前端 JS 校验通过新格式 |
| `src/main/resources/static/app.js` | 错误提示文案更新 | 告诉用户现在支持哪些格式 |
| `src/main/resources/application.yml` | 新增 `spring.servlet.multipart` 配置 | 把文件大小上限从默认 1MB 提升到 50MB |
| `src/main/java/.../controller/FileUploadController.java` | 移除 `consumes` 属性 | 避免 FormContentFilter 与 consumes 组合触发 bug |
| `src/main/java/.../config/WebMvcConfig.java` | 禁用 `FormContentFilter` | 防止它错误消费 multipart 请求体 |
| `src/main/java/.../config/WebMvcConfig.java` | 显式注册 `MultipartResolver` | 确保文件解析器不会被自动配置跳过 |

---

## 怎么验证修好了

### 方法一：网页上传（推荐）

1. 重启 Spring Boot 服务
2. 浏览器打开 `http://localhost:9900`
3. 点击输入框左边的 **`···`** 按钮 → **上传文件**
4. 选一个 PDF 文件上传
5. 看到成功提示即表示修复成功

### 方法二：curl 命令测试（Windows PowerShell）

```powershell
# 把下面路径换成你的 PDF 文件路径
curl -X POST http://localhost:9900/api/upload -F "file=@C:\你的文件.pdf"
```

如果返回 `{"code":200,"message":"success",...}` 就说明成功了。

> **常见坑**：Windows 下 `curl` 本质上是 `curl.exe` 的别名，参数规则和 Linux 一样。如果你装了 Git Bash，也可以用那边的 curl。

---

## 如果还不行？—— 更多排查点

### 1. 检查你的 curl 命令格式

**错误写法（会报 400）：**

```bash
# ❌ 用了 JSON body（-d）
curl -X POST http://localhost:9900/api/upload -d '{"file":"test.pdf"}'

# ❌ 参数名写错了
curl -X POST http://localhost:9900/api/upload -F "pdf=@test.pdf"
```

**正确写法：**

```bash
# ✅ 必须用 -F，参数名必须是 file
curl -X POST http://localhost:9900/api/upload -F "file=@test.pdf"
```

### 2. 检查后端日志

如果上传后看到这个日志：

```
WARN ... MissingServletRequestPartException: Required part 'file' is not present.
```

说明请求根本没带文件。可能的原因：
- 用 `-d`（JSON body）而不是 `-F`（multipart form）
- 表单字段名写错了（必须是 `file`）
- 用了 GET 而不是 POST

### 3. 检查文件大小

即便改到了 50MB，超出 50MB 的文件还是会被拒绝。可以临时调大测试：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 200MB
      max-request-size: 200MB
```

---

## 知识点总结

| 概念 | 通俗理解 |
|------|---------|
| `multipart/form-data` | 一种 HTTP 请求格式，专门用来传文件。和 JSON（纯文本）不同，它能携带二进制数据 |
| `@RequestParam("file")` | 后端接口说"我要一个名叫 `file` 的表单字段" |
| `accept` 属性 | 前端文件选择框的过滤器，用来限制用户只能看到特定格式的文件 |
| `max-file-size` | 后端的包裹尺寸限制，超限的请求直接拒收 |
| `-F` vs `-d` | curl 中 `-F` = 表单上传，`-d` = 纯文本 JSON。传文件只能用 `-F` |
| `FormContentFilter` | Spring Boot 3.2 的过滤器，负责处理普通表单数据，但有时会"偷吃" multipart 请求体 |
| `MultipartResolver` | 后端专门负责拆包裹（解析文件）的组件，比如 `StandardServletMultipartResolver` |
| `FilterRegistrationBean` | Spring 用来控制某个过滤器是否启用、执行顺序的配置类 |

---

## 三把锁的解决顺序（踩坑心得）

这三把锁是按"发现顺序"慢慢暴露出来的：

```
第一次上传 PDF → 前端根本选不到文件 → 发现第一把锁（accept/validateFileType）
修改后能选到文件了 → 上传还是 400 → 看日志发现 body 为空 → 发现第二把锁（1MB 限制）
改完大小限制 → 还是 400 → 排查堆栈看到 FormContentFilter → 发现第三把锁（过滤器偷吃）
```

**经验**：排查 web 上传问题时，沿着"浏览器 → 网络 → 过滤器链 → 业务代码"这条链路逐个排除，而不是只看最后的错误信息。
