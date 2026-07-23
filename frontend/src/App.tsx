import { useEffect, useState } from 'react'
import { useTriageStore } from './store'
import { api } from './api'
import ChatPanel from './components/ChatPanel'
import CanvasPanel from './components/CanvasPanel'
import RightPanel from './components/RightPanel'
import ChaosPanel from './components/ChaosPanel'
import TicketModal from './components/TicketModal'
import PanelDivider from './components/PanelDivider'
import Footer from './components/Footer'
import Tutorial from './tutorial/Tutorial'

const TOUR_SEEN_KEY = 'triage-copilot.tour-seen'
const PANELS_KEY = 'code-catalyst.panel-widths'

const DEFAULT_LEFT = 340
const DEFAULT_RIGHT = 400
const MIN_LEFT = 260
const MAX_LEFT = 680
const MIN_RIGHT = 300
const MAX_RIGHT = 820
/** The canvas is the star of the demo — never let the side panels squeeze it out. */
const MIN_CANVAS = 340
/** The two dividers occupy real width too (w-1.5 each), so reserve it. */
const DIVIDERS = 12

type PanelWidths = { left: number; right: number }

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

/** Clamp both panels against their own bounds and against the room left for the canvas. */
function fit({ left, right }: PanelWidths): PanelWidths {
  const available = (typeof window === 'undefined' ? 1440 : window.innerWidth) - DIVIDERS
  const nextLeft = clamp(left, MIN_LEFT, Math.min(MAX_LEFT, available - MIN_RIGHT - MIN_CANVAS))
  const nextRight = clamp(right, MIN_RIGHT, Math.min(MAX_RIGHT, available - nextLeft - MIN_CANVAS))
  return { left: nextLeft, right: nextRight }
}

function loadPanels(): PanelWidths {
  try {
    const raw = localStorage.getItem(PANELS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PanelWidths>
      if (typeof parsed.left === 'number' && typeof parsed.right === 'number') {
        return fit({ left: parsed.left, right: parsed.right })
      }
    }
  } catch { /* unreadable or corrupt — fall through to defaults */ }
  return fit({ left: DEFAULT_LEFT, right: DEFAULT_RIGHT })
}

export default function App() {
  const {
    setTopology, setScenarios, chaosOpen, setChaosOpen, ticketOpen,
    tourActive, startTour, endTour,
  } = useTriageStore()
  const [promptFirstRun, setPromptFirstRun] = useState(false)
  const [panels, setPanels] = useState<PanelWidths>(loadPanels)

  // Persist the layout so a resized demo setup survives a reload.
  useEffect(() => {
    try { localStorage.setItem(PANELS_KEY, JSON.stringify(panels)) } catch { /* noop */ }
  }, [panels])

  // A narrower window can invalidate saved widths — re-fit rather than overflow.
  useEffect(() => {
    const onResize = () => setPanels((p) => fit(p))
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const setLeft = (left: number) => setPanels((p) => fit({ ...p, left }))
  const setRight = (right: number) => setPanels((p) => fit({ ...p, right }))
  const nudgeLeft = (d: number) => setPanels((p) => fit({ ...p, left: p.left + d }))
  const nudgeRight = (d: number) => setPanels((p) => fit({ ...p, right: p.right + d }))
  const resetPanels = () => setPanels(fit({ left: DEFAULT_LEFT, right: DEFAULT_RIGHT }))

  useEffect(() => {
    api.topology().then(setTopology).catch(console.error)
    api.chaosState().then((s) => setScenarios(s.scenarios, s.active)).catch(console.error)
    // Offer the tour on the user's first visit only.
    try {
      if (!localStorage.getItem(TOUR_SEEN_KEY)) {
        setPromptFirstRun(true)
      }
    } catch { /* localStorage unavailable — silently skip the prompt */ }
  }, [setTopology, setScenarios])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setChaosOpen(!useTriageStore.getState().chaosOpen)
      }
      if (e.key === 'Escape' && useTriageStore.getState().tourActive) {
        endTour()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [setChaosOpen, endTour])

  function launchTour() {
    try { localStorage.setItem(TOUR_SEEN_KEY, '1') } catch { /* noop */ }
    setPromptFirstRun(false)
    startTour()
  }

  function dismissFirstRun() {
    try { localStorage.setItem(TOUR_SEEN_KEY, '1') } catch { /* noop */ }
    setPromptFirstRun(false)
  }

  return (
    <div className="flex h-full flex-col bg-[#0b1220]">
      <header className="flex items-center justify-between border-b border-slate-800 px-4 py-2">
        <div className="flex items-baseline gap-3">
          <h1 className="text-lg font-semibold tracking-tight text-slate-100">
            Code-<span className="text-sky-400">Catalyst</span>
          </h1>
          <span className="text-xs text-slate-500">AI-powered incident triage · distributed estate</span>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={launchTour}
            disabled={tourActive}
            className="rounded border border-sky-700 bg-sky-950/40 px-2.5 py-1 text-[11px] font-semibold text-sky-300 hover:border-sky-500 hover:bg-sky-900/50 disabled:opacity-40"
          >
            {tourActive ? 'Tour running…' : '★ Take the tour'}
          </button>
          <span className="text-[11px] text-slate-600">Ctrl+Shift+K — chaos panel</span>
        </div>
      </header>

      <main className="flex min-h-0 flex-1">
        <section className="flex shrink-0 flex-col overflow-hidden" style={{ width: panels.left }}>
          <ChatPanel />
        </section>

        <PanelDivider
          label="Resize incident panel"
          width={panels.left}
          onWidth={setLeft}
          onNudge={nudgeLeft}
          onReset={resetPanels}
          direction={1}
        />

        <section className="min-w-0 flex-1">
          <CanvasPanel />
        </section>

        <PanelDivider
          label="Resize report panel"
          width={panels.right}
          onWidth={setRight}
          onNudge={nudgeRight}
          onReset={resetPanels}
          direction={-1}
        />

        <section className="flex shrink-0 flex-col overflow-hidden" style={{ width: panels.right }}>
          <RightPanel />
        </section>
      </main>

      <Footer />
      {chaosOpen && <ChaosPanel />}
      {ticketOpen && <TicketModal />}
      {tourActive && <Tutorial />}

      {promptFirstRun && !tourActive && (
        <div className="fixed inset-0 z-[90] flex items-center justify-center bg-black/70" onClick={dismissFirstRun}>
          <div
            className="w-[440px] rounded-lg border border-sky-800 bg-slate-900 p-5 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="mb-1 text-base font-bold text-slate-100">First time here?</h2>
            <p className="mb-4 text-[12px] leading-relaxed text-slate-400">
              Take a 2-minute guided tour. It runs a real investigation for you and walks through every panel —
              you&apos;ll see how the agent scopes, hypothesises, walks the estate on evidence, and produces a
              report where every claim cites a ledger entry.
            </p>
            <div className="flex justify-end gap-2">
              <button
                onClick={dismissFirstRun}
                className="rounded border border-slate-700 px-3 py-1.5 text-[12px] text-slate-400 hover:text-slate-200"
              >
                Maybe later
              </button>
              <button
                onClick={launchTour}
                className="rounded bg-sky-600 px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-sky-500"
              >
                Start the tour
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
