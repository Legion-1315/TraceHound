type Props = {
  title: string
  value: string | number
}

export default function KPICard({ title, value }: Props) {
  return (
    <div className="rounded-md border border-slate-800 bg-[#0d1728] p-3">
      <p className="text-[11px] uppercase tracking-wide text-slate-500">
        {title}
      </p>

      <h2 className="mt-2 text-2xl font-bold text-slate-100">
        {value}
      </h2>
    </div>
  )
}