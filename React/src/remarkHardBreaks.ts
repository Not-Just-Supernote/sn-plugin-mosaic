// remarkHardBreaks.ts — 把段落内的单个换行渲染为硬换行（<br>）
// 爬取的 Reddit/贴吧内容不遵循 markdown 双空格换行规范，
// 单个 \n 默认被渲染成空格（中文场景直接揉在一起），此插件将其转为 break 节点。
// 零依赖实现（等价于 remark-breaks），代码块（code/inlineCode 为 value 节点）不受影响。

export default function remarkHardBreaks() {
  return (tree: any) => {
    const walk = (node: any) => {
      if (!Array.isArray(node.children)) return
      const next: any[] = []
      for (const child of node.children) {
        if (child.type === 'text' && typeof child.value === 'string' && child.value.includes('\n')) {
          const parts = child.value.split('\n')
          parts.forEach((p: string, i: number) => {
            if (p) next.push({ type: 'text', value: p })
            if (i < parts.length - 1) next.push({ type: 'break' })
          })
        } else {
          walk(child)
          next.push(child)
        }
      }
      node.children = next
    }
    walk(tree)
    return tree
  }
}
