# 第三方许可随附资料

本目录应与正式 APK **一并交付**，不是可省略的可选附件。目录中的第三方条款不替代本应用的 [GPLv3](../LICENSE) 和完整对应源码。

- `licenses/Apache-2.0.txt`：AndroidX、Material、Kotlin、Coroutines、OkHttp、Okio、Gson 等运行依赖所用 Apache License 2.0 完整正文。
- `notices/kotlinx-coroutines-NOTICE.txt`：Coroutines 1.7.3 运行库声明。
- `notices/okhttp-publicsuffix-NOTICE.txt`：OkHttp 内嵌 Public Suffix List 声明。
- `licenses/MPL-2.0.txt` 和 `sources/public_suffix_list.dat`：APK 内 Public Suffix List 编译数据的许可证及对应可修改源数据；源数据按 MPL-2.0 提供。
- `notices/kotlin-COPYRIGHT.txt`、`licenses/BSL-1.0.txt`：Kotlin 运行库相关归属及保守随附的 Boost 条款。
- `COMPONENTS.txt`：当前 releaseRuntimeClasspath 的解析组件清单，包括 BOM/多平台选择器，不代表 APK 内含所有组件的独立代码副本。
- `SOURCES.json`：这些资料的版本固定公开来源与 SHA-256。

## Public Suffix List 源码获取

本发布随附 `sources/public_suffix_list.dat`，可直接取得并修改。它与 OkHttp 4.12.0 使用的数据对应，原公开版本：

https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat

源文件 SHA-256：`e8b273972eb5a70e888bd3e7d7c5b9b04e600a59d69def9136a74d70ae6fcdd3`。

已核对此文件生成的 9499 条普通规则和 8 条例外规则，与 1.2.0（112）发布 APK 中的对应数据完全一致。

## 分发方式

推荐分发包含 APK、本目录、应用 LICENSE、对应完整源码及构建文档的完整发行包。不要只转发裸 APK 而丢弃这些随附资料。其他渠道应保证许可、NOTICE 和对应源码获取告知实际到达接收者；本文不保证任意应用商店的交付流程满足这些要求。
