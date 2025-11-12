import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Link } from 'react-router-dom';
import App from './App.jsx';
import AdminPage from './AdminPage.jsx';
import './styles.css';
import './admin-styles.css';

function Navigation() {
  return (
    <nav style={{
      position: 'fixed',
      top: 0,
      left: 0,
      right: 0,
      height: '60px',
      background: 'rgba(15, 23, 42, 0.95)',
      backdropFilter: 'blur(16px)',
      borderBottom: '1px solid rgba(148, 163, 184, 0.2)',
      display: 'flex',
      alignItems: 'center',
      padding: '0 2rem',
      gap: '2rem',
      zIndex: 1000
    }}>
      <h1 style={{
        margin: 0,
        fontSize: '1.25rem',
        fontWeight: 700,
        background: 'linear-gradient(135deg, #60a5fa 0%, #a78bfa 100%)',
        WebkitBackgroundClip: 'text',
        WebkitTextFillColor: 'transparent',
        backgroundClip: 'text'
      }}>
        Payment SWElite
      </h1>
      <div style={{ display: 'flex', gap: '1rem' }}>
        <Link
          to="/"
          style={{
            color: '#cbd5e1',
            textDecoration: 'none',
            padding: '0.5rem 1rem',
            borderRadius: '8px',
            transition: 'all 0.2s',
            fontWeight: 500
          }}
          onMouseEnter={(e) => {
            e.target.style.background = 'rgba(148, 163, 184, 0.1)';
            e.target.style.color = '#f1f5f9';
          }}
          onMouseLeave={(e) => {
            e.target.style.background = 'transparent';
            e.target.style.color = '#cbd5e1';
          }}
        >
          🛍️ Commerce
        </Link>
        <Link
          to="/admin"
          style={{
            color: '#cbd5e1',
            textDecoration: 'none',
            padding: '0.5rem 1rem',
            borderRadius: '8px',
            transition: 'all 0.2s',
            fontWeight: 500
          }}
          onMouseEnter={(e) => {
            e.target.style.background = 'rgba(148, 163, 184, 0.1)';
            e.target.style.color = '#f1f5f9';
          }}
          onMouseLeave={(e) => {
            e.target.style.background = 'transparent';
            e.target.style.color = '#cbd5e1';
          }}
        >
          ⚙️ Admin Dashboard
        </Link>
      </div>
    </nav>
  );
}

function Root() {
  return (
    <BrowserRouter>
      <div style={{ paddingTop: '60px' }}>
        <Navigation />
        <Routes>
          <Route path="/" element={<App />} />
          <Route path="/admin" element={<AdminPage />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>
);
