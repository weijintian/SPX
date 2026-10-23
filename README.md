# SPX iOS

SPX Chat 原生 iOS 工程，使用 SwiftUI，支持 iOS 17 及以上。HTTP / WebSocket 沿用现有服务端接口。此仓库本次收录 iOS 客户端；Android 工程和生产服务器配置继续保留在原工作区。

## 在 Mac 上打开

安装 Xcode（含 iOS 模拟器）、XcodeGen 和 CocoaPods，然后执行：

```bash
git clone https://github.com/weijintian/SPX.git
cd SPX/ios-native
bash scripts/bootstrap-macos.sh
open SpxChatIOS.xcworkspace
```

在 Xcode 选择 SpxChatIOS scheme。无需重新填写腾讯云密钥：客户端通过既有服务端接口获取通话凭证，不把服务端密钥写进 App。

## 编译和测试

```bash
cd ios-native
bash scripts/verify-macos.sh
```

脚本会自动选择可用的 iPhone 模拟器，也可以通过 SPX_TEST_DESTINATION 指定目标。

GitHub 的 **Actions → iOS build and archive** 会在源码推送及手动运行时执行 macOS 构建和 XCTest，并生成未签名的真机归档。进入一次运行的 Artifacts 下载归档和日志。未签名归档用于构建验证，不能直接安装到 iPhone；工作流不会发布到应用商店。

## 生成可安装的正式包

在 Mac 的 Xcode 中登录 Apple Developer 账号，配置 com.spx.chat.ios 对应的签名和描述文件。然后运行：

```bash
export SPX_APPLE_TEAM_ID="你的 Apple Team ID"
cd ios-native
bash scripts/archive-macos.sh
```

使用 Xcode Organizer 验证并导出 IPA，按实际账号和分发方式选择 Development、Ad Hoc、TestFlight 或 App Store。证书、私钥、描述文件和账号凭证不存入本仓库。CI 目前只生成未签名归档；尚未配置签名 IPA 导出。

## 代码与验收状态

- ios-native/：客户端、界面、资源、测试、XcodeGen 与 CocoaPods 配置。
- server-companion/chat_upload.php：客户端媒体断点恢复对应的服务端辅助模块，供对照及本地隔离测试使用，不能独立代替完整服务端。
- ios-native/FEATURE-PARITY.md：功能覆盖和已知缺口。
- ios-native/DEVICE-TEST-MATRIX.md：真机验收清单。
- ios-native/Validation/：源代码和隔离测试记录，不能当作 Xcode 编译或真机通过记录。

本地 Windows 已通过 Swift 语法树、资源和 95 个 API action 检查；实际编译结果以 GitHub Actions 或 Mac 上的 Xcode 日志为准。后台/终止状态来电推送、账号注销等缺口见功能清单。iOS 不集成 Android APK 自动更新。

## 可选本地静态与上传测试

```bash
cd ios-native
python3 -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements-dev.txt
python scripts/validate_sources.py
node scripts/test-media-upload.mjs /path/to/php
```

独立仓库的 API action 校验使用导出的接口名快照，属于离线契约校验，不代表线上接口可用性。上传测试需要 Node.js 20+、PHP 8.3 与 Fileinfo，在临时目录和本机回环地址运行，不接触线上数据库或账号。
