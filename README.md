# NCNick

> 为 Minecraft 服务器打造的**中文昵称**插件 —— 简繁中文昵称、双占位符、所见即所得颜色 GUI、全服唯一、MySQL 跨服同步。

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-2.0.0-blue">
  <img alt="minecraft" src="https://img.shields.io/badge/Minecraft-1.8%2B-green">
  <img alt="license" src="https://img.shields.io/badge/license-GPLv3-orange">
  <img alt="java" src="https://img.shields.io/badge/Java-8%2B-red">
</p>

---

## ✨ 这是什么

**NCNick** 让玩家拥有一个可以自由设置、带颜色/渐变、且**全服唯一**的中文昵称。
它把昵称应用到**聊天栏、TAB 列表、玩家头顶**三处显示，并提供购买/权限两种获取模式、界面化配置、MySQL 跨服同步与 30 种内置语言。

- 🀄 **中文优先**：内置简繁中文字符集白名单，开箱即用，无需写正则
- 🎨 **所见即所得颜色**：纯色 / 双色渐变，图形界面点选，实时预览
- 🔒 **全服唯一**：Unicode 归一化 + 数据库唯一索引双保险，杜绝"重名/伪装名"
- 🌐 **跨服同步**：MySQL 存储时自动同步，多服玩家昵称一致
- 💰 **灵活的付费**：昵称与颜色**各自独立**选择金币 / 点券 / 两者皆可
- 🧩 **单 jar 兼容 1.8+**：编译锁定 1.8.8 API，其余能力反射适配
- 🗂️ **配置分文件**：8 个功能域独立 yml，热重载，缺失键自动回补
- 🌍 **30 种语言**：内置多语言，可自行扩展

---

## 📦 快速开始

1. 下载 `NCNick-2.0.0.jar`，放入服务端的 `plugins/` 目录
2. 重启服务器（首次启动会自动下载数据库驱动到 `plugins/NCNick/lib/`）
3. 玩家输入 `/nc` 打开昵称界面；管理员输入 `/nca` 打开管理界面
4. 按需修改 `plugins/NCNick/` 下的配置文件，执行 `/nca reload` 生效

> 需要离线部署？把驱动手动放入 `plugins/NCNick/lib/`，并将 `storage.yml` 中 `driver.auto-download` 设为 `false`。

---

## 🖥️ 兼容性

| 项 | 支持范围 |
| --- | --- |
| 服务端 | Spigot / Paper / 衍生核心 |
| 版本 | Minecraft **1.8 ~ 最新**（单 jar） |
| Java | 8+ |
| 软依赖 | Vault、PlayerPoints、PlaceholderAPI、PacketEvents、TAB、ProtocolLib |

> 插件刻意**不声明** `api-version`，以便 1.8 - 1.12 服务端也能加载（详见 WIKI）。

---

## 🎮 功能一览

### 昵称
- 长度、字符集（可自定义 Unicode 区间）、屏蔽词、与真实 ID 相同检测
- **全服唯一**：忽略颜色/大小写/空格可配置，Unicode NFKC 归一化防伪装
- 玩家路径禁止颜色代码，颜色必须走 GUI（防止"隐形空格/隐藏字符"类昵称）

### 颜色
- 纯色（16 染料色）与双色渐变
- 1.16+ 支持十六进制真彩，旧版本自动降级到最近的 16 色
- 三层 GUI：选类型 → 选色 → 预览购买

### 显示
- 聊天栏、TAB 列表、玩家头顶三处应用
- **双占位符**：支持"仅昵称"（`#小明`）与"原ID+昵称"（`Steve#小明`）两种模式
- 自动处理 1.8-1.12 的 16 字符长度限制与颜色码截断

### 经济
- 购买模式：金币（Vault）/ 点券（PlayerPoints），**昵称与颜色各自独立配置**
- 权限模式：拥有权限即可免费设置，权限丢失可自动隐藏
- 超长昵称按字数阶梯加价

### 存储与同步
- SQLite（单服，默认）/ MySQL（多服）
- MySQL 下通过同步表实现**跨服昵称同步**
- 数据库驱动**不打包**，首次使用自动从镜像下载（阿里云 → 腾讯云 → 官方）

### 扩展
- **PlaceholderAPI**：`%ncnick_nickname%`、`%ncnick_display%` 等十余个变量
- **实验性接管**：直接替换原版 TAB / 头顶 / 聊天显示，避免多插件"各显示一套"
- **30 种内置语言**，缺失键自动回退

---

## 📖 文档

完整使用文档（安装、配置、命令、权限、变量、存储、FAQ、架构）见 **[WIKI](./wiki/Home.md)**。

常用命令速查：

```text
/nc                  打开昵称界面
/nc set <昵称>       直接设置昵称
/nc color            打开颜色界面
/nc remove           清除自己的昵称
/nc info             查看自己的昵称信息

/nca                 打开管理界面
/nca set <玩家> <昵称>   设置指定玩家昵称（可用颜色代码）
/nca color <玩家> <颜色> 设置昵称颜色
/nca resetcolor <玩家>   重置昵称颜色
/nca clear <玩家>        清除指定玩家昵称
/nca list [页码]         列出所有已设置昵称的玩家
/nca driver [status|download]  驱动管理
/nca debug [on|off]      切换调试模式
/nca reload              重载配置与语言
```

---

## 🛠️ 从源码构建

```bash
git clone <repo-url> NCNick
cd NCNick
mvn clean package
# 产物：target/NCNick-2.0.0.jar
```

技术栈：Maven · Java 8 · Spigot API · HikariCP（relocate 打包）· SLF4J（relocate 打包）
数据库驱动以 `provided` 作用域引入，运行时由内置下载器按需获取。

---

## 📄 许可证

本项目基于 **GNU General Public License v3.0** 发布，详见 [LICENSE](./LICENSE)。

