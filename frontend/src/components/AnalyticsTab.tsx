import { useEffect, useState } from "react"
import KPICard from "./Dashboard/KPICard"
import CategoryChart from "./Dashboard/CategoryChart"
import ServiceChart from "./Dashboard/ServiceChart"
import TrendChart from "./Dashboard/TrendChart"
import { api } from "../api"
import { useTriageStore } from "../store"
import type { DashboardData } from "../types"

export default function AnalyticsTab() {
  const [data, setData] = useState<DashboardData | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Re-fetch whenever an investigation finishes — the completed incident is
  // appended to the historical corpus server-side, so the dashboards move.
  const incidentId = useTriageStore((s) => s.report?.incidentId ?? null)

  useEffect(() => {
    let cancelled = false
    api
      .analytics()
      .then((d) => {
        if (!cancelled) {
          setData(d)
          setError(null)
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "failed to load analytics")
      })
    return () => {
      cancelled = true
    }
  }, [incidentId])

  if (error) {
    return (
      <div className="p-6 text-center text-xs text-rose-400">
        Could not load analytics: {error}
      </div>
    )
  }

  if (!data) {
    return (
      <div className="p-6 text-center text-xs text-slate-600">
        Loading incident history…
      </div>
    )
  }

  const { summary, categories, services, trend } = data

  return (
    <div className="space-y-4 p-4">
      <div className="flex items-center justify-between">
        <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">
          Historical incident analytics
        </span>
        <span className="text-[10px] text-slate-600">
          {summary.totalIncidents} incidents on record
        </span>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <KPICard title="Incidents" value={summary.totalIncidents} />
        <KPICard title="Avg MTTR" value={summary.avgMttr} />
        <KPICard title="AI Success" value={summary.aiSuccess} />
        <KPICard title="Critical" value={summary.criticalIncidents} />
      </div>

      <TrendChart data={trend} />

      <CategoryChart data={categories} />

      <ServiceChart data={services} />
    </div>
  )
}
