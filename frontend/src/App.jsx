import { useMemo, useState } from 'react';
import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? ''
});

const PRODUCTS = [
  {
    id: 'iphone-16-pro',
    name: '\uC544\uC774\uD3F0\u0020\u0031\u0036\u0020\u0050\u0072\u006F',
    price: 1690000,
    colors: [
      '\uB0B4\uCD94\uB7F4\u0020\uD2F0\uD0C0\uB284',
      '\uBE14\uB799\u0020\uD2F0\uD0C0\uB284',
      '\uB370\uC800\uD2B8\u0020\uD2F0\uD0C0\uB284'
    ]
  },
  {
    id: 'galaxy-s25-ultra',
    name: '\uAC24\uB7ED\uC2DC\u0020\u0053\u0032\u0035\u0020\u0055\u006C\u0074\u0072\u0061',
    price: 1490000,
    colors: [
      '\uD32C\uD140\u0020\uBE14\uB799',
      '\uBBFC\uD2B8\u0020\uBE14\uB8E8',
      '\uADF8\uB808\uC774\u0020\uC0C8\uB3C4\uC6B0'
    ]
  },
  {
    id: 'xiaomi-pro',
    name: '\uC0E4\uC624\uBBF8\u0020\u0031\u0034\u0054\u0020\u0050\u0072\u006F',
    price: 890000,
    colors: [
      '\uBA54\uD14C\uC624\u0020\uBE14\uB799',
      '\uC624\uB85C\uB77C\u0020\uC2E4\uBC84'
    ]
  }
];

const currencyFormatter = new Intl.NumberFormat('ko-KR', {
  style: 'currency',
  currency: 'KRW'
});

