# cloud.lua Reverse Notes

## Artifact

- 授权与范围：仅对自有项目做静态兼容分析。生产包和提取文件只读；未执行生产字节码，未解密或还原商业脚本，未 hook、attach 或 instrument 客户设备，未连接真实账号；生产包仅通过既有只读更新接口获取并用 MD5 校验；未通过 SSH 或管理接口访问、修改后端服务器，未部署、修改或上传生产包。
- 采集日期：2026-07-19；平台：Windows；解释器：Python 3.12.10。
- 生产证据严格限于（下列 JSON SHA256 均按本次 Windows 工作树字节计算）：
  - `analysis/cloud-lua/manifest.json`，SHA256 `29d37791991ac4bddccd226525429d8ed6eea60616fae54bd58ce4f3cbcaa12f`
  - `analysis/cloud-lua/x86lua-report.json`，SHA256 `a3bf99e849c898e37a363828be5ede4fc9f20c0f83856873614b25c6aafea0a1`
  - ignored `analysis/cloud-lua/extracted/entry.json`，SHA256 `ed278cf8d4b8939cd186a3e41228727fa6fe166204d7143070d037664c7b31f0`
  - ignored `analysis/cloud-lua/extracted/脚本/cloud.lua`，SHA256 `46b11aca44e945d96e6b3ccb205045e307bf7a5d4ff6494559d2b9835860d107`
- `manifest.json` 记录的生产包：大小 `1,712,597` bytes，MD5 `de760ee66209882f057c6f1f77ed35db`，SHA256 `921117c26d2eebabf7d080c654112c16f4de2294f177f46ae94ecb640637b655`。
- ZIP 清单共有 `29` 个条目，其中 `22` 个是 `脚本/*.lua`。`entry.json` 指向 Lua 主入口、资源入口、UI 入口和插件目录，并标记 `enc="1"`；本分析未执行这些入口。
- `脚本/cloud.lua` 的 ZIP 时间为 `2026-07-05 23:17:28`（ZIP 元数据未提供时区），未压缩大小 `26,403` bytes，压缩大小 `21,100` bytes，CRC32 `6065ef70`。
- `x86lua-report.json` 与实际只读文件交叉验证一致：头部 hex `864c55410300`（`86 4c 55 41 03 00`），`isX86Lua=true`，版本字节 `3`，熵 `7.341525` bits/byte，可见 ASCII 字符串数量 `101`。实际文件大小和 SHA256 也与上述结果一致。

复验生产文件静态属性使用：

```powershell
python -B -c "import hashlib,json,pathlib; from tools.cloud_lua_reverse.x86lua import probe_bytes; p=pathlib.Path(r'analysis/cloud-lua/extracted/脚本/cloud.lua'); data=p.read_bytes(); report=probe_bytes(data); print(json.dumps({'sha256':hashlib.sha256(data).hexdigest(), **{k:report[k] for k in ('size','isX86Lua','version','headerHex','entropy','printableStringCount')}}, indent=2))"
```

## Static recovery

### Production token presence

`analysis/cloud-lua/protocol-presence.json` 由以下命令直接从生产 `cloud.lua` 生成，本次 Windows 工作树文件 SHA256 为 `7700d3eb05ee05d5465840996c7a695089c1762a52f3573d0ec68392aa584e49`：

```powershell
python -B -m tools.cloud_lua_reverse.protocol_reference analysis/cloud-lua/extracted/脚本/cloud.lua --output analysis/cloud-lua/protocol-presence.json
```

固定清单的 `14` 个 token 均为 `false`：

| 类别 | 不可见 token |
| --- | --- |
| 路径 | `/heartBeat`, `/getTask`, `/addLog`, `/uploadImage`, `/completeTask`, `/failTask`, `/sanReport`, `/haltComplete` |
| 字段 | `deviceToken`, `assignmentId`, `clientVersion`, `accountId`, `imageUrl`, `taskType` |

The payload encrypts or encodes protocol constants; absence is evidence of opacity, not evidence that an endpoint is unused.

静态可确认的生产结构仅包括 `x86LUA` 头、版本字节、大小、熵和可见字符串统计。没有稳定端点、字段名、函数名、状态码或重复协议结构从生产文件中恢复；本报告不声称恢复了 Lua 源码。

### Historical reference boundary

历史明文只采用指定工作树的已提交对象，不采用工作副本：

