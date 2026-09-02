# 橘瓣 OrangeChat 改动账本（PROGRESS）

> 记录 fork (Miss995/orangechat) 的功能改动。格式：日期 | commit | 改了啥 | 状态

## 2026-09-02
- commit 5e995b8 | 视频转述 V1 | Modality 新增 VIDEO；ChatCompletionsAPI 支持 video_url 序列化（OpenAI 兼容）；新增 VideoNarrationTransformer（模型不支持视频时自动调 OCR 视觉模型把视频转述成文本注入上下文）；注册 ChatService/ProactiveMessageService 转换链；UI 模态分支补全 + 模态编辑支持 Video。状态：已推 master，待宝构建验证（发 <7MB 短视频 → 应回视频叙述）。
