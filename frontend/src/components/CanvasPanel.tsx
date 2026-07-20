import { useMemo } from 'react'
import { ReactFlow, Background, BackgroundVariant, Handle, Position, type Edge, type Node, type NodeProps } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useTriageStore } from '../store'
import type { NodeStatus, ServiceDef } from '../types'

const STATUS_STYLES: Record<NodeStatus, { border: string; bg: string; label: string; labelColor: string }> = {
  UNKNOWN: { border: '#475569', bg: '#0f172a', label: 'unknown', labelColor: '#64748b' },
  PROBING: { border: '#3b82f6', bg: '#0c1830', label: 'probing', labelColor: '#60a5fa' },
  CLEARED: { border: '#22c55e', bg: '#0c2016', label: 'cleared', labelColor: '#4ade80' },
  SUSPECT: { border: '#f59e0b', bg: '#231a08', label: 'suspect', labelColor: '#fbbf24' },
  ROOT_CAUSE: { border: '#ef4444', bg: '#2a0e0e', label: 'ROOT CAUSE', labelColor: '#f87171' },
}

type ServiceNodeData = { service: ServiceDef; status: NodeStatus }

function ServiceNode({ data }: NodeProps) {
  const { service, status } = data as ServiceNodeData
  const s = STATUS_STYLES[status] ?? STATUS_STYLES.UNKNOWN
  const pulseClass = status === 'PROBING' ? 'node-probing' : status === 'ROOT_CAUSE' ? 'node-rootcause' : ''
  return (
    <div
      className={`w-[190px] rounded-lg border-2 px-3 py-2 transition-colors duration-500 ${pulseClass}`}
      style={{ borderColor: s.border, background: s.bg }}
    >
      <Handle type="target" position={Position.Left} style={{ background: '#334155' }} />
      <div className="text-[13px] font-semibold text-slate-100">{service.name}</div>
      <div className="text-[10px] text-slate-500">{service.team}</div>
      <div className="mt-1 inline-block rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide"
        style={{ color: s.labelColor, background: `${s.border}22` }}>
        {s.label}
      </div>
      <Handle type="source" position={Position.Right} style={{ background: '#334155' }} />
    </div>
  )
}

const nodeTypes = { service: ServiceNode }

/** Fixed left-to-right layout in flow order; ref-data (the shared feeder) sits below, confirmations at bottom. */
const POSITIONS: Record<string, { x: number; y: number }> = {
  'trade-capture': { x: 0, y: 60 },
  'enrichment-service': { x: 260, y: 60 },
  'submission-builder': { x: 520, y: 60 },
  'regulatory-gateway': { x: 780, y: 60 },
  'ref-data-service': { x: 130, y: 240 },
  'trade-confirmations': { x: 420, y: 300 },
}

export default function CanvasPanel() {
  const { topology, nodeStatus, activeScenario, scenarios, replayIndex } = useTriageStore()

  const flowName = topology?.flows.find((f) => f.id === 'mifid-reporting')?.name ?? ''
  const scenarioName = scenarios.find((s) => s.id === activeScenario)?.name

  const nodes: Node[] = useMemo(() => {
    if (!topology) return []
    return topology.services.map((service, i) => ({
      id: service.id,
      type: 'service',
      position: POSITIONS[service.id] ?? { x: (i % 3) * 260, y: Math.floor(i / 3) * 140 },
      data: { service, status: nodeStatus[service.id] ?? 'UNKNOWN' },
      draggable: true,
    }))
  }, [topology, nodeStatus])

  const edges: Edge[] = useMemo(() => {
    if (!topology) return []
    const result: Edge[] = []
    for (const service of topology.services) {
      for (const target of service.downstream) {
        const sourceStatus = nodeStatus[service.id] ?? 'UNKNOWN'
        const targetStatus = nodeStatus[target] ?? 'UNKNOWN'
        const active = sourceStatus !== 'UNKNOWN' && targetStatus !== 'UNKNOWN'
        const implicated = sourceStatus === 'ROOT_CAUSE' || sourceStatus === 'SUSPECT'
        result.push({
          id: `${service.id}->${target}`,
          source: service.id,
          target,
          animated: active,
          style: {
            stroke: implicated ? '#b45309' : active ? '#0369a1' : '#1e293b',
            strokeWidth: implicated ? 2.5 : 1.5,
          },
        })
      }
    }
    return result
  }, [topology, nodeStatus])

  return (
    <div className="relative h-full">
      <div className="absolute left-3 top-3 z-10 flex items-center gap-2">
        <span className="rounded-full border border-sky-800 bg-sky-950/80 px-3 py-1 text-xs font-semibold text-sky-300">
          {flowName || 'Business flow'}
        </span>
        {scenarioName && (
          <span className="rounded-full border border-rose-900 bg-rose-950/80 px-3 py-1 text-[11px] text-rose-300">
            fault armed: {scenarioName}
          </span>
        )}
        {replayIndex !== null && (
          <span className="rounded-full border border-violet-800 bg-violet-950/80 px-3 py-1 text-[11px] text-violet-300">
            REPLAY MODE
          </span>
        )}
      </div>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.25 }}
        proOptions={{ hideAttribution: false }}
        colorMode="dark"
        nodesConnectable={false}
        elementsSelectable={false}
      >
        <Background variant={BackgroundVariant.Dots} color="#1e293b" gap={24} />
      </ReactFlow>
    </div>
  )
}
