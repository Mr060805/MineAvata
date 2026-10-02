# MineAvata

> 一只住在 Android 屏幕上的 Live2D 桌宠:悬浮窗常驻、定时随机动作、语音唤醒互动、本地模型导入。

**⚠️ 许可协议:CC BY-NC-SA 4.0 —— 禁止一切商业用途,二次开发必须以同协议开源,并须署名来源。详见文末[许可协议](#许可协议)。**

---

## 功能特性

### 🐾 桌面宠物(Live2D)
- **悬浮窗桌宠**:前台服务(`PetService`,specialUse 类型)驱动,桌宠悬浮在所有应用之上,可拖拽移动
- **包络窗口**:悬浮窗按模型包络开矩形([`ModelEnvelope`](app/src/main/java/com/example/mineavata/pet/ModelEnvelope.kt)),窗口大小随模型实际显示范围自适应
- **动作调度**:站桩待机 + 每 25~35 秒定时随机播放动作,结束后参数**渐进回正**,不做生硬切换
- **注视与拖拽**:拖拽基于像素语义(`dragScene`),松手平滑回位

### 🎙️ 语音唤醒
- 独立常驻服务 [`VoiceWakeService`](app/src/main/java/com/example/mineavata/voice/VoiceWakeService.kt)(microphone 前台服务),与桌宠服务**互相独立、开关互不影响**
- 引擎为系统 `SpeechRecognizer`,产出整句文本(为后续接入 LLM 预留接口)
- [`PhraseMatcher`](app/src/main/java/com/example/mineavata/voice/PhraseMatcher.kt) 匹配提示词,命中后触发桌宠随机动作/表情,支持震动反馈
- 唤醒词等配置持久化于 [`WakePrefs`](app/src/main/java/com/example/mineavata/voice/WakePrefs.kt)

### 📦 模型导入流水线
- [`ImportManager`](app/src/main/java/com/example/mineavata/pet/ImportManager.kt) 后台**六阶段**导入:拷贝 → 检查 → 解压 → 校验 → 贴图处理 → rename 安装
- 全程可视化进度、支持取消;WakeLock 防止导入途中息屏中断
- [`ModelAutoMapper`](app/src/main/java/com/example/mineavata/pet/ModelAutoMapper.kt) 自动映射模型参数,[`ModelStorage`](app/src/main/java/com/example/mineavata/pet/ModelStorage.kt) 管理本地模型仓库

### 🖥️ 界面
- Jetpack Compose + Material 3 主界面([`MainActivity`](app/src/main/java/com/example/mineavata/MainActivity.kt)),管理桌宠/语音唤醒开关与模型导入

---

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 语言 | Kotlin | 2.4.10 |
| 构建 | Android Gradle Plugin | 9.3.1(内置 Kotlin 支持) |
| 构建 | Gradle(wrapper) | 9.5 |
| UI | Jetpack Compose(BOM) | 2026.08.00 |
| UI | Material 3 / Navigation Compose | 随 BOM / 2.9.8 |
| SDK | compileSdk / targetSdk / minSdk | 37 / 36 / 26(Android 8.0+) |
| 渲染 | Live2D Cubism Core SDK(预编译 `.so` / `.a`,随仓库分发) | — |
| 渲染 | [alive2d](https://github.com/EasyLive2D/alive2d)(vendored,含本地修改) | 见下文 |
| Native | C++ / CMake / NDK / JNI(alive2d 原生层) | — |
| 语音 | Android 系统 `SpeechRecognizer` | — |
| 服务 | 前台服务(specialUse + microphone)、悬浮窗、WakeLock | — |

### 使用的 Android 权限

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 桌宠悬浮窗 |
| `FOREGROUND_SERVICE` / `_SPECIAL_USE` | 桌宠常驻服务 |
| `FOREGROUND_SERVICE_MICROPHONE` / `RECORD_AUDIO` | 语音唤醒监听(运行时申请) |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `WAKE_LOCK` | 模型导入防息屏中断 |
| `VIBRATE` | 唤醒命中震动反馈 |

---

## 项目结构

```
MineAvata/
├── app/
│   ├── libs/alive2d/          # vendored 的 alive2d 库(含本地修改,见其 UPSTREAM.md)
│   └── src/main/
│       ├── java/com/example/mineavata/
│       │   ├── pet/           # 桌宠:PetService、Live2DView、导入流水线、包络、存储
│       │   ├── voice/         # 语音唤醒:VoiceWakeService、SpeechWakeDetector、PhraseMatcher
│       │   └── ui/theme/      # Compose 主题
│       └── assets/live2d/     # 内置示例模型
├── tools/prepare_model.py     # 模型预处理脚本(PC 端)
├── keystore/                  # 签名密钥(不入库,需自备)
└── gradle/libs.versions.toml  # 版本目录
```

---

## 构建与运行

### 环境要求
- **JDK 17+**
- **Android Studio**(建议最新版,需支持 AGP 9.3 / compileSdk 37)
- **Android SDK Platform 37**、**NDK + CMake**(编译 alive2d 原生层)
- 真机或模拟器(**Android 8.0 / API 26 以上**;语音唤醒依赖系统识别服务,模拟器上可能不可用)

### 构建步骤

```bash
git clone https://github.com/Mr060805/MineAvata.git
cd MineAvata
```

用 Android Studio 打开项目根目录,同步 Gradle 后:

```bash
# Debug 构建(开箱即用)
./gradlew assembleDebug
```

> **Release 签名**:项目通过 `keystore/realers.properties` 读取签名配置(字段:`storeFile`、`storePassword`、`keyAlias`、`keyPassword`)。该文件与密钥**不入库**;缺失或字段不全时自动回退 debug 签名,不影响构建。如需正式签名,请在 `keystore/` 下自备密钥并创建同名配置。

### 使用流程

1. **授予悬浮窗权限**:首次启动桌宠时按系统提示授权"显示在其他应用上层"
2. **启动桌宠**:主界面开关桌宠服务,选择/导入模型
3. **导入自有模型**:通过导入入口选择模型压缩包,六阶段流水线自动完成校验与安装(可视化进度、可取消)
   - PC 端可先用 [`tools/prepare_model.py`](tools/prepare_model.py) 预处理模型
4. **开启语音唤醒**:主界面单独开关(与桌宠互不依赖),首次开启需授予麦克风权限;命中唤醒词后桌宠做出反应并震动提示

---

## 第三方组件与说明

| 组件 | 说明 | 许可 |
|---|---|---|
| [alive2d](https://github.com/EasyLive2D/alive2d) | Live2D Android 渲染封装,**已 vendored 并含本地修改**(fine-grained 模型参数、窗口包络支持),修改清单见 [`app/libs/alive2d/UPSTREAM.md`](app/libs/alive2d/UPSTREAM.md) | 遵循其上游项目许可 |
| Live2D Cubism Core | 预编译原生库,随 alive2d 目录分发 | [Live2D Proprietary Software 许可](app/libs/alive2d/alive2d/src/main/cpp/Live2D/Core/LICENSE.md),使用须遵守 Live2D, Inc. 官方条款 |
| Live2D Cubism Framework | 随 alive2d 目录分发 | [Live2D Open Software 许可](app/libs/alive2d/alive2d/src/main/cpp/Live2D/Framework/LICENSE.md) |
| `assets/live2d/` 内置模型 | 示例用第三方角色模型,版权归原作者/发行方所有,**不代表本项目授权范围**;二次分发或商用场景请自行替换为拥有合法授权的模型 | 原版权方所有 |

---

## 许可协议

本项目(源代码及仓库内除第三方组件外的全部内容)采用 **CC BY-NC-SA 4.0(署名-非商业性使用-相同方式共享)** 许可,完整条文见 [LICENSE](LICENSE)。核心条款:

1. **🚫 严禁一切商业用途** —— 不得将本项目或其任何部分(含修改版)用于任何商业目的,包括但不限于出售、内置于商业产品、提供商业服务、以本项目牟利
2. **🔓 二次开发必须开源** —— 任何基于本项目的修改、衍生作品,必须以**相同协议(CC BY-NC-SA 4.0)** 公开全部源代码
3. **✍️ 必须署名来源** —— 使用、转载、二次开发时,须明确标注:
   > 基于 [Mr060805/MineAvata](https://github.com/Mr060805/MineAvata),采用 CC BY-NC-SA 4.0 许可

第三方组件(alive2d、Live2D Cubism Core/Framework、模型资产)各自遵循其原始许可,不在本协议授权范围内。
