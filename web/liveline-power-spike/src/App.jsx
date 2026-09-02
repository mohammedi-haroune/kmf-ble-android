import { useEffect, useMemo, useState } from 'react'
import { Liveline } from 'liveline'

const emptyPayload = {
  points: [],
  latestValue: 0,
  nativeSentAtMs: 0,
}

function formatWatts(value) {
  if (!Number.isFinite(value)) {
    return '-- W'
  }
  const magnitude = Math.abs(value)
  const decimals = magnitude <= 5 ? 2 : magnitude <= 20 ? 1 : 0
  return `${value.toLocaleString(undefined, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })} W`
}

function postEvent(kind, payload) {
  // Bridge timing events back to Android so we can log page-ready, payload-received,
  // and first-frame latency from the device side.
  window.KmfLivelineBridge?.onChartEvent?.(
    kind,
    payload.nativeSentAtMs ?? 0,
    Date.now(),
  )
}

export function App() {
  const [payload, setPayload] = useState(emptyPayload)
  const [status, setStatus] = useState('Waiting for Android samples')

  useEffect(() => {
    console.debug('liveline app effect mount')
    window.kmfPowerChart = {
      update(nextPayload) {
        console.debug('liveline app update', nextPayload)
        const normalized = {
          points: Array.isArray(nextPayload?.points) ? nextPayload.points : [],
          latestValue: Number(nextPayload?.latestValue ?? 0),
          nativeSentAtMs: Number(nextPayload?.nativeSentAtMs ?? 0),
        }
        setPayload(normalized)
        setStatus(`Received ${normalized.points.length} points`)
        postEvent('received', normalized)
        requestAnimationFrame(() => {
          postEvent('frame', normalized)
        })
      },
    }

    console.debug('liveline bridge ready')
    postEvent('ready', emptyPayload)

    return () => {
      delete window.kmfPowerChart
    }
  }, [])

  const chartData = useMemo(
    () =>
      payload.points.map((point) => ({
        time: point.time,
        value: point.value,
      })),
    [payload.points],
  )

  return (
    // <main className="screen">
    //   <section className="header">
    //     <div>
    //       <div className="eyebrow">Experimental</div>
    //       <h1>Power</h1>
    //       <div className="status">{status}</div>
    //     </div>
    //     <div className="latest">{formatWatts(payload.latestValue)}</div>
    //   </section>

    //   <section className="chart-shell" aria-label="Power chart">
    //     <div className="chart-frame">
          // {/* Keep the live series readable at low power by using a slightly thicker stroke. */}
        <div style={{ height: 350}}>
          <Liveline
            data={chartData}
            value={payload.latestValue}
            theme="dark"
            color="#60a5fa"
            window={30}
            // showValue={true}
            // valueMomentumColor={true}
            // exaggerate={true}
            windows={[
              { label: '5s', secs: 5 },
              { label: '30s', secs: 30 },
              { label: '1m', secs: 60 },
              { label: '5m', secs: 300 },
            ]}
            windowStyle="rounded"
            // fill={false}
            // lineWidth={3}
            formatValue={formatWatts}
          />
        </div>
    //     </div>
    //   </section>
    // </main>
  )
}
