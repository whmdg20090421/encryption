// CDN 加载策略补丁
// 拦截 CDN 元素 → 探测 CDN 可达性 → 放行(恢复原URL) 或 回退(缓存/本地)
// 加载期间显示等待遮罩，全部失败时显示详细诊断
(function() {
    var PORT = 18900;
    var LOCAL_BASE = 'http://localhost:' + PORT;
    var DB_NAME = 'rp_hub_cdn_cache';
    var DB_STORE = 'scripts';
    var TIMEOUT_MS = 15000;

    var RESOURCES = [
        { cdn: 'https://cdn.tailwindcss.com', local: '/vendor/tailwindcss.js', name: 'Tailwind CSS', check: function() { return !!window.tailwind; } },
        { cdn: 'https://cdn.jsdelivr.net/npm/daisyui@4.7.2/dist/full.min.css', local: '/vendor/daisyui.full.min.css', name: 'DaisyUI', isCss: true },
        { cdn: 'https://unpkg.com/vue@3/dist/vue.global.prod.js', local: '/vendor/vue.global.prod.js', name: 'Vue 3', check: function() { return !!window.Vue; } },
        { cdn: 'https://cdn.jsdelivr.net/npm/localforage@1.10.0/dist/localforage.min.js', local: '/vendor/localforage.min.js', name: 'localForage', check: function() { return !!window.localforage; } },
        { cdn: 'https://cdn.jsdelivr.net/npm/marked/marked.min.js', local: '/vendor/marked.min.js', name: 'Marked', check: function() { return !!window.marked; } },
        { cdn: 'https://cdn.jsdelivr.net/npm/dompurify@3.0.6/dist/purify.min.js', local: '/vendor/purify.min.js', name: 'DOMPurify', check: function() { return !!window.DOMPurify; } },
        { cdn: 'https://cdn.jsdelivr.net/npm/sortablejs@latest/Sortable.min.js', local: '/vendor/Sortable.min.js', name: 'SortableJS', check: function() { return !!window.Sortable; } }
    ];

    var CDN_MAP = {};
    for (var i = 0; i < RESOURCES.length; i++) CDN_MAP[RESOURCES[i].cdn] = RESOURCES[i];

    // ── 拦截队列 ──
    // observer 在浏览器解析 HTML 时拦截 CDN 元素，暂停加载（移除 src/href）
    var intercepted = []; // { element, originalUrl, tag }

    var observer = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
            var nodes = mutations[i].addedNodes;
            for (var j = 0; j < nodes.length; j++) {
                var node = nodes[j];
                if (node.nodeType !== 1) continue;
                var tag = node.tagName;
                var url = null;
                if (tag === 'SCRIPT') url = node.getAttribute('src');
                else if (tag === 'LINK' && node.getAttribute('rel') === 'stylesheet') url = node.getAttribute('href');

                if (url && CDN_MAP[url]) {
                    // 暂停加载：移除 src/href，保存原始值
                    if (tag === 'SCRIPT') node.removeAttribute('src');
                    else node.removeAttribute('href');
                    intercepted.push({ element: node, originalUrl: url, tag: tag });
                }
            }
        }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    // ── 遮罩 ──
    var overlay, statusEl;
    function createOverlay() {
        overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(255,255,255,0.97);z-index:99999;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:system-ui,-apple-system,sans-serif;';
        var spinner = document.createElement('div');
        spinner.style.cssText = 'width:36px;height:36px;border:3px solid #e5e7eb;border-top-color:#6366f1;border-radius:50%;animation:cdn-spin 0.8s linear infinite;margin-bottom:16px;';
        var s = document.createElement('style');
        s.textContent = '@keyframes cdn-spin{to{transform:rotate(360deg)}}';
        document.head.appendChild(s);
        statusEl = document.createElement('div');
        statusEl.style.cssText = 'font-size:13px;color:#6b7280;text-align:center;max-width:300px;line-height:1.5;';
        statusEl.textContent = '正在检测网络...';
        overlay.appendChild(spinner);
        overlay.appendChild(statusEl);
        document.body.appendChild(overlay);
    }
    function updateStatus(t) { if (statusEl) statusEl.textContent = t; }
    function removeOverlay() {
        if (overlay && overlay.parentNode) {
            overlay.style.transition = 'opacity 0.3s';
            overlay.style.opacity = '0';
            setTimeout(function() { if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay); }, 300);
        }
    }

    // ── IndexedDB 缓存 ──
    function openDB(cb) {
        try {
            var r = indexedDB.open(DB_NAME, 1);
            r.onupgradeneeded = function(e) { e.target.result.createObjectStore(DB_STORE); };
            r.onsuccess = function(e) { cb(e.target.result); };
            r.onerror = function() { cb(null); };
        } catch(e) { cb(null); }
    }
    function cacheGet(db, key, cb) {
        if (!db) { cb(null); return; }
        try {
            var r = db.transaction(DB_STORE, 'readonly').objectStore(DB_STORE).get(key);
            r.onsuccess = function() { cb(r.result || null); };
            r.onerror = function() { cb(null); };
        } catch(e) { cb(null); }
    }
    function cachePut(db, key, val) {
        if (!db) return;
        try { db.transaction(DB_STORE, 'readwrite').objectStore(DB_STORE).put(val, key); } catch(e) {}
    }

    // ── XHR 探测 CDN（收集诊断信息）──
    function probeCdn(url) {
        return new Promise(function(ok) {
            var info = {
                url: url, method: 'GET', startTime: Date.now(),
                endTime: null, duration: null, status: null, statusText: null,
                responseHeaders: null, errorType: null, errorMessage: null, readyState: null
            };
            try {
                var x = new XMLHttpRequest();
                x.open('GET', url, true);
                x.timeout = TIMEOUT_MS;
                x.onreadystatechange = function() { info.readyState = x.readyState; };
                x.onload = function() {
                    info.endTime = Date.now(); info.duration = info.endTime - info.startTime;
                    info.status = x.status; info.statusText = x.statusText || '';
                    try { info.responseHeaders = x.getAllResponseHeaders(); } catch(e) { info.responseHeaders = '(CORS 限制)'; }
                    if (x.status >= 200 && x.status < 300) { info.errorType = null; }
                    else { info.errorType = 'HTTP_ERROR'; info.errorMessage = 'HTTP ' + x.status + ' ' + x.statusText; }
                    ok(info);
                };
                x.onerror = function() {
                    info.endTime = Date.now(); info.duration = info.endTime - info.startTime;
                    info.errorType = 'NETWORK_ERROR';
                    info.errorMessage = '网络请求失败。可能: DNS 失败、连接拒绝、SSL 握手失败、代理不可达。';
                    ok(info);
                };
                x.ontimeout = function() {
                    info.endTime = Date.now(); info.duration = info.endTime - info.startTime;
                    info.errorType = 'TIMEOUT';
                    info.errorMessage = '请求超时 (' + TIMEOUT_MS + 'ms)。';
                    ok(info);
                };
                x.send();
            } catch(e) {
                info.endTime = Date.now(); info.duration = info.endTime - info.startTime;
                info.errorType = 'EXCEPTION'; info.errorMessage = e.message || String(e);
                ok(info);
            }
        });
    }

    // ── 格式化诊断报告 ──
    function formatDiag(info) {
        var lines = [];
        lines.push('=== CDN 加载诊断 ===');
        lines.push('资源: ' + info.url);
        lines.push('方法: ' + info.method);
        lines.push('发起时间: ' + new Date(info.startTime).toISOString());
        lines.push('耗时: ' + info.duration + 'ms');
        lines.push('ReadyState: ' + info.readyState);
        lines.push('HTTP 状态: ' + (info.status || '无响应') + ' ' + (info.statusText || ''));
        lines.push('错误类型: ' + (info.errorType || '无'));
        lines.push('错误信息: ' + (info.errorMessage || '无'));
        if (info.responseHeaders) { lines.push('--- 响应头 ---'); lines.push(info.responseHeaders); }
        lines.push('');
        lines.push('=== 分析 ===');
        if (info.errorType === 'NETWORK_ERROR') {
            lines.push('XHR 网络错误通常意味着:');
            lines.push('  1. DNS 无法解析目标域名');
            lines.push('  2. TCP 连接被拒绝或超时');
            lines.push('  3. SSL/TLS 握手失败');
            lines.push('  4. 代理(如 VPN)无法转发请求');
            lines.push('  5. 目标服务器无响应');
        } else if (info.errorType === 'TIMEOUT') {
            lines.push('超时原因: 网络延迟过高或服务器无响应');
        } else if (info.errorType === 'HTTP_ERROR') {
            if (info.status === 403) lines.push('  403: 服务器拒绝访问');
            else if (info.status === 429) lines.push('  429: 请求频率过高');
            else if (info.status === 451) lines.push('  451: 因法律原因不可用');
            else if (info.status >= 500) lines.push('  5xx: 服务器内部错误');
        }
        return lines.join('\n');
    }

    // ── 加载工具 ──
    function loadScript(src) {
        return new Promise(function(ok, fail) {
            var s = document.createElement('script'); s.src = src;
            var t = setTimeout(function() { fail(new Error('timeout')); }, TIMEOUT_MS);
            s.onload = function() { clearTimeout(t); ok(); };
            s.onerror = function() { clearTimeout(t); fail(new Error('fail')); };
            document.head.appendChild(s);
        });
    }
    function loadLink(href) {
        return new Promise(function(ok, fail) {
            var l = document.createElement('link'); l.rel = 'stylesheet'; l.href = href;
            var t = setTimeout(function() { fail(new Error('timeout')); }, TIMEOUT_MS);
            l.onload = function() { clearTimeout(t); ok(); };
            l.onerror = function() { clearTimeout(t); fail(new Error('fail')); };
            document.head.appendChild(l);
        });
    }
    function fetchText(url) {
        return new Promise(function(ok, fail) {
            var x = new XMLHttpRequest(); x.open('GET', url, true); x.timeout = TIMEOUT_MS;
            x.onload = function() { x.status >= 200 && x.status < 300 ? ok(x.responseText) : fail(new Error('HTTP ' + x.status)); };
            x.onerror = function() { fail(new Error('network')); };
            x.ontimeout = function() { fail(new Error('timeout')); };
            x.send();
        });
    }

    // ── 错误对话框 ──
    function showErrorDialog(failedResources) {
        removeOverlay();
        var diagTexts = failedResources.map(function(r) { return formatDiag(r.diag); });
        var fullReport = diagTexts.join('\n\n' + '─'.repeat(50) + '\n\n');
        console.error('[CDN-FALLBACK-DIAG]\n' + fullReport);

        var overlay2 = document.createElement('div');
        overlay2.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:99999;display:flex;align-items:center;justify-content:center;padding:16px;font-family:system-ui,-apple-system,sans-serif;';
        var box = document.createElement('div');
        box.style.cssText = 'background:#fff;border-radius:16px;max-width:480px;width:100%;max-height:80vh;display:flex;flex-direction:column;box-shadow:0 8px 32px rgba(0,0,0,0.2);';
        var header = document.createElement('div');
        header.style.cssText = 'padding:16px 20px 12px;border-bottom:1px solid #e5e7eb;';
        var title = document.createElement('div');
        title.style.cssText = 'font-size:16px;font-weight:700;color:#dc2626;';
        title.textContent = 'CDN 资源加载失败';
        var subtitle = document.createElement('div');
        subtitle.style.cssText = 'font-size:12px;color:#9ca3af;margin-top:4px;';
        subtitle.textContent = failedResources.length + ' 个资源全部失败，无缓存可用';
        header.appendChild(title); header.appendChild(subtitle);

        var body = document.createElement('div');
        body.style.cssText = 'flex:1;overflow-y:auto;padding:12px 20px;';
        for (var i = 0; i < failedResources.length; i++) {
            var r = failedResources[i];
            var card = document.createElement('div');
            card.style.cssText = 'background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:10px 12px;margin-bottom:8px;';
            card.innerHTML = '<div style="font-size:13px;font-weight:600;color:#991b1b;margin-bottom:4px;">' + r.name + '</div>' +
                '<div style="font-size:11px;color:#6b7280;word-break:break-all;margin-bottom:4px;">URL: ' + r.diag.url + '</div>' +
                '<div style="font-size:11px;color:#dc2626;">' + r.diag.errorType + ': ' + r.diag.errorMessage + '</div>' +
                '<div style="font-size:10px;color:#9ca3af;margin-top:3px;">耗时 ' + r.diag.duration + 'ms | HTTP ' + (r.diag.status || '无响应') + '</div>';
            body.appendChild(card);
        }

        var footer = document.createElement('div');
        footer.style.cssText = 'padding:12px 20px 16px;border-top:1px solid #e5e7eb;display:flex;gap:10px;';
        var btnCopy = document.createElement('button');
        btnCopy.style.cssText = 'flex:1;padding:10px;border:1px solid #d1d5db;border-radius:10px;background:#fff;color:#374151;font-size:13px;font-weight:600;cursor:pointer;';
        btnCopy.textContent = '复制诊断报告';
        btnCopy.onclick = function() {
            try { navigator.clipboard.writeText(fullReport); btnCopy.textContent = '已复制'; setTimeout(function(){ btnCopy.textContent='复制诊断报告'; },2000); } catch(e) {}
        };
        var btnRetry = document.createElement('button');
        btnRetry.style.cssText = 'flex:1;padding:10px;border:none;border-radius:10px;background:#6366f1;color:#fff;font-size:13px;font-weight:600;cursor:pointer;';
        btnRetry.textContent = '重试';
        btnRetry.onclick = function() { location.reload(); };
        footer.appendChild(btnCopy); footer.appendChild(btnRetry);
        box.appendChild(header); box.appendChild(body); box.appendChild(footer);
        overlay2.appendChild(box); document.body.appendChild(overlay2);
    }

    // ── 处理单个被拦截的资源 ──
    // 策略: 探测 CDN → 成功则放行(恢复原URL) → 失败则缓存/本地
    function processIntercepted(item, db) {
        return new Promise(function(resolve) {
            var res = CDN_MAP[item.originalUrl];

            // CSS: 先探测
            if (res.isCss) {
                probeCdn(res.cdn).then(function(diag) {
                    if (!diag.errorType) {
                        // CDN 可达 → 恢复原 href，让浏览器正常加载
                        item.element.setAttribute('href', item.originalUrl);
                        // 异步缓存
                        fetchText(res.cdn).then(function(t) { cachePut(db, res.cdn, { content: t, ts: Date.now() }); }).catch(function(){});
                        resolve({ src: 'cdn', diag: diag });
                    } else {
                        // CDN 不可达 → 缓存/本地
                        fallbackLoad(res, db, diag, resolve);
                    }
                });
                return;
            }

            // Script: 先检查全局变量（可能已通过其他方式加载）
            if (res.check && res.check()) { resolve({ src: 'existing', diag: null }); return; }

            // 探测 CDN
            probeCdn(res.cdn).then(function(diag) {
                if (!diag.errorType) {
                    // CDN 可达 → 恢复原 src，让浏览器正常加载
                    // 用 Promise 等待脚本实际执行完成
                    var el = item.element;
                    var loaded = new Promise(function(ok, fail) {
                        var t = setTimeout(function() { fail(new Error('exec timeout')); }, TIMEOUT_MS);
                        el.onload = function() { clearTimeout(t); ok(); };
                        el.onerror = function() { clearTimeout(t); fail(new Error('exec fail')); };
                    });
                    el.setAttribute('src', item.originalUrl);

                    loaded.then(function() {
                        // 脚本执行成功 → 缓存
                        fetchText(res.cdn).then(function(t) { cachePut(db, res.cdn, { content: t, ts: Date.now() }); }).catch(function(){});
                        resolve({ src: 'cdn', diag: diag });
                    }).catch(function() {
                        // CDN 可达但脚本执行失败 → 回退
                        diag.errorType = 'EXEC_FAIL';
                        diag.errorMessage = 'CDN 可达 (HTTP ' + diag.status + ') 但脚本执行失败。';
                        el.removeAttribute('src');
                        if (el.parentNode) el.parentNode.removeChild(el);
                        fallbackLoad(res, db, diag, resolve);
                    });
                } else {
                    // CDN 不可达 → 回退
                    fallbackLoad(res, db, diag, resolve);
                }
            });
        });
    }

    // 回退: 缓存 → 本地 vendor
    function fallbackLoad(res, db, diag, resolve) {
        cacheGet(db, res.cdn, function(cached) {
            if (cached && cached.content) {
                if (res.isCss) {
                    var st = document.createElement('style'); st.textContent = cached.content;
                    document.head.appendChild(st);
                } else {
                    var s = document.createElement('script'); s.textContent = cached.content;
                    document.head.appendChild(s);
                }
                console.log('[cdn-fallback] ' + res.name + ' 使用缓存');
                resolve({ src: 'cache', diag: diag });
            } else {
                var loader = res.isCss ? loadLink : loadScript;
                loader(LOCAL_BASE + res.local).then(function() {
                    console.log('[cdn-fallback] ' + res.name + ' 使用本地');
                    resolve({ src: 'local', diag: diag });
                }).catch(function(localErr) {
                    diag.localError = localErr.message;
                    console.error('[cdn-fallback] ' + res.name + ' 完全失败');
                    resolve({ src: 'fail', diag: diag });
                });
            }
        });
    }

    // ── 调试面板 ──
    var jsErrors = [];
    window.addEventListener('error', function(e) {
        jsErrors.push({ msg: e.message, src: e.filename, line: e.lineno, col: e.colno });
    });

    function showDebugPanel(results) {
        removeOverlay();

        // 延迟检查全局变量（给脚本执行时间）
        setTimeout(function() {
            var lines = [];
            lines.push('=== CDN 加载结果 ===');
            lines.push('拦截元素数: ' + intercepted.length);
            lines.push('');
            for (var i = 0; i < results.length; i++) {
                var r = results[i];
                var res = r.res;
                var status = r.src;
                var globalOk = res.check ? (res.check() ? 'YES' : 'NO') : 'N/A';
                lines.push(res.name + ': ' + status + ' | 全局变量=' + globalOk);
            }
            lines.push('');
            lines.push('=== 全局变量状态 ===');
            lines.push('window.tailwind: ' + (typeof window.tailwind));
            lines.push('window.Vue: ' + (typeof window.Vue));
            lines.push('window.localforage: ' + (typeof window.localforage));
            lines.push('window.marked: ' + (typeof window.marked));
            lines.push('window.DOMPurify: ' + (typeof window.DOMPurify));
            lines.push('window.Sortable: ' + (typeof window.Sortable));
            lines.push('window.app: ' + (typeof window.app));
            lines.push('');
            lines.push('=== JS 错误 (' + jsErrors.length + ') ===');
            for (var j = 0; j < jsErrors.length; j++) {
                var err = jsErrors[j];
                lines.push(err.msg + ' @ ' + (err.src || 'inline') + ':' + err.line);
            }
            lines.push('');
            lines.push('=== DOM 状态 ===');
            lines.push('document.readyState: ' + document.readyState);
            lines.push('#app children: ' + (document.getElementById('app') ? document.getElementById('app').children.length : 'NOT FOUND'));
            lines.push('#app innerHTML length: ' + (document.getElementById('app') ? document.getElementById('app').innerHTML.length : 0));
            lines.push('[v-cloak] elements: ' + document.querySelectorAll('[v-cloak]').length);

            var report = lines.join('\n');
            console.error('[CDN-DEBUG]\n' + report);

            // 显示调试面板
            var panel = document.createElement('div');
            panel.style.cssText = 'position:fixed;top:0;right:0;width:320px;max-height:70vh;background:#1e1e2e;color:#cdd6f4;font:11px/1.5 monospace;padding:12px;overflow-y:auto;z-index:99998;border-bottom-left-radius:12px;box-shadow:-2px 2px 12px rgba(0,0,0,0.3);';
            var pre = document.createElement('pre');
            pre.style.cssText = 'margin:0;white-space:pre-wrap;word-break:break-all;';
            pre.textContent = report;
            var btn = document.createElement('button');
            btn.style.cssText = 'margin-top:8px;padding:4px 12px;background:#6366f1;color:#fff;border:none;border-radius:6px;font-size:11px;cursor:pointer;';
            btn.textContent = '关闭';
            btn.onclick = function() { panel.parentNode.removeChild(panel); };
            panel.appendChild(pre);
            panel.appendChild(btn);
            document.body.appendChild(panel);
        }, 2000);
    }

    // ── 主流程 ──
    function main() {
        if (!document.body) { setTimeout(main, 10); return; }

        // 停止 observer（拦截阶段结束）
        observer.disconnect();

        createOverlay();
        console.log('[cdn-fallback] 拦截到 ' + intercepted.length + ' 个 CDN 元素');

        openDB(function(db) {
            var index = 0;
            var failedResources = [];
            var allResults = [];

            function next() {
                if (index >= intercepted.length) {
                    if (failedResources.length > 0) {
                        showErrorDialog(failedResources);
                    }
                    // Debug 模式下显示调试面板
                    if (window.__RP_HUB_DEBUG__) showDebugPanel(allResults);
                    return;
                }

                var item = intercepted[index];
                var res = CDN_MAP[item.originalUrl];
                updateStatus('检测 ' + res.name + ' (' + (index + 1) + '/' + intercepted.length + ')...');

                processIntercepted(item, db).then(function(result) {
                    allResults.push({ res: res, src: result.src, diag: result.diag });
                    if (result.src === 'fail') {
                        failedResources.push({ name: res.name, type: res.isCss ? 'CSS' : 'JS', diag: result.diag });
                    }
                    index++;
                    next();
                });
            }
            next();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', main);
    } else {
        main();
    }
})();
