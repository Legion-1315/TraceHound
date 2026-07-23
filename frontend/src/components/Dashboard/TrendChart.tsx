import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"

type Props = {
  data: {
    name: string
    value: number
  }[]
}

export default function TrendChart({ data }: Props) {
  return (
    <div className="rounded-md border border-slate-800 bg-[#0d1728] p-3">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-400">
        Incident Volume — last 8 weeks
      </h3>

      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -20 }}>
            <defs>
              <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#38bdf8" stopOpacity={0.5} />
                <stop offset="100%" stopColor="#38bdf8" stopOpacity={0} />
              </linearGradient>
            </defs>

            <CartesianGrid stroke="#1e293b" vertical={false} />

            <XAxis
              dataKey="name"
              stroke="#64748b"
              tick={{ fontSize: 10 }}
              tickLine={false}
            />

            <YAxis
              stroke="#64748b"
              tick={{ fontSize: 10 }}
              tickLine={false}
              allowDecimals={false}
            />

            <Tooltip
              contentStyle={{
                background: "#0f172a",
                border: "1px solid #1e293b",
                borderRadius: 6,
                fontSize: 12,
              }}
            />

            <Area
              type="monotone"
              dataKey="value"
              stroke="#38bdf8"
              strokeWidth={2}
              fill="url(#trendFill)"
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
