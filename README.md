# KlinRead for Watch

为安卓手表优化的 KlinRead。数据层与手机版同步，UI 针对小屏和圆屏调整。

---

## 为什么卡

原版在手表上卡，原因不在 UI，而在**内存**。实测你的表：

| 项 | 值 |
|---|---|
| 型号 | OPPO Watch（OWW212） |
| 内存 | **860 MB**（可用约 360 MB） |
| CPU | **armeabi-v7a**（32 位 ARM） |
| 屏幕 | 372×430，Android 11 |

原版的两个致命问题：

1. **整本书一次性读进内存** —— 220 万字 ≈ 4.4 MB 字符串
2. **EPUB 全部条目解压进内存** —— 157 个文档全 `readBytes()`，约 15-20 MB 常驻

在可用内存只有几百 MB 的手表上，这会导致**持续 GC**，导入和翻页都卡。

---

## 优化结果（真机实测）

| 指标 | 原版 | 手表版 |
|---|---|---|
| **APK 体积** | 19.42 MB | **1.81 MB** |
| **冷启动** | 2173 ms | **373 ms** |
| **`.dex mmap`** | 60,954 KB | **2,792 KB** |
| **总内存 PSS** | 101,500 KB | **29,153 KB** |

`.dex mmap` 从 60 MB 降到 2.8 MB 是关键 —— 那是 Compose 未使用代码的体积，也是卡顿的主因。

> 上表是 1.0.0 加入封面提取、书架分类、读完标记之后的实测值。
> 新增功能使 `.dex mmap` 从 2,566 KB 略增到 2,792 KB（+226 KB），
> 但 PSS 反而下降，启动也更快。

---

## 做了什么

### 1. EPUB 流式解析（最大改动）

原来：把 ZIP 里每个条目 `readBytes()` 存进 Map，全部驻留内存。

现在：**读两遍压缩包**
- 第一遍只读 `container.xml` 和 OPF（都很小），拿到阅读顺序和章节标题
- 第二遍逐个流式读取正文文档，转成文本后立刻丢弃字节

峰值内存从「整本书」降到「单章」。

### 2. 章节正文缓存

原来 `body` 是 `get() = book.text.substring(...)` —— 属性读取在 composition 里，**每次重组都复制一遍整章**（可能几万字）。翻页时每帧都在分配。

现在用 `by lazy` 缓存。

### 3. 章节切分优化

原来对**每一行**都调 `substring` 就为了判断是不是标题 —— 220 万字的书约 10 万个临时字符串。

现在先做廉价的字符判断（标题一定以「第/楔/序/引…」开头），只对可能的行分配字符串。

### 4. 导入不再重复读文件

原来解析完还要重新打开文件嗅探格式。现在格式直接取文件名扩展名。

### 5. 导入进度提示

长时间导入时按钮变成进度条显示百分比 —— 之前看起来像卡死。

### 6. R8 混淆

release 构建开启 `minifyEnabled` + `shrinkResources`，配合针对 Room / Kotlin / 枚举的 keep 规则。

### 7. 运行时设备检测

`DeviceProfile` 检测内存和屏幕形态：
- 低内存设备自动减少每页段落数（手表 8 段，手机 16 段）
- 自动识别圆屏 / 方屏

---

## 构建

```powershell
$env:JAVA_HOME = "C:\Software\AndroidStudio\jbr"
$env:ANDROID_HOME = "C:\Software\ASDK"
cd "C:\Users\秋梧栖\Desktop\KlinReadForWatch"
.\gradlew.bat assembleRelease
```

产物：

```
app\build\outputs\apk\release\
    app-armeabi-v7a-release.apk     1.81 MB   ← 你的手表用这个
    app-arm64-v8a-release.apk       1.82 MB
    app-universal-release.apk       1.91 MB
```

### 安装到手表

```powershell
$adb = "C:\Software\ASDK\platform-tools\adb.exe"
& $adb -s <手表序列号> install -r "app\build\outputs\apk\release\app-armeabi-v7a-release.apk"
```

**注意用 release 而不是 debug** —— debug 版大 10 倍，装上去依然会卡。

### 签名

release 用 `keystore/watch-release.jks` 签名（密码 `kkl1nread`）。
若无此文件，构建输出未签名 APK，无法安装。

重新生成：

```powershell
& "C:\Software\AndroidStudio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore keystore\watch-release.jks -alias watch `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass kkl1nread -keypass kkl1nread `
  -dname "CN=KlinRead Watch, O=Klin, C=CN"
```

---

## 测试

