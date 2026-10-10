// HistoryList.tsx — 通用历史记录面板：查看、添加、删除（转录历史与贴吧爬取历史共用）
import React, { useState, useEffect, useCallback } from 'react'

interface HistoryListProps<T extends { id: string; createdAt: string }> {
  title: string
  endpoint: string          // 列表 GET / 删除 DELETE 共用前缀，如 '/api/transcriptions'
  getName: (record: T) => string
  getDateSuffix?: (record: T) => string   // 日期后附加信息，如 '· 12楼'
  deleteConfirmText: string
  onLoadRecord: (record: T) => void
  onAddAsCards: (record: T) => void
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function HistoryList<T extends { id: string; createdAt: string }>({
  title, endpoint, getName, getDateSuffix, deleteConfirmText, onLoadRecord, onAddAsCards,
}: HistoryListProps<T>) {
  const [records, setRecords] = useState<T[]>([])
  const [loading, setLoading] = useState(false)
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null)

  const fetchHistory = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch(endpoint)
      if (res.ok) {
        const data = await res.json()
        setRecords(data.records || [])
      }
    } catch {
      // 后端未启动时静默失败
    } finally {
      setLoading(false)
    }
  }, [endpoint])

  useEffect(() => { fetchHistory() }, [fetchHistory])

  const handleDelete = useCallback(async (id: string) => {
    try {
      const res = await fetch(`${endpoint}/${id}`, { method: 'DELETE' })
      if (res.ok) {
        setRecords(prev => prev.filter(r => r.id !== id))
      }
    } catch {
      // ignore
    }
    setDeleteConfirmId(null)
  }, [endpoint])

  if (loading) {
    return (
      <div className="transcription-history">
        <div className="history-header">{title}</div>
        <div className="transcription-status"><div className="status-spinner" />加载中...</div>
      </div>
    )
  }

  if (records.length === 0) return null

  return (
    <div className="transcription-history">
      <div className="history-header">{title}</div>
      {records.map(record => (
        <div key={record.id} className="history-item">
          <div className="history-item-info">
            <span className="history-item-name" title={getName(record)}>
              {getName(record)}
            </span>
            <span className="history-item-date">
              {formatDate(record.createdAt)}{getDateSuffix ? ` ${getDateSuffix(record)}` : ''}
            </span>
          </div>
          <div className="history-item-actions">
            <button className="history-btn" onClick={() => onLoadRecord(record)}>查看</button>
            <button className="history-btn" onClick={() => onAddAsCards(record)}>添加</button>
            <button
              className="history-btn danger"
              onClick={() => setDeleteConfirmId(record.id)}
            >删除</button>
          </div>

          {deleteConfirmId === record.id && (
            <div className="delete-confirm">
              <span>{deleteConfirmText}</span>
              <div className="delete-confirm-actions">
                <button className="history-btn danger" onClick={() => handleDelete(record.id)}>确认删除</button>
                <button className="history-btn" onClick={() => setDeleteConfirmId(null)}>取消</button>
              </div>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

export default HistoryList
