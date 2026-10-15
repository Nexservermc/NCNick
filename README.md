# NCNick · 中文昵称插件

> 让玩家拥有一个**全服唯一的中文昵称**，在头顶、TAB 列表、聊天栏三处优雅显示。
> 颜色通过图形界面所见即所得地选择，支持**纯色**与**双色渐变**。

[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)](https://github.com/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8%20--%201.21.11-green.svg)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://adoptium.net/)
[![Jar Size](https://img.shields.io/badge/jar-0.47%20MB-lightgrey.svg)](#)
[![Platform](https://img.shields.io/badge/platform-Spigot%20%7C%20Paper-yellow.svg)](https://papermc.io/)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](../LICENSE)

---

## 效果预览

```
头顶 / TAB   :  Steve#小明
聊天栏       :  <#小明> 你好，世界！
```

玩家自己决定昵称文字，然后用 GUI 挑选颜色：

```
┌──────────────────────────────────┐
│         昵称颜色设置              │
│                                  │
│    [纯色]        [渐  变]         │
│   #FF5555      红 → 蓝            │
│                                  │
│   价格：5000 金币 / 500 点券        │
│        15000 金币 / 1500 点券      │
└──────────────────────────────────┘
```

> 📷 截图占位：建议在此处补充三张图 —— ① 主界面 ② 颜色选择界面 ③ 游戏内头顶效果。

---

## 为什么选择 NCNick

| 对比项 | 常见做法 | NCNick |
|---|---|---|
| 昵称字符 | 手动配置正则，容易漏 | **Unicode 区间编译成二分查找表**，默认简繁中文，按**码点**处理（扩展 B 区汉字不会误判） |
| 设置颜色 | 让玩家手打 `&a` 颜色代码 | **三层 GUI**：选类型 → 选颜色 → 预览购买，玩家完全不接触代码 |
| 昵称重复 | 靠字符串比对 | **Unicode 归一化**（NFKC + 剥离零宽/组合字符）+ 数据库唯一索引，`§a小明` 与 `小明` 视为重复 |
| 插件体积 | 把 JDBC 驱动打进 jar（约 18 MB） | **按需下载驱动**，jar 仅 **0.47 MB** |
| 驱动下载 | 只给官方源，国内超时 | **三源降级**：阿里云 → 腾讯云 → Maven 官方 |
| 跨服同步 | 需要额外插件 | 内置 MySQL 轮询同步 |
| 语言 | 只有中文 | **简中 / 繁中 / 英文** 三套独立语言文件 |

---

## 主要特性

### 🈶 中文昵称，开箱即用
默认字符集就是**简体 + 繁体中文**，无需任何配置即可使用。
需要允许英文/数字/日文/韩文/符号？在配置里逐个开关即可。

### 🎨 图形化颜色系统
- **第一层**：选择「纯色」或「渐变」
- **第二层**：纯色选 1 种颜色；渐变先选起始色，再选结束色
- **第三层**：预览实际效果 → 确认购买

提供 Minecraft 全部 **16 种染料颜色**，支持 1.16+ 真彩色十六进制。

### 🔒 昵称全服唯一
数据库层面建立唯一索引兜底，同时做 Unicode 归一化，拦截这些"视觉相同"的绕过手法：

| 绕过手法 | 是否拦截 |
|---|---|
| 颜色代码不同（`§a小明` vs `§b小明`） | ✅ 视为重复 |
| 大小写不同（`ABC` vs `abc`） | ✅ 视为重复 |
| 全角/半角（`ＡＢＣ` vs `ABC`） | ✅ 视为重复 |
| 零宽字符（`小\u200B明` vs `小明`） | ✅ 视为重复 |
| 组合字符（`café` vs `cafe`+U+0301） | ✅ 视为重复 |
| 双向控制符 / BOM / 软连字符 | ✅ 视为重复 |

### 💰 双支付，玩家自选
金币（Vault / XConomy）与点券（PlayerPoints）**并列展示，由玩家选择**。
配置里也可以限定只用其中一种。

### 🛠 完整的 OP 管理
- 命令：`set` / `color` / `resetcolor` / `clear` / `info` / `list` / `driver` / `debug` / `reload`
- **独立管理 GUI**：玩家列表（分页 + 搜索）→ 玩家详情 → 二次确认删除
- GUI 里支持修改昵称、设置颜色、重置颜色、删除数据

### 🧪 实验性：直接接管原版显示
不想和其他显示类插件"各显示一套"？实验性功能**在原版显示上做替换**：

| 开关 | 接管位置 | 实现方式 |
|---|---|---|
| `tab` | 原版 TAB 列表 | `setPlayerListName` |
| `nametag` | 原版头顶悬浮 ID | 记分板队伍前缀/后缀 |
| `chat` | 原版聊天栏 | 聊天事件格式替换（1.19+ Paper 自动适配 `AsyncChatEvent`） |

三个开关**独立控制**，每个还能单独选择显示模式（①全自定义 / ②原ID+自定义），
并有**总开关**统一控制。默认全部关闭，无需任何额外依赖。

### 📦 插件只有 0.47 MB
数据库驱动**不打包**，首次使用时自动从国内镜像下载到 `plugins/NCNick/lib/`。
不想联网？手动放进驱动即可，插件会识别。

---

## 快速开始

### 1. 安装

把 `NCNick-2.0.0.jar` 放进服务端的 `plugins/` 目录，重启服务器。

首次启动会自动生成：
```
plugins/NCNick/
├── config.yml      主配置
├── lang/           语言文件（简中 / 繁中 / 英文）
└── data.db         SQLite 数据库（单服模式）
```

### 2. 可选依赖

按需安装，不装也能正常使用基础功能：

| 插件 | 用途 |
|---|---|
| **Vault** + 经济插件（如 XConomy） | 金币支付 |
| **PlayerPoints** | 点券支付 |
| **PlaceholderAPI** | 注册 `%ncnick_*%` 变量 |

### 3. 玩家使用

```
/nc            → 打开昵称主界面
```

1. 点击「设置昵称」→ 在聊天栏输入中文昵称
2. 通过校验后进入**支付方式选择**界面（金币 / 点券 / 权限免费）
3. 设置成功后点击「昵称颜色」→ 选颜色 → 预览 → 购买

> 💡 玩家**不需要**输入任何颜色代码，颜色全部通过界面选择。

### 4. 管理员使用

```
/nca           → 打开管理界面
/nca reload    → 重载配置与语言文件
```

---

## 命令一览

### 玩家命令 `/nc`

| 命令 | 说明 | 权限 |
|---|---|---|
| `/nc` | 打开昵称主界面 | `ncnick.use` |
| `/nc set <昵称>` | 直接设置昵称 | `ncnick.use` |
| `/nc color` | 打开颜色设置界面 | `ncnick.use` |
| `/nc remove` | 清除自己的昵称 | `ncnick.remove` |
| `/nc info` | 查看自己的昵称信息 | `ncnick.use` |
| `/nc help` | 查看帮助 | — |

别名：`/ncnick`、`/nickname`、`/nick`

### 管理员命令 `/nca`

| 命令 | 说明 |
|---|---|
| `/nca` | 打开玩家管理界面 |
| `/nca set <玩家> <昵称>` | 设置指定玩家昵称（**支持颜色代码**） |
| `/nca color <玩家> <颜色>` | 设置昵称颜色（支持 `&a`、`&#FF5555`、中文色名） |
| `/nca resetcolor <玩家>` | 重置昵称颜色 |
| `/nca clear <玩家>` | 清除指定玩家昵称 |
| `/nca info <玩家>` | 查看玩家昵称详情 |
| `/nca list [页码]` | 列出所有已设置昵称的玩家 |
| `/nca driver [status\|download]` | 查看 / 强制下载数据库驱动 |
| `/nca debug [on\|off]` | 切换调试模式 |
| `/nca reload` | 重载配置与语言文件 |

别名：`/ncnickadmin`

> ⚠️ **权限边界**：玩家**不能**在昵称里写颜色代码（必须走颜色 GUI）；
> 管理员用 `/nca set`、`/nca color` 时**可以**直接用颜色代码。

---

## 权限节点

| 权限 | 默认 | 说明 |
|---|---|---|
| `ncnick.use` | 所有玩家 | 使用昵称功能 |
| `ncnick.remove` | 所有玩家 | 清除自己的昵称 |
| `ncnick.color` | 所有玩家 | 使用自定义昵称颜色 |
| `ncnick.admin` | 仅 OP | 管理界面与全部管理命令 |

`ncnick.admin` 默认包含其余三个权限。

---

## PlaceholderAPI 变量

| 变量 | 说明 |
|---|---|
| `%ncnick_nickname%` | 昵称（含颜色） |
| `%ncnick_raw%` | 昵称（纯文本，无颜色） |
| `%ncnick_name%` | 玩家真实 ID |
| `%ncnick_display%` | 完整显示名（如 `Steve#小明`） |
| `%ncnick_color%` | 昵称颜色的十六进制值 |
| `%ncnick_hasnick%` | 是否已设置昵称（`true` / `false`） |
| `%ncnick_prefix%` | 配置的自定义占位符（默认 `#`） |

---

## 配置文件结构

`config.yml` 分为十个区块，每个区块都有详细注释：

```
config-version        配置版本
language              语言选择（zh_CN / zh_TW / en_US）
validation            昵称字符规则（字符集、长度、禁止字符、禁止词）
uniqueness            全服唯一性（是否忽略颜色/大小写/空格）
modes                 权限模式与购买模式（价格、支付方式、加价规则）
display               显示格式与占位符
chat                  聊天栏名字包裹与加粗
experimental          实验性功能（三个接管开关 + 总开关）
storage               存储（SQLite / MySQL + 跨服同步）
driver                驱动下载（三个源、超时、重试、坐标）
performance           权限检查间隔、缓存刷新
debug                 调试开关（默认关闭，含四个细分项）
gui                   界面标题与填充
```

---

## 数据库与驱动

### 存储

| 模式 | 适用场景 | 说明 |
|---|---|---|
| `sqlite` | 单服（**默认**） | 数据存于 `plugins/NCNick/data.db`，开启 WAL 提升并发 |
| `mysql` | 多服 | 支持 **跨服同步**，每 5 秒轮询增量表 |

### 驱动按需下载

插件**不内置**数据库驱动，首次使用时自动下载到：

```
plugins/NCNick/lib/mysql-connector-j-8.0.33.jar
plugins/NCNick/lib/sqlite-jdbc-3.45.3.0.jar
```

下载源（按顺序尝试，失败自动降级）：

1. **阿里云镜像**
2. **腾讯云镜像**
3. **Maven 官方中央仓库**

**离线部署**：手动下载驱动放进 `plugins/NCNick/lib/` 即可，插件会自动识别。
出于安全考虑，插件**只加载文件名匹配已知驱动包**的文件。

---

## 常见问题

<details>
<summary><b>中文昵称设置后不显示？</b></summary>

检查是否被其他 TAB / 聊天插件覆盖。可尝试开启 `experimental` 中对应的接管开关。
</details>

<details>
<summary><b>提示"昵称包含不允许的字符"？</b></summary>

默认只允许中文。如需允许英文/数字，在 `config.yml` 中开启：

```yaml
validation:
  charset:
    latin:
      enabled: true
    digits:
      enabled: true
```
</details>

<details>
<summary><b>MySQL 连接失败？</b></summary>

执行 `/nca driver status` 查看驱动状态。若驱动缺失且服务器无法联网，
请手动下载驱动放入 `plugins/NCNick/lib/`，然后 `/nca reload`。
</details>

<details>
<summary><b>想让昵称更长？</b></summary>

修改 `validation.max-length`。注意超过 `extra-fee.threshold` 后会按字数加价。
</details>

<details>
<summary><b>1.19+ 聊天栏昵称不生效？</b></summary>

插件内置了 `AsyncChatEvent` 适配，会自动检测并注册。
若仍无效，请确认服务端为 **Paper** 或其衍生核心（Spigot 在 1.19+ 的聊天事件体系不同）。
</details>

<details>
<summary><b>头顶为什么不能只显示昵称？</b></summary>

这是 **Minecraft 平台限制**，不是本插件的缺陷：

原版头顶名字 = `队伍前缀` + **`玩家实体名`** + `队伍后缀`，
中间那个「玩家实体名」无法通过 Bukkit 记分板 API 移除。

因此：
- `mode: name-nickname` → 显示 `Steve#小明` ✅
- `mode: full` → 显示 `Steve小明`（原 ID 仍在）

若需要真正只显示昵称，请改用 `experimental.tab`（TAB 位置无此限制）。
插件启动时会就此给出一次提示。
</details>

---

## 兼容性

| 项目 | 支持范围 |
|---|---|
| Minecraft | **1.8 - 1.21.11** |
| 服务端 | Spigot / Paper 及其衍生核心 |
| Java | 8 及以上 |
| 编译目标 | Java 8（`source/target 1.8`） |

> 插件**刻意不声明 `api-version`**，因为声明 `1.13` 会导致 1.8-1.12 服务端拒绝加载。
> 所有 1.9+ 才有的 API（如十六进制颜色、异步聊天事件）都通过反射探测与降级处理。

---

## 从 1.x 升级

1. **备份** `plugins/NCNick/` 目录
2. 替换 jar
3. 删除旧的 `config.yml`（2.0.0 的配置结构有较大调整，会自动重建）
4. 启动服务器

旧数据（SQLite 的 `data.db` 或 MySQL 表）会被自动兼容 ——
插件启动时会为旧表补充缺失的列。

---

## 构建

需要 JDK 8+ 与 Maven：

```bash
mvn clean package
```

产物：`target/NCNick-2.0.0.jar`

---

## 许可

本项目采用 **GNU General Public License v3.0**（GPL-3.0）。

```
Copyright (C) 2025 xiaotian_xt, Nice_Leo_

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

完整条款见仓库根目录的 [`LICENSE`](../LICENSE) 文件。

> **使用 GPL-3.0 意味着**：任何人可以自由使用、修改、分发本插件，
> 但**二次开发的衍生作品也必须以 GPL-3.0 开源**，且需保留版权声明。
> 如果你希望允许闭源二次开发，需要考虑其他许可证。