31 个单元测试，覆盖解析逻辑与本次同步的修复：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `EpubParserTest` | 9 | 含「40 个文档的大书」场景，验证两遍扫描不漏读 |
| `CoverExtractorTest` | 8 | 封面定位规则（EPUB 3 属性 / EPUB 2 meta）、路径解析、3 MB 上限 |
| `MusicFilterTest` | 8 | 音乐搜索：空查询、大小写、去空格、无匹配 |
| `TextDecoderTest` | 6 | UTF-8 / GB18030 / BOM |

纯 JVM 逻辑，不需要模拟器。

在本机运行（绕开 Gradle 无法 fork worker 的环境限制）：

```powershell
pwsh tools\run-tests.ps1
```

### 关于 `CoverExtractorTest` 的两处修正

写测试时先按直觉写了期望值，跑出来是红的，核对实现后确认**是测试期望写错了、代码是对的**：

- `resolvePath("OEBPS/text", "../images/cover.jpg")` 的结果是 `OEBPS/images/cover.jpg`，
  不是 `images/cover.jpg` —— `..` 抵消的是 OPF 自己的子目录，符合文件系统语义。
- `baseDir` 为空时函数会**提前返回 href**，不做 `..` 折叠，这是唯一不走折叠逻辑的路径。

两条都已按实际行为固定下来。

---

## 本次同步（1.0.0）

从手机版同步过来的内容：

| 项目 | 说明 |
|---|---|
| **包名** | `com.kkl1n.read` → `com.klin.read`（与手机版一致） |
| **版本** | 1.0.0，设置页改用 `BuildConfig.VERSION_NAME` |
| **图标** | 新的粉色自适应图标（各密度 + 512px） |
| **收款码 / 头像** | 换成新图；**按手表尺寸大幅缩小**，见下方说明 |
| **书架分类** | 全部 / 未读 / 在读 / 读完 / 未分类 + 自定义分类，每个带实时数量 |
| **封面** | 导入时提取并缓存缩略图；无封面的格式用生成的占位图 |
| **读完标记** | 读到最后一章最后一段自动标记；封面右上角「完」角标 |
| **音乐修复** | 修复「搜索无效」与「歌曲一直显示已选中」两个 bug |
| **文案** | 删去 BETA 测试版字样与软件内说明文字；亮度默认 100% |

### 封面提取：没有照搬手机版

手机版的 `CoverExtractor` 会把 EPUB 的**每个 ZIP 条目读进内存**再找封面。
这恰恰是本项目要消灭的问题——157 个文档的 EPUB 会膨胀到 15-20 MB。

手表版改为**复用流式两遍扫描**：

1. 第一遍只保留 `container.xml` 和 OPF（都很小），拿到封面的 href；
2. 第二遍只截取那一个条目；
3. 单个条目**硬上限 3 MB**，超过就放弃并使用占位封面；
4. 解码时用 `inSampleSize` 降采样，4000px 的封面不会变成 4000px 的位图；
5. 重新编码成 JPEG 写进缓存目录，其余字节随即释放。

峰值内存是「一张被限制大小的图」，而不是整本书。

### 图片资源按手表尺寸重做

手机版的收款码是 1024px（65 KB）、头像是 176px。手表上分别只显示到
约 200dp 和 32dp，照搬纯属浪费，而这个项目的核心指标就是体积：

| 资源 | 手机版 | 手表版 |
|---|---|---|
| 收款码 | 1024px / 65 KB | 512px / 64 KB |
| 头像（三档合计） | 176px / 77 KB | 32/48/64px / 12 KB |

结果：**APK 反而比同步前的 1.99 MB 更小（1.81 MB）**。

---

## 未验证的部分

**UI 在手表上的实际观感我看不到。** 我只能保证编译通过、进程不崩、性能数据正确、
界面文字能被无障碍服务读到。

已经实测确认的：冷启动、PSS、dex mmap、书架/音乐/设置/作者四个页面的渲染、无崩溃日志。

还需要你在表上亲自确认：
- 圆屏上内容有没有被裁切
- 分类 chip 横向滚动是否顺手
- **长按书籍弹出的操作面板**在小屏上是否好按（这是本次新增的交互）
- 阅读时翻页是否流畅

### 已知限制

- **数据库 v1 → v2 不迁移**：升级会重建数据库，书架需要重新导入。
- **封面提取的格式覆盖有限**：MOBI 只按「第一张图片」定位；TXT / HTML / UMD
  本来就没有封面，一律使用生成的占位图。
- **没有 CI**：这个工程目前只在本机构建，没有自动化测试流水线。
  （手机版有 GitHub Actions，但两边的测试集不同，不能直接共用。）

