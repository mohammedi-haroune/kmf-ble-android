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
  const rounded = Math.abs(value) >= 10 ? value.toFixed(1) : value.toFixed(2)
  return `${Number(rounded).toLocaleString(undefined, { maximumFractionDigits: 2 })} W`
}

function postEvent(kind, payload) {
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
    <main className="screen">
      <section className="header">
        <div>
          <div className="eyebrow">Experimental</div>
          <h1>Power</h1>
          <div className="status">{status}</div>
        </div>
        <div className="latest">{formatWatts(payload.latestValue)}</div>
      </section>

      <section className="chart-shell" aria-label="Power chart">
        <div className="chart-frame">
          <Liveline
            data={chartData}
            value={payload.latestValue}
            theme="dark"
            color="#60a5fa"
            window={60}
            showValue={true}
            valueMomentumColor={true}
            windows={[{ label: '60s', secs: 60 }]}
            windowStyle="text"
            formatValue={formatWatts}
          />
        </div>
      </section>
    </main>
  )
}
