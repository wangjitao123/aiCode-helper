# AI Code Helper

> 一款 IntelliJ IDEA 的 AI 编程助手插件。其核心已按照 Claude Code 的 **agent harness（智能体框架）** 设计思想重构：基于生成器的查询引擎、ReAct 主循环、工具系统、五层上下文压缩、流式并发工具执行与 Hook 架构。

## 架构总览（Agent Harness）

聊天窗口不再是“发一次请求、拿一次响应”，而是驱动一个真正的 **ReAct 智能体**：模型可以按需调用工具读取项目、搜索代码，逐步推理后再作答。整体设计对应 Claude Code 源码泄漏分析中的六个工程决策：

| # | 决策 | 本项目对应实现 |
|---|------|----------------|
| 1 | **AsyncGenerator 核心** | `QueryEngine.query()` 返回冷 `Flow<AgentEvent>`，逐事件 `emit`（文本增量/工具调用/工具结果/压缩/错误）；下游 `collect` 取消 ≈ `generator.return()` | 
| 2 | **while(true) + State 状态机** | `QueryEngine` 的 ReAct 主循环；`AgentState` 携带 `transition` 字段作为防死循环**断路器**（reactive_compact_retry / max_output_tokens_recovery） |
| 3 | **五层上下文压缩** | `ContextManager`：microcompact → snip → autocompact → reactiveCompact → contextCollapse；常量见 `CompactionConstants`（含运营数据注释） |
| 4 | **流式并发工具执行** | `StreamingToolExecutor`：模型仍在输出时即开始执行就绪工具；按 `isConcurrencySafe()` 调度；`siblingAbort` 同批次取消 |
| 5 | **Hook 架构** | `HookManager` + `PreToolUse/PostToolUse/PostSampling/PreCompact/PostCompact`；PostSampling 错误被吞掉，不影响主循环；`PermissionHook` 做权限控制 |
| 6 | **运营数据驱动决策** | `CompactionConstants` 中每个阈值都附带来历注释（如 p99.99 摘要输出 17,387 tokens → 预算 20,000） |

### 内置工具

| 工具 | 权限 | 并发安全 | 说明 |
|------|------|----------|------|
| `project_structure` | 只读 | ✅ | 项目目录结构与统计 |
| `list_directory` | 只读 | ✅ | 列出目录内容 |
| `read_file` | 只读 | ✅ | 读取文件内容 |
| `grep_search` | 只读 | ✅ | 在源码中搜索文本 |
| `write_file` | 写 | ❌ | 覆盖写文件（默认禁用，需在设置中授权） |

## 功能介绍

### 1. AI 智能体聊天（核心）
- IDEA 右侧 **DEEPWAY CODE** 面板，直接提问
- AI 以 ReAct 方式工作：**调用工具 → 查看工具结果 → 继续推理 → 给出回答**，过程实时可见
- 运行时「发送」按钮变为「停止」，可随时中断（取消整个生成器链）
- 支持多轮上下文；上下文过长时自动触发分层压缩

### 2. 代码自动补全
- 在编辑器中输入代码时，AI 自动提供智能代码补全建议
- 支持 Java、Kotlin、Python、JavaScript、TypeScript 等多种语言

### 3. 代码解释
- 选中代码 → 右键 → **AI Code Helper** → **AI 解释代码**
- AI 以中文解释代码功能、实现逻辑、关键点和注意事项
- 解释结果显示在右侧 DEEPWAY CODE 面板

### 4. 代码优化
- 选中代码 → 右键 → **AI Code Helper** → **AI 优化代码**
- AI 给出优化建议和优化后的完整代码
- 支持一键将优化代码替换到编辑器中

### 5. 项目结构分析
- 菜单栏 → **Tools** → **AI Code Helper** → **AI 分析项目结构**
- 也可直接在聊天框提问“分析一下这个项目”，让智能体自行调用工具探索

### 6. 设置页面
- 前往 **Settings → Tools → AI Code Helper**
- 基础配置：
  - **API 地址**：默认 `https://api.openai.com`，支持自定义
  - **API Key**：你的 API 密钥
  - **模型名称**：如 `gpt-3.5-turbo`、`gpt-4`、`deepseek-chat` 等
  - **最大 Token 数**：控制响应长度（1 ~ 32000）
  - **Temperature**：控制 AI 创造性（0.00 ~ 1.00）
- Agent / Harness 配置：
  - **启用工具调用（Agent 模式）**：关闭后退化为纯聊天
  - **自动放行只读工具** / **允许写工具**：权限控制（决策 #5）
  - **启用 autocompact 自动上下文压缩**（决策 #3）
  - **最大迭代次数**：ReAct 主循环断路器
  - **上下文窗口 (tokens)**：autocompact 阈值判断
  - **摘要模型**：压缩时使用的更便宜模型（留空则用主模型）

## 支持的 AI 服务

本插件使用 OpenAI 兼容 API 格式，支持以下服务：

