import { useEffect, useState } from 'react'
import { useTriageStore } from '../store'
import { api } from '../api'

/**
 * Fault injection (Ctrl+Shift+K). In the live estate this really breaks a running service;
 * in the simulated estate it arms a scenario's telemetry overlay.
 */
export default function ChaosPanel() {
  const { scenarios, activeFaults, setActiveFaults, setChaosOpen, modeInfo } = useTriageStore()
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const live = modeInfo?.estate === 'live'

  useEffect(() => {
    api.chaosState().then((s) => setActiveFaults(s.activeFaults ?? [])).catch(() => {})
  }, [setActiveFaults])

  async function run(id: string | null) {
    setBusy(id ?? 'reset')
    setMessage(null)
    try {
      if (id) await api.injectFault(id)
      else await api.resetFaults()
      const s = await api.chaosState()
      setActiveFaults(s.activeFaults ?? [])
      setMessage(id
        ? (live ? 'Fault injected. Symptoms take ~30-60s to show up in metrics and alerts.' : 'Scenario armed.')
        : 'All services restored.')
    } catch (err) {
      setMessage(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="fixed inset-0 z-[80] flex items-center justify-center bg-black/60" onClick={() => setChaosOpen(false)}>
      <div className="w-[460px] rounded-lg border border-amber-800/70 bg-slate-900 p-4 shadow-2xl" onClick={(e) => e.stopPropagation()}>
        <div className="mb-1 flex items-center justify-between">
          <h2 className="text-sm font-bold text-amber-300">Fault injection</h2>
          <button onClick={() => setChaosOpen(false)} className="text-slate-500 hover:text-slate-200" aria-label="Close">✕</button>
        </div>
        <p className="mb-3 text-[11px] leading-relaxed text-slate-400">
          {live
            ? 'Breaks a real running service. The agent never sees these switches — only the logs, metrics, changes and schemas the broken behaviour produces.'
            : 'Simulated estate: arms a scenario’s telemetry signature.'}
        </p>
        <ul className="space-y-1.5">
          {scenarios.map((s) => {
            const on = activeFaults.includes(s.id)
            return (
              <li key={s.id} className="flex items-center gap-2 rounded border border-slate-800 bg-slate-950/60 p-2">
                <div className="min-w-0 flex-1">
                  <div className="text-[12px] font-semibold text-slate-200">
                    {s.name}
                    {on && <span className="ml-2 rounded bg-rose-900/70 px-1 text-[9px] font-bold text-rose-200">ACTIVE</span>}
                  </div>
                  <div className="text-[10px] leading-snug text-slate-500">{s.description}</div>
                </div>
                <button
                  onClick={() => void run(s.id)}
                  disabled={busy !== null || on}
                  className="shrink-0 rounded border border-amber-700 px-2 py-1 text-[11px] text-amber-300 hover:bg-amber-900/30 disabled:opacity-40"
                >
                  {busy === s.id ? '…' : 'Inject'}
                </button>
              </li>
            )
          })}
        </ul>
        <div className="mt-3 flex items-center justify-between">
          <span className="text-[11px] text-slate-400">{message}</span>
          <button
            onClick={() => void run(null)}
            disabled={busy !== null}
            className="rounded bg-emerald-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-emerald-600 disabled:opacity-40"
          >
            {busy === 'reset' ? 'Restoring…' : 'Restore all'}
          </button>
        </div>
      </div>
    </div>
  )
}
