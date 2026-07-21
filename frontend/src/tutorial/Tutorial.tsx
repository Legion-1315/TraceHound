import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useTriageStore } from '../store'
import { api } from '../api'
import { TUTORIAL_STEPS, type Step, type TourAction } from './steps'

type Rect = { top: number; left: number; width: number; height: number }

const TOOLTIP_W = 360
const TOOLTIP_PAD = 12

export default function Tutorial() {
  const {
    tourActive, tourStep, nextStep, prevStep, endTour, report, setActiveTab, startInvestigation,
    setActiveScenario, setChaosOpen,
  } = useTriageStore()

  const step: Step | undefined = tourActive ? TUTORIAL_STEPS[tourStep] : undefined
  const [rect, setRect] = useState<Rect | null>(null)
  const executedFor = useRef<number>(-1)

  const runAction = useCallback(async (action: TourAction) => {
    switch (action.kind) {
      case 'arm-fault': {
        const res = await api.activateScenario(action.scenarioId)
        setActiveScenario(res.active)
        break
      }
      case 'start-investigation':
        await startInvestigation(action.alertText)
        break
      case 'switch-tab':
        setActiveTab(action.tab)
        break
      case 'open-chaos':
        setChaosOpen(true)
        break
      case 'close-chaos':
        setChaosOpen(false)
        break
      case 'wait-for-report':
        break
    }
  }, [setActiveScenario, setActiveTab, setChaosOpen, startInvestigation])

  // Run the step's onEnter action exactly once per step entry.
  useEffect(() => {
    if (!step) return
    if (executedFor.current === tourStep) return
    executedFor.current = tourStep
    if (step.onEnter) void runAction(step.onEnter)
  }, [step, tourStep, runAction])

  // Reset the "already-executed" marker whenever the tour restarts.
  useEffect(() => {
    if (!tourActive) executedFor.current = -1
  }, [tourActive])

  // Track the target element's bounding rect; update on resize / scroll / DOM churn.
  useLayoutEffect(() => {
    if (!step || step.target === 'none') {
      setRect(null)
      return
    }
    let rafId = 0
    const measure = () => {
      const el = document.querySelector<HTMLElement>(`[data-tour="${step.target}"]`)
      if (!el) {
        // The target may not exist yet (e.g. after switching tabs). Retry a few times.
        rafId = requestAnimationFrame(measure)
        return
      }
      const r = el.getBoundingClientRect()
      setRect({ top: r.top, left: r.left, width: r.width, height: r.height })
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(document.body)
    window.addEventListener('scroll', measure, true)
    return () => {
      cancelAnimationFrame(rafId)
      observer.disconnect()
      window.removeEventListener('scroll', measure, true)
    }
  }, [step, tourActive])

  if (!step) return null

  const waiting = step.waitFor === 'report' && !report
  const isLast = tourStep === TUTORIAL_STEPS.length - 1
  const tooltipPos = rect ? positionTooltip(rect, step.side ?? 'auto') : centerTooltip()

  return (
    <div className="fixed inset-0 z-[100]" role="dialog" aria-label="Tutorial">
      {/* dim backdrop with a rectangular cutout using inset box-shadow */}
      {rect ? (
        <div
          className="pointer-events-none fixed rounded-lg transition-all duration-300 ease-out"
          style={{
            top: rect.top - 6,
            left: rect.left - 6,
            width: rect.width + 12,
            height: rect.height + 12,
            boxShadow: '0 0 0 4px rgba(56,189,248,0.9), 0 0 0 9999px rgba(2,6,20,0.78)',
          }}
        />
      ) : (
        <div className="pointer-events-none fixed inset-0 bg-slate-950/78" />
      )}

      {/* tooltip card */}
      <div
        className="pointer-events-auto absolute rounded-lg border border-sky-800 bg-slate-900 shadow-2xl"
        style={{ top: tooltipPos.top, left: tooltipPos.left, width: TOOLTIP_W }}
      >
        <div className="flex items-center justify-between border-b border-slate-800 px-3 py-2">
          <span className="text-[10px] font-bold uppercase tracking-wider text-sky-400">
            Step {tourStep + 1} of {TUTORIAL_STEPS.length}
          </span>
          <button
            onClick={endTour}
            className="text-[11px] text-slate-500 hover:text-slate-300"
            title="Exit tour"
          >
            Skip tour ✕
          </button>
        </div>
        <div className="p-3">
          <h3 className="mb-1.5 text-sm font-bold text-slate-100">{step.title}</h3>
          <p className="text-[12px] leading-relaxed text-slate-300">{step.body}</p>
        </div>
        <div className="flex items-center justify-between border-t border-slate-800 px-3 py-2">
          <button
            onClick={prevStep}
            disabled={tourStep === 0}
            className="text-[11px] text-slate-500 hover:text-slate-200 disabled:opacity-30"
          >
            ← Back
          </button>
          <div className="flex flex-1 justify-center">
            {TUTORIAL_STEPS.map((_, i) => (
              <span
                key={i}
                className={`mx-0.5 h-1.5 w-1.5 rounded-full ${
                  i === tourStep ? 'bg-sky-400' : i < tourStep ? 'bg-sky-800' : 'bg-slate-700'
                }`}
              />
            ))}
          </div>
          {isLast ? (
            <button
              onClick={endTour}
              className="rounded bg-emerald-600 px-3 py-1 text-[12px] font-semibold text-white hover:bg-emerald-500"
            >
              Done
            </button>
          ) : (
            <button
              onClick={nextStep}
              disabled={waiting}
              className="rounded bg-sky-600 px-3 py-1 text-[12px] font-semibold text-white hover:bg-sky-500 disabled:cursor-not-allowed disabled:bg-slate-800 disabled:text-slate-500"
              title={waiting ? 'Waiting for the investigation to complete…' : ''}
            >
              {waiting ? 'Waiting…' : 'Next →'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function centerTooltip() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  return { top: Math.max(60, vh / 2 - 120), left: Math.max(12, vw / 2 - TOOLTIP_W / 2) }
}

function positionTooltip(rect: Rect, side: 'top' | 'bottom' | 'left' | 'right' | 'auto') {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const H = 200 // rough tooltip height estimate for placement math

  const spaces = {
    right: vw - (rect.left + rect.width),
    left: rect.left,
    top: rect.top,
    bottom: vh - (rect.top + rect.height),
  }
  const chosen: 'top' | 'bottom' | 'left' | 'right' =
    side === 'auto'
      ? (Object.entries(spaces).sort((a, b) => b[1] - a[1])[0][0] as 'top' | 'bottom' | 'left' | 'right')
      : side

  let top = 0
  let left = 0
  switch (chosen) {
    case 'right':
      left = rect.left + rect.width + TOOLTIP_PAD
      top = clamp(rect.top + rect.height / 2 - H / 2, 12, vh - H - 12)
      break
    case 'left':
      left = rect.left - TOOLTIP_W - TOOLTIP_PAD
      top = clamp(rect.top + rect.height / 2 - H / 2, 12, vh - H - 12)
      break
    case 'top':
      top = rect.top - H - TOOLTIP_PAD
      left = clamp(rect.left + rect.width / 2 - TOOLTIP_W / 2, 12, vw - TOOLTIP_W - 12)
      break
    case 'bottom':
      top = rect.top + rect.height + TOOLTIP_PAD
      left = clamp(rect.left + rect.width / 2 - TOOLTIP_W / 2, 12, vw - TOOLTIP_W - 12)
      break
  }
  // fall back to a safe centered position if the placement puts us off-screen
  if (left < 12 || left + TOOLTIP_W > vw - 12 || top < 12 || top + H > vh - 12) {
    return centerTooltip()
  }
  return { top, left }
}

function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}
