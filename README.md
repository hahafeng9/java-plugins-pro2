## JavaPluginsPro

基于 [hahafeng9/java-plugins](https://github.com/hahafeng9/java-plugins) 的增强版 Minecraft Paper/Bukkit 代理插件，重点改进了插件的**文件生命周期管理、进程管理和测试能力**。

### 新增能力

| 能力 | 说明 |
| --- | --- |
| server.jar 自动替换 | 启动时检查 `server.jar`；支持从配置的 URL/本地路径获取新 JAR；替换前自动备份（`server.jar.bak-<时间戳>-<随机>`）；SHA-256 或结构校验；任一步失败自动回滚；**旧备份自动清理**（默认保留最近 3 份，可配置）；全程明确日志 |
| 临时文件随机化 | 每次运行使用唯一临时目录 `JavaPluginsPro-<随机ID>`，临时文件名由 `SecureRandom` 生成；随机名称仅用于避免运行实例之间的冲突；插件停止/程序退出时清理所有自己创建并登记的临时文件 |
| 文件清理命令 | `/plugin cleanup [--dry-run]`：只删除插件自己创建并登记的文件；删除前检查路径，禁止 `..` 越界；不扫描、不删除任何系统文件；`--dry-run` 先列出待删除项；删除失败输出具体原因 |
| 子进程管理 | 优先使用 Java API；确需外部进程（如 openssl）时保存 Process 对象与 PID 并登记；插件停止时 destroy → 等待 → 强制结束；不做任何隐藏/伪装/规避系统进程管理的行为 |
| 原生服务韧性 | 原生库下载内置双镜像自动回退（00666.xyz → oooen.com）；单个原生服务缺少导出符号或加载失败时优雅跳过并记录日志，不影响其余服务运行 |
| 线程管理 | 所有线程由 ThreadManager 创建，命名 `JavaPluginsPro-Worker-<随机ID>`；插件停止时中断并回收；无不可追踪的后台线程；`/plugin threads` 查看插件创建的全部线程 |
| 生命周期命令 | `/plugin status`：插件版本、运行状态、工作目录、任务数量、线程数量、子进程数量、临时文件数量 |

### 命令

```
/plugin status                 # 生命周期状态
/plugin cleanup [--dry-run]    # 安全清理插件自己产生的临时资源/备份
/plugin threads                # 调试：列出插件创建的线程
```

### 配置（config.yml）

```yaml
server-jar:
  path: "server.jar"           # 要管理的 server.jar 路径（相对服务器根目录）
  source: ""                   # 新 JAR 来源：http(s) URL 或本地路径；留空=不自动替换
  expected-sha256: ""          # 可选：下载 JAR 的期望 SHA-256；留空=结构校验（zip 魔数+manifest）
  replace-on-start: true       # 每次启动执行检查/替换
  max-backups: 3               # 最多保留最近 N 份备份，超出部分每次启动检查后自动删除
temp:
  base-dir: ""                 # 临时目录基准；留空=系统临时目录
shutdown:
  join-timeout-millis: 10000   # 停止时等待线程/子进程退出的超时
```

### 构建

```bash
mvn clean package
```

产物：`target/JavaPluginsPro-2.0.1.jar`（GitHub Actions 推送到 main 后自动构建并发布到 Release）。

### 测试

```bash
mvn test
```

自动化测试覆盖：

- server.jar 替换成功（首装 + 带备份替换）
- server.jar 替换失败后的回滚（损坏源 / 校验和不匹配 / 安装后校验失败）
- 随机临时文件名/目录名不会冲突（含 2 万次抽样唯一性）
- cleanup 不会删除工作目录之外的文件（`..` 越界 + 绝对路径越界）
- cleanup dry-run 不删除任何文件
- 插件停止后线程能够结束（中断 + join）
- 插件停止后创建的子进程能够结束（destroy + 强杀兜底）
- 重复启动/停止不会留下插件自己的临时资源

### 相关环境变量说明（与原版一致）

App 部分沿用原 java-plugins 的环境变量（可通过 `.env` 或系统环境变量配置）：

```
UUID=fe7431cb-ab1b-4205-a14c-d056f821b383  # 默认UUID
FILE_PATH=./world                          # 文件路径
NEZHA_SERVER=                              # Nezha服务器地址, v1: nezha.xxx.com:8008  v0: nezha.xxx.com
NEZHA_PORT=                                # Nezha agent端口,v1请留空，仅v0填写
NEZHA_KEY=                                 # Nezha agent密钥,面板后台安装命令里获取
                                           # 注意：若日志出现 "no JNA start function ... agent.so"，说明该 CDN 构建的
                                           # agent.so 缺少导出符号（v0 不可用），请将 NEZHA_PORT 留空改用 v1（v1.so）。
                                           # 单个服务启动失败只会跳过该服务并记录日志，不影响 sing-box/cloudflared。
ARGO_PORT=                                 # Argo隧道端口
ARGO_DOMAIN=                               # Argo固定隧道域名
ARGO_AUTH=                                 # Argo固定隧道密钥
S5_PORT=                                   # Socks5端口
HY2_PORT=                                  # HY2端口
TUIC_PORT=                                 # TUIC端口
ANYTLS_PORT=                               # AnyTLS端口
REALITY_PORT=                              # Reality端口
CFIP=spring.io                             # 优选域名或优选IP
CFPORT=443                                 # 优选域名或优选ip对应的端口
UPLOAD_URL=                                # 节点自动上传URL
CHAT_ID=                                   # Telegram Chat ID
BOT_TOKEN=                                 # Telegram Bot Token
NAME=                                      # 节点名称
DISABLE_ARGO=false                         # 是否禁用Argo,false开启,true禁用,默认开启
```
