# GijiMemo

Android 会议纪要 App。录音 MP3 → LLM 转写/格式化 → 输出 Word/MD/TXT → 调系统邮件 App 发送。

## 技术栈

Kotlin · Jetpack Compose · Hilt · Room · Apache POI · Coroutines/Flow · OkHttp

## 模块结构

- `:app` — UI 屏幕
- `:core-audio` — 录音
- `:core-llm` — LLM 客户端（OpenAI 兼容）
- `:core-document` — Word/MD/TXT 生成
- `:core-share` — 邮件分享
- `:core-data` — Room + DataStore + EncryptedPrefs

## 开发

### 前置要求

- JDK 21（Gradle 8.5 不支持 Java 26+）
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"   # Git Bash
  set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11         # Windows CMD
  ```
- Android SDK（ANDROID_HOME 环境变量）
  ```bash
  export ANDROID_HOME="C:/Users/superlambkin/AppData/Local/Android/Sdk"
  ```

### 常用命令

```bash
./gradlew assembleDebug    # 构建 Debug APK
./gradlew assembleRelease  # 构建 Release APK
./gradlew test             # 运行所有单元测试
./gradlew :app:installDebug # 安装到连接的设备
```

## 文档

设计文档和实施计划在 OB Vault：
- 设计: `80_POC_Projects/POC_009_GijiMemo/02_设计文档/设计文档.md`
- 计划: `80_POC_Projects/POC_009_GijiMemo/03_开发文档/实施计划.md`

## LLM 配置

首次使用需在 App 设置页配置：
1. 选择 LLM 服务商（MiniMax / OpenAI / Claude 代理 / DeepSeek / Ollama）
2. 填入对应 API Key
3. 选默认模型
4. 选调用模式（多模态 / Whisper+总结）

## 手动测试

参见 `docs/MANUAL_TEST_CHECKLIST.md`。