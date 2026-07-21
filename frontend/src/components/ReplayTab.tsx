import { useTriageStore } from '../store'

export default function ReplayTab() {
  const { replayEvents, replayIndex, setReplayIndex, report } = useTriageStore()

  if (!report || replayEvents.length === 0) {
    return (
      <div className="p-6 text-center text-xs text-slate-600">
        After an investigation completes you can scrub back through every step here — the canvas follows the scrubber.
      </div>
    )
  }

  const max = replayEvents.length - 1
  const position = replayIndex ?? max
  const visible = replayEvents.slice(0, position + 1)

  return (
    <div className="flex h-full flex-col p-3">
      <div className="mb-2" data-tour="replay-scrubber">
        <div className="mb-1 flex justify-between text-[11px] text-slate-500">
          <span>Investigation timeline</span>
          <span className="font-mono">{position + 1} / {replayEvents.length}</span>
        </div>
        <input
          type="range"
          min={0}
          max={max}
          value={position}
          onChange={(e) => setReplayIndex(Number(e.target.value))}
          className="w-full accent-violet-500"
        />
        <div className="mt-1 flex gap-1.5">
          <button
            onClick={() => setReplayIndex(0)}
            className="rounded border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400 hover:text-slate-200"
          >⏮ start</button>
          <button
            onClick={() => setReplayIndex(Math.max(0, position - 1))}
            className="rounded border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400 hover:text-slate-200"
          >◀ step</button>
          <button
            onClick={() => setReplayIndex(Math.min(max, position + 1))}
            className="rounded border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400 hover:text-slate-200"
          >step ▶</button>
          <button
            onClick={() => setReplayIndex(null)}
            className="ml-auto rounded border border-violet-800 px-2 py-0.5 text-[10px] text-violet-300 hover:bg-violet-950"
          >exit replay</button>
        </div>
      </div>

      <div className="tab-scroll min-h-0 flex-1 space-y-1 overflow-y-auto font-mono text-[10px] leading-relaxed">
        {visible.map((e, i) => (
          <div
            key={i}
            className={`rounded border px-2 py-1 ${
              i === position ? 'border-violet-700 bg-violet-950/40' : 'border-slate-800/60 bg-slate-900/40'
            }`}
          >
            <span className={
              e.type === 'TOOL_CALL' ? 'text-amber-400'
                : e.type === 'NODE_STATUS' ? 'text-sky-400'
                  : e.type === 'HYPOTHESIS_UPDATE' ? 'text-violet-400'
                    : e.type === 'REPORT_READY' ? 'text-emerald-400' : 'text-slate-400'
            }>
              {e.type}
            </span>
            <span className="ml-1.5 text-slate-400">
              {e.type === 'NODE_STATUS' && `${e.service} → ${e.status}`}
              {e.type === 'AGENT_THOUGHT' && e.text}
              {e.type === 'TOOL_CALL' && `${e.tool} #${e.ledgerId}`}
              {e.type === 'HYPOTHESIS_UPDATE' && `${e.hypotheses?.length} hypotheses re-ranked`}
              {e.type === 'REPORT_READY' && 'triage report finalised'}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
