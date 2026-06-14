import React from 'react';

// Conduit icon set — ported from the Claude Design bundle (icons.jsx). Minimal stroke icons (Lucide geometry).
type IconProps = { size?: number; fill?: string; sw?: number } & React.SVGProps<SVGSVGElement>;
const Ic = ({ d, children, size = 18, fill = 'none', sw = 2, ...p }: IconProps & { d?: string; children?: React.ReactNode }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={fill} stroke="currentColor" strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round" {...p}>
    {d ? <path d={d} /> : children}
  </svg>
);

export type IconC = (p?: IconProps) => React.ReactElement;

export const I: Record<string, IconC> = {
  grid: (p) => <Ic {...p}><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/></Ic>,
  bolt: (p) => <Ic {...p} fill="currentColor" sw={0}><path d="M13 2 4 14h6l-1 8 9-12h-6z"/></Ic>,
  charger: (p) => <Ic {...p}><rect x="6" y="3" width="12" height="18" rx="2.5"/><path d="M12 8 10 12h3l-2 4"/></Ic>,
  pulse: (p) => <Ic {...p}><path d="M3 12h4l2-6 4 12 2-6h6"/></Ic>,
  alert: (p) => <Ic {...p}><path d="M12 9v4M12 17h.01"/><path d="M10.3 3.3 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.3a2 2 0 0 0-3.4 0Z"/></Ic>,
  sessions: (p) => <Ic {...p}><path d="M3 3v18h18"/><path d="M7 14l3-4 3 2 4-6"/></Ic>,
  search: (p) => <Ic {...p}><circle cx="11" cy="11" r="7"/><path d="m21 21-3.5-3.5"/></Ic>,
  bell: (p) => <Ic {...p}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/></Ic>,
  arrowR: (p) => <Ic {...p}><path d="M5 12h14M13 6l6 6-6 6"/></Ic>,
  x: (p) => <Ic {...p}><path d="M18 6 6 18M6 6l12 12"/></Ic>,
  download: (p) => <Ic {...p}><path d="M12 3v12M7 10l5 5 5-5M5 21h14"/></Ic>,
  refresh: (p) => <Ic {...p}><path d="M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5"/></Ic>,
  clock: (p) => <Ic {...p}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></Ic>,
  zap: (p) => <Ic {...p} fill="currentColor" sw={0}><path d="M13 2 4 14h6l-1 8 9-12h-6z"/></Ic>,
  cpu: (p) => <Ic {...p}><rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/></Ic>,
  user: (p) => <Ic {...p}><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></Ic>,
  shield: (p) => <Ic {...p}><path d="M12 2 4 5v6c0 5 3.5 8.5 8 10 4.5-1.5 8-5 8-10V5l-8-3Z"/><path d="m9 12 2 2 4-4"/></Ic>,
  globe: (p) => <Ic {...p}><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18 14 14 0 0 1 0-18Z"/></Ic>,
  battery: (p) => <Ic {...p}><rect x="2" y="7" width="17" height="10" rx="2"/><path d="M22 11v2"/></Ic>,
  trend: (p) => <Ic {...p}><path d="M3 17l6-6 4 4 8-8M21 7v5h-5"/></Ic>,
  check: (p) => <Ic {...p}><path d="M20 6 9 17l-5-5"/></Ic>,
  list: (p) => <Ic {...p}><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></Ic>,
  layers: (p) => <Ic {...p}><path d="m12 2 9 5-9 5-9-5 9-5ZM3 12l9 5 9-5M3 17l9 5 9-5"/></Ic>,
  flag: (p) => <Ic {...p}><path d="M4 22V4M4 4h13l-2 4 2 4H4"/></Ic>,
  chevR: (p) => <Ic {...p}><path d="m9 6 6 6-6 6"/></Ic>,
  command: (p) => <Ic {...p}><path d="M9 6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6Z"/></Ic>,
  sync: (p) => <Ic {...p}><path d="M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5"/></Ic>,
  scale: (p) => <Ic {...p}><path d="M12 3v18M5 7h14M7 7l-3 7a3 3 0 0 0 6 0zM17 7l-3 7a3 3 0 0 0 6 0z"/></Ic>,
  up: (p) => <Ic {...p}><path d="M7 17 17 7M9 7h8v8"/></Ic>,
  down: (p) => <Ic {...p}><path d="M7 7 17 17M17 9v8H9"/></Ic>,
  arrowBack: (p) => <Ic {...p}><path d="M19 12H5M11 18l-6-6 6-6"/></Ic>,
  more: (p) => <Ic {...p}><circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/></Ic>,
  filter: (p) => <Ic {...p}><path d="M3 5h18l-7 8v6l-4 2v-8L3 5Z"/></Ic>,
  settings: (p) => <Ic {...p}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 0 1-4 0v-.1A1.6 1.6 0 0 0 7 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H1a2 2 0 0 1 0-4h.1A1.6 1.6 0 0 0 4.6 7a1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V1a2 2 0 0 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H23a2 2 0 0 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1Z"/></Ic>,
  expand: (p) => <Ic {...p}><path d="M8 3H3v5M16 3h5v5M16 21h5v-5M8 21H3v-5"/></Ic>,
  sun: (p) => <Ic {...p}><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4 12H2M22 12h-2M5 5 4 4M20 20l-1-1M19 5l1-1M4 20l1-1"/></Ic>,
  moon: (p) => <Ic {...p}><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/></Ic>,
  play: (p) => <Ic {...p} fill="currentColor" sw={0}><path d="M7 4v16l13-8z"/></Ic>,
  pause: (p) => <Ic {...p}><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></Ic>,
  map: (p) => <Ic {...p}><path d="m9 4-6 2v14l6-2 6 2 6-2V4l-6 2-6-2Z"/><path d="M9 4v14M15 6v14"/></Ic>,
  mapPin: (p) => <Ic {...p}><path d="M12 21s-7-6.3-7-11a7 7 0 0 1 14 0c0 4.7-7 11-7 11Z"/><circle cx="12" cy="10" r="2.5"/></Ic>,
  car: (p) => <Ic {...p}><path d="M5 13 7 7h10l2 6M5 13h14v5H5zM7 18v2M17 18v2"/></Ic>,
  leaf: (p) => <Ic {...p}><path d="M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.5 19 2c1 2 2 4.2 2 8a7 7 0 0 1-7 7Z"/><path d="M2 22c1.5-7 6-9 9-10"/></Ic>,
  wifi: (p) => <Ic {...p}><path d="M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01"/></Ic>,
  wifiOff: (p) => <Ic {...p}><path d="m2 2 20 20M8.5 16a5 5 0 0 1 7 0M5 12.5a10 10 0 0 1 4-2.5M16 10a10 10 0 0 1 3 2.5"/></Ic>,
};
