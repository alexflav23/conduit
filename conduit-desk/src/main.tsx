import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from 'react-oidc-context';
import { QueryClientProvider } from '@tanstack/react-query';
import { oidcConfig } from './lib/auth';
import './styles/hv-tokens.css'; // Hypervolt design tokens (from the Claude Design bundle)
import './styles/desk.css'; // the ported desk shell + kit styling
import './i18n'; // initialise i18next before the app renders
import { App } from './App';
import { queryClient } from './lib/query';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthProvider {...oidcConfig}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </AuthProvider>
  </React.StrictMode>,
);
