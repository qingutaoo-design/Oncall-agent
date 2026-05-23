# DeepSeek 配置问题诊断与修复指南

> 适用项目：SuperBizAgent  
> 编写日期：2026-05-23

---

## 📋 目录

1. [问题概述](#1-问题概述)
2. [问题一：API 提供商配置错误（核心问题）](#2-问题一api-提供商配置错误核心问题)
3. [问题二：模型名称无效](#3-问题二模型名称无效)
4. [问题三：代码硬编码了默认模型名（忽略 YAML）](#4-问题三代码硬编码了默认模型名忽略-yaml)
5. [问题四：两个 API Key 配置位置](#5-问题四两个-api-key-配置位置)
6. [解决方案](#6-解决方案)
   - [方案 A：使用真正的 DeepSeek API](#方案-a使用真正的-deepseek-api)
   - [方案 B：改用阿里云 DashScope](#方案-b改用阿里云-dashscope)
7. [如何验证是否修复成功](#7-如何验证是否修复成功)
8. [常见问题](#8-常见问题)

---

## 1. 问题概述

你在 `application.yml` 中配置了 DeepSeek 的 API Key 和模型名：

```yaml
spring:
  ai:
    dashscope:
      api-key: sk-16bad21b94404c308b1892ea329e6d78
      chat:
        options:
          model: deepseek-v4-flash
```

但项目启动后无法正常工作。经检查，**共有 4 个问题**，其中前两个是致命错误，必须修复。

---

## 2. 问题一：API 提供商配置错误（核心问题）

### 怎么回事？

看这段配置：

```yaml
spring:
  ai:
    dashscope:    # ← 这里写的是 dashscope！！！
```

`spring.ai.dashscope.*` 是 **阿里云 DashScope（通义千问）** 的配置路径。DeepSeek 和 DashScope 是**两家完全不同的公司**，就像移动和联通的手机卡不能互换一样。

| 项目 | 阿里云 DashScope | DeepSeek |
|------|-----------------|----------|
| 官网 | https://dashscope.aliyun.com | https://platform.deepseek.com |
| API 地址 | `dashscope.aliyuncs.com` | `api.deepseek.com` |
| API Key | 在阿里云控制台获取 | 在 DeepSeek 平台获取 |
| 模型 | `qwen-turbo`、`qwen-plus`、`qwen-max` 等 | `deepseek-chat`、`deepseek-reasoner` 等 |

**你用 DeepSeek 的 API Key 去调 DashScope 的接口，就像拿移动的SIM卡插到联通手机上——完全不通。**

### 怎么判断？

看你的 API Key 前缀：

- DeepSeek API Key 示例：`sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`
- DashScope API Key 示例：`sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

光看格式**无法区分**，需要看**你在哪个平台申请的**：
- 在 https://platform.deepseek.com 申请的 → DeepSeek Key
- 在 https://dashscope.aliyun.com 申请的 → DashScope Key

---

## 3. 问题二：模型名称无效

### 怎么回事？

你配置的模型名是：

```yaml
model: deepseek-v4-flash
```

**这个模型名在任何一个平台都不存在。**

| 平台 | 正确的模型名 |
|------|------------|
| **DeepSeek** | `deepseek-chat`（旧称 deepseek-v3）或 `deepseek-reasoner`（旧称 deepseek-r1） |
| **DashScope** | `qwen-turbo`、`qwen-plus`、`qwen-max`、`qwen3-max` 等 |

> 💡 `deepseek-v4-flash` 可能是一个流传的假名字。DeepSeek **没有**叫 `v4` 或 `flash` 的模型。

---

## 4. 问题三：代码硬编码了默认模型名（忽略 YAML）

### 怎么回事？

即使你把前面两个问题都修好了，**你的 YAML 配置的模型名也不会生效**。

因为代码里写死了（`ChatService.java` 第 69 行）：

```java
.withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
```

`DEFAULT_MODEL_NAME` 是一个常量，**永远使用默认模型**，完全不读取 YAML 里的 `spring.ai.dashscope.chat.options.model`。

### 结果

你 YAML 里写的 `model: deepseek-v4-flash` → **代码根本没用** → 等于白写。

---

## 5. 问题四：两个 API Key 配置位置

项目中有**两个** API Key 配置位置：

| 配置路径 | 当前值 | 用途 |
|---------|--------|------|
| `spring.ai.dashscope.api-key` | `sk-16bad21b94404c308b1892ea329e6d78` | 聊天/Agent 功能 |
| `dashscope.api.key` | `${QWEN_KEY}`（环境变量） | RAG 问答、向量嵌入 |

两个位置都需要有效的 API Key。目前只有第一个填了值，第二个依赖环境变量 `QWEN_KEY`，如果没设置也会报错。

---

## 6. 解决方案

根据你的需求（你想用 DeepSeek），有两个方案：

- **方案 A（推荐）**：用 DeepSeek 的 OpenAI 兼容接口（需要改代码）
- **方案 B（简单）**：改用阿里云 DashScope（不改代码，但需要 DashScope Key）

---

### 方案 A：使用真正的 DeepSeek API

DeepSeek 提供了**兼容 OpenAI 的 API**，所以可以通过 Spring AI 的 OpenAI 客户端来调用 DeepSeek。

#### 修改操作

总共需要改 **2 个文件**，新增 **0 个文件**。

---

#### 步骤 1：修改 `pom.xml` — 添加 OpenAI 依赖

打开 `pom.xml`，在 `<dependencies>` 里（大约第 72 行附近），添加这一段：

```xml
<!-- DeepSeek (via OpenAI compatible API) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

放在 `<dependency>` 列表的任意位置都可以。

**为什么要加这个？**  
DeepSeek 没有自己的 Spring Boot Starter，但它提供了兼容 OpenAI 的 API（也就是说，用 OpenAI 的客户端程序，把地址改成 DeepSeek 的服务器地址，就能调用 DeepSeek）。

---

#### 步骤 2：修改 `application.yml` — 把 DashScope 配置改为 DeepSeek 配置

打开 `src/main/resources/application.yml`，找到这一段（第 23-36 行）：

```yaml
spring:
  ai:
    dashscope:
      api-key: sk-16bad21b94404c308b1892ea329e6d78
      chat:
        options:
          model: deepseek-v4-flash
          timeout: 180000
```

**替换为：**

```yaml
spring:
  ai:
    openai:
      api-key: sk-16bad21b94404c308b1892ea329e6d78
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
```

> ⚠️ 注意：
> - `api-key` 换成你的 DeepSeek API Key（从 https://platform.deepseek.com 获取）
> - 确保模型名改为正确的 `deepseek-chat` 或 `deepseek-reasoner`
> - 加了 `base-url: https://api.deepseek.com` 告诉程序去 DeepSeek 的服务器

---

#### 步骤 3：修改 `ChatService.java` — 改用 OpenAI 客户端

打开 `src/main/java/org/example/service/ChatService.java`

**① 修改 import 导入（第 3-5 行附近）**

把这三行：

```java
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
```

替换为：

```java
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
```

**② 修改 `@Value` 注入（第 47-48 行）**

把：

```java
@Value("${spring.ai.dashscope.api-key}")
private String dashScopeApiKey;
```

替换为：

```java
@Value("${spring.ai.openai.api-key}")
private String openAiApiKey;

@Value("${spring.ai.openai.base-url}")
private String openAiBaseUrl;
```

**③ 修改 `createDashScopeApi()` 方法（第 53-57 行）**

把整个方法：

```java
public DashScopeApi createDashScopeApi() {
    return DashScopeApi.builder()
            .apiKey(dashScopeApiKey)
            .build();
}
```

替换为：

```java
public OpenAiApi createOpenAiApi() {
    return OpenAiApi.builder()
            .apiKey(openAiApiKey)
            .baseUrl(openAiBaseUrl)
            .build();
}
```

**④ 修改 `createChatModel()` 方法（第 65-75 行）**

把整个方法：

```java
public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
    return DashScopeChatModel.builder()
            .dashScopeApi(dashScopeApi)
            .defaultOptions(DashScopeChatOptions.builder()
                    .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                    .withTemperature(temperature)
                    .withMaxToken(maxToken)
                    .withTopP(topP)
                    .build())
            .build();
}
```

替换为：

```java
public OpenAiChatModel createChatModel(OpenAiApi openAiApi, double temperature, int maxToken, double topP) {
    return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                    .withModel("deepseek-chat")
                    .withTemperature(temperature)
                    .withMaxTokens(maxToken)
                    .withTopP(topP)
                    .build())
            .build();
}
```

**⑤ 修改 `createStandardChatModel()` 方法（第 80-82 行）**

把：

```java
public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
    return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
}
```

替换为：

```java
public OpenAiChatModel createStandardChatModel(OpenAiApi openAiApi) {
    return createChatModel(openAiApi, 0.7, 2000, 0.9);
}
```

**⑥ 修改 `createReactAgent()` 方法的参数类型（第 157 行）**

把：

```java
public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
```

替换为：

```java
public ReactAgent createReactAgent(OpenAiChatModel chatModel, String systemPrompt) {
```

---

#### 步骤 4：修改 `ChatController.java` 和 `AiOpsService.java`

这两个文件中使用了 `DashScopeApi` 和 `DashScopeChatModel`，也需要改为 `OpenAiApi` 和 `OpenAiChatModel`。

**修改 `ChatController.java`**

```diff
- import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
- import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
+ import org.springframework.ai.openai.OpenAiChatModel;
+ import org.springframework.ai.openai.api.OpenAiApi;
```

把所有：

```diff
- DashScopeApi dashScopeApi = chatService.createDashScopeApi();
- DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
+ OpenAiApi openAiApi = chatService.createOpenAiApi();
+ OpenAiChatModel chatModel = chatService.createStandardChatModel(openAiApi);
```

**修改 `AiOpsService.java`**

同样的方式，把 `DashScopeChatModel` 改为 `OpenAiChatModel`，把 `DashScopeChatOptions` 改为 `OpenAiChatOptions`。

---

#### 步骤 5：修改 `DashScopeConfig.java` — 改配置类

这个类配置了超时时间，也依赖 DashScope。可以改为通用配置：

打开 `src/main/java/org/example/config/DashScopeConfig.java`

把文件**重命名为** `OpenAiConfig.java`（或者在原文件基础上改），修改内容：

```java
package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class OpenAiConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(180).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(180).toMillis());
                }});
    }
}
```

---

#### 步骤 6：怎么办 — RAG 和 Embedding 部分

这个项目的 RAG 问答和向量嵌入功能**仍然使用 DashScope 原生 SDK**（`RagService.java`、`VectorEmbeddingService.java`）。

如果你没有 DashScope Key，这些功能会报错。有两种处理方式：

**方式一：也申请一个 DashScope Key**
- 去 https://dashscope.aliyun.com 注册并申请 API Key
- 设置环境变量：`QWEN_KEY=你的DashScope Key`
- RAG 模型改为可用的：`qwen-plus` 或 `qwen-max`

**方式二：暂时关闭 RAG 功能**
- 如果暂时不用 RAG 问答，可以先注释掉相关代码

---

### 方案 B：改用阿里云 DashScope

如果你不想改代码，只想让配置正确工作，可以改用 DashScope。

#### 步骤 1：获取 DashScope API Key

去 https://dashscope.aliyun.com 注册阿里云账号 → 开通 DashScope 服务 → 创建 API Key。

#### 步骤 2：修复模型名称

把 `application.yml` 中的 `deepseek-v4-flash` 改为一个有效的 DashScope 模型：

```yaml
spring:
  ai:
    dashscope:
      api-key: sk-你的DashScopeKey
      chat:
        options:
          model: qwen-plus       # ← 改为有效的 DashScope 模型
```

常用 DashScope 模型：
| 模型名 | 说明 |
|--------|------|
| `qwen-turbo` | 快速、便宜的模型 |
| `qwen-plus` | 均衡型（推荐） |
| `qwen-max` | 最强模型，价格较高 |
| `qwen3-max` | 最新最强模型 |

#### 步骤 3：修复代码中的硬编码

打开 `ChatService.java` 第 69 行：

```diff
- .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
+ .withModel("qwen-plus")  // 建议从 YAML 读取
```

更好的做法是从 YAML 读取模型名：

在 `ChatService.java` 添加：

```java
@Value("${spring.ai.dashscope.chat.options.model}")
private String chatModelName;
```

然后第 69 行改为：

```java
.withModel(chatModelName)
```

#### 步骤 4：设置环境变量

```bash
set QWEN_KEY=sk-你的DashScopeKey
```

---

## 7. 如何验证是否修复成功

### 方法一：启动日志

```bash
# 在项目根目录执行
mvn spring-boot:run
```

观察启动日志：
- 如果看到 `Connected to OpenAI API at https://api.deepseek.com` → DeepSeek 连接成功
- 如果看到 `401 Unauthorized` → API Key 无效
- 如果看到 `404` 或模型不存在 → 模型名写错了

### 方法二：调用测试接口

启动项目后：

```bash
curl http://localhost:9900/api/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"Id\":\"test\",\"Question\":\"你好，请问1+1等于几？\"}"
```

如果返回正常回答 → 修复成功。  
如果返回错误 → 看错误信息排查。

---

## 8. 常见问题

### Q：我到底有没有 DeepSeek API Key？

想一下你的 API Key 是在哪里申请的：
- https://platform.deepseek.com → ✅ 这是 DeepSeek Key
- https://dashscope.aliyun.com → ❌ 这是 DashScope Key，不叫 DeepSeek

### Q：不改代码行不行？

**不行。** 因为：
1. 项目依赖的是 `spring-ai-alibaba-starter-dashscope`（DashScope 的包）
2. DeepSeek 必须用 OpenAI 兼容接口才能调用
3. 代码里硬编码了 DashScope 的类名

至少要改 `pom.xml` + `application.yml` + `ChatService.java`。

### Q：改了之后 RAG 功能还能用吗？

RAG 功能用的是 DashScope 原生 SDK（`RagService.java`），如果改为 DeepSeek 方案后你没有 DashScope Key，RAG 会报错。

建议：先用 DeepSeek 跑通聊天功能，RAG 后续再配置。

### Q：`deepseek-v4-flash` 这个模型名哪里来的？

这不是 DeepSeek 官方模型名。DeepSeek 的官方模型名只有：
- `deepseek-chat`（对话模型，旧称 deepseek-v3）
- `deepseek-reasoner`（推理模型，旧称 deepseek-r1）

建议去 https://platform.deepseek.com 查看最新模型列表。

### Q：我想保持简单，不想改代码怎么办？

选择 **方案 B**：
1. 去阿里云注册 DashScope
2. 拿到 DashScope API Key
3. 把模型名改为 `qwen-plus`
4. 修复代码中的硬编码
5. 设置环境变量

这样几乎不改动项目架构，只改配置和一两行代码。

---

> **总结**：你的核心问题是 **把 DeepSeek 的 API Key 填到了 DashScope 的配置位置**，就像把汽车的钥匙插到了飞机的启动口。请根据本指南选择适合自己的方案进行修复。
