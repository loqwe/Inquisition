# 本地 OCR 理智识别

## 技术选择

本功能使用 `RapidOCR 1.4.4 + ONNX Runtime CPU + OpenCV headless`。没有采用完整 PaddleOCR，也没有把 Python 和模型塞进 Java 主容器。

`ddddocr 1.6.1` 已用同一张截图做过对照：精确裁开当前值和上限后，Beta 模型可以得到 `1` 和 `210`；但裁剪稍宽就出现 `w2i0` 等结果，而且它不负责在游戏截图中定位文字。因此生产只使用 RapidOCR，不同时加载两套模型。

样图实际识别响应：

```json
{"currentSanity":1,"maxSanity":210,"confidence":0.9222488164901733,"votes":3}
```

## 执行链路

1. 旧设备脚本照常上传截图，并在 `/completeTask` 传入 `imageUrl`，无需更新 Lua。
2. 后端完成任务状态落库后，把账号 ID、截图地址和完成时间放入单线程有界队列。
3. Java 只允许下载 `INQUISITION_OCR_ALLOWED_IMAGE_HOSTS` 中的域名，拒绝重定向、非图片响应和超过 8 MiB 的内容。
4. Java 将图片二进制 POST 到内网 sidecar 的 `/v1/sanity`，sidecar 不主动访问任何外部 URL。
5. OpenCV 识别 16:9 游戏画面并按相对坐标裁剪理智区域，依次执行原图、2 倍放大、CLAHE 增强三种预处理；花纹背景导致检测器漏掉大号当前值时，使用同一个 RapidOCR 模型的仅识别模式处理更小的当前理智区域。
6. 至少两个预处理版本必须得到相同的当前理智和上限，且满足 `0 <= current <= max <= 999`。
7. Java 再检查最低置信度，合格后写入 `account_runtime`，来源为 `LOCAL_OCR`。
8. 比截图更新的理智记录不会被旧 OCR 覆盖；后续森空岛新结果仍可继续校准。

OCR 下载、推理和写库都不阻塞 `/completeTask`。没有图片、队列满、超时、低置信度或结果不一致时，只跳过 OCR，不改变原任务完成结果。

## 配置

后端需要与 OCR sidecar 位于同一个 Docker 网络，并设置：

```dotenv
INQUISITION_OCR_ENABLE=true
INQUISITION_OCR_SERVICE_URL=http://inquisition-sanity-ocr:8000/v1/sanity
INQUISITION_OCR_ALLOWED_IMAGE_HOSTS=inquisition-img.longxin.xyz
INQUISITION_OCR_MAX_IMAGE_BYTES=8388608
INQUISITION_OCR_MINIMUM_CONFIDENCE=0.80
```

多个图床域名用英文逗号分隔。不要把任意用户可控域名加入允许列表。
当 OCR 已启用但接口地址、域名白名单或大小限制无效时，后端会拒绝启动，避免静默失效。

## 构建和运行

在仓库根目录执行：

```shell
docker compose -f ocr-service/compose.yml up -d --build
```

sidecar 只使用 Compose 的 `expose`，不映射宿主机端口。现有后端容器重建时需要增加：

```shell
--network inquisition-internal
```

健康检查：

```shell
docker exec inquisition-sanity-ocr python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/health').read().decode())"
```

## 验证

Python 单测及指定样图：

```powershell
$env:OCR_SAMPLE_IMAGE='截图绝对路径'
python -m unittest discover -s ocr-service/tests -v
```

接口测试：

```shell
curl -H 'Content-Type: image/png' --data-binary @sample.png http://127.0.0.1:8000/v1/sanity
```

本机 Windows 实测模型常驻工作集约 `111 MiB`，两张真实截图单次请求约 `1.1-2.3` 秒。Linux 容器的最终 RSS 应在部署灰度时用 `docker stats` 重新记录；Compose 将内存上限设为 `768 MiB`，并将并发限制为 1，避免 OCR 抢占 Java 后端。

## 回滚

将 `INQUISITION_OCR_ENABLE=false` 后重启 Java 后端，再停止 `inquisition-sanity-ocr` 容器即可。该功能不增加数据库表或字段，回滚不需要执行 SQL；已写入的 `LOCAL_OCR` 快照可以保留，并会被后续森空岛结果更新。
