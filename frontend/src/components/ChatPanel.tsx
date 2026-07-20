import { useEffect, useRef, useState } from 'react'
import { useTriageStore } from '../store'
import { api } from '../api'
import { subscribeToInvestigation } from '../ws'
import type { InvestigationEvent } from '../types'

export default function ChatPanel() {
  const { scenarios, running, feed, incidentId, mode, beginIncident, completeIncident,
    setActiveScenario } = useTriageStore()
  const [alertText, setAlertText] = useState('')
  const feedRef = useRef<HTMLDivElement>(null)
  const unsubscribeRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    feedRef.current?.scrollTo({ top: feedRef.current.scrollHeight, behavior: 'smooth' })
  }, [feed])

  useEffect(() => () => unsubscribeRef.current?.(), [])

  async function start(text: string) {
    const trimmed = text.trim()
    if (!trimmed || running) return
    const { incidentId: id, mode: runMode, scenarioId } = await api.startIncident(trimmed)
    setActiveScenario(scenarioId)
    beginIncident(id, runMode)
    unsubscribeRef.current?.()
    unsubscribeRef.current = subscribeToInvestigation(id, (e: InvestigationEvent) => {
      useTriageStore.getState().applyEvent(e)
      if (e.type === 'REPORT_READY') void finish(id)
    })
  }

  async function finish(id: string) {
    // the report is stored just after REPORT_READY fires; poll briefly
    for (let attempt = 0; attempt < 15; attempt++) {
      try {
        const report = await api.report(id)
        if (report && 'rootCause' in report) {
          const [ledger, events] = await Promise.all([api.ledger(id), api.events(id)])
          completeIncident(report, ledger, events)
          unsubscribeRef.current?.()
          unsubscribeRef.current = null
          return
        }
      } catch { /* not ready yet */ }
      await new Promise((r) => setTimeout(r, 400))
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-slate-800 p-3">
        <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          Incident alert
        </div>
        <textarea
          value={alertText}
          onChange={(e) => setAlertText(e.target.value)}
          placeholder="Paste a production alert here…"
          rows={4}
          className="w-full resize-none rounded-md border border-slate-700 bg-slate-900 p-2 text-xs
            text-slate-200 placeholder-slate-600 focus:border-sky-600 focus:outline-none"
        />
        <div className="mt-2 flex flex-wrap gap-1.5">
          {scenarios.map((s) => (
            <button
              key={s.id}
              onClick={() => setAlertText(s.alertText)}
              disabled={running}
              title={s.description}
              className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-[11px]
                text-slate-400 hover:border-sky-700 hover:text-sky-300 disabled:opacity-40"
            >
              {s.name}
            </button>
          ))}
        </div>
        <button
          onClick={() => void start(alertText)}
          disabled={running || !alertText.trim()}
          className="mt-2 w-full rounded-md bg-sky-600 py-1.5 text-sm font-semibold text-white
            hover:bg-sky-500 disabled:cursor-not-allowed disabled:bg-slate-800 disabled:text-slate-500"
        >
          {running ? 'Investigating…' : 'Start investigation'}
        </button>
      </div>

      <div className="flex items-center justify-between px-3 pt-2">
        <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          Agent reasoning
        </span>
        {incidentId && (
          <span className="text-[10px] text-slate-600">
            {incidentId} · <span className={mode === 'llm' ? 'text-violet-400' : 'text-emerald-500'}>
              {mode === 'llm' ? 'LLM agent' : 'scripted'}
            </span>
          </span>
        )}
      </div>
      <div ref={feedRef} className="feed-scroll min-h-0 flex-1 overflow-y-auto p-3 font-mono text-[11px] leading-relaxed">
        {feed.length === 0 && (
          <div className="mt-6 text-center text-slate-600">
            Pick a preset alert and press Start.<br />The agent&apos;s thinking streams here.
          </div>
        )}
        {feed.map((item, i) =>
          item.kind === 'thought' ? (
            <div key={i} className="mb-1.5 text-slate-300">
              <span className="mr-1 text-sky-500">›</span>
              {item.text}
            </div>
          ) : (
            <div key={i} className="mb-1.5 rounded border-l-2 border-amber-600/60 bg-slate-900/70 px-2 py-1 text-slate-400">
              <span className="text-amber-500">⚙ {item.tool}</span>
              <span className="ml-1 text-slate-600">#{item.ledgerId}</span>
              <div className="text-slate-500">{item.text}</div>
            </div>
          ),
        )}
        {running && <div className="animate-pulse text-sky-600">▋</div>}
      </div>
    </div>
  )
}
