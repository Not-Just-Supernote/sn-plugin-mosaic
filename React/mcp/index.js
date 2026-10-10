#!/usr/bin/env node

/**
 * MCP Server — 卡片盒（Card Box）只读访问
 * Streamable HTTP transport，端口 3792
 *
 * 环境变量：
 *   CARDBOX_API_URL — whiteboard-ai 后端地址，默认 http://localhost:3791
 *   MCP_PORT        — MCP 监听端口，默认 3792
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { createMcpExpressApp } from '@modelcontextprotocol/sdk/server/express.js'
import { z } from 'zod'

const API_URL = process.env.CARDBOX_API_URL || 'http://localhost:3791'
const PORT = Number(process.env.MCP_PORT) || 3792

// ============================================
// HTTP 客户端
// ============================================

async function api(path) {
  const res = await fetch(`${API_URL}${path}`)
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`API ${res.status}: ${text}`)
  }
  return res.json()
}

function parseTags(raw) {
  try { return JSON.parse(raw || '[]') } catch { return [] }
}

// ============================================
// MCP Server 工厂（stateless，每次请求新建）
// ============================================

function createServer() {
  const server = new McpServer({
    name: 'cardbox',
    version: '1.0.0',
  }, { capabilities: { tools: {} } })

  // ---------- list_boxes ----------
  server.tool(
    'list_boxes',
    '列出所有卡片盒（分组），含各盒子的卡片数量',
    {},
    async () => {
      const data = await api('/api/box/list')
      const boxes = data.boxes.map(b => ({
        id: b.id,
        name: b.name,
        description: b.description,
        card_count: b.card_count,
        updated_at: b.updated_at,
      }))
      return { content: [{ type: 'text', text: JSON.stringify(boxes, null, 2) }] }
    },
  )

  // ---------- list_cards ----------
  server.tool(
    'list_cards',
    '列出某个盒子里的所有卡片（标题/预览/来源/标签），可按来源类型或关键词过滤',
    {
      box_id: z.string().describe('盒子 ID'),
      source_type: z.string().optional().describe('按来源类型过滤：import/clipboard/manual/transcription/tieba/reddit-post/reddit-comment'),
      tag: z.string().optional().describe('按标签过滤'),
      q: z.string().optional().describe('按关键词搜索标题和内容'),
    },
    async ({ box_id, source_type, tag, q }) => {
      const params = new URLSearchParams()
      if (source_type) params.set('source_type', source_type)
      if (tag) params.set('tag', tag)
      if (q) params.set('q', q)
      const qs = params.toString()
      const data = await api(`/api/box/${encodeURIComponent(box_id)}/cards${qs ? '?' + qs : ''}`)
      const cards = data.cards.map(c => ({
        id: c.id,
        title: c.title,
        preview: c.preview,
        source_type: c.source_type,
        source_label: c.source_label,
        tags: parseTags(c.tags),
        author: c.author,
        created_at: c.created_at,
      }))
      return { content: [{ type: 'text', text: JSON.stringify(cards, null, 2) }] }
    },
  )

  // ---------- get_card ----------
  server.tool(
    'get_card',
    '获取单张卡片的完整内容（全文、来源、标签等）',
    {
      card_id: z.string().describe('卡片 ID'),
    },
    async ({ card_id }) => {
      const data = await api(`/api/box/card/${encodeURIComponent(card_id)}`)
      const c = data.card
      const card = {
        id: c.id,
        box_id: c.box_id,
        title: c.title,
        content: c.content,
        source_type: c.source_type,
        source_label: c.source_label,
        source_url: c.source_url,
        tags: parseTags(c.tags),
        author: c.author,
        created_at: c.created_at,
        updated_at: c.updated_at,
      }
      return { content: [{ type: 'text', text: JSON.stringify(card, null, 2) }] }
    },
  )

  // ---------- search_cards ----------
  server.tool(
    'search_cards',
    '跨所有盒子搜索卡片（按关键词匹配标题和内容）',
    {
      q: z.string().describe('搜索关键词'),
      box_id: z.string().optional().describe('限定在某个盒子内搜索'),
    },
    async ({ q, box_id }) => {
      const params = new URLSearchParams({ q })
      if (box_id) params.set('box_id', box_id)
      const data = await api(`/api/box/search?${params.toString()}`)
      const cards = data.cards.map(c => ({
        id: c.id,
        box_id: c.box_id,
        title: c.title,
        preview: c.preview,
        source_type: c.source_type,
        tags: parseTags(c.tags),
        author: c.author,
        created_at: c.created_at,
      }))
      return { content: [{ type: 'text', text: JSON.stringify(cards, null, 2) }] }
    },
  )

  return server
}

// ============================================
// Express + Streamable HTTP
// ============================================

const app = createMcpExpressApp({ host: '0.0.0.0' })

app.post('/mcp', async (req, res) => {
  const server = createServer()
  try {
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
    await server.connect(transport)
    await transport.handleRequest(req, res, req.body)
    res.on('close', () => { transport.close(); server.close() })
  } catch (error) {
    console.error('[MCP] Error:', error)
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: '2.0',
        error: { code: -32603, message: 'Internal server error' },
        id: null,
      })
    }
  }
})

app.get('/mcp', (req, res) => {
  res.writeHead(405).end(JSON.stringify({
    jsonrpc: '2.0',
    error: { code: -32000, message: 'Method not allowed.' },
    id: null,
  }))
})

app.delete('/mcp', (req, res) => {
  res.writeHead(405).end(JSON.stringify({
    jsonrpc: '2.0',
    error: { code: -32000, message: 'Method not allowed.' },
    id: null,
  }))
})

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[MCP] cardbox server listening on http://0.0.0.0:${PORT}/mcp`)
  console.log(`[MCP] backend: ${API_URL}`)
})

process.on('SIGINT', () => process.exit(0))
