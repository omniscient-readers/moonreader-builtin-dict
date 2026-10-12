# 构建平台说明

## 为什么要在 PC 上打包

一个容易踩的坑：在 Android 上（Termux 之类）跑 `apktool b` 通常会失败。

原因是 apktool 打包资源时要调 `aapt2`，而 Google 只发了
Windows、Linux(x86_64)、macOS 的官方二进制。社区移植版要么版本太旧，要么在 Android 的
bionic libc 上跑不起来，报错一般是 `aapt2: cannot execute`，或者资源编译到一半就退出。

所以流程是 PC 上做反编译、打补丁、编译 Java、打包、签名，设备上只负责安装和运行。

`build.ps1` 是给 Windows + PowerShell 写的。在 Linux 或 macOS 上按 [BUILD.md](BUILD.md)
手工跑同一串命令就行，脚本本身没有 Windows 专有的东西，只有 `build.ps1`、
`fetch-tools.ps1`、`gen-keystore.ps1` 三个外壳是 PowerShell。

## 对齐和签名

`uber-apk-signer` 一步做完 zipalign 加签名，v1/v2/v3 视 Android 版本自动选。等价的原生
命令是：

```bash
# 4 字节对齐，-p 让 .so 页对齐（这里没有 .so，加上也无害）
zipalign -p -f 4 merged.apk merged-aligned.apk

# 签名
apksigner sign --ks mrdict.keystore --ks-key-alias mrdict \
    --out merged-aligned-signed.apk merged-aligned.apk

# 验签
apksigner verify --print-certs merged-aligned-signed.apk
```

`zipalign` 和 `apksigner` 在 build-tools 里。这里用 Java 实现的 `uber-apk-signer`，
好处是不用装完整的 SDK。

## resources.arsc 为什么不能压缩

Android 11（API 30）开始要求 `resources.arsc` 以 STORED 方式存放，也就是不压缩，否则
安装时可能直接被拒。apktool 打包时本来就满足，但任何按条目重写 zip 的步骤都可能把它
变成 deflate。`inject_dex.py` 因此显式保留每个条目原来的压缩方式，只把新的
`classes8.dex` 用 deflate 写进去。`verify_apk.py` 里 `resources.arsc is stored` 那一项
就是盯这个的。

## 新 dex 为什么叫 classes8.dex

原 APK 已经有 `classes.dex` 到 `classes7.dex`。多 dex 的加载顺序是按文件名排的，新 dex
放最后能保证它不改变原有类的解析顺序。`inject_dex.py` 会先看这个名字有没有被占用，
占用了就往后找空位。

## minSdk 和 API 级别

`D8 --min-api` 要小于等于原 App 的 `minSdkVersion`，否则 desugar 出来的代码可能用到
设备上不存在的 API。原 App 的 `minSdkVersion` 在 `AndroidManifest.xml` 或 `apktool.yml`
里能看到。`--release` 去掉调试信息、体积更小，调试阶段可以去掉它保留行号。

`DictActivity` 的 theme 用的是 `@android:style/Theme.Material.Light.NoActionBar`，
需要 API 21 以上。目标设备更低的话换成 `Theme.Holo.Light.NoActionBar`。

## 常见的几种失败

| 现象 | 原因和处理 |
| --- | --- |
| `apktool b` 报 aapt2 相关错误 | 见上面，换 PC 构建，或用 `--aapt <aapt2 路径>` 指定一个能用的 aapt2 |
| 安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名和已装版本不一致，先卸载原版，或者改 `applicationId` |
| 安装报 `INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION` | 多半是 `resources.arsc` 被压缩了，用 `verify_apk.py` 确认 |
| 点「词典」没反应 | 挂钩的方法签名和你的版本对不上，`apply_mod.py` 会打印是哪一条没匹配上 |
| 卡片弹出来但查不到词 | 词库没打进 APK 或者加载失败，词典界面会显示条目数和错误信息 |
| 编译报 `cannot find symbol: com.flyersoft.tools.A` | `java/stubs` 没加进 `-classpath` |
