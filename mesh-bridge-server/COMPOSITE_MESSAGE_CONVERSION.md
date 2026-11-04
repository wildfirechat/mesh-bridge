# 组合消息跨域转换修改说明

## 一、问题背景

中转服务在 `OutService.convertMessagePayloadDomainId` 中把消息里的用户 ID、群组 ID、消息 ID 和媒体地址转换为目标域可用的值。

原实现只处理普通消息，组合消息（`type == 11`）存在以下问题：

1. 组合消息内部的子消息没有逐条转换，子消息中的 `uid`、`target`、`from`、`tos`、提醒用户和引用消息 ID 等仍然使用原域数据。
2. 子消息中的图片、文件、视频等媒体地址没有转换，私有对象存储场景下目标域可能无法访问。
3. 子消息媒体转换是异步操作，如果直接循环调用并立即发送，可能出现媒体尚未传完消息就已经发出的问题。
4. 较大的组合消息会把完整子消息列表放在 `remoteMediaUrl` 指向的 JSON 文件中，仅转换 `base64edData` 会漏掉文件中的完整消息列表。
5. 同一个媒体文件跨域转发时可能重复上传；消息 publish 回原域时，也需要将对端媒体地址和子消息 ID 恢复为本域的值。（重复上传和接收域缺少对应关系的问题见第五节的反向映射）

## 二、组合消息格式

格式定义参考 `android-chat/client/src/main/java/cn/wildfirechat/message/CompositeMessageContent.java`：

- `base64edData`（客户端侧的 `binaryContent`）解码后是 `{"ms":[...]}`，`ms` 是子消息数组。
- 每条子消息由两部分组成：
  - 消息外壳：`uid`、`type`、`target`、`line`、`from`、`tos`、`direction`、`status`、`serverTime`、`le`。
  - 消息内容：`ctype`、`csc`、`cpc`、`cpd`、`cc`、`cbc`、`cmt`、`cmts`、`ce`、`mt`、`mru`，对应一个独立的 `MessagePayload`。
- `cbc` 本身已经是 Base64 字符串（子消息 `binaryContent` 的 Base64），构造子 `MessagePayload` 时直接赋值，**不能重复编码**。
- `mru` 是子消息的媒体地址；`mt` 是媒体类型（`MessageContentMediaType`，0=GENERAL，1=IMAGE，4=FILE …）。
- 组合消息本身也是 `MediaMessageContent`：当子消息较多（客户端阈值 20480 字节）时，完整列表写入一个 `wcf-*.data` 文件，`cbc` 中只保留前缀，完整列表通过外层 `remoteMediaUrl` 指向该文件。因此转换时必须同时处理 `cbc` 和 `mru` 两条路径。
- 组合消息支持嵌套：子消息的 `ctype` 仍然可以是 11，此时该子消息自己的 `cbc`/`mru` 又是一层组合消息。

几个容易踩坑的格式细节（本次修改已全部兼容）：

- `uid` 是子消息的**消息 ID(messageUid)**，不是客户端本地自增的 `messageId`。转换必须走 `findMappedMessageUid`（`convertMessageUid` 用的也是它），即 `in_message_ids`/`out_message_ids` 这一套映射。
- 接收者字段各端不一致：Android 端编码写 `tos`、解码却读 `toUsers`；Web 端 `tos` 写的是字符串而不是数组。代码同时兼容 `tos`/`toUsers`、数组/字符串两种形态。
- 部分消息类型的扩展数据（`cbc`）是可选的，不能直接 `Base64.decode(null)`。
- `uid` 必须**先于** `from` 转换，否则无法判断原消息来自本域还是外域。

## 三、出站转换（OutService）

### 1. 入口

`convertMessagePayloadDomainId` 增加了一个带 `depth` 的重载，`type == 11` 时交给 `convertCompositeMessagePayload`，其余类型逻辑保持不变。最大嵌套深度为 `CompositeMessageUtils.MAX_NEST_DEPTH`（32）。

### 2. 子消息 ID 转换

对每条子消息：

1. 读取原始 `from`，调用 `convertCompositeChildUid` 转换 `uid`。
2. `target`、`from`、`tos`/`toUsers` 使用 `DomainIdUtils.toExternalId` 转换。
3. 把 `ctype/cc/cbc/cmt/cmts/ce/mt/mru` 组成子 `MessagePayload`。
4. 递归调用 `convertMessagePayloadDomainId`，复用普通消息已有的 ID、引用、媒体转换逻辑。
5. 子消息转换成功后把结果写回 JSON（**只写回真正变化的字段**，不会给文本子消息凭空加 `mt:0`/`cmt:0`/`cmts:[]`）。
6. 所有子消息完成后重新编码外层 `base64edData`，再处理组合消息自身的媒体地址。

