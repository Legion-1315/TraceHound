import { useState } from 'react'
import { useTriageStore } from '../store'

export default function ReportTab() {
  const { report, ledger, running, setTicketOpen } = useTriageStore()
  const [expanded, setExpanded] = useState<number | null>(null)

  if (!report) {
    return (
      <div className="p-6 text-center text-xs text-slate-600">
        {running ? 'Investigation in progress — the report lands here when the causal chain is verified.'
          : 'No report yet. Start an investigation.'}
      </div>
    )
  }

  const entryById = new Map(ledger.map((e) => [e.id, e]))

  return (
    <div className="space-y-4 p-3 text-xs">
      <section className="rounded-md border border-rose-900/70 bg-rose-950/30 p-3">
        <div className="mb-1 flex items-center justify-between">
          <span className="font-bold uppercase tracking-wider text-rose-300">Root cause</span>
          <span className="rounded bg-rose-900/60 px-2 py-0.5 font-mono text-[11px] font-bold text-rose-200">
            {report.confidencePct}% confidence
          </span>
        </div>
        <p className="leading-relaxed text-slate-200">{report.rootCause}</p>
        <p className="mt-2 text-[11px] leading-relaxed text-slate-400">{report.summary}</p>
        <div className="mt-2 text-[11px] text-slate-400">
          Owner: <span className="text-slate-200">{report.owningTeam}</span>
          {report.owningSlack && <span className="ml-1 text-sky-400">{report.owningSlack}</span>}
        </div>
      </section>

      <section>
        <h3 className="mb-1.5 font-bold uppercase tracking-wider text-slate-400">
          Evidence chain <span className="font-normal normal-case text-slate-600">(click a link to see the cited ledger entry)</span>
        </h3>
        <ol className="space-y-1.5">
          {report.causalChain.map((link, i) => {
            const entry = entryById.get(link.ledgerId)
            const open = expanded === i
            return (
              <li key={i} className="rounded border border-slate-800 bg-slate-900/60">
                <button
                  onClick={() => setExpanded(open ? null : i)}
                  className="flex w-full items-start gap-2 p-2 text-left hover:bg-slate-800/50"
                >
                  <span className="mt-0.5 shrink-0 rounded bg-slate-800 px-1.5 font-mono text-[10px] text-sky-400">
                    #{link.ledgerId}
                  </span>
                  <span className="leading-snug text-slate-300">{link.claim}</span>
                </button>
                {open && entry && (
                  <div className="border-t border-slate-800 bg-slate-950/70 p-2 font-mono text-[10px] leading-relaxed">
                    <div className="text-amber-400">{entry.tool}({JSON.stringify(entry.params)})</div>
                    <div className="mt-1 text-slate-400">{entry.resultSummary}</div>
                    {entry.agentInference && (
                      <div className="mt-1 text-sky-300">inference: {entry.agentInference}</div>
                    )}
                  </div>
                )}
              </li>
            )
          })}
        </ol>
      </section>

      {report.blastRadius.length > 0 && (
        <section>
          <h3 className="mb-1.5 font-bold uppercase tracking-wider text-slate-400">Blast radius</h3>
          <div className="space-y-1">
            {report.blastRadius.map((b, i) => (
              <div key={i} className="flex items-center justify-between rounded border border-amber-900/50 bg-amber-950/20 px-2 py-1.5">
                <div>
                  <span className="text-slate-200">{b.flowName}</span>
                  <span className="ml-1.5 text-[10px] text-slate-500">via {b.impactedService} · {b.owningTeam}</span>
                </div>
                <span className={`rounded px-1.5 py-0.5 text-[10px] font-bold uppercase ${
                  b.criticality === 'high' ? 'bg-rose-900/60 text-rose-300' : 'bg-amber-900/60 text-amber-300'
                }`}>
                  {b.criticality}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}

      <section>
        <h3 className="mb-1.5 font-bold uppercase tracking-wider text-slate-400">
          Remediation <span className="font-normal normal-case text-amber-500">— awaiting human approval</span>
        </h3>
        <ul className="space-y-1">
          {report.remediation.map((r, i) => (
            <li key={i} className="rounded border border-slate-800 bg-slate-900/60 p-2 leading-snug text-slate-300">
              {r}
            </li>
          ))}
        </ul>
      </section>

      {report.ruledOut.length > 0 && (
        <section>
          <h3 className="mb-1.5 font-bold uppercase tracking-wider text-slate-400">Ruled out</h3>
          <ul className="space-y-0.5">
            {report.ruledOut.map((r, i) => (
              <li key={i} className="text-[11px] text-slate-500">
                <span className="mr-1 text-emerald-600">✓</span>{r}
              </li>
            ))}
          </ul>
        </section>
      )}

      <button
        onClick={() => setTicketOpen(true)}
        className="w-full rounded-md border border-sky-700 bg-sky-950/50 py-2 text-sm font-semibold text-sky-300 hover:bg-sky-900/50"
      >
        Create ITSM ticket
      </button>
    </div>
  )
}