```powershell
git -C D:/自建功能/审判庭/_worktrees/arklights-reliability status --short -- cloud.lua
git -C D:/自建功能/审判庭/_worktrees/arklights-reliability diff -- cloud.lua
git -C D:/自建功能/审判庭/_worktrees/arklights-reliability rev-parse HEAD
git -C D:/自建功能/审判庭/_worktrees/arklights-reliability show HEAD:cloud.lua
python -B -c "import hashlib,subprocess; data=subprocess.check_output(['git','-C',r'D:\自建功能\审判庭\_worktrees\arklights-reliability','show','HEAD:cloud.lua']); print(len(data), hashlib.sha256(data).hexdigest())"
```

- 历史工作树 HEAD：`bf26f4fb30171251864a623a354c90784329ed37`。
- `HEAD:cloud.lua`：`16,913` bytes，SHA256 `18395691aabf007ecc87b41b0e7c8f727407ed4813d6f72a9f71443ae5c79da6`。这是下表唯一的 **Historical reference**。
- 工作副本状态为 `M cloud.lua`；工作副本 SHA256 `5fbf87e204dafafb762d317612456144ef36b53aebe717a449129d93d8e0fb91`。其未提交 diff 增加任务分配标识、客户端版本、日志身份补全、部分 `200/409` 收口和重复停机抑制。这些内容全部标记为“排除的未发布增强”，没有当作历史协议、生产能力或部署内容。
- `D:/自建功能/审判庭/ArkLights/cloud.lua` 是另一个明文同源候选，SHA256 `49719eedad779dbde38905643a95c6298bf240d6c6f95f6ae97e65a9cd70ba6a`；它不是用户指定的历史引用，因此也未用于协议结论。
- 分析过程中未复制或使用真实密码、会话 token、私钥、账号值或设备标识值；历史文件中的账号/凭据相关键仅作为结构存在性处理。

### Historical protocol summary

以下均来自已提交的 `HEAD:cloud.lua`，不是对生产 x86LUA 的源码恢复：

| 操作 | 方法与路径 | 历史请求形态和旧字段 |
| --- | --- | --- |
| heartBeat | `POST /heartBeat` | JSON body：`status`, `deviceToken`。 |
| getTask | `GET /getTask` | Query：`deviceToken`。 |
| addLog | `POST /addLog` | Query：`deviceToken`；JSON body 包含 `id`, `level`, `taskType`, `title`, `detail`, `imageUrl`, `from`, `name`, `time`，另有账号、服务器和凭据相关旧键但不记录任何值；活动实现将凭据字段置空。 |
| uploadImage | `POST /uploadImage` | JSON body：`base64Image`, `deviceToken`。这是 base64 JSON 上传，不是 multipart/form 上传。历史函数名为 `uploadImgToInquisition`。 |
| completeTask | `POST /completeTask` | 空 body；Query：`deviceToken`, `imageUrl`。 |
| failTask | `POST /failTask` | 空 body；Query：`deviceToken`, `imageUrl`, `type`。历史失败类型常量包括线路繁忙和账号错误两类，但不记录账号值。 |
| sanReport | `POST /sanReport` | 空 body；Query：`san`, `maxSan`, `deviceToken`。 |
| haltComplete | `POST /haltComplete` | JSON body：`status`, `deviceToken`。 |

历史客户端没有发送 `assignmentId`、`clientVersion` 或独立的 `accountId`；日志使用旧 `id` 字段。没有观察到上述八个操作使用 form 编码。

历史返回码和状态流仅可概括为：

- `uploadImage` 先要求 HTTP transport code `200`，再要求 JSON `data.code == 200`，成功时返回 `data.data`；其他情况返回空值。
- `getTask` 响应需能解码为 table，且 `data` 为 table、业务码不为 `500`，才交给任务求解入口。每轮轮询后将本地 `status` 重置为 `1`，间隔约 5 秒。
- 心跳约每 5 秒一次；历史代码把心跳 JSON 业务码 `500` 解释为停机信号，随后调用 `haltComplete` 并安排停止。
- 历史已提交版本的 `completeTask`、`failTask` 和 `haltComplete` 只返回原始响应/transport code，不解析 `200/409`；相关增强只存在于被排除的未提交工作副本。

## Decoder triage

### Focused search

按计划执行的原始只读搜索为：