`uid` 的转换规则（`findMappedMessageUid`）：

| 子消息发送者 | 目标域 | 查询 | 说明 |
| --- | --- | --- | --- |
| 本域 | 任意 | `out_message_ids (uid, target)` | 本域发出去的消息，映射到目标域消息 ID |
| 外域 | 等于发送者所在域 | `in_message_ids (senderDomain, uid)` | publish 回原域，能反查出原域消息 ID |
| 外域 | 其它域 | 只查 `out_message_ids`，未命中置 0 | 没有可靠映射，0 表示目标域未知 |

原先 `convertMessageUid` 对外域发送者无条件查 `in_message_ids`，返回的是**消息原域**的 uid，只在"publish 回原域"时才成立；转到第三个域时会写入错误 ID。现在 `convertMessageUid` 统一走 `findMappedMessageUid`：先查 `out_message_ids`（中继消息也能命中），再按"目标域是否就是原域"决定是否查 `in_message_ids`。找不到映射时仍返回 0（引用/撤回沿用的旧约定），但日志从 `error` 降为 `warn`；组合消息子消息走 `findMappedMessageUid`，未命中时把子消息 `uid` 置 0 并只打 `debug` 日志。

**为什么未命中要置 0、不能保留原值**：子消息的 `uid` 可能来自别的域（比如 A 发组合消息给 B 时，某个子消息在 A 侧没有到 B 的映射）。B 以后再发出去时会先按本域 uid 查 `out_message_ids`，如果保留了这个外域 uid，而它恰好等于 B 某条发往目标域的消息 ID，就会误命中并改写成错误的消息 ID（uid 碰撞）。置 0 表示"目标域未知"，客户端会退化成不做消息定位，但不会指向错误的消息。

**批量查询**：整层子消息的 `uid` 会先收集起来，用一次 `in` 查询取回映射（`findByMessageIds` / `findByLocalMessageIds`，每 500 个一批），再统一回写；批量查询失败时自动退回逐条查询，行为和原来一致。100 条聊天记录的 uid 查询从 100~200 次降到 1~2 次。

### 3. 子消息串行转换

子消息必须串行处理：带媒体的子消息要等异步传输完成后再处理下一条。

实现上用 `CompletableFuture<Integer>` 表示"这条子消息转换完成"：

- 转换前会按整层子消息的 `mru` 预取媒体映射（`prefetchRemoteMediaUrls`），`transferFile` 直接命中缓存，不再逐个查库。
- 同步完成（无媒体 / 媒体命中映射缓存）：当前循环直接继续，不会递归加深调用栈。
- 异步完成：`whenComplete` 里从下一条继续，`isDone()` + `whenComplete` 的组合保证"完成通知恰好被消费一次"，不依赖普通布尔变量的跨线程可见性。
- 任一子消息失败：整个组合消息转换失败，不发送部分转换的数据；失败错误码原样向上传递。

### 4. 媒体转换

`convertMessageMedia` 统一处理：

- `isPublish2OriganlDomain == true`（publish 回消息发送者所在域）时跳过传输，直接用对方已保存的地址，对端收到后按映射换回本域地址。
- 普通消息且 `mediaPublicAccessible == true`：不传输，直接沿用地址。
- 普通消息且 `mediaPublicAccessible == false`：走原有 `transferFile`（边下边传，不落地）。
- 组合消息且存在 `remoteMediaUrl`：**无论媒体是否公开可读都要传输**，因为文件里存的是子消息列表，必须下载后改写再上传，不能只改地址。

### 5. 组合大文件改写

`transferCompositeFile` → `downloadCompositeFile` → `uploadCompositeFile`：

1. 下载前先查映射（目标域 + 本域地址），命中则直接复用对端地址。
2. 校验地址白名单/协议/私网限制，校验目标域存在。
3. 下载 JSON（限制大小，见配置），**下载完成后先关闭响应流**，再解析、改写、上传，避免上传期间一直占着下载连接。
4. 对 `ms` 数组执行与内嵌组合消息完全相同的递归转换。
5. 把改写后的 JSON 上传到目标域，用返回地址更新外层 `remoteMediaUrl`。
6. 保存"本域地址 → 目标域地址"映射。

内嵌前缀中已经上传过的媒体会写入映射，处理完整文件时直接复用，不会重复上传。

