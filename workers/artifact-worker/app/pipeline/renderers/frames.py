"""读取已校验素材中的原始关键帧并生成时间标注。"""

from io import BytesIO

from PIL import Image


def read_frame(frame_id, evidence, frame_reader):
    if not any(frame_id in sentence.get("frame_ids", []) for sentence in evidence["sentences"]):
        raise ValueError("关键帧不属于当前素材包")
    if frame_reader is None:
        raise ValueError("尚未配置任务关键帧读取接口")
    content = frame_reader(frame_id)
    if not isinstance(content, bytes) or not 0 < len(content) <= 16_000_000:
        raise ValueError("关键帧图片大小无效")
    with Image.open(BytesIO(content)) as image:
        if image.format not in {"PNG", "JPEG"} or image.width * image.height > 50_000_000:
            raise ValueError("关键帧图片格式或像素数量无效")
        image.verify()
    return content


def time_label(figure, evidence):
    anchors = set(figure["caption"]["anchors"])
    sentences = [s for s in evidence["sentences"]
                 if s["id"] in anchors and figure["frame_id"] in s.get("frame_ids", [])]
    labels = []
    for sentence in sentences:
        if sentence.get("start_ms") is None or sentence.get("end_ms") is None:
            raise ValueError("关键帧素材缺少视频时间区间")
        def clock(ms):
            seconds = int(ms) // 1000
            return f"{seconds // 3600:02d}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"
        label = f"P{sentence.get('part') or 1} {clock(sentence['start_ms'])} - {clock(sentence['end_ms'])}"
        if label not in labels:
            labels.append(label)
    if not labels:
        raise ValueError("关键帧图注没有对应的时间素材")
    return "；".join(labels)
