import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
} from "recharts"

type Props = {
  data: {
    name: string
    value: number
  }[]
}

export default function ServiceChart({ data }: Props) {
  return (
    <div className="rounded-md border border-slate-800 bg-[#0d1728] p-3">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-400">
        Incidents by Service
      </h3>

      <div className="h-72">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            layout="vertical"
            margin={{ left: 30 }}
          >
            <XAxis type="number" stroke="#64748b" />

            <YAxis
              type="category"
              dataKey="name"
              stroke="#94a3b8"
              width={120}
            />

            <Tooltip />

            <Bar
              dataKey="value"
              fill="#38bdf8"
              radius={[4, 4, 4, 4]}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}