## 四、入站恢复（InService）

`send_message` 和 `publish_message` 两条入站路径都会调用 `restoreCompositeMediaUrls`，把对端视角的内容恢复成本域视角：

- 子消息 `mru`：按"来源域 + 对端地址"查映射，换回本域地址。
- 子消息 `uid`：按"来源域 + 对端消息 ID"查 `out_message_ids`，换回本域消息 ID；`uid` 为 0 时跳过（0 是对端"目标域未知"的标记，本来就恢复不了）。
- 嵌套组合消息递归处理，深度上限同上。
- 只有确实命中并改写了字段才重新编码 `base64edData`，不做无谓的 Base64 解码/编码。

**为什么还要恢复 `uid`**：publish 回本域时，IM 服务端按请求里的原 messageId 保存/投递消息（见 `PublishMessageHandler`：`messageId > 0` 时用该 ID `storeMessage`），本域原来那条消息的内容会被对端转换后的 payload 覆盖。如果 payload 里的子消息 `uid` 还是对端视角（比如老版本对端、或 republish 时 payload 取自对端本地存储），不恢复就会让本域消息引用别的域的消息 ID，而且原值已被覆盖、不可恢复。新版本对端在无法映射时会把 `uid` 置 0，这种情况没有可恢复的信息，只能保持 0。

恢复过程是**尽力而为**的：`cbc` 不是合法 JSON、`ms` 不是数组、结构异常等只记录 `warn` 并原样放行，不会因为"恢复不了地址"而拒掉整条消息（恢复失败最多是地址仍指向对端存储）。

## 五、媒体地址映射

新增：

- `src/main/java/cn/wildfirechat/bridge/jpa/MediaUrlMapping.java`
- `src/main/java/cn/wildfirechat/bridge/jpa/MediaUrlMappingRepository.java`
- `src/main/java/cn/wildfirechat/bridge/service/MediaUrlMappingService.java`

表 `media_url_mappings`：

| 字段 | 说明 |
| --- | --- |
| `domain_id` | 对端域 |
| `local_url_hash` / `remote_url_hash` | 地址的 SHA-256，索引和唯一约束建在哈希上 |
| `local_media_url` / `remote_media_url` | 完整地址，查询后核对完整值，避免长 URL 超索引限制 / 哈希碰撞 |
| `created_at` | 创建时间（毫秒），用于按保留期清理 |

- 发送方：上传前按"目标域 + 本域地址"查询，命中直接复用，不再上传；上传成功后写入 `(domain=目标域, local=本域地址, remote=对端地址)`。
- 接收方：上传方在 `/transfer_file` 请求头里带上 `x-source-media-url`（自己的原始地址），接收成功后写入 `(domain=来源域, local=本域地址, remote=来源域地址)`。这样接收域也有了对应关系，文件再发回来源域时 `findRemoteMediaUrl` 直接命中来源域原地址，不会重复上传。
- `x-source-media-url` 的值做过 percent 编码（header 只允许可见 ASCII），长度上限 4096；超长或非 http/https 时不带这个 header，接收方只是少一条反向映射，不影响文件传输。
- 写入用 `insert ... on duplicate key update`（upsert），不再"先查后插"：每次上传少一次查询，也不会因为并发插入抛唯一键异常。
- 进程内加了有界 LRU 缓存（每个方向 8192 条），避免每条出入站媒体消息、组合消息的每个子消息都查库；映射一旦建立不会变化，所以只做容量上限、不做过期。
- 查不到映射时进 30 秒负缓存，避免"没有映射"的地址每次都查库；保存映射时会清掉对应的负缓存。
- 组合消息按整层子消息批量预取（`prefetchRemoteMediaUrls` / `prefetchLocalMediaUrls`，按 hash 列表 `in` 查询，每 500 个一批），100 条记录的媒体映射查询从 100 次降到 1 次。
- 查询/写入的数据库异常降级为"没有映射 / 保存失败"，不影响消息收发。
- 定时清理：`@Scheduled` 按 `created_at` 索引分批删除（每批 `batch_size`，单次任务最多 100 批），避免一次删除太多行导致长事务和长时间持锁；`retention_days <= 0` 或 `cleanup_enabled=false` 时关闭。

映射方向语义：`domain_id` 永远是**对方域**。发送文件时是目标域，接收文件时是来源域——同一条链路的两个方向都落在"对方域"上，所以 `findRemoteMediaUrl(对方域, 本域地址)` 和 `findLocalMediaUrl(对方域, 对方地址)` 都能对称命中：
- 发送方保存 `(对方域, 本域地址, 对方地址)`，用于"再发一次"时去重；
- 接收方保存 `(对方域, 本域地址, 对方地址)`，用于"发回给对方"时复用对方原地址。

