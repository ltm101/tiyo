# Tiyo 手机原生 Presence 架构

## 产品边界

Tiyo 是手机 Agent，其他应用接入的主链路必须在手机上独立工作

```text
平台官方接口 / 手机长连接
        ↓
PresenceAdapter（只负责收发）
        ↓
PresenceRouter → 同一个 Tiyo Agent → 同一人格、记忆、EnuMan
        ↓
PresenceOutboundGate（来源绑定、白名单、健康状态、频率、能力检查）
        ↓
原平台
```

电脑、cc-connect 与 KoyoGateway 不再是飞书、企业微信、QQ、微信的运行依赖

## 手机原生通道

| 平台 | 手机传输 | 身份 | 当前首版能力 |
|---|---|---|---|
| 飞书 | 官方 Java SDK WebSocket | 企业自建机器人 | 文字、图片、回复 |
| 企业微信 | 智能机器人 WebSocket | 官方 Bot | 文字、语音转写、加密图片、回复 |
| QQ | 官方 Bot Gateway + OpenAPI v2 | 官方机器人 | 群 @、C2C、图片、回复 |
| 微信 | 腾讯 iLink HTTP 长轮询 | 独立联系人 | 手机内扫码绑定、文字与语音转写、回复 |
| 抖音 | Android 系统分享为手机原生主路径 | 分享联系人 | 文字、链接、视频分享 |

抖音独立联系人仍属于可选扩展，因为当前没有能在 Android 上等价替代 Windows
“抖音聊天”注入点的官方 Bot API。该扩展不得被描述成手机原生能力

## 保活与恢复

- `TiyoPresenceService` 使用 Android `remoteMessaging` 前台服务类型
- 开机后由 `DeviceEventReceiver` 恢复已启用通道
- 凭据存入 Android Keystore 加密存储
- 未填写白名单时只信任第一位联系者，随后自动锁定
- 每个平台的 Adapter 不调用模型、不写记忆、不自行决定是否回复
- 同一会话消息进入串行队列，不丢弃连续消息

## 多模态

- 平台图片先保存到 Tiyo 私有目录
- `PresenceConversationCoordinator` 将原图作为 image content 交给 Rust Agent
- 公开版不内置模型凭据；用户可选择任意声明原生视觉能力的兼容模型
- provider 声明 `supports_vision=true` 时原图直达模型
- 只有纯文本模型才使用备用识图链路

## 仍需平台侧完成的外部开户动作

代码不伪造平台账号或凭据。真正上线前需要用户在对应平台创建机器人并把凭据填入
Tiyo 的“接入其他应用”页面；微信可直接在手机内扫码绑定
