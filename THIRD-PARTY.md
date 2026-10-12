# 第三方组件

这个仓库里只有为本 mod 写的代码，下面列的东西都是构建时引用或者属于各自作者，仓库里
没有它们的副本。

## 被修改的 App

Moon+ Reader（静读天下）的代码、资源、商标都归它的作者所有。这个项目没有包含、镜像
或再分发其中任何部分。要构建必须自备一份原版 APK，相关许可和当地法律的合规问题由你
自己负责。

补丁脚本里必然会写到目标 App 的类名和方法名（`DictPackage`、`ActivityTxt.lookupWord`、
`MrAd`，以及 smali 的目录结构等），挂钩只能靠名字定位目标，这些引用仅此而已。

## 构建工具链

由 `scripts/fetch-tools.py` 下载到 `tools/`，不入库。

| 工具 | 许可 | 地址 |
| --- | --- | --- |
| Apktool | Apache-2.0 | https://github.com/iBotPeaches/Apktool |
| smali / baksmali（随 Apktool） | BSD-3-Clause | https://github.com/google/smali |
| Eclipse Compiler for Java | EPL-2.0 | https://github.com/eclipse-jdt/eclipse.jdt.core |
| R8 / D8 | BSD-3-Clause | https://r8.googlesource.com/r8 |
| uber-apk-signer | Apache-2.0 | https://github.com/patrickfav/uber-apk-signer |
| android.jar（Sable/android-platforms） | Apache-2.0 | https://github.com/Sable/android-platforms |

`android.jar` 只是 Android 框架的编译期桩，真正的实现在设备上。

## 词库数据

本地生成，不入库。

| 来源 | 许可 | 用途 |
| --- | --- | --- |
| [ECDICT](https://github.com/skywind3000/ECDICT) | MIT | `dict_big.txt`，英汉词条 |
| [chinese-xinhua](https://github.com/pwxcoo/chinese-xinhua) | MIT | `dict_zh.txt`，汉字、词语、成语 |
| COCA 词频表、Google 20k | 见上游条款 | 排序用，让常用词排前面 |

`scripts/build_wordlists.py` 只负责在你自己的机器上从这些数据重建词库文件。再分发派生
出来的词库之前先看一遍各家的许可，MIT 那两个要求保留版权声明，词频表有它自己的条款。
