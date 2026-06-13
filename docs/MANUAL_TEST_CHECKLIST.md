# GijiMemo 手动测试 Checklist

> 环境受限跳过说明：当前 Windows 环境无连接 Android 设备或模拟器，ANDROID_HOME 未设置。以下为完整的手动测试指南，实际测试需在配置好环境后执行。

---

## 环境配置说明

### 1. 设置 ANDROID_HOME

**Git Bash:**
```bash
export ANDROID_HOME="C:/Users/superlambkin/AppData/Local/Android/Sdk"
echo 'export ANDROID_HOME="C:/Users/superlambkin/AppData/Local/Android/Sdk"' >> ~/.bashrc
```

**Windows CMD:**
```cmd
set ANDROID_HOME=C:\Users\superlambkin\AppData\Local\Android\Sdk
```

### 2. 安装模拟器

1. 打开 Android Studio → Tools → SDK Manager
2. 安装 Android SDK Platform 34
3. Tools → Device Manager → Create Device → Pixel 6 (API 34)
4. 启动模拟器

### 3. 安装 Debug APK

```bash
cd D:/AI-Agent/GijiMemo
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"
export ANDROID_HOME="C:/Users/superlambkin/AppData/Local/Android/Sdk"
./gradlew :app:installDebug
adb shell am start -n com.gijimemo/.MainActivity
```

---

## 功能测试 Checklist

### 录音功能

- [ ] **T1.1** 从 Home 屏幕点击「新建录音」按钮，能正确导航到 RecordingScreen
- [ ] **T1.2** 点击录音按钮，录音指示器亮起，计时器开始计数
- [ ] **T1.3** 录音过程中，波形/振幅显示正常更新
- [ ] **T1.4** 点击暂停按钮，录音暂停，计时器停止
- [ ] **T1.5** 点击继续按钮，录音恢复
- [ ] **T1.6** 点击停止按钮，录音结束，导航到 ProcessingScreen 或返回 Home

### LLM 转写功能

- [ ] **T2.1** 录音停止后，ProcessingScreen 显示「转写中」状态
- [ ] **T2.2** SSE 流式输出，转写内容逐步显示在屏幕上
- [ ] **T2.3** 转写完成后，自动跳转到 PreviewScreen 显示结果
- [ ] **T2.4** 转写失败时，显示错误提示（网络错误 / API Key 无效 / 限流）
- [ ] **T2.5** 设置页配置不同的 LLM 服务商（MiniMax/OpenAI/DeepSeek/Ollama）均能正常工作

### 文档生成功能

- [ ] **T3.1** PreviewScreen 显示转写结果（Markdown 格式）
- [ ] **T3.2** 点击「导出 Word」，生成 .docx 文件并保存
- [ ] **T3.3** 点击「导出 Markdown」，生成 .md 文件并保存
- [ ] **T3.4** 点击「导出 TXT」，生成 .txt 文件并保存
- [ ] **T3.5** 长录音（>25分钟）自动分片转写，多片结果合并

### 邮件分享功能

- [ ] **T4.1** 点击「分享」按钮，调起系统邮件 App（ACTION_SEND）
- [ ] **T4.2** 邮件附件包含导出的文档文件
- [ ] **T4.3** 邮件主题和正文正确填充
- [ ] **T4.4** 多个附件时使用 ACTION_SEND_MULTIPLE

### 设置功能

- [ ] **T5.1** 设置页可选择默认 LLM 服务商
- [ ] **T5.2** 设置页可输入/修改 API Key（加密存储）
- [ ] **T5.3** 设置页可选择默认模型
- [ ] **T5.4** 设置页可切换调用模式（多模态 / Whisper+总结）
- [ ] **T5.5** 设置页可配置默认切片时长（分钟）
- [ ] **T5.6** 设置页可配置默认收件人邮箱

### 会话管理（Home 屏幕）

- [ ] **T6.1** Home 屏幕显示所有历史会话列表（按时间倒序）
- [ ] **T6.2** 点击会话卡片，能重新打开查看转写结果
- [ ] **T6.3** 可删除历史会话

### 权限检查

- [ ] **T7.1** 首次录音前，系统正确请求麦克风权限
- [ ] **T7.2** 权限拒绝时，App 显示友好的权限说明界面

---

## 测试数据参考

### 测试用 LLM API Key

> 请使用你自己的 API Key，以下为格式参考

| 服务商 | Key 格式 | 默认模型 |
|--------|----------|----------|
| MiniMax | `eyJ...` | MiniMax-M3 |
| OpenAI | `sk-...` | gpt-4o-audio-preview |
| DeepSeek | `sk-...` | deepseek-chat |
| Ollama | 本地无需 Key | llama3.1 |

### 测试录音时长

| 场景 | 时长 | 用途 |
|------|------|------|
| 短会话 | 1-5 分钟 | 基本流程验证 |
| 中会话 | 10-20 分钟 | 正常负载 |
| 长会话 | 30+ 分钟 | 分片逻辑验证 |

---

## 环境限制说明

当前 Windows 环境限制：
- 无物理 Android 设备连接
- ANDROID_HOME 未配置
- 无法执行 `installDebug` 和端到端真机测试

**解决方式：** 在配置好 Android SDK 和模拟器/设备的机器上执行上述 checklist。