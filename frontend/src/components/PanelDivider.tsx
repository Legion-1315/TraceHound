import { useRef, useState } from 'react'

type Props = {
  label: string
  width: number
  /** Absolute set, used by dragging (measured from the width captured at pointer-down). */
  onWidth: (width: number) => void
  /** Relative nudge, used by the keyboard so repeats compose instead of fighting a stale prop. */
  onNudge: (delta: number) => void
  onReset: () => void
  /** +1 when dragging right grows the panel (left panel), -1 when it shrinks it (right panel). */
  direction: 1 | -1
}

/**
 * Draggable divider between two panels. Drag with the mouse, nudge with the arrow
 * keys (Shift for a bigger step), or double-click / press Home to reset to the default.
 */
export default function PanelDivider({ label, width, onWidth, onNudge, onReset, direction }: Props) {
  const drag = useRef<{ x: number; width: number } | null>(null)
  const [dragging, setDragging] = useState(false)

  function onPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    e.preventDefault()
    drag.current = { x: e.clientX, width }
    // Capture keeps the drag alive when the pointer outruns this 6px strip.
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* no active pointer */ }
    setDragging(true)
    // Keep the resize cursor and kill text selection for the whole drag, not just
    // while the pointer happens to be over this 6px strip.
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }

  function onPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    if (!drag.current) return
    onWidth(drag.current.width + direction * (e.clientX - drag.current.x))
  }

  function onPointerUp(e: React.PointerEvent<HTMLDivElement>) {
    if (!drag.current) return
    drag.current = null
    setDragging(false)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId)
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLDivElement>) {
    const step = e.shiftKey ? 48 : 16
    if (e.key === 'ArrowLeft') {
      e.preventDefault()
      onNudge(direction * -step)
    } else if (e.key === 'ArrowRight') {
      e.preventDefault()
      onNudge(direction * step)
    } else if (e.key === 'Home') {
      e.preventDefault()
      onReset()
    }
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(width)}
      tabIndex={0}
      title={`${label} — drag, arrow keys, or double-click to reset`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      onDoubleClick={onReset}
      onKeyDown={onKeyDown}
      className="group relative w-1.5 shrink-0 cursor-col-resize touch-none outline-none"
    >
      {/* The visible hairline — replaces the panel borders it sits between. */}
      <span
        className={`absolute inset-y-0 left-1/2 w-px -translate-x-1/2 transition-colors ${
          dragging ? 'bg-sky-400' : 'bg-slate-800 group-hover:bg-sky-600 group-focus-visible:bg-sky-500'
        }`}
      />
      {/* Grip dots, revealed on hover so the affordance is discoverable. */}
      <span
        className={`absolute left-1/2 top-1/2 flex h-8 w-1.5 -translate-x-1/2 -translate-y-1/2 flex-col items-center justify-center gap-1 transition-opacity ${
          dragging ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
        }`}
      >
        <span className="h-1 w-1 rounded-full bg-sky-400" />
        <span className="h-1 w-1 rounded-full bg-sky-400" />
        <span className="h-1 w-1 rounded-full bg-sky-400" />
      </span>
    </div>
  )
}
