/**
 * 统一 URL 识别器 — Reddit 板块 / Reddit 单帖 / 贴吧帖子
 * 纯正则，无网络调用。
 */

export type ResolvedSource =
  | { platform: 'reddit'; type: 'subreddit'; name: string; url: string }
  | { platform: 'reddit'; type: 'post'; name: string; postId: string; url: string }
  | { platform: 'tieba'; type: 'post'; postId: string; url: string }
  | null

const REDDIT_POST_RE = /reddit\.com\/r\/([A-Za-z0-9_]+)\/comments\/([a-z0-9]+)/
const REDDIT_SUB_RE = /reddit\.com\/r\/([A-Za-z0-9_]+)\/?(?:\?.*)?$/
const BARE_SUB_RE = /^\/?r\/([A-Za-z0-9_]+)\/?$/
const TIEBA_RE = /tieba\.baidu\.com\/p\/(\d{5,})/

export function resolveSourceUrl(input: string): ResolvedSource {
  const url = input.trim()

  let m = url.match(REDDIT_POST_RE)
  if (m) {
    return {
      platform: 'reddit',
      type: 'post',
      name: m[1],
      postId: m[2],
      url: `https://www.reddit.com/r/${m[1]}/comments/${m[2]}/`,
    }
  }

  m = url.match(REDDIT_SUB_RE) || url.match(BARE_SUB_RE)
  if (m) {
    return {
      platform: 'reddit',
      type: 'subreddit',
      name: m[1],
      url: `https://www.reddit.com/r/${m[1]}/`,
    }
  }

  m = url.match(TIEBA_RE)
  if (m) {
    return {
      platform: 'tieba',
      type: 'post',
      postId: m[1],
      url: `https://tieba.baidu.com/p/${m[1]}`,
    }
  }

  // 纯数字视为贴吧帖子 ID（兼容现有行为）
  if (/^\d{5,}$/.test(url)) {
    return {
      platform: 'tieba',
      type: 'post',
      postId: url,
      url: `https://tieba.baidu.com/p/${url}`,
    }
  }

  return null
}
