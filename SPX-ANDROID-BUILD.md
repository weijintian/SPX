# Android 发布包构建标准

> 2026-11-01 起执行。规则写在 `android-native/app/build.gradle`，脚本会再校验一遍，改不了也绕不过。

## 一、三条硬规则

| 规则 | 内容 | 为什么 |
|---|---|---|
| **ABI 只打 ARM** | `arm64-v8a` + `armeabi-v7a`，**不打 x86 / x86_64** | x86 系列只服务模拟器，却给线上包增加约 **70 MB**（126 MB → 56 MB） |
| **版本号必须递增** | 每次发版 `versionCode` +1，`versionName` 同步 | Android 禁止降级安装，版本号不变老用户收不到更新 |
| **签名固定** | `keys/spx-release.jks`，`CN=SPX IM` | 换签名会导致老用户无法覆盖安装 |

## 二、一条命令打包 + 自检

```powershell
powershell -ExecutionPolicy Bypass -File android-native\build-release.ps1
```

脚本会：

1. 自动找 JDK 21（`JAVA_HOME` 或 `%TEMP%\spx-jdk21\`）
2. 用阿里云镜像跑 `assembleRelease`（`dl.google.com` 在国内会 429）
3. 校验产物并打印：体积、ABI 清单、版本号、签名 DN、sha256

只校验现有产物（不重新构建）：

```powershell
powershell -ExecutionPolicy Bypass -File android-native\build-release.ps1 -SkipBuild
```

## 三、校验通过的标准

```
大小   : 约 56 MB（超过 80 MB 说明 x86 被打进来了）
ABI    : 'arm64-v8a' 'armeabi-v7a'
签名   : CN=SPX IM
sha256 : 记录到发布记录里
```

参考：**1.80 / code 81 —— 56.03 MB，sha256 `9465b96b…`**

## 四、模拟器怎么测

发布包**装不进 x86 模拟器**（这是预期行为）。测模拟器用 debug 包：

```powershell
cd android-native
.\gradlew.bat assembleDebug        # debug 保留 arm64-v8a + armeabi-v7a + x86_64
```

真机（arm64）直接用发布包就行；带 Houdini 转译的模拟器（如雷电）也能装发布包。

## 五、发布流程

```
1) 改版本号：app/build.gradle → versionCode +1，versionName 同步
2) 打包自检：build-release.ps1
3) 后台「设置 → 发布 Android 更新」上传 APK
   → 服务端自动上传 OSS，并写入 app_releases.apk_url
   → 该版本立即 active
```

上传后 `app_releases.apk_url` 会指向 `https://oss.spx001.top/releases/spxchat-<version>-<code>.apk`。
**已安装的旧客户端不用更新**：`app.update.download` 会 302 跳到 OSS，下载速度从 0.9 MB/s 提升到 20 MB/s 级别。

**OSS 同步失败不会阻塞发版**：`apk_url` 留空，下载自动回退到服务器本地文件，后台会提示"暂用服务器直发"。

历史版本补传（把 `apk_url` 为空的版本推到 OSS）：

```bash
php /var/www/html/spxchat/oss_release.php --sync-missing --dry-run   # 先看会做什么
php /var/www/html/spxchat/oss_release.php --sync-missing             # 真正执行
```

## 五之二、服务端依赖的 OSS 配置

| 文件/目录 | 用途 |
|---|---|
| `/etc/spxchat/oss.env` | Bucket、Endpoint、AK、公网域名（640 root:www-data） |
| `/etc/spxchat/ossutilconfig` | 给 ossutil 用的同一套凭证（640 root:www-data） |
| `/var/cache/spxchat-oss` | ossutil 断点续传目录（www-data 可写） |
| `/var/www/html/spxchat/oss_release.php` | 推送与补传逻辑 |


## 六、常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 包又变 126 MB | ABI 过滤被删/被环境变量扩大 | 检查 `release.abiFilters`，跑一次 `build-release.ps1` 自检 |
| 装到手机提示「应用未安装」 | `versionCode` 没加 | 改成比线上大的数字重新打包 |
| 模拟器装不上 | 发布包没有 x86 | 正常，改用 debug 包 |
| 下载仍很慢 | `apk_url` 为空或 OSS 域名异常 | 查 `app_releases.apk_url`，`curl -I` 看 302 是否生效 |

## 七、相关文件

| 文件 | 作用 |
|---|---|
| `android-native/app/build.gradle` | ABI 规则、版本号、签名配置 |
| `android-native/build-release.ps1` | 打包 + 自检脚本 |
| `android-native/keys/signing.properties` | 签名口令（不进 git） |
| `spxchat/app_download.php` | 下载接口与 OSS 跳转 |
