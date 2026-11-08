<?php
declare(strict_types=1);
require_once __DIR__.'/merchant_session.php';
merchantSession();
header("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https: http:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
$error='';
if ($_SERVER['REQUEST_METHOD']==='POST') {
    try {
        if (!merchantCsrf((string)($_POST['csrf']??''))) throw new InvalidArgumentException('安全校验失败，请刷新后重试');
        if (($_POST['action']??'')==='logout') merchantLogout();
        elseif (!merchantLogin(trim((string)($_POST['username']??'')),(string)($_POST['password']??''))) throw new InvalidArgumentException('账户或密码错误，或账户不可用');
        header('Location: merchant.php');exit;
    } catch(InvalidArgumentException $e) {$error=$e->getMessage();}
}
$user=merchantSessionUser();
function mh(mixed $v): string {return htmlspecialchars((string)$v,ENT_QUOTES|ENT_SUBSTITUTE,'UTF-8');}
?>
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="csrf-token" content="<?= mh($_SESSION['csrf']) ?>"><title>SPX 商户工作台</title><link rel="stylesheet" href="merchant.css"><?php if($user): ?><script src="merchant.js" defer></script><?php endif; ?></head><body>
<?php if(!$user): ?>
<main class="login"><div class="brand">SPX · MERCHANT</div><h1>让经营更简单</h1><p>使用 SPX App 的用户名或绑定邮箱及密码登录，管理商品、订单与店铺资金。</p><form method="post"><input type="hidden" name="csrf" value="<?= mh($_SESSION['csrf']) ?>"><label>用户名或邮箱<input name="username" autocomplete="username" maxlength="254" placeholder="请输入 App 用户名或绑定邮箱" required></label><label>密码<input name="password" type="password" autocomplete="current-password" maxlength="256" required></label><?php if($error): ?><p role="alert"><?= mh($error) ?></p><?php endif; ?><button class="primary">登录工作台</button></form><small>请先在手机 App 申请开店。所有商品发布须审核通过并缴足保证金。</small></main>
<?php else: ?>
<aside><div class="brand">SPX 商户工作台</div><nav><button data-page="overview">经营概览</button><button data-page="products">商品管理</button><button data-page="drafts">商品草稿</button><button data-page="orders">订单与售后</button><button data-page="finance">资金明细</button><button data-page="audit">操作记录</button><button data-page="settings">店铺与保证金</button></nav><form method="post"><input type="hidden" name="csrf" value="<?= mh($_SESSION['csrf']) ?>"><input type="hidden" name="action" value="logout"><button>退出登录 · <?= mh($user['username']) ?></button></form></aside>
<main class="workspace"><header><div><span class="eyebrow">SPX · 商家服务</span><h1 id="heading">经营概览</h1></div><button id="refresh">刷新</button></header><p id="notice" role="status" aria-live="polite"></p><div id="content"></div></main>
<dialog id="editor"><form id="product-form"><header><h2>商品资料</h2><button type="button" id="close-editor">关闭</button></header><p>按四步填写，保存草稿可稍后继续；发布时系统自动校验，保证金不足不能上架。</p>
<fieldset><legend>1 · 基本信息</legend><div class="grid"><label>商品名称 *<input name="name" maxlength="150" required></label><label>类目<select name="category"><option>服饰</option><option>数码</option><option>家居</option><option>食品</option><option>本地服务</option><option>数字商品</option><option>其他</option></select></label><label>商品类型<select name="product_type"><option value="physical">实物快递</option><option value="digital">数字商品</option><option value="local">到店服务</option></select></label><label>咨询群 *<select name="consultation_group_code" required></select><small>请先在 App 建群；仅可选择你管理的群。</small></label></div><label>商品简介 *<textarea name="description" maxlength="5000" required></textarea></label></fieldset>
<fieldset><legend>2 · 图片与详情</legend><div class="grid"><section><h3>商品主图（1—8 张）</h3><input id="main-files" type="file" accept="image/*" multiple><div id="main-images" class="images"></div></section><section><h3>详情图（最多 20 张）</h3><input id="detail-files" type="file" accept="image/*" multiple><div id="detail-images" class="images"></div></section></div><label>详细介绍<textarea name="detail_text" maxlength="15000"></textarea></label><label>商品参数（每行：参数：值）<textarea name="parameters" maxlength="5000"></textarea></label><label>视频地址（HTTPS）<input name="video_url" type="url"></label></fieldset>
<fieldset><legend>3 · 价格与规格</legend><div class="grid"><label>商品价格（CNY，最多八位小数）<input name="cny_price" inputmode="decimal" required></label><label>初始库存<input name="stock" type="number" min="0" max="1000000" required></label></div><p>多规格商品以各规格价格和库存为准，已发布库存通过“调整库存”修改。</p><div id="skus"></div><button id="add-sku" type="button">添加规格</button></fieldset>
<fieldset><legend>4 · 发货与服务</legend><div class="grid"><label>每单运费（SPX，0 为包邮）<input name="shipping_fee" inputmode="decimal"></label><label>实物 / 数字商品交付天数<input name="delivery_days" type="number" min="0" max="90"></label><label>到店服务有效天数<input name="service_valid_days" type="number" min="1" max="365"></label><label><input name="appointment_required" type="checkbox"> 到店需提前预约</label></div><label>交付 / 使用说明<textarea name="delivery_note" maxlength="500"></textarea></label><label>售后与服务条款<textarea name="service_terms" maxlength="5000"></textarea></label></fieldset>
<p id="editor-notice" role="status"></p><footer><button type="button" id="save-draft">保存草稿</button><button class="primary" type="submit">预览并发布商品</button></footer></form></dialog>
<?php endif; ?></body></html>
