# SPX Chat PHP API

## 配置

数据库连接配置在 `config.php`，当前使用本地 MySQL：

- Host: `127.0.0.1`
- Port: `3306`
- Database: `spxchat`
- Charset: `utf8mb4`

首次自动建库需要数据库账号拥有 `CREATE DATABASE` 权限；如果数据库已由管理员提前创建，则需要该库的建表、索引和数据读写权限。

API 第一次访问时会自动完成以下初始化：

- 创建 `spxchat` 数据库（数据库不存在时）
- 创建全部业务表和索引
- 创建默认商城商品

也可以手动执行 `schema.sql` 完成同样的初始化。

## 启动

在 `spxchat` 目录执行：

```bash
php -S 127.0.0.1:8080
```

健康检查：

```text
GET http://127.0.0.1:8080/index.php?action=health
```

## 认证

注册：

```text
POST /index.php?action=auth.email.code
{"email":"demo@example.com"}

POST /index.php?action=auth.register
{"email":"demo@example.com","code":"123456","username":"demo","password":"123456","nickname":"SPX用户","invite_code":"spx_7890a2"}
```

`invite_code` 为选填项，填写时使用已有用户的 SPX ID；邀请码无效会拒绝注册，不填写则正常注册。注册必须通过邮箱验证码校验。

登录后把返回的 token 放到请求头：

```text
Authorization: Bearer TOKEN
```

## 主要接口

- `auth.register` 注册
- `auth.email.code` 发送注册邮箱验证码
- `auth.login` 登录
- `auth.logout` 退出登录
- `me` 当前用户
- `user.profile` 通过用户 ID 或 SPX ID 查询公开资料
- `profile.update` 修改昵称、头像、备注
- `upload.avatar` 上传头像，使用 `multipart/form-data` 的 `file` 字段
- `contacts` 联系人列表
- `contacts.search` 搜索用户
- `contacts.add` 添加好友
- `contacts.remark` 保存好友备注
- `contacts.delete` 删除好友
- `chats` 聊天列表
- `chats.create` 创建会话
- `chats.settings` 免打扰、置顶、拉黑、聊天背景
- `chats.settings.get` 读取聊天设置
- `chats.delete` 删除会话
- `groups` 群组列表
- `groups.create` 创建群聊
- `groups.update` 修改群名称、头像和公告（群主）
- `groups.members.add` 邀请群成员（群主）
- `groups.members.role` 设置或取消管理员（群主）
- `groups.members.remove` 移出群成员（群主）
- `groups.members.mute` 禁言或解除成员禁言（群主）
- `groups.settings` 修改自己的群免打扰设置
- `groups.leave` 退出群聊（群主需先转让群主）
- `groups.members` 群成员
- `groups.messages` 群消息
- `groups.messages.send` 发送群消息
- `messages` 聊天消息
- `messages.send` 发送消息
- `messages.clear` 清空聊天记录
- `messages.read` 标记已读
- `products` 商品列表
- `product` 商品详情
- `orders` 兑换订单
- `order` 订单详情
- `orders.redeem` 使用 SPX 创建兑换订单
- `orders.cancel` 取消订单并退回 SPX
- `wallet` 钱包资产
- `wallet.transactions` 钱包流水
- `wallet.deposit` 充值申请
- `wallet.withdraw` 提现申请
- `wallet.address` 地址列表
- `wallet.address.save` 保存收货地址
- `wallet.address.update` 修改收货地址
- `wallet.address.delete` 删除收货地址
- `staking` 质押记录
- `staking.create` 创建质押记录
- `staking.redeem` 赎回质押
- `faq` 常见问题
- `questions` 我的提问
- `questions.create` 提交问题
- `feedback.create` 提交反馈

## 说明

`config.php` 含数据库密码，只应部署在 PHP 服务端，不能打包进 Android 客户端或提交到公开仓库。商城兑换接口已经使用数据库事务扣减 SPX 余额并扣减库存；充值、提现、BSC 链上签名仍需要后续接入钱包服务、RPC 节点、私钥/托管方案和交易审计逻辑，当前接口只创建申请流水，不会伪造链上交易。

SMTP 使用环境变量配置，示例：

```text
FISS_SMTP_HOST=smtp.qq.com
FISS_SMTP_USER=2873728660@qq.com
FISS_SMTP_PASS=QQ邮箱授权码（当前代码含临时测试回退值）
FISS_SMTP_PORT=465
FISS_BSC_USDT_DEPOSIT_ADDRESS=平台的BSC-USDT充值地址
```

不要把 QQ 邮箱授权码写入公开代码库。验证码有效期为 5 分钟，同一邮箱 60 秒内只能发送一次，验证码最多尝试 5 次。

平台充值地址通过 `FISS_BSC_USDT_DEPOSIT_ADDRESS` 配置，客户端不会允许用户修改该地址。充值提交交易哈希和金额后会生成待核验流水，只有后台或链上核验服务确认后才能给用户资产入账，不会仅凭用户输入的哈希直接增加余额。

也可以直接在服务端 `config.php` 的 `wallet.bsc_usdt_deposit_address` 填入平台 BSC-USDT 地址；环境变量优先级更高。

当前开发环境没有安装 PHP CLI，因此没有在本机执行实际的 PHP 语法检查、PHP 服务启动和 MySQL 连通性测试。部署服务器需要 PHP 8.1+、PDO_MySQL、Fileinfo 和对应 MySQL 权限。部署前可执行：

```bash
php -l index.php
php -l db.php
php -l auth.php
php -S 127.0.0.1:8080
```

Android 模拟器访问电脑上的本地 PHP 服务时使用 `http://10.0.2.2:8080/api.php`。原生工程已提供 `ApiClient` 基础类和本地 HTTP 调试配置；当前页面仍保留演示数据，登录后再逐页替换为真实 API 数据。
