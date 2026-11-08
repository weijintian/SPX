<?php
declare(strict_types=1);
header('Cache-Control: no-cache, must-revalidate');
?><!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="description" content="SPX IM：实时聊天、好物商城、短视频和红包钱包，生活常用功能集中在一个 App。">
    <title>SPX IM｜聊天、购物、刷视频，一个 App 全搞定</title>
    <style>
        :root { --ink:#14213d; --muted:#65738d; --blue:#3157d7; --line:#e2e8f5; --warm:#fff8f1; font-family:Inter,"PingFang SC","Microsoft YaHei",system-ui,sans-serif; color:var(--ink); background:#f7f9ff; }
        * { box-sizing:border-box; }
        html { scroll-behavior:smooth; }
        body { margin:0; min-width:320px; background:#f7f9ff; }
        a { color:inherit; }
        button,a { -webkit-tap-highlight-color:transparent; }
        button { font:inherit; }
        .site-header { position:absolute; inset:0 0 auto; z-index:10; display:flex; align-items:center; gap:38px; max-width:1400px; margin:0 auto; padding:28px 7vw; color:#35415d; }
        .brand { display:inline-flex; align-items:center; gap:10px; margin-right:auto; text-decoration:none; font-size:22px; font-weight:800; }
        .brand img { width:38px; height:38px; border-radius:11px; object-fit:cover; box-shadow:0 5px 15px #2449ad24; }
        .nav-link { color:#53617a; font-size:14px; text-decoration:none; }
        .nav-link:hover { color:var(--blue); }
        .hero { min-height:760px; overflow:hidden; padding:130px 7vw 70px; background:radial-gradient(800px 480px at 86% 18%,#cbd7ff 0,transparent 68%),radial-gradient(720px 500px at 8% 90%,#c7f0ff 0,transparent 68%),linear-gradient(180deg,#eef2ff,#fbfcff); }
        .hero-inner { display:grid; grid-template-columns:minmax(0,1.02fr) minmax(540px,.98fr); align-items:center; gap:44px; max-width:1400px; margin:0 auto; }
        .eyebrow { display:inline-flex; align-items:center; gap:8px; padding:8px 13px; border-radius:999px; color:#4438a5; background:#fff; box-shadow:0 7px 20px #4038a51b; font-size:13px; }
        .eyebrow i { width:7px; height:7px; border-radius:50%; background:#31a86b; }
        h1 { max-width:720px; margin:20px 0 16px; font-size:clamp(42px,5.1vw,70px); line-height:1.12; letter-spacing:0; }
        h1 span { background:linear-gradient(90deg,#2458d8,#7549d8); -webkit-background-clip:text; color:transparent; }
        .lead { max-width:590px; margin:0; color:#53617a; font-size:18px; line-height:1.75; }
        .download-row { display:flex; flex-wrap:wrap; gap:13px; margin-top:30px; }
        .download-button { display:inline-flex; align-items:center; gap:12px; min-height:58px; padding:10px 20px; border:0; border-radius:15px; color:#fff; text-decoration:none; box-shadow:0 13px 25px -12px currentColor; cursor:pointer; }
        .download-button svg { flex:0 0 auto; width:28px; height:28px; }
        .download-button span { display:grid; gap:2px; text-align:left; font-size:16px; font-weight:700; }
        .download-button small { font-size:11px; font-weight:500; opacity:.78; }
        .android { background:#149b54; }
        .ios { background:#101827; }
        .download-button.disabled { cursor:default; opacity:.55; pointer-events:none; }
        .secondary-actions { display:flex; flex-wrap:wrap; align-items:center; gap:17px; margin-top:18px; color:#68758c; font-size:13px; }
        .text-button { border:0; padding:0; color:#2d58ca; background:transparent; text-decoration:none; cursor:pointer; }
        .text-button:hover { text-decoration:underline; }
        .release-note { margin:24px 0 0; color:#8792a6; font-size:12px; }
        .hero-visual { position:relative; min-height:600px; }
        .phone { position:absolute; width:230px; padding:9px; border-radius:42px; background:#0b1220; box-shadow:0 30px 60px -20px #14265373,0 0 0 1.5px #ffffff26 inset; }
        .phone-screen { overflow:hidden; aspect-ratio:9/16; border-radius:33px; background:#fff; }
        .phone-screen img { display:block; width:100%; height:100%; object-fit:cover; object-position:top; }
        .phone-one { top:76px; left:0; transform:rotate(-8deg); }
        .phone-two { top:0; left:180px; z-index:2; width:258px; }
        .phone-three { top:76px; left:385px; transform:rotate(8deg); }
        .speech { position:absolute; z-index:3; max-width:190px; padding:12px 15px; border-radius:16px; background:#fff; box-shadow:0 14px 30px -10px #1e2b6338; color:#41506a; font-size:13px; }
        .speech-one { top:320px; left:0; }
        .speech-two { right:-5px; top:472px; }
        .section { padding:84px 7vw; }
        .section-inner { max-width:1260px; margin:0 auto; }
        .section-heading { text-align:center; }
        .section-heading h2 { margin:0; font-size:39px; line-height:1.25; }
        .section-heading p { margin:12px 0 0; color:var(--muted); font-size:16px; }
        .feature-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:26px; margin-top:44px; }
        .feature { display:flex; min-height:555px; flex-direction:column; overflow:hidden; padding:28px 28px 0; border:1px solid #e9edf7; border-radius:24px; background:#fff; box-shadow:0 14px 40px -30px #1e2b6338; }
        .feature-tag { display:inline-block; padding:5px 10px; border-radius:8px; color:#4b55bd; background:#eef1ff; font-size:12px; }
        .feature h3 { margin:14px 0 7px; font-size:23px; }
        .feature p { margin:0; color:var(--muted); font-size:14px; line-height:1.7; }
        .feature-image { width:220px; margin:auto auto 0; padding:8px 8px 0; border-radius:34px 34px 0 0; background:#0b1220; box-shadow:0 -12px 28px -20px #14265373; }
        .feature-image .phone-screen { border-radius:26px 26px 0 0; }
        .download-help { margin:12px 0 0; color:#65738d; font-size:12px; line-height:1.7; scroll-margin-top:24px; }
        .status { margin:12px 0 0; padding:10px 12px; border-radius:10px; color:#2448a1; background:#eef3ff; font-size:13px; line-height:1.65; }
        .footer { padding:34px 7vw 46px; color:#7d8799; background:#fff; font-size:12px; text-align:center; }
        .modal-backdrop { position:fixed; inset:0; z-index:40; display:grid; place-items:center; padding:20px; background:#10182773; }
        .modal-backdrop[hidden] { display:none; }
        .modal { width:min(520px,100%); max-height:min(680px,90vh); overflow:auto; padding:26px; border-radius:22px; background:#fff; box-shadow:0 25px 80px #10182745; }
        .modal-head { display:flex; align-items:flex-start; gap:16px; }
        .modal-head h2 { margin:0; font-size:24px; }
        .modal-head p { margin:6px 0 0; color:#7c8799; font-size:13px; line-height:1.6; }
        .modal-close { width:36px; height:36px; flex:0 0 auto; margin-left:auto; border:0; border-radius:50%; color:#65738d; background:#f2f5fb; cursor:pointer; font-size:22px; line-height:1; }
        .backup-list { display:grid; gap:10px; margin-top:22px; }
        .backup-item { display:flex; align-items:center; gap:12px; min-height:58px; padding:11px 13px; border:1px solid #e8edf7; border-radius:14px; color:#3157d7; text-decoration:none; }
        .backup-item:hover { border-color:#9eb1ed; background:#f7f9ff; }
        .backup-icon { display:grid; width:34px; height:34px; flex:0 0 auto; place-items:center; border-radius:10px; color:#fff; background:#159b54; font-size:12px; font-weight:700; }
        .backup-item.ios-line .backup-icon { background:#111827; }
        .backup-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        .backup-empty { margin:22px 0 4px; color:#7c8799; font-size:13px; line-height:1.6; }
        :focus-visible { outline:3px solid #8ea9ff; outline-offset:3px; }
        @media (max-width:900px) {
            .site-header { padding:18px 20px; }
            .nav-link { display:none; }
            .hero { min-height:auto; padding:88px 20px 42px; background:radial-gradient(300px 260px at 90% 7%,#fed7aa,transparent 70%),radial-gradient(320px 260px at 0 44%,#fbcfe8,transparent 70%),var(--warm); }
            .hero-inner { display:block; }
            h1 { margin-top:16px; font-size:36px; }
            .lead { font-size:15px; }
            .download-row { display:grid; gap:11px; }
            .download-button { justify-content:center; width:100%; }
            .secondary-actions { justify-content:center; gap:15px; }
            .release-note { text-align:center; }
            .hero-visual { min-height:auto; margin:25px 0 2px; }
            .phone { position:relative; top:auto; left:auto; width:220px; margin:0 auto; transform:none; }
            .phone-one,.phone-three { display:none; }
            .phone-two { display:block; width:220px; }
            .speech { font-size:12px; }
            .speech-one { top:56px; left:0; }
            .speech-two { top:170px; right:0; }
            .section { padding:38px 20px; }
            .section-heading { text-align:left; }
            .section-heading h2 { font-size:25px; }
            .section-heading p { font-size:13px; }
            .feature-grid { display:grid; grid-template-columns:1fr; gap:12px; margin-top:20px; }
            .feature { display:grid; grid-template-columns:1fr 106px; align-items:center; min-height:0; padding:18px; border-radius:20px; }
            .feature h3 { margin:9px 0 5px; font-size:17px; }
            .feature p { font-size:12px; line-height:1.6; }
            .feature-image { grid-column:2; grid-row:1; width:100px; margin:0; padding:5px 5px 0; border-radius:18px 18px 0 0; }
            .feature-image .phone-screen { border-radius:15px 15px 0 0; }
            .footer { padding:26px 20px 34px; }
        }
        @media (min-width:901px) and (max-width:1180px) { .hero-inner { grid-template-columns:1fr 500px; } .phone-one { left:-16px; } .phone-two { left:145px; } .phone-three { left:315px; } }
    </style>
    <link rel="stylesheet" href="/assets/website/download-routes.css?v=20261031-178">
</head>
<body>
<header class="site-header">
    <a class="brand" href="#top" aria-label="SPX IM 首页"><img src="/assets/website/logo.png" alt="SPX IM App Logo"><span>SPX IM</span></a>
    <a class="nav-link" href="#features">产品特色</a><a class="nav-link" href="#download-help">安装帮助</a><a class="nav-link" href="/login.php">注册账号</a>
</header>
<main id="top">
    <section class="hero"><div class="hero-inner"><div class="hero-copy">
        <span class="eyebrow"><i></i><span id="versionBadge">正在读取最新版本</span></span>
        <h1>聊天、购物、刷视频<br><span>一个 App 全搞定</span></h1>
        <p class="lead">SPX IM 把实时聊天、好物商城、短视频和红包钱包放在一起。和朋友畅聊，也能顺手逛店、看视频、发现新鲜好物。</p>
        <div class="download-row" aria-label="下载客户端">
            <a id="androidDownload" class="download-button android" href="#androidRoutes" aria-haspopup="dialog" aria-controls="androidRoutes"><svg viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d="M17.6 9.48l1.84-3.18a.38.38 0 0 0-.14-.52.38.38 0 0 0-.52.14l-1.87 3.23A11.4 11.4 0 0 0 12 8.12c-1.74 0-3.4.37-4.91 1.03L5.22 5.92a.38.38 0 0 0-.52-.14.38.38 0 0 0-.14.52L6.4 9.48C3.24 11.2 1.1 14.4.75 18.12h22.5c-.35-3.72-2.49-6.92-5.65-8.64zM6.97 15.32a1.13 1.13 0 1 1 0-2.26 1.13 1.13 0 0 1 0 2.26zm10.06 0a1.13 1.13 0 1 1 0-2.26 1.13 1.13 0 0 1 0 2.26z"/></svg><span><small>Android 版</small>立即下载</span></a>
            <a id="iosDownload" class="download-button ios disabled" href="#" aria-disabled="true"><svg viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d="M12.15 6.9c-.95 0-2.42-1.08-3.96-1.04-2.04.03-3.91 1.18-4.96 3.01-2.12 3.68-.55 9.1 1.52 12.09 1.01 1.45 2.21 3.09 3.79 3.04 1.52-.07 2.09-.99 3.94-.99 1.83 0 2.35.99 3.96.95 1.64-.03 2.68-1.48 3.68-2.95 1.16-1.69 1.64-3.33 1.66-3.42-.04-.01-3.18-1.22-3.22-4.86-.03-3.04 2.48-4.49 2.6-4.56-1.43-2.09-3.62-2.32-4.39-2.38-2-.16-3.68 1.09-4.61 1.09zM15.53 3.83c.84-1.01 1.4-2.43 1.24-3.83-1.2.05-2.66.8-3.53 1.82-.78.9-1.45 2.34-1.27 3.71 1.34.1 2.72-.69 3.56-1.7z"/></svg><span><small>iPhone / iPad</small>App 下载</span></a>
        </div>
        <p id="status" class="status" role="status" aria-live="polite" hidden></p>
        <p id="download-help" class="download-help">Android 点击后可选择官网或网盘线路下载，完成后打开 APK 安装。iPhone / iPad 请按 iOS 下载页面提示安装。</p>
        <div class="secondary-actions"><button id="backupDownload" class="text-button" type="button" hidden>备用下载</button><a class="text-button" href="/login.php">注册账号</a><span id="deviceHint">正在识别设备</span></div>
        <p id="releaseNote" class="release-note" aria-live="polite">正在读取版本信息…</p>
    </div><div class="hero-visual" aria-label="SPX IM 客户端功能截图">
        <div class="phone phone-one"><div class="phone-screen"><img src="/assets/website/product.png" alt="SPX IM 商品详情截图"></div></div><div class="phone phone-two"><div class="phone-screen"><img src="/assets/website/release.png" alt="SPX IM 客户端聊天与商城综合截图"></div></div><div class="phone phone-three"><div class="phone-screen"><img src="/assets/website/chat.png" alt="SPX IM 实时聊天截图"></div></div><div class="speech speech-one">实时消息，随时都能接上话题</div><div class="speech speech-two">边聊天，边发现喜欢的好物</div>
    </div></div></section>
    <section id="features" class="section"><div class="section-inner"><div class="section-heading"><h2>为什么下载 SPX IM</h2><p>截图来自真实客户端，页面中的商品、消息和奖励数据为演示数据</p></div><div class="feature-grid">
        <article class="feature"><div><span class="feature-tag">即时通讯</span><h3>实时聊天，秒发秒达</h3><p>私聊、群聊、语音视频通话，消息实时同步，和朋友保持联系更简单。</p></div><div class="feature-image"><div class="phone-screen"><img src="/assets/website/chat.png" alt="实时聊天功能截图"></div></div></article>
        <article class="feature"><div><span class="feature-tag">SPX 商城</span><h3>好物商城，边聊边买</h3><p>浏览店铺和商品，把喜欢的好物分享给朋友，一起挑选更方便。</p></div><div class="feature-image"><div class="phone-screen"><img src="/assets/website/mall.png" alt="SPX 商城功能截图"></div></div></article>
        <article class="feature"><div><span class="feature-tag">短视频与奖励</span><h3>刷视频，发现新内容</h3><p>闲下来刷刷短视频，查看演示中的积分奖励和活动内容。</p></div><div class="feature-image"><div class="phone-screen"><img src="/assets/website/video.png" alt="短视频奖励功能截图"></div></div></article>
    </div></div></section>
</main><footer class="footer">SPX IM · 实时聊天、商城和短视频</footer>
<div id="backupModal" class="modal-backdrop" hidden><section class="modal" role="dialog" aria-modal="true" aria-labelledby="backupTitle"><div class="modal-head"><div><h2 id="backupTitle">备用下载</h2><p>主线路无法下载时，请选择下面的线路。</p></div><button id="closeBackup" class="modal-close" type="button" aria-label="关闭备用下载">×</button></div><div id="backupList" class="backup-list"></div><p id="backupEmpty" class="backup-empty" hidden>当前没有可用的备用线路，请稍后再试。</p></section></div>
<div id="androidRoutes" class="modal-backdrop" hidden><section class="modal" role="dialog" aria-modal="true" aria-labelledby="routeTitle" aria-describedby="routeDescription"><button id="closeRoutes" class="modal-close" type="button" aria-label="关闭下载线路选择">×</button><div class="route-head"><div class="route-eyebrow"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><path d="m7 3-2-2m12 2 2-2M4 10a8 8 0 0 1 16 0H4Zm0 3v5h16v-5M8 18v3m8-3v3"/><path d="M8 7h.01M16 7h.01" stroke-width="2.5"/></svg>ANDROID 客户端</div><h2 id="routeTitle">选择下载线路</h2><p id="routeDescription">选择一条方便的线路，下载 SPX IM。</p></div><div class="app-summary"><img src="/assets/website/logo.png" alt=""><div><strong>SPX IM</strong><small id="androidRouteVersion">正在读取版本信息…</small></div><span class="apk-tag">APK</span></div><div id="androidRouteList" class="route-list"></div><div class="route-tip"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-11v2"/></svg><span>某条线路下载较慢？可以返回这里，换一条试试。<br>微信内无法下载时，请选择“在浏览器打开”。</span></div><div class="route-footer"><span>网盘版本以分享页面标注为准</span><button id="routeInstallHelp" type="button">安装帮助 ↗</button></div></section></div>
<script src="/assets/website/download-routes.js?v=20261031-178" defer></script>
</body>
</html>
