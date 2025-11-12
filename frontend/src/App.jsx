import { useEffect, useMemo, useState } from 'react';
import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api'
});

const CATEGORIES = [
  {
    id: 'signature-tech',
    name: 'Signature Tech',
    tagline: 'Flagship Device Studio',
    description: '플래그십 스마트폰과 태블릿을 가장 빠르게 검증하고 결제 시나리오를 실험하는 하이엔드 디바이스 큐레이션입니다.',
    accent: '#38bdf8',
    heroImage: '/category-mobile.jpg',
    products: [
      {
        id: 'iphone-16-pro',
        name: 'iPhone 16 Pro Desert Titanium',
        brand: 'Apple',
        price: 1690000,
        originalPrice: 1790000,
        rating: 4.9,
        reviews: 1284,
        badges: ['프리미엄 새벽배송', '무료 반품'],
        shortDescription: 'A18 Pro 칩과 티타늄 프레임, ProMotion OLED로 완성한 Apple의 최신 플래그십.',
        highlights: [
          '48MP 트리플 카메라 · ProRes 로그 촬영',
          '6.1" ProMotion OLED · 최대 2000nit',
          'AppleCare+ 즉시 가입 · 전용 컬러 옵션',
          '스튜디오 재고 12대 · 실시간 시연 가능'
        ],
        shipping: '오늘 18시 이전 결제 시 내일 07시 배송완료',
        colorOptions: ['Desert Titanium', 'Natural Titanium', 'Black Titanium'],
        monthlyInstallment: '월 70,900원 (24개월 / 무이자)',
        promoLabel: '카드 즉시할인 7% + 트래블 파우치 증정',
        image: '/iphone-16-pro.jpg',
        fulfillment: '김포 프라임 허브',
        sla: 'J-STUDIO 1센터 · 잔여 12대'
      },
      {
        id: 'galaxy-s25-ultra',
        name: 'Galaxy S25 Ultra Ceramic Black',
        brand: 'Samsung',
        price: 1590000,
        originalPrice: 1690000,
        rating: 4.8,
        reviews: 894,
        badges: ['S플러스 혜택', '24개월 무이자'],
        shortDescription: '200MP 쿼드 카메라와 AI 강화를 더한 삼성의 최상위 모델.',
        highlights: [
          'Vision Booster 6.9" QHD+ 디스플레이',
          'S펜 기본 제공 · Knox Vault 보안',
          'AI Live Translate · ProVisual Engine',
          '부산 스마트 물류 / 재고 9대'
        ],
        shipping: '수도권 내일 새벽, 지방 2일 이내 배송',
        colorOptions: ['Ceramic Black', 'Platinum Gray', 'Mist Blue'],
        monthlyInstallment: '월 66,300원 (24개월 / 무이자)',
        promoLabel: '삼성카드 즉시할인 10만P',
        image: '/galaxy-s25-ultra.jpg',
        fulfillment: '부산 스마트 물류',
        sla: 'J-STUDIO 2센터 · 잔여 9대'
      },
      {
        id: 'ipad-pro',
        name: 'iPad Pro 13" M4 Wi-Fi 512GB',
        brand: 'Apple',
        price: 1840000,
        originalPrice: 1940000,
        rating: 4.9,
        reviews: 648,
        badges: ['Expert Setup', '교육가 적용'],
        shortDescription: '울트라 Retina XDR과 M4 칩이 결합된 전문가용 태블릿.',
        highlights: [
          'Apple Pencil Pro · 매직 키보드 호환',
          '울트라 Retina XDR · 듀얼 OLED',
          'Pro 워크플로 NIC · Wi-Fi 6E',
          '판교 프라임 허브 / 잔여 6대'
        ],
        shipping: '프리미엄 기사 설치 / 2영업일 내 완료',
        colorOptions: ['Space Black', 'Silver'],
        monthlyInstallment: '월 76,600원 (24개월 / 무이자)',
        promoLabel: '교육 고객 전용가 + Apple Pencil Pro 증정',
        image: '/ipad-pro.jpg',
        fulfillment: '판교 프라임 허브',
        sla: 'J-STUDIO 3센터 · 잔여 6대'
      }
    ]
  },
  {
    id: 'atelier-living',
    name: 'Atelier Living',
    tagline: 'Hotel-grade Home Appliances',
    description: '공간을 완성하는 하이엔드 가전과 키친 컬렉션. 설치 · 케어까지 한 번에 제공합니다.',
    accent: '#818cf8',
    heroImage: '/category-appliance.jpg',
    products: [
      {
        id: 'dyson-gen5',
        name: 'Dyson Gen5 Detect Absolute',
        brand: 'Dyson',
        price: 1290000,
        originalPrice: 1390000,
        rating: 4.7,
        reviews: 523,
        badges: ['케어 플랜', '필터 구독'],
        shortDescription: '340AW 흡입력과 레이저 먼지 감지 기능의 프리미엄 클리닝 솔루션.',
        highlights: [
          'Hyperdymium 모터 135,000rpm',
          '레이저 먼지 감지 · LCD 리포트',
          '2중 필터 · 99.99% 미세먼지 차단',
          '전담 케어 매니저 배정'
        ],
        shipping: '전국 전문 엔지니어 방문 설치 · 3영업일',
        colorOptions: ['Prussian Blue', 'Copper', 'Nickel'],
        monthlyInstallment: '월 53,700원 (24개월 / 무이자)',
        promoLabel: '필터 구독 6개월 + 연 1회 케어',
        image: '/dyson-gen5.jpg',
        fulfillment: 'Premium Care Team',
        sla: 'J-LIVING 1센터 · 재고 7대'
      },
      {
        id: 'balmuda-purifier',
        name: 'Balmuda The Pure Air Atelier Edition',
        brand: 'Balmuda',
        price: 849000,
        originalPrice: 899000,
        rating: 4.8,
        reviews: 311,
        badges: ['화이트글러브 배송', '필터 구독'],
        shortDescription: '공간을 갤러리처럼 밝히는 디자인 공기청정기.',
        highlights: [
          '3단 공기 흐름 · 360° 흡입',
          '아트 프레임 디자인',
          'IoT 앱 연동 · 공기 리포트',
          '필터 구독 / 연 1회 방문 케어'
        ],
        shipping: '화이트글러브 팀 / 4영업일 내 설치',
        colorOptions: ['Matte White', 'Charcoal'],
        monthlyInstallment: '월 35,400원 (24개월 / 무이자)',
        promoLabel: '프리미엄 필터 구독 1년 제공',
        image: '/balmuda-air-purifier.jpg',
        fulfillment: 'Atelier Living Hub',
        sla: 'J-LIVING 2센터 · 잔여 5대'
      },
      {
        id: 'breville-barista',
        name: 'Breville Barista Pro Brushed Steel',
        brand: 'Breville',
        price: 1090000,
        originalPrice: 1190000,
        rating: 4.9,
        reviews: 268,
        badges: ['바리스타 설치', '3년 보증'],
        shortDescription: '호텔 라운지를 그대로 옮겨온 듯한 홈카페 에스프레소 머신.',
        highlights: [
          '3초 예열 · PID 정밀 온도 제어',
          '미세 분쇄 조절 · 듀얼 보일러',
          '롱텀 바리스타 케어 프로그램',
          '프리미엄 원두 1kg 동봉'
        ],
        shipping: '전용 바리스타 설치 · 방문 튜토리얼 포함',
        colorOptions: ['Brushed Steel', 'Matte Black'],
        monthlyInstallment: '월 45,400원 (24개월 / 무이자)',
        promoLabel: '3년 보증 + 커피 구독 3개월',
        image: '/breville-barista-pro.jpg',
        fulfillment: 'Gourmet Care Team',
        sla: 'J-LIVING 3센터 · 잔여 4대'
      }
    ]
  }
];

