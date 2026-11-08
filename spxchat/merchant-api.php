<?php
declare(strict_types=1);
require_once __DIR__.'/response.php';
require_once __DIR__.'/merchant_session.php';
merchantSession();
if (!merchantSessionUser()) jsonResponse(null,'请重新登录商户工作台',401);
$action=(string)($_GET['action']??'');
$reads=['merchant.me','merchant.products','merchant.orders','merchant.order.detail','merchant.groups','merchant.drafts','merchant.draft','merchant.finance','merchant.audit','merchant.performance','mall.config'];
$writes=['merchant.product.save','merchant.product.online','merchant.product.offline','merchant.product.delete','merchant.products.offline','merchant.inventory.adjust','merchant.media.upload','merchant.draft.save','merchant.draft.delete','merchant.order.ship','merchant.order.deliver','merchant.order.redeem','merchant.order.reply','merchant.deposit.pay','merchant.close'];
$method=$_SERVER['REQUEST_METHOD'];
if (($method==='GET'&&!in_array($action,$reads,true)) || ($method==='POST'&&!in_array($action,$writes,true)) || !in_array($method,['GET','POST'],true)) jsonResponse(null,'此操作不向商户开放',403);
if ($method==='POST' && !merchantCsrf((string)($_SERVER['HTTP_X_CSRF_TOKEN']??''))) jsonResponse(null,'安全校验失败，请刷新页面',403);
$_SERVER['HTTP_AUTHORIZATION']='Bearer '.$_SESSION['token'];
session_write_close();
require __DIR__.'/index.php';
