import { useEffect } from 'react'
import L from 'leaflet'
import { MapContainer, Marker, TileLayer, Tooltip, useMap } from 'react-leaflet'
import type { Turbine } from '../types'
import 'leaflet/dist/leaflet.css'

interface Props {
  turbines: Turbine[]
  selectedTurbineId: string
  onSelect: (turbineId: string) => void
}

function markerIcon(selected: boolean): L.DivIcon {
  return L.divIcon({
    className: `turbine-marker${selected ? ' turbine-marker-selected' : ''}`,
    html: '<span></span>',
    iconSize: [20, 20],
    iconAnchor: [10, 10],
  })
}

// Держим карту "как в Hetzner": подгоняем зум под обе точки один раз и
// запираем диапазон рядом с ним, чтобы нельзя было ни отдалиться до всего
// города, ни приблизиться до потери контекста.
function FitBounds({ turbines }: { turbines: Turbine[] }) {
  const map = useMap()

  useEffect(() => {
    if (turbines.length === 0) return

    if (turbines.length === 1) {
      map.setView([turbines[0].lat, turbines[0].lon], 15)
      map.setMinZoom(13)
      map.setMaxZoom(17)
      return
    }

    const bounds = L.latLngBounds(turbines.map((t) => [t.lat, t.lon] as [number, number]))
    map.fitBounds(bounds, { padding: [56, 56] })
    const zoom = map.getZoom()
    map.setMinZoom(Math.max(zoom - 1, 3))
    map.setMaxZoom(zoom + 2)
    map.setMaxBounds(bounds.pad(1))
  }, [map, turbines])

  return null
}

export function TurbineMap({ turbines, selectedTurbineId, onSelect }: Props) {
  if (turbines.length === 0) {
    return <div className="card muted">Загрузка карты...</div>
  }

  const center: [number, number] = [turbines[0].lat, turbines[0].lon]

  return (
    <div className="map-card card">
      <MapContainer center={center} zoom={15} scrollWheelZoom={false} style={{ height: 320, width: '100%' }}>
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <FitBounds turbines={turbines} />
        {turbines.map((t) => (
          <Marker
            key={t.id}
            position={[t.lat, t.lon]}
            icon={markerIcon(t.id === selectedTurbineId)}
            eventHandlers={{ click: () => onSelect(t.id) }}
          >
            <Tooltip permanent direction="top" offset={[0, -12]} className="turbine-tooltip">
              {t.name}
            </Tooltip>
          </Marker>
        ))}
      </MapContainer>
    </div>
  )
}
