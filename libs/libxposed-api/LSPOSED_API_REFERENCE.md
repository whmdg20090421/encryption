# LSPosed API 参考文档

> 基于 LSPosed 仓库源码 (github.com/LSPosed/LSPosed) 整理

## 1. 架构概览

```
LSPosed Daemon (root 进程)
├── LSPosedService         ← ILSPosedService.Stub，注册为系统服务 "serial"
├── LSPManagerService      ← ILSPManagerService.Stub，管理器专用
├── LSPApplicationService  ← ILSPApplicationService.Stub，模块进程专用
├── LSPInjectedModuleService ← ILSPInjectedModuleService.Stub，注入模块专用
└── ConfigManager          ← SQLite 数据库操作（scope 表）
```

## 2. 服务连接链路

### 2.1 获取 ILSPosedService（需要 root/system 权限）

```java
// 通过系统服务名 "serial" 获取（LSPosed 代理了此服务名）
IBinder binder = android.os.ServiceManager.getService("serial");
ILSPosedService lspdService = ILSPosedService.Stub.asInterface(binder);
```

### 2.2 获取 ILSPApplicationService（模块进程内）

```java
// 在 Xposed 模块进程中，通过心跳获取
ILSPApplicationService appService = lspdService.requestApplicationService(uid, pid, processName, heartBeat);
```

### 2.3 获取 ILSPManagerService（通过 ApplicationService）

```java
// 从 ApplicationService 获取管理器 binder
List<IBinder> binders = new ArrayList<>();
IBinder managerBinder = appService.requestInjectedManagerBinder(binders);
ILSPManagerService managerService = ILSPManagerService.Stub.asInterface(managerBinder);
```

## 3. ILSPManagerService 接口（AIDL）

| 方法 | 说明 |
|------|------|
| `String getApi()` | 获取 API 版本 |
| `ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess)` | 获取所有用户已安装包 |
| `String[] enabledModules()` | 获取已启用模块列表 |
| `boolean enableModule(String packageName)` | 启用模块 |
| `boolean disableModule(String packageName)` | 禁用模块 |
| **`boolean setModuleScope(String packageName, List<Application> scope)`** | **设置模块作用域列表** |
| **`List<Application> getModuleScope(String packageName)`** | **获取模块作用域列表** |
| `boolean isVerboseLog()` | 是否详细日志 |
| `void setVerboseLog(boolean enabled)` | 设置详细日志 |
| `int getXposedApiVersion()` | 获取 Xposed API 版本 |
| `String getXposedVersionName()` | 获取 Xposed 版本名 |
| `int getXposedVersionCode()` | 获取 Xposed 版本号 |
| `boolean clearLogs(boolean verbose)` | 清除日志 |
| `PackageInfo getPackageInfo(String packageName, int flags, int uid)` | 获取包信息 |
| `void forceStopPackage(String packageName, int userId)` | 强制停止包 |
| `void reboot()` | 重启 |
| `boolean uninstallPackage(String packageName, int userId)` | 卸载包 |
| `boolean isSepolicyLoaded()` | SELinux 策略是否加载 |
| `List<UserInfo> getUsers()` | 获取用户列表 |
| `void setHiddenIcon(boolean hide)` | 隐藏图标 |
| `void getLogs(ParcelFileDescriptor zipFd)` | 获取日志 |
| `boolean enableStatusNotification()` | 启用状态通知 |
| `void setEnableStatusNotification(boolean enable)` | 设置状态通知 |
| `boolean getDexObfuscate()` | 获取混淆状态 |
| `void setDexObfuscate(boolean enable)` | 设置混淆 |

## 4. Application 模型（AIDL）

```java
parcelable Application {
    String packageName;  // 应用包名
    int userId;          // 用户 ID（通常为 0）
}
```

## 5. ILSPApplicationService 接口

| 方法 | 说明 |
|------|------|
| `List<Module> getLegacyModulesList()` | 旧版模块列表 |
| `List<Module> getModulesList()` | 当前模块列表 |
| `String getPrefsPath(String packageName)` | 获取模块 prefs 路径 |
| `IBinder requestInjectedManagerBinder(out List<IBinder> binder)` | 获取管理器 binder |

## 6. RemotePreferences 机制

`XposedInterface.getRemotePreferences("group")` 返回的是 `LSPosedRemotePreferences` 实例：

- **只读**：`edit()` 方法直接抛出 `UnsupportedOperationException`
- **数据源**：通过 `ILSPInjectedModuleService.requestRemotePreferences(group, callback)` 从 daemon 获取
- **存储位置**：LSPosed daemon 的数据目录，**不是**模块 app 的 SharedPreferences
- **实时更新**：通过 `IRemotePreferenceCallback.onUpdate()` 回调接收变更

**重要**：模块的 `getRemotePreferences()` 和 UI 的 `getSharedPreferences()` 读写的是**完全不同的存储**。

## 7. 作用域（Scope）管理

### 数据库结构

