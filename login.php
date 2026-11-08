<?php
declare(strict_types=1);
$invite = trim((string) ($_GET['invite'] ?? ''));
?><!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
    <title>注册 SPX IM</title>
    <style>
        :root{font-family:Arial,"Microsoft YaHei",sans-serif;color:#18243b;background:#f3f6fb}
        *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:20px}
        .box{width:min(430px,100%);background:#fff;border-radius:16px;padding:28px;box-shadow:0 12px 42px #243b6420}
        h1{margin:0 0 8px;font-size:25px}.intro{color:#71809a;font-size:14px;line-height:1.6;margin:0 0 22px}
        .field{margin:14px 0}.field label{display:block;color:#66758d;font-size:13px;margin-bottom:7px}
        input{width:100%;height:44px;padding:0 12px;border:1px solid #d8e0ec;border-radius:9px;font-size:15px;outline:none}
        input:focus{border-color:#315bea;box-shadow:0 0 0 3px #315bea18}.row{display:flex;gap:8px}.row input{flex:1}
        button,.button{border:0;border-radius:9px;background:#315bea;color:#fff;height:44px;padding:0 15px;font-size:14px;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;justify-content:center}
        button:disabled{opacity:.55}.primary{width:100%;margin-top:8px}.invite{margin-top:8px;padding:10px 12px;background:#eef3ff;border-radius:9px;color:#315bea;font-size:12px;line-height:1.5;word-break:break-all}
        .status{min-height:22px;color:#d05555;font-size:13px;margin-top:12px;line-height:1.5}.success{color:#177b4d}
    </style>
</head>
<body>
<main class="box">
    <h1>注册 SPX IM</h1>
    <p class="intro">创建账号后即可使用聊天、商城和质押服务。</p>
    <form id="registerForm">
        <div class="field"><label>用户名</label><input name="username" maxlength="64" required autocomplete="username"></div>
        <div class="field"><label>昵称</label><input name="nickname" maxlength="64" placeholder="可选"></div>
        <div class="field"><label>邮箱</label><input name="email" type="email" required autocomplete="email"></div>
        <div class="field"><label>邮箱验证码</label><div class="row"><input name="code" inputmode="numeric" maxlength="6" required><button id="sendCode" type="button">获取验证码</button></div></div>
        <div class="field"><label>密码</label><input name="password" type="password" minlength="6" required autocomplete="new-password"></div>
        <div class="field"><label>邀请码</label><input name="invite_code" value="<?= htmlspecialchars($invite, ENT_QUOTES, 'UTF-8') ?>" required><div class="invite">邀请码由邀请链接自动填写，用于绑定上下级关系。</div></div>
        <button class="primary" type="submit">注册并进入首页</button>
        <div id="status" class="status"></div>
    </form>
</main>
<script>
const api='/spxchat/api.php';
const form=document.getElementById('registerForm');
const status=document.getElementById('status');
const sendCode=document.getElementById('sendCode');
const request=(action,body)=>fetch(api+'?action='+encodeURIComponent(action),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(async response=>{const data=await response.json();if(!response.ok||data.code!==0)throw new Error(data.message||'请求失败');return data});
new URLSearchParams(location.search).get('invite') && (form.invite_code.value=new URLSearchParams(location.search).get('invite'));
sendCode.addEventListener('click',()=>{const email=form.email.value.trim();if(!email){status.textContent='请先填写邮箱';return}sendCode.disabled=true;request('auth.email.code',{email,purpose:'register'}).then(data=>{status.textContent=data.message||'验证码已发送';status.className='status success';let seconds=60;sendCode.textContent=seconds+' 秒';const timer=setInterval(()=>{seconds--;sendCode.textContent=seconds+' 秒';if(seconds<=0){clearInterval(timer);sendCode.disabled=false;sendCode.textContent='重新获取'}},1000)}).catch(error=>{sendCode.disabled=false;sendCode.textContent='获取验证码';status.className='status';status.textContent=error.message})});
form.addEventListener('submit',event=>{event.preventDefault();const data=Object.fromEntries(new FormData(form));request('auth.register',data).then(()=>{status.textContent='注册成功，正在进入首页…';status.className='status success';setTimeout(()=>{location.href='/index.php?registered=1'},500)}).catch(error=>{status.className='status';status.textContent=error.message})});
</script>
</body>
</html>
