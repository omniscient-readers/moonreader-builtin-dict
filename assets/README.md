# assets/

`build.ps1` 会把这里的每个 `*.txt` 复制到 APK 的 `assets/mrdict/`。

内容不入库，用 [../scripts/build_wordlists.py](../scripts/build_wordlists.py) 生成：

```powershell
python ..\scripts\build_wordlists.py `
    --zh-dir  <chinese-xinhua>\data `
    --ecdict  <...>\stardict-ecdict-2.4.2.idx `
    --freq    <...>\coca60k.txt <...>\freq20k.txt `
    --out     .
```

| 文件 | 内容 | 大概大小 |
| --- | --- | --- |
| `dict_zh.txt` | 汉字、成语、词语，来自 chinese-xinhua | 约 20 MB |
| `dict_cn.txt` | 精选英汉词条和短语 | 不到 1 MB |
| `dict_big.txt` | ECDICT 按词频裁过的子集 | 约 4 MB |

格式和加载顺序见 [../docs/WORDLISTS.md](../docs/WORDLISTS.md)。

三个文件都可以不要。一个都不放也能构建和运行，只是卡片会走百科标签，独立词典界面显示
0 条。也可以完全不重新打包，直接在 App 的外部文件目录放一个 `mrdict_user.txt` 来补词。
