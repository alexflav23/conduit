import React from 'react';
import { createRoot } from 'react-dom/client';
import './styles/hv-tokens.css'; // Hypervolt design tokens (from the Claude Design bundle)
import './styles/desk.css'; // the ported desk shell + kit styling
import './i18n'; // initialise i18next before the app renders
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
