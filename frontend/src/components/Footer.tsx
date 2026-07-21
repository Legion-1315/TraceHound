import { useEffect, useState } from 'react'
import { useTriageStore } from '../store'

export default function Footer() {
  const { running, startedAt, toolCallCount, report } = useTriageStore()
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    if (!running) return
    const t = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(t)
  }, [running])

  const elapsedMs = report ? report.elapsedMs : startedAt ? now - startedAt : 0
  const elapsed = (elapsedMs / 1000).toFixed(1)

  return (
    <footer data-tour="footer" className="flex items-center gap-6 border-t border-slate-800 bg-slate-950 px-4 py-1.5 text-[11px] text-slate-500">
      <span>
        elapsed <span className="font-mono text-slate-300">{elapsed}s</span>
      </span>
      <span>
        tool calls <span className="font-mono text-slate-300">{report ? report.toolCallCount : toolCallCount}</span>
      </span>
      <span>
        PII redacted <span className="font-mono text-emerald-400">{report ? report.piiRedactedCount : '—'}</span>
      </span>
      {report && (
        <span className="ml-auto">
          diagnosis confidence <span className="font-mono text-sky-300">{report.confidencePct}%</span>
        </span>
      )}
      {running && <span className="ml-auto animate-pulse text-sky-500">investigating…</span>}
    </footer>
  )
}