```sql
CREATE TABLE scope (
    mid INTEGER,           -- 模块 ID（关联 modules 表）
    app_pkg_name TEXT,     -- 目标应用包名
    user_id INTEGER,       -- 用户 ID
    PRIMARY KEY (mid, app_pkg_name, user_id),
    FOREIGN KEY (mid) REFERENCES modules(mid) ON DELETE CASCADE
);
```

### 查询模块作用域

```java
// ConfigManager.getModuleScope() 实现
Cursor cursor = db.query(
    "scope INNER JOIN modules ON scope.mid = modules.mid",
    new String[]{"app_pkg_name", "user_id"},
    "module_pkg_name=?",
    new String[]{packageName},
    null, null, null
);
List<Application> result = new ArrayList<>();
while (cursor.moveToNext()) {
    Application scope = new Application();
    scope.packageName = cursor.getString(appPkgNameIdx);
    scope.userId = cursor.getInt(userIdIdx);
    result.add(scope);
}
```

### 设置模块作用域

```java
// ConfigManager.setModuleScope() 实现
db.delete("scope", "mid = ?", new String[]{String.valueOf(mid)});
for (Application app : scopes) {
    ContentValues values = new ContentValues();
    values.put("mid", mid);
    values.put("app_pkg_name", app.packageName);
    values.put("user_id", app.userId);
    db.insertWithOnConflict("scope", null, values, SQLiteDatabase.CONFLICT_IGNORE);
}
```

## 8. 模块作用域申请流程

### 方式一：通过 ILSPManagerService（推荐）

```java
// 1. 连接到 LSPosed 服务
IBinder binder = android.os.ServiceManager.getService("serial");
ILSPosedService lspd = ILSPosedService.Stub.asInterface(binder);

// 2. 获取 ApplicationService
ILSPApplicationService appSvc = lspd.requestApplicationService(uid, pid, processName, heartbeat);

// 3. 获取 ManagerService
List<IBinder> binders = new ArrayList<>();
IBinder mgrBinder = appSvc.requestInjectedManagerBinder(binders);
ILSPManagerService mgr = ILSPManagerService.Stub.asInterface(mgrBinder);

// 4. 查询当前作用域
List<Application> scope = mgr.getModuleScope("com.whmdg.mczj.tools");

// 5. 添加新作用域
Application newApp = new Application();
newApp.packageName = "net.defensezone3.ultra";
newApp.userId = 0;
scope.add(newApp);
mgr.setModuleScope("com.whmdg.mczj.tools", scope);
```

### 方式二：通过 LSPosed Manager UI

```java
// 启动 LSPosed Manager 的作用域配置界面
Intent intent = new Intent();
intent.setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.ScopeActivity");
intent.putExtra("module_pkg_name", "com.whmdg.mczj.tools");
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(intent);
```

## 9. 权限要求

- 连接 `ILSPosedService`：需要 root 或 system 权限（系统服务 "serial"）
- 连接 `ILSPApplicationService`：需要在 LSPosed 模块进程中（由框架自动注入）
- 连接 `ILSPManagerService`：需要通过 `requestInjectedManagerBinder()` 获取
- 读写 scope 数据库：需要 root 权限（daemon 进程）

## 10. 模块进程中的 binder 链调用

LSPosed 将 `BridgeService` 注入到每个模块进程中，模块可通过以下反射链获取 `ILSPManagerService`：

```kotlin
// 1. 获取 ILSPosedService
val bridgeClass = Class.forName("org.lsposed.lspd.service.BridgeService")
val lspdService = bridgeClass.getMethod("getService").invoke(null) as IBinder

// 2. 获取 ILSPApplicationService
val lspdStub = Class.forName("org.lsposed.lspd.service.ILSPosedService\$Stub")
val lspd = lspdStub.getMethod("asInterface", IBinder::class.java).invoke(null, lspdService)
val appService = lspd.javaClass.getMethod("requestApplicationService",
    Int::class.java, Int::class.java, String::class.java, IBinder::class.java
).invoke(lspd, uid, pid, processName, heartbeat) as IBinder

// 3. 获取 ILSPManagerService
val appStub = Class.forName("org.lsposed.lspd.service.ILSPApplicationService\$Stub")
val appSvc = appStub.getMethod("asInterface", IBinder::class.java).invoke(null, appService)
val mgrBinder = appSvc.javaClass.getMethod("requestInjectedManagerBinder", java.util.List::class.java)
    .invoke(appSvc, ArrayList<IBinder>()) as IBinder
val manager = Class.forName("org.lsposed.lspd.ILSPManagerService\$Stub")
    .getMethod("asInterface", IBinder::class.java).invoke(null, mgrBinder)

// 4. 调用接口
val scope = manager.javaClass.getMethod("getModuleScope", String::class.java)
    .invoke(manager, "com.whmdg.mczj.tools") as List<Application>
```

**注意**：此链路仅在模块进程内有效（`BridgeService.getService()` 非 null）。UI 进程中如果模块已注入也可使用。