## 六、行为变化与兼容性

1. **`uid` 语义修正**：`convertMessageUid` 现在优先查 `out_message_ids`，并且只在目标域就是消息原域时才查 `in_message_ids`。这同时修正了引用、撤回消息在"转发到第三域"场景下写入错误消息 ID 的问题。
2. **未命中映射时子消息 `uid` 置 0**：不再保留原值，避免外域 uid 在本域被当成 uid 使用导致误命中（见 3.2 的说明）。代价是这类子消息在目标域里无法做"定位到原消息"，且如果消息被 publish 回原域，原域存储的子消息 uid 也会变成 0（0 无法再恢复）。
3. **入站解析失败不再拒消息**：`restoreCompositeMediaUrls` 全程 try/catch，失败只告警。
4. **组合大文件一定会跨域传输**：即使 `mediaPublicAccessible=true`，只要组合消息带 `remoteMediaUrl`，仍需下载改写后重新上传（内容是子消息列表，必须转换）。这会带来额外的带宽和存储开销，属于必要成本。
5. **接收域也建立映射**：新增 `x-source-media-url` 请求头（协议向后兼容：老对端不带这个头，只是少一条反向映射，签名算法不变）。文件再发回来源域时直接复用来源域原地址，不再重复上传。
6. **组合大文件单独限制大小**：默认 10MB（普通文件仍是 100MB），并且下载时始终用 `LimitedInputStream` 兜底（`Content-Length` 为 -1 也生效）。
7. **可选扩展数据判空**：`type` 为 10/400/406/104-124 的消息在 `base64edData` 为空时跳过扩展数据转换，不再 NPE；80/81 同样处理。
8. **只写回变化的字段**：子消息 JSON 中未知字段（如 `le`、`serverTime`、未来新增字段）原样保留，已知字段只在值变化时写回。
9. **`mentionedTarget` 判空**：修复了 `mentionedTarget` 为 null 时的 NPE（共享路径）。

## 七、配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `bridge.composite_file_max_size_mb` | `10` | 组合消息大文件上限（MB），0 表示不限制；不建议调大，文件会整个读进内存 |
| `bridge.media_url_mapping_retention_days` | `180` | 映射保留天数，`<=0` 表示不清理；需明显大于消息可能被重新 publish 的时间窗口 |
| `bridge.media_url_mapping_cleanup_cron` | `0 30 3 * * *` | 清理任务执行时间 |
| `bridge.media_url_mapping_cleanup_enabled` | `true` | 是否在本实例执行清理。多实例部署建议只在一个实例上打开 |
| `bridge.media_url_mapping_cleanup_batch_size` | `1000` | 每批删除行数，单次任务最多删 100 批 |
| `bridge.file_transfer_max_size_mb` | `100` | 普通文件传输上限（未变） |
| `bridge.media_public_accessible` | `true` | 媒体是否公开可读；为 false 时普通文件走 `transferFile`，组合消息始终走改写上传 |

`media_url_mappings` 由 `spring.jpa.hibernate.ddl-auto=update` 自动建表（新增了 `created_at` 列，如果环境里已经建过旧表，`update` 会自动补列）。生产环境如果用 `validate`，需要手动执行：

```sql
CREATE TABLE media_url_mappings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  domain_id VARCHAR(64) NOT NULL,
  local_url_hash VARCHAR(64) NOT NULL,
  local_media_url VARCHAR(4096) NOT NULL,
  remote_url_hash VARCHAR(64) NOT NULL,
  remote_media_url VARCHAR(4096) NOT NULL,
  created_at BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_media_local (domain_id, local_url_hash),
  UNIQUE KEY uk_media_remote (domain_id, remote_url_hash),
  KEY idx_media_created_at (created_at)
);
```

`@EnableScheduling` 已加到 `Application`，清理任务才会生效。

## 八、测试

新增单元测试（`src/test/java`，共 46 个用例）：

