import { useMemo, useState } from 'react';
import axios from 'axios';

const PRODUCTS = [
  {
    id: 'iphone-16-pro',
    name: '아이폰 16 Pro',
    price: 1550000,
    colors: ['스페이스 블랙', '티타늄 블루', '데저트 티타늄']
  },
  {
    id: 'galaxy-s25-ultra',
    name: '갤럭시 S25 Ultra',
    price: 1490000,
    colors: ['팬텀 블랙', '미드나잇 퍼플', '그레이']
  },
  {
    id: 'xiaomi-pro',
    name: '샤오미 14T Pro',
    price: 890000,
    colors: ['메테오 블랙', '알파 실버']
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

  const selectedProduct = useMemo(
    () => PRODUCTS.find((product) => product.id === selectedProductId) ?? PRODUCTS[0],
    [selectedProductId]
  );

  const totalAmount = selectedProduct.price * quantity;

  const handlePurchase = async () => {
    setLoading(true);
    setStatus(null);
    setReceipt(null);

    try {
      const idempotencyKey = randomKey();
      const merchantId = 'MOCK-MERCHANT';
      const authorizeResponse = await axios.post('/payments/authorize', {
        merchantId,
        amount: totalAmount,
        currency: 'KRW',
        idempotencyKey
      });

      const paymentId = authorizeResponse.data.paymentId;
      const captureResponse = await axios.post(`/payments/capture/${paymentId}`, {
        merchantId
      });

      setReceipt({
        product: selectedProduct.name,
        color: selectedColor,
        quantity,
        authorization: authorizeResponse.data,
        capture: captureResponse.data
      });
      setStatus({
        type: 'success',
        message: '결제가 완료되었습니다.'
      });
    } catch (error) {
      if (error.response) {
        setStatus({
          type: 'error',
          message: error.response.data?.message ?? '결제 요청이 거절되었습니다.'
        });
      } else {
        setStatus({
          type: 'error',
          message: 'API 서버에 연결할 수 없습니다.'
        });
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="app-container">
      <header>
        <div>
          <h1>프리미엄 디바이스 스토어</h1>
          <p>React 목업 결제 페이지 · Kafka/MariaDB 연동 테스트</p>
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
          {loading ? '결제 진행 중…' : '선택 상품 결제하기'}
        </button>
      </header>

      <section className="products-grid">
        {PRODUCTS.map((product) => (
          <article key={product.id} className="product-card">
            <h2>{product.name}</h2>
            <strong>{currencyFormatter.format(product.price)}</strong>

            <label>
              색상 선택
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
              수량
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
              이 상품 선택
            </button>
          </article>
        ))}
      </section>

      <section className="receipt">
        <h3>요약</h3>
        <p>
          선택한 상품: <strong>{selectedProduct.name}</strong>
        </p>
        <p>색상: {selectedColor}</p>
        <p>수량: {quantity}개</p>
        <p>총 결제 금액: {currencyFormatter.format(totalAmount)}</p>

        {status && (
          <p className={status.type === 'success' ? 'status-success' : 'status-error'}>
            {status.message}
          </p>
        )}

        {receipt && (
          <div style={{ marginTop: '1.5rem' }}>
            <h4>결제 응답</h4>
            <pre style={{
              background: '#0f172a',
              color: '#e2e8f0',
              padding: '1rem',
              borderRadius: '8px',
              overflowX: 'auto'
            }}>
              {JSON.stringify(receipt, null, 2)}
            </pre>
          </div>
        )}
      </section>
    </div>
  );
}
