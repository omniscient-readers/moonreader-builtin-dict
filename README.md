# Moon+ Reader 内置词典 Mod

给 Android 阅读器 Moon+ Reader（静读天下）加一个离线词典，通过 smali 挂钩加一个独立
dex 叠加到原 App 上。

> 仅供个人学习研究使用。原版 App 是商业软件，反编译和再分发可能违反它的许可协议
> 与当地法律，请自行确认使用场景合法，不要分发修改后的 APK。

## 功能

- 阅读时选中中英文单词或成语，用卡片弹出释义，不再跳外部词典
- 卡片分两个标签：词典查本地词库，百科用内嵌 WebView 开百度百科，链接不外跳
- 英文做词形还原，中文支持汉字、词语、成语，长词查不到会回退到词头
- 查不到词时列出前缀匹配的候选
- `mrdict_user.txt` 放到 App 外部目录就能补词，不用重新打包
- 注册了 PROCESS_TEXT 和 SEARCH，别的 App 里选中文字也能调起来
- `NetSites` 可以把自定义书库写进 Moon+ Reader 的书源目录

## 目录结构

```
.
├── build.ps1                    一键构建：反编译、打补丁、编译、打包、签名
├── java/
│   ├── com/flyersoft/moonreader/builtindict/
│   │   ├── DictEngine.java      词库引擎：加载、查询、词形还原、前缀建议
│   │   ├── DictPopup.java       阅读页词典卡片
│   │   ├── DictActivity.java    独立查词界面
│   │   ├── NetSites.java        在线书源导入
│   │   └── EsjActivity.java     可选，站内 WebView 浏览器
│   └── stubs/com/flyersoft/tools/A.java   编译期桩
├── scripts/
│   ├── apply_mod.py             给反编译树打补丁
│   ├── inject_dex.py            把编译好的 classes8.dex 塞进 APK
│   ├── pack_jar.py              把 .class 打成 jar，d8 不收目录
│   ├── build_wordlists.py       从公开数据源生成 dict_*.txt 词库
│   ├── dump_manifest.py         解析二进制 AndroidManifest.xml
│   ├── verify_apk.py            检查成品里的词库、dex、manifest、arsc 和签名
│   ├── fetch-tools.py           下载 jar 工具链
│   ├── fetch-tools.ps1          同上，PowerShell 版
│   └── gen-keystore.ps1         生成本地签名密钥
├── docs/
│   ├── BUILD.md                 手工构建步骤
│   ├── HOOKS.md                 挂钩点和注入原理
│   ├── WORDLISTS.md             词库格式与数据来源
│   └── PLATFORM.md              构建平台相关说明
├── assets/                      放 dict_*.txt 词库
└── tools/                       放 apktool、ecj、r8、android.jar
```

`assets/` 和 `tools/` 里的东西都不入库，仓库里只有源码和脚本。

## 快速开始

需要 Windows、PowerShell 5.1 以上、Python 3.8 以上、JDK 11 以上。建议用完整 JDK，
不过脚本没用到 `jar` 命令，只有 JRE 也能跑。

```powershell
# 1. 准备工具链，会下载到 tools/
python .\scripts\fetch-tools.py

# 2. 生成签名密钥，放在 .keys/
.\scripts\gen-keystore.ps1

# 3. 准备词库，放一个或多个 dict_*.txt 到 assets/，这步可以跳过
python .\scripts\build_wordlists.py --zh-dir <chinese-xinhua>\data --out .\assets
python .\scripts\build_wordlists.py --ecdict <...>\stardict-ecdict-2.4.2.idx --out .\assets

# 4. 构建，-BaseApk 指向自备的原版 APK
.\build.ps1 -BaseApk "D:\path\to\stock.apk"

# 5. 自检
python .\scripts\verify_apk.py --apk .\build\out\merged-aligned-signed.apk --expect-version 909005
```

成品在 `build/out/`。签名和原版不同，装之前先把设备上的原版卸掉，或者自己改一个
`applicationId` 让两个共存。

没有词库也能构建，只是卡片会走百科标签，独立词典界面显示 0 条。词库格式和生成细节
见 [docs/WORDLISTS.md](docs/WORDLISTS.md)。

如果 PowerShell 不让跑脚本，报「在此系统上禁止运行脚本」，改成这样：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -BaseApk "D:\path\to\stock.apk"
```

## 原理

原版有 4 条路径会去启动外部词典。`apply_mod.py` 在这 4 个方法的开头插入一段 smali：

```smali
invoke-static {p0, p1}, Lcom/flyersoft/moonreader/builtindict/DictPopup;->hook(Landroid/content/Context;Ljava/lang/String;)Z
move-result v3
if-eqz v3, :mrdict_orig
return-void

:mrdict_orig
```

`DictPopup.hook()` 返回 true，说明取到词了、卡片已经弹出来，直接返回，原逻辑不执行；
返回 false 说明没取到词，接着走原流程。`.locals` 只加 1，改动尽量小。细节见
[docs/HOOKS.md](docs/HOOKS.md)。

mod 的 Java 代码编译成独立的 `classes8.dex`，不混进原有的 `smali_classes*`。这样
`resources.arsc` 能保持未压缩，原 APK 的其它部分一点不动，方便逐项 diff 验证。

## 许可

MIT，见 [LICENSE](LICENSE)。许可证只覆盖 `java/`、`scripts/`、`docs/` 里的原创代码，
不覆盖 Moon+ Reader 以及其它第三方组件，后者见 [THIRD-PARTY.md](THIRD-PARTY.md)。
原版 App 的名称、包名以及其中的代码和资源都归它的作者所有，这个仓库里没有。
