(() => {
'use strict';
const api='/spxchat/api.php';
const byId=id=>document.getElementById(id);
const android=byId('androidDownload'),ios=byId('iosDownload'),backup=byId('backupDownload'),modal=byId('androidRoutes'),list=byId('androidRouteList'),close=byId('closeRoutes');
const backupModal=byId('backupModal'),backupList=byId('backupList'),backupEmpty=byId('backupEmpty'),closeBackup=byId('closeBackup');
let release=null,config={backup_enabled:false,android_backup_lines:[],ios_backup_lines:[]},releaseState='loading',configState='loading',previousFocus=null,previousOverflow='';
function status(text){byId('status').textContent=text;byId('status').hidden=!text;}
function safeUrl(value,platform){if(typeof value!=='string'||!value)return false;try{const url=new URL(value,location.href);return !url.username&&!url.password&&(platform==='ios'&&url.protocol==='itms-services:'||['http:','https:'].includes(url.protocol));}catch{return false;}}
function androidDevice(){return /Android/i.test(navigator.userAgent);}
function iosDevice(){return /iPhone|iPad|iPod/i.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);}
function ready(link,text){link.classList.remove('disabled');link.removeAttribute('aria-disabled');link.removeAttribute('tabindex');link.querySelector('span').lastChild.textContent=text;}
function unavailable(link,text){link.classList.add('disabled');link.setAttribute('aria-disabled','true');link.setAttribute('tabindex','-1');link.querySelector('span').lastChild.textContent=text;}
function openDialog(target,button){previousFocus=document.activeElement;previousOverflow=document.body.style.overflow;document.body.style.overflow='hidden';target.hidden=false;button.focus();}
function closeDialog(target){if(target.hidden)return;target.hidden=true;document.body.style.overflow=previousOverflow;if(previousFocus?.isConnected)previousFocus.focus();}
const symbols={direct:'<path d="M12 3v12m-4-4 4 4 4-4M5 16v4h14v-4"/>',xunlei:'<path d="m14 2-9 12h7l-2 8 9-12h-7l2-8Z"/>',lanzou:'<path d="M7 18h10a4 4 0 0 0 .2-8A5.5 5.5 0 0 0 6.5 8.4 4.8 4.8 0 0 0 7 18Z"/>'};
function icon(type){const span=document.createElement('span');span.className='route-icon '+type;span.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+symbols[type]+'</svg>';return span;}
function routeCard(number,name,description,type,url,primary=false){
 const active=safeUrl(url,'android'),item=document.createElement(active?'a':'button');item.className='route-card'+(primary?' primary':'');
 if(active){item.href=url;if(primary){item.download='spxchat-'+release.version_name+'.apk';item.addEventListener('click',()=>{closeDialog(modal);status('已向浏览器发起下载，请在下载列表或系统通知栏查看进度。');});}else{item.target='_blank';item.rel='noopener noreferrer';}}
 else{item.type='button';item.disabled=true;item.setAttribute('aria-disabled','true');}
 const copy=document.createElement('span');copy.className='route-copy';const title=document.createElement('span');title.className='route-title';title.textContent=name;
 if(primary&&active){const badge=document.createElement('span');badge.className='recommend';badge.textContent='推荐';title.append(badge);}
 const desc=document.createElement('span');desc.className='route-desc';const num=document.createElement('span');num.className='route-number';num.textContent='线路 '+String(number).padStart(2,'0')+' · ';desc.append(num,document.createTextNode(description));copy.append(title,desc);
 const arrow=document.createElement('span');arrow.innerHTML='<svg class="route-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6"/></svg>';item.append(icon(type),copy,arrow);return item;
}
function renderRoutes(){
 const size=release&&Number(release.apk_size)>0?' · '+(Number(release.apk_size)/1048576).toFixed(1)+' MB':'';
 byId('androidRouteVersion').textContent=release?'v'+release.version_name+size+' · Android 安装包':releaseState==='loading'?'正在读取版本信息…':'官网版本暂不可用，可尝试其他线路';
 list.replaceChildren();list.append(routeCard(1,'官网下载',release?'使用浏览器直接下载 APK':releaseState==='loading'?'正在获取最新安装包…':'暂不可用，请稍后重试','direct',release?api+'?action=app.update.download&id='+encodeURIComponent(release.id):'',true));
 const configured=config.backup_enabled&&Array.isArray(config.android_backup_lines)?config.android_backup_lines.filter(line=>line&&safeUrl(line.url,'android')):[];
 const candidates=[...configured];
 const take=expression=>{const index=candidates.findIndex(line=>expression.test((line.name||'')+' '+line.url));return index<0?null:candidates.splice(index,1)[0];};
 const xunlei=take(/迅雷|xunlei/i),lanzou=take(/蓝奏|lanzou/i);
 const missing=configState==='loading'?'正在读取线路配置…':configState==='error'?'线路配置读取失败，请稍后重试':'暂未开放，请选择其他线路';
 list.append(routeCard(2,'迅雷网盘',xunlei?'前往迅雷网盘获取安装包':missing,'xunlei',xunlei?.url||''));
 list.append(routeCard(3,'蓝奏云',lanzou?'前往蓝奏云获取安装包':missing,'lanzou',lanzou?.url||''));
 candidates.forEach((line,index)=>list.append(routeCard(index+4,line.name||'备用下载','打开备用下载页面','lanzou',line.url)));
}
function openAndroid(event){event?.preventDefault();if(!modal.hidden)return;renderRoutes();openDialog(modal,close);}
function openBackup(){
 if(androidDevice()){openAndroid();return;}
 backupList.replaceChildren();let count=0;
 for(const line of Array.isArray(config.ios_backup_lines)?config.ios_backup_lines:[]){if(!line||!safeUrl(line.url,'ios'))continue;const item=document.createElement('a');item.className='backup-item ios-line';item.href=line.url;item.target='_blank';item.rel='noopener noreferrer';const mark=document.createElement('span');mark.className='backup-icon';mark.textContent='iOS';const name=document.createElement('span');name.className='backup-name';name.textContent=line.name||'iOS 备用下载';item.append(mark,name);backupList.append(item);count++;}
 if(!iosDevice()){const item=document.createElement('button');item.className='backup-item';item.type='button';item.textContent='Android 下载线路 →';item.addEventListener('click',()=>{closeDialog(backupModal);openAndroid();});backupList.prepend(item);count++;}
 backupEmpty.hidden=count>0;openDialog(backupModal,closeBackup);
}
for(const [dialog,button] of [[modal,close],[backupModal,closeBackup]]){button.addEventListener('click',()=>closeDialog(dialog));dialog.addEventListener('click',event=>{if(event.target===dialog)closeDialog(dialog);});}
 document.addEventListener('keydown',event=>{const current=!modal.hidden?modal:!backupModal.hidden?backupModal:null;if(!current)return;if(event.key==='Escape'){closeDialog(current);return;}if(event.key!=='Tab')return;const targets=[...current.querySelectorAll('button:not(:disabled),a[href]')].filter(el=>!el.hidden);const first=targets[0],last=targets.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}});
 android.addEventListener('click',openAndroid);backup.addEventListener('click',openBackup);
 byId('routeInstallHelp').addEventListener('click',()=>{closeDialog(modal);byId('download-help').scrollIntoView({behavior:'smooth',block:'center'});status('下载完成后，打开 APK，按系统提示允许该来源安装应用，再继续安装。微信内无法下载时，请选择“在浏览器打开”。');});
 byId('deviceHint').textContent=androidDevice()?'已识别为 Android 设备 · 可选择下载线路':iosDevice()?'已识别为 iOS 设备 · 推荐下载 iOS 版':'电脑端可先选择 Android 或 iOS 下载';
 async function payload(url){const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),15000);try{const response=await fetch(url,{cache:'no-store',signal:controller.signal});if(!response.ok)throw Error('下载服务暂不可用');const result=await response.json();if(result.code!==0||!result.data)throw Error('下载配置暂不可用');return result.data;}finally{clearTimeout(timeout);}}
 payload(api+'?action=app.download.config').then(data=>{config=data;configState='ready';if(data.ios_available&&safeUrl(data.ios_download_url,'ios')){ios.href=data.ios_download_url;ready(ios,'App 下载');}else unavailable(ios,'暂未开放');backup.hidden=!data.backup_enabled;renderRoutes();}).catch(()=>{configState='error';unavailable(ios,'暂不可用');renderRoutes();});
 payload(api+'?action=app.update.check&version_code=0').then(data=>{if(!Number.isSafeInteger(Number(data.id))||Number(data.id)<=0||!Number.isSafeInteger(Number(data.version_code)))throw Error('暂无可下载版本');release=data;releaseState='ready';byId('versionBadge').textContent='全新 v'+(data.version_name||'最新版本')+' 已上线';byId('releaseNote').textContent=data.release_notes||'版本更新与功能优化';renderRoutes();}).catch(()=>{releaseState='error';byId('versionBadge').textContent='Android 多线路下载';byId('releaseNote').textContent='官网版本暂不可用，请选择可用备用线路，或稍后刷新页面。';renderRoutes();});
 renderRoutes();
})();
