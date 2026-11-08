# SPX IM WebSocket 部署

聊天窗口建立 WebSocket 长连接并通过 Token 鉴权。消息仍由 PHP API 写入 MySQL；写入成功后 API 将事件转发到本机 WebSocket 进程，再立即推送给在线用户。重连后 Android 只补拉断线期间的历史消息，不进行定时轮询。

## 运行服务

需要 PHP CLI，且 CLI 与网站 PHP 使用相同的 PHP 扩展和 `config.php`。在服务器项目目录启动：

```sh
php ws-server.php
```

成功启动后终端会保持占用，并显示 `SPX WebSocket server started`。没有返回 shell 提示符是正常现象，说明进程正在运行；需要按 `Ctrl+C` 停止。

建议用 systemd 或 Supervisor 保持进程常驻。`config.php` 中：

- `websocket_public_port`：WebSocket 监听端口，默认 `8081`。
- `websocket_event_host` 和 `websocket_event_port`：PHP API 到 WebSocket 服务的本机事件通道，默认 `127.0.0.1:8090`，不要暴露到公网。

Android 应通过 Nginx 暴露的 `/ws` 路径连接：明文环境使用 `ws://主机/ws`，HTTPS 环境使用 `wss://主机/ws`。生产环境应使用 TLS 反向代理，不要让 Android 直接连接 8081。

## Nginx TLS 反向代理

在现有 HTTPS `server` 配置中加入：

```nginx
location /ws {
    proxy_pass http://127.0.0.1:8081/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```

HTTPS API 下 Android 使用 `wss://主机/ws`。此时应在防火墙中关闭公网 8081，仅允许 Nginx 本机访问该端口。

## 验证

1. 启动 PHP WebSocket 常驻进程并确认 8081 正在监听。
2. 确认 PHP 网站进程可以连接 `127.0.0.1:8090`。
3. 使用两个已登录账号打开同一私聊，发送消息应即时到达；群聊应即时推送给在线成员。
4. 关闭 WebSocket 进程时 REST 发送仍会写入数据库；服务恢复、客户端重连后会补载离线消息。