const INITIAL_PRODUCT = CATEGORIES[0].products[0];

const currencyFormatter = new Intl.NumberFormat('ko-KR', {
  style: 'currency',
  currency: 'KRW'
});

const FALLBACK_IMAGE =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600" viewBox="0 0 800 600"><defs><linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" stop-color="#a5b4fc"/><stop offset="100%" stop-color="#38bdf8"/></linearGradient></defs><rect width="800" height="600" rx="32" fill="url(#g)"/><g fill="#0f172a" font-family="Pretendard, Arial, sans-serif" text-anchor="middle"><text x="400" y="270" font-size="48" font-weight="700">Curated Product</text><text x="400" y="340" font-size="24" opacity="0.75">이미지를 불러오지 못했습니다.</text></g></svg>`
  );

const handleImageFallback = (event) => {
  if (event.target.dataset.fallbackApplied === 'true') {
    return;
  }
  event.target.dataset.fallbackApplied = 'true';
  event.target.src = FALLBACK_IMAGE;
};

function randomKey() {
  return `mock-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function formatNumber(value) {
  return value.toLocaleString('ko-KR');
}

function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString('ko-KR', { hour12: false });
}

function Toast({ message, type, onClose }) {
  useEffect(() => {
    const timer = setTimeout(onClose, 5000);
    return () => clearTimeout(timer);
  }, [onClose]);

  return (
    <div className={`toast toast--${type}`}>
      <div className="toast__icon">
        {type === 'success' && '✓'}
        {type === 'error' && '✕'}
        {type === 'info' && 'ⓘ'}
      </div>
      <div className="toast__message">{message}</div>
      <button className="toast__close" onClick={onClose} aria-label="Close">
        ✕
      </button>
    </div>
  );
}