```powershell
rg -a -n --hidden --glob '!analysis/cloud-lua/original/**' --glob '!analysis/cloud-lua/extracted/**' 'x86LUA|installLrPkg|restartPackage|httpPost|heartBeat' D:/自建功能/审判庭 D:/自建功能/核云服务器
```

该命令返回 `140` 个匹配行、可解析为 `25` 个文件；`D:/自建功能/核云服务器` 没有匹配。筛选时排除了 `.git`/生成报告/当前工具与测试/计划文档/后端 Java 服务等不能代表 LR 解码器的命中，没有把大文件或二进制偶然字符串当作结论。

进一步确认 `installLrPkg` 没有本地 Lua 定义：

```powershell
rg -a -n --hidden --glob '*.lua' 'installLrPkg\s*=|function\s+installLrPkg' D:/自建功能/审判庭/_worktrees/arklights-reliability D:/自建功能/审判庭/ArkLights
```

结果为无匹配。没有执行 APK、EXE 或 shared library，也没有反编译第三方商业 LR 运行时。

### Inspected candidates

候选读取和哈希使用的实际只读命令如下；除指定的 `HEAD:cloud.lua` Git blob 外，候选 SHA256 均按本次 Windows 工作树字节计算。表格以命令编号关联，避免在 Markdown 表格中改写管道字符：

```powershell
# C1: candidate hash inventory
@(
  'D:\自建功能\审判庭\_worktrees\arklights-reliability\util.lua',
  'D:\自建功能\审判庭\_worktrees\arklights-reliability\path.lua',
  'D:\自建功能\审判庭\_worktrees\arklights-reliability\exPath.lua',
  'D:\自建功能\审判庭\_worktrees\arklights-reliability\cloud.lua',
  'D:\自建功能\审判庭\ArkLights\util.lua',
  'D:\自建功能\审判庭\ArkLights\path.lua',
  'D:\自建功能\审判庭\ArkLights\exPath.lua',
  'D:\自建功能\审判庭\ArkLights\cloud.lua',
  'D:\自建功能\审判庭\_worktrees\inquisition-reliability\tools\cloud_lua_reverse\x86lua.py'
) | ForEach-Object {
  $item = Get-Item -LiteralPath $_
  $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $_
  [pscustomobject]@{ Path = $_; Size = $item.Length; SHA256 = $hash.Hash.ToLowerInvariant() }
} | Format-List

# C2: static probe source
Get-Content -Raw -LiteralPath 'tools\cloud_lua_reverse\x86lua.py'

# C3: loader/update and restart references
Get-Content -LiteralPath 'D:\自建功能\审判庭\_worktrees\arklights-reliability\util.lua' | Select-Object -Skip 2678 -First 32
Get-Content -LiteralPath 'D:\自建功能\审判庭\_worktrees\arklights-reliability\util.lua' | Select-Object -Skip 4928 -First 26
Get-Content -LiteralPath 'D:\自建功能\审判庭\_worktrees\arklights-reliability\util.lua' | Select-Object -Skip 4954 -First 44
Get-Content -LiteralPath 'D:\自建功能\审判庭\_worktrees\arklights-reliability\util.lua' | Select-Object -Skip 4998 -First 35

# C4: comment-only restart matches
Get-Content -LiteralPath 'D:\自建功能\审判庭\_worktrees\arklights-reliability\path.lua' | Select-Object -Skip 6344 -First 12
Get-Content -LiteralPath 'D:\自建功能\审判庭\_worktrees\arklights-reliability\exPath.lua' | Select-Object -Skip 32 -First 12
```

