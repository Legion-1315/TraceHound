import { Client } from '@stomp/stompjs'
import type { InvestigationEvent } from './types'

/**
 * Subscribe to the investigation event stream for one incident.
 * Returns a cleanup function that closes the connection.
 */
export function subscribeToInvestigation(
  incidentId: string,
  onEvent: (e: InvestigationEvent) => void,
): () => void {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const client = new Client({
    brokerURL: `${protocol}://${window.location.host}/ws`,
    reconnectDelay: 2000,
    onConnect: () => {
      client.subscribe(`/topic/investigation/${incidentId}`, (message) => {
        onEvent(JSON.parse(message.body) as InvestigationEvent)
      })
    },
  })
  client.activate()
  return () => {
    void client.deactivate()
  }
}