export default function App() {
  const [selectedCategoryId, setSelectedCategoryId] = useState(CATEGORIES[0].id);
  const [selectedProductId, setSelectedProductId] = useState(INITIAL_PRODUCT.id);
  const [selectedColor, setSelectedColor] = useState(INITIAL_PRODUCT.colorOptions[0]);
  const [quantity, setQuantity] = useState(1);
  const [receipt, setReceipt] = useState(null);
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState(randomKey());
  const [manualPaymentId, setManualPaymentId] = useState('');
  const [lastPaymentId, setLastPaymentId] = useState(null);
  const [refundReason, setRefundReason] = useState('');
  const [sessionDetails, setSessionDetails] = useState(null);
  const [now, setNow] = useState(() => new Date());
  const [toasts, setToasts] = useState([]);

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  const selectedCategory = useMemo(
    () => CATEGORIES.find((category) => category.id === selectedCategoryId) ?? CATEGORIES[0],
    [selectedCategoryId]
  );

  useEffect(() => {
    const defaultProduct = selectedCategory.products[0];
    setSelectedProductId(defaultProduct.id);
  }, [selectedCategory]);

  const selectedProduct = useMemo(() => {
    return (
      selectedCategory.products.find((product) => product.id === selectedProductId) ??
      selectedCategory.products[0]
    );
  }, [selectedCategory, selectedProductId]);

  useEffect(() => {
    if (!selectedProduct) {
      return;
    }
    setSelectedColor((previous) =>
      selectedProduct.colorOptions.includes(previous) ? previous : selectedProduct.colorOptions[0]
    );
    setQuantity(1);
  }, [selectedProduct]);

  const totalAmount = selectedProduct.price * quantity;
  const formattedNow = now.toLocaleString('ko-KR', { weekday: 'short', hour: '2-digit', minute: '2-digit' });

  const addToast = (message, type = 'info') => {
    const id = Date.now();
    setToasts((prev) => [...prev, { id, message, type }]);
  };

  const removeToast = (id) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  };

  const handleApiError = (error, fallbackMessage) => {
    const errorMessage = error?.response?.data?.message ?? fallbackMessage;
    setStatus({
      type: 'error',
      message: errorMessage
    });
    addToast(errorMessage, 'error');
  };

  const handleAuthorizeOnly = async () => {
    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const details = {
        productId: selectedProduct.id,
        productName: selectedProduct.name,
        color: selectedColor,
        quantity,
        unitPrice: selectedProduct.price,
        totalAmount
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
        order: details,
        authorization: authorizeResponse.data,
        capture: null,
        refund: null
      });
      setStatus({
        type: 'success',
        message: '승인 요청이 완료되었습니다. 동일 키로 재호출 시 멱등 응답이 반환됩니다.'
      });
      addToast('결제 승인이 완료되었습니다.', 'success');
    } catch (error) {
      handleApiError(error, '승인 요청 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const resolvePaymentId = () => {
    const candidate =
      manualPaymentId.trim() || (lastPaymentId != null ? String(lastPaymentId) : '');
    if (!candidate) {
      setStatus({
        type: 'error',
        message: '결제 ID를 입력한 뒤 진행해 주세요.'
      });
      return null;
    }
    if (!/^\d+$/.test(candidate)) {
      setStatus({
        type: 'error',
        message: '결제 ID는 숫자만 입력할 수 있습니다.'
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
        receipt?.order ?? {
          productId: selectedProduct.id,
          productName: selectedProduct.name,
          color: selectedColor,
          quantity,
          unitPrice: selectedProduct.price,
          totalAmount
        };

      setSessionDetails(details);
      setLastPaymentId(captureResponse.data.paymentId ?? Number(paymentId));
      setManualPaymentId(paymentId);
      setReceipt((previous) => ({
        order: details,
        authorization: previous?.authorization ?? null,
        capture: captureResponse.data,
        refund: null
      }));
      setStatus({
        type: 'success',
        message: '정산 요청이 완료되었습니다.'
      });
      addToast('정산(Capture)이 완료되었습니다.', 'success');
    } catch (error) {
      handleApiError(error, '정산 요청 중 문제가 발생했습니다.');
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
        reason: refundReason.trim() ? refundReason.trim() : 'manual-refund'
      });

      const details =
        sessionDetails ??
        receipt?.order ?? {
          productId: selectedProduct.id,
          productName: selectedProduct.name,
          color: selectedColor,
          quantity,
          unitPrice: selectedProduct.price,
          totalAmount
        };

      setSessionDetails(details);
      setLastPaymentId(refundResponse.data.paymentId ?? Number(paymentId));
      setManualPaymentId(paymentId);
      setReceipt((previous) => ({
        order: details,
        authorization: previous?.authorization ?? null,
        capture: previous?.capture ?? null,
        refund: refundResponse.data
      }));
      setStatus({
        type: 'success',
        message: '환불 요청이 완료되었습니다.'
      });
      addToast('환불(Refund)이 완료되었습니다.', 'success');
    } catch (error) {
      handleApiError(error, '환불 요청 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handlePurchase = async () => {
    setLoading(true);
    setStatus(null);

    try {
      const merchantId = 'MOCK-MERCHANT';
      const details = {
        productId: selectedProduct.id,
        productName: selectedProduct.name,
        color: selectedColor,
        quantity,
        unitPrice: selectedProduct.price,
        totalAmount
      };

      const authorizeResponse = await api.post('/payments/authorize', {
        merchantId,
        amount: totalAmount,
        currency: 'KRW',
        idempotencyKey
      });

      const paymentId = authorizeResponse.data.paymentId;

      const captureResponse = await api.post(`/payments/capture/${paymentId}`, {
        merchantId
      });

      setLastPaymentId(paymentId ?? null);
      setManualPaymentId(paymentId ? String(paymentId) : '');
      setSessionDetails(details);
      setReceipt({
        order: details,
        authorization: authorizeResponse.data,
        capture: captureResponse.data,
        refund: null
      });
      setStatus({
        type: 'success',
        message: '승인과 정산이 모두 완료되었습니다.'
      });
    } catch (error) {
      handleApiError(error, '승인 및 정산 처리 중 문제가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setReceipt(null);
    setStatus(null);
    setManualPaymentId('');
    setRefundReason('');
    setIdempotencyKey(randomKey());
    setSessionDetails(null);
    setLastPaymentId(null);
  };

  return (
    <div className="studio-app">
      <header className="studio-header">
        <div className="studio-header__brand">
          <div className="logo-dot" />
          <div>
            <p className="eyebrow">SWELITE COMMERCE LAB</p>
            <h1>프리미엄 결제 실험실</h1>
          </div>
        </div>
        <div className="studio-header__meta">
          <div className="status-indicator">
            <span className="dot" />
            Kafka Healthy
          </div>
          <time>{formattedNow}</time>
          <button type="button" className="ghost-btn" onClick={handleAuthorizeOnly} disabled={loading}>
            승인만 실행
          </button>
          <div className="avatar">OP</div>
        </div>
      </header>

      <HeroSection
        category={selectedCategory}
        selectedProduct={selectedProduct}
        totalAmount={totalAmount}
        lastPaymentId={lastPaymentId}
        formattedNow={formattedNow}
        onPurchase={handlePurchase}
        onAuthorize={handleAuthorizeOnly}
        loading={loading}
      />

      <section className="workspace">
        <div className="catalog-stack">
          <CategoryTabs
            categories={CATEGORIES}
            selectedId={selectedCategoryId}
            onSelect={setSelectedCategoryId}
          />

          <div className="product-strip">
            {selectedCategory.products.map((product) => (
              <ProductCard
                key={product.id}
                product={product}
                active={product.id === selectedProduct.id}
                onSelect={() => setSelectedProductId(product.id)}
              />
            ))}
          </div>

          <ProductDetailPanel
            product={selectedProduct}
            selectedColor={selectedColor}
            onColorChange={setSelectedColor}
            quantity={quantity}
            onQuantityChange={setQuantity}
            totalAmount={totalAmount}
          />
        </div>

        <div className="ops-stack">
          <OrderControlPanel
            idempotencyKey={idempotencyKey}
            onIdempotencyKeyChange={setIdempotencyKey}
            onRandomizeKey={() => setIdempotencyKey(randomKey())}
            manualPaymentId={manualPaymentId}
            onManualPaymentIdChange={setManualPaymentId}
            refundReason={refundReason}
            onRefundReasonChange={setRefundReason}
            onAuthorize={handleAuthorizeOnly}
            onCapture={handleCapture}
            onRefund={handleRefund}
          onPurchase={handlePurchase}
          onReset={handleReset}
          loading={loading}
          status={status}
          lastPaymentId={lastPaymentId}
          selectedProduct={selectedProduct}
          selectedColor={selectedColor}
          quantity={quantity}
          totalAmount={totalAmount}
          toasts={toasts}
          onRemoveToast={removeToast}
        />

          <ReceiptPanel receipt={receipt} />
        </div>
      </section>
    </div>
  );
}

function HeroSection({
  category,
  selectedProduct,
  totalAmount,
  lastPaymentId,
  formattedNow,
  onPurchase,
  onAuthorize,
  loading
}) {
  const stats = [
    { label: '현재 시각', value: formattedNow },
    { label: '최근 결제', value: lastPaymentId ? `#${lastPaymentId}` : '대기 중' },
    { label: '선택 상품', value: selectedProduct.name },
    { label: '결제 예정 금액', value: currencyFormatter.format(totalAmount) }
  ];

  return (
    <section className="hero-section" style={{ '--accent': category.accent }}>
      <div className="hero-copy">
        <p className="eyebrow">{category.tagline}</p>
        <h2>{category.description}</h2>
        <p className="sub">
          카드사 혜택, 멱등 처리, 정산/환불 플로우까지 한 화면에서 리허설하고 운영팀이 즉시 사용할 수 있도록 설계된
          Studio 버전 UI입니다.
        </p>

        <div className="hero-actions">
          <button type="button" className="btn primary" onClick={onPurchase} disabled={loading}>
            즉시 구매 & 정산
          </button>
          <button type="button" className="btn ghost" onClick={onAuthorize} disabled={loading}>
            승인 플로우 재현
          </button>
        </div>

        <dl className="hero-stats">
          {stats.map((stat) => (
            <div key={stat.label}>
              <dt>{stat.label}</dt>
              <dd>{stat.value}</dd>
            </div>
          ))}
        </dl>
      </div>

      <div className="hero-visual">
        <div className="hero-visual__media">
          <img src={selectedProduct.image} alt={selectedProduct.name} onError={handleImageFallback} />
          <span className="badge">{selectedProduct.promoLabel}</span>
        </div>
        <div className="hero-visual__card">
          <div>
            <span className="eyebrow">{selectedProduct.brand}</span>
            <strong>{selectedProduct.name}</strong>
          </div>
          <div>
            <span>실행 금액</span>
            <strong>{currencyFormatter.format(totalAmount)}</strong>
            <small>{selectedProduct.monthlyInstallment}</small>
          </div>
          <div className="hero-visual__meta">
            <span>{selectedProduct.fulfillment}</span>
            <span>{selectedProduct.sla}</span>
          </div>
        </div>
      </div>
    </section>
  );
}

function CategoryTabs({ categories, selectedId, onSelect }) {
  return (
    <nav className="category-tabs">
      {categories.map((category) => (
        <button
          key={category.id}
          type="button"
          className={category.id === selectedId ? 'active' : ''}
          onClick={() => onSelect(category.id)}
        >
          <span>{category.name}</span>
          <small>{category.tagline}</small>
        </button>
      ))}
    </nav>
  );
}

function ProductCard({ product, active, onSelect }) {
  return (
    <article
      className={active ? 'product-card is-active' : 'product-card'}
      onClick={onSelect}
      role="button"
      tabIndex={0}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect();
        }
      }}
    >
      <div className="product-card__media">
        <img src={product.image} alt={product.name} onError={handleImageFallback} />
        <span>{product.promoLabel}</span>
      </div>
      <div className="product-card__body">
        <div className="product-card__brand">{product.brand}</div>
        <h3>{product.name}</h3>
        <p>{product.shortDescription}</p>

        <div className="product-card__price">
          <strong>{currencyFormatter.format(product.price)}</strong>
          {product.originalPrice && (
            <span>{currencyFormatter.format(product.originalPrice)}</span>
          )}
        </div>

        <div className="product-card__meta">
          <span>리뷰 {formatNumber(product.reviews)}</span>
          <span>평점 {product.rating.toFixed(1)}</span>
        </div>
      </div>
    </article>
  );
}

function ProductDetailPanel({ product, selectedColor, onColorChange, quantity, onQuantityChange, totalAmount }) {
  return (
    <section className="detail-panel">
      <div className="detail-panel__header">
        <div>
          <span className="eyebrow">{product.brand}</span>
          <h3>{product.name}</h3>
          <p>{product.shortDescription}</p>
        </div>
        <div className="detail-panel__pricing">
          <strong>{currencyFormatter.format(product.price)}</strong>
          {product.originalPrice && <span>{currencyFormatter.format(product.originalPrice)}</span>}
          <small>{product.monthlyInstallment}</small>
        </div>
      </div>

      <div className="detail-panel__grid">
        <label>
          <span>컬러 옵션</span>
          <select value={selectedColor} onChange={(event) => onColorChange(event.target.value)}>
            {product.colorOptions.map((color) => (
              <option key={color} value={color}>
                {color}
              </option>
            ))}
          </select>
        </label>

        <label>
          <span>수량</span>
          <input
            type="number"
            min={1}
            max={5}
            value={quantity}
            onChange={(event) => {
              const value = Number(event.target.value);
              onQuantityChange(Number.isNaN(value) ? 1 : Math.min(Math.max(value, 1), 5));
            }}
          />
        </label>

        <div>
          <span>총 결제 금액</span>
          <strong>{currencyFormatter.format(totalAmount)}</strong>
        </div>

        <div>
          <span>배송 약속</span>
          <strong>{product.shipping}</strong>
        </div>
      </div>

      <ul className="detail-panel__highlights">
        {product.highlights.map((highlight) => (
          <li key={highlight}>{highlight}</li>
        ))}
      </ul>
    </section>
  );
}

function OrderControlPanel({
  idempotencyKey,
  onIdempotencyKeyChange,
  onRandomizeKey,
  manualPaymentId,
  onManualPaymentIdChange,
  refundReason,
  onRefundReasonChange,
  onAuthorize,
  onCapture,
  onRefund,
  onPurchase,
  onReset,
  loading,
  status,
  lastPaymentId,
  selectedProduct,
  selectedColor,
  quantity,
  totalAmount,
  toasts,
  onRemoveToast
}) {
  return (
    <section className="order-console">
      <header>
        <div>
          <p className="eyebrow">Payment Console</p>
          <h3>멱등 키와 결제 플로우를 한 곳에서 제어하세요.</h3>
        </div>
        <div className="order-console__summary">
          <span>{selectedProduct.name}</span>
          <strong>{currencyFormatter.format(totalAmount)}</strong>
          <small>{selectedColor} · {quantity}개 · 최근 결제 {lastPaymentId ?? '-'}</small>
        </div>
      </header>

      <div className="field-grid">
        <label>
          <span>멱등 키</span>
          <div className="field-with-action">
            <input
              type="text"
              value={idempotencyKey}
              onChange={(event) => onIdempotencyKeyChange(event.target.value)}
              disabled={loading}
            />
            <button type="button" onClick={onRandomizeKey} disabled={loading}>
              재생성
            </button>
          </div>
        </label>

        <label>
          <span>결제 ID</span>
          <input
            type="text"
            value={manualPaymentId}
            onChange={(event) => onManualPaymentIdChange(event.target.value)}
            placeholder="최근 결제 번호"
            disabled={loading}
          />
        </label>

        <label>
          <span>환불 사유</span>
          <input
            type="text"
            value={refundReason}
            onChange={(event) => onRefundReasonChange(event.target.value)}
            placeholder="예: 고객 변심, 재고 회수 등"
            disabled={loading}
          />
        </label>
      </div>

      <div className="order-console__actions">
        <button type="button" onClick={onAuthorize} disabled={loading} className={loading ? 'loading' : ''}>
          {loading ? '처리 중...' : '승인'}
        </button>
        <button type="button" onClick={onCapture} disabled={loading} className={loading ? 'loading' : ''}>
          {loading ? '처리 중...' : '정산'}
        </button>
        <button type="button" onClick={onRefund} disabled={loading} className={loading ? 'loading' : ''}>
          {loading ? '처리 중...' : '환불'}
        </button>
        <button type="button" className={`primary ${loading ? 'loading' : ''}`} onClick={onPurchase} disabled={loading}>
          {loading ? '처리 중...' : '승인 + 정산'}
        </button>
      </div>

      <footer>
        <button type="button" onClick={onReset} disabled={loading}>
          화면 초기화
        </button>
        <span>요청은 Redis/DB/Kafka 상태에 따라 실시간 반영됩니다.</span>
      </footer>

      {status && (
        <div className={status.type === 'success' ? 'status-banner success' : 'status-banner error'}>
          {status.message}
        </div>
      )}

      {!!toasts?.length && (
        <div className="toast-container">
          {toasts.map((toast) => (
            <Toast key={toast.id} message={toast.message} type={toast.type} onClose={() => onRemoveToast(toast.id)} />
          ))}
        </div>
      )}
    </section>
  );
}

function ReceiptPanel({ receipt }) {
  if (!receipt) {
    return (
      <section className="receipt-timeline empty">
        <h3>거래 타임라인</h3>
        <p>승인부터 환불까지 실행하면 해당 흐름이 타임라인으로 기록됩니다.</p>
      </section>
    );
  }

  const { order, authorization, capture, refund } = receipt;

  return (
    <section className="receipt-timeline">
      <h3>거래 타임라인</h3>

      <div className="timeline-order">
        <div>
          <span>상품</span>
          <strong>{order.productName}</strong>
          <small>{order.color} · {order.quantity}개</small>
        </div>
        <div>
          <span>총 금액</span>
          <strong>{currencyFormatter.format(order.totalAmount)}</strong>
        </div>
      </div>

      <div className="timeline-steps">
        {authorization && (
          <div className="timeline-step">
            <header>
              <span className="badge">Authorize</span>
              <time dateTime={authorization.createdAt}>{formatDateTime(authorization.createdAt)}</time>
            </header>
            <dl>
              <div>
                <dt>상태</dt>
                <dd>{authorization.status}</dd>
              </div>
              <div>
                <dt>메시지</dt>
                <dd>{authorization.message}</dd>
              </div>
              <div>
                <dt>결제 ID</dt>
                <dd>{authorization.paymentId}</dd>
              </div>
            </dl>
          </div>
        )}

        {capture && (
          <div className="timeline-step capture">
            <header>
              <span className="badge">Capture</span>
              <time dateTime={capture.createdAt}>{formatDateTime(capture.createdAt)}</time>
            </header>
            <dl>
              <div>
                <dt>상태</dt>
                <dd>{capture.status}</dd>
              </div>
              <div>
                <dt>메시지</dt>
                <dd>{capture.message}</dd>
              </div>
              <div>
                <dt>원장 기록</dt>
                <dd>
                  {capture.ledgerEntries?.map((entry) => `${entry.debitAccount} → ${entry.creditAccount}`).join(', ') ??
                    '원장 기록 없음'}
                </dd>
              </div>
            </dl>
          </div>
        )}

        {refund && (
          <div className="timeline-step refund">
            <header>
              <span className="badge">Refund</span>
              <time dateTime={refund.createdAt}>{formatDateTime(refund.createdAt)}</time>
            </header>
            <dl>
              <div>
                <dt>상태</dt>
                <dd>{refund.status}</dd>
              </div>
              <div>
                <dt>메시지</dt>
                <dd>{refund.message}</dd>
              </div>
              <div>
                <dt>원장 기록</dt>
                <dd>
                  {refund.ledgerEntries?.map((entry) => `${entry.debitAccount} → ${entry.creditAccount}`).join(', ') ??
                    '원장 기록 없음'}
                </dd>
              </div>
            </dl>
          </div>
        )}
      </div>
    </section>
  );
}
