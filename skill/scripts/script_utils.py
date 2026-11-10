#!/usr/bin/env python3
# SPDX-License-Identifier: NOASSERTION
"""Small CLI helpers shared by ok-cosmic scripts."""

from __future__ import annotations

import argparse
import sys
from typing import Callable, Optional


class FriendlyArgumentParser(argparse.ArgumentParser):
    """ArgumentParser that reports concise failures instead of dumping usage."""

    def error(self, message: str) -> None:
        self.exit(1, f"✖️ 参数错误: {message}\n提示: 使用 --help 查看参数。\n")


def run_cli(main_func: Callable[[], Optional[int]]) -> int:
    """Run a CLI entrypoint and convert uncaught exceptions to failure text."""

    try:
        result = main_func()
        if isinstance(result, int):
            return result
        return 0
    except SystemExit as e:
        code = e.code
        if code is None:
            return 0
        if isinstance(code, int):
            return code
        print(f"✖️ 执行失败: {code}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("✖️ 执行已取消", file=sys.stderr)
        return 130
    except Exception as e:
        print(f"✖️ 执行失败: {e}", file=sys.stderr)
        return 1
