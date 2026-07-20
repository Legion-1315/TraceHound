import { useTriageStore } from '../store'

export default function HypothesesTab() {
  const hypotheses = useTriageStore((s) => s.hypotheses)
  const sorted = [...hypotheses].sort((a, b) => b.probability - a.probability)

  if (sorted.length === 0) {
    return (
      <div className="p-6 text-center text-xs text-slate-600">
        Ranked hypotheses appear here once the agent has scanned change events and incident memory.
      </div>
    )
  }

  return (
    <div className="space-y-3 p-3">
      {sorted.map((h) => (
        <div key={h.text} className="rounded-md border border-slate-800 bg-slate-900/60 p-2.5">
          <div className="mb-1.5 flex items-start justify-between gap-2">
            <span className="text-xs leading-snug text-slate-300">{h.text}</span>
            <span className="shrink-0 font-mono text-sm font-bold text-sky-300">{h.probability}%</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-slate-800">
            <div
              className="hyp-bar h-full rounded-full"
              style={{
                width: `${h.probability}%`,
                background: h.probability >= 60 ? '#f87171' : h.probability >= 30 ? '#fbbf24' : '#38bdf8',
              }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}
