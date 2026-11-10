#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cosmic-post-check.py — 苍穹代码生成后统一检查入口

逻辑：
1. 从目标文件/目录向上查找 Gradle 项目根目录（同时存在 build.gradle + settings.gradle）
2. 若找到 → 直接执行 Gradle 编译，编译通过后再执行代码规范检查
3. 若未找到 → 直接执行 cosmic-post-lint.py 代码规范检查

用法:
    python3 cosmic-post-check.py <file_or_directory> [--fix-hint] [--json] [--strict]

示例:
    python3 cosmic-post-check.py /path/to/project/code/module-bos/src/main/java/Foo.java
    python3 cosmic-post-check.py ./src/main/java/ --fix-hint
"""

import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Dict, Optional

from config_loader import get_bool, get_int, load_project_config
from script_utils import run_cli

SCRIPT_DIR = Path(__file__).resolve().parent

# 全局 verbose 标志，通过 --verbose 开启
VERBOSE = False


def _verbose(msg: str):
    """在 --verbose 模式下输出诊断信息到 stderr。"""
    if VERBOSE:
        print(f"  [verbose] {msg}", file=sys.stderr)


# ══════════════════════════════════════════════
#  Gradle 项目检测
# ══════════════════════════════════════════════

def find_gradle_root(target: str) -> Optional[Path]:
    """从目标路径向上查找 Gradle 项目根目录。

    判定条件：目录中同时存在 build.gradle(.kts) 和 settings.gradle(.kts)。
    """
    p = Path(target).resolve()
    if p.is_file():
        p = p.parent
    while p != p.parent:
        has_build = (p / "build.gradle").exists() or (p / "build.gradle.kts").exists()
        has_settings = (p / "settings.gradle").exists() or (p / "settings.gradle.kts").exists()
        if has_build and has_settings:
            return p
        p = p.parent
    return None


def parse_modules(gradle_root: Path) -> Dict[str, Path]:
    """解析 settings.gradle 获取 模块名 → 目录 映射。

    支持标准格式：
        include 'moduleName'
        project(':moduleName').projectDir = new File('relative/path')
    """
    modules: Dict[str, Path] = {}
    for name in ("settings.gradle", "settings.gradle.kts"):
        settings = gradle_root / name
        if settings.exists():
            break
    else:
        return modules

    text = settings.read_text(encoding="utf-8")

    # 收集所有 include 声明
    includes = re.findall(r"include\s+'([^']+)'", text)

    # 收集 projectDir 映射
    project_dirs: Dict[str, str] = dict(re.findall(
        r"project\(':([^']+)'\)\.projectDir\s*=\s*new\s+File\('([^']+)'\)",
        text,
    ))

    for mod_name in includes:
        if mod_name in project_dirs:
            modules[mod_name] = (gradle_root / project_dirs[mod_name]).resolve()
        else:
            modules[mod_name] = (gradle_root / mod_name).resolve()

    return modules


def find_module(gradle_root: Path, target: str,
                modules: Dict[str, Path]) -> Optional[str]:
    """确定目标文件/目录属于哪个 Gradle 子模块。"""
    target_path = Path(target).resolve()
    # 优先匹配最长路径（最具体的模块）
    best_match: Optional[str] = None
    best_len = 0
    for name, mod_dir in modules.items():
        try:
            target_path.relative_to(mod_dir)
            if len(str(mod_dir)) > best_len:
                best_len = len(str(mod_dir))
                best_match = name
        except ValueError:
            continue
    return best_match


# ══════════════════════════════════════════════
#  JAVA_HOME 解析（不做版本判断）
# ══════════════════════════════════════════════

def _read_properties(filepath: Path) -> Dict[str, str]:
    """读取 .properties 文件为 key→value 字典。"""
    props: Dict[str, str] = {}
    if not filepath.exists():
        return props
    for line in filepath.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def resolve_java_home(gradle_root: Path, configured: str = "") -> Optional[str]:
    """解析 JAVA_HOME 路径，不做版本判断。

    优先级：DSH 插件设置 postCheck.javaHome → gradle.properties 中
    org.gradle.java.home → 环境变量 JAVA_HOME。
    """
    if configured and os.path.isdir(configured):
        return configured
    props = _read_properties(gradle_root / "gradle.properties")
    explicit = props.get("org.gradle.java.home", "").strip()
    if explicit and os.path.isdir(explicit):
        return explicit
    env_home = os.environ.get("JAVA_HOME", "").strip()
    if env_home and os.path.isdir(env_home):
        return env_home
    return None


# ══════════════════════════════════════════════
#  Gradle 编译
# ══════════════════════════════════════════════

def run_gradle(
    gradle_root: Path,
    module: Optional[str],
    java_home: Optional[str],
    config: Dict,
) -> int:
    """执行 Gradle 编译，返回退出码。不预判 JDK 版本，直接尝试构建。"""
    gradle_command = str(config.get("postCheck", {}).get("gradleCommand", "") or "").strip()
    if gradle_command:
        cmd_prefix = [gradle_command]
    else:
        # 定位 gradlew
        wrapper_name = "gradlew.bat" if sys.platform == "win32" else "gradlew"
        gradlew = gradle_root / wrapper_name
        if not gradlew.exists():
            print("[Warn] gradlew 不存在，尝试使用系统 gradle", file=sys.stderr)
            cmd_prefix = ["gradle"]
        else:
            # 确保 gradlew 有可执行权限
            if not os.access(gradlew, os.X_OK):
                os.chmod(gradlew, 0o755)
            cmd_prefix = [str(gradlew)]

    task_name = str(config.get("postCheck", {}).get("gradleTask", "compileJava") or "compileJava").strip()
    task = f":{module}:{task_name}" if module else task_name
    extra_args_text = str(config.get("postCheck", {}).get("gradleArgs", "") or "")
    extra_args = shlex.split(extra_args_text, posix=(os.name != "nt"))
    cmd = cmd_prefix + [task] + extra_args
    timeout_seconds = max(1, get_int(config, "postCheck", "timeoutSeconds", 600))

    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home

    print("══════════════════════════════════════════════")
    print("  [Build] Gradle 编译检查")
    print("══════════════════════════════════════════════")
    print(f"  [Project] 项目根目录: {gradle_root}")
    if module:
        print(f"  [Module] 目标模块:   {module}")
    print(f"  [Java] JAVA_HOME:  {java_home or '(未设置，由 Gradle 自行解析)'}")
    print(f"  [Command] 编译命令:   {' '.join(cmd)}")
    print("──────────────────────────────────────────────")
    print(flush=True)

    try:
        result = subprocess.run(
            cmd,
            cwd=str(gradle_root),
            env=env,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired:
        print()
        print("══════════════════════════════════════════════")
        print(f"  ✖️ Gradle 编译超时（>{timeout_seconds} 秒），已终止。")
        print("  可在 DSH 设置 → 苍穹开发 中调大 postCheckTimeoutSeconds。")
        print("══════════════════════════════════════════════")
        return 1

    print()
    print("══════════════════════════════════════════════")
    if result.returncode == 0:
        print("  ✔️ Gradle 编译成功，未发现编译错误。")
    else:
        print(f"  ✖️ Gradle 编译失败 (exit code {result.returncode})")
        print("  请根据上述编译错误信息修复代码。")
    print("══════════════════════════════════════════════")

    return result.returncode


# ══════════════════════════════════════════════
#  Post-Lint 代码规范检查
# ══════════════════════════════════════════════

def run_post_lint(args: list) -> int:
    """执行 cosmic-post-lint.py 代码规范检查。"""
    post_lint = SCRIPT_DIR / "cosmic-post-lint.py"
    if not post_lint.exists():
        print(f"✖️ 未找到 {post_lint}", file=sys.stderr)
        return 1

    print("[Check] 执行代码规范检查")
    print(flush=True)

    cmd = [sys.executable, str(post_lint)] + args
    result = subprocess.run(cmd)
    return result.returncode


# ══════════════════════════════════════════════
#  主入口
# ══════════════════════════════════════════════

def main():
    global VERBOSE

    if len(sys.argv) < 2:
        print("用法: cosmic-post-check.py <file_or_directory> [--config <json>] [--fix-hint] [--json] [--strict] [--verbose]")
        print()
        print("优先执行 Gradle 编译；编译通过后再执行代码规范检查。非 Gradle 项目直接执行规范检查。")
        sys.exit(1)

    args = list(sys.argv[1:])

    # 解析 --config 并从参数中移除（不传给 post-lint）
    config: Dict = {}
    if "--config" in args:
        index = args.index("--config")
        if index + 1 < len(args):
            config = load_project_config(args[index + 1])
            del args[index:index + 2]
        else:
            print("✖️ --config 缺少路径", file=sys.stderr)
            sys.exit(1)

    # 解析 --verbose 并从 argv 中移除（不传给 post-lint）
    if "--verbose" in args:
        VERBOSE = True
        args.remove("--verbose")

    if not args:
        print("✖️ 缺少要检查的文件或目录", file=sys.stderr)
        sys.exit(1)

    target = args[0]
    remaining_args = args

    # 1. 检测 Gradle 项目（插件设置可关闭）
    gradle_enabled = get_bool(config, "postCheck", "gradleEnabled", True)
    gradle_root = find_gradle_root(target) if gradle_enabled else None
    _verbose(f"Gradle 项目检测: {gradle_root or '未找到'}")

    if not gradle_root:
        # 非 Gradle 或已禁用 → 直接执行代码规范检查
        sys.exit(run_post_lint(remaining_args))

    # 2. 解析 JAVA_HOME（设置优先，不做版本判断）
    configured_java_home = str(config.get("postCheck", {}).get("javaHome", "") or "").strip()
    java_home = resolve_java_home(gradle_root, configured_java_home)
    _verbose(f"JAVA_HOME: {java_home or '未设置'}")

    # 3. 直接执行 Gradle 编译
    modules = parse_modules(gradle_root)
    module = find_module(gradle_root, target, modules)
    exit_code = run_gradle(gradle_root, module, java_home, config)
    if exit_code != 0:
        sys.exit(exit_code)

    # 4. 编译成功 → 执行代码规范检查
    print("[Tip] Gradle 编译通过，继续执行代码规范检查...")
    sys.exit(run_post_lint(remaining_args))


if __name__ == "__main__":
    sys.exit(run_cli(main))