function randomKey() {
  return `mock-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export default function App() {
  const [selectedProductId, setSelectedProductId] = useState(PRODUCTS[0].id);
  const [selectedColor, setSelectedColor] = useState(PRODUCTS[0].colors[0]);
  const [quantity, setQuantity] = useState(1);
  const [receipt, setReceipt] = useState(null);
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState(randomKey());
  const [manualPaymentId, setManualPaymentId] = useState('');
  const [lastPaymentId, setLastPaymentId] = useState(null);
  const [refundReason, setRefundReason] = useState('customer cancel');
  const [sessionDetails, setSessionDetails] = useState(null);

  const selectedProduct = useMemo(
    () => PRODUCTS.find((product) => product.id === selectedProductId) ?? PRODUCTS[0],
    [selectedProductId]
  );

  const totalAmount = selectedProduct.price * quantity;

  const handleApiError = (error, fallbackMessage) => {
    if (error?.response) {
      setStatus({
        type: 'error',
        message: error.response.data?.message ?? fallbackMessage
      });
    } else {
      setStatus({
        type: 'error',
        message: fallbackMessage
      });
    }
  };

  const handleAuthorizeOnly = async () => {
    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const details = {
        product: selectedProduct.name,
        color: selectedColor,
        quantity
      };

      const authorizeResponse = await api.post('/payments/authorize', {
        merchantId,
        amount: totalAmount,
        currency: 'KRW',
        idempotencyKey
      });

      const paymentId = authorizeResponse.data.paymentId;
      setLastPaymentId(paymentId ?? null);
      setManualPaymentId(paymentId ? String(paymentId) : '');
      setSessionDetails(details);
      setReceipt({
        ...details,
        authorization: authorizeResponse.data,
        capture: null,
        refund: null
      });
      setStatus({
        type: 'success',
        message: '\uC2B9\uC778\uC774\u0020\uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4\u002E'
      });
    } catch (error) {
      handleApiError(error, '\uC2B9\uC778\u0020\uC694\uCCAD\uC5D0\u0020\uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4\u002E');
    } finally {
      setLoading(false);
    }
  };

  const resolvePaymentId = () => {
    const candidate = manualPaymentId.trim() || (lastPaymentId != null ? String(lastPaymentId) : '');
    if (!candidate) {
      setStatus({
        type: 'error',
        message: '\uACB0\uC81C\u0020\u0049\u0044\uB97C\u0020\uC785\uB825\uD558\uAC70\uB098\u0020\uBA3C\uC800\u0020\uC2B9\uC778\uC744\u0020\uC9C4\uD589\uD558\uC138\uC694\u002E'
      });
      return null;
    }
    if (!/^\d+$/.test(candidate)) {
      setStatus({
        type: 'error',
        message: '\uACB0\uC81C\u0020\u0049\u0044\uB294\u0020\uC22B\uC790\uB85C\uB9CC\u0020\uC785\uB825\uD558\uC138\uC694\u002E'
      });
      return null;
    }
    return candidate;
  };

  const handleCapture = async () => {
    const paymentId = resolvePaymentId();
    if (!paymentId) {
      return;
    }

    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const captureResponse = await api.post(`/payments/capture/${paymentId}`, {
        merchantId
      });

      const details =
        sessionDetails ??
        receipt ??
        {
          product: selectedProduct.name,
          color: selectedColor,
          quantity
        };

      setSessionDetails(details);
      setLastPaymentId(captureResponse.data.paymentId ?? Number(paymentId));
      setManualPaymentId(paymentId);
      setReceipt((previous) => ({
        ...details,
        authorization: previous?.authorization ?? null,
        capture: captureResponse.data,
        refund: null
      }));
      setStatus({
        type: 'success',
        message: '\uC815\uC0B0\uC774\u0020\uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4\u002E'
      });
    } catch (error) {
      handleApiError(error, '\uC815\uC0B0\u0020\uC694\uCCAD\uC5D0\u0020\uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4\u002E');
    } finally {
      setLoading(false);
    }
  };

  const handleRefund = async () => {
    const paymentId = resolvePaymentId();
    if (!paymentId) {
      return;
    }

    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const refundResponse = await api.post(`/payments/refund/${paymentId}`, {
        merchantId,
        reason: refundReason?.trim() ? refundReason.trim() : 'manual-refund'
      });

      const details =
        sessionDetails ??
        receipt ??
        {
          product: selectedProduct.name,
          color: selectedColor,
          quantity
        };

      setSessionDetails(details);
      setLastPaymentId(refundResponse.data.paymentId ?? Number(paymentId));
      setManualPaymentId(paymentId);
      setReceipt((previous) => ({
        ...details,
        authorization: previous?.authorization ?? null,
        capture: previous?.capture ?? null,
        refund: refundResponse.data
      }));
      setStatus({
        type: 'success',
        message: '\uD658\uBD88\uC774\u0020\uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4\u002E'
      });
    } catch (error) {
      handleApiError(error, '\uD658\uBD88\u0020\uC694\uCCAD\uC5D0\u0020\uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4\u002E');
    } finally {
      setLoading(false);
    }
  };

  const handlePurchase = async () => {
    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const keyToUse = idempotencyKey;
      const details = {
        product: selectedProduct.name,
        color: selectedColor,
        quantity
      };

      const authorizeResponse = await api.post('/payments/authorize', {
        merchantId,
        amount: totalAmount,
        currency: 'KRW',
        idempotencyKey: keyToUse
      });

      const paymentId = authorizeResponse.data.paymentId;
      const captureResponse = await api.post(`/payments/capture/${paymentId}`, {
        merchantId
      });

      setSessionDetails(details);
      setLastPaymentId(paymentId ?? null);
      setManualPaymentId(paymentId ? String(paymentId) : '');
      setReceipt({
        ...details,
        authorization: authorizeResponse.data,
        capture: captureResponse.data,
        refund: null
      });
      setStatus({
        type: 'success',
        message: '\uC2B9\uC778\uACFC\u0020\uC815\uC0B0\uC774\u0020\uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4\u002E'
      });
      setIdempotencyKey(randomKey());
    } catch (error) {
      handleApiError(error, '\uC2B9\uC778\u0020\uBC0F\u0020\uC815\uC0B0\u0020\uC694\uCCAD\uC5D0\u0020\uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4\u002E');
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setIdempotencyKey(randomKey());
    setManualPaymentId('');
    setLastPaymentId(null);
    setRefundReason('customer cancel');
    setSessionDetails(null);
    setReceipt(null);
    setStatus(null);
  };

  return (
    <div className="app-container">
      <header>
        <div>
          <h1>{'\uD504\uB9AC\uBBF8\uC5C4\u0020\uAE30\uAE30\u0020\uC2A4\uD1A0\uC5B4'}</h1>
          <p>{'\u0052\u0065\u0061\u0063\u0074\u0020\uACB0\uC81C\u0020\uBAA9\uC5C5\u0020\u00B7\u0020\u004B\u0061\u0066\u006B\u0061\u0020\u002F\u0020\u004D\u0061\u0072\u0069\u0061\u0044\u0042\u0020\uD30C\uC774\uD504\uB77C\uC778'}</p>
        </div>
        <button
          onClick={handlePurchase}
          disabled={loading}
          style={{
            background: '#16a34a',
            color: '#fff',
            border: 'none',
            padding: '0.85rem 1.5rem',
            borderRadius: '10px',
            fontSize: '1.05rem',
            cursor: loading ? 'not-allowed' : 'pointer',
            opacity: loading ? 0.7 : 1
          }}
        >
          {loading ? '\uCC98\uB9AC\u0020\uC911\u2026' : '\uC2B9\uC778\u0020\uD6C4\u0020\uC815\uC0B0'}
        </button>
      </header>

      <section
        style={{
          background: '#f8fafc',
          border: '1px solid #e2e8f0',
          borderRadius: '12px',
          padding: '1.25rem',
          marginTop: '1.5rem'
        }}
      >
        <h3 style={{ marginTop: 0, marginBottom: '0.75rem' }}>{'\uACB0\uC81C\u0020\uD14C\uC2A4\uD2B8\u0020\uB3C4\uAD6C'}</h3>

        <div
          style={{
            display: 'grid',
            gap: '0.75rem'
          }}
        >
          <div
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: '0.75rem'
            }}
          >
            <label style={{ flex: '1 1 260px', minWidth: '200px' }}>
              <span style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.35rem' }}>
                {'\uBA71\uB4F1\u0020\uD0A4'}
              </span>
              <input
                type="text"
                value={idempotencyKey}
                onChange={(event) => setIdempotencyKey(event.target.value)}
                style={{ width: '100%' }}
                disabled={loading}
              />
            </label>
            <button type="button" onClick={() => setIdempotencyKey(randomKey())} disabled={loading}>
              {'\uD0A4\u0020\uC0DD\uC131'}
            </button>
            <button type="button" onClick={handleAuthorizeOnly} disabled={loading}>
              {'\uC2B9\uC778\u0020\uD638\uCD9C'}
            </button>
          </div>

          <div
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: '0.75rem'
            }}
          >
            <label style={{ flex: '1 1 200px', minWidth: '160px' }}>
              <span style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.35rem' }}>
                {'\uACB0\uC81C\u0020\u0049\u0044'}
              </span>
              <input
                type="text"
                value={manualPaymentId}
                onChange={(event) => setManualPaymentId(event.target.value)}
                placeholder={'\uC608\u003A\u0020\u0031'}
                style={{ width: '100%' }}
                disabled={loading}
              />
            </label>
            <label style={{ flex: '1 1 220px', minWidth: '180px' }}>
              <span style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.35rem' }}>
                {'\uD658\uBD88\u0020\uC0AC\uC720'}
              </span>
              <input
                type="text"
                value={refundReason}
                onChange={(event) => setRefundReason(event.target.value)}
                placeholder={'\uC0AC\uC720\u0020\uC785\uB825'}
                style={{ width: '100%' }}
                disabled={loading}
              />
            </label>
            <button type="button" onClick={handleCapture} disabled={loading}>
              {'\uC815\uC0B0\u0020\uD638\uCD9C'}
            </button>
            <button type="button" onClick={handleRefund} disabled={loading}>
              {'\uD658\uBD88\u0020\uD638\uCD9C'}
            </button>
          </div>

          <div
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: '0.75rem'
            }}
          >
            <button type="button" onClick={handleReset} disabled={loading}>
              {'\uC785\uB825\u0020\uCD08\uAE30\uD654'}
            </button>
            <span style={{ alignSelf: 'center', fontSize: '0.85rem', color: '#475569' }}>
              {'\uB9C8\uC9C0\uB9C9\u0020\uACB0\uC81C\u0020\u0049\u0044\u003A\u0020'}
              {lastPaymentId != null ? lastPaymentId : '-'}
            </span>
          </div>
        </div>
      </section>

      <section className="products-grid">
        {PRODUCTS.map((product) => (
          <article key={product.id} className="product-card">
            <h2>{product.name}</h2>
            <strong>{currencyFormatter.format(product.price)}</strong>

            <label>
              {'\uC0C9\uC0C1'}
              <select
                value={product.id === selectedProductId ? selectedColor : product.colors[0]}
                onChange={(event) => {
                  setSelectedProductId(product.id);
                  setSelectedColor(event.target.value);
                }}
              >
                {product.colors.map((color) => (
                  <option key={color} value={color}>
                    {color}
                  </option>
                ))}
              </select>
            </label>

            <label>
              {'\uC218\uB7C9'}
              <input
                type="number"
                min={1}
                max={5}
                value={product.id === selectedProductId ? quantity : 1}
                onFocus={() => {
                  setSelectedProductId(product.id);
                  setSelectedColor(product.colors[0]);
                }}
                onChange={(event) => {
                  const value = Number(event.target.value);
                  setQuantity(Number.isNaN(value) ? 1 : Math.min(Math.max(value, 1), 5));
                }}
              />
            </label>

            <button
              type="button"
              onClick={() => {
                setSelectedProductId(product.id);
                setSelectedColor(product.colors[0]);
              }}
            >
              {'\uC774\u0020\uC0C1\uD488\uC73C\uB85C\u0020\uD14C\uC2A4\uD2B8'}
            </button>
          </article>
        ))}
      </section>

      <section className="receipt">
        <h3>{'\uACB0\uC81C\u0020\uC694\uC57D'}</h3>
        <p>
          {'\uC120\uD0DD\uD55C\u0020\uC0C1\uD488\u003A\u0020'}
          <strong>{selectedProduct.name}</strong>
        </p>
        <p>
          {'\uC0C9\uC0C1\u003A\u0020'}
          {selectedColor}
        </p>
        <p>
          {'\uC218\uB7C9\u003A\u0020'}
          {quantity}
          {'\uAC1C'}
        </p>
        <p>
          {'\uCD1D\u0020\uACB0\uC81C\u0020\uAE08\uC561\u003A\u0020'}
          {currencyFormatter.format(totalAmount)}
        </p>

        {status && (
          <p className={status.type === 'success' ? 'status-success' : 'status-error'}>{status.message}</p>
        )}

        {receipt && (
          <div style={{ marginTop: '1.5rem' }}>
            <h4>{'\uCD5C\uADFC\u0020\u0041\u0050\u0049\u0020\uC751\uB2F5'}</h4>
            <pre
              style={{
                background: '#0f172a',
                color: '#e2e8f0',
                padding: '1rem',
                borderRadius: '8px',
                overflowX: 'auto'
              }}
            >
              {JSON.stringify(receipt, null, 2)}
            </pre>
          </div>
        )}
      </section>
    </div>
  );
}
