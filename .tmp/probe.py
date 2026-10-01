"""编码损坏分析（临时脚本）：找出不可逆字符并验证恢复方案。"""

from collections import Counter
from pathlib import Path

root = Path(__file__).resolve().parent.parent

text = (root / "docs/TECHNICAL.md").read_bytes().decode("utf-8-sig")
readme = (root / "README.md").read_bytes().decode("utf-8")

bad: Counter[str] = Counter()
for ch in text:
    try:
        ch.encode("gbk")
    except UnicodeEncodeError:
        bad[ch] += 1

print(f"不可 GBK 编码的字符种类 {len(bad)}，总数 {sum(bad.values())}")
for ch, n in bad.most_common(15):
    try:
        gb = ch.encode("gb18030").hex()
    except Exception as exc:  # noqa: BLE001
        gb = f"<{exc.__class__.__name__}>"
    print(f"  U+{ord(ch):04X} {ch!r} x{n} gb18030={gb} | README 中出现 {readme.count(ch)} 次")

# 检查：这些字符是否都能用「单字节 latin-1 值」解释（.NET cp936 对 0x80-0xFF 单字节的 best-fit 映射）
print()
print("按 latin-1 字节解释（0x80-0xFF 单字节 best-fit）:")
for ch, _n in bad.most_common(15):
    code = ord(ch)
    tag = f"0x{code:02X}" if 0x80 <= code <= 0xFF else "非单字节范围"
    print(f"  U+{code:04X} {ch!r} -> {tag}")
