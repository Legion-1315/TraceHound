import { useEffect, useRef, useState } from 'react'
import { useTriageStore } from '../store'

const SEVERITY_STYLE: Record<string, string> = {
  SEV1: 'bg-rose-900/70 text-rose-200',
  SEV2: 'bg-amber-900/60 text-amber-200',
  SEV3: 'bg-slate-800 text-slate-300',
}

export default function ChatPanel() {
  const {
    scenarios, running, feed, incidentId, mode, startInvestigation, modeInfo, alerts, error, clearError,
    setChaosOpen,
  } = useTriageStore()
  const [alertText, setAlertText] = useState('')
  const feedRef = useRef<HTMLDivElement>(null)
  const live = modeInfo?.estate === 'live'
  const presets = scenarios.filter((s) => s.alertText)

  useEffect(() => {
    feedRef.current?.scrollTo({ top: feedRef.current.scrollHeight, behavior: 'smooth' })
  }, [feed])

  async function start(text: string) {
    const trimmed = text.trim()
    if (!trimmed || running) return
    await startInvestigation(trimmed)
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-slate-800 p-3" data-tour="alert-box">
        {live && (
          <div className="mb-3">
            <div className="mb-1.5 flex items-center justify-between">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
                Firing alerts
              </span>
              <button
                onClick={() => setChaosOpen(true)}
                className="text-[10px] text-slate-500 underline-offset-2 hover:text-amber-300 hover:underline"
                title="Ctrl+Shift+K"
              >
                fault injection
              </button>
            </div>
            {alerts.length === 0 ? (
              <div className="rounded border border-emerald-900/50 bg-emerald-950/20 px-2 py-1.5 text-[11px] text-emerald-400/90">
                No alerts firing — the estate looks healthy. Inject a fault to break something for real.
              </div>
            ) : (
              <ul className="max-h-40 space-y-1 overflow-y-auto">
                {alerts.map((a) => (
                  <li key={a.id} className="flex items-start gap-2 rounded border border-slate-800 bg-slate-900/70 p-1.5">
                    <span className={`mt-0.5 shrink-0 rounded px-1 text-[9px] font-bold ${SEVERITY_STYLE[a.severity] ?? SEVERITY_STYLE.SEV3}`}>
                      {a.severity}
                    </span>
                    <span className="min-w-0 flex-1 text-[11px] leading-snug text-slate-300">{a.text}</span>
                    <button
                      onClick={() => { setAlertText(a.text); void start(a.text) }}
                      disabled={running}
                      className="shrink-0 rounded bg-sky-700 px-1.5 py-0.5 text-[10px] font-semibold text-white hover:bg-sky-600 disabled:bg-slate-800 disabled:text-slate-500"
                    >
                      Investigate
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          {live ? 'Or describe any alert' : 'Incident alert'}
        </div>
        <textarea
          value={alertText}
          onChange={(e) => setAlertText(e.target.value)}
          placeholder={live
            ? 'e.g. "Regulators say our MiFID submissions stopped arriving about 10 minutes ago"'
            : 'Paste a production alert here…'}
          rows={live ? 3 : 4}
          maxLength={4000}
          className="w-full resize-none rounded-md border border-slate-700 bg-slate-900 p-2 text-xs
            text-slate-200 placeholder-slate-600 focus:border-sky-600 focus:outline-none"
        />
        {presets.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5" data-tour="preset-alerts">
            {presets.map((s) => (
              <button
                key={s.id}
                onClick={() => setAlertText(s.alertText)}
                disabled={running}
                title={s.description}
                data-tour={`preset-${s.id}`}
                className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-[11px]
                  text-slate-400 hover:border-sky-700 hover:text-sky-300 disabled:opacity-40"
              >
                {s.name}
              </button>
            ))}
          </div>
        )}
        <button
          onClick={() => void start(alertText)}
          disabled={running || !alertText.trim()}
          data-tour="start-btn"
          className="mt-2 w-full rounded-md bg-sky-600 py-1.5 text-sm font-semibold text-white
            hover:bg-sky-500 disabled:cursor-not-allowed disabled:bg-slate-800 disabled:text-slate-500"
        >
          {running ? 'Investigating…' : 'Start investigation'}
        </button>
        {live && modeInfo && (
          <div className="mt-1.5 text-center text-[10px] text-slate-600">
            {modeInfo.budget.investigationsToday}/{modeInfo.budget.maxInvestigationsPerDay} live investigations today
            · limit {modeInfo.budget.maxPerClientPerHour}/hour per visitor
          </div>
        )}
        {error && (
          <div className="mt-2 flex items-start gap-2 rounded border border-rose-900/70 bg-rose-950/40 p-2 text-[11px] text-rose-200">
            <span className="flex-1 leading-snug">{error}</span>
            <button onClick={clearError} className="text-rose-400 hover:text-rose-200" aria-label="Dismiss">✕</button>
          </div>
        )}
      </div>

      <div className="flex items-center justify-between px-3 pt-2">
        <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          Agent reasoning
        </span>
        {incidentId && (
          <span className="text-[10px] text-slate-600">
            {incidentId} · <span className={mode === 'llm' ? 'text-violet-400' : 'text-emerald-500'}>
              {mode === 'llm' ? `Claude agent${modeInfo?.model ? ` (${modeInfo.model})` : ''}` : 'scripted replay'}
            </span>
          </span>
        )}
      </div>
      <div
        ref={feedRef}
        data-tour="reasoning-feed"
        className="feed-scroll min-h-0 flex-1 overflow-y-auto p-3 font-mono text-[11px] leading-relaxed"
      >
        {feed.length === 0 && (
          <div className="mt-6 text-center text-slate-600">
            {live
              ? <>Pick a firing alert or describe one, then press Start.<br />The agent&apos;s reasoning streams here live.</>
              : <>Pick a preset alert and press Start.<br />The agent&apos;s thinking streams here.</>}
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
