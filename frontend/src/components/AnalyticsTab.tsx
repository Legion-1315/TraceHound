import KPICard from "./Dashboard/KPICard"
import CategoryChart from "./Dashboard/CategoryChart"
import ServiceChart from "./Dashboard/ServiceChart"
import { dashboardData } from "../Mock/DashboardData"


export default function AnalyticsTab() {
  const { summary, categories, services } = dashboardData

  return (
    <div className="space-y-4 p-4">
      <div className="grid grid-cols-2 gap-3">
        <KPICard
          title="Incidents"
          value={summary.totalIncidents}
        />

        <KPICard
          title="Avg MTTR"
          value={summary.avgMttr}
        />

        <KPICard
          title="AI Success"
          value={summary.aiSuccess}
        />

        <KPICard
          title="Critical"
          value={summary.criticalIncidents}
        />
      </div>

      <CategoryChart data={categories} />

      <ServiceChart data={services} />
    </div>
  )
}