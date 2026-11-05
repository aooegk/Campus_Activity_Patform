# 找个搭子 · 安卓 / 苹果双平台运行说明

## 架构（一句话）

后端（Spring Boot + PostgreSQL）跑在电脑/服务器上，手机端不装后端；
手机用浏览器/PWA 或原生壳访问同一套后端 API，业务逻辑（报名事务、
满员候补、退出递补、限时确认）只在后端一份，前后端接口不变。

## 方式一：PWA / 浏览器（安卓和苹果都已跑通，推荐演示用）

1. 电脑上启动后端（Windows 包：`windows-package/run.bat`，或本机的 Jar）。
2. 手机和电脑连同一个 Wi-Fi，查电脑局域网 IP（如 192.168.1.5）。
3. 手机浏览器打开 `http://192.168.1.5:8080`（把 8080 换成实际端口）。
4. 安装到主屏幕：
   - **安卓 Chrome**：菜单 →「添加到主屏幕」/「安装应用」，图标为陶土橙底白字「搭」，
     全屏 standalone 打开，效果和原生 App 一样。
   - **苹果 Safari**：分享 →「添加到主屏幕」，图标同上（已配 apple-touch-icon）。
5. 原生 App 模式才需要填「后端地址」：登录页底部有一行输入框，填电脑地址
   （如 `http://192.168.1.5:8080`）后登录；网页/PWA 留空即可，因为页面本身
   就来自后端地址，`/api` 相对路径天然指向后端。

已验证（2026-11-02，本地实测，脚本 `tests/test_mobile.py`，16/16 通过）：
- 安卓：Chromium + Pixel 7 仿真（412×915，触屏）：登录、看大厅、看详情、
  我的活动、通知、组织者发布活动「安卓手机测试」成功落库并出现在大厅，
  全程无横向溢出。截图在 `screenshots/mobile-android-*.png`。
- 苹果：Chromium + iPhone 14 仿真（390×844，iOS UA，触屏）：登录、看大厅、
  看详情、我的活动、通知、无横向溢出通过；`apple-mobile-web-app-capable`、
  `apple-touch-icon`、`manifest.json`（short_name「找个搭子」，192/512 图标）
  均已验证。截图在 `screenshots/mobile-ios-*.png`。
- 复核（2026-11-05，16/16 再次通过）：本机重置后先按 2026-11-04 17:14 的备份恢复库（21 用户、3 活动、11 参与，核对无误）、重启后端再跑 `tests/test_mobile.py`，安卓与苹果流程全过；测试脚本里的发布日期因过期顺延了 2 天；手机上新发布的「安卓手机测试」活动成功落库，库内活动数由 3 变为 4。截图已更新为本次实测（`screenshots/mobile-*.png`，2026-11-05）。用户已确认 PWA 为演示路线。
- 诚实说明：本机曾尝试用 Playwright 的真实 WebKit（Safari 同款引擎）跑 iOS，
  浏览器本体已能启动，但 WebProcess 仍缺系统库（libbacktrace、libjxl 等，
  逐个补库链条很长），最终 iOS 以 iPhone 仿真 + 上述 PWA 元数据验证为准；
  真机 Safari 的最终验收就是上面方式一的第 3–4 步，在 iPhone 上 1 分钟可完成。

## 方式二：原生壳（Capacitor，工程已生成）

前端已装 Capacitor，包名 `com.campusbuddy.app`，应用名「找个搭子」：

- 安卓工程：`frontend/android/`（已同步最新 dist，含 PWA 文件）
- 苹果工程：`frontend/ios/`（已同步最新 dist；Info.plist 已加
  `NSAllowsLocalNetworking`，局域网 http 后端可通）
- 配置：`frontend/capacitor.config.json`（`server.cleartext: true`，允许 http 后端）

### 安卓：出 APK（需在有直连网络的电脑/Android Studio 里做）

