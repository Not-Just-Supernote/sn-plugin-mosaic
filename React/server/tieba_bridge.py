#!/usr/bin/env python3
"""
贴吧爬虫桥接脚本 — 供 Node.js 后端调用
接收命令行参数，输出 JSON 到 stdout
用法: python3 tieba_bridge.py <post_id>
"""

import asyncio
import json
import re
import sys
from typing import Dict, Optional
from dataclasses import dataclass

try:
    import aiotieba as tb
except ImportError:
    print(json.dumps({
        "success": False,
        "error": "缺少依赖库，请安装：pip install aiotieba>=4.4.9"
    }))
    sys.exit(1)


@dataclass
class TiebaAuth:
    BDUSS: str = ""

    @classmethod
    def from_file(cls, filename: str = "tieba_auth.json"):
        try:
            with open(filename, 'r', encoding='utf-8') as f:
                data = json.load(f)
                return cls(BDUSS=data.get("BDUSS", ""))
        except (FileNotFoundError, json.JSONDecodeError):
            return cls()

    def is_valid(self) -> bool:
        return bool(self.BDUSS.strip())


class ContentFilter:
    """内容过滤器"""
    FOLD_KW = ["该楼层疑似违规已被系统折叠", "此回复已被折叠", "折叠回复", "隐藏此楼", "查看此楼"]
    JUNK_RE = [
        r"^[顶支持沙发前排占楼]+$", r"^[0-9]+楼$", r"^[哈哦嗯额啦呵]+$",
        r"^[艾666笑哭]+$", r"^[\.。！!？\s]+$", r"^收藏了?$",
        r"^马克$", r"^好$", r"^mark$", r"^[+1同]+$",
    ]
    QA_KW = [
        "问", "请问", "想问", "求问", "？", "吗", "呢", "求助",
        "有没有", "是否", "什么", "怎么", "为什么", "如何",
        "答", "回答", "解释", "说明", "分析",
    ]

    def score(self, text: str) -> float:
        s = 1.0 if len(text) > 50 else (0.5 if len(text) > 20 else 0.0)
        s += sum(0.5 for kw in self.QA_KW if kw in text)
        if "http" in text or "www." in text:
            s += 0.5
        if re.search(r'\d+集|\d+话|\d+号|\d+章', text):
            s += 0.3
        return s

    def keep(self, text: str) -> bool:
        if not text or len(text.strip()) < 5:
            return False
        if any(kw in text for kw in self.FOLD_KW):
            return False
        if any(re.match(p, text.strip(), re.I) for p in self.JUNK_RE):
            return False
        return self.score(text) >= 1.0


async def crawl(post_id: str, bduss: str = "") -> dict:
    """爬取帖子，返回 JSON 可序列化的 dict"""
    filt = ContentFilter()
    posts = []
    title = ""
    forum = ""
    total_raw = 0

    try:
        # 第一页：获取基本信息
        async with tb.Client(bduss) as client:
            first = await client.get_posts(int(post_id), 1)
            if not first or first.thread.tid == 0:
                return {"success": False, "error": "无法获取帖子，请检查ID是否正确"}

            title = first.thread.title
            forum = first.forum.fname
            total_pages = first.page.total_page
    except Exception as e:
        return {"success": False, "error": f"连接贴吧失败: {e}"}

    # 逐页爬取
    for pn in range(1, total_pages + 1):
        try:
            async with tb.Client(bduss) as client:
                page = await client.get_posts(int(post_id), pn)
                if not page or not hasattr(page, 'objs') or not page.objs:
                    continue

                for p in page.objs:
                    parts = []
                    if hasattr(p, 'contents'):
                        for c in p.contents:
                            if hasattr(c, 'text'):
                                parts.append(c.text)
                    text = '\n'.join(parts).strip()
                    total_raw += 1

                    if filt.keep(text):
                        posts.append({
                            "floor": getattr(p, 'floor', 0),
                            "username": getattr(p.user, 'user_name', '匿名') if hasattr(p, 'user') else '匿名',
                            "content": text,
                            "score": round(filt.score(text), 1),
                        })

        except Exception as e:
            print(f"[tieba_bridge] 第 {pn} 页异常: {e}", file=sys.stderr)
            continue

        if pn < total_pages:
            await asyncio.sleep(1)

    return {
        "success": True,
        "title": title,
        "forum": forum,
        "posts": posts,
        "total": total_raw,
        "filtered": len(posts),
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"success": False, "error": "用法: python3 tieba_bridge.py <post_id>"}))
        sys.exit(1)

    post_id = sys.argv[1]
    bduss = sys.argv[2] if len(sys.argv) > 2 else ""

    result = asyncio.run(crawl(post_id, bduss))
    print(json.dumps(result, ensure_ascii=False))
