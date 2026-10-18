# 构建与验证

## 工具链

- JDK 17。
- Android SDK Platform 34、Android SDK Build-Tools 34.0.0（用于外部签名及检查）。
- 仓库 Gradle Wrapper 8.6；Android Gradle Plugin 8.2.0；Kotlin 1.9.0。
- 首次构建需联网下载 Gradle、Maven 和 Robolectric 依赖；离线执行需先缓存全部依赖。

设置 `JAVA_HOME`、`ANDROID_HOME`（或在本机 `local.properties` 写入 `sdk.dir`），再在仓库根目录执行。不要提交本机 SDK 路径。Windows 使用 `gradlew.bat` 替代 `bash gradlew`。

```sh
bash gradlew --no-daemon :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。Debug 使用开发签名，不用于正式发行。

## 验证目标

```sh
bash gradlew --no-daemon --max-workers=1 --rerun-tasks --continue \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease \
  :app:lintDebug :app:lintRelease :app:assembleDebugAndroidTest
```

JUnit/Robolectric 包含本地真实 Socket/HTTPS 测试，请允许 IPv6 loopback。完整历史与文档恢复用例可能耗时较长。查看 `app/build/test-results/`、`app/build/reports/` 获取实际测试与 lint 结果，命令退出成功不能替代检查报告。

`assembleDebugAndroidTest` **只编译**仪器测试。有设备时另执行 `:app:connectedDebugAndroidTest`；它不替代正式签名 Release APK 的实机安装、测速和数据恢复验收。

项目默认限制 Gradle workers 和内存。可在个人 Gradle 用户配置或命令行调整资源；不要把机器特有临时目录写入项目配置。

## Release 外部签名

`assembleRelease` 默认生成未签名 APK：`app/build/outputs/apk/release/app-release-unsigned.apk`。需要由发布者在项目外管理自己的长期签名密钥。普通开发者可使用自己的密钥构建，不需要也不应索取维护者私钥。

下面命令要求已安装 Build-Tools 并将其加入 PATH。用本机绝对路径设置 `KEYSTORE`、`STOREPASS_FILE`，设置 `KEY_ALIAS`；密码文件仅包含本机密钥密码，严禁提交。

```sh
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "file:$STOREPASS_FILE" --out app-release.apk app-release-aligned.apk
apksigner verify --verbose --print-certs app-release.apk
zipalign -c -v 4 app-release.apk
aapt dump badging app-release.apk
sha256sum app-release.apk
```

上例针对 PKCS12 且 key/store 同密码；此时可继承 store 密码，不要把同一个单行文件重复当成两次密码输入。其他密钥配置按对应工具文档处理。密码不要写入命令行明文或构建日志。

检查包名、versionCode/versionName、非 debuggable、证书指纹及 APK 哈希。外部签名不意味着构建逐字节可复现；验证源码版本、工具链和最终产物应分别记录。

## 源码发布与签名隔离

正式发布 tag 应指向 APK 对应源码，保留 Gradle Wrapper、配置、业务源码、资源和测试。不得把构建缓存、本机配置、内部工作记录或签名材料放入源码/Release 资产。

当前本地候选包名 `com.cfst.app.local`、versionCode `113`、versionName `1.2.1`。覆盖升级必须使用同一签名和更大的 versionCode，安装与数据保留须实机验证。请将密钥及恢复信息置于项目外安全保管，并在离线加密存储中保留已验证副本。

采样算法 v2 的固定 seed 只用于相同算法版本复现，不保证重建 1.2.0 的候选。默认探索的有效 seed 在确认启动时解析，并冻结在该次配置中；预览不消耗随机数。完整状态含新增 Boolean 设置，旧 APK 降级前必须保留独立旧备份，不保证旧版读取新状态安全。
