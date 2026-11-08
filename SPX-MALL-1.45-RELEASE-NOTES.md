# SPX 商城 1.45 正式交付记录

生成日期：2026-10-13。已完成本地实现和验证；生产服务端尚未部署，真机验收尚未进行。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| [SPX-IM-1.45-release.apk](SPX-IM-1.45-release.apk) | 原发布证书签名的 Android 正式安装包 |
| [SPX-MALL-1.45-server-update.zip](SPX-MALL-1.45-server-update.zip) | 配套的现有服务端覆盖更新包，十个文件与逐文件清单 |
| [MALL-UPGRADE.md](spxchat/MALL-UPGRADE.md) | 功能、规则、完整上线顺序与回退说明 |
| [SPX-IM-1.45-release-verification.json](SPX-IM-1.45-release-verification.json) | 正式 APK 验证结果 |
| [SPX-MALL-1.45-server-manifest.json](SPX-MALL-1.45-server-manifest.json) | 服务端更新文件 SHA-256 清单 |

手机端与服务端需要配套更新，单独安装新 APK 不能启用全部流程。后台发布版本应填写 1.45、版本号 46。

## 本轮结果

商城列表与详情、规格面板、地址返回、报价确认、支付结果恢复、订单详情与售后、商家图片和规格编辑、独立库存、自动商品校验均已接入。初级十万、黄金一百万 SPX001 保证金及黄金曝光、平台开店审核、门店地图导航延续完整流程。底部重复订单按钮已移除。

商品通过结构和业务校验后自动上架；该校验不包含 AI 图片识别。店铺资料和资质仍由平台审核。运费按每单固定金额收取；完成后七天为售后期，财务人工结算；退货退款不自动回库。

## 验证

- 65 项隔离 API/后台集成检查全部通过，包括两个独立服务进程并发支付/库存、自动上架、精确金额、报价有效期、退款与可质押额度、规格保留、历史平台订单、退货物流、权限和保证金退回。
- 18 个 PHP 文件语法通过；地图和客户端解析检查通过。
- 正式构建成功；原证书、v2 签名、包名、版本、ZIP 和对齐核验通过；26 个源文件与打包内容一致，177 个编译类进入 APK。
- 未部署后端或操作真实资金。实际手机布局、图片滚动、弱网支付恢复及地图应用跳转尚需真机验收。

## 安装包信息

- 包名：com.spx.chat
- 版本：1.45（46）
- 构建类型：release；debuggable=false；testOnly=false
- APK 大小：9,609,970 字节
- APK SHA-256：`8b79a6be5b02255692c32d115cb4c24f6fd06424ea422ea008f7ce6b9c81a2ed`
- 原发布证书 SHA-256：`776718a739e05447b4be92a46fb822b8ecc946aee334bc7b81d0eba009f68aa5`
- 服务端更新包 SHA-256：`1eb94efcc876ce23a7ec81dc613bf1d5cc2852ddc545321aa0647345dce0235c`

服务端压缩包仅包含清单中的代码及说明，不包含配置、上传目录、发布证书私钥或签名密码。部署前保留现有配置与数据。

## 复验入口

集成用例位于 spxchat/tests/mall_integration.py，构建和校验入口位于 .spx-release-build/build_commerce.py 与 .spx-release-build/verify_release.py。最终集成日志为 .spx-release-build/commerce-integration-final.log，构建日志为 .spx-release-build/commerce-build.log。运行测试使用独立临时 PHP/MariaDB 和虚构账号。