| 服务 | API 地址 | 模型示例 |
|------|----------|----------|
| **OpenAI** | `https://api.openai.com` | `gpt-3.5-turbo`、`gpt-4` |
| **DeepSeek** | `https://api.deepseek.com` | `deepseek-chat`、`deepseek-coder` |
| **本地 Ollama** | `http://localhost:11434` | `llama3`、`codellama`、`qwen2.5-coder` |
| **其他兼容服务** | 自定义地址 | 对应模型名 |

## 安装方法

### 方法一：从源码构建

```bash
# 克隆仓库
git clone https://github.com/wangjitao123/aiCode-helper.git
cd aiCode-helper

# 构建插件
./gradlew buildPlugin

# 插件 zip 包位于 build/distributions/ 目录
```

然后在 IntelliJ IDEA 中：
1. 打开 **Settings → Plugins**
2. 点击右上角齿轮图标 **⚙️**
3. 选择 **Install Plugin from Disk...**
4. 选择 `build/distributions/aiCode-helper-1.0.0.zip`
5. 重启 IDEA

### 方法二：直接运行开发版

```bash
./gradlew runIde
```

这会启动一个带有插件的新 IntelliJ IDEA 实例，用于开发测试。

## 配置说明

首次安装后，请先配置 API 信息：

1. 打开 **Settings（Ctrl+Alt+S）**
2. 进入 **Tools → AI Code Helper**
3. 填写以下信息：
   - **API 地址**：你使用的 AI 服务地址
   - **API Key**：你的 API 密钥
   - **模型名称**：你要使用的模型
4. 点击 **Apply** 保存

## 使用说明

### 代码解释 / 优化
1. 在编辑器中选中一段代码
2. 右键打开上下文菜单
3. 选择 **AI Code Helper → AI 解释代码** 或 **AI 优化代码**
4. 等待 AI 分析完成，结果显示在右侧面板

### AI 聊天
1. 点击 IDEA 右侧的 **AI Code Helper** 图标打开面板
2. 在底部输入框输入问题
3. 按 **Ctrl+Enter** 或点击「发送」按钮
4. AI 回复会以流式方式显示

### 项目分析
1. 点击菜单 **Tools → AI Code Helper → AI 分析项目结构**
2. 等待分析完成
3. 分析结果显示在 AI Code Helper 面板中

## 构建方法

本项目使用 Gradle + Kotlin DSL 构建，需要 JDK 17+。

```bash
# 构建项目
./gradlew build

# 构建插件包
./gradlew buildPlugin

# 运行测试
./gradlew test

# 启动测试 IDEA 实例
./gradlew runIde
```

## 项目结构

```
aiCode-helper/
├── build.gradle.kts              # Gradle 构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
├── src/main/
│   ├── kotlin/com/aicode/helper/
│   │   ├── agent/                # 🆕 Agent Harness 核心
│   │   │   ├── QueryEngine.kt            # 决策#1/#2：Flow 生成器 + ReAct 主循环
│   │   │   ├── AgentState.kt             # 决策#2：State + transition 断路器
│   │   │   ├── AgentMessage.kt           # 含 tool_call/tool_result 的消息模型
│   │   │   ├── AgentSession.kt           # 会话装配（工具/Hook/压缩/LLM）
│   │   │   ├── StreamingToolExecutor.kt  # 决策#4：并发执行 + 并发安全调度 + sibling abort
│   │   │   ├── event/AgentEvent.kt       # 决策#1：被 yield 的事件类型
│   │   │   ├── tools/                    # 工具系统（权限 + isConcurrencySafe）
│   │   │   │   ├── Tool.kt / ToolRegistry.kt / ToolSupport.kt
│   │   │   │   ├── ReadFileTool / ListDirectoryTool / GrepSearchTool
│   │   │   │   ├── ProjectStructureTool / WriteFileTool
│   │   │   ├── hooks/                    # 决策#5：Hook 架构
│   │   │   │   ├── AgentHook.kt / HookManager.kt / PermissionHook.kt
│   │   │   └── context/                  # 决策#3/#6：五层压缩
│   │   │       ├── ContextManager.kt / CompactionConstants.kt / TokenEstimator.kt
│   │   ├── actions/              # 右键菜单 Action
│   │   ├── completion/           # 代码补全
│   │   ├── service/              # 服务层
│   │   │   ├── AiApiService.kt           # 一次性请求（解释/优化/补全）
│   │   │   ├── LlmClient.kt              # 🆕 流式 + tool-calling 客户端
│   │   │   └── ChatHistoryService.kt
│   │   ├── settings/             # 设置
│   │   ├── toolwindow/           # Tool Window UI（驱动 QueryEngine）
│   │   └── utils/                # 工具类
│   └── resources/META-INF/
│       └── plugin.xml            # 插件描述符
└── README.md
```

## 注意事项

- 使用代码补全功能时，由于每次都需要调用 AI API，可能稍有延迟
- 请妥善保管你的 API Key，不要提交到版本控制系统
- 建议将 API Key 保存在插件设置中，它会以加密方式保存在 IDEA 配置目录
- 对于大型项目，项目结构分析可能需要较长时间

## License

MIT License
