import { useTriageStore } from '../store'
import { api } from '../api'

export default function ChaosPanel() {
  const { scenarios, activeScenario, setActiveScenario, setChaosOpen } = useTriageStore()

  async function activate(id: string) {
    const res = await api.activateScenario(id)
    setActiveScenario(res.active)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={() => setChaosOpen(false)}>
      <div
        className="w-[480px] rounded-lg border border-rose-900 bg-slate-950 p-4 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-bold uppercase tracking-wider text-rose-400">
            ⚡ Chaos panel — fault injection
          </h2>
          <button onClick={() => setChaosOpen(false)} className="text-slate-500 hover:text-slate-200">✕</button>
        </div>
        <p className="mb-3 text-[11px] text-slate-500">
          Arms a fault in the simulated estate. Telemetry rewrites immediately; then paste (or preset) the
          matching alert and start the investigation.
        </p>
        <div className="space-y-2">
          {scenarios.map((s) => (
            <button
              key={s.id}
              onClick={() => void activate(s.id)}
              className={`w-full rounded-md border p-2.5 text-left transition-colors ${
                activeScenario === s.id
                  ? 'border-rose-700 bg-rose-950/40'
                  : 'border-slate-800 bg-slate-900 hover:border-rose-900'
              }`}
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-200">{s.name}</span>
                {activeScenario === s.id && (
                  <span className="rounded bg-rose-900 px-1.5 py-0.5 text-[10px] font-bold text-rose-200">ARMED</span>
                )}
              </div>
              <div className="mt-0.5 text-[11px] leading-snug text-slate-500">{s.description}</div>
            </button>
          ))}
          <button
            onClick={() => void activate('none')}
            className="w-full rounded-md border border-emerald-900 bg-emerald-950/30 p-2 text-xs font-semibold text-emerald-400 hover:bg-emerald-950/60"
          >
            Reset estate to healthy
          </button>
        </div>
      </div>
    </div>
  )
}
