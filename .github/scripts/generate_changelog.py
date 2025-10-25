#!/usr/bin/env python3
"""
自动生成 Changelog
从上次 release tag 到当前 HEAD 的所有 commits，按类型分类生成 markdown
"""

import subprocess
import re
import sys
import locale
from collections import defaultdict
from typing import Dict, List, Tuple


# Commit 类型配置
COMMIT_TYPES = {
    'feat': {'title': '新功能', 'order': 1},
    'fix': {'title': 'Bug修复', 'order': 2},
    'perf': {'title': '性能优化', 'order': 3},
    'refactor': {'title': '重构', 'order': 4},
    'docs': {'title': '文档', 'order': 5},
    'style': {'title': '代码格式', 'order': 6},
    'test': {'title': '测试', 'order': 7},
    'build': {'title': '构建系统', 'order': 8},
    'ci': {'title': 'CI配置', 'order': 9},
    'chore': {'title': '其他', 'order': 10},
}


def run_command(cmd: str) -> str:
    """执行命令并返回输出"""
    try:
        result = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            encoding='utf-8',
            errors='ignore'
        )
        return result.stdout.strip() if result.stdout else ""
    except Exception:
        # 如果失败，返回空字符串
        return ""


def get_last_tag() -> str:
    """获取上一个 tag (倒数第二个，因为最新的 tag 就是当前 release)"""
    # 获取所有 tags，按版本号倒序排列，取第二个（跳过当前 tag）
    tags = run_command("git tag --sort=-v:refname")
    if not tags:
        return None
    tag_list = [t.strip() for t in tags.split('\n') if t.strip()]
    # 如果只有一个或没有 tag，返回 None
    if len(tag_list) < 2:
        return None
    # 返回倒数第二个 tag
    return tag_list[1]


def get_commits_since(since_ref: str = None) -> List[str]:
    """获取指定引用之后的所有 commits"""
    if since_ref:
        cmd = f"git log {since_ref}..HEAD --pretty=format:%s"
    else:
        # 如果没有 tag，获取所有 commits
        cmd = "git log HEAD --pretty=format:%s"

    output = run_command(cmd)
    return [line.strip() for line in output.split('\n') if line.strip()]


def parse_commit(commit_msg: str) -> Tuple[str, str, str]:
    """
    解析 commit message
    返回: (type, scope, description)
    """
    # 匹配格式: type(scope): description 或 type: description
    pattern = r'^(\w+)(?:\(([^)]+)\))?: (.+)$'
    match = re.match(pattern, commit_msg)

    if match:
        commit_type = match.group(1).lower()
        scope = match.group(2) or ''
        description = match.group(3)
        return commit_type, scope, description

    return None, None, commit_msg


def filter_commits(commits: List[str]) -> List[Tuple[str, str, str]]:
    """过滤并解析 commits"""
    parsed_commits = []

    for commit in commits:
        # 跳过 merge commits
        if commit.startswith('Merge '):
            continue

        commit_type, scope, description = parse_commit(commit)

        # 只保留已知类型的 commits
        if commit_type and commit_type in COMMIT_TYPES:
            parsed_commits.append((commit_type, scope, description))

    return parsed_commits


def group_commits_by_type(parsed_commits: List[Tuple[str, str, str]]) -> Dict[str, List[Tuple[str, str]]]:
    """按类型分组 commits"""
    grouped = defaultdict(list)

    for commit_type, scope, description in parsed_commits:
        grouped[commit_type].append((scope, description))

    return grouped


def generate_changelog(grouped_commits: Dict[str, List[Tuple[str, str]]]) -> str:
    """生成 markdown 格式的 changelog"""
    lines = []

    # 按照定义的顺序输出
    sorted_types = sorted(
        grouped_commits.keys(),
        key=lambda t: COMMIT_TYPES[t]['order']
    )

    for commit_type in sorted_types:
        type_info = COMMIT_TYPES[commit_type]
        commits = grouped_commits[commit_type]

        lines.append(f"### {type_info['title']}\n")

        for scope, description in commits:
            if scope:
                lines.append(f"- **{scope}**: {description}")
            else:
                lines.append(f"- {description}")

        lines.append("")  # 空行分隔

    return '\n'.join(lines)


def main():
    """主函数"""
    # 获取上次 tag
    last_tag = get_last_tag()

    if last_tag:
        print(f"# 从 {last_tag} 到现在的更新", file=sys.stderr)
    else:
        print("# 首次发布", file=sys.stderr)

    # 获取 commits
    commits = get_commits_since(last_tag)

    if not commits:
        print("## 无更新内容")
        return

    # 解析和过滤
    parsed_commits = filter_commits(commits)

    if not parsed_commits:
        print("## 无分类的更新内容")
        return

    # 分组
    grouped = group_commits_by_type(parsed_commits)

    # 生成 changelog
    changelog = generate_changelog(grouped)

    # 输出到 stdout
    print(changelog)


if __name__ == '__main__':
    main()
