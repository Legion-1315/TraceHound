import { useState } from 'react'
import { useTriageStore } from '../store'
import HypothesesTab from './HypothesesTab'
import ReportTab from './ReportTab'
import ReplayTab from './ReplayTab'

const TABS = ['Hypotheses', 'Report', 'Replay'] as const
type Tab = (typeof TABS)[number]

export default function RightPanel() {
  const [tab, setTab] = useState<Tab>('Hypotheses')
  const report = useTriageStore((s) => s.report)

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex border-b border-slate-800">
        {TABS.map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`flex-1 py-2 text-xs font-semibold uppercase tracking-wider transition-colors ${
              tab === t
                ? 'border-b-2 border-sky-500 text-sky-300'
                : 'text-slate-500 hover:text-slate-300'
            }`}
          >
            {t}
            {t === 'Report' && report && <span className="ml-1 text-emerald-500">●</span>}
          </button>
        ))}
      </div>
      <div className="tab-scroll min-h-0 flex-1 overflow-y-auto">
        {tab === 'Hypotheses' && <HypothesesTab />}
        {tab === 'Report' && <ReportTab />}
        {tab === 'Replay' && <ReplayTab />}
      </div>
    </div>
  )
}
