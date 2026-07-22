import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from "recharts"

type Props = {
  data: {
    name: string
    value: number
  }[]
}

const COLORS = [
  "#38bdf8",
  "#22c55e",
  "#f59e0b",
  "#ef4444",
  "#a855f7",
]

export default function CategoryChart({ data }: Props) {
  return (
    <div className="rounded-md border border-slate-800 bg-[#0d1728] p-3">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-400">
        Incidents by Category
      </h3>

      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="value"
              nameKey="name"
              innerRadius={50}
              outerRadius={80}
              paddingAngle={3}
            >
              {data.map((_, index) => (
                <Cell
                  key={index}
                  fill={COLORS[index % COLORS.length]}
                />
              ))}
            </Pie>

            <Tooltip />
          </PieChart>
        </ResponsiveContainer>
      </div>

      <div className="mt-3 space-y-1">
        {data.map((item, index) => (
          <div
            key={item.name}
            className="flex items-center justify-between text-xs"
          >
            <div className="flex items-center gap-2">
              <span
                className="h-3 w-3 rounded-full"
                style={{
                  backgroundColor:
                    COLORS[index % COLORS.length],
                }}
              />
              <span className="text-slate-300">
                {item.name}
              </span>
            </div>

            <span className="text-slate-400">
              {item.value}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}