# 挂钩点和注入原理

## 为什么要挂钩

阅读器启动词典的行为是写死的：选中文字，点「词典」，它拼一个 Intent 交给系统或外部
词典 App。要换成自己的卡片，最省事的做法是在它决定跳转之前插一脚。

改 4 个入口而不是 1 个，是因为有 4 条不同的路径都能触发查词：正文长按、PROCESS_TEXT、
保存的词典意图、划词搜索。只挂一条，别的路径就漏了。

## 注入的 smali

在目标方法体第一条真实指令之前插入：

```smali
.locals N+1                     # 原来是 N，加 1 给新寄存器
    invoke-static {p0, p1}, Lcom/flyersoft/moonreader/builtindict/DictPopup;->hook(Landroid/content/Context;Ljava/lang/String;)Z
    move-result vN
    if-eqz vN, :mrdict_orig
    return-void

    :mrdict_orig
    ... 原方法体 ...
```

约定是这样的：`hook()` 返回 true 表示词已经取到、卡片也弹出来了，直接 `return-void`，
原逻辑不执行；返回 false 表示没取到词（空串、上下文不是 Activity、或者内部抛了异常），
落到 `:mrdict_orig` 继续原来的流程。所以它是叠加而不是替换，出问题也不会把阅读器弄坏。

寄存器是这么选的。`{p0, p1}` 或 `{p0, p2}` 直接复用参数寄存器，不额外占参数位；新寄存器
取 `vN`，也就是 `v<原来的 .locals>`，同时把 `.locals` 从 N 改成 N+1。这是 smali 里最不
容易出错的做法，因为 `p` 寄存器在方法内只是 `v(registers - paramCount + i)` 的别名，
动 `.locals` 不会改变参数寄存器的含义。返回值用 `move-result` 接，再用 `if-eqz` 判断。

`DictPackage$DictItem` 那个重载的参数是 `(Context, DictItem, String)`，词在 `p2`，所以
注册表写 `{p0, p2}`。传错寄存器的话拿到的会是 `DictItem` 对象，`cleanWord()` 得到空串
然后返回 false，不会崩但功能失效，所以每个入口的寄存器都要对着方法描述符核一遍。

## 方法查找和幂等

`apply_mod.py` 里是 `hook_method()` 干的活：

1. 按完整方法声明定位（`src.find(decl)`），不按方法名找，同名重载太多
2. 方法体开头 1200 字符内已经出现 `DictPopup` 就认为插过桩了，直接跳过
3. 在 `.end method` 之前取方法体，正则找 `.locals`，加 1
4. 找第一条非空、不以 `.` 开头也不以 `#` 开头的行作为插入点，这样能自动跳过
   `.locals`、`.param`、`.annotation`、`.prologue` 这些伪指令

## 另外两处改动

`AndroidManifest.xml` 里加了 `<activity ... DictActivity>` 和 SEARCH、PROCESS_TEXT 两个
intent-filter，这样其它 App 才能调起查词界面。`android:exported="true"` 是 Android 12
以上的硬性要求，必须写。PROCESS_TEXT 还需要
`<data android:mimeType="text/plain"/>`，否则系统不会把选中文字派发过来。

`res/values/strings.xml` 里加了 `mrdict_label`，因为 Activity 的 `android:label` 要引用
一个资源。

## EsjActivity 的挂钩

`EsjActivity`（可选功能）走的是另一条路，它不替换查词，而是在线书库列表被点击时把事件
拦下来。挂的是 `ActivityMain$LibraryAdapter$2.onClick`，插入点前面要多两条 `iget-object`
把 Activity 取出来：

```smali
iget-object vN, p0, Lcom/flyersoft/moonreader/ActivityMain$LibraryAdapter$2;->this$1:...LibraryAdapter
iget-object vN, vN, Lcom/flyersoft/moonreader/ActivityMain$LibraryAdapter;->this$0:...ActivityMain
invoke-static {p1, vN}, .../EsjActivity;->hookOnLibraryTap(Landroid/view/View;Landroid/app/Activity;)Z
move-result vN
if-eqz vN, :mresj_orig
return-void

:mresj_orig
```

`hookOnLibraryTap()` 用反射读 `com.flyersoft.opds.N.getLibraries()` 拿当前书库列表，
再按 View 的 tag 索引判断被点的是不是目标站点，是就打开自己的 WebView。这里用反射是
有意的：那几个类的签名在不同版本会变，用反射能把失败限制在 `catch (Throwable)` 里，
不影响阅读器本身。

不需要这个功能就别应用这一步，`EsjActivity.java` 也可以从 `java/` 里删掉，它没有别的
依赖。
