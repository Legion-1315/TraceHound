import { useTriageStore } from '../store'

export default function TicketModal() {
  const { report, setTicketOpen } = useTriageStore()
  if (!report) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={() => setTicketOpen(false)}>
      <div
        className="w-[560px] max-h-[80vh] overflow-y-auto rounded-lg border border-slate-700 bg-slate-950 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-800 bg-slate-900 px-4 py-2.5">
          <div className="flex items-center gap-2">
            <span className="rounded bg-emerald-800 px-1.5 py-0.5 text-[10px] font-bold text-emerald-100">SN</span>
            <span className="text-sm font-semibold text-slate-200">New Incident — ServiceNow (mock)</span>
          </div>
          <button onClick={() => setTicketOpen(false)} className="text-slate-500 hover:text-slate-200">✕</button>
        </div>
        <div className="space-y-3 p-4 text-xs">
          {[
            ['Number', `INC${String(Math.floor(Math.random() * 900000) + 100000)}`],
            ['Caller', 'triage-copilot (automated)'],
            ['Assignment group', report.owningTeam],
            ['Priority', '2 - High'],
            ['Category', 'Application / Data Quality'],
            ['Configuration item', report.blastRadius[0]?.impactedService ?? 'regulatory-gateway'],
          ].map(([label, value]) => (
            <div key={label} className="flex">
              <span className="w-36 shrink-0 text-slate-500">{label}</span>
              <span className="text-slate-200">{value}</span>
            </div>
          ))}
          <div>
            <div className="mb-1 text-slate-500">Short description</div>
            <div className="rounded border border-slate-800 bg-slate-900 p-2 text-slate-200">
              {report.rootCause.slice(0, 140)}{report.rootCause.length > 140 ? '…' : ''}
            </div>
          </div>
          <div>
            <div className="mb-1 text-slate-500">Description (from triage report {report.incidentId})</div>
            <div className="whitespace-pre-wrap rounded border border-slate-800 bg-slate-900 p-2 font-mono text-[10px] leading-relaxed text-slate-300">
              {`ROOT CAUSE (${report.confidencePct}% confidence)\n${report.rootCause}\n\nREMEDIATION (awaiting approval)\n${report.remediation.map((r) => '- ' + r).join('\n')}\n\nEvidence: ${report.causalChain.length} cited ledger entries · ${report.toolCallCount} tool calls · ${(report.elapsedMs / 1000).toFixed(1)}s`}
            </div>
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <button onClick={() => setTicketOpen(false)}
              className="rounded border border-slate-700 px-3 py-1.5 text-slate-400 hover:text-slate-200">
              Cancel
            </button>
            <button onClick={() => setTicketOpen(false)}
              className="rounded bg-emerald-700 px-3 py-1.5 font-semibold text-white hover:bg-emerald-600">
              Submit (mock)
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
