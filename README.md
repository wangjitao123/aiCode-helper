# AI Code Helper

> 一款功能强大的 IntelliJ IDEA AI 编程助手插件，支持代码解释、代码优化、项目结构分析、AI 聊天等功能。

## 功能介绍

### 1. 代码自动补全
- 在编辑器中输入代码时，AI 自动提供智能代码补全建议
- 支持 Java、Kotlin、Python、JavaScript、TypeScript 等多种语言

### 2. 代码解释
- 选中代码 → 右键 → **AI Code Helper** → **AI 解释代码**
- AI 以中文解释代码功能、实现逻辑、关键点和注意事项
- 解释结果显示在右侧 AI Code Helper 面板

### 3. 代码优化
- 选中代码 → 右键 → **AI Code Helper** → **AI 优化代码**
- AI 给出优化建议和优化后的完整代码
- 支持一键将优化代码替换到编辑器中

### 4. 项目结构分析
- 菜单栏 → **Tools** → **AI Code Helper** → **AI 分析项目结构**
- 自动分析项目目录结构、文件类型分布
- AI 给出项目架构摘要和建议

### 5. AI 聊天窗口
- IDEA 右侧提供 **AI Code Helper** 面板
- 支持与 AI 自由对话
- 保持聊天历史记录
- 支持流式响应（打字机效果）
- 按 **Ctrl+Enter** 或点击「发送」按钮发送消息

### 6. 设置页面
- 前往 **Settings → Tools → AI Code Helper**
- 可配置：
  - **API 地址**：默认 `https://api.openai.com`，支持自定义
  - **API Key**：你的 API 密钥
  - **模型名称**：如 `gpt-3.5-turbo`、`gpt-4`、`deepseek-chat` 等
  - **最大 Token 数**：控制响应长度（1 ~ 32000）
  - **Temperature**：控制 AI 创造性（0.00 ~ 1.00）

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
│   │   ├── actions/              # 右键菜单 Action
│   │   │   ├── ExplainCodeAction.kt
│   │   │   ├── OptimizeCodeAction.kt
│   │   │   └── ProjectStructureAction.kt
│   │   ├── completion/           # 代码补全
│   │   │   └── AiCompletionContributor.kt
│   │   ├── service/              # 服务层
│   │   │   ├── AiApiService.kt
│   │   │   └── ChatHistoryService.kt
│   │   ├── settings/             # 设置
│   │   │   ├── AiCodeSettings.kt
│   │   │   └── AiCodeSettingsConfigurable.kt
│   │   ├── toolwindow/           # Tool Window UI
│   │   │   ├── AiChatToolWindowFactory.kt
│   │   │   └── ChatPanel.kt
│   │   └── utils/                # 工具类
│   │       └── ProjectStructureUtil.kt
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