本机已装好 Android SDK（`workspace/.portable/android-sdk`：platform-tools、
platforms android-34/36、build-tools 34.0.0）与 Gradle 8.14.3，
但本机出口代理会把 Gradle 依赖下载连接重置（curl、Maven、sdkmanager 都通，
唯独 Gradle 的下载客户端被重置，阿里云镜像也一样），所以 APK 没能在这台
机器上编出来——这是环境问题，不是工程问题。换一台能直连的电脑：

```bash
cd frontend/android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或用 Android Studio 打开 `frontend/android/`，点 Run。装到手机后，
登录页填电脑后端地址即可使用（见方式一第 5 步）。

### 苹果：出 IPA（必须用 Mac）

iOS 应用只能在 macOS + Xcode 里编译，Linux 上编不出来，这是平台限制：

```bash
cd frontend
npx cap sync ios
npx cap open ios   # 在 Xcode 里选真机或模拟器，点 Run；或 Product → Archive 出包
```

## 方式三：安卓模拟器（2026-11-04 在本机实测，未能跑通，原因已查明）

本机（Linux，无硬件虚拟化：无 `/dev/kvm`，CPU 无 vmx/svm，只能纯软件模拟
TCG）上实测了 Android SDK 自带模拟器，结果是**模拟器本身无法完成开机**，
不是应用的问题，应用一次都没被打开过，数据库也全程未被触碰：

- 安卓 14（`system-images;android-34;google_apis;x86_64`，AVD `campus`，720×1280）：
  首次开机解压/优化耗时很长，随后每次开机都在系统服务启动后半段被安卓
  看门狗（Watchdog）杀掉，前后 6 次，死在不同步骤（包管理锁、PeopleService
  数据加载、默认权限授予），主线程阻塞 93–117 秒即被杀。
- 安卓 30（`system-images;android-30;google_apis;x86`，AVD `campus30`，720×1280）：
  更轻的镜像也一样，恢复开机后仍在系统服务阶段被看门狗杀掉。
- 已试过且无效：`device_config put watchdog system_server_watchdog_timeout_ms
  600000`（值能持久化，但不生效）、`settings put global` 同键、`watchdog.timeout`
  等 sysprop。并用 dex 解析核对了安卓 30 的 `services.jar`：其 Watchdog
  没有 SettingsObserver、也不引用该键，超时在该版本里不是运行时可调的。
- 结论：没有 KVM 的机器上，纯软件模拟跑完整安卓系统镜像会被看门狗判超时，
  换镜像、调参数都绕不过去。**要在模拟器里跑，换一台带 KVM 的 Linux/Windows
  机器**（BIOS 开 VT-x/AMD-V，`emulator -avd campus30 -gpu swiftshader_indirect`
  即可；或 Android Studio 里直接 Run），或直接用真机走方式一。
- 补充：实测期间本机虚拟机被重置两次（18:37、约 20:38），`/tmp` 下的数据库
  和后端进程随之丢失；工作区完好，已按 17:14 备份恢复并复核（21 用户、3 活动、
  11 参与，容量与唯一约束全干净），模拟器全程未写库。

## 改动清单（相对 Windows 版）

- `frontend/public/`：新增 `manifest.json`、`icon-192.png`、`icon-512.png`、
  `apple-touch-icon.png`（陶土橙底白字「搭」）。
- `frontend/index.html`：加 manifest、theme-color、viewport-fit、
  `apple-mobile-web-app-*`、apple-touch-icon。
- `frontend/src/App.vue`：`api()` 支持 `apiBase`（localStorage），登录页加
  「后端地址」输入框，仅原生 App 场景用。
- `frontend/android/build.gradle`：仓库加阿里云镜像（google/public/gradle-plugin）
  优先，大陆可直连。
- 新增：`frontend/capacitor.config.json`、`frontend/android/`、`frontend/ios/`、
  `tests/test_mobile.py`、`screenshots/mobile-*.png`、本文档。
- 后端、数据库、接口、业务规则均未改动；新 Jar 已含上述前端改动。