| 候选 | SHA256 | 复验命令 | 结果类别 |
| --- | --- | --- | --- |
| `tools/cloud_lua_reverse/x86lua.py` | `0cd55db9fa6a885616329b0060140b373cbd7c9766024fcaa4a2cef28c022de2` | C1, C2 | **no decoder**：只识别头、版本、熵和 printable strings。 |
| `D:/自建功能/审判庭/_worktrees/arklights-reliability/util.lua` | `1d1488652b0cb2fd6e2ce1e5065ad9abc0c381e763a341581a99cdd8785eb947` | focused `rg`, C1, C3 | **loader reference**：热更新下载后做 MD5 校验并调用宿主 `installLrPkg`; `restartPackage` 仅负责编排重启，没有解码实现。 |
| `D:/自建功能/审判庭/ArkLights/util.lua` | `1d1488652b0cb2fd6e2ce1e5065ad9abc0c381e763a341581a99cdd8785eb947` | focused `rg`, C1 | **loader reference**：与上项字节相同，无本地 decoder。 |
| designated `HEAD:cloud.lua` | `18395691aabf007ecc87b41b0e7c8f727407ed4813d6f72a9f71443ae5c79da6` | historical boundary commands | **historical source**：只作为旧协议参考。 |
| `D:/自建功能/审判庭/_worktrees/arklights-reliability/cloud.lua` 工作副本 | `5fbf87e204dafafb762d317612456144ef36b53aebe717a449129d93d8e0fb91` | focused `rg`, historical diff, C1 | **historical source / excluded unpublished enhancement**：不是 decoder，也不是历史基线。 |
| `D:/自建功能/审判庭/ArkLights/cloud.lua` | `49719eedad779dbde38905643a95c6298bf240d6c6f95f6ae97e65a9cd70ba6a` | focused `rg`, C1 | **historical source / excluded sibling**：不是指定基线，也不是 decoder。 |
| 两份 `path.lua` | `d942d0d555dcb2f27dd4758161fda2238455e13b341d78a3d2fc2119ae858d2a` | focused `rg`, C1, C4 | **unrelated**：唯一相关命中是注释中的 `restartPackage()`。两份文件字节相同。 |
| 两份 `exPath.lua` | `04dfbc07a66b8e1a6edd1528df6d4b000163c698205425f178d48d835116fe48` | focused `rg`, C1, C4 | **unrelated**：唯一相关命中是注释中的 `restartPackage()`。两份文件字节相同。 |

No local decoder was identified; compiled internals remain Unresolved.

### Runtime capture gate

只检查了计划允许的两个候选：

```powershell
@(
  'D:\自建功能\审判庭\_worktrees\arklights-reliability\localConfig.py',
  'D:\自建功能\审判庭\ArkLights\localConfig.py'
) | ForEach-Object { [pscustomobject]@{ Path = $_; Exists = Test-Path -LiteralPath $_ -PathType Leaf } } | Format-List
```

两者均不存在。因此：Runtime capture unavailable: no isolated local LR project configured

没有使用生产账号、真实设备或远程服务进行捕获，本报告不使用 **Confirmed runtime**。

## Protocol conclusion

生产 x86LUA 的操作级内部语义没有被静态恢复。下表把“历史协议存在”与“生产脚本是否确认”分开；状态值只使用允许的四类。

| 操作 | 历史协议状态 | 生产脚本状态 |
| --- | --- | --- |
| `heartBeat` | **Historical reference** — `POST /heartBeat`, JSON | **Unresolved** |
| `getTask` | **Historical reference** — `GET /getTask`, query | **Unresolved** |
| `addLog` | **Historical reference** — `POST /addLog`, query + JSON | **Unresolved** |
| `uploadImage` | **Historical reference** — `POST /uploadImage`, base64 JSON upload | **Unresolved** |
| `completeTask` | **Historical reference** — `POST /completeTask`, query + empty body | **Unresolved** |
| `failTask` | **Historical reference** — `POST /failTask`, query + empty body | **Unresolved** |
| `sanReport` | **Historical reference** — `POST /sanReport`, query + empty body | **Unresolved** |
| `haltComplete` | **Historical reference** — `POST /haltComplete`, JSON | **Unresolved** |

- **Confirmed static** 仅适用于生产制品格式事实：`x86LUA` 头、版本 `3`、大小、哈希、熵和字符串统计；没有操作达到生产协议级的 Confirmed static。
- **Confirmed runtime**：无。runtime gate 不可用，且没有执行或插桩生产运行时。
- **Historical reference**：八个方法/路径及旧请求形态由指定 `HEAD:cloud.lua` 确认。
- **Unresolved**：生产包是否仍使用每个端点、生产内部字段映射、业务码分支、编译/编码算法、密钥和指令语义。没有猜测这些内容。

生产脚本内部语义与后端兼容性是两条独立证据链：服务端可通过契约测试发送上表的历史最小请求，验证旧请求在缺少 `assignmentId`、`clientVersion` 和独立 `accountId` 时仍被正确处理；这类测试只能确认后端兼容行为，不能把生产 x86LUA 的内部实现提升为 Confirmed static 或 Confirmed runtime。
