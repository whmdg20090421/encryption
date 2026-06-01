// CDN 加载策略补丁
// 优先 CDN → 失败时 IndexedDB 缓存 → 再失败时本地 vendor
// 全部失败时显示详细错误诊断信息
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

    var CDN_URLS = {};
    for (var i = 0; i < RESOURCES.length; i++) CDN_URLS[RESOURCES[i].cdn] = RESOURCES[i];

    // ── MutationObserver：拦截浏览器自动加载的 CDN 元素 ──
    var removedCdnElements = [];
    var observer = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
            var nodes = mutations[i].addedNodes;
            for (var j = 0; j < nodes.length; j++) {
                var node = nodes[j];
                if (node.nodeType !== 1) continue;
                var tag = node.tagName;
                var url = null;
                if (tag === 'SCRIPT') url = node.getAttribute('src');
                else if (tag === 'LINK') url = node.getAttribute('href');
                if (url && CDN_URLS[url]) {
                    node.setAttribute('data-cdn-original', url);
                    if (tag === 'SCRIPT') node.removeAttribute('src');
                    else node.removeAttribute('href');
                    removedCdnElements.push(node);
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
        statusEl.style.cssText = 'font-size:13px;color:#6b7280;text-align:center;max-width:280px;line-height:1.5;';
        statusEl.textContent = '正在加载资源...';
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

    // ── 诊断信息收集 ──
    // 用 XHR 探测 CDN，收集详细的网络诊断信息
    function probeCdn(url) {
        return new Promise(function(ok) {
            var info = {
                url: url,
                method: 'GET',
                startTime: Date.now(),
                endTime: null,
                duration: null,
                status: null,
                statusText: null,
                responseHeaders: null,
                errorType: null,
                errorMessage: null,
                readyState: null
            };
            try {
                var x = new XMLHttpRequest();
                x.open('GET', url, true);
                x.timeout = TIMEOUT_MS;
                x.onreadystatechange = function() { info.readyState = x.readyState; };
                x.onload = function() {
                    info.endTime = Date.now();
                    info.duration = info.endTime - info.startTime;
                    info.status = x.status;
                    info.statusText = x.statusText || '';
                    try { info.responseHeaders = x.getAllResponseHeaders(); } catch(e) { info.responseHeaders = '(CORS 限制)'; }
                    if (x.status >= 200 && x.status < 300) {
                        info.errorType = null;
                    } else {
                        info.errorType = 'HTTP_ERROR';
                        info.errorMessage = '服务器返回 HTTP ' + x.status + ' ' + x.statusText;
                    }
                    ok(info);
                };
                x.onerror = function() {
                    info.endTime = Date.now();
                    info.duration = info.endTime - info.startTime;
                    info.errorType = 'NETWORK_ERROR';
                    info.errorMessage = '网络请求失败。可能原因: DNS 解析失败、连接被拒绝、SSL 握手失败、代理不可达、或服务器无响应。';
                    ok(info);
                };
                x.ontimeout = function() {
                    info.endTime = Date.now();
                    info.duration = info.endTime - info.startTime;
                    info.errorType = 'TIMEOUT';
                    info.errorMessage = '请求超时 (' + TIMEOUT_MS + 'ms)。服务器未在规定时间内响应，可能网络缓慢或目标不可达。';
                    ok(info);
                };
                x.send();
            } catch(e) {
                info.endTime = Date.now();
                info.duration = info.endTime - info.startTime;
                info.errorType = 'EXCEPTION';
                info.errorMessage = e.message || String(e);
                ok(info);
            }
        });
    }

    // 格式化诊断信息为可读文本
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
        if (info.responseHeaders) {
            lines.push('--- 响应头 ---');
            lines.push(info.responseHeaders);
        }
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
            lines.push('超时原因:');
            lines.push('  1. 网络延迟过高 (>15s)');
            lines.push('  2. 服务器处理缓慢');
            lines.push('  3. 中间代理(如 VPN)引入延迟');
        } else if (info.errorType === 'HTTP_ERROR') {
            lines.push('HTTP 错误原因:');
            if (info.status === 403) lines.push('  403 Forbidden: 服务器拒绝访问，可能被 CDN 防护拦截');
            else if (info.status === 429) lines.push('  429 Too Many Requests: 请求频率过高，被限流');
            else if (info.status === 451) lines.push('  451 Unavailable For Legal Reasons: 因法律原因不可用，可能被地区封锁');
            else if (info.status >= 500) lines.push('  5xx: 服务器内部错误，CDN 节点可能故障');
            else lines.push('  请根据状态码查阅 HTTP 规范');
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

    function cleanupIntercepted() {
        for (var i = 0; i < removedCdnElements.length; i++) {
            var el = removedCdnElements[i];
            if (el.parentNode) el.parentNode.removeChild(el);
        }
        removedCdnElements = [];
    }

    // ── 详细错误对话框 ──
    function showErrorDialog(failedResources) {
        removeOverlay();

        var diagTexts = failedResources.map(function(r) { return formatDiag(r.diag); });
        var fullReport = diagTexts.join('\n\n' + '─'.repeat(50) + '\n\n');

        // 通过 console.error 输出完整诊断，Kotlin 层 DiagnosticLog 捕获
        console.error('[CDN-FALLBACK-DIAG]\n' + fullReport);

        // DOM 错误对话框
        var overlay2 = document.createElement('div');
        overlay2.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:99999;display:flex;align-items:center;justify-content:center;padding:16px;font-family:system-ui,-apple-system,sans-serif;';

        var box = document.createElement('div');
        box.style.cssText = 'background:#fff;border-radius:16px;max-width:480px;width:100%;max-height:80vh;display:flex;flex-direction:column;box-shadow:0 8px 32px rgba(0,0,0,0.2);';

        // 标题
        var header = document.createElement('div');
        header.style.cssText = 'padding:16px 20px 12px;border-bottom:1px solid #e5e7eb;';
        var title = document.createElement('div');
        title.style.cssText = 'font-size:16px;font-weight:700;color:#dc2626;';
        title.textContent = 'CDN 资源加载失败';
        var subtitle = document.createElement('div');
        subtitle.style.cssText = 'font-size:12px;color:#9ca3af;margin-top:4px;';
        subtitle.textContent = failedResources.length + ' 个资源全部加载失败，无本地缓存可用';
        header.appendChild(title);
        header.appendChild(subtitle);

        // 详情列表
        var body = document.createElement('div');
        body.style.cssText = 'flex:1;overflow-y:auto;padding:12px 20px;';

        for (var i = 0; i < failedResources.length; i++) {
            var r = failedResources[i];
            var card = document.createElement('div');
            card.style.cssText = 'background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:10px 12px;margin-bottom:8px;';

            var nameRow = document.createElement('div');
            nameRow.style.cssText = 'font-size:13px;font-weight:600;color:#991b1b;margin-bottom:4px;';
            nameRow.textContent = r.name + ' (' + r.type + ')';

            var urlRow = document.createElement('div');
            urlRow.style.cssText = 'font-size:11px;color:#6b7280;word-break:break-all;margin-bottom:4px;';
            urlRow.textContent = 'URL: ' + r.diag.url;

            var errRow = document.createElement('div');
            errRow.style.cssText = 'font-size:11px;color:#dc2626;font-weight:500;';
            errRow.textContent = r.diag.errorType + ': ' + r.diag.errorMessage;

            var detailRow = document.createElement('div');
            detailRow.style.cssText = 'font-size:10px;color:#9ca3af;margin-top:3px;';
            detailRow.textContent = '耗时 ' + r.diag.duration + 'ms | HTTP ' + (r.diag.status || '无响应') + ' | readyState=' + r.diag.readyState;

            card.appendChild(nameRow);
            card.appendChild(urlRow);
            card.appendChild(errRow);
            card.appendChild(detailRow);
            body.appendChild(card);
        }

        // 复制按钮
        var footer = document.createElement('div');
        footer.style.cssText = 'padding:12px 20px 16px;border-top:1px solid #e5e7eb;display:flex;gap:10px;';

        var btnCopy = document.createElement('button');
        btnCopy.style.cssText = 'flex:1;padding:10px;border:1px solid #d1d5db;border-radius:10px;background:#fff;color:#374151;font-size:13px;font-weight:600;cursor:pointer;';
        btnCopy.textContent = '复制诊断报告';
        btnCopy.onclick = function() {
            try {
                navigator.clipboard.writeText(fullReport).then(function() {
                    btnCopy.textContent = '已复制';
                    setTimeout(function() { btnCopy.textContent = '复制诊断报告'; }, 2000);
                }).catch(function() {
                    // fallback
                    var ta = document.createElement('textarea');
                    ta.value = fullReport;
                    ta.style.cssText = 'position:fixed;left:-9999px;';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                    btnCopy.textContent = '已复制';
                    setTimeout(function() { btnCopy.textContent = '复制诊断报告'; }, 2000);
                });
            } catch(e) {}
        };

        var btnRetry = document.createElement('button');
        btnRetry.style.cssText = 'flex:1;padding:10px;border:none;border-radius:10px;background:#6366f1;color:#fff;font-size:13px;font-weight:600;cursor:pointer;';
        btnRetry.textContent = '重试加载';
        btnRetry.onclick = function() { location.reload(); };

        footer.appendChild(btnCopy);
        footer.appendChild(btnRetry);

        box.appendChild(header);
        box.appendChild(body);
        box.appendChild(footer);
        overlay2.appendChild(box);
        document.body.appendChild(overlay2);
    }

    // ── 单个资源加载：CDN → 缓存 → 本地，同时收集诊断信息 ──
    function loadResource(res, db) {
        return new Promise(function(resolve) {
            var diag = null;

            // 先用 XHR 探测 CDN 获取详细诊断
            probeCdn(res.cdn).then(function(d) {
                diag = d;

                if (res.isCss) {
                    // CSS
                    var existing = document.querySelector('link[href="' + res.cdn + '"]');
                    if (existing) { resolve({ src: 'existing', diag: diag }); return; }

                    if (!diag.errorType) {
                        // XHR 成功 → 尝试用 link 加载（可能 XHR 成功但 link 失败，如 MIME 不对）
                        loadLink(res.cdn).then(function() {
                            fetchText(res.cdn).then(function(t) { cachePut(db, res.cdn, { content: t, ts: Date.now() }); }).catch(function(){});
                            resolve({ src: 'cdn', diag: diag });
                        }).catch(function() {
                            diag.errorType = 'ELEMENT_LOAD_FAIL';
                            diag.errorMessage = 'XHR 探测成功 (HTTP ' + diag.status + ') 但 <link> 元素加载失败。可能 MIME 类型不匹配或内容被篡改。';
                            tryCache(res, db, diag, resolve);
                        });
                    } else {
                        tryCache(res, db, diag, resolve);
                    }
                    return;
                }

                // Script
                if (res.check && res.check()) { resolve({ src: 'existing', diag: diag }); return; }

                if (!diag.errorType) {
                    loadScript(res.cdn).then(function() {
                        fetchText(res.cdn).then(function(t) { cachePut(db, res.cdn, { content: t, ts: Date.now() }); }).catch(function(){});
                        resolve({ src: 'cdn', diag: diag });
                    }).catch(function() {
                        diag.errorType = 'ELEMENT_LOAD_FAIL';
                        diag.errorMessage = 'XHR 探测成功 (HTTP ' + diag.status + ') 但 <script> 元素执行失败。可能内容被篡改或 CSP 限制。';
                        tryCache(res, db, diag, resolve);
                    });
                } else {
                    tryCache(res, db, diag, resolve);
                }
            });
        });
    }

    function tryCache(res, db, diag, resolve) {
        cacheGet(db, res.cdn, function(cached) {
            if (cached && cached.content) {
                if (res.isCss) {
                    var st = document.createElement('style'); st.textContent = cached.content;
                    document.head.appendChild(st);
                } else {
                    var s = document.createElement('script'); s.textContent = cached.content;
                    document.head.appendChild(s);
                }
                resolve({ src: 'cache', diag: diag });
            } else {
                // 尝试本地 vendor
                var loader = res.isCss ? loadLink : loadScript;
                loader(LOCAL_BASE + res.local).then(function() {
                    resolve({ src: 'local', diag: diag });
                }).catch(function(localErr) {
                    diag.localError = localErr.message;
                    resolve({ src: 'fail', diag: diag });
                });
            }
        });
    }

    // ── 主流程 ──
    function main() {
        if (!document.body) { setTimeout(main, 10); return; }

        observer.disconnect();
        cleanupIntercepted();
        createOverlay();

        openDB(function(db) {
            var index = 0;
            var failedResources = [];

            function next() {
                if (index >= RESOURCES.length) {
                    if (failedResources.length > 0) {
                        showErrorDialog(failedResources);
                    } else {
                        removeOverlay();
                    }
                    return;
                }
                var res = RESOURCES[index];
                updateStatus('加载 ' + res.name + ' (' + (index + 1) + '/' + RESOURCES.length + ')...');

                loadResource(res, db).then(function(result) {
                    if (result.src === 'fail') {
                        console.error('[CDN-FALLBACK] ' + res.name + ' 完全加载失败:', JSON.stringify(result.diag));
                        failedResources.push({ name: res.name, type: res.isCss ? 'CSS' : 'JS', diag: result.diag });
                    } else {
                        console.log('[CDN-FALLBACK] ' + res.name + ' 加载成功 (来源: ' + result.src + ')');
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
