import React from 'react';
import { createRoot } from 'react-dom/client';
import './i18n'; // initialise i18next before the app renders
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