- `CompositeMessageUtilsTest`：组合消息 JSON 编解码、字段往返、只写回变化字段、未知字段保留、异常数据容错。
- `OutServiceCompositeConversionTest`：子消息用户/群 ID 转换、uid 三种映射规则（含未命中置 0）、整层 uid 批量查询、嵌套组合消息、异步媒体串行转换、异步失败传播与中断、畸形 `cbc`、组合大文件"媒体公开也要传"、publish 回原域不传文件、收到反向映射时复用来源域原地址。
- `InServiceCompositeRestoreTest`：子消息 `mru` 恢复、`uid` 恢复（整层批量查询）、嵌套恢复、无命中不重编码、畸形数据不抛异常、非组合消息直接跳过。
- `InServiceTransferFileTest`：接收文件时保存反向映射、没有/非法来源地址时不保存、保存失败不影响文件传输。
- `MediaTransferUtilsTest`：`x-source-media-url` 编码/解码、查询参数与中文、非法协议/超长地址。
- `OutServiceTransferHeaderTest`：上传请求头携带来源地址。
- `MediaUrlMappingServiceTest`：upsert 写入 + 双向缓存、空 URL、批量预取、负缓存、保留期/开关、分批清理。
- `JpaQueryValidationTest`：用 H2 建表并执行三条批量 JPQL，校验实体映射、查询语法和 `created_at` 索引。

运行：

```bash
cd mesh-bridge-server
./mvnw test                     # 需要能访问 Maven 仓库
./mvnw -o -DskipTests compile   # 离线编译
```

测试里通过覆盖 `OutService.doTransferFile` / `downloadCompositeFile`（包内可见）替换真正的"下载+上传"，映射查询、地址校验、`transferFile`/`transferCompositeFile` 的流程仍然走生产代码，不依赖真实 HTTP 和对象存储。

## 九、已知限制与上线注意

1. 组合消息的 uid 和媒体映射已改成整层批量查询（每 500 个一批），100 条记录大约 2~4 次查询；但单条消息超过 500 个子消息时会分成多批，长度和查询次数线性相关。
2. **升级前已经传过的文件没有反向映射**：接收域是升级后才开始记录 `x-source-media-url` 的，升级前的文件第一次被发回来源域时仍会重新上传一次，之后就有映射了。消息 ID 的映射（`in_message_ids`/`out_message_ids`）是原有能力，不受影响。
3. 接收方保存的来源地址来自请求头，**不在请求签名范围内**（签名 canonical 只包含 path/query/body/content-type/x-media-type/x-file-extension，老对端不带这个头也要能互通）。影响范围有限：这条映射只在"发回给同一个来源域"时使用，等于把对方自己声明的地址还给对方；但仍需保证 bridge 之间走 HTTPS，避免被中间人篡改。
4. 来源地址超过 4096 字符（或不是 http/https）时不会带这个 header，对应文件仍会像以前一样重新上传。
5. 映射保留期到期后，很久以前的消息再被 publish 回原域时，子消息地址/ID 无法恢复；保留期请按业务的消息可撤回/可编辑时间窗口设置。
6. 映射表存的是完整 URL 明文；当前存的都是普通对象存储地址，如果以后把预签名 URL 也写进来，这张表会包含可访问凭据，需要额外评估。
7. 映射命中会跳过地址白名单和目标域存在性校验（映射是在校验通过并传输成功后才写入的），这是有意为之，避免重复做一次网络/数据库校验。
8. 多实例部署时清理任务默认每个实例都会跑，虽然分批删除已降低持锁时间，仍建议只在一个实例上设置 `cleanup_enabled=true`，其余实例设为 false。
9. 负缓存有效期 30 秒：多实例场景下，另一个实例刚写入的映射可能在本实例还要等最多 30 秒才能命中，这期间该文件可能被重复上传一次。属于性能优化的可接受代价，映射本身不会错。

## 十、改动文件

新增：

- `utilis/CompositeMessageUtils.java`：组合消息 JSON 读写、嵌套深度上限。
- `utilis/MediaTransferUtils.java`：`x-source-media-url` 的编码/校验。
- `jpa/MediaUrlMapping.java`、`jpa/MediaUrlMappingRepository.java`、`service/MediaUrlMappingService.java`：媒体地址映射表、缓存、清理。
- `src/test/java/...`：第八节列出的 7 个测试类。

修改：

- `service/OutService.java`：组合消息递归转换、串行处理、组合大文件改写、`uid` 转换规则、上传请求头、组合文件大小限制。
- `service/InService.java`：入站 `mru`/`uid` 恢复（best-effort）、接收文件时保存反向映射。
- `controller/ExternalController.java`：读取 `x-source-media-url` 并传给 `InService`。
- `Application.java`：`@EnableScheduling`（映射清理任务）。
- `pom.xml`：测试依赖（`junit-jupiter`、`mockito-core`、`h2`）。
