// CDN 回退策略补丁
// 在 CDN 脚本加载前执行，决定使用 CDN 还是本地 vendor 文件
(function() {
    var PORT = 18900;
    var CDN_MAP = {
        'https://cdn.tailwindcss.com': '/vendor/tailwindcss.js',
        'https://unpkg.com/vue@3/dist/vue.global.prod.js': '/vendor/vue.global.prod.js',
        'https://cdn.jsdelivr.net/npm/marked/marked.min.js': '/vendor/marked.min.js',
        'https://cdn.jsdelivr.net/npm/dompurify@3.0.6/dist/purify.min.js': '/vendor/purify.min.js',
        'https://cdn.jsdelivr.net/npm/sortablejs@latest/Sortable.min.js': '/vendor/Sortable.min.js',
        'https://cdn.jsdelivr.net/npm/daisyui@4.7.2/dist/full.min.css': '/vendor/daisyui.full.min.css',
        'https://cdn.jsdelivr.net/npm/localforage@1.10.0/dist/localforage.min.js': '/vendor/localforage.min.js'
    };
    var STORAGE_KEY = 'rp_hub_cdn_fallback_until';
    var LOCAL_BASE = 'http://localhost:' + PORT;

    // 检查是否在 7 天本地策略期内
    function isLocalPeriod() {
        try {
            var until = parseInt(localStorage.getItem(STORAGE_KEY) || '0');
            return until > 0 && Date.now() < until;
        } catch(e) { return false; }
    }

    // 设置 7 天本地策略
    function setLocalPeriod() {
        try {
            localStorage.setItem(STORAGE_KEY, String(Date.now() + 7 * 24 * 60 * 60 * 1000));
        } catch(e) {}
    }

    // 清除本地策略
    function clearLocalPeriod() {
        try { localStorage.removeItem(STORAGE_KEY); } catch(e) {}
    }

    // 替换所有 CDN 引用为本地路径
    function switchToLocal() {
        var scripts = document.querySelectorAll('script[src]');
        for (var i = 0; i < scripts.length; i++) {
            var src = scripts[i].getAttribute('src');
            for (var cdn in CDN_MAP) {
                if (src && src.indexOf(cdn) !== -1) {
                    scripts[i].setAttribute('src', LOCAL_BASE + CDN_MAP[cdn]);
                    break;
                }
            }
        }
        var links = document.querySelectorAll('link[href]');
        for (var i = 0; i < links.length; i++) {
            var href = links[i].getAttribute('href');
            for (var cdn in CDN_MAP) {
                if (href && href.indexOf(cdn) !== -1) {
                    links[i].setAttribute('href', LOCAL_BASE + CDN_MAP[cdn]);
                    break;
                }
            }
        }
    }

    // 创建回退对话框（纯 DOM，不依赖 Vue）
    function showDialog(failedUrl) {
        var overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:99999;display:flex;align-items:center;justify-content:center;font-family:system-ui,-apple-system,sans-serif;';

        var box = document.createElement('div');
        box.style.cssText = 'background:#fff;border-radius:16px;padding:24px;max-width:340px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,0.2);';

        var title = document.createElement('div');
        title.style.cssText = 'font-size:18px;font-weight:700;color:#1a1a1a;margin-bottom:8px;';
        title.textContent = '资源加载失败';

        var msg = document.createElement('div');
        msg.style.cssText = 'font-size:14px;color:#666;margin-bottom:20px;line-height:1.5;';
        msg.textContent = '无法从网络加载所需资源，是否使用本地版本？';

        var urlDiv = document.createElement('div');
        urlDiv.style.cssText = 'font-size:11px;color:#999;margin-bottom:16px;word-break:break-all;background:#f5f5f5;padding:8px;border-radius:8px;';
        urlDiv.textContent = failedUrl;

        // 复选框：7 天内不再提示
        var checkWrap = document.createElement('label');
        checkWrap.style.cssText = 'display:flex;align-items:center;gap:8px;margin-bottom:20px;cursor:pointer;font-size:13px;color:#666;';
        var checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.style.cssText = 'width:16px;height:16px;accent-color:#6366f1;';
        var checkText = document.createElement('span');
        checkText.textContent = '7天内不再提示，直接使用本地版本';
        checkWrap.appendChild(checkbox);
        checkWrap.appendChild(checkText);

        // 按钮组
        var btnRow = document.createElement('div');
        btnRow.style.cssText = 'display:flex;gap:10px;';

        var btnLocal = document.createElement('button');
        btnLocal.style.cssText = 'flex:1;padding:10px;border:none;border-radius:10px;background:#6366f1;color:#fff;font-size:15px;font-weight:600;cursor:pointer;';
        btnLocal.textContent = '使用本地版本';
        btnLocal.onmouseover = function() { btnLocal.style.background = '#4f46e5'; };
        btnLocal.onmouseout = function() { btnLocal.style.background = '#6366f1'; };

        var btnRetry = document.createElement('button');
        btnRetry.style.cssText = 'flex:1;padding:10px;border:1px solid #ddd;border-radius:10px;background:#fff;color:#333;font-size:15px;font-weight:600;cursor:pointer;';
        btnRetry.textContent = '尝试重新加载';
        btnRetry.onmouseover = function() { btnRetry.style.background = '#f9f9f9'; };
        btnRetry.onmouseout = function() { btnRetry.style.background = '#fff'; };

        btnLocal.onclick = function() {
            if (checkbox.checked) setLocalPeriod();
            switchToLocal();
            document.body.removeChild(overlay);
            location.reload();
        };

        btnRetry.onclick = function() {
            if (checkbox.checked) setLocalPeriod();
            clearLocalPeriod();
            document.body.removeChild(overlay);
            location.reload();
        };

        btnRow.appendChild(btnLocal);
        btnRow.appendChild(btnRetry);

        box.appendChild(title);
        box.appendChild(msg);
        box.appendChild(urlDiv);
        box.appendChild(checkWrap);
        box.appendChild(btnRow);
        overlay.appendChild(box);
        document.body.appendChild(overlay);
    }

    // 主逻辑
    if (isLocalPeriod()) {
        // 7 天期内，直接使用本地
        switchToLocal();
    } else {
        // 检测 CDN 加载失败
        var cdnUrls = Object.keys(CDN_MAP);
        var failed = [];
        var total = cdnUrls.length;
        var done = 0;
        var dialogShown = false;

        function checkDone() {
            done++;
            if (done >= total && failed.length > 0 && !dialogShown) {
                dialogShown = true;
                showDialog(failed[0]);
            }
        }

        // 给每个 CDN 脚本/link 添加加载检测
        for (var i = 0; i < cdnUrls.length; i++) {
            (function(cdnUrl) {
                var elements = document.querySelectorAll('script[src*="' + cdnUrl + '"], link[href*="' + cdnUrl + '"]');
                if (elements.length === 0) { checkDone(); return; }
                for (var j = 0; j < elements.length; j++) {
                    var el = elements[j];
                    var tag = el.tagName.toLowerCase();
                    if (tag === 'script') {
                        el.onerror = function() { failed.push(cdnUrl); checkDone(); };
                        el.onload = function() { checkDone(); };
                    } else if (tag === 'link') {
                        el.onerror = function() { failed.push(cdnUrl); checkDone(); };
                        el.onload = function() { checkDone(); };
                    }
                }
            })(cdnUrls[i]);
        }

        // 超时保护：8 秒后如果部分失败也弹窗
        setTimeout(function() {
            if (failed.length > 0 && !dialogShown) {
                dialogShown = true;
                showDialog(failed[0]);
            }
        }, 8000);
    }
})();
