import { useTriageStore, type RightTab } from '../store'
import HypothesesTab from './HypothesesTab'
import ReportTab from './ReportTab'
import ReplayTab from './ReplayTab'
import AnalyticsTab from './AnalyticsTab'

const TABS: RightTab[] = ['Hypotheses', 'Report', 'Replay','Analytics']

export default function RightPanel() {
  const { activeTab, setActiveTab, report } = useTriageStore()

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex border-b border-slate-800">
        {TABS.map((t) => (
          <button
            key={t}
            onClick={() => setActiveTab(t)}
            data-tour={`tab-${t.toLowerCase()}`}
            className={`flex-1 py-2 text-xs font-semibold uppercase tracking-wider transition-colors ${
              activeTab === t
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
        {activeTab === 'Hypotheses' && <HypothesesTab />}
        {activeTab === 'Report' && <ReportTab />}
        {activeTab === 'Replay' && <ReplayTab />}
        {activeTab === 'Analytics' && <AnalyticsTab />}
      </div>
    </div>
  )
}
