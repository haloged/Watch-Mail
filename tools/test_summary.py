#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
汇总 JUnit XML 测试结果，输出可读的文本报告。

用法：
    python tools/test_summary.py [--results DIR] [--out FILE]

默认读取 app/build/test-results/testDebugUnitTest/，输出到 stdout；
指定 --out 时同时写入文件（UTF-8）。
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser(description="汇总单元测试结果")
    parser.add_argument(
        "--results",
        default=os.path.join("app", "build", "test-results", "testDebugUnitTest"),
    )
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    files = sorted(glob.glob(os.path.join(args.results, "*.xml")))
    if not files:
        print(f"未找到测试结果：{args.results}", file=sys.stderr)
        return 1

    lines = ["WearMail 单元测试结果（testDebugUnitTest）", "=" * 78]
    total = failed = errors = skipped = 0
    for path in files:
        root = ET.parse(path).getroot()
        tests = int(root.get("tests", 0))
        fails = int(root.get("failures", 0))
        errs = int(root.get("errors", 0))
        skips = int(root.get("skipped", 0))
        total += tests
        failed += fails
        errors += errs
        skipped += skips
        lines.append(
            f"{root.get('name'):<62} tests={tests:<4} failures={fails} "
            f"errors={errs} skipped={skips} time={root.get('time')}s"
        )
    lines.append("-" * 78)
    lines.append(f"合计: {total} 个用例, 失败 {failed}, 错误 {errors}, 跳过 {skipped}")
    lines.append("")
    lines.append("结果: " + ("全部通过" if failed == 0 and errors == 0 else "存在失败用例"))

    text = "\n".join(lines)
    print(text)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
