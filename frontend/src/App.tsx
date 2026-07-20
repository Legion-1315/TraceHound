import { useEffect } from 'react'
import { useTriageStore } from './store'
import { api } from './api'
import ChatPanel from './components/ChatPanel'
import CanvasPanel from './components/CanvasPanel'
import RightPanel from './components/RightPanel'
import ChaosPanel from './components/ChaosPanel'
import TicketModal from './components/TicketModal'
import Footer from './components/Footer'

export default function App() {
  const { setTopology, setScenarios, chaosOpen, setChaosOpen, ticketOpen } = useTriageStore()

  useEffect(() => {
    api.topology().then(setTopology).catch(console.error)
    api.chaosState().then((s) => setScenarios(s.scenarios, s.active)).catch(console.error)
  }, [setTopology, setScenarios])

  // hidden chaos panel: Ctrl+Shift+K
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setChaosOpen(!useTriageStore.getState().chaosOpen)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [setChaosOpen])

  return (
    <div className="flex h-full flex-col bg-[#0b1220]">
      <header className="flex items-center justify-between border-b border-slate-800 px-4 py-2">
        <div className="flex items-baseline gap-3">
          <h1 className="text-lg font-semibold tracking-tight text-slate-100">
            Triage <span className="text-sky-400">Copilot</span>
          </h1>
          <span className="text-xs text-slate-500">AI-powered incident triage · distributed estate</span>
        </div>
        <span className="text-[11px] text-slate-600">Ctrl+Shift+K — chaos panel</span>
      </header>

      <main className="flex min-h-0 flex-1">
        <section className="flex w-[340px] shrink-0 flex-col border-r border-slate-800">
          <ChatPanel />
        </section>
        <section className="min-w-0 flex-1">
          <CanvasPanel />
        </section>
        <section className="flex w-[400px] shrink-0 flex-col border-l border-slate-800">
          <RightPanel />
        </section>
      </main>

      <Footer />
      {chaosOpen && <ChaosPanel />}
      {ticketOpen && <TicketModal />}
    </div>
  )
}
