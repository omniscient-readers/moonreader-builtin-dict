# 手工构建步骤

`build.ps1` 做的就是这些事。想一步步来，或者想在别的系统上做，可以照下面走。
下面用 `$W` 表示任意工作目录，`$APK` 表示自备的原版 APK，命令都假设当前在仓库根目录。

## 0. 环境

- JDK 11 以上，构建时用的是 17
- Python 3.8 以上
- `tools/` 里放好 `apktool.jar`、`ecj.jar`、`r8.jar`、`uber-apk-signer.jar`、`android.jar`，
  跑 `..\scripts\fetch-tools.py` 或 `..\scripts\fetch-tools.ps1` 会自动下载

## 1. 反编译

```powershell
java -jar tools\apktool.jar d -f -o "$W\base" "$APK"
```

出来是 apktool 的工程树，`AndroidManifest.xml` 已经是文本，还有 `smali*/`、`res/`、
`assets/`、`apktool.yml`。顺手看一眼版本号，后面要用：

```powershell
Select-String -Path "$W\base\apktool.yml" -Pattern 'versionCode|versionName'
```

## 2. 打补丁

```powershell
python scripts\apply_mod.py --tree "$W\base" `
    --dict-src .\assets `
    --version-code 909005 --version-name 9.9-mrdict4
```

它做四件事，都是幂等的，重复跑不会重复插桩：

1. 把 `--dict-src` 下的 `dict_*.txt` 复制到 `assets/mrdict/`
2. 在 `AndroidManifest.xml` 里注册 `builtindict.DictActivity`，带 SEARCH 和
   PROCESS_TEXT 两个 intent-filter，不加启动图标
3. 在 `res/values/strings.xml` 里加 `mrdict_label`
4. 在 4 个查词典入口注入 `DictPopup.hook` 序言，对应的 `.locals` 各加 1

挂钩的 4 个方法：

| 文件 | 方法 |
| --- | --- |
| `smali_classes3/.../ActivityTxt.smali` | `lookupWord(String,ZZ)V` |
| `smali_classes3/.../components/DictPackage.smali` | `lookupFromSavedDictIntent(Context,String,Z)V` |
| 同上 | `searchProcessText(Context,I,String)V` |
| 同上 | `searchProcessText(Context,DictItem,String)V` |

如果你的 APK 版本不一样，类路径或方法签名可能对不上，脚本会报错并指出是哪一条。
改 `scripts/apply_mod.py` 顶部的 `SMALI_MAIN` 和 `HOOKS` 就行。原理见
[HOOKS.md](HOOKS.md)。

## 3. 编译 Java 源码

用 ECJ 对着 `android.jar` 编译，用 javac 也行，但得加 `-bootclasspath`：

```powershell
java -jar tools\ecj.jar -1.8 -nowarn -encoding UTF-8 `
    -d "$W\classes" `
    -classpath "tools\android.jar;$W\classes;java\stubs" `
    (Get-ChildItem java -Recurse -Filter *.java | ForEach-Object FullName)
```

`java/stubs/` 里只有一个桩类 `com.flyersoft.tools.A`，用来对齐原 App 里那个类的签名
（`getContext()` 和 `xml_files_folder`）。运行时用的是原 App 的真实类。

## 4. 转成 dex

```powershell
java -cp tools\r8.jar com.android.tools.r8.D8 `
    --min-api 21 --release `
    --lib tools\android.jar `
    --output "$W\dex" `
    "$W\classes\com"
```

产出 `$W\dex\classes.dex`。`--release` 去掉调试信息，体积小一些，调试阶段可以去掉它
保留行号。`--min-api` 要和原 App 的 `minSdkVersion` 一致或更低。

## 5. 重新打包

```powershell
java -jar tools\apktool.jar b "$W\base" -o "$W\rebuilt.apk"
```

## 6. 塞入 dex

```powershell
python scripts\inject_dex.py --apk "$W\rebuilt.apk" --dex "$W\dex\classes.dex" --out "$W\merged.apk"
```

这一步按条目重写整个 zip，目的是保住 `resources.arsc` 的 STORED（不压缩）属性。
Android 11 以上要求它可 mmap，被压缩过会装不上。新 dex 命名为 `classes8.dex`。

## 7. 对齐和签名

```powershell
.\scripts\gen-keystore.ps1 -OutDir "$W\keys"
$pass = Get-Content "$W\keys\keystore.pass" -Raw
java -jar tools\uber-apk-signer.jar -a "$W\merged.apk" -o "$W\out" `
    --ks "$W\keys\mrdict.keystore" --ksAlias mrdict `
    --ksPass $pass --ksKeyPass $pass
```

`-o` 和 `--overwrite` 不能同时用，前者输出到新目录，后者原地覆盖，两个都传会直接打印
帮助然后退出。

等价的原生命令是 `zipalign -p -f 4` 加 `apksigner sign`，见 [PLATFORM.md](PLATFORM.md)。

## 8. 自检

```powershell
python scripts\verify_apk.py --apk "$W\out\merged-aligned-signed.apk" --expect-version 909005
```

它会检查词库资源在不在、mod 类有没有进 dex、manifest 里 `DictActivity` 和两个
intent-filter 对不对、`resources.arsc` 有没有被压缩、以及签名摘要。

也可以直接看一眼编译后的 manifest：

```powershell
python scripts\dump_manifest.py --apk "$W\out\merged-aligned-signed.apk" --filter builtindict
```

## 9. 安装

```powershell
adb install -r "$W\out\merged-aligned-signed.apk"
```

## 相关

- [HOOKS.md](HOOKS.md)：挂钩点和 smali 注入的原理
- [WORDLISTS.md](WORDLISTS.md)：词库格式、加载顺序、生成方式
- [PLATFORM.md](PLATFORM.md)：平台限制、对齐签名、常见失败
