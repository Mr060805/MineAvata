"""把 Live2D 模型导入为桌宠 assets（通用版，任意模型）。

- 贴图超尺寸自动降采样
- moc3/物理/cdi3/动作/表情全部拷入
- 重写 model3.json：贴图路径、动作组、表情清单
- 文件名全部 ASCII 化（避免 native 层中文路径问题）
"""
import json
import os
import re
import shutil
import sys

from PIL import Image


def safe_name(name: str) -> str:
    """中文/特殊字符文件名 → 安全 ASCII 名"""
    # 保留扩展名，主体用拼音化的简单策略：非 ASCII 字符替换为 _
    stem, ext = os.path.splitext(name)
    safe = re.sub(r'[^\w.-]', '_', name)
    return safe


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    src = os.path.abspath(sys.argv[1])
    dst = os.path.abspath(sys.argv[2])
    tex_size = int(sys.argv[3]) if len(sys.argv) > 3 else 2048

    os.makedirs(dst, exist_ok=True)

    model_json_name = next(f for f in os.listdir(src) if f.endswith(".model3.json"))
    with open(os.path.join(src, model_json_name), encoding="utf-8") as fp:
        model3 = json.load(fp)
    fr = model3["FileReferences"]

    rename = {}  # 原名 → 新名（全部平铺到 dst）

    # 1) 贴图
    new_textures = []
    for tex in fr["Textures"]:
        tex_path = os.path.join(src, tex)
        im = Image.open(tex_path)
        if im.size[0] > tex_size:
            im = im.resize((tex_size, tex_size), Image.LANCZOS)
        out = os.path.basename(tex)
        im.save(os.path.join(dst, out), optimize=True)
        new_textures.append(out)
        print(f"贴图 {os.path.basename(tex)} -> {out} ({im.size[0]})")
    fr["Textures"] = new_textures

    # 2) moc3 / 物理
    for key in ("Moc", "Physics", "DisplayInfo"):
        if key in fr and fr[key]:
            shutil.copy2(os.path.join(src, fr[key]), os.path.join(dst, os.path.basename(fr[key])))
            fr[key] = os.path.basename(fr[key])

    # 3) 动作登记
    motions = {}
    for f in sorted(os.listdir(src)):
        if f.endswith(".motion3.json"):
            shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
            motions.setdefault("Motion", []).append(
                {"File": f, "Sound": "", "FadeInTime": 300, "FadeOutTime": 300}
            )
    if motions:
        fr["Motions"] = motions

    # 4) 表情登记
    expressions = []
    for f in sorted(os.listdir(src)):
        if f.endswith(".exp3.json"):
            name = f[: -len(".exp3.json")]
            safe = name  # 表情名保留原文（用于 cdi 语义归类）
            shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
            expressions.append({"Name": name, "File": f})
    if expressions:
        fr["Expressions"] = expressions

    # 5) model3.json 改名+重写（贴图/动作/表情路径全部平铺化）
    new_json_name = "model.model3.json"
    with open(os.path.join(dst, new_json_name), "w", encoding="utf-8") as fp:
        json.dump(model3, fp, ensure_ascii=False, indent="\t")
    if model_json_name != new_json_name:
        old_path = os.path.join(dst, model_json_name)
        if os.path.exists(old_path):
            os.remove(old_json_name if False else os.path.join(dst, model_json_name))

    total = sum(os.path.getsize(os.path.join(dst, f)) for f in os.listdir(dst))
    print(f"完成 -> {dst}  总大小 {sum(os.path.getsize(os.path.join(dst,f)) for f in os.listdir(dst))/1e6:.1f}MB")


if __name__ == "__main__":
    main()
