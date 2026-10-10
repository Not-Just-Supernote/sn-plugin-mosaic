#!/usr/bin/env python3
"""Reddit 桥接脚本 — 包装 ScrapiReddit，供 Node 子进程调用。

命令：
  resolve <url>                                  识别 URL 类型（纯正则，无网络）
  list_posts <subreddit> [--sort new] [--after CURSOR] [--limit 100]
                                                 抓取一页 listing，输出 NDJSON
  get_comments <subreddit> <post_id> [--limit 500]
                                                 抓取单帖评论树，输出单条 JSON
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


def _find_scrapi_path() -> str:
    """定位 ScrapiReddit 目录：优先环境变量，再向上查找，最后回退默认值。"""
    env_path = os.environ.get('SCRAPI_REDDIT_PATH')
    if env_path and Path(env_path).is_dir():
        return env_path

    start = Path(__file__).resolve().parent
    for parent in [start, *start.parents]:
        candidate = parent / 'ScrapiReddit'
        if candidate.is_dir():
            return str(candidate)
    return '/home/Dev/ScrapiReddit'


SCRAPI_PATH = _find_scrapi_path()

try:
    from scrapi_reddit import (  # type: ignore
        build_session,
        fetch_json,
        flatten_comments,
        flatten_post_record,
    )
except ImportError:
    sys.path.insert(0, SCRAPI_PATH)
    try:
        from scrapi_reddit import (  # type: ignore
            build_session,
            fetch_json,
            flatten_comments,
            flatten_post_record,
        )
    except ImportError as exc:
        print(json.dumps({
            "type": "error",
            "message": f"无法导入 scrapi_reddit: {exc}。请确认 {SCRAPI_PATH} 存在",
        }))
        sys.exit(1)

BASE_URL = "https://www.reddit.com"
USER_AGENT = "whiteboard-ai/1.0 (community archiver)"


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


# ============================================
# resolve
# ============================================

SUBREDDIT_RE = re.compile(r"reddit\.com/r/([A-Za-z0-9_]+)/?(?:\?.*)?$")
POST_RE = re.compile(r"reddit\.com/r/([A-Za-z0-9_]+)/comments/([a-z0-9]+)")
BARE_SUB_RE = re.compile(r"^/?r/([A-Za-z0-9_]+)/?$")


def cmd_resolve(url: str) -> None:
    url = url.strip()
    m = POST_RE.search(url)
    if m:
        emit({"type": "post", "subreddit": m.group(1), "post_id": m.group(2)})
        return
    m = SUBREDDIT_RE.search(url) or BARE_SUB_RE.match(url)
    if m:
        name = m.group(1)
        emit({
            "type": "subreddit",
            "subreddit": name,
            "url": f"{BASE_URL}/r/{name}/",
        })
        return
    emit({"type": "unknown", "message": "无法识别的 Reddit URL"})


# ============================================
# list_posts — 一次一页，NDJSON 输出
# ============================================

def cmd_list_posts(subreddit: str, sort: str, after: str | None, limit: int) -> None:
    session = build_session(USER_AGENT, True)
    url = f"{BASE_URL}/r/{subreddit}/{sort}/.json"
    params: dict = {"limit": min(limit, 100), "raw_json": 1}
    if after:
        params["after"] = after

    data = fetch_json(session, url, params=params)
    listing = data.get("data", {}) if isinstance(data, dict) else {}
    children = listing.get("children", [])
    next_after = listing.get("after")

    scanned = 0
    for child in children:
        if not isinstance(child, dict) or child.get("kind") != "t3":
            continue
        post_data = child.get("data", {})
        record = flatten_post_record(subreddit, {}, post_data)
        scanned += 1
        emit({"type": "post", "data": record})

    emit({
        "type": "done",
        "scanned": scanned,
        "after": next_after,
        "has_more": bool(next_after),
    })


# ============================================
# get_comments — 单帖评论树
# ============================================

def cmd_get_comments(subreddit: str, post_id: str, limit: int) -> None:
    session = build_session(USER_AGENT, True)
    url = f"{BASE_URL}/r/{subreddit}/comments/{post_id}/.json"
    params = {"limit": min(limit, 500), "raw_json": 1}

    post_json = fetch_json(session, url, params=params)

    # 帖子本体（用于顺带更新分数/评论数）
    post_record = None
    if isinstance(post_json, list) and post_json:
        post_children = post_json[0].get("data", {}).get("children", [])
        if post_children:
            post_record = flatten_post_record(subreddit, {}, post_children[0].get("data", {}))

    context = {"post_id": post_id, "subreddit": subreddit}
    comments = flatten_comments(post_json, context)

    emit({
        "type": "comments",
        "post_id": post_id,
        "post": post_record,
        "comments": comments,
        "count": len(comments),
    })


# ============================================
# main
# ============================================

def main() -> None:
    parser = argparse.ArgumentParser(description="Reddit bridge for whiteboard-ai")
    sub = parser.add_subparsers(dest="command", required=True)

    p_resolve = sub.add_parser("resolve")
    p_resolve.add_argument("url")

    p_list = sub.add_parser("list_posts")
    p_list.add_argument("subreddit")
    p_list.add_argument("--sort", default="new")
    p_list.add_argument("--after", default=None)
    p_list.add_argument("--limit", type=int, default=100)

    p_comments = sub.add_parser("get_comments")
    p_comments.add_argument("subreddit")
    p_comments.add_argument("post_id")
    p_comments.add_argument("--limit", type=int, default=500)

    args = parser.parse_args()

    try:
        if args.command == "resolve":
            cmd_resolve(args.url)
        elif args.command == "list_posts":
            cmd_list_posts(args.subreddit, args.sort, args.after, args.limit)
        elif args.command == "get_comments":
            cmd_get_comments(args.subreddit, args.post_id, args.limit)
    except Exception as exc:  # noqa: BLE001
        emit({"type": "error", "message": str(exc)})
        sys.exit(1)


if __name__ == "__main__":
    main()
