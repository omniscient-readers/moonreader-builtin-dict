# 词库格式与数据来源

## 格式

纯文本 UTF-8，一行一条，词条和释义之间用一个制表符隔开：

```
hello	int. 喂；哈罗  n. 表示问候
成语	中国传统的四字短语
```

`DictEngine.readInto()` 里的规则不复杂：`#` 开头的行是注释，跳过；没有制表符，或者
制表符在行首行尾的，跳过；词条超过 64 字符、释义超过 500 字符的，跳过；词条统一转成
小写，中文不受影响。同一个词条如果在前面加载的文件里已经出现过，后面的不会覆盖它。

## 加载顺序

`DictEngine.load()` 按固定顺序读三份内置的，最后再读一份用户词库：

| 顺序 | 文件 | 来源 | 内容 |
| --- | --- | --- | --- |
| 1 | `assets/mrdict/dict_zh.txt` | chinese-xinhua | 汉字、词语、成语 |
| 2 | `assets/mrdict/dict_cn.txt` | 自带词表 | 精选英汉词条和短语 |
| 3 | `assets/mrdict/dict_big.txt` | ECDICT | 英汉大词库，按词频裁过 |
| 4 | 用户词库 | 由你提供 | 覆盖上面全部 |

三份内置的都不是必需的，缺哪份就少哪部分；一份都没有的时候卡片只走百科标签。

用户词库放在 App 的外部目录：

```
/sdcard/Android/data/<包名>/files/mrdict_user.txt
```

不需要 root，也不用重新打包，改完重开词典界面就生效。它排在最后，所以优先级最高，
适合修正释义或者补专有名词。文件不存在或者读不了都只是静默跳过，不影响内置词库。

三份内置词库任何一份缺失或损坏都不会导致崩溃。`load()` 是逐份 try/catch 的，失败的
记进 `lastError`，词典界面会显示一行提示，然后继续用剩下的。

## 生成

词库数据不入库，一是体积大，二是版权属于上游项目。用
`scripts/build_wordlists.py` 在自己的机器上生成：

```powershell
# dict_zh.txt，需要 chinese-xinhua 的 data/ 目录
python scripts\build_wordlists.py --zh-dir <chinese-xinhua>\data --out .\assets

# dict_big.txt，需要 ECDICT stardict 的 .idx 和 .dict
python scripts\build_wordlists.py --ecdict <...>\stardict-ecdict-2.4.2.idx --out .\assets

# dict_cn.txt，合并自带词表 words_*.py，格式是 DATA = [(词条, 释义), ...]
python scripts\build_wordlists.py --cn-dir <词表目录> --out .\assets
```

三个参数可以任意组合，给哪个就只生成哪个。数据源见
[../THIRD-PARTY.md](../THIRD-PARTY.md)。

### dict_big.txt 是怎么裁的

ECDICT 全量有三百多万条，直接塞进 APK 不现实。脚本做了两级筛选。

先按词频保留：ECDICT 自带频率标记，形如 `(雅托中考高)`，取里面的最小排名，排名不超过
150000 的留下；另外命中外部词频表（COCA 60k、Google 20k）的词直接留下。再按长度裁：
每条释义最多 6 行、220 字符，`的过去式`、`的复数形式` 这类词形变化行丢掉，反正
`DictEngine` 自己会做词形还原，音标行 `*[...]` 保留。

这样出来的文件大概几 MB，覆盖常用词和考试词。冷僻词查不到时还有百科标签兜底。

## 查询算法

`DictEngine.lookup()` 依次尝试三种：

1. 精确匹配，转小写后查 HashMap
2. 英文词形还原，仅在不含 CJK 时。先查不规则变化表，覆盖 `went` 到 `go`、`children`
   这类高频形式；再按后缀剥离，后缀有 `ies / ves / ily / es / ed / ing / est / er /
   ly / s / d`，每个后缀试原干、去重复辅音、加 `e`、加 `y`、加 `f`、加 `fe` 几种还原，
   命中就返回。`studies` 到 `study`、`running` 到 `run`、`knives` 到 `knife` 都靠这个
3. 中文词头收缩，仅在含 CJK 时。从尾部逐字砍，`孩子们` 到 `孩子`、`钢筋混凝土` 到
   `钢筋`，砍到 2 字为止

`DictEngine.suggestions()` 用排好序的 key 数组做二分找前缀，返回「你是不是要找」；
中文查不到时同样会先收缩词头再找前缀。

改完算法建议拿小样本词库手工核对一遍。也可以把 `DictEngine` 的逻辑照抄成 Python 跑
批量回归，当初就是这么验的。
